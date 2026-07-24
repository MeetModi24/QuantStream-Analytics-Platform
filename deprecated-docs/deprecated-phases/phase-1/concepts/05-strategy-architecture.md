# Trading Strategy Architecture Explained

## Overview

This document explains **when**, **where**, and **how** alpha trading strategies fit into the QuantStream system architecture. It answers the fundamental question: "We're generating market data now, but when do strategies come into play?"

---

## The Big Picture: Three-Phase Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 1: DATA FOUNDATION                      │
│                     (Current - Building Now)                     │
├─────────────────────────────────────────────────────────────────┤
│  Generator → Kafka → Consumer → QuestDB                         │
│                                                                  │
│  Purpose: Build data infrastructure & accumulate historical data│
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 2: STRATEGY ENGINE                      │
│                      (Future - After Phase 1)                    │
├─────────────────────────────────────────────────────────────────┤
│  QuestDB → Strategy Engine → Trading Signals → Kafka            │
│                                                                  │
│  Purpose: Apply alpha strategies to generate trading signals    │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                  PHASE 3-4: STRATEGY EVALUATION                  │
│                      (Future - After Phase 2)                    │
├─────────────────────────────────────────────────────────────────┤
│  Signals → Backtester → Performance Metrics → Dashboard         │
│                                                                  │
│  Purpose: Evaluate which strategies work best                   │
└─────────────────────────────────────────────────────────────────┘
```

---

## What is an Alpha Strategy?

**Alpha Strategy** = An algorithm that analyzes market data and produces trading signals.

### Input → Logic → Output

```
INPUT:  Historical market prices (from QuestDB)
  ↓
LOGIC:  Calculate indicators, apply rules
  ↓
OUTPUT: Trading signal (BUY / SELL / HOLD)
```

### Example: Simple Moving Average Crossover

```
INPUT:
- Last 50 days of AAPL prices from QuestDB
- Current: MA(10) = $184.50, MA(50) = $182.00

LOGIC:
- Short-term MA (10-day) crosses above long-term MA (50-day)
- This is a "Golden Cross" - bullish signal

OUTPUT:
- Signal: BUY AAPL
- Confidence: 85%
- Timestamp: 2026-07-15T10:30:00Z
```

---

## Why Strategies Come AFTER Data Storage

### ❌ Bad Approach: Strategy on Live Stream

```
Generator → Kafka → Strategy Engine (listening to live stream)
                         ↓
                    Trading Signals
```

**Problems:**

1. **No Historical Context**
   - Strategy needs 50-day moving average
   - Live stream only has current tick
   - Can't calculate indicators

2. **Can't Backtest**
   - Historical data is ephemeral
   - No way to test "Would this have worked last month?"

3. **Hard to Debug**
   - Streaming data disappears
   - Can't replay to find bugs

### ✅ Good Approach: Strategy on Stored Data

```
Generator → Kafka → QuestDB (storage)
                         ↓
              Strategy Engine (queries DB)
                         ↓
                  Trading Signals
```

**Benefits:**

1. **Full Historical Access**
   - Query any time range: "Last 50 days of AAPL"
   - Calculate complex indicators
   - Access complete market context

2. **Backtest Capability**
   - Replay strategy on past data
   - Test: "If I ran this strategy last year, what would have happened?"
   - Validate before deploying

3. **Easy Debugging**
   - Data persists in database
   - Reproduce exact conditions
   - Analyze why strategy made a decision

---

## The Complete Data Flow (All Phases)

### Phase 1: Data Generation (Current)

```
┌──────────────────────────────────────────────────────────────┐
│ 1. Market Data Generator                                     │
│    - Simulates 10 tokens (AAPL, BTC, ETH, etc.)            │
│    - Uses GBM algorithm for realistic price movement        │
│    - Generates 10 ticks/second (1 per token)                │
└──────────────────────────────────────────────────────────────┘
                        ↓ (sends to Kafka)
┌──────────────────────────────────────────────────────────────┐
│ 2. Kafka Topic: "market-data"                               │
│    - Reliable message queue                                  │
│    - Decouples producer from consumer                        │
│    - Handles backpressure                                    │
└──────────────────────────────────────────────────────────────┘
                        ↓ (consumed by)
┌──────────────────────────────────────────────────────────────┐
│ 3. Database Consumer                                         │
│    - Reads from Kafka                                        │
│    - Batches writes for efficiency                          │
│    - Writes to QuestDB                                       │
└──────────────────────────────────────────────────────────────┘
                        ↓ (persists to)
┌──────────────────────────────────────────────────────────────┐
│ 4. QuestDB (Time-Series Database)                           │
│    - Stores all historical ticks                             │
│    - Optimized for time-series queries                       │
│    - Enables fast indicator calculations                     │
└──────────────────────────────────────────────────────────────┘

PHASE 1 GOAL: Accumulate 2-4 weeks of historical data
```

### Phase 2: Strategy Application (Future)

```
┌──────────────────────────────────────────────────────────────┐
│ 5. Strategy Engine                                           │
│    - Runs multiple strategies in parallel                    │
│    - Each strategy is a separate service                     │
└──────────────────────────────────────────────────────────────┘
                        ↓ (strategies query)
┌──────────────────────────────────────────────────────────────┐
│ QuestDB                                                      │
│ "SELECT price FROM market_data                               │
│  WHERE symbol = 'AAPL'                                       │
│  ORDER BY timestamp DESC LIMIT 50"                           │
└──────────────────────────────────────────────────────────────┘
                        ↓ (calculate indicators)
┌──────────────────────────────────────────────────────────────┐
│ Strategy Logic                                               │
│ - Calculate MA(10), MA(50)                                   │
│ - Check for crossover                                        │
│ - Generate signal if conditions met                          │
└──────────────────────────────────────────────────────────────┘
                        ↓ (produce signals)
┌──────────────────────────────────────────────────────────────┐
│ Kafka Topic: "trading-signals"                              │
│ {                                                            │
│   "symbol": "AAPL",                                         │
│   "action": "BUY",                                          │
│   "strategy": "MA_CROSSOVER",                               │
│   "confidence": 0.85,                                       │
│   "timestamp": "2026-07-15T10:30:00Z"                       │
│ }                                                            │
└──────────────────────────────────────────────────────────────┘

PHASE 2 GOAL: Generate trading signals from multiple strategies
```

### Phase 3-4: Strategy Evaluation (Future)

```
┌──────────────────────────────────────────────────────────────┐
│ 6. Strategy Evaluator (Backtester)                          │
│    - Reads historical data from QuestDB                      │
│    - Simulates running strategy on past data                 │
│    - Tracks virtual portfolio performance                    │
└──────────────────────────────────────────────────────────────┘
                        ↓ (calculates)
┌──────────────────────────────────────────────────────────────┐
│ Performance Metrics                                          │
│ - Sharpe Ratio: 1.45                                        │
│ - Win Rate: 62%                                             │
│ - Max Drawdown: -15%                                        │
│ - Total Return: +28%                                        │
│ - Trade Count: 47                                           │
└──────────────────────────────────────────────────────────────┘
                        ↓ (compare)
┌──────────────────────────────────────────────────────────────┐
│ Strategy Rankings                                            │
│ 1. MA Crossover    - Sharpe: 1.45 ⭐⭐⭐⭐⭐                 │
│ 2. RSI Mean Rev    - Sharpe: 1.32 ⭐⭐⭐⭐                   │
│ 3. Bollinger Bands - Sharpe: 0.98 ⭐⭐⭐                     │
└──────────────────────────────────────────────────────────────┘
                        ↓ (visualize)
┌──────────────────────────────────────────────────────────────┐
│ Dashboard                                                    │
│ - Performance charts                                         │
│ - Equity curves                                             │
│ - Drawdown graphs                                           │
│ - Trade history                                             │
└──────────────────────────────────────────────────────────────┘

PHASE 3-4 GOAL: Identify which strategies work best
```

---

## Three Strategy Examples

### Strategy 1: Moving Average Crossover (Simple)

**Type:** Trend-following  
**Difficulty:** Beginner  
**Data Required:** 50 days of price history

```java
@Service
public class MovingAverageCrossoverStrategy implements TradingStrategy {
    
    @Scheduled(fixedRate = 60000)  // Run every minute
    public void analyzeAndSignal() {
        for (String symbol : activeSymbols) {
            // Query QuestDB for last 50 prices
            List<Double> prices = questDbClient.query(
                "SELECT price FROM market_data " +
                "WHERE symbol = ? " +
                "ORDER BY timestamp DESC LIMIT 50",
                symbol
            );
            
            // Calculate moving averages
            double ma10 = calculateMA(prices, 10);
            double ma50 = calculateMA(prices, 50);
            
            // Store previous values for crossover detection
            double prevMA10 = previousMA10.get(symbol);
            double prevMA50 = previousMA50.get(symbol);
            
            // Detect Golden Cross (bullish)
            if (ma10 > ma50 && prevMA10 <= prevMA50) {
                Signal signal = new Signal(
                    symbol, 
                    "BUY", 
                    "MA_CROSSOVER",
                    0.85, 
                    Instant.now()
                );
                kafkaTemplate.send("trading-signals", signal);
            }
            
            // Detect Death Cross (bearish)
            else if (ma10 < ma50 && prevMA10 >= prevMA50) {
                Signal signal = new Signal(
                    symbol, 
                    "SELL", 
                    "MA_CROSSOVER",
                    0.85, 
                    Instant.now()
                );
                kafkaTemplate.send("trading-signals", signal);
            }
            
            // Update previous values
            previousMA10.put(symbol, ma10);
            previousMA50.put(symbol, ma50);
        }
    }
    
    private double calculateMA(List<Double> prices, int period) {
        return prices.stream()
            .limit(period)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    }
}
```

**When it trades:**
- **BUY:** When short-term MA crosses above long-term MA (Golden Cross)
- **SELL:** When short-term MA crosses below long-term MA (Death Cross)

**Best for:** Trending markets (stocks with consistent upward/downward movement)

---

### Strategy 2: RSI Mean Reversion (Intermediate)

**Type:** Mean-reversion  
**Difficulty:** Intermediate  
**Data Required:** 14 days of price history

```java
@Service
public class RSIMeanReversionStrategy implements TradingStrategy {
    
    private static final double OVERSOLD_THRESHOLD = 30.0;
    private static final double OVERBOUGHT_THRESHOLD = 70.0;
    
    @Scheduled(fixedRate = 60000)
    public void analyzeAndSignal() {
        for (String symbol : activeSymbols) {
            // Query QuestDB for last 14 days
            List<Double> prices = questDbClient.query(
                "SELECT price FROM market_data " +
                "WHERE symbol = ? " +
                "ORDER BY timestamp DESC LIMIT 14",
                symbol
            );
            
            // Calculate RSI (Relative Strength Index)
            double rsi = calculateRSI(prices);
            
            // Oversold - expect bounce up
            if (rsi < OVERSOLD_THRESHOLD) {
                Signal signal = new Signal(
                    symbol, 
                    "BUY", 
                    "RSI_MEAN_REVERSION",
                    (OVERSOLD_THRESHOLD - rsi) / OVERSOLD_THRESHOLD, // Higher confidence = more oversold
                    Instant.now()
                );
                kafkaTemplate.send("trading-signals", signal);
            }
            
            // Overbought - expect pullback
            else if (rsi > OVERBOUGHT_THRESHOLD) {
                Signal signal = new Signal(
                    symbol, 
                    "SELL", 
                    "RSI_MEAN_REVERSION",
                    (rsi - OVERBOUGHT_THRESHOLD) / (100 - OVERBOUGHT_THRESHOLD),
                    Instant.now()
                );
                kafkaTemplate.send("trading-signals", signal);
            }
        }
    }
    
    private double calculateRSI(List<Double> prices) {
        // Calculate price changes
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        
        for (int i = 0; i < prices.size() - 1; i++) {
            double change = prices.get(i) - prices.get(i + 1);
            if (change > 0) {
                gains.add(change);
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(Math.abs(change));
            }
        }
        
        // Calculate average gain and loss
        double avgGain = gains.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgLoss = losses.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        // Calculate RS and RSI
        if (avgLoss == 0) return 100.0;
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
}
```

**When it trades:**
- **BUY:** When RSI < 30 (oversold, expect bounce)
- **SELL:** When RSI > 70 (overbought, expect pullback)

**Best for:** Range-bound markets (stocks oscillating around a mean price)

---

### Strategy 3: Bollinger Bands Breakout (Advanced)

**Type:** Volatility breakout  
**Difficulty:** Advanced  
**Data Required:** 20 days of price history

```java
@Service
public class BollingerBandsStrategy implements TradingStrategy {
    
    private static final int BB_PERIOD = 20;
    private static final double BB_STD_DEV = 2.0;
    
    @Scheduled(fixedRate = 60000)
    public void analyzeAndSignal() {
        for (String symbol : activeSymbols) {
            // Query QuestDB for last 20 days
            List<Double> prices = questDbClient.query(
                "SELECT price FROM market_data " +
                "WHERE symbol = ? " +
                "ORDER BY timestamp DESC LIMIT 20",
                symbol
            );
            
            double currentPrice = prices.get(0);
            
            // Calculate Bollinger Bands
            double middleBand = prices.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double stdDev = calculateStdDev(prices, middleBand);
            double upperBand = middleBand + (BB_STD_DEV * stdDev);
            double lowerBand = middleBand - (BB_STD_DEV * stdDev);
            
            // Price touches lower band - oversold
            if (currentPrice <= lowerBand) {
                Signal signal = new Signal(
                    symbol, 
                    "BUY", 
                    "BOLLINGER_BANDS",
                    (lowerBand - currentPrice) / stdDev, // Distance from band = confidence
                    Instant.now()
                );
                kafkaTemplate.send("trading-signals", signal);
            }
            
            // Price touches upper band - overbought
            else if (currentPrice >= upperBand) {
                Signal signal = new Signal(
                    symbol, 
                    "SELL", 
                    "BOLLINGER_BANDS",
                    (currentPrice - upperBand) / stdDev,
                    Instant.now()
                );
                kafkaTemplate.send("trading-signals", signal);
            }
        }
    }
    
    private double calculateStdDev(List<Double> prices, double mean) {
        double variance = prices.stream()
            .mapToDouble(price -> Math.pow(price - mean, 2))
            .average()
            .orElse(0.0);
        return Math.sqrt(variance);
    }
}
```

**When it trades:**
- **BUY:** When price touches or breaks below lower Bollinger Band
- **SELL:** When price touches or breaks above upper Bollinger Band

**Best for:** Volatile markets with clear support/resistance levels

---

## Why This Phase Order Matters

### ❌ Building Strategies First (Without Data)

```
Week 1: Build MovingAverageCrossoverStrategy
Problem: No historical data to test it on!
         Can't calculate MA(50) with 0 days of data
         
Week 2: Build RSI strategy
Problem: Still no data accumulating
         Can't verify if indicators are calculating correctly
         
Result: Strategies exist but untested and unvalidated
```

### ❌ Building Evaluator First (Without Strategies)

```
Week 1: Build Strategy Evaluator
Problem: No strategies to evaluate!
         Evaluator is just an empty shell
         
Week 2: Add performance metrics calculation
Problem: Still nothing to calculate metrics for
         
Result: Evaluator waits idle with nothing to do
```

### ✅ Data First (The Right Way)

```
Week 1-2: Build Generator → Kafka → Consumer → QuestDB
          Let system run 24/7
Result:   2 weeks of historical data accumulating
          Foundation ready for strategy development

Week 3-4: Build 3 strategies (MA, RSI, Bollinger)
          Test on 2 weeks of real data
          Iterate and refine based on results
Result:   Working strategies producing signals
          Now have something to evaluate

Week 5-6: Build Strategy Evaluator
          Backtest all 3 strategies on 4 weeks of data
          Calculate performance metrics
          Rank strategies
Result:   Know which strategies work best
          Data-driven strategy selection
```

---

## Timeline & Dependencies

```
┌─────────────────────────────────────────────────────────────────┐
│ PHASE 1: Foundation (Weeks 1-2)                                 │
├─────────────────────────────────────────────────────────────────┤
│ Build:                                                           │
│ ✅ Task 4: Market Data Generator (DONE)                         │
│ 🚧 Task 5: Database Consumer (NEXT)                             │
│                                                                  │
│ Deliverable: Working data pipeline, 2 weeks of historical data  │
│ Blocker for: Phase 2 (need data to test strategies)            │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ PHASE 2: Strategies (Weeks 3-4)                                 │
├─────────────────────────────────────────────────────────────────┤
│ Build:                                                           │
│ ⏳ Strategy Engine framework                                    │
│ ⏳ MovingAverageCrossoverStrategy                               │
│ ⏳ RSIMeanReversionStrategy                                     │
│ ⏳ BollingerBandsStrategy                                       │
│                                                                  │
│ Deliverable: 3 strategies producing trading signals             │
│ Depends on: Phase 1 (need historical data)                     │
│ Blocker for: Phase 3 (need strategies to evaluate)             │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│ PHASE 3-4: Evaluation (Weeks 5-6)                               │
├─────────────────────────────────────────────────────────────────┤
│ Build:                                                           │
│ ⏳ Strategy Evaluator (Backtester)                              │
│ ⏳ Performance metrics calculation                              │
│ ⏳ Strategy comparison dashboard                                │
│                                                                  │
│ Deliverable: Know which strategies work best                    │
│ Depends on: Phase 1 (data) + Phase 2 (strategies)              │
└─────────────────────────────────────────────────────────────────┘
```

---

## Key Takeaways

### 1. Strategies Apply to STORED Data (Not Live Stream)

Strategies query QuestDB for historical data because:
- Need 50-200 days of history for indicators
- Can backtest on past data
- Data persists for debugging

### 2. Build Order Matters

```
Data → Strategies → Evaluation
 ↑        ↑            ↑
Must     Can't        Can't
come     work         compare
first    without      without
         data         strategies
```

### 3. Current Status

**✅ Phase 1 (90% Complete):**
- Market data generator: DONE
- Database consumer: NEXT

**⏳ Phase 2 (Not Started):**
- Strategy engine: WAITING for Phase 1
- Alpha strategies: WAITING for Phase 1

**⏳ Phase 3-4 (Not Started):**
- Strategy evaluator: WAITING for Phase 2
- Performance metrics: WAITING for Phase 2

### 4. What You're Building

```
NOW:       Data infrastructure (pipes, storage)
NEXT:      Alpha strategies (trading logic)
LATER:     Evaluation framework (find best strategy)
```

---

## Next Steps

1. **Complete Phase 1:** Finish Database Consumer (Task 5)
2. **Let it Run:** Accumulate 2-4 weeks of data
3. **Start Phase 2:** Build first alpha strategy (Moving Average Crossover)

**Remember:** You can't trade what you can't see, and you can't evaluate what doesn't exist. Data comes first! 🎯
