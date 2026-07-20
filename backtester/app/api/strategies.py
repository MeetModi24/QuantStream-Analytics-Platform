"""
Strategy API Endpoints

Provides strategy information, performance metrics, and leaderboard.
Strategy metadata comes from code, performance is computed from signals/backtest results.
"""

from fastapi import APIRouter, HTTPException, Query
from typing import Optional
from datetime import datetime

from app.database.queries import SignalQueries
from app.models.api_models import (
    StrategyListResponse,
    StrategyInfo,
    StrategyDetail,
    StrategyStatistics,
    LeaderboardResponse,
    LeaderboardEntry,
    PerformanceResponse,
    PerformanceMetrics,
    SignalListResponse,
    SignalInfo
)

router = APIRouter(prefix="/api/v1/strategies", tags=["Strategies"])

# Initialize query handler
signal_queries = SignalQueries()

# Static strategy metadata (from strategy-engine code)
STRATEGY_METADATA = {
    "rsi_strategy": {
        "display_name": "RSI Mean Reversion",
        "type": "technical",
        "description": "Identifies oversold (RSI < 30) and overbought (RSI > 70) conditions using 14-period RSI",
        "parameters": {"rsi_period": 14, "oversold": 30, "overbought": 70},
        "active": True
    },
    "moving_average_crossover": {
        "display_name": "Moving Average Crossover",
        "type": "technical",
        "description": "Generates signals when fast MA (10) crosses slow MA (30)",
        "parameters": {"fast_period": 10, "slow_period": 30},
        "active": True
    },
    "momentum_strategy": {
        "display_name": "Momentum Strategy",
        "type": "technical",
        "description": "Buys assets with positive 20-period momentum, sells negative momentum",
        "parameters": {"period": 20},
        "active": True
    },
    "bollinger_bands": {
        "display_name": "Bollinger Bands",
        "type": "technical",
        "description": "Mean reversion strategy using 20-period Bollinger Bands with 2 std deviations",
        "parameters": {"period": 20, "std_dev": 2},
        "active": True
    },
    "macd_strategy": {
        "display_name": "MACD Signal",
        "type": "technical",
        "description": "MACD line crosses signal line (12, 26, 9 parameters)",
        "parameters": {"fast": 12, "slow": 26, "signal": 9},
        "active": True
    },
    "volume_breakout": {
        "display_name": "Volume Breakout",
        "type": "technical",
        "description": "Identifies volume spikes above 2x average with price momentum",
        "parameters": {"volume_threshold": 2.0, "lookback": 20},
        "active": True
    },
    "mean_reversion": {
        "display_name": "Z-Score Mean Reversion",
        "type": "statistical",
        "description": "Trades when price deviates beyond ±2 standard deviations from 20-day mean",
        "parameters": {"period": 20, "z_threshold": 2.0},
        "active": True
    },
    "trend_following": {
        "display_name": "ADX Trend Following",
        "type": "technical",
        "description": "Follows strong trends identified by ADX > 25 with directional movement",
        "parameters": {"adx_period": 14, "adx_threshold": 25},
        "active": True
    },
    "pairs_trading": {
        "display_name": "Pairs Trading",
        "type": "statistical",
        "description": "Statistical arbitrage using cointegrated pairs with z-score threshold",
        "parameters": {"lookback": 60, "entry_z": 2.0, "exit_z": 0.5},
        "active": True
    },
    "breakout_strategy": {
        "display_name": "Support/Resistance Breakout",
        "type": "technical",
        "description": "Trades breakouts above 20-day high or below 20-day low",
        "parameters": {"lookback": 20},
        "active": True
    }
}


@router.get("", response_model=StrategyListResponse)
async def list_strategies():
    """
    Get list of all available strategies with metadata.

    Returns static strategy information (name, type, parameters).

    Returns:
        List of all strategies

    Example:
        GET /api/v1/strategies

        Response:
        {
            "strategies": [
                {
                    "name": "rsi_strategy",
                    "display_name": "RSI Mean Reversion",
                    "type": "technical",
                    "description": "Identifies oversold/overbought conditions...",
                    "parameters": {"rsi_period": 14, ...},
                    "active": true
                }
            ],
            "total": 10
        }
    """
    try:
        strategies = [
            StrategyInfo(name=name, **meta)
            for name, meta in STRATEGY_METADATA.items()
        ]

        return StrategyListResponse(
            strategies=strategies,
            total=len(strategies)
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch strategies: {str(e)}")


@router.get("/{name}", response_model=StrategyDetail)
async def get_strategy_detail(name: str):
    """
    Get detailed information for a specific strategy.

    Includes metadata and recent 24h execution statistics.

    Args:
        name: Strategy name (e.g., 'rsi_strategy')

    Returns:
        Detailed strategy information

    Example:
        GET /api/v1/strategies/rsi_strategy

        Response:
        {
            "name": "rsi_strategy",
            "display_name": "RSI Mean Reversion",
            "type": "technical",
            "description": "...",
            "parameters": {...},
            "active": true,
            "statistics": {
                "total_signals_24h": 45,
                "buy_signals_24h": 18,
                "sell_signals_24h": 15,
                "hold_signals_24h": 12,
                "avg_confidence": 0.78
            }
        }
    """
    try:
        if name not in STRATEGY_METADATA:
            raise HTTPException(status_code=404, detail=f"Strategy '{name}' not found")

        meta = STRATEGY_METADATA[name]

        # Get 24h statistics from signals table
        stats_data = signal_queries.get_signal_statistics(period_hours=24)

        strategy_signal_count = stats_data['by_strategy'].get(name, 0)

        # Get breakdown by action for this strategy
        signals = signal_queries.get_signals_by_strategy(
            strategy_name=name,
            limit=1000
        )

        buy_count = sum(1 for s in signals if s.get('action') == 'BUY')
        sell_count = sum(1 for s in signals if s.get('action') == 'SELL')
        hold_count = sum(1 for s in signals if s.get('action') == 'HOLD')

        avg_conf = (
            sum(s.get('confidence', 0) for s in signals) / len(signals)
            if signals else 0.0
        )

        statistics = StrategyStatistics(
            total_signals_24h=strategy_signal_count,
            buy_signals_24h=buy_count,
            sell_signals_24h=sell_count,
            hold_signals_24h=hold_count,
            avg_confidence=round(avg_conf, 4)
        )

        return StrategyDetail(
            name=name,
            **meta,
            statistics=statistics
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch strategy details: {str(e)}")


@router.get("/leaderboard", response_model=LeaderboardResponse)
async def get_leaderboard(
    sort_by: str = Query("total_return_pct", description="Sort field"),
    period: str = Query("all", description="Time period (all, 30d, 7d, 24h)")
):
    """
    Get strategy performance leaderboard.

    PLACEHOLDER: Currently returns mock data.
    Future: compute from backtest_results table.

    Args:
        sort_by: Field to sort by (total_return_pct, sharpe_ratio, win_rate_pct)
        period: Time period for metrics

    Returns:
        Ranked list of strategies by performance

    Example:
        GET /api/v1/strategies/leaderboard?sort_by=sharpe_ratio

        Response:
        {
            "leaderboard": [
                {
                    "rank": 1,
                    "strategy_name": "momentum_strategy",
                    "display_name": "Momentum Strategy",
                    "total_return_pct": 15.3,
                    "sharpe_ratio": 1.85,
                    "win_rate_pct": 62.5,
                    "max_drawdown_pct": -8.2,
                    "total_signals": 450,
                    "profitable_trades": 281
                }
            ],
            "period": "all",
            "sorted_by": "sharpe_ratio",
            "total_strategies": 10
        }
    """
    # PLACEHOLDER - return empty leaderboard for now
    return LeaderboardResponse(
        leaderboard=[],
        period=period,
        sorted_by=sort_by,
        total_strategies=len(STRATEGY_METADATA)
    )


@router.get("/{name}/performance", response_model=PerformanceResponse)
async def get_strategy_performance(
    name: str,
    period: str = Query("all", description="Time period (all, 30d, 7d, 24h)")
):
    """
    Get detailed performance metrics for a strategy.

    PLACEHOLDER: Currently returns mock data.
    Future: compute from backtest_results table and actual trades.

    Args:
        name: Strategy name
        period: Time period for metrics

    Returns:
        Performance metrics and equity curve

    Example:
        GET /api/v1/strategies/rsi_strategy/performance

        Response:
        {
            "strategy_name": "rsi_strategy",
            "display_name": "RSI Mean Reversion",
            "period": "all",
            "metrics": {
                "total_return_pct": 12.5,
                "sharpe_ratio": 1.45,
                "win_rate_pct": 58.3,
                ...
            },
            "equity_curve": [],
            "by_symbol": {}
        }
    """
    try:
        if name not in STRATEGY_METADATA:
            raise HTTPException(status_code=404, detail=f"Strategy '{name}' not found")

        meta = STRATEGY_METADATA[name]

        # PLACEHOLDER - return empty metrics for now
        metrics = PerformanceMetrics(
            total_return_pct=0.0,
            sharpe_ratio=0.0,
            win_rate_pct=0.0,
            max_drawdown_pct=0.0,
            avg_win_pct=0.0,
            avg_loss_pct=0.0,
            risk_reward_ratio=0.0,
            total_signals=0,
            profitable_trades=0,
            losing_trades=0
        )

        return PerformanceResponse(
            strategy_name=name,
            display_name=meta["display_name"],
            period=period,
            metrics=metrics,
            equity_curve=[],
            by_symbol={}
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch performance: {str(e)}")


@router.get("/{name}/signals", response_model=SignalListResponse)
async def get_strategy_signals(
    name: str,
    symbol: Optional[str] = Query(None, description="Filter by symbol"),
    start_date: Optional[str] = Query(None, description="Start date ISO format"),
    end_date: Optional[str] = Query(None, description="End date ISO format"),
    limit: int = Query(100, ge=1, le=500, description="Maximum signals")
):
    """
    Get signals generated by a specific strategy.

    Args:
        name: Strategy name
        symbol: Optional symbol filter
        start_date: Optional start date
        end_date: Optional end date
        limit: Maximum signals (1-500)

    Returns:
        List of signals from the strategy

    Example:
        GET /api/v1/strategies/rsi_strategy/signals?symbol=AAPL&limit=50

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
            "count": 50,
            "last_updated": "2026-07-20T11:15:35Z"
        }
    """
    try:
        if name not in STRATEGY_METADATA:
            raise HTTPException(status_code=404, detail=f"Strategy '{name}' not found")

        signals_data = signal_queries.get_signals_by_strategy(
            strategy_name=name,
            symbol=symbol.upper() if symbol else None,
            start_date=start_date,
            end_date=end_date,
            limit=limit
        )

        # Add strategy_name to each signal
        for signal in signals_data:
            signal['strategy_name'] = name

        signals = [SignalInfo(**signal) for signal in signals_data]

        return SignalListResponse(
            signals=signals,
            count=len(signals),
            last_updated=datetime.utcnow().isoformat() + "Z"
        )

    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Failed to fetch strategy signals: {str(e)}")
