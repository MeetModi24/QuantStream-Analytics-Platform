from pydantic import BaseModel, Field


class BacktestMetrics(BaseModel):
    """Complete set of backtest performance metrics."""

    # Return Metrics
    total_return_pct: float = Field(description="Total return percentage")
    final_portfolio_value: float = Field(description="Final portfolio value in dollars")

    # Trade Count Metrics
    num_trades: int = Field(description="Total number of trades (SELL orders only)")
    num_winning_trades: int = Field(description="Number of profitable trades")
    num_losing_trades: int = Field(description="Number of losing trades")

    # Win Rate Metrics
    win_rate_pct: float = Field(description="Percentage of winning trades")

    # Profit/Loss Metrics
    gross_profit: float = Field(description="Total profit from winning trades")
    gross_loss: float = Field(description="Total loss from losing trades (positive number)")
    net_profit: float = Field(description="Net profit (gross profit - gross loss)")

    # Average Trade Metrics
    avg_win: float = Field(description="Average profit per winning trade")
    avg_loss: float = Field(description="Average loss per losing trade (positive number)")

    # Risk Metrics
    sharpe_ratio: float = Field(description="Risk-adjusted return measure")
    max_drawdown_pct: float = Field(description="Maximum peak-to-trough decline percentage (negative)")

    # Derived Metrics
    risk_reward_ratio: float = Field(description="Average win / Average loss")
    profit_factor: float = Field(description="Gross profit / Gross loss")

    # Optional: For strategies with no trades
    has_trades: bool = Field(default=True, description="Whether any trades were executed")
