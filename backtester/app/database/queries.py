"""
Database query functions for API endpoints.

Reuses existing QuestDBFetcher for connection management.
All queries are read-only (no writes).
"""

import psycopg2
from typing import List, Dict, Optional, Tuple
from datetime import datetime, timedelta
from app.config import get_settings

settings = get_settings()


class MarketDataQueries:
    """Query functions for market data endpoints."""

    def __init__(self):
        """Initialize database connection parameters."""
        self.host = settings.questdb_host
        self.port = settings.questdb_port
        self.user = settings.questdb_user
        self.password = settings.questdb_password
        self.database = settings.questdb_database

    def _get_connection(self):
        """
        Create database connection.

        Returns:
            psycopg2.connection: Database connection
        """
        return psycopg2.connect(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            database=self.database
        )

    def get_all_tokens(self) -> List[Dict]:
        """
        Get latest price for all tokens.

        Returns:
            List of dicts with token data
        """
        conn = None
        cursor = None

        try:
            conn = self._get_connection()
            cursor = conn.cursor()

            # Get latest tick for each symbol
            query = """
                SELECT symbol, price, volume, timestamp
                FROM (
                    SELECT symbol, price, volume, timestamp,
                           ROW_NUMBER() OVER (PARTITION BY symbol ORDER BY timestamp DESC) as rn
                    FROM ticks
                ) WHERE rn = 1
                ORDER BY symbol
            """

            cursor.execute(query)
            rows = cursor.fetchall()

            result = []
            for row in rows:
                result.append({
                    'symbol': row[0],
                    'current_price': float(row[1]),
                    'volume': int(row[2]),
                    'last_updated': row[3].isoformat() if row[3] else None
                })

            return result

        except Exception as e:
            raise Exception(f"Database query failed: {str(e)}")
        finally:
            if cursor:
                cursor.close()
            if conn:
                conn.close()

    def get_token_24h_stats(self, symbol: str) -> Optional[Dict]:
        """
        Get 24-hour statistics for a token.

        Args:
            symbol: Token symbol (e.g., 'AAPL')

        Returns:
            Dict with 24h stats or None if no data
        """
        conn = None
        cursor = None

        try:
            conn = self._get_connection()
            cursor = conn.cursor()

            # Calculate 24h ago timestamp
            time_24h_ago = (datetime.utcnow() - timedelta(hours=24)).isoformat()

            # Get current price
            current_query = """
                SELECT price, volume, timestamp
                FROM ticks
                WHERE symbol = %s
                ORDER BY timestamp DESC
                LIMIT 1
            """
            cursor.execute(current_query, (symbol,))
            current_row = cursor.fetchone()

            if not current_row:
                return None

            current_price = float(current_row[0])
            current_volume = int(current_row[1])
            last_updated = current_row[2]

            # Get 24h statistics
            stats_query = """
                SELECT
                    MIN(price) as low_24h,
                    MAX(price) as high_24h,
                    SUM(volume) as volume_24h
                FROM ticks
                WHERE symbol = %s
                  AND timestamp >= %s
            """
            cursor.execute(stats_query, (symbol, time_24h_ago))
            stats_row = cursor.fetchone()

            # Get price from 24h ago for change calculation
            open_query = """
                SELECT price
                FROM ticks
                WHERE symbol = %s
                  AND timestamp >= %s
                ORDER BY timestamp ASC
                LIMIT 1
            """
            cursor.execute(open_query, (symbol, time_24h_ago))
            open_row = cursor.fetchone()

            open_24h = float(open_row[0]) if open_row else current_price
            change_24h_pct = ((current_price - open_24h) / open_24h * 100) if open_24h > 0 else 0.0

            return {
                'symbol': symbol,
                'current_price': current_price,
                'change_24h_pct': round(change_24h_pct, 2),
                'high_24h': float(stats_row[1]) if stats_row and stats_row[1] else current_price,
                'low_24h': float(stats_row[0]) if stats_row and stats_row[0] else current_price,
                'volume_24h': int(stats_row[2]) if stats_row and stats_row[2] else current_volume,
                'open_24h': open_24h,
                'last_updated': last_updated.isoformat() if last_updated else None
            }

        except Exception as e:
            raise Exception(f"Database query failed: {str(e)}")
        finally:
            if cursor:
                cursor.close()
            if conn:
                conn.close()

    def get_candles(
        self,
        symbol: str,
        interval: str = "1m",
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        limit: int = 1000
    ) -> List[Dict]:
        """
        Get historical candles for a symbol.

        Args:
            symbol: Token symbol
            interval: Candle interval (only "1m" supported currently)
            start_date: Start date ISO format (optional)
            end_date: End date ISO format (optional)
            limit: Maximum candles to return

        Returns:
            List of candle dicts
        """
        conn = None
        cursor = None

        try:
            conn = self._get_connection()
            cursor = conn.cursor()

            # Build query based on filters
            query = """
                SELECT timestamp, open, high, low, close, volume
                FROM candles_1m
                WHERE symbol = %s
            """
            params = [symbol]

            if start_date:
                query += " AND timestamp >= %s"
                params.append(start_date)

            if end_date:
                query += " AND timestamp < %s"
                params.append(end_date)

            query += " ORDER BY timestamp DESC LIMIT %s"
            params.append(limit)

            cursor.execute(query, params)
            rows = cursor.fetchall()

            candles = []
            for row in rows:
                candles.append({
                    'timestamp': row[0].isoformat() if row[0] else None,
                    'open': float(row[1]),
                    'high': float(row[2]),
                    'low': float(row[3]),
                    'close': float(row[4]),
                    'volume': int(row[5])
                })

            # Reverse to get chronological order
            candles.reverse()

            return candles

        except Exception as e:
            raise Exception(f"Database query failed: {str(e)}")
        finally:
            if cursor:
                cursor.close()
            if conn:
                conn.close()

    def get_latest_tick(self, symbol: str) -> Optional[Dict]:
        """
        Get the most recent tick for a symbol.

        Args:
            symbol: Token symbol

        Returns:
            Dict with tick data or None
        """
        conn = None
        cursor = None

        try:
            conn = self._get_connection()
            cursor = conn.cursor()

            query = """
                SELECT price, volume, timestamp
                FROM ticks
                WHERE symbol = %s
                ORDER BY timestamp DESC
                LIMIT 1
            """

            cursor.execute(query, (symbol,))
            row = cursor.fetchone()

            if not row:
                return None

            return {
                'symbol': symbol,
                'price': float(row[0]),
                'volume': int(row[1]),
                'timestamp': row[2].isoformat() if row[2] else None
            }

        except Exception as e:
            raise Exception(f"Database query failed: {str(e)}")
        finally:
            if cursor:
                cursor.close()
            if conn:
                conn.close()


class SignalQueries:
    """Query functions for trading signals endpoints."""

    def __init__(self):
        """Initialize database connection parameters."""
        self.host = settings.questdb_host
        self.port = settings.questdb_port
        self.user = settings.questdb_user
        self.password = settings.questdb_password
        self.database = settings.questdb_database

    def _get_connection(self):
        """Create database connection."""
        return psycopg2.connect(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            database=self.database
        )

    def get_recent_signals(
        self,
        limit: int = 50,
        action: Optional[str] = None,
        symbol: Optional[str] = None
    ) -> List[Dict]:
        """
        Get recent trading signals.

        Args:
            limit: Maximum signals to return
            action: Filter by action (BUY, SELL, HOLD)
            symbol: Filter by symbol

        Returns:
            List of signal dicts
        """
        conn = None
        cursor = None

        try:
            conn = self._get_connection()
            cursor = conn.cursor()

            query = """
                SELECT timestamp, symbol, strategy_name, action, confidence
                FROM signals
                WHERE 1=1
            """
            params = []

            if action:
                query += " AND action = %s"
                params.append(action)

            if symbol:
                query += " AND symbol = %s"
                params.append(symbol)

            query += " ORDER BY timestamp DESC LIMIT %s"
            params.append(limit)

            cursor.execute(query, params)
            rows = cursor.fetchall()

            signals = []
            for row in rows:
                signals.append({
                    'timestamp': row[0].isoformat() if row[0] else None,
                    'symbol': row[1],
                    'strategy_name': row[2],
                    'action': row[3],
                    'confidence': float(row[4]),
                    'price': 0.0  # Price not stored in signals table
                })

            return signals

        except Exception as e:
            raise Exception(f"Database query failed: {str(e)}")
        finally:
            if cursor:
                cursor.close()
            if conn:
                conn.close()

    def get_signal_statistics(self, period_hours: int = 24) -> Dict:
        """
        Get aggregate signal statistics.

        Args:
            period_hours: Time period in hours (default 24)

        Returns:
            Dict with statistics
        """
        conn = None
        cursor = None

        try:
            conn = self._get_connection()
            cursor = conn.cursor()

            time_ago = (datetime.utcnow() - timedelta(hours=period_hours)).isoformat()

            # Total signals
            total_query = """
                SELECT COUNT(*) FROM signals
                WHERE timestamp >= %s
            """
            cursor.execute(total_query, (time_ago,))
            total_signals = cursor.fetchone()[0]

            # By action
            action_query = """
                SELECT action, COUNT(*) as count
                FROM signals
                WHERE timestamp >= %s
                GROUP BY action
            """
            cursor.execute(action_query, (time_ago,))
            by_action = {row[0]: int(row[1]) for row in cursor.fetchall()}

            # By strategy
            strategy_query = """
                SELECT strategy_name, COUNT(*) as count
                FROM signals
                WHERE timestamp >= %s
                GROUP BY strategy_name
            """
            cursor.execute(strategy_query, (time_ago,))
            by_strategy = {row[0]: int(row[1]) for row in cursor.fetchall()}

            # By symbol
            symbol_query = """
                SELECT symbol, COUNT(*) as count
                FROM signals
                WHERE timestamp >= %s
                GROUP BY symbol
            """
            cursor.execute(symbol_query, (time_ago,))
            by_symbol = {row[0]: int(row[1]) for row in cursor.fetchall()}

            # Average confidence
            avg_conf_query = """
                SELECT AVG(confidence) FROM signals
                WHERE timestamp >= %s
            """
            cursor.execute(avg_conf_query, (time_ago,))
            avg_confidence = cursor.fetchone()[0]

            return {
                'period_hours': period_hours,
                'total_signals': int(total_signals),
                'by_action': by_action,
                'by_strategy': by_strategy,
                'by_symbol': by_symbol,
                'avg_confidence': round(float(avg_confidence), 4) if avg_confidence else 0.0
            }

        except Exception as e:
            raise Exception(f"Database query failed: {str(e)}")
        finally:
            if cursor:
                cursor.close()
            if conn:
                conn.close()

    def get_signals_by_strategy(
        self,
        strategy_name: str,
        symbol: Optional[str] = None,
        start_date: Optional[str] = None,
        end_date: Optional[str] = None,
        limit: int = 100
    ) -> List[Dict]:
        """
        Get signals for a specific strategy.

        Args:
            strategy_name: Strategy name
            symbol: Filter by symbol (optional)
            start_date: Start date (optional)
            end_date: End date (optional)
            limit: Maximum signals

        Returns:
            List of signal dicts
        """
        conn = None
        cursor = None

        try:
            conn = self._get_connection()
            cursor = conn.cursor()

            query = """
                SELECT timestamp, symbol, action, confidence
                FROM signals
                WHERE strategy_name = %s
            """
            params = [strategy_name]

            if symbol:
                query += " AND symbol = %s"
                params.append(symbol)

            if start_date:
                query += " AND timestamp >= %s"
                params.append(start_date)

            if end_date:
                query += " AND timestamp < %s"
                params.append(end_date)

            query += " ORDER BY timestamp DESC LIMIT %s"
            params.append(limit)

            cursor.execute(query, params)
            rows = cursor.fetchall()

            signals = []
            for row in rows:
                signals.append({
                    'timestamp': row[0].isoformat() if row[0] else None,
                    'symbol': row[1],
                    'action': row[2],
                    'confidence': float(row[3]),
                    'price': 0.0  # Price not stored in signals table
                })

            return signals

        except Exception as e:
            raise Exception(f"Database query failed: {str(e)}")
        finally:
            if cursor:
                cursor.close()
            if conn:
                conn.close()
