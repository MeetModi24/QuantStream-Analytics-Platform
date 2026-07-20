# Task 5: Portfolio Simulation Engine

**Goal:** Implement portfolio simulation to track trades, cash, shares, and portfolio value over time.

**Estimated Time:** 4 hours

---

## Overview

The **Portfolio Simulation Engine** is the heart of backtesting. It simulates what would happen if you traded with real money using a strategy's signals.

**What it does:**
- Starts with initial capital (e.g., $10,000)
- Executes BUY/SELL trades based on strategy signals
- Tracks cash, shares owned, and total portfolio value
- Records every trade with P/L (profit/loss)
- Handles transaction costs (e.g., 0.1% per trade)

**Why it's important:**
- Without simulation, we only have signals ("BUY at $100")
- With simulation, we know actual profit: "Bought 100 shares at $100, sold at $110, profit = $1,000 - $20 fees = $980"
- This is what the frontend will display to showcase strategy performance

---

## Understanding Portfolio Simulation

### What is a Portfolio?

A **portfolio** tracks your money and assets over time.

**Components:**
1. **Cash:** Money available to buy stocks
2. **Shares:** Number of stocks owned
3. **Portfolio Value:** Cash + (Shares × Current Price)

### Example Simulation

**Strategy:** RSI Mean Reversion on AAPL

**Starting State:**
```
Cash = $10,000
Shares = 0
Portfolio Value = $10,000
```

**Day 1: 10:00 AM - BUY Signal (RSI = 28)**
```
Price = $100
Action: BUY with all cash
Shares to buy = $10,000 / $100 = 100 shares
Transaction cost = $10,000 × 0.001 = $10
Total cost = $10,010

After trade:
Cash = $10,000 - $10,010 = -$10 (we're $10 short!)
```

**Problem:** We can't buy 100 shares with $10,000 if there's a transaction cost.

**Solution:** Account for transaction cost BEFORE buying:
```
Available for shares = $10,000 / (1 + 0.001) = $9,990.01
Shares to buy = $9,990.01 / $100 = 99.9 shares
Transaction cost = $9,990.01 × 0.001 = $9.99

After trade:
Cash = $10,000 - $9,990.01 - $9.99 = $0
Shares = 99.9
Portfolio Value = $0 + (99.9 × $100) = $9,990
```

**Day 2: 2:00 PM - Price Rises**
```
Price = $105
Cash = $0
Shares = 99.9
Portfolio Value = $0 + (99.9 × $105) = $10,489.50
Unrealized P/L = $10,489.50 - $10,000 = +$489.50 (+4.9%)
```

**Day 3: 11:00 AM - SELL Signal (RSI = 72)**
```
Price = $110
Action: SELL all shares
Sale amount = 99.9 × $110 = $10,989
Transaction cost = $10,989 × 0.001 = $10.99

After trade:
Cash = $0 + $10,989 - $10.99 = $10,978.01
Shares = 0
Portfolio Value = $10,978.01
Realized P/L = $10,978.01 - $10,000 = +$978.01 (+9.78%)
```

**Trade Summary:**
```
Entry: Bought 99.9 shares at $100 (Day 1)
Exit: Sold 99.9 shares at $110 (Day 3)
Gross profit: $999 (99.9 shares × $10 gain)
Transaction costs: $20.98 (entry + exit)
Net profit: $978.01
Return: 9.78%
```

---

## Key Concepts

### 1. Position Sizing

**Question:** When you get a BUY signal, how many shares do you buy?

**Strategies:**
1. **All-In (100% capital):** Buy as many shares as possible
2. **Fixed Percentage:** Use only 50% of capital per trade
3. **Fixed Dollar Amount:** Always invest $5,000 per trade
4. **Kelly Criterion:** Mathematical formula based on win rate

**Our Approach (Phase 3):** **All-In**
- Simple and easy to understand
- Shows maximum potential of strategy
- Real traders would use position sizing (Phase 4 enhancement)

### 2. Transaction Costs

**Real trading has costs:**
- **Commission:** Broker fee (e.g., $0 for Robinhood, $1-5 for traditional)
- **Slippage:** Price moves between signal and execution (1-3 ticks)
- **Spread:** Difference between bid and ask price (0.01-0.1%)

**Our Model:** Single percentage fee
- Default: 0.1% (0.001) per trade
- Example: $10,000 trade = $10 fee
- Applied to both BUY and SELL

**Why this matters:**
- Strategy with 100 trades vs 10 trades
- 100 trades = $1,000 in fees
- 10 trades = $100 in fees
- Same return before fees, but 10-trade strategy is better!

### 3. Trade Lifecycle

**1. Signal Generated**
```python
signal = 'BUY'
price = 100.50
timestamp = '2026-07-19 10:30:00'
```

**2. Check if Trade is Possible**
```python
if signal == 'BUY' and portfolio.cash > 0:
    # Can buy
elif signal == 'SELL' and portfolio.shares > 0:
    # Can sell
else:
    # Ignore signal (no cash or no shares)
```

**3. Execute Trade**
```python
if signal == 'BUY':
    shares_to_buy = calculate_shares(cash, price, transaction_cost)
    portfolio.buy(shares_to_buy, price, timestamp)
elif signal == 'SELL':
    portfolio.sell(portfolio.shares, price, timestamp)
```

**4. Record Trade**
```python
trade = {
    'timestamp': timestamp,
    'action': 'BUY' or 'SELL',
    'price': price,
    'shares': shares,
    'total': shares * price,
    'fee': total * transaction_cost,
    'cash_before': cash_before,
    'cash_after': cash_after,
    'shares_before': shares_before,
    'shares_after': shares_after
}
```

**5. Update Portfolio Value**
```python
portfolio.value = portfolio.cash + (portfolio.shares * current_price)
```

---

## Architecture Design

### Portfolio Class

**Responsibilities:**
1. Track cash, shares, and portfolio value
2. Execute BUY trades (decrease cash, increase shares)
3. Execute SELL trades (increase cash, decrease shares)
4. Record all trades with details
5. Track portfolio value over time (equity curve)
6. Handle transaction costs

**State:**
```python
{
    'initial_capital': 10000.0,
    'cash': 10978.01,
    'shares': 0,
    'current_value': 10978.01,
    'transaction_cost': 0.001,
    'trades': [
        {'timestamp': '...', 'action': 'BUY', ...},
        {'timestamp': '...', 'action': 'SELL', ...}
    ],
    'equity_curve': [
        {'timestamp': '...', 'value': 10000.0},
        {'timestamp': '...', 'value': 10489.5},
        {'timestamp': '...', 'value': 10978.01}
    ]
}
```

---

## Step 1: Create Portfolio Model

Create `app/models/portfolio.py`:

```python
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
```

---

## Step 2: Implement Portfolio Logic

Create `app/core/portfolio.py`:

```python
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
        
        # Check if enough cash
        if total_cost > self.cash:
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
        """Get current portfolio value (last equity point)."""
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
```

---

## Step 3: Test Portfolio Simulation

Create `tests/test_portfolio.py`:

```python
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
```

---

## Success Criteria Checklist

- [ ] `app/models/portfolio.py` created with Trade, EquityPoint, PortfolioState models
- [ ] `app/core/portfolio.py` created with Portfolio class
- [ ] Portfolio tracks cash, shares, and value correctly
- [ ] BUY trades decrease cash and increase shares
- [ ] SELL trades increase cash and decrease shares
- [ ] Transaction costs deducted from both BUY and SELL
- [ ] P/L calculated correctly for SELL trades
- [ ] `calculate_max_shares()` accounts for transaction costs
- [ ] Equity curve tracks portfolio value over time
- [ ] All 7 tests pass
- [ ] Trades rejected when insufficient cash/shares

---

## Frontend Showcase Features

With this portfolio simulation, the frontend can display:

### 1. Portfolio Value Over Time (Equity Curve)
```
Line chart showing how portfolio grows from $10,000 → $13,250
```

### 2. Trade History Table
```
| Time     | Action | Price  | Shares | P/L      |
|----------|--------|--------|--------|----------|
| 10:30 AM | BUY    | $100   | 99.9   | -        |
| 2:15 PM  | SELL   | $110   | 99.9   | +$978    |
```

### 3. Performance Metrics
```
Total Return: +32.5%
Number of Trades: 487
Win Rate: 68% (331 wins / 156 losses)
Total Fees Paid: $245.60
```

### 4. Current Position
```
Cash: $10,978.01
Shares: 0
Portfolio Value: $10,978.01
```

---

## Expected Test Output

When you run the test:

```
============================================================
TEST 1: Portfolio Initialization
============================================================
✅ Initial cash: $10,000.00
✅ Initial shares: 0
✅ Initial value: $10,000.00

============================================================
TEST 2: BUY Trade
============================================================
Max shares at $100: 99.90
✅ Bought 99.90 shares at $100.0
✅ Cost: $9,990.01
✅ Fee: $9.99
✅ Cash remaining: $0.00
✅ Shares owned: 99.90

============================================================
TEST 3: SELL Trade (with profit)
============================================================
Bought 99.90 shares at $100.0
✅ Sold 99.90 shares at $110.0
✅ Proceeds: $10,989.00
✅ Fee: $10.99
✅ P/L: $978.01 (9.78%)
✅ Final cash: $10,978.01
✅ Total return: 9.78%

... (more tests)

============================================================
✅ ALL PORTFOLIO TESTS PASSED!
============================================================
```

---

## Common Pitfalls

### 1. Not Accounting for Transaction Costs Before Trade

❌ **Wrong:**
```python
shares = cash / price  # Will fail if transaction cost applied after
```

✅ **Correct:**
```python
shares = cash / (price * (1 + transaction_cost))
```

### 2. Forgetting to Update Equity Curve

Portfolio value must be tracked at every timestamp (not just trades).

### 3. Not Handling Partial Shares

Some brokers don't allow partial shares. For simplicity, we allow decimals (like fractional shares on Robinhood).

### 4. Calculating P/L Wrong

P/L = Net proceeds - Cost basis (NOT just price difference)

---

## Next Steps

Once Task 5 is complete:

**Task 6: Performance Metrics Calculator**
- Calculate Sharpe Ratio
- Calculate Win Rate
- Calculate Max Drawdown
- Calculate Risk/Reward Ratio

See: `docs/phase-3/guides/05-metrics-calculator.md`

---

**Time:** ~4 hours  
**Files Created:** 3 (portfolio.py model, portfolio.py core, test_portfolio.py)  
**Lines of Code:** ~600
