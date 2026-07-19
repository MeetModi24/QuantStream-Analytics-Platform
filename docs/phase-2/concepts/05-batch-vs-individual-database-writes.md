# Batch vs Individual Database Writes - Design Concept

**Date:** 2026-07-19  
**Context:** Database-Consumer Service Design Decision  
**Target Audience:** Beginners to intermediate developers

---

## The Problem: Writing High-Volume Data to Database

When consuming messages from Kafka and writing to a database, we have two choices:

1. **Individual Processing**: Write each message immediately (one database INSERT per message)
2. **Batch Processing**: Collect multiple messages, write them all at once (one database INSERT for many messages)

**Key Question:** Which approach should we use for ticks, candles, and signals?

---

## Understanding the Data Volumes

### Our System (10 symbols, 1 tick/second/symbol):

```
Data Generator → Kafka → Database Consumer → QuestDB
     ↓              ↓            ↓              ↓
  Raw ticks    market-data   TickConsumer   ticks table
  (10/sec)      topic        (600/min)      (HIGHEST VOLUME)
     
     ↓ Aggregation (60 ticks → 1 candle)
     
  Aggregator  → Kafka  → Database Consumer → QuestDB
     ↓           ↓            ↓               ↓
  Candles   candles-1m  CandleConsumer  candles_1m table
  (10/min)    topic      (10/min)       (MEDIUM VOLUME)
  
     ↓ Analysis (rare signals)
     
Strategy Engine → Kafka → Database Consumer → QuestDB
     ↓             ↓           ↓                ↓
  Signals   trading-signals SignalConsumer  signals table
  (1-5/min)     topic        (1-5/min)      (LOWEST VOLUME)
```

### Volume Hierarchy (Sorted by Message Rate):

| Data Type | Messages/Minute | Messages/Second | Why This Volume? |
|-----------|----------------|-----------------|------------------|
| **Ticks** | 600 | 10 | Raw market data: 10 symbols × 1 tick/sec × 60 sec |
| **Candles** | 10 | 0.16 | Aggregation: 60 ticks → 1 candle, so 60x less |
| **Signals** | 1-5 | 0.02-0.08 | Rare events: Only when strategy detects opportunity |

**Critical Understanding:**
- Candles are made FROM ticks (aggregation)
- Therefore, ticks MUST have higher volume than candles
- It's impossible for candles to have more volume than ticks

---

## How Database Writes Work

### Individual Write Process

When you write ONE record:

```
Application                   Database (QuestDB)
    |                              |
    |---- INSERT INTO ticks ------>|
    |      (network round-trip)    |
    |                              |-- Find table
    |                              |-- Parse SQL
    |                              |-- Acquire lock
    |                              |-- Write to disk
    |                              |-- Update index
    |<----- Success/Failure -------|
    |      (network round-trip)    |
    |                              |
```

**Total time: ~10-50ms** depending on:
- Network latency (2-5ms local, 50-100ms remote)
- Database lock contention (1-10ms)
- Disk write (1-5ms SSD, 5-20ms HDD)
- Index update (1-5ms)

### Batch Write Process

When you write 100 records at once:

```
Application                   Database (QuestDB)
    |                              |
    |-- INSERT INTO ticks (100) -->|
    |   (network round-trip)       |
    |                              |-- Find table (ONCE)
    |                              |-- Parse SQL (ONCE)
    |                              |-- Acquire lock (ONCE)
    |                              |-- Write 100 rows to disk
    |                              |-- Update index (ONCE)
    |<---- Success/Failure --------|
    |   (network round-trip)       |
    |                              |
```

**Total time: ~50-100ms** for ALL 100 records

---

## The Math: Why Batch Processing is Faster

### Individual Processing (600 ticks/minute):

```
Per-message overhead:
  - Network round-trip: 5ms
  - SQL parsing: 2ms
  - Lock acquisition: 3ms
  - Actual write: 2ms
  - Index update: 3ms
  - Network response: 5ms
  TOTAL: 20ms per message

For 600 messages:
  600 messages × 20ms = 12,000ms = 12 seconds
  
Available time: 60 seconds/minute
Utilization: 12 seconds / 60 seconds = 20%
Status: ✅ Keeping up, but inefficient
```

### Batch Processing (600 ticks/minute, batches of 100):

```
Per-batch overhead:
  - Network round-trip: 5ms
  - SQL parsing: 2ms (ONCE for all 100)
  - Lock acquisition: 3ms (ONCE for all 100)
  - Actual write: 20ms (100 rows, slightly slower)
  - Index update: 10ms (ONCE for all 100)
  - Network response: 5ms
  TOTAL: 45ms per batch of 100

For 600 messages (6 batches):
  6 batches × 45ms = 270ms = 0.27 seconds
  
Available time: 60 seconds/minute
Utilization: 0.27 seconds / 60 seconds = 0.45%
Status: ✅ Highly efficient
```

### Performance Comparison

| Approach | Time for 600 Messages | Database Calls | Efficiency |
|----------|----------------------|----------------|------------|
| Individual | 12 seconds | 600 | 20% CPU used |
| Batch (100) | 0.27 seconds | 6 | 0.45% CPU used |
| **Speedup** | **44x faster** | **100x fewer calls** | **44x more efficient** |

---

## Real-World Example: Restaurant Analogy

### Individual Processing = Making One Trip Per Item

You're moving to a new apartment. You have 100 boxes to move.

**Individual approach:**
```
Trip 1: Carry 1 box (10 minutes: pack, drive, unpack)
Trip 2: Carry 1 box (10 minutes)
Trip 3: Carry 1 box (10 minutes)
...
Trip 100: Carry 1 box (10 minutes)

Total time: 100 trips × 10 minutes = 1,000 minutes (16.7 hours)
```

**Batch approach:**
```
Trip 1: Load 20 boxes in truck (12 minutes: pack, drive, unpack all 20)
Trip 2: Load 20 boxes in truck (12 minutes)
Trip 3: Load 20 boxes in truck (12 minutes)
Trip 4: Load 20 boxes in truck (12 minutes)
Trip 5: Load 20 boxes in truck (12 minutes)

Total time: 5 trips × 12 minutes = 60 minutes (1 hour)
```

**Savings: 16.7 hours → 1 hour** (16x faster!)

The "trip" (network + overhead) is expensive. Carrying 20 boxes instead of 1 doesn't take 20x longer - the trip time dominates.

---

## Where the Overhead Comes From

### 1. Network Round-Trip (5-10ms per call)

```
Application (localhost)  →  Database (localhost)
              ↓ 2-5ms
         Network stack
              ↓ 2-5ms
         Database receives
```

**Individual:** 600 messages × 5ms = 3 seconds of network time  
**Batch:** 6 batches × 5ms = 30ms of network time  
**Savings: 2.97 seconds (99x less network time)**

### 2. SQL Parsing (1-3ms per statement)

Database must parse: `INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)`

**Individual:** Parse 600 times = 1.8 seconds  
**Batch:** Parse 6 times = 18ms  
**Savings: 1.782 seconds (100x less parsing)**

### 3. Lock Acquisition (1-5ms per transaction)

Database must lock the table for each write (prevents concurrent conflicts).

**Individual:** Acquire lock 600 times = 3 seconds  
**Batch:** Acquire lock 6 times = 30ms  
**Savings: 2.97 seconds (100x less locking)**

### 4. Index Updates (1-5ms per write)

After writing data, database updates indexes for fast queries.

**Individual:** Update index 600 times = 3 seconds  
**Batch:** Update index 6 times (bulk update) = 60ms  
**Savings: 2.94 seconds (50x less index work)**

### Total Overhead Saved:

```
Network:  2.97 seconds
Parsing:  1.78 seconds
Locking:  2.97 seconds
Indexing: 2.94 seconds
─────────────────────────
Total:   10.66 seconds saved out of 12 seconds
         = 89% of time was just overhead!
```

**Key Insight:** For high-volume data, the actual data writing is FAST. The overhead (network, parsing, locking) is what kills performance.

---

## When to Use Each Approach

### Use Individual Processing When:

✅ **Low message volume** (< 10 messages/minute)
- Overhead is small compared to total time
- Example: 5 messages × 20ms = 100ms (negligible)

✅ **Real-time requirement** (latency matters more than throughput)
- User needs to see data immediately
- Example: Trading signals (trader wants instant notification)

✅ **Each message is independent** (no natural batching boundary)
- Messages arrive sporadically
- Example: User login events

### Use Batch Processing When:

✅ **High message volume** (> 100 messages/minute)
- Overhead dominates processing time
- Example: 600 ticks/minute (10 seconds of overhead per minute)

✅ **Latency is acceptable** (can wait 1-2 seconds for batch to fill)
- Data is for analysis, not real-time alerts
- Example: Historical candles for charting

✅ **Messages arrive in bursts** (natural batching boundary)
- Kafka consumer fetches 100-500 messages at once
- Example: Processing backlog after downtime

---

## Our Design Decision

### Data Type: Ticks

**Volume:** 600/minute (10 messages/second)  
**Decision:** ✅ **BATCH PROCESSING**

**Reasoning:**
1. Highest volume in our system
2. Individual processing wastes 10+ seconds/minute on overhead
3. Batch processing reduces overhead by 44x
4. No real-time requirement (ticks are for storage, not alerts)
5. Kafka already fetches in batches (MAX_POLL_RECORDS=500)

**Batch Size:** 100-500 ticks per batch  
**Expected Latency:** < 100ms per batch  
**Expected Throughput:** 10,000+ ticks/second (far exceeds current 10/sec)

### Data Type: Candles

**Volume:** 10/minute (0.16 messages/second)  
**Decision:** ✅ **BATCH PROCESSING**

**Reasoning:**
1. Current volume is low (10/min), but...
2. Future scaling: 1,000 symbols = 1,000 candles/minute
3. Backlog processing: 4,975 candles accumulated during aggregator downtime
4. Code consistency: Same pattern as ticks
5. No real-time requirement (candles are for charts, not alerts)

**Batch Size:** 10-500 candles per batch  
**Expected Latency:** < 100ms per batch  
**Expected Throughput:** 5,000+ candles/second

**Why batch despite low current volume?**
- Handles backlog efficiently (4,975 candles in 10 batches = 1 second)
- Future-proof for scaling (no code changes needed)
- Consistent architecture (all high-volume data uses batching)

### Data Type: Signals

**Volume:** 1-5/minute (0.02-0.08 messages/second)  
**Decision:** ❌ **INDIVIDUAL PROCESSING**

**Reasoning:**
1. Lowest volume in system (1-5/min)
2. Real-time requirement: Traders want immediate signal notification
3. Overhead is negligible: 5 messages × 20ms = 100ms (vs 60 seconds available)
4. Simplicity: No batching logic needed
5. Better failure isolation: One bad signal doesn't block others

**Processing:** Individual message, commit immediately  
**Expected Latency:** < 20ms per signal  
**Expected Throughput:** 100+ signals/second (far exceeds current 0.08/sec)

---

## Architecture Comparison

### Before (Inconsistent):

```
Ticks (600/min)      → Individual ❌ (wasting 10+ seconds/min on overhead)
Candles (10/min)     → Batch ✅ (but lower volume than ticks!)
Signals (1-5/min)    → Individual ✅
```

**Problem:** Batching the LOWER volume data but not the HIGHER volume data!

### After (Consistent & Efficient):

```
Ticks (600/min)      → Batch ✅ (highest volume, most benefit)
Candles (10/min)     → Batch ✅ (future-proof, backlog handling)
Signals (1-5/min)    → Individual ✅ (real-time, low volume)
```

**Rationale:** Batch by volume descending, switch to individual only for real-time low-volume.

---

## Implementation Strategy

### 1. Modify Existing TickConsumer (Batch)

**Current (Individual):**
```java
@KafkaListener(topics = "market-data", ...)
public void consumeTick(@Payload Tick tick, ...) {
    // Process ONE tick
    jdbcTemplate.update(sql, tick.getSymbol(), tick.getPrice(), ...);
}
```

**New (Batch):**
```java
@KafkaListener(topics = "market-data", ...)
public void consumeTicks(@Payload List<Tick> ticks, ...) {
    // Process 100-500 ticks at once
    jdbcTemplate.batchUpdate(sql, ticks, ticks.size(), (ps, tick) -> {
        ps.setString(1, tick.getSymbol());
        ps.setDouble(2, tick.getPrice());
        // ... set all parameters
    });
}
```

**Changes Required:**
- Change `@Payload Tick tick` → `@Payload List<Tick> ticks`
- Add `factory.setBatchListener(true)` in KafkaConsumerConfig
- Change `jdbcTemplate.update()` → `jdbcTemplate.batchUpdate()`
- Update validation to handle list

### 2. Create New CandleConsumer (Batch)

Same pattern as TickConsumer:
- Batch listener
- List<Candle> parameter
- batchUpdate() for persistence

### 3. Create New SignalConsumer (Individual)

Simple individual processing:
- Single message listener
- Signal parameter (not List<Signal>)
- update() for persistence (not batchUpdate)

---

## Performance Expectations

### Current (Individual Tick Processing):

```
Ticks: 600/min
Time spent on database: 12 seconds/min
CPU usage: 20% of available time
Scalability: Up to ~3,000 ticks/min before falling behind
```

### After (Batch Tick Processing):

```
Ticks: 600/min
Time spent on database: 0.27 seconds/min
CPU usage: 0.45% of available time
Scalability: Up to ~100,000+ ticks/min before falling behind
```

**Improvement:**
- 44x faster processing
- 44x more CPU headroom
- 33x better scalability

---

## Common Misconceptions (What I Got Wrong Initially)

### Misconception 1: "Candles have higher volume than ticks"

❌ **WRONG!** Candles are aggregations of ticks.
- 60 ticks → 1 candle
- Therefore: Candles = Ticks ÷ 60
- Candles MUST have lower volume than ticks

✅ **CORRECT:** Ticks have 60x more volume than candles.

### Misconception 2: "Batch low-volume data, individual for high-volume"

❌ **WRONG!** This is backwards.
- High volume = more overhead = more benefit from batching
- Low volume = less overhead = batching is overkill

✅ **CORRECT:** Batch high-volume, individual for low-volume (unless real-time).

### Misconception 3: "Batching adds latency, so avoid it"

⚠️ **PARTIAL TRUTH:** Batching adds small latency (waiting for batch to fill).
- Individual: 20ms latency per message
- Batch: 100ms latency (wait for 100 messages + write)
- Difference: 80ms extra latency

But for non-real-time data (ticks, candles), this is acceptable:
- Ticks are for historical storage (80ms doesn't matter)
- Candles are for charting (80ms doesn't matter)
- Signals are for alerts (80ms DOES matter, so use individual)

✅ **CORRECT:** Use batching unless latency is critical AND volume is low.

---

## Testing the Design

### Test 1: Measure Individual vs Batch (Console)

```sql
-- Create test table
CREATE TABLE test_ticks (
    symbol SYMBOL,
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp);

-- Individual inserts (run 100 times manually)
-- Time: ~2-5 seconds for 100 inserts
INSERT INTO test_ticks VALUES ('AAPL', 150.0, 1000000, now());

-- Batch insert (run once)
-- Time: ~50-100ms for 100 inserts
INSERT INTO test_ticks VALUES
  ('AAPL', 150.0, 1000000, now()),
  ('AAPL', 150.1, 1000000, now()),
  ... (98 more rows)
  ('AAPL', 151.0, 1000000, now());

-- Batch is 20-100x faster!
```

### Test 2: Monitor Consumer Lag

```bash
# After deploying batch consumers
docker exec kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --describe --all-groups

# Check LAG column:
# - LAG = 0 → Consumer keeping up ✅
# - LAG > 0 and growing → Consumer falling behind ❌
```

### Test 3: Database CPU Usage

```bash
# Monitor QuestDB resource usage
docker stats questdb

# Before batching: 5-10% CPU (mostly overhead)
# After batching: 1-2% CPU (mostly actual work)
```

---

## Summary Table

| Consumer | Volume | Strategy | Batch Size | Latency | Reason |
|----------|--------|----------|------------|---------|--------|
| Tick | 600/min | **Batch** | 100-500 | 100ms | Highest volume, no real-time requirement |
| Candle | 10/min | **Batch** | 10-500 | 100ms | Future scaling, backlog handling |
| Signal | 1-5/min | Individual | 1 | 20ms | Real-time alerts, lowest volume |

---

## Key Takeaways

1. **Volume determines strategy:** High volume → batch, low volume → individual
2. **Overhead dominates at scale:** 89% of time is network/parsing/locking, not actual writes
3. **Batching = 44x speedup:** From 12 seconds/min to 0.27 seconds/min
4. **Ticks > Candles:** Ticks are raw data, candles are aggregations (60:1 ratio)
5. **Real-time vs throughput:** Signals need real-time, ticks/candles need throughput
6. **Future-proof design:** Code works at 10 symbols or 10,000 symbols without changes

---

## Further Reading

- [QuestDB Bulk Loading Best Practices](https://questdb.io/docs/guides/bulk-loading/)
- [Kafka Consumer Batching](https://kafka.apache.org/documentation/#consumerconfigs_max.poll.records)
- [JDBC Batch Updates](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/jdbc/core/JdbcTemplate.html#batchUpdate-java.lang.String-java.util.Collection-int-org.springframework.jdbc.core.ParameterizedPreparedStatementSetter-)
- [Database Connection Pooling](https://github.com/brettwooldridge/HikariCP)

---

**Document Owner:** QuantStream Team  
**Last Updated:** 2026-07-19  
**Status:** Final Design Decision
