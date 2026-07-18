# Aggregator Service - Complete Implementation Guide

## Overview

This guide walks through creating the `aggregator` Kafka Streams service that converts raw market ticks into OHLC (Open, High, Low, Close) candles for frontend visualization.

**By the end:** You'll have a working Kafka Streams application that produces 1-minute candles to the `candles-1m` topic.

---

## Why Kafka Streams?

### Interview Question: "Why not just query QuestDB for candles?"

**Answer:**

| Approach | Kafka Streams | QuestDB Queries |
|----------|---------------|-----------------|
| **Latency** | Real-time (< 1s) | Poll-based (30s+) |
| **Load** | Distributed stream processing | Repeated SELECT queries |
| **State** | Built-in state stores | Manual caching |
| **Scalability** | Horizontal (add partitions) | Vertical (bigger DB) |
| **Consistency** | Event-time windowing | Clock-based GROUP BY |

**Use case:** Frontend needs **real-time candles** updating as trades happen, not stale 30-second-old data from polls.

---

### Interview Question: "Why separate aggregator service?"

**Answer:**

**Failure Isolation:**
- Aggregator crash → Frontend charts stop updating
- Strategy engine continues analyzing ticks
- Critical path (signal generation) unaffected

**Different Processing Models:**
- **Aggregator:** Stateful stream processing (windows, aggregations)
- **Strategy Engine:** Stateless queries (SQL SELECT)

**Resource Profiles:**
- **Aggregator:** Memory-heavy (Kafka Streams state stores)
- **Strategy Engine:** CPU-heavy (indicator calculations)

Separate services allow independent scaling and restart.

---

## Architecture

### Data Flow

```
market-data topic (ticks)
        ↓
Kafka Streams (windowing)
        ↓
1-minute tumbling windows
        ↓
OHLC aggregation
        ↓
candles-1m topic
        ↓
Database Consumer → QuestDB candles_1m table
        ↓
Frontend queries QuestDB for charts
```

### Windowing Strategy

**Tumbling Windows (not Hopping):**

```
Tumbling (our choice):
[--Window 1--][--Window 2--][--Window 3--]
14:00-14:01   14:01-14:02   14:02-14:03

Each tick belongs to exactly ONE window.

Hopping (not used):
[--Window 1--]
    [--Window 2--]
        [--Window 3--]
        
Windows overlap, same tick in multiple windows.
```

**Why Tumbling:** Candles don't overlap. 14:00 candle = ticks from 14:00:00 to 14:00:59.

---

## Prerequisites

- [ ] Java 21 installed
- [ ] Maven 3.9+ installed
- [ ] Docker running with Kafka
- [ ] Phase 1 data-generator producing ticks to `market-data` topic
- [ ] Kafka topic `market-data` exists

**Verify:**
```bash
# Check ticks flowing
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic market-data \
  --max-messages 5

# Should see JSON tick messages
```

---

## Step 1: Create Project Directory

```bash
cd /Users/mhiteshkumar/QuantStream
mkdir aggregator
cd aggregator
```

---

## Step 2: Create pom.xml

Create `pom.xml` with Kafka Streams dependencies:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.7</version>
        <relativePath/>
    </parent>

    <groupId>com.quantstream</groupId>
    <artifactId>aggregator</artifactId>
    <version>1.0.0</version>
    <name>QuantStream Aggregator</name>
    <description>Kafka Streams aggregator - creates OHLC candles from ticks</description>

    <properties>
        <java.version>21</java.version>
    </properties>

    <dependencies>
        <!-- Spring Boot Core -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter</artifactId>
        </dependency>

        <!-- Kafka Streams -->
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-streams</artifactId>
        </dependency>

        <!-- Spring Kafka (for Kafka Streams binder) -->
        <dependency>
            <groupId>org.springframework.kafka</groupId>
            <artifactId>spring-kafka</artifactId>
        </dependency>

        <!-- Jackson for JSON (Serdes) -->
        <dependency>
            <groupId>com.fasterxml.jackson.core</groupId>
            <artifactId>jackson-databind</artifactId>
        </dependency>
        <dependency>
            <groupId>com.fasterxml.jackson.datatype</groupId>
            <artifactId>jackson-datatype-jsr310</artifactId>
        </dependency>

        <!-- Lombok (reduce boilerplate) -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- Testing -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-streams-test-utils</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <configuration>
                    <annotationProcessorPaths>
                        <path>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                            <version>${lombok.version}</version>
                        </path>
                    </annotationProcessorPaths>
                </configuration>
            </plugin>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

**Key Dependencies:**
- `kafka-streams` - Kafka Streams DSL
- `spring-kafka` - Spring integration with Kafka Streams
- `jackson-datatype-jsr310` - Serialize `Instant` timestamps

---

## Step 3: Create Directory Structure

```bash
mkdir -p src/main/java/com/quantstream/aggregator
mkdir -p src/main/java/com/quantstream/aggregator/config
mkdir -p src/main/java/com/quantstream/aggregator/model
mkdir -p src/main/java/com/quantstream/aggregator/serdes
mkdir -p src/main/resources
```

---

## Step 4: Create application.yml

Create `src/main/resources/application.yml`:

```yaml
spring:
  application:
    name: aggregator

  kafka:
    bootstrap-servers: localhost:9092
    streams:
      application-id: aggregator-service
      # State store directory
      state-dir: /tmp/kafka-streams
      # Processing guarantee (exactly-once semantics)
      processing:
        guarantee: exactly_once_v2
      properties:
        # Commit interval (how often to flush state)
        commit.interval.ms: 1000
        # Windowing grace period (late events)
        window.grace.ms: 5000

server:
  port: 8084

logging:
  level:
    com.quantstream: DEBUG
    org.apache.kafka.streams: INFO
    org.springframework.kafka: INFO
```

**Key Configurations:**

- **application-id:** Unique identifier for this Kafka Streams app
  - Used for consumer group and state store naming
  - Change this if you want to restart fresh (creates new consumer group)

- **state-dir:** Where Kafka Streams stores local state
  - RocksDB database files
  - Changelog topic offsets
  - `/tmp` is fine for dev, use persistent volume in prod

- **exactly_once_v2:** Transactional processing
  - Guarantees: Process each record exactly once, even with failures
  - Required for financial data
  - Alternative: `at_least_once` (faster but may duplicate)

- **commit.interval.ms:** How often to flush aggregations
  - Lower = more real-time, higher write load
  - 1000ms = good balance

- **window.grace.ms:** How long to wait for late events
  - Events can arrive out-of-order (network delays)
  - 5000ms = accept events up to 5s late
  - After grace period, window closes permanently

---

## Step 5: Create Model Classes

### 5.1 Tick Model

Create `src/main/java/com/quantstream/aggregator/model/Tick.java`:

```java
package com.quantstream.aggregator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Incoming tick from market-data topic.
 * 
 * Matches data-generator output format.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Tick {
    private String symbol;
    private double price;
    private double volume;
    
    @JsonProperty("timestamp")
    private Instant timestamp;
}
```

**Why separate model from strategy-engine:**
- Aggregator and strategy-engine are separate services
- No shared code (microservices principle)
- Same structure, but independently maintained

---

### 5.2 Candle Model

Create `src/main/java/com/quantstream/aggregator/model/Candle.java`:

```java
package com.quantstream.aggregator.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * OHLC candle output.
 * 
 * Published to candles-1m topic.
 * 
 * Fields:
 * - open: First tick price in window
 * - high: Highest tick price in window
 * - low: Lowest tick price in window
 * - close: Last tick price in window
 * - volume: Sum of all tick volumes in window
 * - timestamp: Window start time
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Candle {
    private String symbol;
    private double open;
    private double high;
    private double low;
    private double close;
    private double volume;
    
    @JsonProperty("timestamp")
    private Instant timestamp;
}
```

**Candle Semantics:**

```
Window: 14:00:00 - 14:00:59

Ticks:
  14:00:03 → 100.0
  14:00:15 → 102.0 (highest)
  14:00:45 → 98.0  (lowest)
  14:00:58 → 101.0

Candle:
  open:   100.0 (first)
  high:   102.0 (max)
  low:    98.0  (min)
  close:  101.0 (last)
  volume: sum of all volumes
  timestamp: 14:00:00 (window start)
```

---

## Step 6: Create JSON Serdes

Kafka Streams needs custom serializers/deserializers for JSON.

### 6.1 Generic JSON Serde

Create `src/main/java/com/quantstream/aggregator/serdes/JsonSerde.java`:

```java
package com.quantstream.aggregator.serdes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import java.util.Map;

/**
 * Generic JSON Serde for Kafka Streams.
 * 
 * Handles serialization/deserialization of POJOs to/from JSON.
 * 
 * Usage:
 *   Serde<Tick> tickSerde = JsonSerde.of(Tick.class);
 */
public class JsonSerde<T> implements Serde<T> {

    private final ObjectMapper objectMapper;
    private final Class<T> type;

    public JsonSerde(Class<T> type) {
        this.type = type;
        this.objectMapper = new ObjectMapper();
        // Register JSR310 module for Instant/LocalDateTime support
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    /**
     * Factory method for creating Serde.
     */
    public static <T> Serde<T> of(Class<T> type) {
        return new JsonSerde<>(type);
    }

    @Override
    public Serializer<T> serializer() {
        return new JsonSerializer();
    }

    @Override
    public Deserializer<T> deserializer() {
        return new JsonDeserializer();
    }

    /**
     * JSON Serializer
     */
    private class JsonSerializer implements Serializer<T> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            // No configuration needed
        }

        @Override
        public byte[] serialize(String topic, T data) {
            if (data == null) {
                return null;
            }
            try {
                return objectMapper.writeValueAsBytes(data);
            } catch (Exception e) {
                throw new RuntimeException("Error serializing JSON", e);
            }
        }

        @Override
        public void close() {
            // No resources to close
        }
    }

    /**
     * JSON Deserializer
     */
    private class JsonDeserializer implements Deserializer<T> {
        @Override
        public void configure(Map<String, ?> configs, boolean isKey) {
            // No configuration needed
        }

        @Override
        public T deserialize(String topic, byte[] data) {
            if (data == null) {
                return null;
            }
            try {
                return objectMapper.readValue(data, type);
            } catch (Exception e) {
                throw new RuntimeException("Error deserializing JSON", e);
            }
        }

        @Override
        public void close() {
            // No resources to close
        }
    }
}
```

**Why Custom Serde:**
- Kafka Streams built-in serdes only support primitives (String, Long, etc.)
- We need to serialize custom objects (Tick, Candle)
- Jackson handles JSON conversion
- JSR310 module handles `Instant` timestamps

---

## Step 7: Create Kafka Streams Configuration & Topology

This is the core aggregation logic with Kafka Streams configuration.

Create `src/main/java/com/quantstream/aggregator/config/StreamsTopology.java`:

```java
package com.quantstream.aggregator.config;

import com.quantstream.aggregator.model.Candle;
import com.quantstream.aggregator.model.Tick;
import com.quantstream.aggregator.serdes.JsonSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.kstream.*;
import org.apache.kafka.streams.processor.TimestampExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.annotation.KafkaStreamsDefaultConfiguration;
import org.springframework.kafka.config.KafkaStreamsConfiguration;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Streams topology for OHLC candle aggregation.
 *
 * Flow:
 * 1. Read ticks from market-data topic
 * 2. Group by symbol
 * 3. Window into 1-minute tumbling windows
 * 4. Aggregate into OHLC candles
 * 5. Write candles to candles-1m topic
 */
@Configuration
@EnableKafkaStreams
public class StreamsTopology {

    private static final Logger log = LoggerFactory.getLogger(StreamsTopology.class);

    private static final String INPUT_TOPIC = "market-data";
    private static final String OUTPUT_TOPIC = "candles-1m";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.streams.application-id}")
    private String applicationId;

    @Value("${spring.kafka.streams.state-dir:/tmp/kafka-streams}")
    private String stateDir;

    @Bean(name = KafkaStreamsDefaultConfiguration.DEFAULT_STREAMS_CONFIG_BEAN_NAME)
    public KafkaStreamsConfiguration defaultKafkaStreamsConfig() {
        Map<String, Object> props = new HashMap<>();
        props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(StreamsConfig.APPLICATION_ID_CONFIG, applicationId);
        props.put(StreamsConfig.STATE_DIR_CONFIG, stateDir);
        // At-least-once semantics (suitable for single-broker dev setup)
        props.put(StreamsConfig.PROCESSING_GUARANTEE_CONFIG, StreamsConfig.AT_LEAST_ONCE);
        props.put(StreamsConfig.COMMIT_INTERVAL_MS_CONFIG, 1000);
        return new KafkaStreamsConfiguration(props);
    }

    @Bean
    public KStream<String, Candle> kStream(StreamsBuilder builder) {
        
        // Define Serdes
        Serde<String> stringSerde = Serdes.String();
        Serde<Tick> tickSerde = JsonSerde.of(Tick.class);
        Serde<Candle> candleSerde = JsonSerde.of(Candle.class);

        // 1. Read ticks from input topic
        KStream<String, Tick> tickStream = builder.stream(
            INPUT_TOPIC,
            Consumed.with(stringSerde, tickSerde)
                .withTimestampExtractor(new TickTimestampExtractor())
        );

        // 2. Group by symbol
        KGroupedStream<String, Tick> groupedBySymbol = tickStream
            .groupBy(
                (key, tick) -> tick.getSymbol(),
                Grouped.with(stringSerde, tickSerde)
            );

        // 3. Window into 1-minute tumbling windows
        TimeWindowedKStream<String, Tick> windowedStream = groupedBySymbol
            .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)));

        // 4. Aggregate into OHLC candles
        KTable<Windowed<String>, Candle> candleTable = windowedStream
            .aggregate(
                // Initializer: Create empty candle
                () -> null,
                
                // Aggregator: Update candle with each tick
                (key, tick, candle) -> {
                    if (candle == null) {
                        // First tick in window - initialize candle
                        return new Candle(
                            tick.getSymbol(),
                            tick.getPrice(),     // open = first price
                            tick.getPrice(),     // high = first price
                            tick.getPrice(),     // low = first price
                            tick.getPrice(),     // close = first price
                            tick.getVolume(),    // volume = first volume
                            tick.getTimestamp()  // timestamp = first tick time
                        );
                    } else {
                        // Update existing candle
                        candle.setHigh(Math.max(candle.getHigh(), tick.getPrice()));
                        candle.setLow(Math.min(candle.getLow(), tick.getPrice()));
                        candle.setClose(tick.getPrice());  // Last tick price
                        candle.setVolume(candle.getVolume() + tick.getVolume());
                        return candle;
                    }
                },
                
                // Materialized: State store config
                Materialized.with(stringSerde, candleSerde)
            );

        // 5. Convert windowed KTable to KStream and write to output
        KStream<String, Candle> candleStream = candleTable
            .toStream()
            .map((windowedKey, candle) -> {
                // Extract symbol from windowed key
                String symbol = windowedKey.key();
                
                // Set timestamp to window start time
                candle.setTimestamp(windowedKey.window().startTime());
                
                log.info("Emitting candle: {} @ {} (O:{} H:{} L:{} C:{} V:{})",
                    symbol, 
                    candle.getTimestamp(),
                    candle.getOpen(),
                    candle.getHigh(),
                    candle.getLow(),
                    candle.getClose(),
                    candle.getVolume()
                );
                
                return KeyValue.pair(symbol, candle);
            });

        // Write to output topic
        candleStream.to(OUTPUT_TOPIC, Produced.with(stringSerde, candleSerde));

        return candleStream;
    }

    /**
     * Custom timestamp extractor - use tick's event time, not processing time.
     * 
     * Why: Windowing should be based on when tick occurred, not when we process it.
     */
    private static class TickTimestampExtractor implements TimestampExtractor {
        @Override
        public long extract(org.apache.kafka.clients.consumer.ConsumerRecord<Object, Object> record, long partitionTime) {
            Tick tick = (Tick) record.value();
            if (tick != null && tick.getTimestamp() != null) {
                return tick.getTimestamp().toEpochMilli();
            }
            // Fallback to record timestamp if tick timestamp missing
            return record.timestamp();
        }
    }
}
```

**Key Concepts Explained:**

### Timestamp Extraction
```java
withTimestampExtractor(new TickTimestampExtractor())
```
- Uses tick's `timestamp` field (event time)
- Not Kafka record timestamp (processing time)
- Critical for correct windowing

### Tumbling Windows
```java
TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1))
```
- 1-minute non-overlapping windows
- `NoGrace` = don't wait for late events (use `withGracePeriodAndSize` if needed)
- Window boundaries: [00:00, 00:01), [00:01, 00:02), etc.

### Aggregation Logic
```java
aggregate(initializer, aggregator, materialized)
```
1. **Initializer:** Returns `null` (no candle yet)
2. **Aggregator:** Called for each tick:
   - First tick: Create new candle (all OHLC = first price)
   - Subsequent ticks: Update high/low/close/volume
3. **Materialized:** Stores intermediate state (RocksDB)

### Window Closing
- Windows close after window size + grace period
- Our config: Close after 1 minute (no grace)
- When window closes, emit final candle to output topic

---

## Step 8: Create Main Application

Create `src/main/java/com/quantstream/aggregator/AggregatorApplication.java`:

```java
package com.quantstream.aggregator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Aggregator Service - OHLC Candle Creation
 * 
 * Kafka Streams application that:
 * 1. Consumes ticks from market-data topic
 * 2. Windows into 1-minute intervals
 * 3. Aggregates into OHLC candles
 * 4. Produces candles to candles-1m topic
 * 
 * Processing Model:
 * - Stateful (maintains RocksDB state store)
 * - Event-time windowing (uses tick timestamps)
 * - Exactly-once semantics (transactional)
 * 
 * State Management:
 * - Local: /tmp/kafka-streams/aggregator-service/
 * - Changelog: __aggregator-service-KSTREAM-AGGREGATE-STATE-STORE-0000000003-changelog
 * 
 * Failure Recovery:
 * - State automatically restored from changelog topic
 * - Resumes from last committed offset
 * - No data loss (exactly-once guarantee)
 */
@SpringBootApplication
public class AggregatorApplication {
    public static void main(String[] args) {
        SpringApplication.run(AggregatorApplication.class, args);
    }
}
```

---

## Step 9: Compile and Verify

```bash
# Compile
mvn clean compile

# Should see:
# [INFO] BUILD SUCCESS
```

**Expected output:**
- 6 source files compiled
- No errors

---

## Step 10: Create Kafka Topics

Before starting, ensure topics exist:

```bash
# Create candles-1m topic
docker exec kafka kafka-topics \
  --create \
  --bootstrap-server localhost:9092 \
  --topic candles-1m \
  --partitions 3 \
  --replication-factor 1 \
  --if-not-exists

# Verify
docker exec kafka kafka-topics \
  --list \
  --bootstrap-server localhost:9092 | grep candles
```

**Why 3 partitions:**
- Same as market-data topic (data parallelism)
- Allows horizontal scaling (3 aggregator instances)

---

## Step 11: Run Aggregator

```bash
mvn spring-boot:run
```

**Expected logs:**
```
[main] AggregatorApplication: Starting AggregatorApplication
[main] StreamThread-1: Starting Kafka Streams
[main] StreamThread-1: State transition from CREATED to REBALANCING
[main] StreamThread-1: State transition from REBALANCING to RUNNING
```

---

## Step 12: Verify Candles

Open another terminal and consume candles:

```bash
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic candles-1m \
  --from-beginning \
  --max-messages 10
```

**Expected output:**
```json
{
  "symbol": "AAPL",
  "open": 150.25,
  "high": 151.30,
  "low": 149.80,
  "close": 150.95,
  "volume": 15432.5,
  "timestamp": 1642176000000
}
{
  "symbol": "BTC",
  "open": 43250.0,
  "high": 43500.0,
  "low": 43100.0,
  "close": 43400.0,
  "volume": 234.75,
  "timestamp": 1642176000000
}
```

**Verification:**
1. One candle per symbol per minute
2. OHLC values reasonable (open ≤ close ≤ high, low ≤ close)
3. Timestamp aligned to minute boundary (seconds = 00)

---

## Step 13: Manual Verification

Verify OHLC calculations are correct:

```bash
# Query QuestDB for raw ticks in a specific minute
curl -G "http://localhost:9001/exec" \
  --data-urlencode "query=SELECT price FROM ticks WHERE symbol='AAPL' AND timestamp BETWEEN '2024-01-14T14:00:00' AND '2024-01-14T14:00:59' ORDER BY timestamp;"

# Should see prices matching candle OHLC
# open = first price
# high = max price
# low = min price
# close = last price
```

---

## Architecture Deep Dive

### Kafka Streams Internals

#### State Store
```
/tmp/kafka-streams/aggregator-service/
├── 0_0/  (partition 0)
│   └── rocksdb/
│       ├── CURRENT
│       ├── MANIFEST
│       └── *.sst (data files)
├── 0_1/  (partition 1)
└── 0_2/  (partition 2)
```

- **RocksDB:** Embedded key-value store
- **Key:** `[symbol, window_start]`
- **Value:** `Candle` (in-progress)
- **Persistent:** Survives restarts

#### Changelog Topic
```
Topic: __aggregator-service-KSTREAM-AGGREGATE-STATE-STORE-0000000003-changelog
Partitions: 3
Replication: 1

Purpose: Backup of state store
- Every state change written to changelog
- On restart, replay changelog to rebuild state
- Enables fault tolerance
```

### Exactly-Once Semantics

How it works:

1. **Read offset** from Kafka
2. **Process tick** (update candle in state store)
3. **Write candle** to output topic
4. **Commit offset + state** in single transaction

If crash happens:
- Transaction aborts
- On restart, replay from last committed offset
- State restored from changelog
- No duplicate candles emitted

---

## Performance Tuning

### Commit Interval
```yaml
commit.interval.ms: 1000  # Default: 30000
```
- Lower = more real-time, higher overhead
- Higher = batching, lower CPU, higher latency
- 1000ms = good for real-time dashboards

### State Store Cache
```yaml
cache.max.bytes.buffering: 10485760  # 10 MB (default)
```
- Caches state updates in memory
- Reduces RocksDB writes
- Flushes on commit

### Parallelism
```yaml
# Number of stream threads (default: 1)
num.stream.threads: 2
```
- Each thread processes partitions
- Max threads = number of partitions (3)
- More threads = more CPU, more throughput

---

## Monitoring

### Application Metrics

Add actuator for metrics:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,metrics,info
  metrics:
    export:
      prometheus:
        enabled: true
```

Metrics endpoint: `http://localhost:8084/actuator/metrics`

### Kafka Streams Metrics

Key metrics to monitor:

```bash
# Consumer lag (how far behind)
docker exec kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --group aggregator-service \
  --describe

# Should show:
# TOPIC          PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
# market-data    0          12345          12345           0
# market-data    1          12346          12346           0
# market-data    2          12344          12344           0
```

**Lag = 0:** Aggregator is caught up (good)  
**Lag > 1000:** Aggregator is falling behind (scale up)

---

## Troubleshooting

### Issue: No candles emitted

**Symptoms:**
- Aggregator running
- No messages in candles-1m topic

**Causes:**

1. **No input data**
   ```bash
   # Check market-data has messages
   docker exec kafka kafka-console-consumer \
     --bootstrap-server localhost:9092 \
     --topic market-data \
     --max-messages 5
   ```

2. **Windows not closing**
   - Wait 1+ minute for first window to close
   - Check logs for "Emitting candle" message

3. **Serde errors**
   ```bash
   # Check logs for:
   # "Error deserializing JSON"
   # "ClassNotFoundException"
   ```

### Issue: Duplicate candles

**Symptoms:**
- Same candle emitted multiple times

**Causes:**

1. **Exactly-once disabled**
   - Check `processing.guarantee: exactly_once_v2` in application.yml

2. **Multiple aggregator instances**
   - Only run one aggregator (or partition properly)

### Issue: State store corrupted

**Symptoms:**
```
RocksDBException: Corruption
```

**Fix:**
```bash
# Delete state store (will rebuild from changelog)
rm -rf /tmp/kafka-streams/aggregator-service/

# Restart aggregator
mvn spring-boot:run
```

### Issue: High memory usage

**Symptoms:**
- Aggregator using > 1 GB memory

**Causes:**

1. **Too many windows in memory**
   - Reduce grace period
   - Increase commit frequency

2. **Large state store cache**
   - Reduce `cache.max.bytes.buffering`

---

## Testing

### Unit Test (Optional)

```java
@Test
public void testCandleAggregation() {
    // Use TopologyTestDriver
    TopologyTestDriver testDriver = new TopologyTestDriver(
        builder.build(),
        config
    );

    // Input ticks
    TestInputTopic<String, Tick> inputTopic = testDriver.createInputTopic(
        "market-data",
        Serdes.String().serializer(),
        tickSerde.serializer()
    );

    // Output candles
    TestOutputTopic<String, Candle> outputTopic = testDriver.createOutputTopic(
        "candles-1m",
        Serdes.String().deserializer(),
        candleSerde.deserializer()
    );

    // Send test ticks
    inputTopic.pipeInput("AAPL", new Tick("AAPL", 100.0, 10.0, Instant.parse("2024-01-14T14:00:00Z")));
    inputTopic.pipeInput("AAPL", new Tick("AAPL", 105.0, 20.0, Instant.parse("2024-01-14T14:00:30Z")));
    inputTopic.pipeInput("AAPL", new Tick("AAPL", 95.0, 15.0, Instant.parse("2024-01-14T14:00:45Z")));

    // Advance time to close window
    testDriver.advanceWallClockTime(Duration.ofMinutes(2));

    // Verify candle
    Candle candle = outputTopic.readValue();
    assertEquals(100.0, candle.getOpen());
    assertEquals(105.0, candle.getHigh());
    assertEquals(95.0, candle.getLow());
    assertEquals(95.0, candle.getClose());
    assertEquals(45.0, candle.getVolume());
}
```

---

## Production Considerations

### State Store Persistence

**Development:**
```yaml
state-dir: /tmp/kafka-streams
```

**Production:**
```yaml
state-dir: /var/lib/kafka-streams
```

Mount persistent volume:
```yaml
# docker-compose.yml
volumes:
  - ./kafka-streams-data:/var/lib/kafka-streams
```

### Replication Factor

**Development:**
```bash
--replication-factor 1
```

**Production:**
```bash
--replication-factor 3  # Survive 2 broker failures
```

### Resource Limits

```yaml
# docker-compose.yml
deploy:
  resources:
    limits:
      memory: 512M
      cpus: '1.0'
    reservations:
      memory: 256M
      cpus: '0.5'
```

---

## Success Criteria Checklist

- [ ] Aggregator project compiles (`mvn clean compile`)
- [ ] Consumes from `market-data` topic
- [ ] Produces to `candles-1m` topic
- [ ] Candles emit at end of each minute
- [ ] OHLC values correct (verified manually)
- [ ] Can see candles: `docker exec kafka kafka-console-consumer --topic candles-1m`
- [ ] State restored after restart (stop/start aggregator, verify no gaps)
- [ ] No errors in logs for 5 minutes
- [ ] Consumer lag = 0

---

## Next Steps

After aggregator is working:

1. **Task 5:** Extend database-consumer to write candles to QuestDB
2. **Task 7:** Integration testing with all services
3. **Phase 3:** Build frontend to visualize candles

---

## Summary

**What You Built:**
- Kafka Streams application with windowed aggregation
- 1-minute tumbling windows
- OHLC candle creation
- Exactly-once processing semantics
- Stateful stream processing with RocksDB

**Key Learnings:**
- Kafka Streams DSL (KStream, KTable, TimeWindows)
- Event-time vs processing-time
- State stores and changelog topics
- Custom serdes for JSON
- Windowing strategies

**Time Estimate:** 2-3 hours

Good luck! 🚀
