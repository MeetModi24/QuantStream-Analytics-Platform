# Understanding Kafka

## What is Kafka and Why Do We Need It?

### The Problem

You have 1,000 tokens (BTC, ETH, etc.) that update every second:
- 1,000 tokens × 1 update/second = **1,000 messages per second**
- Multiple services need this data (database, aggregator, frontend)
- Services run at different speeds

### Bad Approach (Direct Coupling)

```
Generator → Database → API → Frontend
```

**Problems:**
- Generator must wait for database write
- Database becomes bottleneck
- If database is slow, everything is slow
- Frontend gets stale data

### Good Approach (Kafka as Message Broker)

```
Generator → Kafka → Multiple Consumers
                   ↓
                   Database (writes data)
                   ↓
                   Aggregator (calculates OHLC)
                   ↓
                   WebSocket (sends to browser)
```

**Benefits:**
- Generator writes and continues (doesn't wait)
- Consumers work independently at their own pace
- Services are **decoupled** - can add/remove consumers without affecting others
- Kafka stores messages temporarily (buffer)

---

## Core Kafka Concepts

### 1. Topic
- A **category** or **stream** of messages
- Like a database table, but for messages
- Example: "market-data" topic contains all price updates

### 2. Producer
- Writes messages to a topic
- In our case: Market Data Generator
- Fire-and-forget or wait-for-acknowledgment

### 3. Consumer
- Reads messages from a topic
- Can have multiple consumers reading the same topic
- Each consumer tracks its own position (offset)
- Example: Database service, Aggregator service

### 4. Partition
- A topic is divided into **partitions** for parallel processing
- Each partition is an ordered sequence of messages
- Messages with same key go to same partition
- Example: "BTC" messages always go to partition 3

```
Topic: market-data (10 partitions)

Partition 0: [DOGE, SHIB, ...]
Partition 1: [XRP, ADA, ...]
Partition 2: [BTC, ETH, ...]     <- All BTC messages here
Partition 3: [SOL, AVAX, ...]
...
```

### 5. Consumer Group
- Multiple consumer instances working together
- Each partition is consumed by ONE instance in the group
- Enables parallel processing

```
Consumer Group "database-writers" (3 instances)

Instance 1 → reads Partitions 0, 1, 2, 3
Instance 2 → reads Partitions 4, 5, 6
Instance 3 → reads Partitions 7, 8, 9
```

---

## Why Kafka Scales

### Without Kafka
```
1,000 msg/sec → Single Database Writer → Database
                       ↓
                   Bottleneck!
```

### With Kafka + Partitions
```
1,000 msg/sec → Kafka (10 partitions) → 10 Database Writers
                                         (100 msg/sec each)
                                              ↓
                                         No Bottleneck!
```

---

## Message Flow Example

### 1. Producer Sends Message
```java
Tick tick = new Tick("BTC", 50000.0, Instant.now());
kafkaTemplate.send("market-data", "BTC", tick);
//                  ↑ topic       ↑ key  ↑ value
```

### 2. Kafka Stores Message
- Determines partition based on key ("BTC")
- Appends message to partition log
- Returns acknowledgment to producer

### 3. Consumer Reads Message
```java
@KafkaListener(topics = "market-data", groupId = "database-group")
public void consume(Tick tick) {
    repository.save(tick);
}
```

---

## Kafka in Our Project

### Topics We'll Create

| Topic | Purpose | Messages/sec | Retention |
|-------|---------|--------------|-----------|
| `market-data` | Raw price ticks | 1,000 | 5 minutes |
| `candles-1m` | 1-minute OHLC candles | ~17 | 1 day |
| `candles-5m` | 5-minute OHLC candles | ~3 | 7 days |
| `strategy-signals` | Trading signals | 10-50 | 1 day |

### Why These Retention Times?
- **Raw ticks (5 min)**: High volume, only need recent data for real-time
- **1m candles (1 day)**: Medium volume, for intraday analysis
- **5m candles (7 days)**: Low volume, for weekly analysis
- Long-term data goes to **QuestDB** (queried via SQL, not Kafka)

---

## Key Takeaways

1. **Kafka is a message buffer** between services
2. **Producers** write messages, **Consumers** read messages
3. **Topics** organize messages by category
4. **Partitions** enable parallel processing
5. **Consumer Groups** distribute work across multiple instances
6. Services are **decoupled** - can change one without affecting others

---

## Next: Understanding Time-Series Databases

See: `02-time-series-databases.md`
