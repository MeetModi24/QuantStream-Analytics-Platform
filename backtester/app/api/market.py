"""
Market Data API Endpoints

Provides historical and current market data for frontend.
All data fetched from QuestDB (read-only).
"""

from fastapi import APIRouter, HTTPException, Query
from typing import Optional
from datetime import datetime

from app.database.queries import MarketDataQueries
from app.models.api_models import (
    TokenListResponse,
    TokenInfo,
    TokenDetail,
    CandlesResponse,
    Candle,
    TickResponse
)

router = APIRouter(prefix="/api/v1", tags=["Market Data"])

# Initialize query handler
market_queries = MarketDataQueries()


@router.get("/tokens", response_model=TokenListResponse)
async def list_tokens():
    """
    Get list of all tokens with latest prices.

    Frontend should poll this endpoint every 2 seconds for updates.

    Returns:
        List of tokens with current prices and volumes

    Example:
        GET /api/v1/tokens

        Response:
        {
            "tokens": [
                {
                    "symbol": "AAPL",
                    "current_price": 180.50,
                    "volume": 50561269,
                    "last_updated": "2026-07-20T11:15:30.123Z"
                }
            ],
            "total": 10,
            "last_update": "2026-07-20T11:15:30.123Z"
        }
    """
    try:
        tokens_data = market_queries.get_all_tokens()

        tokens = [TokenInfo(**token) for token in tokens_data]

        return TokenListResponse(
            tokens=tokens,
            total=len(tokens),
            last_update=datetime.utcnow().isoformat() + "Z"
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch tokens: {str(e)}")


@router.get("/tokens/{symbol}", response_model=TokenDetail)
async def get_token_detail(symbol: str):
    """
    Get detailed information for a specific token.

    Includes 24-hour statistics (high, low, volume, change percentage).

    Args:
        symbol: Token symbol (e.g., 'AAPL', 'BTC')

    Returns:
        Detailed token information with 24h stats

    Example:
        GET /api/v1/tokens/AAPL

        Response:
        {
            "symbol": "AAPL",
            "current_price": 180.50,
            "change_24h_pct": 2.3,
            "high_24h": 182.30,
            "low_24h": 177.20,
            "volume_24h": 45200000,
            "open_24h": 176.50,
            "last_updated": "2026-07-20T11:15:30Z"
        }
    """
    try:
        token_data = market_queries.get_token_24h_stats(symbol.upper())

        if not token_data:
            raise HTTPException(status_code=404, detail=f"Token '{symbol}' not found")

        return TokenDetail(**token_data)

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch token details: {str(e)}")


@router.get("/tokens/{symbol}/candles", response_model=CandlesResponse)
async def get_candles(
    symbol: str,
    interval: str = Query("1m", description="Candle interval (only '1m' supported)"),
    start_date: Optional[str] = Query(None, description="Start date ISO format"),
    end_date: Optional[str] = Query(None, description="End date ISO format"),
    limit: int = Query(1000, ge=1, le=5000, description="Maximum candles to return")
):
    """
    Get historical OHLC candles for a token.

    Used for candlestick charts in frontend.

    Args:
        symbol: Token symbol
        interval: Candle interval (currently only "1m" supported)
        start_date: Optional start date in ISO format
        end_date: Optional end date in ISO format
        limit: Maximum number of candles (1-5000, default 1000)

    Returns:
        List of OHLC candles in chronological order

    Example:
        GET /api/v1/tokens/AAPL/candles?limit=100

        Response:
        {
            "symbol": "AAPL",
            "interval": "1m",
            "candles": [
                {
                    "timestamp": "2026-07-20T11:00:00Z",
                    "open": 180.20,
                    "high": 180.60,
                    "low": 180.10,
                    "close": 180.50,
                    "volume": 125000
                }
            ],
            "count": 100
        }
    """
    try:
        if interval != "1m":
            raise HTTPException(
                status_code=400,
                detail="Only '1m' interval is currently supported"
            )

        candles_data = market_queries.get_candles(
            symbol=symbol.upper(),
            interval=interval,
            start_date=start_date,
            end_date=end_date,
            limit=limit
        )

        candles = [Candle(**candle) for candle in candles_data]

        return CandlesResponse(
            symbol=symbol.upper(),
            interval=interval,
            candles=candles,
            count=len(candles)
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch candles: {str(e)}")


@router.get("/tokens/{symbol}/tick", response_model=TickResponse)
async def get_latest_tick(symbol: str):
    """
    Get the most recent tick (price update) for a token.

    Provides sub-second granularity price data.
    Frontend can poll this for real-time price updates.

    Args:
        symbol: Token symbol

    Returns:
        Latest tick with price, volume, timestamp

    Example:
        GET /api/v1/tokens/AAPL/tick

        Response:
        {
            "symbol": "AAPL",
            "price": 180.50,
            "volume": 1000,
            "timestamp": "2026-07-20T11:15:30.458Z"
        }
    """
    try:
        tick_data = market_queries.get_latest_tick(symbol.upper())

        if not tick_data:
            raise HTTPException(status_code=404, detail=f"No data found for token '{symbol}'")

        return TickResponse(**tick_data)

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch tick: {str(e)}")


@router.get("/market/overview")
async def get_market_overview():
    """
    Get overall market summary statistics.

    Future endpoint - placeholder for now.
    Could include: total volume, number of gainers/losers, most active, etc.

    Returns:
        Market overview statistics
    """
    # Placeholder - will implement if needed
    return {
        "status": "not_implemented",
        "message": "Market overview endpoint is not yet implemented"
    }
