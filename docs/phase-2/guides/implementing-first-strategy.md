# Implementing First Strategy: MA Crossover

## Overview

This guide implements the **Moving Average Crossover** strategy end-to-end. You'll learn the full pattern that applies to all other strategies.

**Strategy Logic:**
- **Golden Cross** (MA10 crosses above MA50) → BUY signal
- **Death Cross** (MA10 crosses below MA50) → SELL signal

**By the end:** Complete working strategy generating real signals.

---

## Prerequisites

- [ ] Task 2 complete (framework implemented)
- [ ] StrategyScheduler running (logs "0 strategies")
- [ ] QuestDB has > 50 ticks per symbol
- [ ] IndicatorUtils tests passing

**Verify data:**
```bash
curl -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT symbol, count(*) FROM ticks GROUP BY symbol;"
```

**Each symbol must have ≥ 50 ticks** for MA(50) to work.

---

## What Is Moving Average Crossover?

### The Concept

**Moving Average (MA):**
- Average price over N days
- Smooths out noise, reveals trend

**Two timeframes:**
- **MA(10)** - Short-term trend (fast-moving)
- **MA(50)** - Long-term trend (slow-moving)

**Crossover signals:**

```
MA10 crossing ABOVE MA50:
    Before: MA10 < MA50 (short-term weaker)
    After:  MA10 > MA50 (short-term stronger)
    Signal: GOLDEN CROSS → BUY

MA10 crossing BELOW MA50:
    Before: MA10 > MA50 (short-term stronger)
    After:  MA10 < MA50 (short-term weaker)
    Signal: DEATH CROSS → SELL
```

### Visual Example

```
Price
  ↑
  │              Golden Cross
  │                 ↓
  │           MA10 ╱
  │              ╱╳
  │           ╱ ╱ MA50
  │        ╱╱
  │     ╱╱
  │  ╱╱
  └─────────────────────→ Time
  
  Signal: BUY (MA10 crossed above MA50)
```

---

## Step 1: Create Strategy Class

Create `src/main/java/com/quantstream/strategy/strategies/MaCrossoverStrategy.java`:

```java
package com.quantstream.strategy.strategies;

import com.quantstream.strategy.framework.TradingStrategy;
import com.quantstream.strategy.model.Signal;
import com.quantstream.strategy.utils.IndicatorUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Moving Average Crossover Strategy
 * 
 * Type: Trend Following
 * 
 * Logic:
 * - Golden Cross (MA10 crosses above MA50) → BUY
 * - Death Cross (MA10 crosses below MA50) → SELL
 * 
 * State Tracking:
 * - Must remember previous MA values to detect crossovers
 * - Store per symbol (AAPL's MA ≠ BTC's MA)
 * 
 * Performance:
 * - Best in trending markets (bull/bear runs)
 * - Poor in choppy/sideways markets (many false signals)
 */
@Component
public class MaCrossoverStrategy implements TradingStrategy {
    
    private static final Logger log = LoggerFactory.getLogger(MaCrossoverStrategy.class);
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private IndicatorUtils indicators;
    
    /**
     * Store previous MA values for crossover detection.
     * 
     * Key: symbol (e.g., "AAPL")
     * Value: previous MA10 value
     * 
     * Why HashMap:
     * - Need separate state per symbol
     * - Scheduler is single-threaded (no concurrency issues)
     * - Alternative: ConcurrentHashMap if scheduler becomes multi-threaded
     */
    private final Map<String, Double> previousMA10 = new HashMap<>();
    private final Map<String, Double> previousMA50 = new HashMap<>();
    
    @Override
    public String getName() {
        return "MA_CROSSOVER";
    }
    
    @Override
    public int getRequiredHistoryDays() {
        return 50;  // Need 50 days for MA(50)
    }
    
    @Override
    public Signal analyze(String symbol) {
        try {
            // Step 1: Query historical prices
            List<Double> prices = queryPrices(symbol, 50);
            
            // Step 2: Validate data
            if (prices.size() < 50) {
                log.debug("Not enough data for {}: {} ticks (need 50)", symbol, prices.size());
                return null;
            }
            
            // Step 3: Calculate indicators
            double ma10 = indicators.calculateMA(prices, 10);
            double ma50 = indicators.calculateMA(prices, 50);
            
            // Step 4: Get previous values (null on first run)
            Double prevMA10 = previousMA10.get(symbol);
            Double prevMA50 = previousMA50.get(symbol);
            
            // Step 5: Detect crossovers
            Signal signal = null;
            
            if (prevMA10 != null && prevMA50 != null) {
                // Golden Cross: MA10 crosses ABOVE MA50
                if (ma10 > ma50 && prevMA10 <= prevMA50) {
                    signal = new Signal(
                        symbol,
                        "BUY",
                        getName(),
                        calculateConfidence(ma10, ma50, prices.get(0)),
                        Instant.now()
                    );
                    log.info("Golden Cross detected: {} (MA10={:.2f}, MA50={:.2f})", 
                            symbol, ma10, ma50);
                }
                
                // Death Cross: MA10 crosses BELOW MA50
                else if (ma10 < ma50 && prevMA10 >= prevMA50) {
                    signal = new Signal(
                        symbol,
                        "SELL",
                        getName(),
                        calculateConfidence(ma10, ma50, prices.get(0)),
                        Instant.now()
                    );
                    log.info("Death Cross detected: {} (MA10={:.2f}, MA50={:.2f})", 
                            symbol, ma10, ma50);
                }
            } else {
                log.debug("First run for {}, initializing state", symbol);
            }
            
            // Step 6: Store current values for next run
            previousMA10.put(symbol, ma10);
            previousMA50.put(symbol, ma50);
            
            return signal;
            
        } catch (Exception e) {
            log.error("MA Crossover failed for {}: {}", symbol, e.getMessage());
            return null;
        }
    }
    
    /**
     * Query recent prices from QuestDB.
     * 
     * SQL:
     * - ORDER BY timestamp DESC: Most recent first
     * - LIMIT 50: Only need 50 for MA(50)
     * 
     * Performance:
     * - QuestDB optimized for time-series queries
     * - Query takes ~5-10ms
     * 
     * @param symbol Stock/crypto symbol
     * @param limit Number of prices to fetch
     * @return List of prices (most recent first)
     */
    private List<Double> queryPrices(String symbol, int limit) {
        String sql = "SELECT price FROM ticks WHERE symbol = ? ORDER BY timestamp DESC LIMIT ?";
        
        return jdbcTemplate.query(
            sql,
            (rs, rowNum) -> rs.getDouble("price"),
            symbol,
            limit
        );
    }
    
    /**
     * Calculate confidence score for signal.
     * 
     * Factors:
     * 1. Gap size (bigger gap = stronger signal)
     * 2. Price position relative to MAs
     * 3. Trend strength
     * 
     * Range: 0.0 to 1.0
     * - 0.7-0.8: Moderate confidence
     * - 0.8-0.9: High confidence
     * - 0.9+: Very high confidence
     * 
     * @param ma10 Short-term MA
     * @param ma50 Long-term MA
     * @param currentPrice Current price
     * @return Confidence score
     */
    private double calculateConfidence(double ma10, double ma50, double currentPrice) {
        // Base confidence for any crossover
        double baseConfidence = 0.75;
        
        // Gap between MAs (as percentage)
        double gap = Math.abs(ma10 - ma50) / ma50;
        
        // Price confirmation (price should support signal direction)
        boolean priceConfirms = false;
        if (ma10 > ma50 && currentPrice > ma10) {
            priceConfirms = true;  // BUY signal, price above MA10
        } else if (ma10 < ma50 && currentPrice < ma10) {
            priceConfirms = true;  // SELL signal, price below MA10
        }
        
        // Adjust confidence
        double confidence = baseConfidence;
        
        // Larger gap = stronger trend = higher confidence
        if (gap > 0.05) {  // 5% gap
            confidence += 0.10;
        }
        
        // Price confirmation adds confidence
        if (priceConfirms) {
            confidence += 0.05;
        }
        
        // Cap at 0.95 (never 100% certain)
        return Math.min(confidence, 0.95);
    }
}
```

---

## Step 2: Understanding the Code

### State Tracking Pattern

**Why needed:**
Can't detect crossover from single snapshot. Must compare current vs previous.

```java
// First run (10:00 AM)
MA10 = 180, MA50 = 182
previousMA10 = null  → No comparison possible yet

// Second run (10:01 AM)
MA10 = 181, MA50 = 182
previousMA10 = 180, previousMA50 = 182
Compare: 181 > 182? NO
Compare: 180 > 182? NO
No crossover

// Third run (10:02 AM)
MA10 = 183, MA50 = 182
previousMA10 = 181, previousMA50 = 182
Compare: 183 > 182? YES ✓
Compare: 181 > 182? NO ✗
GOLDEN CROSS! MA10 crossed ABOVE MA50
```

### Crossover Detection Logic

```java
// Golden Cross
if (ma10 > ma50 && prevMA10 <= prevMA50) {
    // Current: MA10 is above MA50
    // Previous: MA10 was below or equal to MA50
    // → Crossover happened!
    return BUY;
}

// Death Cross
if (ma10 < ma50 && prevMA10 >= prevMA50) {
    // Current: MA10 is below MA50
    // Previous: MA10 was above or equal to MA50
    // → Crossover happened!
    return SELL;
}
```

**Why `<=` and `>=`?**
- Handles exact equality case (MA10 == MA50)
- Ensures crossover detected even if values briefly touch

---

## Step 3: Test the Strategy

### Start Application

```bash
mvn spring-boot:run
```

**Expected logs:**
```
2026-07-17 10:10:00 INFO  StrategyScheduler - === Running 1 strategies for 10 symbols ===
2026-07-17 10:10:00 DEBUG MaCrossoverStrategy - First run for AAPL, initializing state
2026-07-17 10:10:00 DEBUG MaCrossoverStrategy - First run for MSFT, initializing state
...
2026-07-17 10:10:01 INFO  StrategyScheduler - === Completed: 0 signals generated, 0 errors ===
```

✅ **Good!** First run initializes state, no signals (expected).

### Wait for Second Run (1 minute)

```
2026-07-17 10:11:00 INFO  StrategyScheduler - === Running 1 strategies for 10 symbols ===
2026-07-17 10:11:01 INFO  StrategyScheduler - === Completed: 0 signals generated, 0 errors ===
```

**If no signals:** Market not crossing (expected - crossovers are rare).

**To simulate crossover:** We'll manually inject test data.

---

## Step 4: Simulate Crossover (Testing)

### Create Test Data Inserter

Create temporary test script `scripts/insert-test-crossover.sh`:

```bash
#!/bin/bash

# Insert test data for AAPL to trigger Golden Cross

SYMBOL="TEST_AAPL"

# Insert prices showing Death Cross → Golden Cross
for i in {1..60}; do
  if [ $i -le 30 ]; then
    # First 30 ticks: Downtrend (MA10 < MA50)
    PRICE=$(echo "180 - $i * 0.5" | bc)
  else
    # Next 30 ticks: Uptrend (MA10 > MA50)
    PRICE=$(echo "165 + ($i - 30) * 1" | bc)
  fi
  
  curl -X POST http://localhost:9001/exec \
    -d "query=INSERT INTO ticks VALUES('$SYMBOL', $PRICE, 100, now() - ${i}s);" \
    > /dev/null 2>&1
done

echo "Inserted 60 test ticks for $SYMBOL"
echo "This should trigger a Golden Cross!"
```

Make executable and run:

```bash
chmod +x scripts/insert-test-crossover.sh
./scripts/insert-test-crossover.sh
```

### Add Test Symbol to Scheduler

Edit `StrategyScheduler.java`:

```java
private static final List<String> SYMBOLS = List.of(
    "AAPL", "MSFT", "GOOGL", "TSLA", "AMZN",
    "BTC", "ETH", "SOL", "AVAX", "MATIC",
    "TEST_AAPL"  // ← Add this
);
```

Restart application:

```bash
mvn spring-boot:run
```

### Wait for Next Cycle

**Expected logs:**
```
2026-07-17 10:15:00 INFO  StrategyScheduler - === Running 1 strategies for 11 symbols ===
2026-07-17 10:15:00 DEBUG MaCrossoverStrategy - First run for TEST_AAPL, initializing state
2026-07-17 10:15:01 INFO  StrategyScheduler - === Completed: 0 signals generated, 0 errors ===
```

**Next cycle (1 minute later):**
```
2026-07-17 10:16:00 INFO  StrategyScheduler - === Running 1 strategies for 11 symbols ===
2026-07-17 10:16:00 INFO  MaCrossoverStrategy - Golden Cross detected: TEST_AAPL (MA10=180.50, MA50=172.50)
2026-07-17 10:16:00 DEBUG StrategyScheduler - Signal: BUY TEST_AAPL from MA_CROSSOVER (confidence: 0.85)
2026-07-17 10:16:01 INFO  StrategyScheduler - === Completed: 1 signals generated, 0 errors ===
```

✅ **SUCCESS!** Golden Cross detected!

---

## Step 5: Verify Signal in Kafka

```bash
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic trading-signals \
  --from-beginning \
  --max-messages 1
```

**Expected output:**
```json
{
  "symbol": "TEST_AAPL",
  "action": "BUY",
  "strategyName": "MA_CROSSOVER",
  "confidence": 0.85,
  "timestamp": "2026-07-17T10:16:00Z"
}
```

✅ **Perfect!** Signal sent to Kafka.

---

## Step 6: Understanding Confidence Scores

### Factors

**1. Gap size:**
```java
double gap = Math.abs(ma10 - ma50) / ma50;

if (gap > 0.05) {  // 5% gap
    confidence += 0.10;
}
```

**Bigger gap = stronger trend = higher confidence.**

**Example:**
- MA10 = 180, MA50 = 179 → Gap = 0.56% → Weak signal (0.75)
- MA10 = 190, MA50 = 180 → Gap = 5.56% → Strong signal (0.85)

---

**2. Price confirmation:**
```java
// BUY signal: Price should be above MA10
if (ma10 > ma50 && currentPrice > ma10) {
    confidence += 0.05;
}

// SELL signal: Price should be below MA10
if (ma10 < ma50 && currentPrice < ma10) {
    confidence += 0.05;
}
```

**Price supporting signal direction = higher confidence.**

**Example:**
- Golden Cross + Price > MA10 → 0.85 confidence
- Golden Cross + Price < MA10 → 0.75 confidence (weaker, price not confirming)

---

### Interview Question: "Why cap confidence at 0.95?"

**Answer:**

**Never 100% certain** in trading:
- Markets are non-deterministic
- Black swan events (news, crashes)
- Indicator lag (signal based on past data)

**0.95 = Very high confidence, but acknowledging uncertainty.**

**Alternative:**
- Use probability ranges (0.7-0.8, 0.8-0.9, 0.9-0.95)
- Confidence reflects historical win rate
- Machine learning to calibrate scores

---

## Step 7: Add Unit Tests

Create `src/test/java/com/quantstream/strategy/strategies/MaCrossoverStrategyTest.java`:

```java
package com.quantstream.strategy.strategies;

import com.quantstream.strategy.model.Signal;
import com.quantstream.strategy.utils.IndicatorUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MaCrossoverStrategyTest {
    
    @Mock
    private JdbcTemplate jdbcTemplate;
    
    @Mock
    private IndicatorUtils indicators;
    
    @InjectMocks
    private MaCrossoverStrategy strategy;
    
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }
    
    @Test
    void testGetName() {
        assertEquals("MA_CROSSOVER", strategy.getName());
    }
    
    @Test
    void testGetRequiredHistoryDays() {
        assertEquals(50, strategy.getRequiredHistoryDays());
    }
    
    @Test
    void testFirstRun_NoSignal() {
        // Setup mock data
        List<Double> prices = createPriceList(50, 100.0, 1.0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), anyInt()))
            .thenReturn(prices);
        
        when(indicators.calculateMA(prices, 10)).thenReturn(105.0);
        when(indicators.calculateMA(prices, 50)).thenReturn(100.0);
        
        // First run should return null (no previous state)
        Signal signal = strategy.analyze("AAPL");
        assertNull(signal);
    }
    
    @Test
    void testGoldenCross_BuySignal() {
        // Setup: First run to initialize state
        List<Double> prices1 = createPriceList(50, 100.0, 0.5);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("AAPL"), anyInt()))
            .thenReturn(prices1);
        
        when(indicators.calculateMA(prices1, 10)).thenReturn(98.0);  // MA10 < MA50
        when(indicators.calculateMA(prices1, 50)).thenReturn(100.0);
        
        strategy.analyze("AAPL");  // Initialize state
        
        // Second run: Golden Cross
        List<Double> prices2 = createPriceList(50, 105.0, 1.0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("AAPL"), anyInt()))
            .thenReturn(prices2);
        
        when(indicators.calculateMA(prices2, 10)).thenReturn(102.0);  // MA10 > MA50
        when(indicators.calculateMA(prices2, 50)).thenReturn(100.0);
        
        Signal signal = strategy.analyze("AAPL");
        
        assertNotNull(signal);
        assertEquals("BUY", signal.getAction());
        assertEquals("MA_CROSSOVER", signal.getStrategyName());
        assertTrue(signal.getConfidence() > 0.7);
    }
    
    @Test
    void testDeathCross_SellSignal() {
        // Setup: First run to initialize state
        List<Double> prices1 = createPriceList(50, 100.0, 1.0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("BTC"), anyInt()))
            .thenReturn(prices1);
        
        when(indicators.calculateMA(prices1, 10)).thenReturn(102.0);  // MA10 > MA50
        when(indicators.calculateMA(prices1, 50)).thenReturn(100.0);
        
        strategy.analyze("BTC");  // Initialize state
        
        // Second run: Death Cross
        List<Double> prices2 = createPriceList(50, 95.0, 0.5);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq("BTC"), anyInt()))
            .thenReturn(prices2);
        
        when(indicators.calculateMA(prices2, 10)).thenReturn(98.0);  // MA10 < MA50
        when(indicators.calculateMA(prices2, 50)).thenReturn(100.0);
        
        Signal signal = strategy.analyze("BTC");
        
        assertNotNull(signal);
        assertEquals("SELL", signal.getAction());
        assertEquals("MA_CROSSOVER", signal.getStrategyName());
        assertTrue(signal.getConfidence() > 0.7);
    }
    
    @Test
    void testNotEnoughData_NoSignal() {
        // Only 30 prices (need 50)
        List<Double> prices = createPriceList(30, 100.0, 1.0);
        when(jdbcTemplate.query(anyString(), any(RowMapper.class), anyString(), anyInt()))
            .thenReturn(prices);
        
        Signal signal = strategy.analyze("AAPL");
        assertNull(signal);
    }
    
    /**
     * Helper: Create price list for testing
     */
    private List<Double> createPriceList(int count, double start, double increment) {
        Double[] prices = new Double[count];
        for (int i = 0; i < count; i++) {
            prices[i] = start + (i * increment);
        }
        return Arrays.asList(prices);
    }
}
```

Run tests:

```bash
mvn test -Dtest=MaCrossoverStrategyTest
```

**Expected output:**
```
Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
```

✅ **All tests pass!**

---

## Step 8: Performance Considerations

### Query Optimization

**Current:**
```java
String sql = "SELECT price FROM ticks WHERE symbol = ? ORDER BY timestamp DESC LIMIT ?";
```

**QuestDB optimization:**
- Timestamp column is indexed by default
- `ORDER BY timestamp DESC` uses index (fast)
- `LIMIT 50` stops after 50 rows (efficient)

**Query takes ~5-10ms** for 50 rows.

**Total time per cycle:**
- 10 strategies × 10 symbols × 10ms = 1 second
- Well within 60-second cycle time

---

### Memory Usage

**State storage:**
```java
private final Map<String, Double> previousMA10 = new HashMap<>();
private final Map<String, Double> previousMA50 = new HashMap<>();
```

**Memory per symbol:**
- 2 maps × 10 symbols × 8 bytes (Double) = 160 bytes
- Negligible!

**Total strategy-engine memory:**
- Spring Boot overhead: ~150 MB
- Strategy state: < 1 KB
- Query results (transient): ~5 KB
- **Total: ~150 MB**

---

## Step 9: Clean Up Test Data

Remove test symbol from scheduler:

Edit `StrategyScheduler.java`:
```java
private static final List<String> SYMBOLS = List.of(
    "AAPL", "MSFT", "GOOGL", "TSLA", "AMZN",
    "BTC", "ETH", "SOL", "AVAX", "MATIC"
    // Removed TEST_AAPL
);
```

Optionally delete test data from QuestDB:
```bash
curl -X POST http://localhost:9001/exec \
  -d "query=DELETE FROM ticks WHERE symbol = 'TEST_AAPL';"
```

---

## Summary

**What You Built:**
- ✅ Complete MA Crossover strategy
- ✅ State tracking (previous MA values)
- ✅ Crossover detection logic
- ✅ Confidence score calculation
- ✅ Integration with framework
- ✅ Unit tests
- ✅ End-to-end verification

**Key Patterns Learned:**
1. **Interface implementation** - Implement TradingStrategy
2. **State tracking** - Store previous values in HashMap
3. **Query historical data** - JdbcTemplate with QuestDB
4. **Calculate indicators** - Use IndicatorUtils
5. **Detect crossovers** - Compare current vs previous
6. **Generate signals** - Return Signal object
7. **Confidence scoring** - Consider multiple factors

**Next Task:**
Proceed to **Task 4: Build Signal Aggregator Service** (guide: `signal-aggregator-guide.md`)

---

## Interview Questions

### "How would you handle false signals?"

**Answer:**

**Problem:** Choppy markets produce many false crossovers.

**Solutions:**

**1. Confirmation period:**
```java
// Require crossover to hold for N cycles before signaling
if (crossoverDetected && crossoverHeldFor >= 3) {
    return signal;
}
```

**2. Volume filter:**
```java
// Only signal if volume is above average (confirms conviction)
if (crossover && currentVolume > averageVolume * 1.5) {
    return signal;
}
```

**3. Volatility filter:**
```java
// Skip signals during high volatility (Bollinger Bands width > threshold)
if (crossover && bollingerWidth < threshold) {
    return signal;
}
```

**4. Multiple timeframes:**
```java
// Confirm with longer timeframe (MA20/MA100 also crossed)
if (ma10CrossedMA50 && ma20CrossedMA100) {
    return signal;  // Stronger confirmation
}
```

---

### "How would you backtest this strategy?"

**Answer:**

**Approach:**

**1. Historical simulation:**
```java
for (LocalDate date : historicalDates) {
    List<Double> prices = getPricesUpTo(date, 50);
    Signal signal = strategy.analyze(symbol);
    
    if (signal != null) {
        // Simulate trade
        Trade trade = executeTrade(signal, getPriceAt(date));
        trades.add(trade);
    }
}
```

**2. Calculate metrics:**
```java
double totalReturn = calculateReturn(trades);
double sharpeRatio = calculateSharpe(trades);
double maxDrawdown = calculateMaxDrawdown(trades);
int winRate = calculateWinRate(trades);
```

**3. Compare to baseline:**
- Buy-and-hold return
- Market index (S&P 500)
- Other strategies

**We'll implement backtester in Phase 3!**

---

### "What if QuestDB query fails?"

**Answer:**

**Current behavior:**
```java
try {
    List<Double> prices = queryPrices(symbol, 50);
    // ...
} catch (Exception e) {
    log.error("MA Crossover failed for {}: {}", symbol, e.getMessage());
    return null;
}
```

**Failure modes:**

**1. Transient (network blip):**
- Return null this cycle
- Will retry next cycle (60 seconds)
- Acceptable for non-critical signals

**2. Persistent (DB down):**
- Every cycle fails
- Scheduler continues (doesn't crash)
- Monitor logs for repeated errors

**Production improvement:**
```java
@Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
public List<Double> queryPrices(String symbol, int limit) {
    // Spring Retry annotation auto-retries on exception
}
```

**Alternative:** Circuit breaker pattern (skip strategy if DB fails repeatedly).

---

## Troubleshooting

### "No signals generated ever"

**Cause:** Crossovers are rare events (may take hours/days).

**Verify strategy works:**
- Use test data script (creates artificial crossover)
- Check logs for "First run" message (state initializing)
- Check logs for MA values (are they close to crossing?)

---

### "Signal generated every minute"

**Cause:** Crossover condition always true (logic bug).

**Debug:**
```java
log.info("DEBUG: MA10={}, MA50={}, prevMA10={}, prevMA50={}", 
         ma10, ma50, prevMA10, prevMA50);
```

**Check:** Are previous values updating correctly?

---

### "Confidence score always 0.75"

**Cause:** Gap and price confirmation conditions never met.

**Fix:**
- Adjust thresholds (gap > 0.05 may be too strict)
- Log gap and price values to verify logic
