# Why Do We Need Kafka?

## The Problem: Direct Database Writes Don't Scale

### Scenario 1: Without Kafka (Direct Write)

```
Market Data Generator → Database (QuestDB)
```

**Seems simple, right? Let's see what happens:**

```java
// Generator sends 10 messages/second
for (Tick tick : generateTicks()) {
    database.insert(tick);  // Blocks until database confirms write
}
```

**What happens when database is slow?**

1. Generator calls `database.insert(tick1)` at time 0ms
2. Database is busy, takes 200ms to respond
3. Generator is **blocked**, waiting for response
4. At 200ms, database confirms
5. Generator sends tick2
6. Database again takes 200ms
7. Generator is blocked again

**Result:**
- Generator can only send **5 messages/second** (1000ms / 200ms = 5)
- We need 10 messages/second
- **System can't keep up**
- Messages are lost or delayed

### Scenario 2: What If Database Crashes?

```
10:00:00 - Generator sends tick, database saves ✅
10:00:01 - Generator sends tick, database saves ✅
10:00:02 - 💥 Database crashes
10:00:03 - Generator sends tick, database is down ❌
10:00:04 - Generator sends tick, database is down ❌
10:00:05 - Database restarts
10:00:06 - Generator sends tick, database saves ✅
```

**Lost:** Ticks from 10:00:03 and 10:00:04 are **gone forever**.

### Scenario 3: Multiple Consumers

What if we want to:
1. Save to database
2. Calculate real-time statistics
3. Send alerts
4. Stream to dashboard

**Without Kafka:**

```java
for (Tick tick : generateTicks()) {
    database.insert(tick);           // 200ms
    statistics.calculate(tick);      // 50ms
    alertService.check(tick);        // 30ms
    dashboard.broadcast(tick);       // 20ms
}
// Total: 300ms per tick
// Can only handle 3 messages/second
```

**Each consumer makes it slower.**

---

## The Solution: Kafka as a Message Buffer

### With Kafka

```
Market Data Generator → Kafka (buffer) → Database
                                      → Statistics Service
                                      → Alert Service
                                      → Dashboard Service
```

**How it works:**

```java
// Generator (Producer)
for (Tick tick : generateTicks()) {
    kafka.send(tick);  // Returns immediately (async)
}
// Can send 10,000+ messages/second
```

```java
// Database Consumer
@KafkaListener(topics = "market-data")
public void consume(Tick tick) {
    database.insert(tick);  // Can be slow, doesn't affect producer
}
```

**Key insight:** Producer and consumer are **decoupled**.

---

## How Kafka Works (Simple Mental Model)

Think of Kafka as a **multi-lane highway** where messages are cars:

### 1. Topic = Highway Name

```
Topic: "market-data"
Topic: "user-actions"
Topic: "sensor-readings"
```

Each topic is a separate highway. Messages in one topic don't affect another.

### 2. Partition = Lane

```
Topic: market-data (3 partitions)

Partition 0: [AAPL] [MSFT] [GOOGL] ...
Partition 1: [BTC]  [ETH]  [SOL]   ...
Partition 2: [TSLA] [AMZN] [META]  ...
```

Messages with the same key (e.g., "AAPL") always go to the same partition.

**Why partitions?**
- **Parallelism:** 3 consumers can read from 3 partitions simultaneously
- **Ordering:** All "AAPL" messages are in order within partition 0
- **Scalability:** Add more partitions = handle more throughput

### 3. Offset = Mile Marker

```
Partition 0:
Offset 0:    {symbol: "AAPL", price: 100}
Offset 1:    {symbol: "AAPL", price: 101}
Offset 2:    {symbol: "AAPL", price: 102}
Offset 3:    {symbol: "AAPL", price: 103}  ← Consumer is here
Offset 4:    {symbol: "AAPL", price: 104}
```

Each message has an **offset** (position in the partition).

Consumer tracks: "I've processed up to offset 3".

If consumer crashes and restarts, it resumes from offset 4.

### 4. Producer = Car Entering Highway

```java
kafkaTemplate.send("market-data", "AAPL", tick);
//                  ↑ topic        ↑ key   ↑ message
```

**What happens:**
1. Producer sends message to Kafka broker
2. Kafka determines partition based on key ("AAPL")
3. Kafka appends message to partition log
4. Kafka returns acknowledgment to producer
5. **Producer moves on** (doesn't wait for consumers)

**Time:** ~5-10ms

### 5. Consumer = Car on Highway

```java
@KafkaListener(topics = "market-data", groupId = "database-group")
public void consume(Tick tick) {
    // Process message
    database.insert(tick);
}
```

**What happens:**
1. Consumer asks Kafka: "Give me next message from offset 3"
2. Kafka returns message at offset 4
3. Consumer processes it
4. Consumer commits: "I finished offset 4"
5. Consumer asks for offset 5

**If processing is slow:** Producer keeps sending, messages buffer in Kafka.

**If consumer crashes:** Another consumer in the same group takes over.

---

## Kafka Benefits (Why We Use It)

### 1. Decoupling

**Before:**
```
Generator ←→ Database (tightly coupled)
If database is slow/down, generator is affected
```

**After:**
```
Generator → Kafka ← Database (decoupled)
Database can be slow/down, generator is unaffected
```

### 2. Durability

Messages are stored on disk for a configured time (e.g., 5 minutes).

```
10:00:00 - Producer sends 100 messages to Kafka ✅
10:00:01 - Consumer crashes 💥
10:00:30 - Consumer restarts
10:00:31 - Consumer reads 100 messages from Kafka ✅
```

**No data loss.**

### 3. Scalability

**Need more throughput?**

Add partitions:
```
Before: 1 partition  → 1 consumer  → 100 msg/sec
After:  10 partitions → 10 consumers → 1,000 msg/sec
```

Each consumer processes 1 partition independently.

### 4. Multiple Consumers

**Want to add new consumer?**

```java
// Existing consumer (unaffected)
@KafkaListener(topics = "market-data", groupId = "database-group")
public void saveToDatabase(Tick tick) { ... }

// New consumer (reads same messages)
@KafkaListener(topics = "market-data", groupId = "analytics-group")
public void calculateStats(Tick tick) { ... }
```

Each consumer group reads messages independently.

**Result:** Same message can be processed by multiple consumers for different purposes.

---

## When NOT to Use Kafka

### 1. Low Message Volume

If you send < 100 messages/hour, Kafka is overkill.

**Better:** Direct database writes or a simple queue (RabbitMQ, SQS)

### 2. Strict Ordering Across Keys

If you need global ordering (not per-key ordering), Kafka with 1 partition works but limits scalability.

**Better:** Single-threaded processing or database transaction log

### 3. Request-Response Pattern

If you need immediate response from consumer:

```
User clicks button → System processes → User sees result
```

**Better:** REST API or RPC (gRPC)

Kafka is for **fire-and-forget**, not request-response.

### 4. Small Team, Simple Application

Kafka adds operational complexity:
- Need to run Zookeeper + Kafka broker
- Monitor consumer lag
- Handle rebalancing
- Manage disk space

**Better:** Start simple (direct writes), add Kafka when you hit scale issues

---

## Our Use Case: Why Kafka Fits

### Requirements

- **1,000 messages/second** (10 tokens × 1 msg/sec in dev, scales to 1,000 tokens)
- **Multiple consumers:**
  1. Database consumer (saves to QuestDB)
  2. Aggregator (calculates candles)
  3. Strategy evaluator (technical indicators)
  4. WebSocket publisher (real-time dashboard)
- **Durability:** Can't lose messages during restarts
- **Scalability:** Should handle 10x, 100x growth

### Why Direct Database Wouldn't Work

```
Generator (1,000 msg/sec)
    ↓
QuestDB (500 msg/sec max)
    ↓
Result: Backlog, lost messages
```

Even if QuestDB could handle it:
- Adding new consumers slows down the whole system
- Database restart = message loss
- Hard to scale (can't just add more database instances)

### With Kafka

```
Generator (1,000 msg/sec)
    ↓
Kafka (10,000+ msg/sec)
    ↓
Database (reads at its own pace)
Aggregator (reads at its own pace)
Strategy Evaluator (reads at its own pace)
```

**Each consumer is independent.**

---

## Key Concepts Summary

### Topic
- Category of messages (like a database table)
- Example: "market-data", "user-events"

### Partition
- Sub-division of a topic for parallel processing
- Messages with same key go to same partition
- Each partition is an ordered log

### Offset
- Position of a message in a partition
- Like a line number in a file
- Consumers track their offset

### Producer
- Sends messages to Kafka topics
- Fire-and-forget (doesn't wait for consumers)
- Asynchronous, high throughput

### Consumer
- Reads messages from Kafka topics
- Tracks its position (offset)
- Can resume after crashes

### Consumer Group
- Multiple consumers working together
- Each partition is consumed by one consumer in the group
- Automatic load balancing

---

## Next Steps

Now that you understand WHY we use Kafka and HOW it works conceptually, you're ready to:

1. Set it up with Docker Compose
2. Create a producer (market data generator)
3. Create a consumer (database writer)
4. See messages flowing through the system

**Next:** Read `02-questdb-basics.md` to understand our time-series database.
