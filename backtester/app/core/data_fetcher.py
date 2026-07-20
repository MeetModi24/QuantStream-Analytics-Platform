import psycopg2
import pandas as pd
from typing import Optional
from datetime import datetime
from app.config import get_settings

settings = get_settings()


class QuestDBFetcher:
    """
    Fetches historical price data from QuestDB for backtesting.
    
    Responsibilities:
    1. Connect to QuestDB using connection pooling
    2. Fetch tick data for a given symbol and date range
    3. Convert to Pandas DataFrame with DatetimeIndex
    4. Resample ticks to OHLC candles (if needed)
    """
    
    def __init__(self):
        """Initialize QuestDB connection parameters."""
        self.host = settings.questdb_host
        self.port = settings.questdb_port
        self.user = settings.questdb_user
        self.password = settings.questdb_password
        self.database = settings.questdb_database
        
    def _get_connection(self):
        """
        Create a new database connection.
        
        Uses psycopg2 (PostgreSQL wire protocol).
        QuestDB supports PostgreSQL wire protocol on port 8812.
        
        Returns:
            psycopg2.connection: Database connection
            
        Raises:
            psycopg2.OperationalError: If connection fails
        """
        return psycopg2.connect(
            host=self.host,
            port=self.port,
            user=self.user,
            password=self.password,
            database=self.database
        )
    
    def fetch_ticks(
        self,
        symbol: str,
        start_date: str,
        end_date: str
    ) -> pd.DataFrame:
        """
        Fetch raw tick data from QuestDB.
        
        Args:
            symbol: Trading symbol (e.g., 'AAPL', 'BTC')
            start_date: Start date in ISO format (e.g., '2026-06-19')
            end_date: End date in ISO format (e.g., '2026-07-19')
            
        Returns:
            pd.DataFrame: DataFrame with columns [price, volume] and DatetimeIndex
            
        Example:
            >>> fetcher = QuestDBFetcher()
            >>> df = fetcher.fetch_ticks('AAPL', '2026-07-01', '2026-07-02')
            >>> print(df.head())
            
                                     price      volume
            timestamp                                  
            2026-07-01 00:00:00  180.50  50561269
            2026-07-01 00:00:01  180.52  50562180
            2026-07-01 00:00:02  180.48  50560145
        """
        conn = None
        cursor = None
        
        try:
            # Connect to QuestDB
            conn = self._get_connection()
            cursor = conn.cursor()
            
            # SQL query to fetch ticks
            # Note: QuestDB handles timestamp comparisons natively (no casting needed)
            query = """
                SELECT timestamp, price, volume
                FROM ticks
                WHERE symbol = %s
                  AND timestamp >= %s
                  AND timestamp < %s
                ORDER BY timestamp ASC
            """
            
            # Execute query
            cursor.execute(query, (symbol, start_date, end_date))
            
            # Fetch all rows
            rows = cursor.fetchall()
            
            # Handle empty result
            if not rows:
                print(f"⚠️  No data found for {symbol} between {start_date} and {end_date}")
                # Return empty DataFrame with correct structure
                return pd.DataFrame(columns=['price', 'volume'], index=pd.DatetimeIndex([], name='timestamp'))
            
            # Convert to DataFrame
            df = pd.DataFrame(rows, columns=['timestamp', 'price', 'volume'])
            
            # Convert timestamp to datetime
            df['timestamp'] = pd.to_datetime(df['timestamp'])
            
            # Set timestamp as index
            df.set_index('timestamp', inplace=True)
            
            print(f"✅ Fetched {len(df)} ticks for {symbol} ({start_date} to {end_date})")
            
            return df
            
        except psycopg2.OperationalError as e:
            print(f"❌ Database connection error: {e}")
            print("Troubleshooting:")
            print("1. Ensure QuestDB is running: docker ps | grep questdb")
            print("2. Check QuestDB logs: docker logs questdb")
            print("3. Verify port 8812 is accessible: nc -zv localhost 8812")
            raise
            
        except Exception as e:
            print(f"❌ Error fetching ticks: {e}")
            raise
            
        finally:
            # Close cursor and connection
            if cursor:
                cursor.close()
            if conn:
                conn.close()
    
    def resample_to_ohlc(
        self,
        df: pd.DataFrame,
        frequency: str = '1T'
    ) -> pd.DataFrame:
        """
        Resample tick data to OHLC (Open, High, Low, Close) candles.
        
        Args:
            df: DataFrame with tick data (must have 'price' and 'volume' columns)
            frequency: Resampling frequency (Pandas offset string)
                      - '1T' or '1min' = 1 minute
                      - '5T' or '5min' = 5 minutes
                      - '15T' or '15min' = 15 minutes
                      - '1H' = 1 hour
                      - '1D' = 1 day
                      
        Returns:
            pd.DataFrame: OHLC DataFrame with columns [open, high, low, close, volume]
            
        Example:
            >>> # 86,400 tick rows (1 tick/sec for 1 day)
            >>> ticks_df = fetcher.fetch_ticks('AAPL', '2026-07-01', '2026-07-02')
            >>> 
            >>> # Resample to 1-minute candles (1,440 rows)
            >>> candles_df = fetcher.resample_to_ohlc(ticks_df, '1T')
            >>> 
            >>> print(candles_df.head())
            
                                open    high     low   close    volume
            timestamp                                                  
            2026-07-01 00:00:00  180.50  180.55  180.45  180.52  3033762
            2026-07-01 00:01:00  180.52  180.58  180.50  180.55  3045123
            2026-07-01 00:02:00  180.55  180.60  180.53  180.58  3021456
        """
        if df.empty:
            # Return empty OHLC DataFrame
            return pd.DataFrame(columns=['open', 'high', 'low', 'close', 'volume'], 
                              index=pd.DatetimeIndex([], name='timestamp'))
        
        # Ensure index is DatetimeIndex
        if not isinstance(df.index, pd.DatetimeIndex):
            raise ValueError("DataFrame index must be DatetimeIndex (set timestamp as index)")
        
        # Resample and aggregate
        ohlc = df['price'].resample(frequency).agg(['first', 'max', 'min', 'last'])
        volume = df['volume'].resample(frequency).sum()
        
        # Rename columns to OHLC format
        ohlc.columns = ['open', 'high', 'low', 'close']
        
        # Combine OHLC and volume
        result = pd.concat([ohlc, volume], axis=1)
        
        # Drop rows where no data exists (all NaN)
        result.dropna(inplace=True)
        
        print(f"✅ Resampled {len(df)} ticks to {len(result)} candles (frequency: {frequency})")
        
        return result
    
    def fetch_candles(
        self,
        symbol: str,
        start_date: str,
        end_date: str,
        frequency: str = '1T'
    ) -> pd.DataFrame:
        """
        Convenience method: Fetch ticks and resample to OHLC in one call.
        
        Args:
            symbol: Trading symbol
            start_date: Start date (ISO format)
            end_date: End date (ISO format)
            frequency: Resampling frequency (default: '1T' = 1 minute)
            
        Returns:
            pd.DataFrame: OHLC DataFrame
            
        Example:
            >>> fetcher = QuestDBFetcher()
            >>> candles = fetcher.fetch_candles('AAPL', '2026-07-01', '2026-07-02', '1H')
            >>> # Returns 24 hourly candles
        """
        # Fetch ticks
        ticks_df = self.fetch_ticks(symbol, start_date, end_date)
        
        # Resample to OHLC
        candles_df = self.resample_to_ohlc(ticks_df, frequency)
        
        return candles_df