# What Are Trading Strategies?

## Overview

A **trading strategy** is an algorithm that analyzes historical market data and produces trading signals (BUY, SELL, or HOLD). It's the "intelligence" layer that sits on top of raw market data.

---

## The Basic Flow

```
INPUT:  Historical tick data from QuestDB
  ↓
LOGIC:  Calculate indicators, apply rules
  ↓
OUTPUT: Trading signal (BUY / SELL / HOLD)
```

---

## Example: Moving Average Crossover

### The Strategy

**Concept:** Short-term trends crossing long-term trends indicate momentum shifts.

**Rules:**
1. Calculate 10-day moving average (MA10)
2. Calculate 50-day moving average (MA50)
3. When MA10 crosses **above** MA50 → **Golden Cross** → BUY signal
4. When MA10 crosses **below** MA50 → **Death Cross** → SELL signal

### Step-by-Step Execution

**Day 1:**
```
AAPL prices (last 50 days): [178, 180, 182, 179, 181, ...]
MA10 = 180.5
MA50 = 178.2
MA10 > MA50  ✓ (but no crossover yet)
Signal: HOLD
```

**Day 2:**
```
AAPL prices (last 50 days): [179, 178, 180, 182, 179, ...]
MA10 = 179.8
MA50 = 178.5
Previous: MA10 was 180.5, MA50 was 178.2
MA10 still > MA50, but decreasing
Signal: HOLD
```

**Day 3:**
```
AAPL prices (last 50 days): [176, 175, 179, 178, 180, ...]
MA10 = 177.6
MA50 = 178.7
Crossover detected! MA10 crossed BELOW MA50
Signal: SELL (Death Cross)
```

---

## Why Strategies Need Historical Data

### ❌ Can't Work on Live Stream Alone

```
Generator → Kafka → Strategy Engine (listening to live ticks)
                         ↓
                    Problem: How to calculate 50-day MA with only current tick?
```

**Issue:** 
- Strategy needs 50 days of history
- Live stream only provides current tick
- Can't calculate MA(50) without 50 data points

### ✅ Must Query Database

```
Generator → Kafka → QuestDB (storage)
                         ↓
              Strategy Engine (queries DB)
              "SELECT price FROM ticks 
               WHERE symbol = 'AAPL' 
               ORDER BY timestamp DESC LIMIT 50"
                         ↓
              Now has 50 days to calculate MA(50)
```

---

## Types of Trading Strategies

### 1. Trend Following

**Philosophy:** "The trend is your friend"

**Logic:** Identify when an asset is trending up or down, ride the trend.

**Examples:**
- Moving Average Crossover
- MACD (Moving Average Convergence Divergence)
- ADX (Average Directional Index)
- Donchian Channel Breakout

**Best for:** Stocks with strong directional movement (AAPL, TSLA during bull runs)

**Weakness:** Choppy, sideways markets (many false signals)

---

### 2. Mean Reversion

**Philosophy:** "What goes up must come down"

**Logic:** Prices deviate from average, but eventually return. Buy when low, sell when high.

**Examples:**
- RSI (Relative Strength Index)
- Bollinger Bands
- Stochastic Oscillator

**Best for:** Range-bound markets (crypto in consolidation)

**Weakness:** Strong trending markets (keeps signaling "sell" during uptrend)

---

### 3. Momentum

**Philosophy:** "Strength begets strength"

**Logic:** Assets moving strongly in one direction tend to continue. Jump on moving trains.

**Examples:**
- Rate of Change (ROC)
- Williams %R

**Best for:** Breakouts, strong directional moves

**Weakness:** Momentum reversals (gets caught on wrong side)

---

### 4. Volume-Based

**Philosophy:** "Volume confirms price"

**Logic:** High volume indicates strong conviction. Price moves with volume are more trustworthy.

**Examples:**
- VWAP (Volume-Weighted Average Price)

**Best for:** Intraday trading, institutional activity

**Weakness:** Low-volume assets (crypto on weekends)

---

## Strategy Components

### 1. Lookback Period

**How much history does the strategy need?**

```
RSI Strategy:     14 days
MA Crossover:     50 days
VWAP:             1 day (intraday only)
```

**Implication:** Can't run strategy until enough data is accumulated.

---

### 2. Calculation Logic

**Core indicator calculation**

```java
// Moving Average
public double calculateMA(List<Double> prices, int period) {
    return prices.stream()
        .limit(period)
        .mapToDouble(Double::doubleValue)
        .average()
        .orElse(0.0);
}
```

---

### 3. Signal Rules

**When to generate BUY/SELL signals**

```java
// Golden Cross detection
if (ma10 > ma50 && previousMA10 <= previousMA50) {
    return new Signal(symbol, "BUY", "MA_CROSSOVER", 0.85);
}
```

---

### 4. Confidence Score

**How confident is the strategy in this signal?**

```
0.0 = No confidence (don't trade)
0.5 = Weak signal (might wait for confirmation)
0.85 = Strong signal (high conviction)
1.0 = Maximum confidence (extremely rare)
```

**Factors affecting confidence:**
- Strength of indicator (RSI at 15 = very oversold = high confidence)
- Volume confirmation (breakout with high volume = higher confidence)
- Multiple indicators agreeing (RSI + Bollinger both say BUY = higher confidence)

---

## Strategy vs. Backtester

**Common confusion:** "What's the difference between running a strategy and backtesting?"

### Strategy (Real-time)

```
Run every minute:
  1. Query latest data
  2. Calculate indicators
  3. Generate signal NOW
  4. Produce to Kafka
  
Purpose: Generate trading signals in real-time
```

### Backtester (Historical)

```
Run once on historical data:
  1. Query ALL historical data (last 60 days)
  2. For each day in past:
     - Pretend we're on that day
     - Calculate indicators with only data UP TO that day
     - Generate signal
     - Simulate trade
  3. Calculate performance metrics
  
Purpose: Evaluate "Would this strategy have worked?"
```

**Key difference:** 
- **Strategy** = Real-time signal generation
- **Backtester** = Historical performance evaluation

---

## Why Multiple Strategies?

**Question:** "If we have 10 strategies, do we take all 10 signals?"

**Answer:** No! Multiple strategies serve different purposes:

### 1. Diversification

Different strategies work in different market conditions:
- **Bull market** → Trend following wins
- **Sideways market** → Mean reversion wins
- **Volatile market** → Momentum wins

Having multiple strategies ensures at least one works in current conditions.

---

### 2. Comparison

**Goal:** Find which strategy works best.

```
After 30 days:
- MA Crossover: +12% return, Sharpe 1.45
- RSI: +8% return, Sharpe 1.32
- Bollinger: +5% return, Sharpe 0.98

Decision: Allocate more capital to MA Crossover
```

---

### 3. Ensemble

**Advanced:** Combine multiple signals.

```
5 strategies say BUY
2 strategies say SELL
3 strategies say HOLD

Ensemble decision: BUY (majority vote)
```

**Better performance:** Reduces false signals, smooths returns.

---

## Strategy Properties

### Idempotent

**Definition:** Running same strategy twice on same data produces same result.

```java
// Always returns same signal for same input
Signal signal1 = strategy.analyze("AAPL"); // BUY
Signal signal2 = strategy.analyze("AAPL"); // BUY (same)
```

**Implication:** Safe to retry, no side effects.

---

### Deterministic

**Definition:** Same inputs always produce same outputs.

```java
// Not deterministic (bad!)
if (Math.random() > 0.5) {
    return BUY;
}

// Deterministic (good!)
if (ma10 > ma50) {
    return BUY;
}
```

**Implication:** Reproducible, testable.

---

### Stateless (mostly)

**Definition:** Each analysis is independent.

**Exception:** Need to store previous indicator values to detect crossovers.

```java
// Need to remember previous MA10 to detect crossover
if (ma10 > ma50 && previousMA10 <= previousMA50) {
    // Crossover detected!
}
```

---

## Real-World Example: MA Crossover in Detail

### Data Preparation

```sql
-- Query QuestDB
SELECT price FROM ticks 
WHERE symbol = 'AAPL' 
ORDER BY timestamp DESC 
LIMIT 50;

Result:
[180.5, 181.2, 179.8, 182.1, 180.9, ...]
```

### Indicator Calculation

```java
List<Double> prices = queryPrices("AAPL", 50);

// Last 10 prices
double ma10 = average(prices[0...9]);  // 180.7

// Last 50 prices
double ma50 = average(prices[0...49]); // 178.3
```

### Signal Generation

```java
// Current state
double ma10 = 180.7;
double ma50 = 178.3;

// Previous state (from 1 minute ago)
double prevMA10 = 178.1;
double prevMA50 = 178.5;

// Check for Golden Cross
if (ma10 > ma50 && prevMA10 <= prevMA50) {
    Signal signal = new Signal(
        symbol: "AAPL",
        action: "BUY",
        strategyName: "MA_CROSSOVER",
        confidence: 0.85,
        timestamp: now()
    );
    
    // Produce to Kafka
    kafkaTemplate.send("trading-signals", signal);
}
```

### Output

```json
{
  "symbol": "AAPL",
  "action": "BUY",
  "strategyName": "MA_CROSSOVER",
  "confidence": 0.85,
  "timestamp": "2026-07-17T10:30:00Z"
}
```

---

## Key Takeaways

1. **Strategies = Algorithms** - Deterministic, idempotent, testable
2. **Need Historical Data** - Can't calculate 50-day MA from live stream alone
3. **Multiple Types** - Trend following, mean reversion, momentum, volume
4. **Different Markets** - Each strategy excels in specific conditions
5. **Signals ≠ Trades** - Strategies generate signals, Phase 3 decides whether to trade
6. **Backtesting Required** - Must validate strategy on historical data before trusting it

---

## Next Concepts

1. **Technical Indicators** - Deep dive into MA, RSI, Bollinger Bands
2. **Interface-Based Design** - How we implement 10 strategies in one service
3. **Strategy Evaluation** - Sharpe ratio, win rate, max drawdown
