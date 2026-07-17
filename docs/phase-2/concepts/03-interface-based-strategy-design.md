# Interface-Based Strategy Design

## Overview

In Phase 2, all 10 strategies run inside a **single Spring Boot service**. To avoid code duplication and ensure consistency, we use **interface-based design**.

**Core Principle:** Define a contract (`TradingStrategy` interface) that all strategies implement.

---

## The Problem Without Interfaces

### Approach 1: Copy-Paste (❌ Bad)

```java
// MaCrossoverStrategy.java
@Component
public class MaCrossoverStrategy {
    public void analyze(String symbol) {
        // ... MA logic
    }
}

// RsiStrategy.java
@Component
public class RsiStrategy {
    public void doAnalysis(String symbol) {  // Different method name!
        // ... RSI logic
    }
}

// BollingerStrategy.java
@Component
public class BollingerStrategy {
    public Signal runStrategy(String symbol, int period) {  // Different signature!
        // ... Bollinger logic
    }
}
```

**Problems:**
- Every strategy has different method names
- Different method signatures
- Scheduler can't call all strategies uniformly
- No type safety
- Hard to add new strategies

---

## The Solution: Interface-Based Design

### Define the Contract

```java
public interface TradingStrategy {
    /**
     * Strategy name (e.g., "MA_CROSSOVER", "RSI")
     */
    String getName();
    
    /**
     * How many days of history needed (e.g., 50 for MA50)
     */
    int getRequiredHistoryDays();
    
    /**
     * Core analysis logic
     * @param symbol Stock/crypto symbol to analyze
     * @return Signal if conditions met, null otherwise
     */
    Signal analyze(String symbol);
}
```

**Key Benefits:**
1. **Uniform contract** - All strategies have same methods
2. **Type safety** - Compiler enforces contract
3. **Polymorphism** - Treat all strategies identically
4. **Extensibility** - Add new strategy = implement interface

---

## How Strategies Implement the Interface

### Example 1: MA Crossover Strategy

```java
@Component
public class MaCrossoverStrategy implements TradingStrategy {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private IndicatorUtils indicators;
    
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
        // Query prices
        List<Double> prices = queryPrices(symbol, 50);
        
        if (prices.size() < 50) {
            return null;  // Not enough data
        }
        
        // Calculate indicators
        double ma10 = indicators.calculateMA(prices, 10);
        double ma50 = indicators.calculateMA(prices, 50);
        
        // Get previous values
        Double prevMA10 = previousMA10.get(symbol);
        Double prevMA50 = previousMA50.get(symbol);
        
        Signal signal = null;
        
        // Golden Cross
        if (prevMA10 != null && ma10 > ma50 && prevMA10 <= prevMA50) {
            signal = new Signal(symbol, "BUY", getName(), 0.85, Instant.now());
        }
        
        // Death Cross
        else if (prevMA10 != null && ma10 < ma50 && prevMA10 >= prevMA50) {
            signal = new Signal(symbol, "SELL", getName(), 0.85, Instant.now());
        }
        
        // Store for next run
        previousMA10.put(symbol, ma10);
        previousMA50.put(symbol, ma50);
        
        return signal;
    }
    
    private List<Double> queryPrices(String symbol, int limit) {
        return jdbcTemplate.query(
            "SELECT price FROM ticks WHERE symbol = ? ORDER BY timestamp DESC LIMIT ?",
            (rs, rowNum) -> rs.getDouble("price"),
            symbol, limit
        );
    }
}
```

---

### Example 2: RSI Strategy

```java
@Component
public class RsiStrategy implements TradingStrategy {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private IndicatorUtils indicators;
    
    private static final double OVERSOLD_THRESHOLD = 30.0;
    private static final double OVERBOUGHT_THRESHOLD = 70.0;
    
    @Override
    public String getName() {
        return "RSI";
    }
    
    @Override
    public int getRequiredHistoryDays() {
        return 14;  // RSI needs 14 days
    }
    
    @Override
    public Signal analyze(String symbol) {
        List<Double> prices = queryPrices(symbol, 15); // Need 15 for 14-day RSI
        
        if (prices.size() < 15) {
            return null;
        }
        
        double rsi = indicators.calculateRSI(prices, 14);
        
        // Oversold - expect bounce
        if (rsi < OVERSOLD_THRESHOLD) {
            double confidence = (OVERSOLD_THRESHOLD - rsi) / OVERSOLD_THRESHOLD;
            return new Signal(symbol, "BUY", getName(), confidence, Instant.now());
        }
        
        // Overbought - expect pullback
        if (rsi > OVERBOUGHT_THRESHOLD) {
            double confidence = (rsi - OVERBOUGHT_THRESHOLD) / (100 - OVERBOUGHT_THRESHOLD);
            return new Signal(symbol, "SELL", getName(), confidence, Instant.now());
        }
        
        return null;  // No signal
    }
    
    private List<Double> queryPrices(String symbol, int limit) {
        return jdbcTemplate.query(
            "SELECT price FROM ticks WHERE symbol = ? ORDER BY timestamp DESC LIMIT ?",
            (rs, rowNum) -> rs.getDouble("price"),
            symbol, limit
        );
    }
}
```

---

## The Scheduler: Running All Strategies

```java
@Component
public class StrategyScheduler {
    
    private static final Logger log = LoggerFactory.getLogger(StrategyScheduler.class);
    
    @Autowired
    private List<TradingStrategy> strategies;  // Spring auto-injects ALL strategies!
    
    @Autowired
    private KafkaTemplate<String, Signal> kafkaTemplate;
    
    private static final List<String> SYMBOLS = List.of(
        "AAPL", "MSFT", "GOOGL", "TSLA", "AMZN",
        "BTC", "ETH", "SOL", "AVAX", "MATIC"
    );
    
    @Scheduled(fixedRate = 60000)  // Every minute
    public void runAllStrategies() {
        log.info("Running {} strategies for {} symbols", strategies.size(), SYMBOLS.size());
        
        for (TradingStrategy strategy : strategies) {
            for (String symbol : SYMBOLS) {
                try {
                    Signal signal = strategy.analyze(symbol);  // Polymorphism!
                    
                    if (signal != null) {
                        kafkaTemplate.send("trading-signals", signal);
                        log.debug("Signal: {} {} from {} (confidence: {})", 
                                 signal.getAction(), symbol, strategy.getName(), signal.getConfidence());
                    }
                    
                } catch (Exception e) {
                    log.error("Strategy {} failed for {}: {}", 
                             strategy.getName(), symbol, e.getMessage());
                }
            }
        }
    }
}
```

**Key Points:**
1. **`List<TradingStrategy> strategies`** - Spring finds ALL `@Component` classes implementing `TradingStrategy`
2. **Polymorphism** - `strategy.analyze(symbol)` calls the correct implementation
3. **No hard-coding** - Add new strategy → Spring auto-discovers it
4. **Uniform error handling** - Try-catch applies to all strategies

---

## Spring's Auto-Discovery Magic

### How It Works

```
1. Spring scans classpath for @Component classes
2. Finds: MaCrossoverStrategy, RsiStrategy, BollingerStrategy, ...
3. All implement TradingStrategy interface
4. Spring creates List<TradingStrategy> containing all implementations
5. Injects list into StrategyScheduler
```

### Example Output

```
2026-07-17 10:30:00 INFO  StrategyScheduler - Running 10 strategies for 10 symbols
2026-07-17 10:30:01 DEBUG StrategyScheduler - Signal: BUY AAPL from MA_CROSSOVER (confidence: 0.85)
2026-07-17 10:30:02 DEBUG StrategyScheduler - Signal: SELL BTC from RSI (confidence: 0.73)
2026-07-17 10:30:03 DEBUG StrategyScheduler - Signal: BUY ETH from BOLLINGER_BANDS (confidence: 0.78)
```

---

## Benefits of Interface-Based Design

### 1. Zero Boilerplate

**Before (10 separate services):**
```java
// MaCrossoverService - 500 lines (400 boilerplate, 100 logic)
// RsiService - 500 lines (400 boilerplate, 100 logic)
// ... 8 more services
// Total: 5000 lines (4000 boilerplate!)
```

**After (single service, interface-based):**
```java
// TradingStrategy.java - 20 lines (interface)
// StrategyScheduler.java - 50 lines (runs all)
// MaCrossoverStrategy.java - 100 lines (only logic)
// RsiStrategy.java - 100 lines (only logic)
// ... 8 more strategies
// Total: 1070 lines (only 70 boilerplate!)
```

**Savings:** 75% less code!

---

### 2. Easy to Add New Strategy

**Steps:**
1. Create new class implementing `TradingStrategy`
2. Add `@Component` annotation
3. Done! Spring auto-discovers it.

**Example: Adding VWAP Strategy**

```java
@Component
public class VwapStrategy implements TradingStrategy {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private IndicatorUtils indicators;
    
    @Override
    public String getName() {
        return "VWAP";
    }
    
    @Override
    public int getRequiredHistoryDays() {
        return 1;  // Intraday only
    }
    
    @Override
    public Signal analyze(String symbol) {
        // Query today's ticks (with volume)
        List<Tick> ticks = queryTodaysTicks(symbol);
        
        double vwap = indicators.calculateVWAP(ticks);
        double currentPrice = ticks.get(0).getPrice();
        
        double deviation = (currentPrice - vwap) / vwap;
        
        if (deviation < -0.02) {
            return new Signal(symbol, "BUY", getName(), 0.70, Instant.now());
        }
        if (deviation > 0.02) {
            return new Signal(symbol, "SELL", getName(), 0.70, Instant.now());
        }
        
        return null;
    }
    
    private List<Tick> queryTodaysTicks(String symbol) {
        return jdbcTemplate.query(
            "SELECT symbol, price, volume, timestamp FROM ticks " +
            "WHERE symbol = ? AND timestamp > dateadd('d', -1, now()) " +
            "ORDER BY timestamp DESC",
            (rs, rowNum) -> new Tick(
                rs.getString("symbol"),
                rs.getDouble("price"),
                rs.getDouble("volume"),
                rs.getTimestamp("timestamp").toInstant()
            ),
            symbol
        );
    }
}
```

**No changes needed to:**
- StrategyScheduler (auto-discovers new strategy)
- Configuration files
- Kafka setup
- Other strategies

---

### 3. Testability

```java
@SpringBootTest
class MaCrossoverStrategyTest {
    
    @Autowired
    private MaCrossoverStrategy strategy;
    
    @Test
    void testGoldenCross() {
        // Setup mock data
        when(jdbcTemplate.query(...)).thenReturn(pricesWithGoldenCross);
        
        // Call analyze
        Signal signal = strategy.analyze("AAPL");
        
        // Verify
        assertNotNull(signal);
        assertEquals("BUY", signal.getAction());
        assertEquals("MA_CROSSOVER", signal.getStrategyName());
        assertTrue(signal.getConfidence() > 0.8);
    }
}
```

**Benefits:**
- Test each strategy independently
- Mock dependencies easily
- Verify contract compliance (interface methods)

---

### 4. Strategy Metrics

```java
@Component
public class StrategyMetrics {
    
    private final Map<String, AtomicLong> signalCounts = new ConcurrentHashMap<>();
    
    @Autowired
    private List<TradingStrategy> strategies;
    
    @PostConstruct
    public void initialize() {
        strategies.forEach(strategy -> 
            signalCounts.put(strategy.getName(), new AtomicLong(0))
        );
    }
    
    public void recordSignal(String strategyName) {
        signalCounts.get(strategyName).incrementAndGet();
    }
    
    public Map<String, Long> getSignalCounts() {
        return signalCounts.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().get()
            ));
    }
}
```

**Output:**
```json
{
  "MA_CROSSOVER": 245,
  "RSI": 312,
  "BOLLINGER_BANDS": 198,
  "MACD": 267,
  "STOCHASTIC": 189,
  ...
}
```

---

## Design Patterns Used

### 1. Strategy Pattern (GoF)

**Definition:** Define a family of algorithms, encapsulate each one, make them interchangeable.

**Our Implementation:**
- **Family of algorithms** = 10 trading strategies
- **Encapsulation** = Each strategy in its own class
- **Interchangeable** = All implement same interface

### 2. Dependency Injection

**Definition:** Dependencies provided by framework, not created by class.

**Our Implementation:**
```java
@Component
public class MaCrossoverStrategy implements TradingStrategy {
    
    @Autowired  // Spring injects this
    private JdbcTemplate jdbcTemplate;
    
    @Autowired  // Spring injects this
    private IndicatorUtils indicators;
}
```

### 3. Template Method (Implicit)

**Definition:** Define skeleton of algorithm, let subclasses override specific steps.

**Our Implementation:**
- **Skeleton** = StrategyScheduler.runAllStrategies()
- **Override** = Each strategy's analyze() method

---

## Common Patterns in Strategy Implementations

### Pattern 1: State Tracking (Crossovers)

```java
@Component
public class MaCrossoverStrategy implements TradingStrategy {
    
    // Track previous indicator values per symbol
    private final Map<String, Double> previousMA10 = new HashMap<>();
    private final Map<String, Double> previousMA50 = new HashMap<>();
    
    @Override
    public Signal analyze(String symbol) {
        double ma10 = calculateMA(prices, 10);
        double ma50 = calculateMA(prices, 50);
        
        Double prevMA10 = previousMA10.get(symbol);
        Double prevMA50 = previousMA50.get(symbol);
        
        // Detect crossover
        if (prevMA10 != null && ma10 > ma50 && prevMA10 <= prevMA50) {
            // Golden Cross detected!
        }
        
        // Store for next run
        previousMA10.put(symbol, ma10);
        previousMA50.put(symbol, ma50);
        
        return signal;
    }
}
```

**Why needed:** Must compare current vs previous to detect crossovers.

---

### Pattern 2: Threshold-Based (Overbought/Oversold)

```java
@Component
public class RsiStrategy implements TradingStrategy {
    
    private static final double OVERSOLD = 30.0;
    private static final double OVERBOUGHT = 70.0;
    
    @Override
    public Signal analyze(String symbol) {
        double rsi = calculateRSI(prices);
        
        if (rsi < OVERSOLD) {
            return new Signal(symbol, "BUY", getName(), confidence, Instant.now());
        }
        if (rsi > OVERBOUGHT) {
            return new Signal(symbol, "SELL", getName(), confidence, Instant.now());
        }
        
        return null;  // No signal
    }
}
```

**Why simple:** No state tracking needed, just compare to threshold.

---

### Pattern 3: Breakout Detection

```java
@Component
public class DonchianStrategy implements TradingStrategy {
    
    @Override
    public Signal analyze(String symbol) {
        double currentPrice = prices.get(0);
        double previousPrice = prices.get(1);
        
        double highestHigh = Collections.max(prices.subList(0, 20));
        
        // Breakout above 20-day high
        if (currentPrice > highestHigh && previousPrice <= highestHigh) {
            return new Signal(symbol, "BUY", getName(), 0.85, Instant.now());
        }
        
        return null;
    }
}
```

**Why powerful:** Catches momentum shifts early.

---

## Key Takeaways

1. **Interface = Contract** - All strategies must implement same methods
2. **Polymorphism** - Treat different strategies uniformly
3. **Spring Auto-Discovery** - No manual wiring, just add `@Component`
4. **Easy to Extend** - Add strategy = add class implementing interface
5. **Separation of Concerns** - Scheduler runs strategies, strategies implement logic
6. **Testable** - Each strategy can be tested independently

---

## Next: Implement First Strategy

Now that you understand the design, let's implement MA Crossover strategy end-to-end!
