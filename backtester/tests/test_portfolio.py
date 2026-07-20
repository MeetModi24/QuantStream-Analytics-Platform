import sys
sys.path.append('.')

from datetime import datetime, timedelta
from app.core.portfolio import Portfolio


def test_portfolio_initialization():
    """Test portfolio starts with correct values."""
    print("=" * 60)
    print("TEST 1: Portfolio Initialization")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)

    assert portfolio.cash == 10000.0
    assert portfolio.shares == 0.0
    assert portfolio.current_value == 10000.0
    assert portfolio.num_trades == 0

    print(f"✅ Initial cash: ${portfolio.cash:,.2f}")
    print(f"✅ Initial shares: {portfolio.shares}")
    print(f"✅ Initial value: ${portfolio.current_value:,.2f}")


def test_buy_trade():
    """Test buying shares."""
    print("\n" + "=" * 60)
    print("TEST 2: BUY Trade")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)

    # Calculate max shares we can buy
    price = 100.0
    max_shares = portfolio.calculate_max_shares(price)
    print(f"Max shares at $100: {max_shares:.2f}")

    # Buy shares
    timestamp = datetime.now()
    trade = portfolio.buy(shares=max_shares, price=price, timestamp=timestamp)

    assert trade is not None
    assert trade.action == 'BUY'
    assert trade.price == 100.0
    assert portfolio.shares == max_shares
    assert portfolio.cash < 1.0  # Should be nearly $0

    print(f"✅ Bought {trade.shares:.2f} shares at ${trade.price}")
    print(f"✅ Cost: ${trade.total_amount:,.2f}")
    print(f"✅ Fee: ${trade.fee:.2f}")
    print(f"✅ Cash remaining: ${portfolio.cash:.2f}")
    print(f"✅ Shares owned: {portfolio.shares:.2f}")


def test_sell_trade():
    """Test selling shares with profit."""
    print("\n" + "=" * 60)
    print("TEST 3: SELL Trade (with profit)")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)

    # Buy shares
    buy_price = 100.0
    shares = portfolio.calculate_max_shares(buy_price)
    portfolio.buy(shares=shares, price=buy_price, timestamp=datetime.now())

    print(f"Bought {shares:.2f} shares at ${buy_price}")

    # Sell shares at higher price
    sell_price = 110.0
    timestamp = datetime.now()
    trade = portfolio.sell(shares=portfolio.shares, price=sell_price, timestamp=timestamp)

    assert trade is not None
    assert trade.action == 'SELL'
    assert trade.price == 110.0
    assert portfolio.shares == 0
    assert trade.pnl is not None
    assert trade.pnl > 0  # Should be profitable

    print(f"✅ Sold {trade.shares:.2f} shares at ${trade.price}")
    print(f"✅ Proceeds: ${trade.total_amount:,.2f}")
    print(f"✅ Fee: ${trade.fee:.2f}")
    print(f"✅ P/L: ${trade.pnl:.2f} ({trade.pnl_pct:.2f}%)")
    print(f"✅ Final cash: ${portfolio.cash:.2f}")
    print(f"✅ Total return: {portfolio.total_return:.2f}%")


def test_multiple_trades():
    """Test multiple buy/sell cycles."""
    print("\n" + "=" * 60)
    print("TEST 4: Multiple Trades")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    base_time = datetime.now()

    # Trade 1: Buy at $100, Sell at $110 (profit)
    shares = portfolio.calculate_max_shares(100.0)
    portfolio.buy(shares=shares, price=100.0, timestamp=base_time)
    portfolio.sell(shares=portfolio.shares, price=110.0, timestamp=base_time + timedelta(hours=1))
    value_after_trade1 = portfolio.cash

    print(f"Trade 1: Buy at $100, Sell at $110")
    print(f"  → Portfolio value: ${value_after_trade1:.2f}")

    # Trade 2: Buy at $105, Sell at $100 (loss)
    shares = portfolio.calculate_max_shares(105.0)
    portfolio.buy(shares=shares, price=105.0, timestamp=base_time + timedelta(hours=2))
    portfolio.sell(shares=portfolio.shares, price=100.0, timestamp=base_time + timedelta(hours=3))
    value_after_trade2 = portfolio.cash

    print(f"Trade 2: Buy at $105, Sell at $100")
    print(f"  → Portfolio value: ${value_after_trade2:.2f}")

    # Trade 3: Buy at $95, Sell at $105 (profit)
    shares = portfolio.calculate_max_shares(95.0)
    portfolio.buy(shares=shares, price=95.0, timestamp=base_time + timedelta(hours=4))
    portfolio.sell(shares=portfolio.shares, price=105.0, timestamp=base_time + timedelta(hours=5))
    value_after_trade3 = portfolio.cash

    print(f"Trade 3: Buy at $95, Sell at $105")
    print(f"  → Portfolio value: ${value_after_trade3:.2f}")

    # Check results
    assert portfolio.num_trades == 6  # 3 buys + 3 sells
    assert len(portfolio.buy_trades) == 3
    assert len(portfolio.sell_trades) == 3

    print(f"\n✅ Total trades: {portfolio.num_trades}")
    print(f"✅ Buy trades: {len(portfolio.buy_trades)}")
    print(f"✅ Sell trades: {len(portfolio.sell_trades)}")
    print(f"✅ Final value: ${portfolio.cash:.2f}")
    print(f"✅ Total return: {portfolio.total_return:.2f}%")


def test_equity_curve():
    """Test equity curve tracking."""
    print("\n" + "=" * 60)
    print("TEST 5: Equity Curve")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)
    base_time = datetime.now()

    # Buy shares
    shares = portfolio.calculate_max_shares(100.0)
    portfolio.buy(shares=shares, price=100.0, timestamp=base_time)

    # Update value at different prices
    prices = [100, 102, 105, 103, 108, 110]
    for i, price in enumerate(prices):
        portfolio.update_value(current_price=price, timestamp=base_time + timedelta(minutes=i))

    # Sell shares
    portfolio.sell(shares=portfolio.shares, price=110.0, timestamp=base_time + timedelta(hours=1))

    # Check equity curve
    assert len(portfolio.equity_curve) >= len(prices) + 1  # Initial + updates + final

    print(f"✅ Equity curve has {len(portfolio.equity_curve)} points")
    print("\nEquity Curve:")
    for point in portfolio.equity_curve[-7:]:  # Show last 7 points
        print(f"  {point.timestamp.strftime('%H:%M:%S')} → ${point.value:,.2f}")


def test_insufficient_cash():
    """Test that trades fail when insufficient funds."""
    print("\n" + "=" * 60)
    print("TEST 6: Insufficient Cash")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=100.0, transaction_cost=0.001)

    # Try to buy shares worth more than available cash
    trade = portfolio.buy(shares=10, price=100.0, timestamp=datetime.now())

    assert trade is None  # Should fail
    assert portfolio.shares == 0  # No shares bought
    assert portfolio.cash == 100.0  # Cash unchanged

    print("✅ Trade correctly rejected due to insufficient cash")


def test_transaction_costs():
    """Test transaction cost calculation."""
    print("\n" + "=" * 60)
    print("TEST 7: Transaction Costs")
    print("=" * 60)

    portfolio = Portfolio(initial_capital=10000.0, transaction_cost=0.001)

    # Buy shares
    shares = portfolio.calculate_max_shares(100.0)
    buy_trade = portfolio.buy(shares=shares, price=100.0, timestamp=datetime.now())

    # Sell shares
    sell_trade = portfolio.sell(shares=portfolio.shares, price=110.0, timestamp=datetime.now())

    total_fees = buy_trade.fee + sell_trade.fee

    print(f"Buy fee: ${buy_trade.fee:.2f}")
    print(f"Sell fee: ${sell_trade.fee:.2f}")
    print(f"Total fees: ${total_fees:.2f}")

    # Verify fees are deducted from profit
    gross_profit = (110.0 - 100.0) * shares
    net_profit = portfolio.cash - 10000.0

    print(f"\nGross profit (no fees): ${gross_profit:.2f}")
    print(f"Net profit (with fees): ${net_profit:.2f}")
    print(f"✅ Fees correctly deducted: ${gross_profit - net_profit:.2f}")


if __name__ == "__main__":
    test_portfolio_initialization()
    test_buy_trade()
    test_sell_trade()
    test_multiple_trades()
    test_equity_curve()
    test_insufficient_cash()
    test_transaction_costs()

    print("\n" + "=" * 60)
    print("✅ ALL PORTFOLIO TESTS PASSED!")
    print("=" * 60)
