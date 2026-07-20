"""
Trading Signals API Endpoints

Provides access to generated trading signals and their statistics.
"""

from fastapi import APIRouter, HTTPException, Query
from typing import Optional
from datetime import datetime

from app.database.queries import SignalQueries
from app.models.api_models import (
    SignalListResponse,
    SignalInfo,
    SignalStatisticsResponse,
    SignalStatistics
)

router = APIRouter(prefix="/api/v1/signals", tags=["Trading Signals"])

# Initialize query handler
signal_queries = SignalQueries()


@router.get("/recent", response_model=SignalListResponse)
async def get_recent_signals(
    limit: int = Query(50, ge=1, le=500, description="Maximum signals to return"),
    action: Optional[str] = Query(None, description="Filter by action (BUY, SELL, HOLD)"),
    symbol: Optional[str] = Query(None, description="Filter by symbol")
):
    """
    Get recent trading signals across all strategies.

    Frontend can poll this endpoint to display latest signals.

    Args:
        limit: Maximum signals to return (1-500, default 50)
        action: Optional filter by action type
        symbol: Optional filter by symbol

    Returns:
        List of recent signals ordered by timestamp descending

    Example:
        GET /api/v1/signals/recent?limit=20&action=BUY

        Response:
        {
            "signals": [
                {
                    "timestamp": "2026-07-20T11:15:30Z",
                    "symbol": "AAPL",
                    "strategy_name": "rsi_strategy",
                    "action": "BUY",
                    "price": 180.50,
                    "confidence": 0.85
                }
            ],
            "count": 20,
            "last_updated": "2026-07-20T11:15:35Z"
        }
    """
    try:
        if action and action.upper() not in ['BUY', 'SELL', 'HOLD']:
            raise HTTPException(
                status_code=400,
                detail="Action must be one of: BUY, SELL, HOLD"
            )

        signals_data = signal_queries.get_recent_signals(
            limit=limit,
            action=action.upper() if action else None,
            symbol=symbol.upper() if symbol else None
        )

        signals = [SignalInfo(**signal) for signal in signals_data]

        return SignalListResponse(
            signals=signals,
            count=len(signals),
            last_updated=datetime.utcnow().isoformat() + "Z"
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch signals: {str(e)}")


@router.get("/symbol/{symbol}", response_model=SignalListResponse)
async def get_signals_by_symbol(
    symbol: str,
    limit: int = Query(100, ge=1, le=500, description="Maximum signals to return")
):
    """
    Get all recent signals for a specific symbol.

    Useful for symbol-specific signal history views.

    Args:
        symbol: Token symbol
        limit: Maximum signals (1-500, default 100)

    Returns:
        List of signals for the symbol

    Example:
        GET /api/v1/signals/symbol/AAPL?limit=50

        Response:
        {
            "signals": [
                {
                    "timestamp": "2026-07-20T11:15:30Z",
                    "symbol": "AAPL",
                    "strategy_name": "momentum_strategy",
                    "action": "BUY",
                    "price": 180.50,
                    "confidence": 0.78
                }
            ],
            "count": 50,
            "last_updated": "2026-07-20T11:15:35Z"
        }
    """
    try:
        signals_data = signal_queries.get_recent_signals(
            limit=limit,
            symbol=symbol.upper()
        )

        signals = [SignalInfo(**signal) for signal in signals_data]

        return SignalListResponse(
            signals=signals,
            count=len(signals),
            last_updated=datetime.utcnow().isoformat() + "Z"
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch signals: {str(e)}")


@router.get("/statistics", response_model=SignalStatisticsResponse)
async def get_signal_statistics(
    period_hours: int = Query(24, ge=1, le=168, description="Time period in hours (max 7 days)")
):
    """
    Get aggregate signal statistics over a time period.

    Provides distribution by action, strategy, and symbol.

    Args:
        period_hours: Time period in hours (1-168, default 24)

    Returns:
        Aggregate statistics

    Example:
        GET /api/v1/signals/statistics?period_hours=24

        Response:
        {
            "statistics": {
                "period_hours": 24,
                "total_signals": 1250,
                "by_action": {
                    "BUY": 450,
                    "SELL": 380,
                    "HOLD": 420
                },
                "by_strategy": {
                    "rsi_strategy": 300,
                    "momentum_strategy": 280,
                    ...
                },
                "by_symbol": {
                    "AAPL": 125,
                    "GOOGL": 118,
                    ...
                },
                "avg_confidence": 0.7234
            }
        }
    """
    try:
        stats_data = signal_queries.get_signal_statistics(period_hours=period_hours)

        statistics = SignalStatistics(**stats_data)

        return SignalStatisticsResponse(statistics=statistics)

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch statistics: {str(e)}")
