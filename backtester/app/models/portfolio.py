from pydantic import BaseModel, Field
from typing import List, Optional
from datetime import datetime


class Trade(BaseModel):
    """Represents a single trade execution."""

    timestamp: datetime
    action: str  # 'BUY' or 'SELL'
    price: float
    shares: float
    total_amount: float  # shares × price
    fee: float
    cash_before: float
    cash_after: float
    shares_before: float
    shares_after: float
    pnl: Optional[float] = None  # Profit/loss for SELL trades
    pnl_pct: Optional[float] = None  # P/L percentage for SELL trades


class EquityPoint(BaseModel):
    """Portfolio value at a specific timestamp."""

    timestamp: datetime
    value: float


class PortfolioState(BaseModel):
    """Current state of the portfolio."""

    initial_capital: float
    cash: float
    shares: float
    current_value: float
    transaction_cost: float
    trades: List[Trade] = Field(default_factory=list)
    equity_curve: List[EquityPoint] = Field(default_factory=list)
