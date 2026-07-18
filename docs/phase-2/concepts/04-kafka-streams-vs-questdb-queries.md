# Kafka Streams vs QuestDB Queries for Real-Time Aggregation

**Date:** 2026-07-18  
**Context:** Phase 2 - Aggregator Service Design  
**Question:** Why use Kafka Streams instead of just querying QuestDB every minute?

---

## Table of Contents

1. [The Question](#the-question)
2. [Option 1: QuestDB Query Approach](#option-1-questdb-query-approach)
3. [Option 2: Kafka Streams Approach](#option-2-kafka-streams-approach)
4. [Side-by-Side Comparison](#side-by-side-comparison)
5. [When to Use Each](#when-to-use-each)
6. [Real-World Analogy](#real-world-analogy)
7. [Conclusion](#conclusion)

---

## The Question

Why not just query QuestDB every minute to generate candles?

```sql
SELECT 
  symbol,
  first(price) as open,
  max(price) as high,
  min(price) as low,
  last(price) as close,
  sum(volume) as volume,
  timestamp
FROM ticks
WHERE timestamp >= now() - INTERVAL '1' MINUTE
SAMPLE BY 1m ALIGN TO CALENDAR;
```

Instead of building a separate Kafka Streams aggregator service?

---

## Option 1: QuestDB Query Approach

### How It Works

Frontend (or backend service) polls QuestDB every 30 seconds:

```
┌─────────────────────────────────────────────────────────────┐
│ Timeline:                                                   │
│                                                             │
│ 14:00:00 ─┬─ Ticks start arriving                          │
│           │                                                 │
│ 14:00:59 ─┴─ Last tick in window                           │
│                                                             │
│ 14:01:00 ─── Window closes                                 │
│           │                                                 │
│ 14:01:30 ─── Frontend polls database (30s later!)          │
│           └─► Gets candle NOW                              │
│                                                             │
│ Latency: 30+ seconds (stale data!)                         │
└─────────────────────────────────────────────────────────────┘
```

If you poll every 5 seconds:
- ✓ Better latency (5s delay)
- ✗ But 12x more database queries!

---

### Problem 1: Polling Latency

**Issue:** Data is always stale by the polling interval.

- Poll every 30 seconds → 30 second old data
- Poll every 5 seconds → 5 second old data (but heavy load)
- Real-time trading needs < 1 second latency

---

### Problem 2: Database Load (The Real Killer)

**Scenario:** 100 concurrent users viewing charts

Each user's frontend:
- Polls every 30 seconds
- Queries last 60 minutes (for chart display)
- Watches 10 symbols

**Database load:**

```
100 users × (60 sec / 30 sec) × 10 symbols = 2,000 queries/minute
= 33 queries/second
```

**With 1,000 users:**

```
1,000 users × 2 queries/min × 10 symbols = 20,000 queries/minute
= 330 queries/second (database will struggle!)
```

**QuestDB is fast, but not designed for:**
- ✗ Thousands of concurrent SELECT queries
- ✗ Repeated aggregations over same data
- ✗ Real-time query workload (optimized for time-series writes)

---

### Problem 3: Cache Invalidation Nightmare

If you add caching to reduce load:

```
┌─────────────────────────────────────────────────────────────┐
│  User A queries → Cache miss → Query DB → Cache result     │
│  User B queries → Cache hit ✓                              │
│  User C queries → Cache hit ✓                              │
│                                                             │
│  New tick arrives! Cache is now stale...                   │
│                                                             │
│  When to invalidate?                                       │
│    - Every new tick? (too aggressive, cache useless)       │
│    - Every minute? (inconsistent data between users)       │
│    - TTL-based? (complex, error-prone, race conditions)    │
│                                                             │
│  "There are only two hard things in Computer Science:      │
│   cache invalidation and naming things." - Phil Karlton   │
└─────────────────────────────────────────────────────────────┘
```

---

### Problem 4: Event-Time vs Processing-Time

**Issue:** Ticks can arrive out of order due to network delays.

**Example:**

```
Actual order (event-time):
  14:00:10 → Tick C ($149.00)
  14:00:15 → Tick A ($150.00)
  14:00:30 → Tick B ($151.00)

Arrival order (processing-time):
  14:00:15 → Tick A ($150.00) ✓
  14:00:30 → Tick B ($151.00) ✓
  14:01:05 → Tick C ($149.00) ✗ (delayed 55 seconds!)
```

**QuestDB `SAMPLE BY` with `now()`:**
- Uses current time (processing-time)
- Tick C arrives at 14:01:05
- Goes into **WRONG window** (14:01 instead of 14:00)
- **OHLC calculation incorrect**

**Need complex logic:**
- Backfill previous windows
- Recompute affected candles
- Notify clients of updates
- Handle race conditions

This is messy and error-prone!

---

### Problem 5: No Exactly-Once Guarantee

**If frontend crashes mid-query:**
- Did the query complete?
- Did it partially commit?
- Which candles did user already see?

**If database restarts:**
- Clients need to refetch
- Duplicate processing
- Inconsistent state across users

---

### Problem 6: Tight Coupling

```
Frontend ←──(tight coupling)──→ QuestDB
```

**Problems:**
- Frontend needs to know SQL syntax
- Frontend needs to understand QuestDB's `SAMPLE BY`
- Database schema change = all clients break
- Can't switch databases without rewriting all frontends
- Testing requires full database setup
- Business logic (aggregation) lives in frontend

---

## Option 2: Kafka Streams Approach

### Architecture

```
market-data topic → [Aggregator Service] → candles-1m topic → Consumers
                         (Kafka Streams)                        ↓
                                                        - Database Consumer
                                                        - Frontend WebSocket
                                                        - Alerting Service
                                                        - Analytics Pipeline
```

---

### Advantage 1: Real-Time Processing (< 1 Second Latency)

```
┌─────────────────────────────────────────────────────────────┐
│ Timeline:                                                   │
│                                                             │
│ 14:00:00 ─┬─ Ticks start arriving                          │
│           │   Aggregator processes in real-time            │
│ 14:00:59 ─┴─ Last tick in window                           │
│                                                             │
│ 14:01:00.5 ─► Candle emitted to Kafka topic                │
│              (500ms after window close!)                   │
│                                                             │
│ 14:01:00.8 ─► Frontend receives via WebSocket/SSE          │
│                                                             │
│ Total latency: < 1 second ✓                                │
└─────────────────────────────────────────────────────────────┘
```

**No polling needed!** Event-driven push model.

---

### Advantage 2: Zero Database Read Load

**Aggregator reads from:** Kafka (not QuestDB)  
**Aggregator writes to:** Kafka (not QuestDB)

**Database only used for:**
- Historical queries (not real-time)
- One write per candle (database-consumer service)

**With 1,000 users:**
- Kafka Streams load: Constant (doesn't care about consumers)
- QuestDB read load: **0 queries/second** ✓

**Database freed up for:**
- Strategy engine queries
- Historical analysis
- Backfills
- Ad-hoc analytics

---

### Advantage 3: Built-In Windowing & State Management

**Kafka Streams provides:**

```java
// Just write this:
KStream<String, Tick> ticks = builder.stream("market-data");

ticks
  .groupByKey()
  .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
  .aggregate(
    Candle::new,           // Initializer
    (key, tick, candle) -> candle.add(tick),  // Aggregator
    Materialized.with(Serdes.String(), candleSerde)
  )
  .toStream()
  .to("candles-1m");
```

**Kafka Streams handles:**
- ✓ Window lifecycle (open, close, purge)
- ✓ State persistence (RocksDB embedded)
- ✓ Changelog topics (automatic backup)
- ✓ Recovery on failure
- ✓ Late event handling
- ✓ Checkpoint management
- ✓ Exactly-once semantics

**You don't write:** Cache invalidation logic, window tracking, state cleanup!

---

### Advantage 4: Event-Time Windowing (Handles Out-of-Order)

**Ticks arrive out of order:**

```
14:00:15 → Tick A ($150.00)
14:00:30 → Tick B ($151.00)
14:00:10 → Tick C ($149.00) ← Late!
```

**Kafka Streams with event-time:**

```java
.withTimestampExtractor(new TickTimestampExtractor())
  ↓
Uses tick.timestamp for windowing, not Kafka record timestamp
```

- ✓ Uses `tick.timestamp` field (not arrival time)
- ✓ Tick C goes into **correct window** (14:00)
- ✓ OHLC calculation correct
- ✓ Grace period configurable (wait for late events)

**Example:**

```java
// Wait up to 5 seconds for late events
TimeWindows.ofSizeAndGrace(
  Duration.ofMinutes(1),
  Duration.ofSeconds(5)
)
```

---

### Advantage 5: Exactly-Once Guarantee

**Kafka Streams with `exactly_once_v2`:**

```
┌─────────────────────────────────────────────────────────────┐
│ Single Transaction:                                         │
│   1. Read tick from market-data                            │
│   2. Update candle in state store (RocksDB)                │
│   3. Emit candle to candles-1m topic                       │
│   4. Commit offset + state                                 │
│                                                             │
│ If crash happens:                                          │
│   → Transaction aborts                                     │
│   → Restart from last committed offset                     │
│   → State restored from changelog                          │
│   → No duplicate candles ✓                                 │
│   → No data loss ✓                                         │
└─────────────────────────────────────────────────────────────┘
```

**Financial-grade reliability!**

Configuration:

```java
props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, "exactly_once_v2");
```

---

### Advantage 6: Horizontal Scalability

**QuestDB Query Approach:**
- Database → Vertical scaling only (bigger machine)
- Max throughput: Single database capacity
- Cost: Exponential

**Kafka Streams Approach:**

```
market-data topic (3 partitions)
       │
       ├─► Aggregator Instance 1 (partition 0) → AAPL, MSFT, GOOGL
       ├─► Aggregator Instance 2 (partition 1) → TSLA, AMZN, BTC
       └─► Aggregator Instance 3 (partition 2) → ETH, SOL, AVAX, MATIC
       
Each instance:
  - Processes different symbols
  - Independent state stores
  - No coordination needed

Max throughput: 3x (linear scaling)

Need more? Add partitions + instances!
```

**Scalability:**
- Horizontal (add cheap commodity servers)
- Cost: Linear with data volume

---

### Advantage 7: Decoupling (Clean Architecture)

```
Data Generator → Kafka → Aggregator → Kafka → Database Consumer
                   ↓                     ↓
             market-data            candles-1m
```

**Benefits:**
- ✓ Aggregator can fail → Frontend still shows cached candles
- ✓ Database can restart → Aggregation continues
- ✓ Add new consumer → No impact on aggregator
- ✓ Switch database → Only change database-consumer
- ✓ Test aggregator → No database needed (`TopologyTestDriver`)

**Each service has ONE job:**
- Data Generator: Generate ticks
- Aggregator: Create candles
- Database Consumer: Persist data
- Strategy Engine: Analyze data
- Frontend: Display charts

**Single Responsibility Principle!**

---

### Advantage 8: Candles as First-Class Events

In Kafka, candles are events:

```
┌─────────────────────────────────────────────────────────────┐
│  candles-1m topic                                           │
│                                                             │
│  Multiple consumers can subscribe:                          │
│    • database-consumer   → Writes to QuestDB              │
│    • alerting-service    → Checks price thresholds        │
│    • frontend-websocket  → Real-time charts               │
│    • analytics-pipeline  → ML feature extraction          │
│    • audit-logger        → Compliance logs                │
│                                                             │
│  Each consumer:                                            │
│    - Independent offset tracking                           │
│    - Can process at own pace                              │
│    - No impact on others                                  │
└─────────────────────────────────────────────────────────────┘
```

**Candles computed ONCE, consumed MANY times!**

This is the **"Compute Once, Serve Many"** pattern.

---

## Side-by-Side Comparison

| Aspect | QuestDB Query | Kafka Streams |
|--------|---------------|---------------|
| **Latency** | 30+ seconds | < 1 second |
| **Database Load** | 100s queries/sec | 0 queries/sec |
| **Real-time** | ✗ Polling-based | ✓ Stream processing |
| **Event-time handling** | ✗ Complex backfills | ✓ Built-in |
| **Exactly-once** | ✗ Manual | ✓ Transactional |
| **Horizontal Scaling** | ✗ Database limited | ✓ Add instances |
| **State Management** | ✗ Manual cache | ✓ RocksDB + changelog |
| **Out-of-order events** | ✗ Messy logic | ✓ Grace period |
| **Decoupling** | ✗ Tight coupling | ✓ Event-driven |
| **Memory Footprint** | Database RAM | 200-300 MB |
| **Code Complexity** | Medium (SQL + cache) | Low (declarative DSL) |
| **Testing** | Need full database | TopologyTestDriver |
| **Failure Recovery** | Manual retry | Automatic (changelog) |
| **Multi-consumer** | ✗ Database load | ✓ Independent offsets |

---

## When to Use Each

### Use QuestDB Query For:

- ✓ **Historical analysis** (1-hour, 1-day candles from existing data)
- ✓ **Backtesting** (analyzing past patterns)
- ✓ **One-off aggregations** (ad-hoc queries)
- ✓ **Batch reports** (daily summaries)
- ✓ **Low volume** (< 10 queries/second)
- ✓ **Flexibility more important than performance**

**Example:**

```sql
-- Historical 1-day candles for chart
SELECT 
  first(price) as open,
  max(price) as high,
  min(price) as low,
  last(price) as close
FROM ticks
WHERE symbol = 'AAPL' 
  AND timestamp BETWEEN '2024-01-01' AND '2024-01-31'
SAMPLE BY 1d;
```

---

### Use Kafka Streams For:

- ✓ **Real-time dashboards** (< 1s latency)
- ✓ **High volume** (1000+ events/second)
- ✓ **Multiple consumers** (100+ users)
- ✓ **Fault tolerance critical** (financial data)
- ✓ **Event-time semantics** (out-of-order events)
- ✓ **Continuous aggregation** (always running)
- ✓ **Event-driven architecture** (decoupled services)

---

## Architecture Pattern: Lambda Architecture

This is the classic **Lambda Architecture** pattern:

```
Hot Path (Real-time):
  Ticks → Kafka Streams → Candles → Live Dashboard
  Latency: < 1 second
  Use case: Trading decisions, live monitoring

Cold Path (Batch):
  Ticks → QuestDB → SQL queries → Historical analysis
  Latency: Minutes to hours
  Use case: Backtesting, research, reports
```

**Best of both worlds:**
- Kafka Streams for **real-time serving**
- QuestDB for **historical queries**
- Same data, different access patterns

---

## Real-World Analogy

### QuestDB Query = Restaurant Kitchen Taking Orders by Phone

```
Customer 1 calls: "What's available?" → Kitchen checks → Responds
Customer 2 calls: "What's available?" → Kitchen checks → Responds
Customer 3 calls: "What's available?" → Kitchen checks → Responds

Problems:
  - Kitchen staff busy answering phones (can't cook!)
  - Same info repeated 100 times
  - New dish? Call everyone to update!
  - Phone lines jammed during peak hours
```

---

### Kafka Streams = Restaurant with a Menu Board

```
Kitchen updates menu board once (emit candle to topic)
All customers read board simultaneously (subscribe to topic)

Benefits:
  - Kitchen focuses on cooking (database writes only)
  - Info shared efficiently (one write, many reads)
  - New dish? Update board once (all customers see it)
  - Scales to 1000s of customers (no overhead)
```

---

## Conclusion

### Why Kafka Streams for 1-Minute Candles?

**Because we need:**
- ✓ **Real-time** (< 1s latency, not 30s polling)
- ✓ **Scalability** (1000s of users, not 100 queries/sec on database)
- ✓ **Correctness** (event-time windowing, exactly-once)
- ✓ **Decoupling** (aggregator fails ≠ database fails)
- ✓ **Simplicity** (built-in state management, no manual caching)

### Division of Responsibilities

**QuestDB is for:**
- ✓ Storage (persistent data)
- ✓ Historical queries (past analysis)
- ✓ Ad-hoc analysis (one-off queries)

**Kafka Streams is for:**
- ✓ Real-time processing (streaming)
- ✓ Continuous aggregation (always running)
- ✓ Event-driven architecture (decoupled)

### In Our Phase 2 Architecture

**Strategy Engine:** Uses QuestDB queries (flexibility needed for complex analysis)  
**Frontend Charts:** Uses Kafka Streams candles (performance + real-time needed)

**Right tool for the right job!** 🛠️

---

## Further Reading

- [Kafka Streams Documentation](https://kafka.apache.org/documentation/streams/)
- [QuestDB SAMPLE BY Documentation](https://questdb.io/docs/reference/sql/sample-by/)
- [Lambda Architecture Pattern](https://en.wikipedia.org/wiki/Lambda_architecture)
- [Event Time vs Processing Time](https://www.oreilly.com/radar/the-world-beyond-batch-streaming-101/)
