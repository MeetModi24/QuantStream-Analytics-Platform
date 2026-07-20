"""
Backtest Result Models

Pydantic models for backtest results and configuration.
"""

from pydantic import BaseModel, Field
from datetime import datetime
from typing import List
from app.models.metrics import BacktestMetrics
from app.models.portfolio import Trade, EquityPoint


class BacktestConfig(BaseModel):
    """Configuration used for a backtest."""

    initial_capital: float = Field(description="Starting capital in dollars")
    transaction_cost: float = Field(description="Transaction cost as decimal (e.g., 0.001 = 0.1%)")
    frequency: str = Field(description="Candle frequency (e.g., '1H', '5T', '1D')")


class DateRange(BaseModel):
    """Date range for backtest period."""

    start: datetime = Field(description="Start date/time")
    end: datetime = Field(description="End date/time")


class BacktestResult(BaseModel):
    """
    Complete backtest results.

    Contains all data from a backtest execution including:
    - Performance metrics
    - Trade history
    - Equity curve
    - Configuration
    """

    # Identification
    strategy_name: str = Field(description="Name of strategy used")
    symbol: str = Field(description="Trading symbol (e.g., 'AAPL')")

    # Time Period
    period: DateRange = Field(description="Date range of backtest")

    # Configuration
    config: BacktestConfig = Field(description="Configuration used for backtest")

    # Results
    metrics: BacktestMetrics = Field(description="All performance metrics")
    trades: List[Trade] = Field(description="All executed trades")
    equity_curve: List[EquityPoint] = Field(description="Portfolio value over time")

    # Summary Fields (for quick access)
    final_portfolio_value: float = Field(description="Final portfolio value")
    total_return_pct: float = Field(description="Total return percentage")
    num_candles_processed: int = Field(description="Number of candles processed")

    class Config:
        json_schema_extra = {
            "example": {
                "strategy_name": "RSI",
                "symbol": "AAPL",
                "period": {
                    "start": "2026-06-19T00:00:00Z",
                    "end": "2026-07-19T23:59:59Z"
                },
                "config": {
                    "initial_capital": 10000.0,
                    "transaction_cost": 0.001,
                    "frequency": "1H"
                },
                "metrics": {
                    "total_return_pct": 32.5,
                    "sharpe_ratio": 1.85,
                    "win_rate_pct": 68.0,
                    "max_drawdown_pct": -8.4,
                    "num_trades": 487
                },
                "final_portfolio_value": 13250.0,
                "total_return_pct": 32.5,
                "num_candles_processed": 720
            }
        }
