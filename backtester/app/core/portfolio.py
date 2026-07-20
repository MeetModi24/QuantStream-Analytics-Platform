"""
Portfolio Simulation Engine

Simulates a trading portfolio with cash and shares.
Tracks trades, portfolio value, and equity curve.
"""

from typing import List, Optional
from datetime import datetime
from app.models.portfolio import Trade, EquityPoint, PortfolioState


class Portfolio:
    """
    Simulates a trading portfolio.

    Handles:
    - BUY/SELL trade execution
    - Transaction cost calculation
    - Trade recording
    - Equity curve tracking

    Example:
        >>> portfolio = Portfolio(initial_capital=10000, transaction_cost=0.001)
        >>> portfolio.buy(shares=50, price=100.0, timestamp=datetime.now())
        >>> portfolio.update_value(current_price=105.0, timestamp=datetime.now())
        >>> print(portfolio.current_value)  # $5,250 (50 shares × $105)
    """

    def __init__(self, initial_capital: float, transaction_cost: float = 0.001):
        """
        Initialize portfolio.

        Args:
            initial_capital: Starting cash amount
            transaction_cost: Transaction cost as decimal (e.g., 0.001 = 0.1%)
        """
        self.initial_capital = initial_capital
        self.cash = initial_capital
        self.shares = 0.0
        self.transaction_cost = transaction_cost
        self.trades: List[Trade] = []
        self.equity_curve: List[EquityPoint] = []

        # Track last buy price for P/L calculation
        self._last_buy_price: Optional[float] = None

        # Record initial equity point
        self.equity_curve.append(EquityPoint(
            timestamp=datetime.now(),
            value=initial_capital
        ))

    def buy(self, shares: float, price: float, timestamp: datetime) -> Optional[Trade]:
        """
        Execute a BUY trade.

        Args:
            shares: Number of shares to buy
            price: Price per share
            timestamp: Time of trade

        Returns:
            Trade object if successful, None if insufficient cash

        Formula:
            total_cost = (shares × price) + fee
            fee = (shares × price) × transaction_cost
        """
        if shares <= 0:
            return None

        # Calculate cost
        gross_cost = shares * price
        fee = gross_cost * self.transaction_cost
        total_cost = gross_cost + fee

        # Check if enough cash (with small tolerance for floating point precision)
        if total_cost > self.cash + 0.01:  # Allow 1 cent tolerance
            return None

        # Execute trade
        cash_before = self.cash
        shares_before = self.shares

        self.cash -= total_cost
        self.shares += shares
        self._last_buy_price = price

        # Record trade
        trade = Trade(
            timestamp=timestamp,
            action='BUY',
            price=price,
            shares=shares,
            total_amount=gross_cost,
            fee=fee,
            cash_before=cash_before,
            cash_after=self.cash,
            shares_before=shares_before,
            shares_after=self.shares
        )
        self.trades.append(trade)

        return trade

    def sell(self, shares: float, price: float, timestamp: datetime) -> Optional[Trade]:
        """
        Execute a SELL trade.

        Args:
            shares: Number of shares to sell
            price: Price per share
            timestamp: Time of trade

        Returns:
            Trade object if successful, None if insufficient shares

        Formula:
            net_proceeds = (shares × price) - fee
            fee = (shares × price) × transaction_cost
            pnl = net_proceeds - (shares × last_buy_price)
        """
        if shares <= 0 or shares > self.shares:
            return None

        # Calculate proceeds
        gross_proceeds = shares * price
        fee = gross_proceeds * self.transaction_cost
        net_proceeds = gross_proceeds - fee

        # Calculate P/L
        pnl = None
        pnl_pct = None
        if self._last_buy_price is not None:
            cost_basis = shares * self._last_buy_price
            pnl = net_proceeds - cost_basis
            pnl_pct = (pnl / cost_basis) * 100

        # Execute trade
        cash_before = self.cash
        shares_before = self.shares

        self.cash += net_proceeds
        self.shares -= shares

        # Clear buy price if all shares sold
        if self.shares == 0:
            self._last_buy_price = None

        # Record trade
        trade = Trade(
            timestamp=timestamp,
            action='SELL',
            price=price,
            shares=shares,
            total_amount=gross_proceeds,
            fee=fee,
            cash_before=cash_before,
            cash_after=self.cash,
            shares_before=shares_before,
            shares_after=self.shares,
            pnl=pnl,
            pnl_pct=pnl_pct
        )
        self.trades.append(trade)

        return trade

    def update_value(self, current_price: float, timestamp: datetime):
        """
        Update portfolio value and record equity point.

        Args:
            current_price: Current price of the asset
            timestamp: Current timestamp

        Formula:
            portfolio_value = cash + (shares × current_price)
        """
        current_value = self.cash + (self.shares * current_price)

        self.equity_curve.append(EquityPoint(
            timestamp=timestamp,
            value=current_value
        ))

    @property
    def current_value(self) -> float:
        """
        Get current portfolio value.

        Returns cash if no shares are held, otherwise returns last equity point.
        This ensures value is accurate even if update_value() hasn't been called.
        """
        # If we have no shares, portfolio value is just cash
        if self.shares == 0:
            return self.cash

        # If we have shares, use last equity point (assumes update_value was called)
        if self.equity_curve:
            return self.equity_curve[-1].value

        return self.initial_capital

    @property
    def total_return(self) -> float:
        """Calculate total return percentage."""
        return ((self.current_value - self.initial_capital) / self.initial_capital) * 100

    @property
    def num_trades(self) -> int:
        """Total number of trades executed."""
        return len(self.trades)

    @property
    def buy_trades(self) -> List[Trade]:
        """Get all BUY trades."""
        return [t for t in self.trades if t.action == 'BUY']

    @property
    def sell_trades(self) -> List[Trade]:
        """Get all SELL trades."""
        return [t for t in self.trades if t.action == 'SELL']

    def calculate_max_shares(self, price: float) -> float:
        """
        Calculate maximum shares that can be bought with available cash.

        Args:
            price: Price per share

        Returns:
            Maximum number of shares (accounting for transaction cost)

        Formula:
            shares = cash / (price × (1 + transaction_cost))
        """
        if price <= 0:
            return 0

        return self.cash / (price * (1 + self.transaction_cost))

    def get_state(self) -> PortfolioState:
        """
        Get complete portfolio state.

        Returns:
            PortfolioState object with all data
        """
        return PortfolioState(
            initial_capital=self.initial_capital,
            cash=self.cash,
            shares=self.shares,
            current_value=self.current_value,
            transaction_cost=self.transaction_cost,
            trades=self.trades,
            equity_curve=self.equity_curve
        )
