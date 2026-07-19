# Task 5: Extending Database Consumer - Complete Guide

**Objective:** Extend the existing database-consumer with batch processing for ticks and candles, plus real-time processing for signals.

**What You'll Build:**
- **Modify TickConsumer**: Convert from individual to batch processing (600 msg/min → 44x speedup)
- **Create CandleConsumer**: Batch processing for aggregated candles (10 msg/min)
- **Create SignalConsumer**: Real-time individual processing (1-5 msg/min)
- Two new QuestDB tables (candles_1m, signals)
- Three independent consumer groups with separate offset tracking

**Why Batch Processing?**
- Ticks: 600/min (highest volume) → Individual = 12 sec/min overhead, Batch = 0.27 sec/min (44x faster)
- Candles: 10/min (medium volume) → Future-proof for scaling + backlog handling
- Signals: 1-5/min (lowest volume) → Real-time alerts need individual processing

**Required Reading:** `/docs/phase-2/concepts/05-batch-vs-individual-database-writes.md` (explains batch processing from first principles)

**Estimated Time:** 1.5 hours

---

## Table of Contents
1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Step 1: Create Model Classes](#step-1-create-model-classes)
4. [Step 2: Update Kafka Configuration](#step-2-update-kafka-configuration)
5. [Step 3: Convert TickConsumer to Batch](#step-3-convert-tickconsumer-to-batch)
6. [Step 4: Create Candle Consumer](#step-4-create-candle-consumer)
7. [Step 5: Create Signal Consumer](#step-5-create-signal-consumer)
8. [Step 6: Create QuestDB Tables](#step-6-create-questdb-tables)
9. [Step 7: Testing](#step-7-testing)
10. [Step 8: Monitoring](#step-8-monitoring)
11. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

### Current State (Before Task 5)
```
database-consumer (existing)
  └─ TickConsumer
       ├─ Kafka Topic: market-data (600 msg/min)
       ├─ Consumer Group: questdb-consumer-group
       ├─ Processing: ❌ Individual (inefficient)
       ├─ Overhead: ~12 seconds/min wasted on database calls
       └─ Output: QuestDB ticks table
```

### Target State (After Task 5)
```
database-consumer (extended)
  ├─ TickConsumer (MODIFIED - convert to batch)
  │    ├─ Topic: market-data (600 msg/min - HIGHEST VOLUME)
  │    ├─ Group: questdb-consumer-group
  │    ├─ Processing: ✅ Batch (100-500 records)
  │    ├─ Overhead: ~0.27 seconds/min (44x improvement)
  │    └─ Output: ticks table
  │
  ├─ CandleConsumer (NEW - batch)
  │    ├─ Topic: candles-1m (10 msg/min - MEDIUM VOLUME)
  │    ├─ Group: questdb-candles-consumer-group
  │    ├─ Processing: ✅ Batch (10-500 records)
  │    ├─ Reason: Future scaling + backlog handling
  │    └─ Output: candles_1m table (NEW)
  │
  └─ SignalConsumer (NEW - individual)
       ├─ Topic: trading-signals (1-5 msg/min - LOWEST VOLUME)
       ├─ Group: questdb-signals-consumer-group
       ├─ Processing: Individual (real-time alerts)
       ├─ Reason: Low volume + real-time requirement
       └─ Output: signals table (NEW)
```

### Volume Hierarchy (Why We Batch What We Batch)

```
Data Flow:

Raw Ticks (10/sec = 600/min)           ← HIGHEST VOLUME
    ↓
    ↓ Aggregation (60 ticks → 1 candle)
    ↓
Candles (10/min)                        ← MEDIUM VOLUME (60x less than ticks)
    ↓
    ↓ Analysis (rare signals)
    ↓
Signals (1-5/min)                       ← LOWEST VOLUME (rare events)
```

**Critical Understanding:**
- Ticks MUST have higher volume than candles (candles are made FROM ticks)
- Batch processing gives most benefit to highest-volume data
- Therefore: Batch ticks (600/min) and candles (10/min), individual for signals (1-5/min)

### Key Design Decisions

**Why Separate Consumer Groups?**
- Independent offset tracking (each consumer reads at its own pace)
- Isolated failure handling (one consumer fails ≠ all fail)
- Different replay strategies (candles vs signals vs ticks)

**Why Batch Processing for Candles?**
- High volume: 10 candles/min × 10 symbols = 100 inserts/min (at minimum)
- With 100 symbols: 1,000 inserts/min
- Batch writes reduce database overhead by 10-100x

**Why Individual Processing for Signals?**
- Low volume: ~1-5 signals/min across all symbols
- Real-time requirement: Traders need immediate signal visibility
- Individual writes add < 10ms latency

---

## Prerequisites

### 1. Verify Existing Service
```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer
mvn clean compile
```

Expected: Successful compilation (existing TickConsumer working)

### 2. Verify Docker Services
```bash
docker ps
```

Expected: Kafka, Zookeeper, QuestDB, Kafka UI running

### 3. Verify Existing Ticks Table
```bash
# Open QuestDB Console
open http://localhost:9001

# Run query
SELECT count(*) FROM ticks;
```

Expected: Non-zero count (existing ticks from previous tasks)

### 4. Verify Kafka Topics
```bash
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

Expected:
```
candles-1m          (from aggregator service)
market-data         (from data-generator)
trading-signals     (from strategy-engine - may not exist yet)
```

---

## Step 1: Create Model Classes

### 1.1 Create Candle Model

Create `src/main/java/com/quantstream/consumer/model/Candle.java`:

```java
package com.quantstream.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * Represents a 1-minute OHLC candle.
 * 
 * Received from Kafka topic: candles-1m (produced by aggregator service)
 * Stored in QuestDB table: candles_1m
 * 
 * Fields:
 * - symbol: Stock/crypto symbol (e.g., AAPL, BTC)
 * - open: First tick price in the minute
 * - high: Highest tick price in the minute
 * - low: Lowest tick price in the minute
 * - close: Last tick price in the minute
 * - volume: Sum of all tick volumes in the minute
 * - timestamp: Window start time (e.g., 14:25:00 for 14:25:00-14:25:59 window)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Candle {
    
    private String symbol;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    private Instant timestamp;
}
```

**Why `@JsonIgnoreProperties(ignoreUnknown = true)`?**
- Protects against schema evolution
- If aggregator adds new fields, this consumer won't break
- Only deserializes fields we care about

### 1.2 Create Signal Model

Create `src/main/java/com/quantstream/consumer/model/Signal.java`:

```java
package com.quantstream.consumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

/**
 * Represents a trading signal generated by the strategy engine.
 * 
 * Received from Kafka topic: trading-signals (produced by strategy-engine)
 * Stored in QuestDB table: signals
 * 
 * Fields:
 * - symbol: Stock/crypto symbol being signaled
 * - action: Trading action (BUY, SELL, HOLD)
 * - strategyName: Which strategy generated this signal (e.g., MovingAverageCrossover)
 * - confidence: Signal strength (0.0 to 1.0, where 1.0 = highest confidence)
 * - timestamp: When the signal was generated
 * 
 * Example:
 * {
 *   "symbol": "AAPL",
 *   "action": "BUY",
 *   "strategyName": "MovingAverageCrossover",
 *   "confidence": 0.85,
 *   "timestamp": "2026-07-19T01:45:00Z"
 * }
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class Signal {
    
    private String symbol;
    private String action;           // BUY, SELL, HOLD
    private String strategyName;     // Which strategy generated this
    private double confidence;       // 0.0 to 1.0
    private Instant timestamp;
}
```

**Signal Design Notes:**
- `action` is String (not enum) to support strategy-specific actions in future
- `confidence` allows filtering: `WHERE confidence > 0.7` (high-confidence only)
- `strategyName` enables per-strategy performance analysis

---

## Step 2: Update Kafka Configuration

We need to create separate consumer factories for each message type.

### 2.1 Extend KafkaConsumerConfig

Edit `src/main/java/com/quantstream/consumer/config/KafkaConsumerConfig.java`:

```java
package com.quantstream.consumer.config;

import com.quantstream.consumer.model.Candle;
import com.quantstream.consumer.model.Signal;
import com.quantstream.consumer.model.Tick;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Consumer configuration for three types of messages:
 * 1. Tick - raw market data
 * 2. Candle - aggregated OHLC data
 * 3. Signal - trading signals
 * 
 * Each type has:
 * - Its own ConsumerFactory (for deserialization)
 * - Its own KafkaListenerContainerFactory (for threading/batching)
 * - Its own consumer group ID (for independent offset tracking)
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // ============================================================
    // TICK CONSUMER (existing, unchanged)
    // ============================================================

    @Bean
    public ConsumerFactory<String, Tick> tickConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "questdb-consumer-group");
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.consumer.model");
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        return new DefaultKafkaConsumerFactory<>(
            configProps,
            new StringDeserializer(),
            new JsonDeserializer<>(Tick.class, false)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Tick> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Tick> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tickConsumerFactory());
        factory.setConcurrency(3);
        factory.getContainerProperties().setAckMode(
            org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        );
        return factory;
    }

    // ============================================================
    // CANDLE CONSUMER (NEW - batch processing)
    // ============================================================

    @Bean
    public ConsumerFactory<String, Candle> candleConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // SEPARATE consumer group (independent offset tracking)
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "questdb-candles-consumer-group");
        
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.consumer.model");
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        
        // BATCH PROCESSING: Fetch up to 500 candles at once
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        return new DefaultKafkaConsumerFactory<>(
            configProps,
            new StringDeserializer(),
            new JsonDeserializer<>(Candle.class, false)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Candle> candleKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Candle> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(candleConsumerFactory());
        
        // BATCH MODE: Deliver messages in batches (not one-by-one)
        factory.setBatchListener(true);
        
        // 3 concurrent threads (matches Kafka topic partitions)
        factory.setConcurrency(3);
        
        // Manual acknowledgment (commit after successful batch insert)
        factory.getContainerProperties().setAckMode(
            org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        );
        
        return factory;
    }

    // ============================================================
    // SIGNAL CONSUMER (NEW - real-time processing)
    // ============================================================

    @Bean
    public ConsumerFactory<String, Signal> signalConsumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // SEPARATE consumer group (independent offset tracking)
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, "questdb-signals-consumer-group");
        
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.consumer.model");
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        
        // INDIVIDUAL PROCESSING: Small batch size for low latency
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        
        return new DefaultKafkaConsumerFactory<>(
            configProps,
            new StringDeserializer(),
            new JsonDeserializer<>(Signal.class, false)
        );
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Signal> signalKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Signal> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(signalConsumerFactory());
        
        // SINGLE MESSAGE MODE (not batch)
        factory.setBatchListener(false);
        
        // Single thread (signals are low-volume)
        factory.setConcurrency(1);
        
        // Manual acknowledgment
        factory.getContainerProperties().setAckMode(
            org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
        );
        
        return factory;
    }
}
```

**Configuration Summary:**

| Consumer | Group ID | Batch Mode | Concurrency | Max Poll |
|----------|----------|------------|-------------|----------|
| Tick | questdb-consumer-group | No | 3 | 500 |
| Candle | questdb-candles-consumer-group | **Yes** | 3 | 500 |
| Signal | questdb-signals-consumer-group | No | 1 | 10 |

**Why Different Settings?**
- **Candles**: High volume → batch processing (500 at a time)
- **Signals**: Low volume → individual processing (real-time)
- **Concurrency = 3** for candles matches Kafka topic partitions (load balancing)

---

## Step 3: Convert TickConsumer to Batch Processing

**Why This Step?**  
The existing TickConsumer processes 600 messages/min individually, wasting ~12 seconds/min on database overhead. Converting to batch processing reduces this to ~0.27 seconds (44x speedup).

**Required Reading:** See `/docs/phase-2/concepts/05-batch-vs-individual-database-writes.md` for detailed explanation of why batch processing is faster.

### 3.1 Update KafkaConsumerConfig for Batch Mode

Edit `src/main/java/com/quantstream/consumer/config/KafkaConsumerConfig.java`:

**Find the `kafkaListenerContainerFactory()` method and add batch mode:**

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, Tick> kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, Tick> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    factory.setConsumerFactory(tickConsumerFactory());
    
    // ADD THIS LINE: Enable batch mode
    factory.setBatchListener(true);
    
    factory.setConcurrency(3);
    factory.getContainerProperties().setAckMode(
        org.springframework.kafka.listener.ContainerProperties.AckMode.MANUAL
    );
    return factory;
}
```

**What this does:**
- `setBatchListener(true)` tells Spring Kafka to deliver messages as `List<Tick>` instead of individual `Tick`
- Kafka still fetches 500 records (MAX_POLL_RECORDS), but now delivers all 500 at once

### 3.2 Convert TickConsumer to Batch

Edit `src/main/java/com/quantstream/consumer/service/TickConsumer.java`:

**Replace the entire `consumeTick` method:**

**OLD (Individual):**
```java
@KafkaListener(
    topics = "market-data",
    groupId = "${spring.kafka.consumer.group-id}",
    containerFactory = "kafkaListenerContainerFactory"
)
public void consumeTick(
        @Payload Tick tick,
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) long offset,
        @Header(KafkaHeaders.RECEIVED_TIMESTAMP) long timestamp,
        Acknowledgment acknowledgment) {
    
    messagesReceived++;
    log.debug("Received tick from partition={}, offset={}: {} @ ${} (volume: {})",
             partition, offset, tick.getSymbol(), tick.getPrice(), tick.getVolume());
    
    try {
        validateTick(tick);
        persistTick(tick);
        acknowledgment.acknowledge();
        messagesProcessed++;
        
    } catch (Exception e) {
        messagesFailed++;
        log.error("Failed to process tick: {}", e.getMessage(), e);
        acknowledgment.acknowledge();
    }
}
```

**NEW (Batch):**
```java
@KafkaListener(
    topics = "market-data",
    groupId = "${spring.kafka.consumer.group-id}",
    containerFactory = "kafkaListenerContainerFactory"
)
public void consumeTicks(
        @Payload List<Tick> ticks,  // Changed: List<Tick> instead of Tick
        @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
        @Header(KafkaHeaders.OFFSET) List<Long> offsets,  // Changed: List<Long> instead of long
        Acknowledgment acknowledgment) {
    
    messagesReceived += ticks.size();
    
    log.info("Received batch of {} ticks from partition {} (offsets: {} to {})",
            ticks.size(), partition, offsets.get(0), offsets.get(offsets.size() - 1));
    
    try {
        // Validate all ticks in batch
        for (Tick tick : ticks) {
            validateTick(tick);
        }
        
        // Batch insert all ticks at once
        int inserted = persistTicksBatch(ticks);
        
        // Acknowledge batch (commits offsets)
        acknowledgment.acknowledge();
        
        messagesProcessed += inserted;
        
        log.info("Successfully persisted batch of {} ticks (total: {})",
                inserted, messagesProcessed);
        
    } catch (Exception e) {
        messagesFailed += ticks.size();
        
        log.error("Failed to process batch of {} ticks: {}",
                ticks.size(), e.getMessage(), e);
        
        // DO NOT acknowledge - will retry
        log.warn("Batch NOT acknowledged - will retry on next poll");
    }
}
```

**Key Changes:**
1. Method name: `consumeTick` → `consumeTicks`
2. Parameter: `Tick tick` → `List<Tick> ticks`
3. Offsets: `long offset` → `List<Long> offsets`
4. Validation: Loop through all ticks
5. Persistence: `persistTick()` → `persistTicksBatch()`
6. Logging: Batch-level instead of message-level

### 3.3 Add Batch Persistence Method

Add this new method to `TickConsumer.java` (after the existing `persistTick` method):

```java
/**
 * Batch persist ticks using JdbcTemplate.batchUpdate().
 * 
 * Performance:
 * - Individual: 600 ticks × 20ms = 12,000ms (12 seconds)
 * - Batch: 6 batches × 45ms = 270ms (0.27 seconds)
 * - Speedup: 44x faster
 * 
 * @param ticks List of ticks to insert
 * @return Number of rows inserted
 */
private int persistTicksBatch(List<Tick> ticks) {
    try {
        String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
        
        int[] updateCounts = jdbcTemplate.batchUpdate(sql, ticks, ticks.size(),
            (ps, tick) -> {
                ps.setString(1, tick.getSymbol());
                ps.setDouble(2, tick.getPrice());
                ps.setDouble(3, tick.getVolume());
                ps.setTimestamp(4, java.sql.Timestamp.from(tick.getTimestamp()));
            });
        
        // Sum successful inserts
        int totalInserted = 0;
        for (int count : updateCounts) {
            if (count > 0) {
                totalInserted += count;
            }
        }
        
        log.debug("Batch inserted {} ticks into QuestDB", totalInserted);
        return totalInserted;
        
    } catch (Exception e) {
        log.error("Batch insert failed: {}", e.getMessage(), e);
        throw new RuntimeException("Failed to persist ticks batch to database", e);
    }
}
```

**How batchUpdate() Works:**

```java
jdbcTemplate.batchUpdate(
    sql,                    // SQL template with ? placeholders
    ticks,                  // List of data objects
    ticks.size(),           // Batch size
    (ps, tick) -> {         // Lambda: how to set parameters for each object
        ps.setString(1, tick.getSymbol());
        ps.setDouble(2, tick.getPrice());
        // ...
    }
);
```

This sends ALL ticks in ONE database round-trip instead of 600 separate calls.

### 3.4 Remove or Keep Old persistTick() Method

**Option A: Remove it** (cleaner, we're not using individual inserts anymore)

Delete the `persistTick(Tick tick)` method entirely.

**Option B: Keep it** (safer, allows fallback if batch fails)

Keep the method but add a comment:
```java
/**
 * Legacy individual insert - kept for compatibility.
 * Use persistTicksBatch() for better performance.
 */
private void persistTick(Tick tick) {
    // ... existing code ...
}
```

**Recommendation:** Keep it for now (safer during testing).

### 3.5 Verify Changes

After editing, verify the file compiles:

```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer
mvn clean compile
```

Expected: `BUILD SUCCESS`

### 3.6 What We Achieved

**Before (Individual):**
```
Per minute:
- Fetch: 600 ticks from Kafka
- Process: 600 individual INSERT statements
- Time: ~12 seconds on database overhead
- Efficiency: 20% (12 sec / 60 sec)
```

**After (Batch):**
```
Per minute:
- Fetch: 600 ticks from Kafka (same)
- Process: 6 batch INSERT statements (100 ticks each)
- Time: ~0.27 seconds on database overhead
- Efficiency: 0.45% (0.27 sec / 60 sec)
```

**Improvement: 44x faster, 99.5% less overhead**

---

## Step 4: Create Candle Consumer

Create `src/main/java/com/quantstream/consumer/service/CandleConsumer.java`:

```java
package com.quantstream.consumer.service;

import com.quantstream.consumer.model.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;

/**
 * Kafka consumer that receives OHLC candles and persists to QuestDB.
 * 
 * Design Philosophy:
 * - BATCH processing (despite low current volume, designed for scale)
 * - Batch inserts using JdbcTemplate.batchUpdate()
 * - Manual offset commit after successful batch
 * - Fail-fast on database errors (don't acknowledge if insert fails)
 * 
 * Volume Analysis:
 * - Current: 10 symbols × 1 candle/min = 10 candles/min (LOW volume)
 * - Scaled: 1000 symbols × 1 candle/min = 1,000 candles/min (HIGH volume)
 * - Backlog: 4,975 candles accumulated (batch processing essential)
 * 
 * Performance:
 * - Can handle 1,000+ candles/second with batch writes
 * - Batch size: 500 candles (configurable via MAX_POLL_RECORDS)
 * - Latency: ~100ms per batch (vs ~50ms/candle for individual inserts)
 * 
 * Why Batch Despite Low Current Volume?
 * - Scalability: Code works unchanged from 10 to 10,000 symbols
 * - Backlog processing: Efficiently processes historical data on startup
 * - Efficiency: Even small batches (10 candles) are 10x faster than individual inserts
 * - Future-proof: No code changes needed when adding more symbols
 */
@Service
public class CandleConsumer {

    private static final Logger log = LoggerFactory.getLogger(CandleConsumer.class);

    private final JdbcTemplate jdbcTemplate;

    public CandleConsumer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Monitoring counters
    private long batchesReceived = 0;
    private long candlesReceived = 0;
    private long candlesProcessed = 0;
    private long candlesFailed = 0;

    /**
     * Consumes batches of candles from Kafka topic.
     * 
     * @param candles List of candles (up to 500)
     * @param partition Which Kafka partition this batch came from
     * @param offsets List of Kafka offsets (one per candle)
     * @param acknowledgment Manual acknowledgment handle
     */
    @KafkaListener(
        topics = "candles-1m",
        groupId = "questdb-candles-consumer-group",
        containerFactory = "candleKafkaListenerContainerFactory"
    )
    public void consumeCandles(
            @Payload List<Candle> candles,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) List<Long> offsets,
            Acknowledgment acknowledgment) {

        batchesReceived++;
        candlesReceived += candles.size();

        log.info("Received batch of {} candles from partition {} (offsets: {} to {})",
                candles.size(), partition, offsets.get(0), offsets.get(offsets.size() - 1));

        try {
            // Validate batch
            validateCandles(candles);

            // Batch insert into QuestDB
            int inserted = persistCandlesBatch(candles);

            // Acknowledge batch (commits offsets)
            acknowledgment.acknowledge();

            candlesProcessed += inserted;

            log.info("Successfully persisted batch of {} candles (total: {})",
                    inserted, candlesProcessed);

            // Log statistics every 10 batches
            if (batchesReceived % 10 == 0) {
                logStatistics();
            }

        } catch (Exception e) {
            candlesFailed += candles.size();

            log.error("Failed to process batch of {} candles: {}",
                    candles.size(), e.getMessage(), e);

            // DO NOT acknowledge - Kafka will re-deliver this batch
            // This prevents data loss but may cause duplicates
            // (Acceptable for candles - QuestDB can handle duplicate timestamps)
            
            log.warn("Batch NOT acknowledged - will retry on next poll");
        }
    }

    private void validateCandles(List<Candle> candles) {
        for (Candle candle : candles) {
            if (candle == null) {
                throw new IllegalArgumentException("Candle cannot be null");
            }

            if (candle.getSymbol() == null || candle.getSymbol().isBlank()) {
                throw new IllegalArgumentException("Symbol cannot be blank");
            }

            // OHLC validation: High >= Low, High >= Open/Close, Low <= Open/Close
            if (candle.getHigh() < candle.getLow()) {
                throw new IllegalArgumentException(
                    String.format("Invalid OHLC: high (%.2f) < low (%.2f) for %s",
                        candle.getHigh(), candle.getLow(), candle.getSymbol())
                );
            }

            if (candle.getHigh() < candle.getOpen() || candle.getHigh() < candle.getClose()) {
                throw new IllegalArgumentException(
                    String.format("Invalid OHLC: high (%.2f) < open (%.2f) or close (%.2f) for %s",
                        candle.getHigh(), candle.getOpen(), candle.getClose(), candle.getSymbol())
                );
            }

            if (candle.getLow() > candle.getOpen() || candle.getLow() > candle.getClose()) {
                throw new IllegalArgumentException(
                    String.format("Invalid OHLC: low (%.2f) > open (%.2f) or close (%.2f) for %s",
                        candle.getLow(), candle.getOpen(), candle.getClose(), candle.getSymbol())
                );
            }

            if (candle.getVolume() < 0) {
                throw new IllegalArgumentException("Volume cannot be negative: " + candle.getVolume());
            }

            if (candle.getTimestamp() == null) {
                throw new IllegalArgumentException("Timestamp cannot be null");
            }
        }
    }

    /**
     * Batch insert candles using JdbcTemplate.batchUpdate().
     * 
     * Why batchUpdate()?
     * - Single database round-trip for entire batch
     * - QuestDB optimizes bulk inserts (column-oriented storage)
     * - 100-500x faster than individual inserts
     * 
     * @return Number of rows inserted
     */
    private int persistCandlesBatch(List<Candle> candles) {
        String sql = "INSERT INTO candles_1m (symbol, open, high, low, close, volume, timestamp) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";

        try {
            int[] updateCounts = jdbcTemplate.batchUpdate(sql, candles, candles.size(),
                (ps, candle) -> {
                    ps.setString(1, candle.getSymbol());
                    ps.setDouble(2, candle.getOpen());
                    ps.setDouble(3, candle.getHigh());
                    ps.setDouble(4, candle.getLow());
                    ps.setDouble(5, candle.getClose());
                    ps.setDouble(6, candle.getVolume());
                    ps.setTimestamp(7, Timestamp.from(candle.getTimestamp()));
                });

            // Sum successful inserts
            int totalInserted = 0;
            for (int count : updateCounts) {
                if (count > 0) {
                    totalInserted += count;
                }
            }

            log.debug("Batch inserted {} candles into QuestDB", totalInserted);
            return totalInserted;

        } catch (Exception e) {
            log.error("Batch insert failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to persist candles batch to database", e);
        }
    }

    private void logStatistics() {
        log.info("===== Candle Consumer Statistics =====");
        log.info("Batches received:   {}", batchesReceived);
        log.info("Candles received:   {}", candlesReceived);
        log.info("Candles processed:  {}", candlesProcessed);
        log.info("Candles failed:     {}", candlesFailed);

        if (candlesReceived > 0) {
            double successRate = (candlesProcessed * 100.0) / candlesReceived;
            log.info("Success rate:       {:.2f}%", successRate);
        }

        if (batchesReceived > 0) {
            double avgBatchSize = candlesReceived * 1.0 / batchesReceived;
            log.info("Avg batch size:     {:.1f} candles", avgBatchSize);
        }

        log.info("======================================");
    }

    public String getStatistics() {
        return String.format(
            "Batches: %d, Candles: %d, Processed: %d, Failed: %d, Success Rate: %.2f%%",
            batchesReceived,
            candlesReceived,
            candlesProcessed,
            candlesFailed,
            candlesReceived > 0 ? (candlesProcessed * 100.0) / candlesReceived : 0.0
        );
    }
}
```

**Batch Processing Explained:**

```
Traditional (Individual):
  Candle 1 → INSERT → 50ms
  Candle 2 → INSERT → 50ms
  Candle 3 → INSERT → 50ms
  ...
  Total: 500 candles × 50ms = 25,000ms (25 seconds)

Batch Processing:
  [Candle 1, Candle 2, ..., Candle 500] → BATCH INSERT → 100ms
  Total: 100ms (250x faster!)
```

---

## Step 4: Create Signal Consumer

Create `src/main/java/com/quantstream/consumer/service/SignalConsumer.java`:

```java
package com.quantstream.consumer.service;

import com.quantstream.consumer.model.Signal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;

/**
 * Kafka consumer that receives trading signals and persists to QuestDB.
 * 
 * Design Philosophy:
 * - INDIVIDUAL processing (low volume: 1-5 signals/min)
 * - Real-time writes (traders need immediate visibility)
 * - Manual offset commit after successful insert
 * - Fail-fast on database errors
 * 
 * Performance:
 * - Latency: ~10ms per signal (vs ~100ms for batch of 500)
 * - Throughput: 100+ signals/second (more than enough)
 * 
 * Why NOT Batch Processing?
 * - Low volume: Batching would add latency (waiting for batch to fill)
 * - Real-time requirement: Traders want signals NOW, not "in the next batch"
 * - Simple logic: No need for batch validation/error handling complexity
 */
@Service
public class SignalConsumer {

    private static final Logger log = LoggerFactory.getLogger(SignalConsumer.class);

    private final JdbcTemplate jdbcTemplate;

    public SignalConsumer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // Monitoring counters
    private long signalsReceived = 0;
    private long signalsProcessed = 0;
    private long signalsFailed = 0;

    /**
     * Consumes individual signals from Kafka topic.
     */
    @KafkaListener(
        topics = "trading-signals",
        groupId = "questdb-signals-consumer-group",
        containerFactory = "signalKafkaListenerContainerFactory"
    )
    public void consumeSignal(
            @Payload Signal signal,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset,
            Acknowledgment acknowledgment) {

        signalsReceived++;

        log.info("Received signal from partition={}, offset={}: {} {} {} (confidence: {:.2f})",
                partition, offset,
                signal.getStrategyName(),
                signal.getAction(),
                signal.getSymbol(),
                signal.getConfidence());

        try {
            // Validate signal
            validateSignal(signal);

            // Insert into QuestDB (individual insert - real-time)
            persistSignal(signal);

            // Acknowledge message (commits offset)
            acknowledgment.acknowledge();

            signalsProcessed++;

            log.info("Successfully persisted signal: {} {} {} (total: {})",
                    signal.getStrategyName(),
                    signal.getAction(),
                    signal.getSymbol(),
                    signalsProcessed);

            // Log statistics every 100 signals
            if (signalsProcessed % 100 == 0) {
                logStatistics();
            }

        } catch (Exception e) {
            signalsFailed++;

            log.error("Failed to process signal: strategy={}, action={}, symbol={}, error={}",
                    signal.getStrategyName(),
                    signal.getAction(),
                    signal.getSymbol(),
                    e.getMessage(), e);

            // Acknowledge anyway to move forward (signals are transient)
            // If one signal fails, we don't want to block all future signals
            acknowledgment.acknowledge();

            log.warn("Signal acknowledged despite failure (fail-forward strategy)");
        }
    }

    private void validateSignal(Signal signal) {
        if (signal == null) {
            throw new IllegalArgumentException("Signal cannot be null");
        }

        if (signal.getSymbol() == null || signal.getSymbol().isBlank()) {
            throw new IllegalArgumentException("Symbol cannot be blank");
        }

        if (signal.getAction() == null || signal.getAction().isBlank()) {
            throw new IllegalArgumentException("Action cannot be blank");
        }

        // Validate action is one of: BUY, SELL, HOLD
        String action = signal.getAction().toUpperCase();
        if (!action.equals("BUY") && !action.equals("SELL") && !action.equals("HOLD")) {
            log.warn("Unexpected action: {} (expected BUY, SELL, or HOLD)", action);
        }

        if (signal.getStrategyName() == null || signal.getStrategyName().isBlank()) {
            throw new IllegalArgumentException("Strategy name cannot be blank");
        }

        // Validate confidence range
        if (signal.getConfidence() < 0.0 || signal.getConfidence() > 1.0) {
            throw new IllegalArgumentException(
                String.format("Confidence must be between 0.0 and 1.0, got: %.2f",
                    signal.getConfidence())
            );
        }

        if (signal.getTimestamp() == null) {
            throw new IllegalArgumentException("Timestamp cannot be null");
        }
    }

    /**
     * Persists signal to QuestDB using individual INSERT.
     * 
     * Why NOT batch?
     * - Low volume: Individual inserts are fast enough (~10ms)
     * - Real-time requirement: No batching delay
     */
    private void persistSignal(Signal signal) {
        try {
            String sql = "INSERT INTO signals (symbol, action, strategy_name, confidence, timestamp) " +
                         "VALUES (?, ?, ?, ?, ?)";

            jdbcTemplate.update(sql,
                signal.getSymbol(),
                signal.getAction(),
                signal.getStrategyName(),
                signal.getConfidence(),
                Timestamp.from(signal.getTimestamp())
            );

            log.debug("Inserted signal into QuestDB: {} {} {}",
                    signal.getStrategyName(), signal.getAction(), signal.getSymbol());

        } catch (Exception e) {
            log.error("Database insert failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to persist signal to database", e);
        }
    }

    private void logStatistics() {
        log.info("===== Signal Consumer Statistics =====");
        log.info("Signals received:   {}", signalsReceived);
        log.info("Signals processed:  {}", signalsProcessed);
        log.info("Signals failed:     {}", signalsFailed);

        if (signalsReceived > 0) {
            double successRate = (signalsProcessed * 100.0) / signalsReceived;
            log.info("Success rate:       {:.2f}%", successRate);
        }

        log.info("=====================================");
    }

    public String getStatistics() {
        return String.format(
            "Received: %d, Processed: %d, Failed: %d, Success Rate: %.2f%%",
            signalsReceived,
            signalsProcessed,
            signalsFailed,
            signalsReceived > 0 ? (signalsProcessed * 100.0) / signalsReceived : 0.0
        );
    }
}
```

**Individual vs Batch Processing:**

| Aspect | Individual (Signals) | Batch (Candles) |
|--------|---------------------|-----------------|
| Volume | 1-5/min | 100-1000/min |
| Latency | ~10ms | ~100ms (per batch) |
| Real-time | Yes | No (waits for batch) |
| Complexity | Low | Medium |
| Use Case | Alerts, notifications | Bulk data storage |

---

## Step 5: Create QuestDB Tables

### 5.1 Start Database Consumer (for connection)

```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer
mvn spring-boot:run
```

Wait for: "Started ConsumerApplication in X seconds"

### 5.2 Create Candles Table

Open QuestDB Console: http://localhost:9001

Run this SQL:

```sql
CREATE TABLE candles_1m (
    symbol SYMBOL,
    open DOUBLE,
    high DOUBLE,
    low DOUBLE,
    close DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp) PARTITION BY DAY;
```

**Table Design Explained:**

- `SYMBOL` type: Optimized for repeated string values (symbol names)
  - Stored as integers internally (memory efficient)
  - Perfect for `WHERE symbol='AAPL'` queries
  
- `TIMESTAMP(timestamp)`: Designated timestamp column
  - Enables time-series queries
  - Required for `LATEST BY` queries
  - Allows efficient time-range filtering
  
- `PARTITION BY DAY`: Data organized by day
  - Each day = separate file on disk
  - Query `WHERE timestamp > '2026-07-01'` only reads July files
  - Older partitions can be archived/deleted independently

### 5.3 Create Signals Table

```sql
CREATE TABLE signals (
    symbol SYMBOL,
    action SYMBOL,
    strategy_name SYMBOL,
    confidence DOUBLE,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp) PARTITION BY DAY;
```

**Why `action` and `strategy_name` are SYMBOL:**
- Limited set of values: BUY/SELL/HOLD, ~10 strategy names
- Frequently filtered: `WHERE action='BUY'` or `WHERE strategy_name='RSI'`
- SYMBOL type makes these queries 10-100x faster

### 5.4 Verify Tables

```sql
-- List all tables
SHOW TABLES;

-- Check candles_1m structure
SHOW COLUMNS FROM candles_1m;

-- Check signals structure
SHOW COLUMNS FROM signals;
```

Expected output:
```
Tables
------
candles_1m
signals
ticks
```

---

## Step 6: Testing

### 6.1 Compile and Start Service

```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer
mvn clean compile
mvn spring-boot:run
```

Expected logs:
```
Started ConsumerApplication in X seconds
Partition assignment: [candles-1m-0, candles-1m-1, candles-1m-2]
Partition assignment: [trading-signals-0]
Partition assignment: [market-data-0, market-data-1, market-data-2]
```

### 6.2 Verify Candle Consumption

**Check Kafka Consumer Groups:**
```bash
docker exec kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group questdb-candles-consumer-group \
    --describe
```

Expected:
```
GROUP                           TOPIC      PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
questdb-candles-consumer-group  candles-1m 0          1658            1658            0
questdb-candles-consumer-group  candles-1m 1          1658            1658            0
questdb-candles-consumer-group  candles-1m 2          1659            1659            0
```

**LAG = 0** means consumer caught up!

**Check QuestDB:**
```sql
-- Count candles
SELECT count(*) FROM candles_1m;

-- See recent candles
SELECT * FROM candles_1m
ORDER BY timestamp DESC
LIMIT 10;

-- Candles per symbol
SELECT symbol, count(*) as candle_count
FROM candles_1m
GROUP BY symbol
ORDER BY candle_count DESC;

-- Latest candle per symbol
SELECT * FROM candles_1m
LATEST BY symbol;
```

Expected: ~4,975 candles (from aggregator backlog)

### 6.3 Verify Signal Consumption

**Note:** Signals may not exist yet if strategy-engine hasn't run.

**Check Consumer Group:**
```bash
docker exec kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group questdb-signals-consumer-group \
    --describe
```

**Check QuestDB:**
```sql
-- Count signals
SELECT count(*) FROM signals;

-- Recent signals
SELECT * FROM signals
ORDER BY timestamp DESC
LIMIT 10;

-- Signals by strategy
SELECT strategy_name, action, count(*) as signal_count
FROM signals
GROUP BY strategy_name, action;
```

Expected: 0 signals (until strategy-engine runs)

### 6.4 End-to-End Flow Test

**Full Data Pipeline:**
```
data-generator → market-data topic
                      ↓
                 aggregator
                      ↓
                candles-1m topic
                      ↓
              database-consumer (CandleConsumer)
                      ↓
              QuestDB candles_1m table
```

**Verify Each Stage:**

1. **Data Generator Running?**
   ```bash
   ps aux | grep data-generator
   ```

2. **Aggregator Running?**
   ```bash
   ps aux | grep aggregator
   ```

3. **Candles in Kafka?**
   ```bash
   docker exec kafka kafka-console-consumer \
       --bootstrap-server localhost:9092 \
       --topic candles-1m \
       --max-messages 3
   ```

4. **Candles in Database?**
   ```sql
   SELECT count(*) FROM candles_1m;
   ```

All stages should show data flowing!

### 6.5 Performance Verification

**Consumer Lag Check:**
```bash
docker exec kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --list

# For each group:
docker exec kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --group <group-name> \
    --describe
```

**Success Criteria:**
- LAG = 0 for all partitions
- Or LAG < 100 (processing slightly behind, but catching up)

**Database-Consumer Logs:**
```bash
tail -f database-consumer/logs/*.log
```

Look for:
```
Successfully persisted batch of 500 candles
Candles processed: 5000
Success rate: 100.00%
```

---

## Step 7: Monitoring

### 7.1 Consumer Group Health

```bash
# Monitor all consumer groups
watch -n 5 'docker exec kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --all-groups --describe'
```

**What to Watch:**
- LAG increasing → Consumer too slow
- LAG = 0 → Consumer keeping up
- CURRENT-OFFSET not moving → Consumer crashed

### 7.2 Database Growth

```sql
-- Candles per day
SELECT
    timestamp::date as day,
    count(*) as candles
FROM candles_1m
GROUP BY day
ORDER BY day DESC;

-- Signals per hour
SELECT
    to_hour(timestamp) as hour,
    count(*) as signals
FROM signals
GROUP BY hour
ORDER BY hour DESC;
```

### 7.3 Application Logs

```bash
# Follow all database-consumer logs
tail -f /Users/mhiteshkumar/QuantStream/database-consumer/logs/*.log

# Or if running via mvn:
# Logs appear in terminal
```

**Key Metrics to Monitor:**
```
Candle Consumer Statistics:
  Batches received: 10
  Candles processed: 5000
  Success rate: 100.00%
  Avg batch size: 500.0 candles

Signal Consumer Statistics:
  Signals processed: 150
  Success rate: 100.00%
```

### 7.4 Kafka UI

Open: http://localhost:8080

Navigate to:
- **Consumer Groups** → See all three groups
- **Topics** → See message counts
- **Lag** → Monitor consumer health

---

## Troubleshooting

### Issue 1: "Table does not exist"

**Error:**
```
Failed to persist candles batch to database
org.postgresql.util.PSQLException: ERROR: table does not exist [table=candles_1m]
```

**Solution:**
```sql
-- Check if table exists
SHOW TABLES;

-- If missing, create it
CREATE TABLE candles_1m (...);  -- See Step 5.2
```

### Issue 2: Consumer Not Reading Messages

**Symptoms:**
- LAG not decreasing
- No logs showing message processing

**Debug:**
```bash
# Check if topic exists
docker exec kafka kafka-topics \
    --bootstrap-server localhost:9092 \
    --describe --topic candles-1m

# Check if messages in topic
docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 \
    --topic candles-1m --time -1
```

**Solution:**
- Ensure `candles-1m` topic exists
- Verify aggregator is producing to this topic
- Check consumer group ID matches configuration

### Issue 3: "Trusted Packages" Error

**Error:**
```
The class 'com.quantstream.consumer.model.Candle' is not in the trusted packages
```

**Solution:**
Edit `KafkaConsumerConfig.java`:
```java
configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.consumer.model");
```

Or use wildcard:
```java
configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "*");  // Trust all packages
```

### Issue 4: High Consumer Lag

**Symptoms:**
- LAG > 1000 and increasing
- Database writes slow

**Solutions:**

1. **Increase Batch Size:**
   ```java
   configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 1000);  // Was 500
   ```

2. **Increase Concurrency:**
   ```java
   factory.setConcurrency(5);  // Was 3
   ```

3. **Optimize Database:**
   ```sql
   -- Check QuestDB write performance
   SELECT count(*) FROM candles_1m;  -- Should be fast (< 100ms)
   ```

### Issue 5: Duplicate Candles

**Symptoms:**
- Same candle appears multiple times in database

**Cause:**
- Consumer crashed after inserting but before acknowledging
- Kafka re-delivered the message

**Solution:**
This is expected with AT_LEAST_ONCE semantics. Options:

1. **Accept duplicates** (candles are idempotent - same timestamp overwrites)
2. **Dedup in query:**
   ```sql
   SELECT * FROM candles_1m
   LATEST BY symbol, timestamp;
   ```
3. **Add unique constraint** (QuestDB doesn't support this)

### Issue 6: No Signals Appearing

**Cause:** Strategy-engine not running or not producing signals yet.

**Verify:**
```bash
# Check if trading-signals topic exists
docker exec kafka kafka-topics \
    --bootstrap-server localhost:9092 \
    --list | grep trading-signals

# Check if topic has messages
docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 \
    --topic trading-signals --time -1
```

**Solution:**
- Wait for strategy-engine to start (Phase 2, Task 6+)
- Or manually produce test signal:
  ```bash
  docker exec kafka kafka-console-producer \
      --bootstrap-server localhost:9092 \
      --topic trading-signals
  
  # Paste this JSON and press Enter:
  {"symbol":"AAPL","action":"BUY","strategyName":"TestStrategy","confidence":0.85,"timestamp":"2026-07-19T01:00:00Z"}
  ```

---

## Performance Benchmarks

### Expected Volume (10 symbols)

| Consumer | Actual Volume | Processing Strategy | Justification |
|----------|---------------|---------------------|---------------|
| Tick | 10 msg/sec (600/min) | Individual | Highest volume - existing pattern |
| Candle | 0.16 msg/sec (10/min) | **Batch** | Lower volume NOW, but scales to 1000/min with 1000 symbols |
| Signal | 0.02-0.08 msg/sec (1-5/min) | Individual | Lowest volume - real-time priority |

**Why Batch Candles Despite Low Current Volume?**

Current: 10 candles/min (low volume, batch seems overkill)
Future: With 1000 symbols → 1000 candles/min (batch becomes essential)

Batching now provides:
1. **Scalability**: Code works at any scale without changes
2. **Backlog processing**: Handles historical data efficiently (we had 4,975 candles backlog)
3. **Efficiency**: Even 10 candles/batch is 10x faster than 10 individual inserts

### Batch Processing Comparison

**Individual Inserts (500 candles):**
```
Time: 25,000ms (25 seconds)
Database calls: 500
Overhead: 500 × 50ms = 25,000ms
```

**Batch Insert (500 candles):**
```
Time: 100ms (0.1 seconds)
Database calls: 1
Overhead: 1 × 100ms = 100ms
```

**Performance Gain: 250x faster!**

---

## Next Steps

After completing this task:

1. **Verify Success Criteria:**
   - [ ] `candles_1m` table contains data
   - [ ] `signals` table created (may be empty)
   - [ ] Three consumer groups running (lag = 0)
   - [ ] Can query latest candles per symbol
   - [ ] No errors in logs

2. **Proceed to Task 6:**
   - Strategy Engine implementation
   - Will produce signals to `trading-signals` topic
   - Will populate `signals` table

3. **Optional Enhancements:**
   - Add REST API to query candles/signals
   - Add Grafana dashboard for monitoring
   - Export consumer metrics to Prometheus

---

## Summary

**What You Built:**

```
database-consumer (before)           database-consumer (after)
├─ TickConsumer                      ├─ TickConsumer (unchanged)
└─ QuestDB: ticks table              ├─ CandleConsumer (NEW)
                                     │   └─ Batch processing
                                     ├─ SignalConsumer (NEW)
                                     │   └─ Real-time processing
                                     └─ QuestDB: 3 tables
                                         ├─ ticks
                                         ├─ candles_1m (NEW)
                                         └─ signals (NEW)
```

**Key Learnings:**
- Multiple Kafka consumers in one application
- Batch vs individual processing trade-offs
- Consumer group independence
- QuestDB SYMBOL type optimization
- Manual offset management

**Time Spent:** ~1 hour ✅

---

## References

- [Spring Kafka Documentation](https://docs.spring.io/spring-kafka/reference/)
- [QuestDB SQL Reference](https://questdb.io/docs/reference/sql/)
- [Kafka Consumer Configuration](https://kafka.apache.org/documentation/#consumerconfigs)
- [Phase 2 Architecture Diagram](/docs/phase-2/architecture-diagram.md)
