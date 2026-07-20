"""
Performance Metrics Calculator

Calculates performance metrics from portfolio simulation results.
"""

import numpy as np
from typing import List
from app.core.portfolio import Portfolio
from app.models.portfolio import Trade
from app.models.metrics import BacktestMetrics


class MetricsCalculator:
    """
    Calculate performance metrics from backtesting results.

    Example:
        >>> calculator = MetricsCalculator(portfolio)
        >>> metrics = calculator.calculate_all_metrics()
        >>> print(f"Sharpe Ratio: {metrics.sharpe_ratio:.2f}")
    """

    def __init__(self, portfolio: Portfolio):
        """
        Initialize calculator with portfolio.

        Args:
            portfolio: Portfolio object after backtest completion
        """
        self.portfolio = portfolio
        self.trades = portfolio.trades
        self.equity_curve = portfolio.equity_curve

    def calculate_total_return(self) -> float:
        """Calculate total return percentage."""
        return self.portfolio.total_return

    def calculate_win_rate(self) -> float:
        """
        Calculate win rate percentage.

        Formula: (Profitable Trades / Total Sell Trades) × 100

        Returns:
            Win rate percentage (0-100)
        """
        sell_trades = self.portfolio.sell_trades

        if len(sell_trades) == 0:
            return 0.0

        winning_trades = [t for t in sell_trades if t.pnl and t.pnl > 0]
        return (len(winning_trades) / len(sell_trades)) * 100

    def calculate_sharpe_ratio(self, risk_free_rate: float = 0.0) -> float:
        """
        Calculate Sharpe Ratio (annualized).

        Formula: (Mean Return - Risk-Free Rate) / Std Deviation of Returns

        Args:
            risk_free_rate: Annual risk-free rate (default: 0%)

        Returns:
            Sharpe ratio (higher is better)

        Note:
            Returns 0 if insufficient data or no variation in returns.
        """
        # Get returns from equity curve
        if len(self.equity_curve) < 2:
            return 0.0

        values = [point.value for point in self.equity_curve]

        # Calculate period returns (percentage change between consecutive points)
        returns = []
        for i in range(1, len(values)):
            if values[i-1] != 0:  # Avoid division by zero
                period_return = (values[i] - values[i-1]) / values[i-1]
                returns.append(period_return)

        if len(returns) < 2:
            return 0.0

        # Calculate mean and std deviation
        mean_return = np.mean(returns)
        std_return = np.std(returns, ddof=1)  # Sample std deviation

        # Avoid division by zero
        if std_return == 0:
            return 0.0

        # Calculate Sharpe ratio
        sharpe = (mean_return - risk_free_rate) / std_return

        # Annualize (assuming daily returns, multiply by sqrt(252 trading days))
        # For minute/hourly data, adjust accordingly
        # For simplicity, we return the raw Sharpe (can be scaled later)
        return sharpe

    def calculate_max_drawdown(self) -> float:
        """
        Calculate maximum drawdown percentage.

        Formula: Max((Peak - Trough) / Peak) over all peaks

        Returns:
            Maximum drawdown as negative percentage (e.g., -15.5%)
        """
        if len(self.equity_curve) < 2:
            return 0.0

        values = [point.value for point in self.equity_curve]

        max_drawdown = 0.0
        peak = values[0]

        for value in values:
            # Update peak if new high
            if value > peak:
                peak = value

            # Calculate drawdown from peak
            if peak > 0:  # Avoid division by zero
                drawdown = (peak - value) / peak

                # Update max drawdown if current is larger
                if drawdown > max_drawdown:
                    max_drawdown = drawdown

        # Return as negative percentage
        return -max_drawdown * 100

    def calculate_average_win(self) -> float:
        """Calculate average profit per winning trade."""
        sell_trades = self.portfolio.sell_trades
        winning_trades = [t for t in sell_trades if t.pnl and t.pnl > 0]

        if len(winning_trades) == 0:
            return 0.0

        total_profit = sum(t.pnl for t in winning_trades)
        return total_profit / len(winning_trades)

    def calculate_average_loss(self) -> float:
        """Calculate average loss per losing trade (returned as positive number)."""
        sell_trades = self.portfolio.sell_trades
        losing_trades = [t for t in sell_trades if t.pnl and t.pnl < 0]

        if len(losing_trades) == 0:
            return 0.0

        total_loss = sum(abs(t.pnl) for t in losing_trades)
        return total_loss / len(losing_trades)

    def calculate_risk_reward_ratio(self) -> float:
        """Calculate risk/reward ratio (average win / average loss)."""
        avg_win = self.calculate_average_win()
        avg_loss = self.calculate_average_loss()

        if avg_loss == 0:
            return 0.0

        return avg_win / avg_loss

    def calculate_profit_factor(self) -> float:
        """Calculate profit factor (gross profit / gross loss)."""
        sell_trades = self.portfolio.sell_trades

        winning_trades = [t for t in sell_trades if t.pnl and t.pnl > 0]
        losing_trades = [t for t in sell_trades if t.pnl and t.pnl < 0]

        gross_profit = sum(t.pnl for t in winning_trades)
        gross_loss = sum(abs(t.pnl) for t in losing_trades)

        if gross_loss == 0:
            return 0.0 if gross_profit == 0 else float('inf')

        return gross_profit / gross_loss

    def calculate_all_metrics(self) -> BacktestMetrics:
        """
        Calculate all performance metrics.

        Returns:
            BacktestMetrics object with all calculated metrics
        """
        sell_trades = self.portfolio.sell_trades

        # Handle case with no trades
        if len(sell_trades) == 0:
            return BacktestMetrics(
                total_return_pct=0.0,
                final_portfolio_value=self.portfolio.initial_capital,
                num_trades=0,
                num_winning_trades=0,
                num_losing_trades=0,
                win_rate_pct=0.0,
                gross_profit=0.0,
                gross_loss=0.0,
                net_profit=0.0,
                avg_win=0.0,
                avg_loss=0.0,
                sharpe_ratio=0.0,
                max_drawdown_pct=0.0,
                risk_reward_ratio=0.0,
                profit_factor=0.0,
                has_trades=False
            )

        # Separate winning and losing trades
        winning_trades = [t for t in sell_trades if t.pnl and t.pnl > 0]
        losing_trades = [t for t in sell_trades if t.pnl and t.pnl < 0]

        # Calculate profit/loss metrics
        gross_profit = sum(t.pnl for t in winning_trades)
        gross_loss = sum(abs(t.pnl) for t in losing_trades)
        net_profit = gross_profit - gross_loss

        # Calculate averages
        avg_win = self.calculate_average_win()
        avg_loss = self.calculate_average_loss()

        return BacktestMetrics(
            total_return_pct=self.calculate_total_return(),
            final_portfolio_value=self.portfolio.current_value,
            num_trades=len(sell_trades),
            num_winning_trades=len(winning_trades),
            num_losing_trades=len(losing_trades),
            win_rate_pct=self.calculate_win_rate(),
            gross_profit=gross_profit,
            gross_loss=gross_loss,
            net_profit=net_profit,
            avg_win=avg_win,
            avg_loss=avg_loss,
            sharpe_ratio=self.calculate_sharpe_ratio(),
            max_drawdown_pct=self.calculate_max_drawdown(),
            risk_reward_ratio=self.calculate_risk_reward_ratio(),
            profit_factor=self.calculate_profit_factor(),
            has_trades=True
        )
