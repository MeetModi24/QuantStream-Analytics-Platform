# Parameter Optimization in Backtesting: Complete Guide

## The Core Question

**"What is parameter optimization and what will the backtesting engine actually DO with it?"**

This document explains parameter optimization from first principles, with concrete examples from our 10 trading strategies.

---

## What is a Strategy Parameter?

A **parameter** is a configurable value in a trading strategy that affects when signals are generated.

### Example: RSI Strategy

```python
class RSIStrategy:
    def __init__(self, period=14, oversold=30, overbought=70):
        self.period = period          # Parameter 1
        self.oversold = oversold      # Parameter 2
        self.overbought = overbought  # Parameter 3
    
    def generate_signals(self, prices):
        rsi = calculate_rsi(prices, period=self.period)
        
        if rsi < self.oversold:
            return 'BUY'   # Oversold condition
        elif rsi > self.overbought:
            return 'SELL'  # Overbought condition
        else:
            return 'HOLD'
```

**Three Parameters:**
1. **period** = How many days to calculate RSI (default: 14)
2. **oversold** = RSI threshold for BUY signal (default: 30)
3. **overbought** = RSI threshold for SELL signal (default: 70)

**The Question:** Are these default values the BEST? What if period=10 works better? What if oversold=25 is better?

---

## What is Parameter Optimization?

**Parameter Optimization** = Testing MANY different parameter combinations to find which one gives the BEST performance.

### Analogy: Cooking Recipe

Imagine you're making a cake:
- **Flour:** 200g (parameter 1)
- **Sugar:** 100g (parameter 2)
- **Eggs:** 2 (parameter 3)

**Question:** Is this the BEST recipe?

**Optimization Process:**
1. Try: 200g flour, 100g sugar, 2 eggs → Taste score: 7/10
2. Try: 250g flour, 100g sugar, 2 eggs → Taste score: 6/10
3. Try: 200g flour, 150g sugar, 2 eggs → Taste score: 9/10 ✅ Best!
4. Try: 200g flour, 150g sugar, 3 eggs → Taste score: 8/10

**Result:** 200g flour, 150g sugar, 2 eggs is the BEST combination.

**Trading Strategy Optimization is the SAME:**
- Try different parameter combinations
- Measure performance (Sharpe ratio, return, win rate)
- Find the combination that gives the best results

---

## Real Example: RSI Strategy Optimization

### Production Configuration (Current)

```python
# Current production settings
rsi_strategy = RSIStrategy(
    period=14,
    oversold=30,
    overbought=70
)
```

**Question:** Is this optimal? Let's test!

### Step 1: Define Parameter Grid

```python
# Parameters to test
periods = [10, 12, 14, 16, 18, 20]        # 6 options
oversolds = [25, 30, 35]                  # 3 options
overboughts = [65, 70, 75]                # 3 options

# Total combinations: 6 × 3 × 3 = 54 different configurations
```

### Step 2: Run Backtest for Each Combination

```python
results = []

for period in periods:
    for oversold in oversolds:
        for overbought in overboughts:
            # Create strategy with these parameters
            strategy = RSIStrategy(
                period=period,
                oversold=oversold,
                overbought=overbought
            )
            
            # Run backtest on historical data (last 30 days)
            result = backtester.run_backtest(
                strategy=strategy,
                symbol='AAPL',
                start_date='2026-06-19',
                end_date='2026-07-19',
                initial_capital=10000
            )
            
            # Store results
            results.append({
                'period': period,
                'oversold': oversold,
                'overbought': overbought,
                'total_return': result.total_return,
                'sharpe_ratio': result.sharpe_ratio,
                'win_rate': result.win_rate,
                'max_drawdown': result.max_drawdown
            })

# Sort by Sharpe ratio (best first)
results.sort(key=lambda x: x['sharpe_ratio'], reverse=True)

# Best configuration
best = results[0]
print(f"Best Config: period={best['period']}, oversold={best['oversold']}, overbought={best['overbought']}")
print(f"Sharpe Ratio: {best['sharpe_ratio']}")
```

### Step 3: Results Example

```
Testing 54 different configurations...

Results (top 5):
┌──────┬──────────┬────────────┬────────┬──────────┬──────────┐
│ Rank │ Period   │ Oversold   │ Overb. │ Return   │ Sharpe   │
├──────┼──────────┼────────────┼────────┼──────────┼──────────┤
│  1   │ 12       │ 25         │ 75     │ +38.2%   │ 2.15     │ ✅ BEST!
│  2   │ 14       │ 30         │ 70     │ +32.5%   │ 1.85     │ (current)
│  3   │ 10       │ 25         │ 70     │ +31.8%   │ 1.78     │
│  4   │ 14       │ 25         │ 75     │ +29.4%   │ 1.65     │
│  5   │ 16       │ 30         │ 70     │ +28.1%   │ 1.52     │
└──────┴──────────┴────────────┴────────┴──────────┴──────────┘

Discovery: Using period=12, oversold=25, overbought=75 gives:
- 17.5% better return (38.2% vs 32.5%)
- 16% better Sharpe ratio (2.15 vs 1.85)

Recommendation: Update production config to use optimized parameters!
```

---

## What the Backtesting Engine Does

### Process Flow

```
┌─────────────────────────────────────────────────────────────┐
│  STEP 1: Fetch Historical Data                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │ Query: Get 30 days of AAPL ticks from QuestDB     │    │
│  │ Result: 2.6M tick records                          │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 2: For Each Parameter Combination...                  │
│  ┌────────────────────────────────────────────────────┐    │
│  │ Config 1: period=10, oversold=25, overbought=65   │    │
│  │                                                     │    │
│  │ 1. Calculate RSI with period=10                    │    │
│  │ 2. Generate signals: BUY when RSI<25, SELL>65     │    │
│  │ 3. Simulate portfolio:                             │    │
│  │    - Start: $10,000                                │    │
│  │    - BUY signal at $175 → Buy 57 shares           │    │
│  │    - SELL signal at $185 → Sell 57 shares         │    │
│  │    - End: $10,570                                  │    │
│  │ 4. Calculate metrics:                              │    │
│  │    - Total Return: +5.7%                           │    │
│  │    - Sharpe Ratio: 0.82                            │    │
│  │    - Win Rate: 55%                                 │    │
│  └────────────────────────────────────────────────────┘    │
│                                                              │
│  Repeat for Config 2, Config 3, ... Config 54              │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 3: Compare All Results                                │
│  ┌────────────────────────────────────────────────────┐    │
│  │ Sort by Sharpe Ratio (risk-adjusted return)       │    │
│  │ Find: Config 23 (period=12, oversold=25, over=75) │    │
│  │ Has highest Sharpe: 2.15                           │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────────┐
│  STEP 4: Return Best Configuration                          │
│  ┌────────────────────────────────────────────────────┐    │
│  │ Recommendation: Use period=12, oversold=25, over=75│    │
│  │ Expected Performance:                               │    │
│  │   - Total Return: +38.2%                           │    │
│  │   - Sharpe Ratio: 2.15                             │    │
│  │   - Win Rate: 71%                                  │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
```

---

## Concrete Example: MA Crossover Strategy

### Strategy Description

```python
class MACrossoverStrategy:
    def __init__(self, fast_period=10, slow_period=50):
        self.fast_period = fast_period  # Short-term MA
        self.slow_period = slow_period  # Long-term MA
    
    def generate_signals(self, prices):
        ma_fast = calculate_sma(prices, self.fast_period)
        ma_slow = calculate_sma(prices, self.slow_period)
        
        # Golden cross: Fast MA crosses above Slow MA
        if ma_fast[-1] > ma_slow[-1] and ma_fast[-2] <= ma_slow[-2]:
            return 'BUY'
        
        # Death cross: Fast MA crosses below Slow MA
        elif ma_fast[-1] < ma_slow[-1] and ma_fast[-2] >= ma_slow[-2]:
            return 'SELL'
        
        return 'HOLD'
```

### Parameter Optimization

**Question:** What's the best combination of fast and slow periods?

**Parameter Grid:**
```python
fast_periods = [5, 10, 15, 20]          # 4 options
slow_periods = [30, 40, 50, 60, 70]     # 5 options

# Total: 4 × 5 = 20 combinations
```

**Testing Process:**

```
Combination 1: MA(5, 30)
- Fast MA changes quickly, many signals
- Result: Total Return: +15%, Sharpe: 0.95, Trades: 87

Combination 2: MA(10, 50)  [Current production]
- Balanced signals
- Result: Total Return: +28%, Sharpe: 1.45, Trades: 42

Combination 3: MA(20, 70)
- Slow to react, fewer signals
- Result: Total Return: +12%, Sharpe: 0.68, Trades: 18

...test 17 more combinations...

Best: MA(15, 60)
- Result: Total Return: +34%, Sharpe: 1.78, Trades: 35
- 21% better return than current config!
```

---

## Why Can't We Use Signals Table for This?

### Problem: Signals Table Has Fixed Parameters

```
Signals table contains:
- Signals generated with RSI(period=14, oversold=30, overbought=70)
- ALL signals use THESE parameters
- Cannot test RSI(period=12, oversold=25, overbought=75)
```

### Solution: Backtest Engine Replays Strategy

```
Backtest engine:
1. Fetches raw tick data (no signals yet)
2. Replays RSI strategy with period=12, oversold=25, overbought=75
3. Generates NEW signals with THESE parameters
4. Simulates portfolio with these signals
5. Measures performance

Can repeat for ANY parameter combination!
```

---

## Parameter Optimization for All 10 Strategies

### 1. RSI Mean Reversion
**Parameters:**
- `period`: RSI calculation period (default: 14)
- `oversold`: Buy threshold (default: 30)
- `overbought`: Sell threshold (default: 70)

**Optimization Goal:** Find period, oversold, and overbought that maximize Sharpe ratio

---

### 2. MA Crossover
**Parameters:**
- `fast_period`: Short-term MA (default: 10)
- `slow_period`: Long-term MA (default: 50)

**Optimization Goal:** Find MA periods that catch trends early without false signals

---

### 3. MACD
**Parameters:**
- `fast_period`: Fast EMA (default: 12)
- `slow_period`: Slow EMA (default: 26)
- `signal_period`: Signal line (default: 9)

**Optimization Goal:** Find MACD configuration with best signal timing

---

### 4. Bollinger Bands
**Parameters:**
- `period`: BB calculation period (default: 20)
- `std_dev`: Number of standard deviations (default: 2)

**Optimization Goal:** Find BB width that captures reversals accurately

---

### 5. Stochastic Oscillator
**Parameters:**
- `k_period`: %K period (default: 14)
- `d_period`: %D period (default: 3)
- `overbought`: Sell threshold (default: 80)
- `oversold`: Buy threshold (default: 20)

**Optimization Goal:** Find stochastic parameters that identify momentum shifts

---

### 6. Williams %R
**Parameters:**
- `period`: Lookback period (default: 14)
- `overbought`: Sell threshold (default: -20)
- `oversold`: Buy threshold (default: -80)

**Optimization Goal:** Find Williams %R thresholds for best entries/exits

---

### 7. ADX Trend Strength
**Parameters:**
- `period`: ADX period (default: 14)
- `threshold`: Minimum ADX for trend (default: 25)

**Optimization Goal:** Find ADX threshold that filters out weak trends

---

### 8. Donchian Channel
**Parameters:**
- `period`: Channel period (default: 20)
- `breakout_buffer`: Buffer above/below channel (default: 0)

**Optimization Goal:** Find channel period that captures breakouts early

---

### 9. ROC (Rate of Change)
**Parameters:**
- `period`: ROC period (default: 12)
- `buy_threshold`: Buy when ROC crosses above (default: 0)
- `sell_threshold`: Sell when ROC crosses below (default: 0)

**Optimization Goal:** Find ROC period and thresholds for momentum

---

### 10. VWAP Deviation
**Parameters:**
- `period`: VWAP period (default: 20)
- `std_threshold`: Standard deviation threshold (default: 2)

**Optimization Goal:** Find VWAP deviation that signals mean reversion

---

## The Optimization API Endpoint

```
POST /api/backtest/optimize
```

**Request:**
```json
{
  "strategy": "RSI_MEAN_REVERSION",
  "symbol": "AAPL",
  "start_date": "2026-06-19",
  "end_date": "2026-07-19",
  "initial_capital": 10000,
  "parameter_grid": {
    "period": [10, 12, 14, 16, 18, 20],
    "oversold": [25, 30, 35],
    "overbought": [65, 70, 75]
  },
  "optimize_for": "sharpe_ratio"
}
```

**Response:**
```json
{
  "optimization_id": "opt-12345",
  "total_combinations": 54,
  "best_parameters": {
    "period": 12,
    "oversold": 25,
    "overbought": 75
  },
  "best_metrics": {
    "total_return": 38.2,
    "sharpe_ratio": 2.15,
    "win_rate": 71.0,
    "max_drawdown": -6.8
  },
  "all_results": [
    {
      "parameters": {"period": 12, "oversold": 25, "overbought": 75},
      "sharpe_ratio": 2.15,
      "total_return": 38.2
    },
    {
      "parameters": {"period": 14, "oversold": 30, "overbought": 70},
      "sharpe_ratio": 1.85,
      "total_return": 32.5
    },
    ...52 more results...
  ],
  "execution_time_seconds": 12.5
}
```

---

## The Danger: Overfitting

### What is Overfitting?

**Overfitting** = Finding parameters that work PERFECTLY on historical data but FAIL on future data.

### Example: Lucky Parameters

```
Test Period: June 1-30, 2026 (AAPL data)

Result of optimization:
- Best config: RSI(period=17, oversold=31.5, overbought=68.3)
- Sharpe Ratio: 3.45 (amazing!)

Reality Check (test on July data):
- Same config on July 1-30, 2026
- Sharpe Ratio: 0.12 (terrible!)

Problem: Parameters were "fit" to June's specific market conditions
These conditions didn't repeat in July!
```

### Visual Example

```
June 2026 (Training Data):
  AAPL had: Strong uptrend, then correction, then recovery
  RSI(17, 31.5, 68.3) caught this pattern perfectly!

July 2026 (Real Trading):
  AAPL had: Sideways choppy movement
  RSI(17, 31.5, 68.3) generated 50 false signals!

The parameters "memorized" June's pattern, not general principles!
```

---

## Solution: Walk-Forward Testing (Future Enhancement)

### Traditional Backtesting (Overfitting Risk)

```
Train on ALL data (June 1 - July 30)
  ↓
Find best parameters
  ↓
Report performance on SAME data ❌ (Overfitted!)
```

### Walk-Forward Testing (Robust)

```
Train Period 1: June 1-20 → Find best parameters → Config A
Test Period 1: June 21-30 → Use Config A → Measure real performance

Train Period 2: June 11-30 → Find best parameters → Config B
Test Period 2: July 1-10 → Use Config B → Measure real performance

Train Period 3: June 21-July 10 → Find best parameters → Config C
Test Period 3: July 11-20 → Use Config C → Measure real performance

Average all test period performances = True expected performance ✅
```

**Phase 3 Scope:** Basic optimization (single period)
**Future Enhancement:** Walk-forward testing

---

## Summary: What Backtesting Engine Does

### 1. **Single Backtest**
```
Input: Strategy with FIXED parameters + Historical data
Process: Replay strategy, simulate portfolio
Output: Performance metrics (return, Sharpe, win rate)

Use Case: "How did RSI(14, 30, 70) perform last month?"
```

### 2. **Parameter Optimization**
```
Input: Strategy + Parameter grid + Historical data
Process: Run backtest for EVERY parameter combination
Output: Best parameters + Performance comparison

Use Case: "What's the BEST RSI configuration?"
```

### 3. **Strategy Comparison**
```
Input: Multiple strategies + Historical data
Process: Run backtest for each strategy
Output: Leaderboard ranking strategies

Use Case: "Which strategy performed best: RSI, MACD, or MA?"
```

### 4. **Multi-Symbol Analysis**
```
Input: Strategy + Multiple symbols (AAPL, BTC, ETH, etc.)
Process: Run backtest on each symbol
Output: Per-symbol performance

Use Case: "Does RSI work better on stocks or crypto?"
```

---

## Practical Example: Full Optimization Flow

```python
# User clicks "Optimize RSI Strategy" on frontend

# Backend receives request
optimization_request = {
    "strategy": "RSI_MEAN_REVERSION",
    "symbol": "AAPL",
    "start_date": "2026-06-19",
    "end_date": "2026-07-19",
    "parameter_grid": {
        "period": [10, 12, 14, 16, 18, 20],
        "oversold": [25, 30, 35],
        "overbought": [65, 70, 75]
    }
}

# Backtest engine processes
for period in [10, 12, 14, 16, 18, 20]:
    for oversold in [25, 30, 35]:
        for overbought in [65, 70, 75]:
            # 1. Fetch ticks from QuestDB
            ticks = fetch_ticks("AAPL", "2026-06-19", "2026-07-19")
            
            # 2. Create strategy with these params
            strategy = RSIStrategy(period, oversold, overbought)
            
            # 3. Generate signals
            signals = strategy.generate_signals(ticks)
            
            # 4. Simulate portfolio
            portfolio = Portfolio(10000)
            for signal in signals:
                portfolio.execute(signal)
            
            # 5. Calculate metrics
            metrics = calculate_metrics(portfolio)
            
            # 6. Store result
            results.append({
                'params': {'period': period, 'oversold': oversold, 'overbought': overbought},
                'metrics': metrics
            })

# 7. Find best
best = max(results, key=lambda x: x['metrics']['sharpe_ratio'])

# 8. Return to frontend
return {
    "best_parameters": best['params'],
    "best_performance": best['metrics'],
    "all_results": results
}

# Frontend displays:
# "Best Config: RSI(12, 25, 75) - Sharpe: 2.15 - Return: +38.2%"
```

---

## Key Takeaways

1. **Parameter Optimization** = Testing many configurations to find the best
2. **Can't use signals table** = It has fixed parameters from production
3. **Must replay strategy** = Generate signals with different parameters
4. **Backtest engine does this** = Automated testing of all combinations
5. **Risk: Overfitting** = Parameters work on past data but fail on future
6. **Solution: Validation** = Test on separate time period

**Next Step:** Implement this in Python with FastAPI!
