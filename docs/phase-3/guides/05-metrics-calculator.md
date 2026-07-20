# Task 6: Performance Metrics Calculator

**Goal:** Calculate performance metrics to evaluate strategy effectiveness (Sharpe Ratio, Win Rate, Max Drawdown, etc.).

**Estimated Time:** 3 hours

---

## Overview

**Performance metrics** answer the question: "Is this strategy good?"

Raw data (trades, equity curve) is hard to interpret. Metrics provide **standardized measurements** that allow us to:
1. Compare strategies objectively
2. Identify strengths and weaknesses
3. Present results to stakeholders
4. Rank strategies on the leaderboard

**What we'll calculate:**
1. **Total Return (%)** - Overall profit/loss
2. **Sharpe Ratio** - Risk-adjusted return
3. **Win Rate (%)** - Percentage of profitable trades
4. **Max Drawdown (%)** - Largest peak-to-trough decline
5. **Average Win/Loss** - Average profit per winning/losing trade
6. **Risk/Reward Ratio** - Ratio of average win to average loss
7. **Profit Factor** - Gross profit / Gross loss
8. **Number of Trades** - Total, winning, and losing trades

---

## Why These Metrics Matter

### 1. Total Return (%)

**Formula:**
```
Total Return = ((Final Value - Initial Capital) / Initial Capital) × 100
```

**Example:**
```
Initial: $10,000
Final: $13,250
Total Return = (($13,250 - $10,000) / $10,000) × 100 = 32.5%
```

**Interpretation:**
- Positive = Profitable strategy
- Negative = Losing strategy
- **Problem:** Ignores risk taken to achieve return

**Why it's not enough:**
- Strategy A: +50% return, 1 trade, got lucky
- Strategy B: +50% return, 100 trades, consistent
- Total return is same, but Strategy B is more reliable!

---

### 2. Sharpe Ratio (Risk-Adjusted Return)

**Formula:**
```
Sharpe Ratio = (Average Return - Risk-Free Rate) / Standard Deviation of Returns

Where:
- Average Return = Mean of per-trade returns
- Risk-Free Rate = Return of "safe" investment (usually 0% for short backtests)
- Standard Deviation = Measure of return volatility
```

**Example:**
```
Trades: [+2%, +3%, -1%, +4%, +2%, -2%, +5%]
Average Return = 1.86%
Std Deviation = 2.41%
Risk-Free Rate = 0%

Sharpe = 1.86% / 2.41% = 0.77
```

**Interpretation:**
- **< 0:** Strategy loses money on average
- **0 - 1:** Not great (high risk for the return)
- **1 - 2:** Good (acceptable risk/return tradeoff)
- **2 - 3:** Very good
- **> 3:** Excellent (low risk for high return)

**Why it matters:**
- Penalizes volatile strategies (inconsistent returns)
- Rewards consistent strategies (predictable returns)
- Industry standard for comparing strategies

**Real Example:**
- Strategy A: +50% return, Sharpe = 0.8 (wild swings)
- Strategy B: +30% return, Sharpe = 2.5 (steady growth)
- **Strategy B is better!** (more predictable, less stressful)

---

### 3. Win Rate (%)

**Formula:**
```
Win Rate = (Profitable Trades / Total Trades) × 100
```

**Example:**
```
Total trades: 100
Profitable: 68
Losing: 32
Win Rate = 68%
```

**Interpretation:**
- **< 50%:** More losses than wins (needs large wins to be profitable)
- **50%:** Break-even (coin flip)
- **> 50%:** More wins than losses
- **> 70%:** Very high win rate (might be over-fitting)

**Important:** Win rate alone is misleading!
- Strategy A: 90% win rate, avg win = $10, avg loss = $1,000 → **Loses money**
- Strategy B: 40% win rate, avg win = $500, avg loss = $100 → **Makes money**

**Must combine with Average Win/Loss!**

---

### 4. Max Drawdown (%)

**Formula:**
```
Drawdown = (Peak Value - Current Value) / Peak Value
Max Drawdown = Largest drawdown during entire backtest
```

**Example:**
```
Equity curve:
Day 1: $10,000
Day 5: $12,000 (peak)
Day 7: $11,000 (trough after peak)
Day 10: $13,000 (new peak)
Day 12: $12,500

Drawdown at Day 7 = ($12,000 - $11,000) / $12,000 = 8.33%
Drawdown at Day 12 = ($13,000 - $12,500) / $13,000 = 3.85%

Max Drawdown = 8.33% (largest)
```

**Interpretation:**
- **0 - 10%:** Low risk (small declines)
- **10 - 20%:** Moderate risk (bearable declines)
- **20 - 30%:** High risk (stressful declines)
- **> 30%:** Very high risk (many would quit)

**Why it matters:**
- Shows worst-case scenario
- Psychological factor (can you stomach a 30% drop?)
- Risk management (prevents account blowup)

**Real-world example:**
- Your portfolio hits $50,000
- It drops to $35,000 (30% drawdown)
- Can you handle that stress? Or will you panic-sell?

---

### 5. Average Win vs Average Loss

**Formula:**
```
Average Win = Sum of all profitable trades / Number of winning trades
Average Loss = Sum of all losing trades / Number of losing trades
```

**Example:**
```
Winning trades: [+$500, +$300, +$400, +$600] → Avg Win = $450
Losing trades: [-$200, -$150, -$100] → Avg Loss = $150
```

**Interpretation:**
- Compare magnitude of wins vs losses
- Need average win > average loss for sustainable profits
- Combined with win rate, tells the full story

**Example combinations:**
1. **High win rate, small wins:** 80% win rate, $100 avg win, $2,000 avg loss → **Bad** (rare losses wipe out gains)
2. **Low win rate, large wins:** 40% win rate, $1,000 avg win, $200 avg loss → **Good** (few big wins offset many small losses)

---

### 6. Risk/Reward Ratio

**Formula:**
```
Risk/Reward Ratio = Average Win / Average Loss
```

**Example:**
```
Average Win = $450
Average Loss = $150
Risk/Reward = $450 / $150 = 3.0
```

**Interpretation:**
- **< 1:** Average win is smaller than average loss (need high win rate)
- **1 - 2:** Decent (typical for mean reversion strategies)
- **2 - 3:** Good (wins are 2-3x larger than losses)
- **> 3:** Excellent (large wins compensate for losses)

**Rule of thumb:**
- Win rate × Risk/Reward should be > 1 for profitability
- Example: 50% win rate × 2.0 R/R = 1.0 (break-even)
- Example: 40% win rate × 3.0 R/R = 1.2 (profitable)

---

### 7. Profit Factor

**Formula:**
```
Profit Factor = Gross Profit / Gross Loss
```

**Example:**
```
Winning trades: +$500, +$300, +$400 = $1,200 gross profit
Losing trades: -$200, -$150 = $350 gross loss
Profit Factor = $1,200 / $350 = 3.43
```

**Interpretation:**
- **< 1.0:** Loses money (gross loss > gross profit)
- **1.0:** Break-even
- **1.0 - 1.5:** Marginally profitable (fragile)
- **1.5 - 2.0:** Decent profitability
- **> 2.0:** Strong profitability

**Why it matters:**
- Single number that summarizes win rate + avg win/loss
- Profit Factor = 2.0 means you make $2 for every $1 lost
- Easy to understand for non-technical stakeholders

---

### 8. Number of Trades

**Why it matters:**
1. **Statistical significance:** 10 trades = luck, 500 trades = pattern
2. **Transaction costs:** 100 trades × $10 fee = $1,000 in costs
3. **Strategy type indicator:**
   - High-frequency: 500+ trades/month
   - Day trading: 50-100 trades/month
   - Swing trading: 10-30 trades/month
   - Position trading: 1-5 trades/month

---

## Metrics Example: Full Breakdown

**Backtest Period:** 30 days  
**Strategy:** RSI Mean Reversion  
**Initial Capital:** $10,000

### Trade Results
```
Total Trades: 100
Winning Trades: 68
Losing Trades: 32

Winning trades P/L: [+$150, +$200, +$180, ... ] (68 trades)
Losing trades P/L: [-$80, -$120, -$90, ... ] (32 trades)

Gross Profit: $14,200
Gross Loss: $3,100
Net Profit: $11,100
```

### Calculated Metrics
```
1. Total Return = ($11,100 / $10,000) × 100 = 111%

2. Win Rate = (68 / 100) × 100 = 68%

3. Average Win = $14,200 / 68 = $208.82
   Average Loss = $3,100 / 32 = $96.88

4. Risk/Reward Ratio = $208.82 / $96.88 = 2.16

5. Profit Factor = $14,200 / $3,100 = 4.58

6. Sharpe Ratio = (calculated from return series) = 1.85

7. Max Drawdown = -8.4% (equity dropped from $18,500 to $16,945)
```

### Interpretation
- ✅ **Excellent strategy:** Sharpe > 1.5, Profit Factor > 2.0
- ✅ **High win rate:** 68% of trades profitable
- ✅ **Good risk management:** Max drawdown under 10%
- ✅ **Positive risk/reward:** Wins are 2x larger than losses
- ✅ **Statistical significance:** 100 trades is enough to trust results

---

## Implementation Design

### MetricsCalculator Class

**Input:** Portfolio object with trades and equity curve  
**Output:** Metrics dictionary

**Methods:**
1. `calculate_total_return()` → float
2. `calculate_sharpe_ratio()` → float
3. `calculate_win_rate()` → float
4. `calculate_max_drawdown()` → float
5. `calculate_average_win()` → float
6. `calculate_average_loss()` → float
7. `calculate_risk_reward_ratio()` → float
8. `calculate_profit_factor()` → float
9. `calculate_all_metrics()` → BacktestMetrics (Pydantic model)

---

## Step 1: Create Metrics Model

Create `app/models/metrics.py`:

```python
from pydantic import BaseModel, Field
from typing import Optional


class BacktestMetrics(BaseModel):
    """Complete set of backtest performance metrics."""
    
    # Return Metrics
    total_return_pct: float = Field(description="Total return percentage")
    final_portfolio_value: float = Field(description="Final portfolio value in dollars")
    
    # Trade Count Metrics
    num_trades: int = Field(description="Total number of trades")
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
    max_drawdown_pct: float = Field(description="Maximum peak-to-trough decline percentage")
    
    # Derived Metrics
    risk_reward_ratio: float = Field(description="Average win / Average loss")
    profit_factor: float = Field(description="Gross profit / Gross loss")
    
    # Optional: For strategies with no trades
    has_trades: bool = Field(default=True, description="Whether any trades were executed")
```

---

## Step 2: Implement Metrics Calculator

Create `app/core/metrics.py`:

```python
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
```

---

## Step 3: Test Metrics Calculator

Create `tests/test_metrics.py`:

(See implementation section for full test code)

---

## Success Criteria Checklist

- [ ] `app/models/metrics.py` created with BacktestMetrics model
- [ ] `app/core/metrics.py` created with MetricsCalculator class
- [ ] Total Return calculated correctly
- [ ] Win Rate calculated correctly
- [ ] Sharpe Ratio calculated from equity curve
- [ ] Max Drawdown finds largest peak-to-trough decline
- [ ] Average Win/Loss calculated from profitable/losing trades
- [ ] Risk/Reward Ratio = Avg Win / Avg Loss
- [ ] Profit Factor = Gross Profit / Gross Loss
- [ ] All tests pass with realistic scenarios
- [ ] Edge cases handled (no trades, all wins, all losses)

---

## Expected Test Scenarios

### Scenario 1: Profitable Strategy
```
100 trades, 70% win rate
Total Return: +50%
Sharpe Ratio: 2.1
Max Drawdown: -12%
```

### Scenario 2: Losing Strategy
```
50 trades, 30% win rate
Total Return: -20%
Sharpe Ratio: -0.5
Max Drawdown: -35%
```

### Scenario 3: No Trades
```
0 trades
All metrics should be 0 or N/A
has_trades = False
```

---

## Frontend Display

With these metrics, the frontend can show:

### Strategy Card
```
┌─────────────────────────────────────┐
│ RSI Mean Reversion                  │
├─────────────────────────────────────┤
│ Total Return: +32.5% 📈             │
│ Sharpe Ratio: 1.85 ⭐⭐⭐⭐          │
│ Win Rate: 68% ✅                    │
│ Max Drawdown: -8.4% 📉              │
│ Profit Factor: 4.58                 │
└─────────────────────────────────────┘
```

### Detailed Metrics Table
```
| Metric               | Value      |
|----------------------|------------|
| Total Return         | +32.5%     |
| Sharpe Ratio         | 1.85       |
| Win Rate             | 68%        |
| Profit Factor        | 4.58       |
| Risk/Reward Ratio    | 2.16       |
| Max Drawdown         | -8.4%      |
| Total Trades         | 100        |
| Winning Trades       | 68         |
| Losing Trades        | 32         |
| Average Win          | $208.82    |
| Average Loss         | $96.88     |
| Gross Profit         | $14,200    |
| Gross Loss           | $3,100     |
| Net Profit           | $11,100    |
```

---

## Next Steps

Once Task 6 is complete:

**Task 7: Strategy Implementation**
- Create base strategy class
- Implement all 10 strategies
- Wire strategies with indicators and portfolio

See: `docs/phase-3/guides/06-strategy-implementation.md`

---

**Time:** ~3 hours  
**Files Created:** 3 (metrics.py model, metrics.py core, test_metrics.py)  
**Lines of Code:** ~500
