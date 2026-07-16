# Phase 2: Strategy Engine

## Goal

Build multiple trading strategy microservices that analyze stored tick data and generate trading signals.

**Extension, not replacement.** Phase 1 pipeline continues running — Phase 2 adds intelligence on top.

---

## What We're Building

```
QuestDB (27k+ ticks stored from Phase 1)
    ↓ (strategies query via SQL)
Strategy Services (10 Java microservices)
    ↓ (produce signals to)
Kafka Topic: "trading-signals"
    ↓ (consumed by)
Signal Aggregator (Java)
    ↓ (writes to)
QuestDB Table: signals
```

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
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ MA-Crossover │  │ MACD Strategy│  │ RSI Strategy │     │
│  │  (Java)      │  │  (Java)      │  │  (Java)      │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│         │                  │                  │              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ Bollinger    │  │ Stochastic   │  │ Williams %R  │     │
│  │  (Java)      │  │  (Java)      │  │  (Java)      │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│         │                  │                  │              │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐     │
│  │ ADX Trend    │  │ Donchian Ch. │  │ ROC Momentum │     │
│  │  (Java)      │  │  (Java)      │  │  (Java)      │     │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘     │
│         │                  │                  │              │
│  ┌──────────────┐                                           │
│  │ VWAP Deviation                                           │
│  │  (Java)      │                                           │
│  └──────┬───────┘                                           │
│         │                                                    │
│         └──────────────────┼──────────────────┘             │
│                            ↓                                 │
│                  ┌──────────────────┐                       │
│                  │  Kafka: signals  │                       │
│                  └────────┬─────────┘                       │
│                           │                                  │
│                           ↓                                  │
│                  ┌──────────────────┐                       │
│                  │ Signal Aggregator│                       │
│                  │     (Java)       │                       │
│                  └────────┬─────────┘                       │
│                           │                                  │
│                           ↓                                  │
│                  ┌──────────────────┐                       │
│                  │  QuestDB: signals│                       │
│                  │      table       │                       │
│                  └──────────────────┘                       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Why This Architecture?

### Each Strategy = Separate Microservice

**Benefits:**
- **Deploy independently** - Update one strategy without restarting others
- **Scale independently** - CPU-heavy strategies get more resources
- **Fail independently** - One strategy crash doesn't kill others
- **Easy to add** - New strategy = deploy another service, no changes to existing code

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

## Strategy Service Pattern

All strategy services follow the same structure:

### Directory Structure
```
ma-crossover-strategy/
├── pom.xml
└── src/main/java/com/quantstream/strategy/macrossover/
    ├── MaCrossoverApplication.java
    ├── config/
    │   ├── KafkaProducerConfig.java
    │   └── QuestDBConfig.java
    ├── model/
    │   ├── Tick.java
    │   └── Signal.java
    ├── service/
    │   └── MaCrossoverStrategy.java
    └── scheduler/
        └── StrategyScheduler.java
```

### Core Components

**1. Strategy Service (Example: MaCrossoverStrategy.java)**
```java
@Service
public class MaCrossoverStrategy {
    
    private final JdbcTemplate jdbcTemplate;
    private final KafkaTemplate<String, Signal> kafkaTemplate;
    
    // Runs every minute
    @Scheduled(fixedRate = 60000)
    public void analyze() {
        for (String symbol : SYMBOLS) {
            // Query last 50 prices
            List<Double> prices = queryPrices(symbol, 50);
            
            // Calculate indicators
            double ma10 = calculateMA(prices, 10);
            double ma50 = calculateMA(prices, 50);
            
            // Check for crossover
            if (isGoldenCross(ma10, ma50)) {
                Signal signal = new Signal(
                    symbol, 
                    "BUY", 
                    "MA_CROSSOVER",
                    0.85,
                    Instant.now()
                );
                
                // Produce to Kafka
                kafkaTemplate.send("trading-signals", signal);
            }
        }
    }
}
```

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

**3. Configuration (application.yml)**
```yaml
spring:
  application:
    name: ma-crossover-strategy

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

strategy:
  symbols:
    - AAPL
    - MSFT
    - GOOGL
    - TSLA
    - AMZN
    - BTC
    - ETH
    - SOL
    - AVAX
    - MATIC
  interval-ms: 60000  # Run every minute
```

---

## Signal Aggregator Service

**Purpose:** Consume signals from all strategies, deduplicate, and persist to QuestDB.

### Key Responsibilities
1. **Consume** from `trading-signals` Kafka topic
2. **Deduplicate** - Same symbol + strategy within 5 minutes = ignore
3. **Persist** - Write to QuestDB `signals` table
4. **Expose REST API** - Query signals by symbol, strategy, time range

### REST API Endpoints
```
GET /api/signals?symbol=AAPL&limit=100
GET /api/signals/strategy/{strategyName}
GET /api/signals/latest
GET /api/signals/count
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

Step 6: Aggregator Consumes
        Checks: Is this duplicate? (No)
        Writes to QuestDB signals table

Step 7: Query signals
        SELECT * FROM signals 
        WHERE symbol = 'AAPL' 
        ORDER BY timestamp DESC
```

---

## Timeline & Milestones

### **Phase 2A: Core Infrastructure (Week 1)**

**Goal:** Get 1 strategy working end-to-end

**Tasks:**
1. Create shared `strategy-framework` module (optional - shared utilities)
2. Create `ma-crossover-strategy` service (first strategy)
3. Create Kafka topic: `trading-signals`
4. Create QuestDB table: `signals`
5. Create `signal-aggregator` service
6. Test: MA strategy produces signals → Aggregator persists → Query works

**Deliverable:** 1 strategy producing signals, stored in QuestDB

**Success criteria:**
- [ ] MA Crossover strategy runs every minute
- [ ] Queries QuestDB successfully
- [ ] Produces signals to Kafka
- [ ] Aggregator consumes and persists signals
- [ ] REST API returns signals

---

### **Phase 2B: Scale to 10 Strategies (Week 2)**

**Goal:** Implement remaining 9 strategies

**Tasks:**
7. Copy `ma-crossover-strategy` as template
8. Implement MACD strategy
9. Implement RSI strategy
10. Implement Bollinger Bands strategy
11. Implement Stochastic strategy
12. Implement Williams %R strategy
13. Implement ADX strategy
14. Implement Donchian Channel strategy
15. Implement ROC strategy
16. Implement VWAP strategy
17. Update docker-compose.yml with all 10 services

**Deliverable:** 10 strategies running in parallel

**Success criteria:**
- [ ] All 10 strategies start without errors
- [ ] Each strategy produces signals independently
- [ ] Signals table shows mix of strategies
- [ ] No duplicate signals (aggregator deduplication works)
- [ ] REST API shows signals from all 10 strategies

---

## Project Structure

```
QuantStream/
├── docker-compose.yml                  # Updated with new services
├── data-generator/                     # Phase 1 (unchanged)
├── database-consumer/                  # Phase 1 (unchanged)
│
├── strategy-framework/                 # NEW - Shared utilities (optional)
│   ├── pom.xml
│   └── src/main/java/com/quantstream/framework/
│       ├── model/Signal.java
│       ├── utils/IndicatorUtils.java   # MA, RSI calculations
│       └── config/BaseKafkaConfig.java
│
├── ma-crossover-strategy/              # NEW - Strategy 1
│   ├── pom.xml
│   └── src/main/java/...
│
├── rsi-strategy/                       # NEW - Strategy 5
│   ├── pom.xml
│   └── src/main/java/...
│
├── bollinger-bands-strategy/           # NEW - Strategy 6
│   ├── pom.xml
│   └── src/main/java/...
│
... (7 more strategy services)
│
└── signal-aggregator/                  # NEW - Aggregator
    ├── pom.xml
    └── src/main/java/...
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
  ma-crossover-strategy:
    build: ./ma-crossover-strategy
    ports:
      - "8083:8083"
    depends_on:
      - kafka
      - questdb
    environment:
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SPRING_DATASOURCE_URL: jdbc:postgresql://questdb:8812/qdb
  
  rsi-strategy:
    build: ./rsi-strategy
    ports:
      - "8084:8084"
    depends_on:
      - kafka
      - questdb
  
  # ... (8 more strategy services)
  
  signal-aggregator:
    build: ./signal-aggregator
    ports:
      - "8093:8093"
    depends_on:
      - kafka
      - questdb
    environment:
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
      SPRING_DATASOURCE_URL: jdbc:postgresql://questdb:8812/qdb
```

---

## Development Strategy

### Option 1: Sequential Development (Safer)
1. Build MA Crossover strategy completely
2. Test end-to-end
3. Copy as template
4. Implement next strategy
5. Repeat 9 times

**Pros:** Lower risk, easier debugging
**Cons:** Slower

### Option 2: Parallel Development (Faster)
1. Build MA Crossover strategy (prototype)
2. Create 9 empty service skeletons
3. Implement all 10 strategies in parallel
4. Test together

**Pros:** Faster
**Cons:** Harder to debug if pattern is wrong

**Recommendation:** **Sequential** for learning, **Parallel** if you're confident.

---

## Success Criteria

Phase 2 is complete when:

- [ ] All 10 strategy services start without errors
- [ ] Each strategy queries QuestDB successfully
- [ ] Strategies produce signals to Kafka `trading-signals` topic
- [ ] Aggregator consumes all signals
- [ ] Aggregator deduplicates correctly (no duplicate signals within 5 min)
- [ ] Signals persist to QuestDB `signals` table
- [ ] REST API endpoints return correct data
- [ ] Query works: `SELECT * FROM signals ORDER BY timestamp DESC LIMIT 100`
- [ ] Can see mix of strategies generating different signals
- [ ] System runs stably for 24 hours without crashes

---

## Key Differences from Phase 1

| Aspect | Phase 1 | Phase 2 |
|--------|---------|---------|
| **Services** | 2 (generator + consumer) | 12 (10 strategies + aggregator + phase 1) |
| **Kafka Topics** | 1 (`market-data`) | 2 (`market-data` + `trading-signals`) |
| **QuestDB Tables** | 1 (`ticks`) | 2 (`ticks` + `signals`) |
| **Data Flow** | Produce → Store | Read → Analyze → Produce → Store |
| **Complexity** | Linear pipeline | Parallel analysis |
| **CPU Usage** | Low | Medium (10 services calculating) |

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
