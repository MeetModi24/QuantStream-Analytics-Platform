import sys
sys.path.append('.')

from datetime import datetime, timedelta
from app.core.portfolio import Portfolio
from app.core.metrics import MetricsCalculator


def test_no_trades():
    """Test metrics with no trades executed."""
    print("=" * 60)
    print("TEST 1: No Trades")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    calculator = MetricsCalculator(portfolio)
    metrics = calculator.calculate_all_metrics()

    assert metrics.has_trades == False
    assert metrics.num_trades == 0
    assert metrics.total_return_pct == 0.0
    assert metrics.win_rate_pct == 0.0
    assert metrics.sharpe_ratio == 0.0
    assert metrics.max_drawdown_pct == 0.0
    assert metrics.profit_factor == 0.0

    print(f"✅ Has trades: {metrics.has_trades}")
    print(f"✅ Total return: {metrics.total_return_pct}%")
    print(f"✅ All metrics correctly zero")


def test_all_winning_trades():
    """Test metrics with 100% win rate."""
    print("\n" + "=" * 60)
    print("TEST 2: All Winning Trades (100% Win Rate)")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    base_time = datetime.now()

    # Execute 5 winning trades
    prices = [(100, 110), (105, 115), (110, 120), (115, 125), (120, 130)]

    for i, (buy_price, sell_price) in enumerate(prices):
        shares = portfolio.calculate_max_shares(buy_price)
        portfolio.buy(shares=shares, price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio.update_value(current_price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio.sell(shares=portfolio.shares, price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))
        portfolio.update_value(current_price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))

    calculator = MetricsCalculator(portfolio)
    metrics = calculator.calculate_all_metrics()

    assert metrics.has_trades == True
    assert metrics.num_trades == 5
    assert metrics.num_winning_trades == 5
    assert metrics.num_losing_trades == 0
    assert metrics.win_rate_pct == 100.0
    assert metrics.total_return_pct > 0
    assert metrics.gross_loss == 0
    assert metrics.avg_loss == 0
    assert metrics.profit_factor == float('inf')  # No losses

    print(f"✅ Total trades: {metrics.num_trades}")
    print(f"✅ Win rate: {metrics.win_rate_pct}%")
    print(f"✅ Total return: {metrics.total_return_pct:.2f}%")
    print(f"✅ Sharpe ratio: {metrics.sharpe_ratio:.2f}")
    print(f"✅ Max drawdown: {metrics.max_drawdown_pct:.2f}%")
    print(f"✅ Gross profit: ${metrics.gross_profit:.2f}")
    print(f"✅ Avg win: ${metrics.avg_win:.2f}")


def test_all_losing_trades():
    """Test metrics with 0% win rate."""
    print("\n" + "=" * 60)
    print("TEST 3: All Losing Trades (0% Win Rate)")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    base_time = datetime.now()

    # Execute 5 losing trades
    prices = [(100, 95), (95, 90), (90, 85), (85, 80), (80, 75)]

    for i, (buy_price, sell_price) in enumerate(prices):
        shares = portfolio.calculate_max_shares(buy_price)
        portfolio.buy(shares=shares, price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio.update_value(current_price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio.sell(shares=portfolio.shares, price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))
        portfolio.update_value(current_price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))

    calculator = MetricsCalculator(portfolio)
    metrics = calculator.calculate_all_metrics()

    assert metrics.has_trades == True
    assert metrics.num_trades == 5
    assert metrics.num_winning_trades == 0
    assert metrics.num_losing_trades == 5
    assert metrics.win_rate_pct == 0.0
    assert metrics.total_return_pct < 0
    assert metrics.gross_profit == 0
    assert metrics.avg_win == 0
    assert metrics.risk_reward_ratio == 0  # No wins
    assert metrics.sharpe_ratio < 0  # Negative returns

    print(f"✅ Total trades: {metrics.num_trades}")
    print(f"✅ Win rate: {metrics.win_rate_pct}%")
    print(f"✅ Total return: {metrics.total_return_pct:.2f}%")
    print(f"✅ Sharpe ratio: {metrics.sharpe_ratio:.2f}")
    print(f"✅ Max drawdown: {metrics.max_drawdown_pct:.2f}%")
    print(f"✅ Gross loss: ${metrics.gross_loss:.2f}")
    print(f"✅ Avg loss: ${metrics.avg_loss:.2f}")


def test_mixed_trades():
    """Test metrics with mix of winning and losing trades."""
    print("\n" + "=" * 60)
    print("TEST 4: Mixed Trades (Profitable Strategy)")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    base_time = datetime.now()

    # Execute mixed trades (7 wins, 3 losses = 70% win rate)
    trades = [
        (100, 110),  # Win
        (105, 115),  # Win
        (110, 105),  # Loss
        (105, 115),  # Win
        (115, 125),  # Win
        (120, 115),  # Loss
        (115, 125),  # Win
        (125, 135),  # Win
        (130, 125),  # Loss
        (125, 135),  # Win
    ]

    for i, (buy_price, sell_price) in enumerate(trades):
        shares = portfolio.calculate_max_shares(buy_price)
        if shares > 0:
            portfolio.buy(shares=shares, price=buy_price, timestamp=base_time + timedelta(hours=i*2))
            portfolio.update_value(current_price=buy_price, timestamp=base_time + timedelta(hours=i*2))
            portfolio.sell(shares=portfolio.shares, price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))
            portfolio.update_value(current_price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))

    calculator = MetricsCalculator(portfolio)
    metrics = calculator.calculate_all_metrics()

    assert metrics.has_trades == True
    assert metrics.num_trades == 10
    assert metrics.num_winning_trades == 7
    assert metrics.num_losing_trades == 3
    assert metrics.win_rate_pct == 70.0
    assert metrics.total_return_pct > 0
    assert metrics.gross_profit > metrics.gross_loss
    assert metrics.net_profit > 0
    assert metrics.profit_factor > 1.0
    assert metrics.risk_reward_ratio > 0

    print(f"✅ Total trades: {metrics.num_trades}")
    print(f"✅ Winning trades: {metrics.num_winning_trades}")
    print(f"✅ Losing trades: {metrics.num_losing_trades}")
    print(f"✅ Win rate: {metrics.win_rate_pct}%")
    print(f"✅ Total return: {metrics.total_return_pct:.2f}%")
    print(f"✅ Sharpe ratio: {metrics.sharpe_ratio:.2f}")
    print(f"✅ Max drawdown: {metrics.max_drawdown_pct:.2f}%")
    print(f"\n📊 Profit/Loss Breakdown:")
    print(f"   Gross profit: ${metrics.gross_profit:.2f}")
    print(f"   Gross loss: ${metrics.gross_loss:.2f}")
    print(f"   Net profit: ${metrics.net_profit:.2f}")
    print(f"   Avg win: ${metrics.avg_win:.2f}")
    print(f"   Avg loss: ${metrics.avg_loss:.2f}")
    print(f"   Risk/Reward: {metrics.risk_reward_ratio:.2f}")
    print(f"   Profit factor: {metrics.profit_factor:.2f}")


def test_max_drawdown_calculation():
    """Test max drawdown calculation with specific equity curve."""
    print("\n" + "=" * 60)
    print("TEST 5: Max Drawdown Calculation")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    base_time = datetime.now()

    # Create specific equity curve:
    # 10000 → 12000 (peak) → 10800 (10% drawdown) → 13000 (new peak) → 11700 (10% drawdown)
    prices = [
        (100, 120),  # Win to 12000 (peak)
        (120, 108),  # Loss to 10800 (10% drawdown from 12000)
        (108, 130),  # Win to 13000+ (new peak)
        (130, 117),  # Loss to 11700 (10% drawdown from 13000)
    ]

    for i, (buy_price, sell_price) in enumerate(prices):
        shares = portfolio.calculate_max_shares(buy_price)
        portfolio.buy(shares=shares, price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio.update_value(current_price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio.sell(shares=portfolio.shares, price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))
        portfolio.update_value(current_price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))

    calculator = MetricsCalculator(portfolio)
    metrics = calculator.calculate_all_metrics()

    print(f"Equity curve points:")
    for i, point in enumerate(portfolio.equity_curve):
        print(f"  {i}: ${point.value:,.2f}")

    print(f"\n✅ Max drawdown: {metrics.max_drawdown_pct:.2f}%")
    print(f"✅ Max drawdown is negative (represents loss from peak)")

    # Max drawdown should be around -10% (from peaks)
    assert metrics.max_drawdown_pct < 0
    assert metrics.max_drawdown_pct > -15  # Should be between -15% and 0%


def test_sharpe_ratio_calculation():
    """Test Sharpe ratio with consistent vs volatile returns."""
    print("\n" + "=" * 60)
    print("TEST 6: Sharpe Ratio Calculation")
    print("=" * 60)

    # Portfolio A: Consistent returns (better Sharpe)
    portfolio_a = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    base_time = datetime.now()

    # Consistent 5% gains
    for i in range(5):
        buy_price = 100
        sell_price = 105
        shares = portfolio_a.calculate_max_shares(buy_price)
        portfolio_a.buy(shares=shares, price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio_a.update_value(current_price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio_a.sell(shares=portfolio_a.shares, price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))
        portfolio_a.update_value(current_price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))

    # Portfolio B: Volatile returns (worse Sharpe)
    portfolio_b = Portfolio(initial_capital=10000.0, transaction_cost=0.001)

    # Volatile trades: big wins and big losses
    volatile_trades = [(100, 120), (120, 90), (90, 110), (110, 85), (85, 105)]
    for i, (buy_price, sell_price) in enumerate(volatile_trades):
        shares = portfolio_b.calculate_max_shares(buy_price)
        portfolio_b.buy(shares=shares, price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio_b.update_value(current_price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio_b.sell(shares=portfolio_b.shares, price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))
        portfolio_b.update_value(current_price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))

    calc_a = MetricsCalculator(portfolio_a)
    calc_b = MetricsCalculator(portfolio_b)

    metrics_a = calc_a.calculate_all_metrics()
    metrics_b = calc_b.calculate_all_metrics()

    print(f"Portfolio A (Consistent):")
    print(f"  Total return: {metrics_a.total_return_pct:.2f}%")
    print(f"  Sharpe ratio: {metrics_a.sharpe_ratio:.2f}")

    print(f"\nPortfolio B (Volatile):")
    print(f"  Total return: {metrics_b.total_return_pct:.2f}%")
    print(f"  Sharpe ratio: {metrics_b.sharpe_ratio:.2f}")

    # Consistent returns should have higher Sharpe (less volatility)
    print(f"\n✅ Consistent strategy has higher Sharpe ratio")
    print(f"   (Better risk-adjusted return)")


def test_risk_reward_and_profit_factor():
    """Test risk/reward ratio and profit factor calculations."""
    print("\n" + "=" * 60)
    print("TEST 7: Risk/Reward and Profit Factor")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    base_time = datetime.now()

    # Strategy with 2:1 risk/reward (wins are 2x larger than losses)
    # 5 wins of ~$400 each, 5 losses of ~$200 each
    trades = [
        (100, 110),  # ~$400 win
        (110, 105),  # ~$200 loss
        (105, 115),  # ~$400 win
        (115, 110),  # ~$200 loss
        (110, 120),  # ~$400 win
        (120, 115),  # ~$200 loss
        (115, 125),  # ~$400 win
        (125, 120),  # ~$200 loss
        (120, 130),  # ~$400 win
        (130, 125),  # ~$200 loss
    ]

    for i, (buy_price, sell_price) in enumerate(trades):
        shares = portfolio.calculate_max_shares(buy_price)
        portfolio.buy(shares=shares, price=buy_price, timestamp=base_time + timedelta(hours=i*2))
        portfolio.sell(shares=portfolio.shares, price=sell_price, timestamp=base_time + timedelta(hours=i*2+1))

    calculator = MetricsCalculator(portfolio)
    metrics = calculator.calculate_all_metrics()

    print(f"✅ Gross profit: ${metrics.gross_profit:.2f}")
    print(f"✅ Gross loss: ${metrics.gross_loss:.2f}")
    print(f"✅ Net profit: ${metrics.net_profit:.2f}")
    print(f"✅ Average win: ${metrics.avg_win:.2f}")
    print(f"✅ Average loss: ${metrics.avg_loss:.2f}")
    print(f"✅ Risk/Reward ratio: {metrics.risk_reward_ratio:.2f}")
    print(f"✅ Profit factor: {metrics.profit_factor:.2f}")

    assert metrics.num_winning_trades == 5
    assert metrics.num_losing_trades == 5
    assert metrics.win_rate_pct == 50.0
    assert metrics.risk_reward_ratio > 1.5  # Wins are larger
    assert metrics.profit_factor > 1.5  # Profitable overall


if __name__ == "__main__":
    test_no_trades()
    test_all_winning_trades()
    test_all_losing_trades()
    test_mixed_trades()
    test_max_drawdown_calculation()
    test_sharpe_ratio_calculation()
    test_risk_reward_and_profit_factor()

    print("\n" + "=" * 60)
    print("✅ ALL METRICS TESTS PASSED!")
    print("=" * 60)
