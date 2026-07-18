# Phase 2: Strategy Engine

## Goal

Build multiple trading strategy microservices that analyze stored tick data and generate trading signals.

**Extension, not replacement.** Phase 1 pipeline continues running — Phase 2 adds intelligence on top.

---

## What We're Building

```
Kafka Topic: "market-data"
    ↓ (consumed by)
Aggregator (Kafka Streams - creates OHLC candles)
    ↓ (produces to)
Kafka Topic: "candles-1m"
    ↓ (consumed by)
database-consumer → QuestDB Table: candles_1m

QuestDB (ticks table)
    ↓ (strategies query via SQL)
Strategy Engine (1 Java service, 10 strategies inside)
    ↓ (produce signals to)
Kafka Topic: "trading-signals"
    ↓ (consumed by)
database-consumer → QuestDB Table: signals
```

**Key Architectural Decision:** All 10 strategies run inside a **single** `strategy-engine` service.

**Why not 10 separate microservices?**
- Strategies are just algorithms (idempotent, deterministic)
- All have identical resource needs (query DB → calculate → produce signal)
- Free tier deployment constraints (11 services = 2.75 GB RAM minimum)
- No organizational need (not separate teams)
- Easier development, debugging, and deployment

**Why Aggregator is separate:**
- Different responsibility (Kafka Streams windowing for ticks → candles vs strategy signal generation)
- Different scaling profile (streaming data transformation vs batch SQL queries)
- Different failure domain (aggregator down doesn't stop signal generation or tick storage)

---

## End State

After Phase 2, you can:
1. Phase 1 services still running (Generator → Kafka → Consumer → QuestDB)
2. 10 strategy services running in parallel
3. Each strategy analyzing ticks and producing signals
4. Query signals: `SELECT * FROM signals WHERE symbol = 'AAPL' ORDER BY timestamp DESC`
5. See which strategies are generating BUY/SELL signals in real-time

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                  PHASE 2: STRATEGY ENGINE                    │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │          Strategy Engine (Spring Boot)             │    │
│  │                                                     │    │
│  │  ┌───────────────┐  ┌───────────────┐            │    │
│  │  │ MA-Crossover  │  │ MACD Strategy │            │    │
│  │  │  @Component   │  │  @Component   │  ...       │    │
│  │  └───────────────┘  └───────────────┘            │    │
│  │                                                     │    │
│  │  ┌───────────────┐  ┌───────────────┐            │    │
│  │  │ RSI Strategy  │  │ Bollinger     │            │    │
│  │  │  @Component   │  │  @Component   │  ...       │    │
│  │  └───────────────┘  └───────────────┘            │    │
│  │                                                     │    │
│  │  ┌───────────────┐  ┌───────────────┐            │    │
│  │  │ Stochastic    │  │ Williams %R   │            │    │
│  │  │  @Component   │  │  @Component   │  ...       │    │
│  │  └───────────────┘  └───────────────┘            │    │
│  │                                                     │    │
│  │  ┌───────────────┐  ┌───────────────┐            │    │
│  │  │ ADX Trend     │  │ Donchian Ch.  │            │    │
│  │  │  @Component   │  │  @Component   │  ...       │    │
│  │  └───────────────┘  └───────────────┘            │    │
│  │                                                     │    │
│  │  ┌───────────────┐  ┌───────────────┐            │    │
│  │  │ ROC Momentum  │  │ VWAP          │            │    │
│  │  │  @Component   │  │  @Component   │            │    │
│  │  └───────────────┘  └───────────────┘            │    │
│  │                                                     │    │
│  │  All strategies implement: TradingStrategy         │    │
│  │  Scheduler runs all strategies every minute        │    │
│  └─────────────────────────┬──────────────────────────┘    │
│                            ↓                                 │
│                  ┌──────────────────┐                       │
│                  │Kafka: signals    │                       │
│                  └────────┬─────────┘                       │
│                           │                                  │
│                           ↓                                  │
│                  ┌──────────────────┐                       │
│                  │database-consumer │                       │
│                  │ (extended)       │                       │
│                  │  - Writes signals│                       │
│                  │  - Writes candles│                       │
│                  └────────┬─────────┘                       │
│                           │                                  │
│                           ↓                                  │
│                  ┌──────────────────┐                       │
│                  │  QuestDB: signals│                       │
│                  │  + candles_1m    │                       │
│                  └──────────────────┘                       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Why This Architecture?

### Single Strategy Engine (All Strategies Inside)

**Why NOT separate microservices per strategy?**

❌ **Reasons that DON'T apply here:**
- **"Independent scaling"** - All strategies have identical CPU/memory profiles (query → calculate → produce)
- **"Independent deployment"** - Strategies are algorithms, not features developed by separate teams
- **"Failure isolation"** - If one strategy fails, it's usually a systemic issue (DB down, bad data) affecting all
- **"Team autonomy"** - You're not Netflix with 50 teams, you're a solo dev or small team

✅ **Why single service makes sense:**
- **Pragmatic** - Free tier deployment possible (500 MB vs 2.75 GB)
- **Simpler** - One codebase, one deployment, one log file to debug
- **Zero duplication** - Models, configs, utilities shared naturally
- **Easy to add strategies** - Just add a new `@Component` class implementing `TradingStrategy`
- **Still modular** - Interface-based design allows extracting strategies later if needed

**Mental model:** This is a **modular monolith**, not a distributed system. Strategies are plugins, not services.

### Strategies Query QuestDB (Not Kafka Stream)

**Why not consume from Kafka directly?**
- Need historical context (50-day MA needs 50 days of data)
- Simpler logic (SQL query vs maintaining in-memory state)
- Enables backtesting (replay past data)
- Easier debugging (data persists, can inspect exact state)

### Signals Go Back to Kafka

**Why another Kafka topic?**
- **Decouples** signal generation from signal consumption
- **Multiple consumers** can act on signals (aggregator, evaluator, UI)
- **Reliable delivery** - Kafka guarantees no signal loss
- **Replay capability** - Can re-process signals if needed

---

## The 10 Trading Strategies

### **Trend Following (4 strategies):**

**1. Moving Average Crossover**
- **Logic:** MA(10) crosses MA(50)
- **Signal:** Golden Cross = BUY, Death Cross = SELL
- **History needed:** 50 days
- **Best for:** Trending markets

**2. MACD (Moving Average Convergence Divergence)**
- **Logic:** MACD line crosses signal line
- **Signal:** Bullish crossover = BUY, Bearish crossover = SELL
- **History needed:** 26 days
- **Best for:** Momentum shifts

**3. ADX (Average Directional Index)**
- **Logic:** Measures trend strength
- **Signal:** ADX > 25 + Price > MA = BUY (strong uptrend)
- **History needed:** 14 days
- **Best for:** Identifying strong trends

**4. Donchian Channel Breakout**
- **Logic:** 20-day high/low breakout
- **Signal:** Price breaks above 20-day high = BUY
- **History needed:** 20 days
- **Best for:** Breakout trading

---

### **Mean Reversion (3 strategies):**

**5. RSI (Relative Strength Index)**
- **Logic:** Identifies overbought/oversold conditions
- **Signal:** RSI < 30 = BUY (oversold), RSI > 70 = SELL (overbought)
- **History needed:** 14 days
- **Best for:** Range-bound markets

**6. Bollinger Bands**
- **Logic:** Price deviation from moving average
- **Signal:** Price touches lower band = BUY, upper band = SELL
- **History needed:** 20 days
- **Best for:** Volatile markets with support/resistance

**7. Stochastic Oscillator**
- **Logic:** Momentum indicator comparing close to price range
- **Signal:** %K crosses above %D in oversold = BUY
- **History needed:** 14 days
- **Best for:** Identifying turning points

---

### **Momentum (2 strategies):**

**8. Rate of Change (ROC)**
- **Logic:** Measures price momentum
- **Signal:** ROC crosses above 0 = BUY, below 0 = SELL
- **History needed:** 10 days
- **Best for:** Momentum trading

**9. Williams %R**
- **Logic:** Momentum oscillator similar to Stochastic
- **Signal:** %R > -20 = overbought (SELL), %R < -80 = oversold (BUY)
- **History needed:** 14 days
- **Best for:** Short-term reversal trading

---

### **Volume-Based (1 strategy):**

**10. VWAP Deviation**
- **Logic:** Volume-weighted average price deviation
- **Signal:** Price below VWAP = BUY (undervalued), above = SELL (overvalued)
- **History needed:** 1 day (intraday)
- **Best for:** Intraday trading

---

## Strategy Engine Structure

Single Spring Boot service containing all strategies:

### Directory Structure
```
strategy-engine/
├── pom.xml
└── src/main/java/com/quantstream/strategy/
    ├── StrategyEngineApplication.java
    ├── config/
    │   ├── KafkaProducerConfig.java
    │   └── QuestDBConfig.java
    ├── model/
    │   ├── Tick.java
    │   └── Signal.java
    ├── framework/
    │   ├── TradingStrategy.java      ← Interface (all strategies implement)
    │   └── StrategyScheduler.java    ← Runs all strategies every minute
    ├── utils/
    │   └── IndicatorUtils.java       ← Shared calculations (MA, RSI, etc.)
    └── strategies/
        ├── MaCrossoverStrategy.java
        ├── MacdStrategy.java
        ├── RsiStrategy.java
        ├── BollingerBandsStrategy.java
        ├── StochasticStrategy.java
        ├── WilliamsRStrategy.java
        ├── AdxStrategy.java
        ├── DonchianStrategy.java
        ├── RocStrategy.java
        └── VwapStrategy.java
```

### Core Components

**1. Trading Strategy Interface**
```java
public interface TradingStrategy {
    String getName();                    // "MA_CROSSOVER"
    int getRequiredHistoryDays();        // 50
    Signal analyze(String symbol);       // Core logic - returns Signal or null
}
```

**2. Strategy Scheduler (Runs All Strategies)**
```java
@Component
public class StrategyScheduler {
    
    @Autowired
    private List<TradingStrategy> strategies;  // Spring auto-injects all
    
    @Autowired
    private KafkaTemplate<String, Signal> kafkaTemplate;
    
    private static final List<String> SYMBOLS = List.of(
        "AAPL", "MSFT", "GOOGL", "TSLA", "AMZN",
        "BTC", "ETH", "SOL", "AVAX", "MATIC"
    );
    
    @Scheduled(fixedRate = 60000)  // Every minute
    public void runAllStrategies() {
        log.info("Running {} strategies for {} symbols", 
                 strategies.size(), SYMBOLS.size());
        
        for (TradingStrategy strategy : strategies) {
            for (String symbol : SYMBOLS) {
                try {
                    Signal signal = strategy.analyze(symbol);
                    
                    if (signal != null) {
                        kafkaTemplate.send("trading-signals", signal);
                        log.debug("Signal: {} {} from {}", 
                                 signal.getAction(), symbol, strategy.getName());
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

**3. Example Strategy Implementation**
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
        return 50;
    }
    
    @Override
    public Signal analyze(String symbol) {
        // Query last 50 prices
        List<Double> prices = jdbcTemplate.query(
            "SELECT price FROM ticks WHERE symbol = ? ORDER BY timestamp DESC LIMIT 50",
            (rs, rowNum) -> rs.getDouble("price"),
            symbol
        );
        
        if (prices.size() < 50) {
            return null;  // Not enough data
        }
        
        // Calculate moving averages
        double ma10 = indicators.calculateMA(prices, 10);
        double ma50 = indicators.calculateMA(prices, 50);
        
        // Get previous values
        Double prevMA10 = previousMA10.get(symbol);
        Double prevMA50 = previousMA50.get(symbol);
        
        Signal signal = null;
        
        // Golden Cross - bullish
        if (prevMA10 != null && ma10 > ma50 && prevMA10 <= prevMA50) {
            signal = new Signal(symbol, "BUY", getName(), 0.85, Instant.now());
        }
        
        // Death Cross - bearish
        else if (prevMA10 != null && ma10 < ma50 && prevMA10 >= prevMA50) {
            signal = new Signal(symbol, "SELL", getName(), 0.85, Instant.now());
        }
        
        // Store for next run
        previousMA10.put(symbol, ma10);
        previousMA50.put(symbol, ma50);
        
        return signal;
    }
}
```

**Key Points:**
- Each strategy is just a `@Component` implementing `TradingStrategy`
- Spring auto-discovers all strategies and injects them into scheduler
- Adding new strategy = add one class, zero config changes
- Shared utilities (IndicatorUtils) avoid code duplication

**2. Signal Model**
```java
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Signal {
    private String symbol;        // AAPL, BTC, ETH, etc.
    private String action;        // BUY, SELL, HOLD
    private String strategyName;  // MA_CROSSOVER, RSI, etc.
    private double confidence;    // 0.0 to 1.0
    private Instant timestamp;
}
```

**4. Configuration (application.yml)**
```yaml
spring:
  application:
    name: strategy-engine

  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer

  datasource:
    url: jdbc:postgresql://localhost:8812/qdb
    username: admin
    password: quest
    driver-class-name: org.postgresql.Driver

server:
  port: 8083

logging:
  level:
    com.quantstream: DEBUG
    org.springframework: INFO
```

---

## Aggregator Service (Kafka Streams)

**Purpose:** Create 1-minute OHLC candles from raw ticks for frontend chart visualization.

### Key Responsibilities
1. **Consume** from `market-data` Kafka topic (raw ticks)
2. **Window** - Group ticks into 1-minute time windows
3. **Aggregate** - Calculate Open, High, Low, Close, Volume per window
4. **Produce** - Emit candles to `candles-1m` topic
5. **Database Consumer** - Extended to write candles + signals to QuestDB

### QuestDB Schema: `candles_1m` Table
```sql
CREATE TABLE IF NOT EXISTS candles_1m (
    symbol SYMBOL,
    open DOUBLE,
    high DOUBLE,
    low DOUBLE,
    close DOUBLE,
    volume LONG,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp) PARTITION BY DAY;
```

### QuestDB Schema: `signals` Table
```sql
CREATE TABLE IF NOT EXISTS signals (
    symbol SYMBOL,
    action SYMBOL,
    strategy_name SYMBOL,
    confidence DOUBLE,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp) PARTITION BY DAY;
```

---

## Data Flow (Complete)

### Real-time Signal Generation

```
Step 1: Strategy Service starts (every minute)
Step 2: Query QuestDB
        SELECT price FROM ticks 
        WHERE symbol = 'AAPL' 
        ORDER BY timestamp DESC LIMIT 50

Step 3: Calculate Indicators
        ma10 = average(last 10 prices)
        ma50 = average(last 50 prices)

Step 4: Apply Logic
        if (ma10 > ma50 && prev_ma10 <= prev_ma50):
            signal = BUY

Step 5: Produce to Kafka
        Topic: "trading-signals"
        Message: {
          "symbol": "AAPL",
          "action": "BUY",
          "strategyName": "MA_CROSSOVER",
          "confidence": 0.85,
          "timestamp": "2026-07-17T10:30:00Z"
        }

Step 6: Database Consumer (Extended) Consumes
        Writes signal to QuestDB signals table

Step 7: Query signals
        SELECT * FROM signals 
        WHERE symbol = 'AAPL' 
        ORDER BY timestamp DESC
```

---

## Timeline & Milestones

### **Phase 2A: Core Infrastructure (Week 1)**

**Goal:** Get strategy engine working with 1 strategy

**Tasks:**
1. Create `strategy-engine` Spring Boot service
2. Create `TradingStrategy` interface
3. Create `StrategyScheduler` (runs all strategies)
4. Create `IndicatorUtils` (shared calculations)
5. Implement `MaCrossoverStrategy` (first strategy)
6. Create Kafka topics: `trading-signals`, `candles-1m`
7. Create QuestDB tables: `signals`, `candles_1m`
8. Build `aggregator` service (Kafka Streams for candles)
9. Extend `database-consumer` to write candles + signals
10. Test: MA strategy produces signals → Consumer persists → Query works

**Deliverable:** 1 strategy producing signals, stored in QuestDB

**Success criteria:**
- [ ] Strategy engine starts successfully
- [ ] MA Crossover strategy runs every minute
- [ ] Queries QuestDB successfully
- [ ] Produces signals to Kafka
- [ ] Aggregator creates candles from ticks
- [ ] Database consumer persists candles + signals

---

### **Phase 2B: Add Remaining 9 Strategies (Week 2)**

**Goal:** Implement 9 more strategies inside strategy-engine

**Tasks:**
10. Implement `MacdStrategy.java`
11. Implement `RsiStrategy.java`
12. Implement `BollingerBandsStrategy.java`
13. Implement `StochasticStrategy.java`
14. Implement `WilliamsRStrategy.java`
15. Implement `AdxStrategy.java`
16. Implement `DonchianStrategy.java`
17. Implement `RocStrategy.java`
18. Implement `VwapStrategy.java`
19. Update `IndicatorUtils` with new calculations
20. Test all strategies

**Deliverable:** 10 strategies running in single service

**Success criteria:**
- [ ] All 10 strategies auto-discovered by Spring
- [ ] Each strategy produces signals independently
- [ ] Signals table shows mix of 10 strategies
- [ ] Candles table populated with 1-min OHLC data
- [ ] Frontend can query candles for chart display
- [ ] No errors in logs for 1 hour continuous run

---

## Project Structure

```
QuantStream/
├── docker-compose.yml                  # Updated with 2 new services
├── data-generator/                     # Phase 1 (unchanged)
├── database-consumer/                  # Phase 1 (unchanged)
│
├── strategy-engine/                    # NEW - All strategies inside
│   ├── pom.xml
│   └── src/main/java/com/quantstream/strategy/
│       ├── StrategyEngineApplication.java
│       ├── config/
│       │   ├── KafkaProducerConfig.java
│       │   └── QuestDBConfig.java
│       ├── model/
│       │   ├── Tick.java
│       │   └── Signal.java
│       ├── framework/
│       │   ├── TradingStrategy.java      ← Interface
│       │   └── StrategyScheduler.java    ← Runs all strategies
│       ├── utils/
│       │   └── IndicatorUtils.java       ← Shared calculations
│       └── strategies/
│           ├── MaCrossoverStrategy.java
│           ├── MacdStrategy.java
│           ├── RsiStrategy.java
│           ├── BollingerBandsStrategy.java
│           ├── StochasticStrategy.java
│           ├── WilliamsRStrategy.java
│           ├── AdxStrategy.java
│           ├── DonchianStrategy.java
│           ├── RocStrategy.java
│           └── VwapStrategy.java
│
└── aggregator/                         # NEW - Kafka Streams for candles
    ├── pom.xml
    └── src/main/java/com/quantstream/aggregator/
        ├── AggregatorApplication.java
        ├── config/
        │   └── KafkaStreamsConfig.java
        ├── model/
        │   ├── Tick.java
        │   └── Candle.java
        └── topology/
            └── CandleAggregationTopology.java  ← Kafka Streams windowing
```

---

## Storage Considerations (Laptop-Friendly)

### Expected Data Growth

**Tick Data (Phase 1):**
- 10 ticks/sec × 86400 sec/day = 864k ticks/day
- ~86 MB/day
- ~2.5 GB/month
- ~7.5 GB for 3 months

**Signal Data (Phase 2):**
- 10 strategies × 10 symbols = 100 signals/minute (worst case)
- ~144k signals/day
- ~14 MB/day
- ~420 MB/month
- ~1.3 GB for 3 months

**Total Storage (3 months):**
- Ticks: 7.5 GB
- Signals: 1.3 GB
- Kafka logs: 500 MB (3 days retention)
- **Total: ~9.3 GB**

Completely manageable on a laptop!

### Retention Policies

**QuestDB:**
```sql
-- Keep 90 days of tick data
-- QuestDB auto-drops old partitions (daily partitions)

-- Keep 30 days of signal data (lighter)
-- Manual cleanup via cron:
DELETE FROM signals WHERE timestamp < dateadd('d', -30, now());
```

**Kafka:**
```yaml
# docker-compose.yml
kafka:
  environment:
    KAFKA_LOG_RETENTION_HOURS: 72        # 3 days
    KAFKA_LOG_SEGMENT_BYTES: 1073741824  # 1GB segments
```

---

## Configuration & Deployment

### Updated docker-compose.yml

```yaml
version: '3.8'

services:
  # Phase 1 services (unchanged)
  zookeeper:
    # ... existing config
  
  kafka:
    # ... existing config
  
  questdb:
    # ... existing config
  
  data-generator:
    # ... existing config
  
  database-consumer:
    # ... existing config
  
  # Phase 2 services (new)
  strategy-engine:
    build: ./strategy-engine
    ports:
      - "8083:8083"
    depends_on:
      - kafka
      - questdb
    environment:
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SPRING_DATASOURCE_URL: jdbc:postgresql://questdb:8812/qdb
  
  aggregator:
    build: ./aggregator
    ports:
      - "8084:8084"
    depends_on:
      - kafka
    environment:
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
```

**Total Services:** 7 (Zookeeper, Kafka, QuestDB, Generator, Consumer, Strategy-Engine, Aggregator)  
**Memory estimate:** ~1.3 GB total (easily fits free tier)

---

## Development Strategy

**Iterative approach (recommended):**

1. **Build framework first** (TradingStrategy interface + StrategyScheduler)
2. **Implement 1 strategy** (MaCrossover) - validate end-to-end
3. **Add 2-3 strategies** (RSI, Bollinger) - ensure pattern scales
4. **Bulk add remaining** (7 more strategies) - copy & modify

**Key insight:** Since all strategies are in one codebase, you can:
- Test incrementally (1 strategy → 3 strategies → 10 strategies)
- Refactor shared code easily (all in one place)
- Debug holistically (single application, single debugger)

---

## Success Criteria

Phase 2 is complete when:

- [ ] Strategy-engine service starts without errors
- [ ] All 10 strategies auto-discovered by Spring (check logs)
- [ ] Each strategy queries QuestDB successfully
- [ ] Strategies produce signals to Kafka `trading-signals` topic
- [ ] Aggregator creates candles from ticks
- [ ] Candles written to `candles-1m` Kafka topic
- [ ] Database consumer writes candles to QuestDB
- [ ] Database consumer writes signals to QuestDB
- [ ] Query works: `SELECT * FROM signals ORDER BY timestamp DESC LIMIT 100`
- [ ] Can see mix of 10 different strategies in signals table
- [ ] System runs stably for 24 hours without crashes
- [ ] Memory usage < 600 MB (strategy-engine + aggregator combined)

---

## Key Differences from Phase 1

| Aspect | Phase 1 | Phase 2 |
|--------|---------|---------|
| **Services** | 2 (generator + consumer) | 4 (generator + consumer + strategy-engine + aggregator) |
| **Kafka Topics** | 1 (`market-data`) | 3 (`market-data` + `candles-1m` + `trading-signals`) |
| **QuestDB Tables** | 1 (`ticks`) | 3 (`ticks` + `candles_1m` + `signals`) |
| **Data Flow** | Produce → Store | Read → Analyze → Produce → Store |
| **Complexity** | Linear pipeline | Parallel analysis (10 strategies) |
| **CPU Usage** | Low | Medium (10 strategies calculating) |
| **Memory** | ~400 MB | ~1.2 GB |
| **Architecture** | Event-driven | Query-driven + Event-driven |

---

## What Phase 3 Will Add

**Phase 3: Strategy Evaluation & Frontend**

1. **Python Backtester (FastAPI + Pandas)**
   - Backtest any strategy on historical data
   - Calculate performance metrics (Sharpe ratio, win rate, etc.)
   - Compare strategies

2. **React Frontend Dashboard**
   - Live signal feed (WebSocket)
   - Strategy performance leaderboard
   - Interactive charts (price + signals)
   - Backtest UI

**Phase 3 depends on Phase 2:** Need strategies generating signals before we can evaluate them!

---

## Next Steps

1. **Read Phase 1 recap:** Ensure Phase 1 is solid
2. **Start Phase 2A:** Build first strategy (MA Crossover)
3. **Follow task list:** `docs/phase-2/tasks/TASK-LIST.md` (to be created)
4. **Refer to guides:** `docs/phase-2/guides/` (to be created)

---

## Questions?

Keep notes of issues and learnings as you build Phase 2!
