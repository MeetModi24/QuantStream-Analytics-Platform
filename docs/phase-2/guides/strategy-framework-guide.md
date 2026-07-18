# Strategy Framework Guide

## Overview

This guide implements the **core framework** that all 10 strategies will use:
- `TradingStrategy` interface (contract)
- `StrategyScheduler` (runs all strategies every minute)
- `IndicatorUtils` (shared calculations)
- Kafka topic setup

**By the end:** You'll have the infrastructure ready to implement strategies.

---

## Prerequisites

- [ ] Task 1 complete (strategy-engine project setup)
- [ ] Application starts without errors
- [ ] QuestDB and Kafka connections verified

---

## Architecture Recap

```
┌──────────────────────────────────────────────────────┐
│            strategy-engine Service                   │
│                                                       │
│  ┌────────────────────────────────────────────────┐ │
│  │         StrategyScheduler                      │ │
│  │   @Scheduled(fixedRate = 60000)                │ │
│  │                                                 │ │
│  │   for (TradingStrategy strategy : strategies) {│ │
│  │       Signal signal = strategy.analyze(symbol);│ │
│  │       kafkaTemplate.send(signal);              │ │
│  │   }                                             │ │
│  └────────────────────────────────────────────────┘ │
│                        ↓                             │
│  ┌──────┐  ┌──────┐  ┌──────┐      ┌──────┐       │
│  │ MA   │  │ RSI  │  │ MACD │ ...  │ VWAP │       │
│  │Strat │  │Strat │  │Strat │      │Strat │       │
│  └──────┘  └──────┘  └──────┘      └──────┘       │
│       ↘        ↘         ↘            ↘            │
│         implements TradingStrategy (interface)     │
│                        ↓                            │
│              ┌──────────────────┐                  │
│              │ IndicatorUtils   │                  │
│              │ (shared helpers) │                  │
│              └──────────────────┘                  │
└──────────────────────────────────────────────────────┘
                        ↓
               Kafka: trading-signals
                        ↓
            database-consumer (Task 5 - extended)
```

---

## Step 1: Create TradingStrategy Interface

Create `src/main/java/com/quantstream/strategy/framework/TradingStrategy.java`:

```java
package com.quantstream.strategy.framework;

import com.quantstream.strategy.model.Signal;

/**
 * Contract that all trading strategies must implement.
 * 
 * Design Pattern: Strategy Pattern (GoF)
 * - Defines family of algorithms (trading strategies)
 * - Encapsulates each one in a class
 * - Makes them interchangeable (all implement same interface)
 * 
 * Spring Auto-Discovery:
 * - Classes implementing this interface + @Component annotation
 * - Are automatically discovered by Spring
 * - Injected as List<TradingStrategy> into StrategyScheduler
 */
public interface TradingStrategy {
    
    /**
     * Strategy identifier.
     * 
     * Used in Signal.strategyName to track which strategy generated signal.
     * 
     * @return Unique strategy name (e.g., "MA_CROSSOVER", "RSI", "MACD")
     */
    String getName();
    
    /**
     * Minimum historical data required.
     * 
     * Examples:
     * - MA(50) strategy needs 50 days
     * - RSI strategy needs 14 days
     * - VWAP strategy needs 1 day (intraday only)
     * 
     * Used to:
     * 1. Validate sufficient data before running strategy
     * 2. Document strategy requirements
     * 3. Optimize database queries
     * 
     * @return Number of days of historical data required
     */
    int getRequiredHistoryDays();
    
    /**
     * Core strategy logic.
     * 
     * Executed by StrategyScheduler every minute for each symbol.
     * 
     * Implementation pattern:
     * 1. Query historical data from QuestDB
     * 2. Calculate indicators (MA, RSI, etc.)
     * 3. Apply strategy rules
     * 4. Generate signal if conditions met
     * 5. Return null if no signal
     * 
     * Error handling:
     * - Throw exceptions for critical errors (DB connection failure)
     * - Return null for expected cases (not enough data, no signal)
     * - Scheduler will catch exceptions and log them
     * 
     * Thread safety:
     * - Strategies are Spring singletons (one instance per strategy)
     * - Scheduler runs single-threaded (no concurrent calls)
     * - Safe to store state in instance variables (e.g., previous MA values)
     * 
     * @param symbol Stock/crypto symbol to analyze (e.g., "AAPL", "BTC")
     * @return Signal if strategy conditions met, null otherwise
     */
    Signal analyze(String symbol);
}
```

### Interview Question: "Why interface instead of abstract class?"

**Answer:**

| Aspect | Interface | Abstract Class |
|--------|-----------|----------------|
| **Multiple inheritance** | ✅ Can implement many | ❌ Can extend only one |
| **Flexibility** | ✅ Looser coupling | ❌ Tighter coupling |
| **Contract** | ✅ Pure contract | Contains implementation |
| **Strategy pattern** | ✅ Recommended | ❌ Not idiomatic |

**Our case:**
- No shared implementation (each strategy is unique)
- Want pure contract (3 methods, no fields)
- May want to implement other interfaces in future (e.g., `Monitorable`)

**If we had shared code:** Could create `AbstractTradingStrategy` implementing `TradingStrategy` with helper methods. But for now, interface is cleaner.

---

## Step 2: Create IndicatorUtils

Create `src/main/java/com/quantstream/strategy/utils/IndicatorUtils.java`:

```java
package com.quantstream.strategy.utils;

import com.quantstream.strategy.model.Tick;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Shared utility methods for technical indicator calculations.
 * 
 * Why separate class:
 * - Avoids duplication (MA used by multiple strategies)
 * - Easier to test (test once, works for all strategies)
 * - Single source of truth (change MA calculation in one place)
 * 
 * Why @Component:
 * - Allows dependency injection into strategies
 * - Spring manages lifecycle
 * - Can add caching/metrics later
 */
@Component
public class IndicatorUtils {
    
    // ============================================
    // Moving Average
    // ============================================
    
    /**
     * Simple Moving Average (SMA)
     * 
     * Formula: (p1 + p2 + ... + pN) / N
     * 
     * Use case: Smooth price data, identify trends
     * 
     * @param prices Historical prices (most recent first)
     * @param period Number of periods to average
     * @return SMA value
     */
    public double calculateMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        return prices.stream()
            .limit(period)
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
    }
    
    /**
     * Exponential Moving Average (EMA)
     * 
     * Formula: EMA = price * k + EMA(previous) * (1 - k)
     *          where k = 2 / (period + 1)
     * 
     * Difference from SMA:
     * - More weight on recent prices
     * - Reacts faster to price changes
     * - Used in MACD
     * 
     * @param prices Historical prices (most recent first)
     * @param period Number of periods
     * @return EMA value
     */
    public double calculateEMA(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        double multiplier = 2.0 / (period + 1);
        
        // Start with SMA of first N prices
        double ema = prices.subList(prices.size() - period, prices.size())
            .stream()
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0.0);
        
        // Apply EMA formula to recent prices
        for (int i = prices.size() - period - 1; i >= 0; i--) {
            ema = (prices.get(i) * multiplier) + (ema * (1 - multiplier));
        }
        
        return ema;
    }
    
    // ============================================
    // RSI (Relative Strength Index)
    // ============================================
    
    /**
     * Relative Strength Index
     * 
     * Formula:
     * 1. Calculate gains and losses over period
     * 2. Average gain = sum(gains) / period
     * 3. Average loss = sum(losses) / period
     * 4. RS = average gain / average loss
     * 5. RSI = 100 - (100 / (1 + RS))
     * 
     * Range: 0 to 100
     * - RSI > 70: Overbought
     * - RSI < 30: Oversold
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 14
     * @return RSI value
     */
    public double calculateRSI(List<Double> prices, int period) {
        if (prices.size() <= period) {
            throw new IllegalArgumentException(
                String.format("Need at least %d prices for RSI(%d), got %d", 
                    period + 1, period, prices.size())
            );
        }
        
        List<Double> gains = new ArrayList<>();
        List<Double> losses = new ArrayList<>();
        
        // Calculate price changes (recent to old)
        for (int i = 0; i < period; i++) {
            double change = prices.get(i) - prices.get(i + 1);
            if (change > 0) {
                gains.add(change);
                losses.add(0.0);
            } else {
                gains.add(0.0);
                losses.add(Math.abs(change));
            }
        }
        
        // Average gains and losses
        double avgGain = gains.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double avgLoss = losses.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        
        // Handle division by zero
        if (avgLoss == 0) {
            return 100.0; // All gains, no losses
        }
        
        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
    
    // ============================================
    // Bollinger Bands
    // ============================================
    
    /**
     * Bollinger Bands
     * 
     * Components:
     * - Middle Band: 20-day SMA
     * - Upper Band: Middle + (2 × Standard Deviation)
     * - Lower Band: Middle - (2 × Standard Deviation)
     * 
     * Use case:
     * - Price near upper band → Overbought
     * - Price near lower band → Oversold
     * - Bands narrow → Low volatility → Breakout coming
     * - Bands wide → High volatility
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 20
     * @param stdDevMultiplier Typically 2.0
     * @return BollingerBands object
     */
    public BollingerBands calculateBollingerBands(List<Double> prices, int period, double stdDevMultiplier) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        // Middle band (SMA)
        double middleBand = calculateMA(prices, period);
        
        // Standard deviation
        List<Double> recentPrices = prices.subList(0, period);
        double variance = recentPrices.stream()
            .mapToDouble(price -> Math.pow(price - middleBand, 2))
            .average()
            .orElse(0.0);
        double stdDev = Math.sqrt(variance);
        
        // Upper and lower bands
        double upperBand = middleBand + (stdDevMultiplier * stdDev);
        double lowerBand = middleBand - (stdDevMultiplier * stdDev);
        
        return new BollingerBands(upperBand, middleBand, lowerBand);
    }
    
    // ============================================
    // MACD (Moving Average Convergence Divergence)
    // ============================================
    
    /**
     * MACD indicator
     * 
     * Components:
     * - MACD Line: EMA(12) - EMA(26)
     * - Signal Line: EMA(9) of MACD Line
     * - Histogram: MACD Line - Signal Line
     * 
     * Signals:
     * - MACD crosses above signal → Bullish
     * - MACD crosses below signal → Bearish
     * - Histogram growing → Trend strengthening
     * 
     * Note: This is a simplified single-call version.
     * Full implementation needs history of MACD values for signal line.
     * 
     * @param prices Historical prices (most recent first)
     * @return MACD object (line only, signal requires state tracking)
     */
    public MACD calculateMACD(List<Double> prices) {
        if (prices.size() < 26) {
            throw new IllegalArgumentException(
                String.format("Need at least 26 prices for MACD, got %d", prices.size())
            );
        }
        
        double ema12 = calculateEMA(prices, 12);
        double ema26 = calculateEMA(prices, 26);
        double macdLine = ema12 - ema26;
        
        // Signal line requires previous MACD values (state tracking)
        // Strategies will handle this in their analyze() method
        return new MACD(macdLine, 0.0, 0.0);
    }
    
    // ============================================
    // Stochastic Oscillator
    // ============================================
    
    /**
     * Stochastic Oscillator
     * 
     * Formula:
     * %K = ((Current - Lowest Low) / (Highest High - Lowest Low)) × 100
     * %D = 3-day SMA of %K
     * 
     * Range: 0 to 100
     * - %K > 80: Overbought
     * - %K < 20: Oversold
     * - %K crosses above %D: Bullish
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 14
     * @return Stochastic object
     */
    public Stochastic calculateStochastic(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        double currentClose = prices.get(0);
        List<Double> periodPrices = prices.subList(0, period);
        
        double highestHigh = Collections.max(periodPrices);
        double lowestLow = Collections.min(periodPrices);
        
        double percentK = ((currentClose - lowestLow) / (highestHigh - lowestLow)) * 100;
        
        // %D requires history of %K values (state tracking in strategy)
        return new Stochastic(percentK, 0.0);
    }
    
    // ============================================
    // Williams %R
    // ============================================
    
    /**
     * Williams %R
     * 
     * Formula: ((Highest High - Close) / (Highest High - Lowest Low)) × -100
     * 
     * Range: -100 to 0 (note negative!)
     * - %R > -20: Overbought
     * - %R < -80: Oversold
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 14
     * @return Williams %R value
     */
    public double calculateWilliamsR(List<Double> prices, int period) {
        if (prices.size() < period) {
            throw new IllegalArgumentException(
                String.format("Not enough data: need %d prices, got %d", period, prices.size())
            );
        }
        
        double currentClose = prices.get(0);
        List<Double> periodPrices = prices.subList(0, period);
        
        double highestHigh = Collections.max(periodPrices);
        double lowestLow = Collections.min(periodPrices);
        
        return ((highestHigh - currentClose) / (highestHigh - lowestLow)) * -100;
    }
    
    // ============================================
    // Rate of Change (ROC)
    // ============================================
    
    /**
     * Rate of Change
     * 
     * Formula: ((Current Price - Price N Periods Ago) / Price N Periods Ago) × 100
     * 
     * Shows momentum as percentage change.
     * - ROC > 0: Upward momentum
     * - ROC < 0: Downward momentum
     * - ROC crossing 0: Trend change
     * 
     * @param prices Historical prices (most recent first)
     * @param period Typically 10
     * @return ROC percentage
     */
    public double calculateROC(List<Double> prices, int period) {
        if (prices.size() <= period) {
            throw new IllegalArgumentException(
                String.format("Need at least %d prices for ROC(%d), got %d", 
                    period + 1, period, prices.size())
            );
        }
        
        double currentPrice = prices.get(0);
        double priceNPeriodsAgo = prices.get(period);
        
        return ((currentPrice - priceNPeriodsAgo) / priceNPeriodsAgo) * 100;
    }
    
    // ============================================
    // VWAP (Volume-Weighted Average Price)
    // ============================================
    
    /**
     * Volume-Weighted Average Price
     * 
     * Formula: Sum(Price × Volume) / Sum(Volume)
     * 
     * "True" average price accounting for trade size.
     * - Price < VWAP: Undervalued
     * - Price > VWAP: Overvalued
     * 
     * Typically used intraday (reset daily).
     * 
     * @param ticks Historical ticks with volume (most recent first)
     * @return VWAP value
     */
    public double calculateVWAP(List<Tick> ticks) {
        if (ticks.isEmpty()) {
            throw new IllegalArgumentException("Need at least 1 tick for VWAP");
        }
        
        double sumPriceVolume = 0;
        double sumVolume = 0;
        
        for (Tick tick : ticks) {
            sumPriceVolume += tick.getPrice() * tick.getVolume();
            sumVolume += tick.getVolume();
        }
        
        if (sumVolume == 0) {
            throw new IllegalArgumentException("Total volume is zero");
        }
        
        return sumPriceVolume / sumVolume;
    }
    
    // ============================================
    // Supporting Data Classes
    // ============================================
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BollingerBands {
        private double upper;
        private double middle;
        private double lower;
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MACD {
        private double line;        // MACD line
        private double signal;      // Signal line
        private double histogram;   // MACD - Signal
    }
    
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Stochastic {
        private double percentK;
        private double percentD;
    }
}
```

### Interview Question: "Why not make these static methods?"

**Answer:**

| Static Methods | Instance Methods (@Component) |
|----------------|------------------------------|
| No dependency injection | ✅ Can inject dependencies |
| Hard to mock in tests | ✅ Easy to mock |
| No lifecycle hooks | ✅ Can use @PostConstruct |
| No Spring proxy | ✅ Can add caching/AOP later |
| Simpler | More flexible |

**Our choice:** Instance methods (@Component)

**Why:**
- Future-proof: Can add caching later (e.g., cache MA(50) for 1 minute)
- Testable: Can mock IndicatorUtils in strategy tests
- Consistent: Matches Spring patterns

**Trade-off:** Slight overhead of bean management, but negligible for our use case.

---

## Step 3: Create StrategyScheduler

Create `src/main/java/com/quantstream/strategy/framework/StrategyScheduler.java`:

```java
package com.quantstream.strategy.framework;

import com.quantstream.strategy.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;

/**
 * Scheduler that runs all trading strategies periodically.
 * 
 * Design:
 * - Spring auto-discovers all @Component classes implementing TradingStrategy
 * - Injects them as List<TradingStrategy>
 * - Runs each strategy every minute
 * - Sends signals to Kafka
 * 
 * Error Handling:
 * - Individual strategy failures don't stop other strategies
 * - Errors logged but not re-thrown
 * - Scheduler continues on next cycle
 */
@Component
public class StrategyScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(StrategyScheduler.class);
    
    /**
     * All strategies implementing TradingStrategy interface.
     * Spring auto-injects ALL @Component classes implementing the interface.
     */
    @Autowired
    private List<TradingStrategy> strategies;
    
    /**
     * Kafka producer for sending signals.
     */
    @Autowired
    private KafkaTemplate<String, Signal> kafkaTemplate;
    
    /**
     * Symbols to analyze (injected from application.yml).
     * 
     * Format in application.yml:
     *   strategy.symbols: AAPL,MSFT,GOOGL,TSLA,AMZN,BTC,ETH,SOL,AVAX,MATIC
     * 
     * Benefits:
     * - No recompilation needed to change symbols
     * - Different symbols per environment (dev/prod)
     * - Easy to add/remove symbols via config
     */
    @Value("${strategy.symbols}")
    private String symbolsConfig;
    
    private List<String> symbols;
    
    /**
     * Initialize symbols list on startup.
     */
    @PostConstruct
    public void init() {
        this.symbols = Arrays.asList(symbolsConfig.split(","));
        log.info("Initialized with {} symbols: {}", symbols.size(), symbols);
    }
    
    /**
     * Main scheduled task.
     * 
     * Runs every 60 seconds (configurable via ${strategy.execution.interval-ms}).
     * 
     * Execution:
     * - For each strategy
     * - For each symbol
     * - Call strategy.analyze(symbol)
     * - If signal returned, send to Kafka
     * 
     * Performance:
     * - 10 strategies × 10 symbols = 100 analyses per minute
     * - Each analysis: ~10ms (DB query + calculation)
     * - Total: ~1 second per cycle
     */
    @Scheduled(fixedRateString = "${strategy.execution.interval-ms}")
    public void runAllStrategies() {
        log.info("=== Running {} strategies for {} symbols ===", 
                strategies.size(), symbols.size());
        
        int signalsGenerated = 0;
        int errorsEncountered = 0;
        
        for (TradingStrategy strategy : strategies) {
            for (String symbol : symbols) {
                try {
                    Signal signal = strategy.analyze(symbol);
                    
                    if (signal != null) {
                        // Send to Kafka
                        kafkaTemplate.send("trading-signals", signal);
                        signalsGenerated++;
                        
                        log.debug("Signal: {} {} from {} (confidence: {:.2f})", 
                                 signal.getAction(), 
                                 symbol, 
                                 strategy.getName(), 
                                 signal.getConfidence());
                    }
                    
                } catch (IllegalArgumentException e) {
                    // Expected errors (not enough data, invalid input)
                    log.debug("Strategy {} skipped {}: {}", 
                             strategy.getName(), symbol, e.getMessage());
                    
                } catch (Exception e) {
                    // Unexpected errors (DB connection, etc.)
                    log.error("Strategy {} failed for {}: {}", 
                             strategy.getName(), symbol, e.getMessage(), e);
                    errorsEncountered++;
                }
            }
        }
        
        log.info("=== Completed: {} signals generated, {} errors ===", 
                signalsGenerated, errorsEncountered);
    }
}
```

### Interview Question: "Why fixedRate instead of fixedDelay?"

**Answer:**

| fixedRate | fixedDelay |
|-----------|------------|
| 60s from **start** of previous execution | 60s from **end** of previous execution |
| Guaranteed regular intervals | Variable intervals |
| Can overlap if execution > rate | Never overlaps |

**Example:**

**fixedRate = 60000:**
```
00:00 → Start execution (takes 5s)
00:05 → Finish execution
00:60 → Start execution (60s from first start)
01:05 → Finish execution
01:60 → Start execution
```

**fixedDelay = 60000:**
```
00:00 → Start execution (takes 5s)
00:05 → Finish execution
01:05 → Start execution (60s after finish)
01:10 → Finish execution
02:10 → Start execution
```

**Our choice:** `fixedRate` because we want strategies to run at **consistent times** (every minute on the minute), not drift over time.

---

## Step 4: Create Kafka Topic

Create script to set up Kafka topic.

Create `scripts/create-kafka-topics.sh`:

```bash
#!/bin/bash

# Create trading-signals topic if it doesn't exist

docker exec -it kafka kafka-topics --create \
  --bootstrap-server localhost:9092 \
  --topic trading-signals \
  --partitions 1 \
  --replication-factor 1 \
  --if-not-exists

# List all topics to verify
echo "Current Kafka topics:"
docker exec -it kafka kafka-topics --list \
  --bootstrap-server localhost:9092
```

Make it executable:

```bash
chmod +x scripts/create-kafka-topics.sh
```

Run it:

```bash
./scripts/create-kafka-topics.sh
```

**Expected output:**
```
Created topic trading-signals.
Current Kafka topics:
market-data
trading-signals
```

### Interview Question: "Why 1 partition?"

**Answer:**

**Partitions** allow parallel consumption. Multiple consumers can read different partitions simultaneously.

**Our case:**
- No need for parallel consumption
- 1 partition = simplest setup

**Production consideration:**
- Multiple partitions for high throughput
- Partition by symbol (all AAPL signals → partition 0)
- Allows parallel processing

---

## Step 5: Test Framework

Let's verify everything works before implementing strategies.

### Test 1: Scheduler Detects Zero Strategies

**Expected behavior:** Scheduler runs but finds no strategies yet.

```bash
mvn spring-boot:run
```

**Expected logs:**
```
2026-07-17 10:00:00 INFO  StrategyScheduler - === Running 0 strategies for 10 symbols ===
2026-07-17 10:00:00 INFO  StrategyScheduler - === Completed: 0 signals generated, 0 errors ===
```

✅ **Success!** Scheduler works, just no strategies yet.

---

### Test 2: Create Dummy Strategy

Create `src/main/java/com/quantstream/strategy/strategies/DummyStrategy.java`:

```java
package com.quantstream.strategy.strategies;

import com.quantstream.strategy.framework.TradingStrategy;
import com.quantstream.strategy.model.Signal;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Dummy strategy for testing framework.
 * 
 * DELETE THIS FILE after verifying framework works!
 */
@Component
public class DummyStrategy implements TradingStrategy {
    
    @Override
    public String getName() {
        return "DUMMY_TEST";
    }
    
    @Override
    public int getRequiredHistoryDays() {
        return 1;
    }
    
    @Override
    public Signal analyze(String symbol) {
        // Always return BUY signal for testing
        return new Signal(
            symbol,
            "BUY",
            getName(),
            0.99,
            Instant.now()
        );
    }
}
```

Restart application:

```bash
mvn spring-boot:run
```

**Expected logs:**
```
2026-07-17 10:01:00 INFO  StrategyScheduler - === Running 1 strategies for 10 symbols ===
2026-07-17 10:01:00 DEBUG StrategyScheduler - Signal: BUY AAPL from DUMMY_TEST (confidence: 0.99)
2026-07-17 10:01:00 DEBUG StrategyScheduler - Signal: BUY MSFT from DUMMY_TEST (confidence: 0.99)
...
2026-07-17 10:01:00 INFO  StrategyScheduler - === Completed: 10 signals generated, 0 errors ===
```

✅ **Success!** Framework discovers and runs strategy.

---

### Test 3: Verify Kafka Signals

```bash
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic trading-signals \
  --from-beginning \
  --max-messages 5
```

**Expected output:**
```json
{"symbol":"AAPL","action":"BUY","strategyName":"DUMMY_TEST","confidence":0.99,"timestamp":"2026-07-17T10:01:00Z"}
{"symbol":"MSFT","action":"BUY","strategyName":"DUMMY_TEST","confidence":0.99,"timestamp":"2026-07-17T10:01:00Z"}
{"symbol":"GOOGL","action":"BUY","strategyName":"DUMMY_TEST","confidence":0.99,"timestamp":"2026-07-17T10:01:00Z"}
{"symbol":"TSLA","action":"BUY","strategyName":"DUMMY_TEST","confidence":0.99,"timestamp":"2026-07-17T10:01:00Z"}
{"symbol":"AMZN","action":"BUY","strategyName":"DUMMY_TEST","confidence":0.99,"timestamp":"2026-07-17T10:01:00Z"}
```

✅ **Perfect!** Signals are flowing to Kafka.

---

### Test 4: Test IndicatorUtils

Create `src/test/java/com/quantstream/strategy/utils/IndicatorUtilsTest.java`:

```java
package com.quantstream.strategy.utils;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class IndicatorUtilsTest {
    
    @Autowired
    private IndicatorUtils indicators;
    
    @Test
    void testCalculateMA() {
        List<Double> prices = List.of(10.0, 11.0, 12.0, 13.0, 14.0);
        double ma = indicators.calculateMA(prices, 5);
        assertEquals(12.0, ma, 0.01);
    }
    
    @Test
    void testCalculateRSI() {
        // Prices with upward trend (should give high RSI)
        List<Double> prices = List.of(
            120.0, 119.0, 118.0, 117.0, 116.0, 115.0, 114.0, 113.0,
            112.0, 111.0, 110.0, 109.0, 108.0, 107.0, 106.0
        );
        
        double rsi = indicators.calculateRSI(prices, 14);
        assertTrue(rsi > 0 && rsi < 100);
    }
    
    @Test
    void testCalculateBollingerBands() {
        List<Double> prices = List.of(
            100.0, 102.0, 101.0, 103.0, 102.0, 104.0, 103.0, 105.0,
            104.0, 106.0, 105.0, 107.0, 106.0, 108.0, 107.0, 109.0,
            108.0, 110.0, 109.0, 111.0
        );
        
        IndicatorUtils.BollingerBands bb = indicators.calculateBollingerBands(prices, 20, 2.0);
        
        assertTrue(bb.getUpper() > bb.getMiddle());
        assertTrue(bb.getMiddle() > bb.getLower());
    }
}
```

Run tests:

```bash
mvn test
```

**Expected output:**
```
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
```

✅ **All tests pass!**

---

## Step 6: Clean Up Test Code

**Delete DummyStrategy:**

```bash
rm src/main/java/com/quantstream/strategy/strategies/DummyStrategy.java
```

Restart application:

```bash
mvn spring-boot:run
```

**Expected logs:**
```
2026-07-17 10:05:00 INFO  StrategyScheduler - === Running 0 strategies for 10 symbols ===
2026-07-17 10:05:00 INFO  StrategyScheduler - === Completed: 0 signals generated, 0 errors ===
```

✅ **Back to zero strategies.** Framework is ready!

---

## Final Project Structure

```
strategy-engine/
├── src/
│   ├── main/
│   │   ├── java/com/quantstream/strategy/
│   │   │   ├── StrategyEngineApplication.java
│   │   │   ├── config/
│   │   │   │   ├── KafkaProducerConfig.java
│   │   │   │   └── QuestDBConfig.java
│   │   │   ├── model/
│   │   │   │   ├── Signal.java
│   │   │   │   └── Tick.java
│   │   │   ├── framework/                    ← NEW
│   │   │   │   ├── TradingStrategy.java      ← Interface
│   │   │   │   └── StrategyScheduler.java    ← Scheduler
│   │   │   ├── utils/                        ← NEW
│   │   │   │   └── IndicatorUtils.java       ← Shared calculations
│   │   │   └── strategies/                   ← Empty (ready for Task 3)
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/
│           └── com/quantstream/strategy/utils/
│               └── IndicatorUtilsTest.java   ← Tests
├── scripts/
│   └── create-kafka-topics.sh                ← Kafka setup
└── pom.xml
```

---

## Summary

**What You Built:**
- ✅ `TradingStrategy` interface (contract for all strategies)
- ✅ `IndicatorUtils` (shared calculation methods)
- ✅ `StrategyScheduler` (runs all strategies every minute)
- ✅ Kafka topic `trading-signals`
- ✅ Tests verifying framework works
- ✅ Spring auto-discovery working

**Next Task:**
Proceed to **Task 3: Implement First Strategy** (guide: `implementing-first-strategy.md`)

You'll implement MA Crossover strategy using this framework!

---

## Troubleshooting

### "Running 0 strategies" (after adding strategy)

**Cause:** Strategy class missing `@Component` annotation.

**Fix:**
```java
@Component  // ← Add this!
public class MaCrossoverStrategy implements TradingStrategy {
    ...
}
```

---

### "Not enough data" errors

**Cause:** QuestDB doesn't have enough historical ticks yet.

**Fix:**
- Run Phase 1 services longer (need 50+ ticks per symbol for MA strategies)
- Check: `SELECT symbol, count(*) FROM ticks GROUP BY symbol`
- Wait until each symbol has > 50 ticks

---

### "Cannot autowire List<TradingStrategy>"

**Cause:** Spring component scanning issue.

**Fix:**
- Ensure strategies package is under `com.quantstream.strategy`
- Check `@SpringBootApplication` is on `StrategyEngineApplication`
- Verify `@Component` annotation on strategy classes

---

### Interview Question: "How would you add caching to indicators?"

**Answer:**

**Problem:** Calculating MA(50) every minute for 10 symbols = 500 DB queries/min.

**Solution:** Cache recent calculations.

```java
@Component
public class IndicatorUtils {
    
    private final LoadingCache<String, Double> maCache = Caffeine.newBuilder()
        .expireAfterWrite(1, TimeUnit.MINUTES)
        .build(key -> {
            // Parse key: "AAPL:MA:50"
            String[] parts = key.split(":");
            String symbol = parts[0];
            int period = Integer.parseInt(parts[2]);
            
            // Query and calculate
            List<Double> prices = queryPrices(symbol, period);
            return calculateMAUncached(prices, period);
        });
    
    public double calculateMA(String symbol, int period) {
        return maCache.get(symbol + ":MA:" + period);
    }
}
```

**Trade-off:**
- Faster (1 query per symbol per minute instead of 10)
- More memory (cache storage)
- Complexity (cache invalidation logic)

**When to add:** When performance becomes an issue (not prematurely).
