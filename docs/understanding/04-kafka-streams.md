# Understanding Kafka Streams

## What is Kafka Streams?

A **Java library** for processing data in Kafka topics using **stream processing** patterns.

Not a separate service - it runs inside your Spring Boot application.

---

## The Problem It Solves

### Scenario: Calculate 1-Minute OHLC Candles

**Without Kafka Streams (Manual Approach):**

```java
// Store prices in memory
Map<String, List<Tick>> buffer = new ConcurrentHashMap<>();

@KafkaListener(topics = "market-data")
public void consume(Tick tick) {
    // Add to buffer
    buffer.computeIfAbsent(tick.getSymbol(), k -> new ArrayList<>()).add(tick);
}

@Scheduled(fixedRate = 60000) // Every minute
public void calculateCandles() {
    for (Map.Entry<String, List<Tick>> entry : buffer.entrySet()) {
        String symbol = entry.getKey();
        List<Tick> ticks = entry.getValue();
        
        OHLCCandle candle = new OHLCCandle();
        candle.setSymbol(symbol);
        candle.setOpen(ticks.get(0).getPrice());
        candle.setHigh(ticks.stream().mapToDouble(Tick::getPrice).max().orElse(0));
        candle.setLow(ticks.stream().mapToDouble(Tick::getPrice).min().orElse(0));
        candle.setClose(ticks.get(ticks.size() - 1).getPrice());
        
        // Save to database
        candleRepository.save(candle);
    }
    
    // Clear buffer
    buffer.clear();
}
```

**Problems:**
1. **Data loss on crash** - In-memory buffer is lost
2. **No distributed processing** - Can't run multiple instances (each has different buffer)
3. **Late-arriving data** - What if a tick arrives after the minute ended?
4. **Time synchronization** - Scheduled job might run at 14:00:03, not 14:00:00
5. **Memory management** - Buffer grows unbounded if processing is slow

---

## With Kafka Streams

```java
@Configuration
@EnableKafkaStreams
public class StreamConfig {
    
    @Bean
    public KStream<String, Tick> processStream(StreamsBuilder builder) {
        
        KStream<String, Tick> source = builder.stream("market-data");
        
        KTable<Windowed<String>, OHLCCandle> candles = source
            .groupByKey()
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
            .aggregate(
                OHLCCandle::new,                    // Initial value
                (key, tick, candle) -> {            // Aggregator function
                    if (candle.getOpen() == 0) {
                        candle.setOpen(tick.getPrice());
                    }
                    candle.updateHigh(tick.getPrice());
                    candle.updateLow(tick.getPrice());
                    candle.setClose(tick.getPrice());
                    return candle;
                },
                Materialized.with(Serdes.String(), candleSerde)
            );
        
        candles.toStream()
            .map((key, candle) -> KeyValue.pair(key.key(), candle))
            .to("candles-1m");
        
        return source;
    }
}
```

**Benefits:**
1. **State persisted** - Stored in RocksDB (local disk)
2. **Fault tolerance** - State backed up to Kafka changelog topic
3. **Distributed** - Multiple instances process different partitions
4. **Event-time processing** - Uses message timestamp, not system time
5. **Exactly-once semantics** - No duplicate candles

---

## Core Concepts

### 1. KStream (Stream of Records)

**Unbounded sequence of key-value pairs.**

```java
KStream<String, Tick> stream = builder.stream("market-data");

// Every message is processed individually
stream.foreach((key, tick) -> {
    System.out.println("Received: " + key + " -> " + tick);
});
```

Think of it as: **forEach** over infinite data

**Example messages:**
```
("BTC", Tick{price: 50000, time: 14:00:00})
("ETH", Tick{price: 3000, time: 14:00:00})
("BTC", Tick{price: 50001, time: 14:00:01})
```

### 2. KTable (Changelog Stream)

**A table** where each message **updates** the value for a key.

```java
KTable<String, LatestPrice> table = builder.table("latest-prices");

// Only latest value per key is retained
```

**Example:**
```
Message 1: ("BTC", 50000) → table["BTC"] = 50000
Message 2: ("BTC", 50001) → table["BTC"] = 50001 (overwrites 50000)
Message 3: ("ETH", 3000)  → table["ETH"] = 3000
```

Current state:
```
{
  "BTC": 50001,
  "ETH": 3000
}
```

### 3. Windowing

**Group messages into time buckets.**

#### Tumbling Window (Fixed Size, No Overlap)
```java
TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1))

14:00:00 - 14:01:00  → Window 1
14:01:00 - 14:02:00  → Window 2
14:02:00 - 14:03:00  → Window 3
```

#### Hopping Window (Fixed Size, With Overlap)
```java
TimeWindows.ofSizeAndGrace(
    Duration.ofMinutes(5),    // Window size
    Duration.ofMinutes(1)     // Advance by
)

14:00 - 14:05  → Window 1
14:01 - 14:06  → Window 2
14:02 - 14:07  → Window 3
```

Used for moving averages.

#### Session Window (Dynamic Size, Based on Inactivity)
```java
SessionWindows.ofInactivityGapWithNoGrace(Duration.ofMinutes(5))

User activity:
14:00, 14:01, 14:02  → Session 1
[5-minute gap]
14:10, 14:11         → Session 2
```

Used for user sessions, trading sessions.

---

## State Management

### Local State Store (RocksDB)

Kafka Streams uses **RocksDB** (embedded key-value store) to maintain state locally.

```
Your Application
├── Kafka Streams Library
├── RocksDB (on disk)
│   └── /tmp/kafka-streams/
│       └── aggregator-app/
│           └── 0_0/  (partition state)
│               ├── window-1.sst
│               ├── window-2.sst
│               └── ...
```

**Why Local?**
- Fast reads/writes (local disk, not network)
- Survives application restarts
- Automatically manages compaction

### Changelog Topic (Backup)

Every state change is also written to a **changelog topic** in Kafka.

```
State Store: {BTC: {open: 50000, high: 50100, ...}}
                    ↓
Changelog Topic: [
  {key: "BTC-window1", value: {open: 50000}},
  {key: "BTC-window1", value: {open: 50000, high: 50100}},
  ...
]
```

**Why Changelog?**
- If RocksDB gets corrupted → restore from changelog
- If application moves to different instance → rebuild state from changelog

**Fault Tolerance:**
1. Application crashes
2. New instance starts up
3. Reads changelog topic from beginning
4. Rebuilds RocksDB state
5. Resumes processing

---

## Distributed Processing

### Single Instance

```
Kafka Topic: market-data (10 partitions)
    ↓
Kafka Streams Instance 1
    - Processes all 10 partitions
    - State for all partitions in RocksDB
```

### Multiple Instances (Automatic Load Balancing)

```
Kafka Topic: market-data (10 partitions)
    ↓
Kafka Streams Instance 1
    - Processes partitions 0, 1, 2, 3, 4
    - State for partitions 0-4 in RocksDB
    
Kafka Streams Instance 2
    - Processes partitions 5, 6, 7, 8, 9
    - State for partitions 5-9 in RocksDB
```

**Key Point:** Each partition is processed by **exactly one** instance at a time.

**Automatic Rebalancing:**
- Instance crashes → partitions reassigned to remaining instances
- New instance added → partitions redistributed

---

## Exactly-Once Semantics

### The Problem: Duplicates

**Without exactly-once:**
1. Process message → Update state → Send output
2. Crash before committing offset
3. Restart → Reprocess same message → Duplicate output

### Kafka Streams Solution

```yaml
spring:
  kafka:
    streams:
      properties:
        processing.guarantee: exactly_once_v2
```

**How it works:**
1. Read message + Update state + Send output → **Single atomic transaction**
2. If any step fails → entire transaction rolls back
3. No duplicates, no partial processing

**Trade-off:** Slightly slower (5-10%) but guarantees correctness

---

## OHLC Aggregation Example (Detailed)

### Input Stream (Kafka Topic: market-data)
```
Partition 0:
  14:00:00 | BTC | 50000.00
  14:00:01 | BTC | 50001.00
  14:00:02 | BTC | 50000.50
  ...
  14:00:59 | BTC | 50050.00
  14:01:00 | BTC | 50055.00  (new window)
```

### Kafka Streams Processing

```java
source
  .groupByKey()                    // Group by symbol (BTC, ETH, ...)
  .windowedBy(                     // Create 1-minute windows
      TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1))
  )
  .aggregate(
      () -> new OHLCCandle(),      // Initial: empty candle
      (key, tick, candle) -> {
          // First tick in window
          if (candle.getOpen() == 0) {
              candle.setOpen(tick.getPrice());
              candle.setHigh(tick.getPrice());
              candle.setLow(tick.getPrice());
          }
          
          // Update high/low
          candle.setHigh(Math.max(candle.getHigh(), tick.getPrice()));
          candle.setLow(Math.min(candle.getLow(), tick.getPrice()));
          
          // Always update close (latest price)
          candle.setClose(tick.getPrice());
          
          // Accumulate volume
          candle.setVolume(candle.getVolume() + tick.getVolume());
          
          return candle;
      }
  )
```

### State Evolution (Window: 14:00:00 - 14:01:00)

```
Tick 1 (14:00:00, $50000):
  candle = {open: 50000, high: 50000, low: 50000, close: 50000, volume: 100}

Tick 2 (14:00:01, $50001):
  candle = {open: 50000, high: 50001, low: 50000, close: 50001, volume: 220}

Tick 3 (14:00:02, $49999):
  candle = {open: 50000, high: 50001, low: 49999, close: 49999, volume: 340}

...

Tick 60 (14:00:59, $50050):
  candle = {open: 50000, high: 50100, low: 49999, close: 50050, volume: 15000}
```

### Output (Kafka Topic: candles-1m)
```
Window ends → Emit final candle:
{
  symbol: "BTC",
  open: 50000.00,
  high: 50100.00,
  low: 49999.00,
  close: 50050.00,
  volume: 15000,
  timestamp: "2024-01-01T14:00:00Z",
  window: "14:00:00-14:01:00"
}
```

---

## Key Takeaways

1. **Kafka Streams = library** for stream processing (not a separate service)
2. **KStream = sequence of events**, KTable = current state
3. **Windowing** groups events by time
4. **State stored in RocksDB** (local) + **changelog topic** (backup)
5. **Distributed processing** - multiple instances process different partitions
6. **Exactly-once semantics** - no duplicates
7. **Fault tolerance** - state can be restored after crashes

---

## Next: Understanding WebSocket

See: `05-websocket.md`
