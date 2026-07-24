# Kafka Producer Configuration Guide

## What You're Building

**KafkaProducerConfig** is a Spring configuration class that sets up the Kafka producer for sending messages.

**Think of it as:** The "setup instructions" for how Spring should create a Kafka producer bean.

---

## Why Configuration Class?

### Without Configuration Class (Manual Setup)

```java
@Service
public class MarketDataGenerator {
    
    public void sendTick() {
        // Create producer manually every time
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.springframework.kafka.support.serializer.JsonSerializer");
        
        KafkaProducer<String, Tick> producer = new KafkaProducer<>(props);
        producer.send(new ProducerRecord<>("market-data", "AAPL", tick));
        producer.close();
    }
}
```

**Problems:**
- Create producer every time (expensive)
- Manage connection lifecycle manually
- No connection pooling
- Hard to test (tightly coupled)

### With Configuration Class (Spring Way)

```java
@Configuration
public class KafkaProducerConfig {
    @Bean
    public KafkaTemplate<String, Tick> kafkaTemplate() {
        // Spring creates ONE producer, reuses it
        return new KafkaTemplate<>(producerFactory());
    }
}

@Service
public class MarketDataGenerator {
    @Autowired
    private KafkaTemplate<String, Tick> kafkaTemplate;
    
    public void sendTick() {
        kafkaTemplate.send("market-data", "AAPL", tick);
        // No producer management needed
    }
}
```

**Benefits:**
- Spring creates producer once, injects it everywhere
- Connection pooling handled automatically
- Easy to test (can inject mock)
- Configuration separate from business logic

---

## Understanding Kafka Serialization

### What is Serialization?

**Serialization** = Converting Java object → bytes

**Kafka only understands bytes**, not Java objects.

**Flow:**

```
Java Object (Tick)
    ↓
Serializer (JsonSerializer)
    ↓
JSON String
    ↓
Bytes
    ↓
Kafka
```

**Example:**

**Java object:**
```java
Tick tick = new Tick("AAPL", 180.50, 1000.0, Instant.now());
```

**After JsonSerializer:**
```json
{
  "symbol": "AAPL",
  "price": 180.5,
  "volume": 1000.0,
  "timestamp": "2024-07-12T10:00:01Z"
}
```

**After byte conversion:**
```
7B 22 73 79 6D 62 6F 6C 22 3A 22 41 41 50 4C 22 2C 22 70 72 69 63 65 22 3A 31 38 30 2E 35 ...
(these bytes are what Kafka stores)
```

### Key vs Value Serializers

**Kafka message = Key + Value**

**Key:** `"AAPL"` (String)
**Value:** `Tick` object

**We need 2 serializers:**
1. **Key serializer:** String → bytes
2. **Value serializer:** Tick → bytes

**Configuration:**
```yaml
spring:
  kafka:
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
```

**Why different serializers?**
- Keys are simple strings (symbol names)
- Values are complex objects (Tick with 4 fields)

---

## Creating KafkaProducerConfig.java

### Step 1: Create the File

**In IntelliJ:**

1. Right-click `src/main/java/com/quantstream/generator/config`
2. New → Java Class
3. Name: `KafkaProducerConfig`
4. Click OK

### Step 2: Write the Code

```java
package com.quantstream.generator.config;

import com.quantstream.generator.model.Tick;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka Producer configuration for sending Tick messages.
 */
@Configuration
public class KafkaProducerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    /**
     * Creates ProducerFactory with configuration for String keys and Tick values.
     */
    @Bean
    public ProducerFactory<String, Tick> producerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        
        // Kafka broker address
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Key serializer (String → bytes)
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        
        // Value serializer (Tick → JSON → bytes)
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        
        // Durability: wait for all replicas to acknowledge
        configProps.put(ProducerConfig.ACKS_CONFIG, "all");
        
        // Retry failed sends up to 3 times
        configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
        
        // Compression (saves bandwidth)
        configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
        
        // Batch settings for efficiency
        configProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
        configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
        
        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Creates KafkaTemplate for sending messages.
     * This is the high-level API you'll use in your services.
     */
    @Bean
    public KafkaTemplate<String, Tick> kafkaTemplate() {
        return new KafkaTemplate<>(producerFactory());
    }
}
```

---

## Understanding Each Part

### @Configuration

```java
@Configuration
public class KafkaProducerConfig {
```

**What it does:**
- Tells Spring: "This class provides beans"
- Spring calls `@Bean` methods at startup
- Beans are stored in application context

**Without @Configuration:**
- Methods never called
- No beans created
- Application fails with "No bean of type KafkaTemplate found"

### @Value Injection

```java
@Value("${spring.kafka.bootstrap-servers}")
private String bootstrapServers;
```

**What it does:**
- Reads value from `application.yml`
- Injects into this field at startup

**From application.yml:**
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
```

**Result:**
```java
bootstrapServers = "localhost:9092"
```

**Why not hardcode?**
```java
// Bad - hardcoded
private String bootstrapServers = "localhost:9092";
```

**Problems with hardcoding:**
- Can't change for production (might be `prod-kafka:9092`)
- Can't override with environment variables
- Violates 12-factor app principles

### ProducerFactory Bean

```java
@Bean
public ProducerFactory<String, Tick> producerFactory() {
    Map<String, Object> configProps = new HashMap<>();
    // ... configuration
    return new DefaultKafkaProducerFactory<>(configProps);
}
```

**What is ProducerFactory?**
- Factory that creates Kafka producers
- Manages connection pool
- Handles connection lifecycle

**Generic types:**
```java
ProducerFactory<String, Tick>
                 ↑      ↑
                 │      └─ Value type (Tick object)
                 └──────── Key type (symbol string)
```

**Configuration properties explained:**

#### BOOTSTRAP_SERVERS_CONFIG

```java
configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
```

**What:** Kafka broker addresses (comma-separated)

**Example:**
```java
"localhost:9092"                    // Single broker (our dev setup)
"kafka1:9092,kafka2:9092,kafka3:9092"  // Production cluster
```

**Why multiple?**
- If one broker is down, producer connects to another
- High availability

#### KEY_SERIALIZER_CLASS_CONFIG

```java
configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
```

**What:** Converts key (String) → bytes

**Example:**
```
"AAPL" → [0x41, 0x41, 0x50, 0x4C]
```

**Why StringSerializer?**
- Keys are simple strings (symbol names)
- No need for complex serialization

#### VALUE_SERIALIZER_CLASS_CONFIG

```java
configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
```

**What:** Converts value (Tick object) → JSON → bytes

**Example:**
```java
Tick("AAPL", 180.5, 1000.0, Instant.now())
    ↓
{"symbol":"AAPL","price":180.5,"volume":1000.0,"timestamp":"2024-07-12T10:00:01Z"}
    ↓
[0x7B, 0x22, 0x73, ...]
```

**Why JsonSerializer?**
- Human-readable (can view in Kafka UI)
- Language-agnostic (any language can read JSON)
- Spring Boot auto-configures it

**Alternatives:**
- **Avro:** Schema-based, more efficient, but requires schema registry
- **Protobuf:** Binary format, fast, but requires .proto files
- **Java serialization:** Only Java can read it (bad for interop)

**We use JSON because:**
- Simple to debug
- Works with any consumer (Python, Node.js, etc.)
- Good enough for our scale (10 msg/sec)

#### ACKS_CONFIG

```java
configProps.put(ProducerConfig.ACKS_CONFIG, "all");
```

**What:** How many broker acknowledgments before considering send successful

**Options:**

**acks=0:** Fire-and-forget
```
Producer → Kafka
(doesn't wait for response)
```
- **Fastest:** No waiting
- **Least durable:** Message could be lost
- **Use case:** Metrics where occasional loss is OK

**acks=1:** Leader acknowledgment (default)
```
Producer → Kafka Leader → ACK
(waits for leader to write to disk)
```
- **Medium speed:** Wait for leader only
- **Medium durability:** Lost if leader crashes before replication
- **Use case:** Most applications

**acks=all (or -1):** All replicas acknowledge
```
Producer → Kafka Leader → Followers → ACK
(waits for all replicas to write)
```
- **Slowest:** Wait for all replicas
- **Most durable:** No data loss unless all replicas fail
- **Use case:** Financial transactions, our project (learning durability)

**Our choice:** `acks=all`
- We want to see durability in action
- 10 msg/sec is low volume (latency doesn't matter)

#### RETRIES_CONFIG

```java
configProps.put(ProducerConfig.RETRIES_CONFIG, 3);
```

**What:** Number of retries for failed sends

**Why retries fail:**
- Network hiccup
- Broker temporarily unavailable
- Leader election in progress

**Example:**
```
Attempt 1: Send → Error: Connection timeout
Attempt 2: Send → Error: Connection timeout
Attempt 3: Send → Success ✓
```

**Default:** `Integer.MAX_VALUE` (essentially infinite)

**We use 3 because:**
- Good balance (handles transient issues)
- Fails fast for real problems (don't retry forever)

#### COMPRESSION_TYPE_CONFIG

```java
configProps.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "snappy");
```

**What:** Compress messages before sending

**Options:**
- **none:** No compression (default)
- **gzip:** Best compression ratio, slower
- **snappy:** Good compression, fast
- **lz4:** Fast compression, good ratio
- **zstd:** Modern, good balance

**Our choice:** `snappy`
- Fast compression/decompression
- Good ratio for JSON
- Used by Kafka internally

**Size comparison:**

**Uncompressed Tick (JSON):**
```json
{"symbol":"AAPL","price":180.5,"volume":1000.0,"timestamp":"2024-07-12T10:00:01.123456Z"}
```
**Size:** ~90 bytes

**With snappy compression:** ~60-70 bytes

**Savings:** 20-30%

**Why compress?**
- Saves network bandwidth
- Saves disk space in Kafka
- Faster transfers

**Cost:** Minimal CPU (snappy is fast)

#### LINGER_MS_CONFIG

```java
configProps.put(ProducerConfig.LINGER_MS_CONFIG, 10);
```

**What:** Wait up to 10ms to batch messages together

**Without batching:**
```
Time 0ms:  Send message 1
Time 1ms:  Send message 2
Time 2ms:  Send message 3
→ 3 separate network requests
```

**With LINGER_MS=10:**
```
Time 0ms:  Message 1 arrives, start timer
Time 1ms:  Message 2 arrives, add to batch
Time 2ms:  Message 3 arrives, add to batch
Time 10ms: Timer expires, send batch (1 network request)
→ 1 network request with 3 messages
```

**Benefits:**
- Fewer network requests
- Better throughput
- Lower latency (amortized)

**Trade-off:**
- Adds up to 10ms latency
- For us: 1 message/second, so usually sends immediately

**Default:** 0ms (send immediately)

**We use 10ms:**
- Small latency acceptable
- Potential batching benefit if multiple simulators fire at same time

#### BATCH_SIZE_CONFIG

```java
configProps.put(ProducerConfig.BATCH_SIZE_CONFIG, 16384);
```

**What:** Maximum batch size in bytes (16 KB)

**When batch is full, send immediately** (don't wait for linger time)

**Example:**
```
Batch: [msg1, msg2, msg3, ...]
Size:  [500 + 500 + 500 + ... = 16,384 bytes]
→ Send batch (batch size limit reached)
```

**Default:** 16,384 bytes (16 KB)

**For our project:**
- Each Tick ~90 bytes
- Batch holds ~180 messages
- We send 10 msg/sec, so batch never fills
- This is just a safety limit

---

## KafkaTemplate Bean

```java
@Bean
public KafkaTemplate<String, Tick> kafkaTemplate() {
    return new KafkaTemplate<>(producerFactory());
}
```

**What is KafkaTemplate?**
- High-level API for sending messages
- Wraps ProducerFactory
- Provides convenient methods

**Generic types:**
```java
KafkaTemplate<String, Tick>
              ↑      ↑
              │      └─ Value type
              └──────── Key type
```

**Usage in services:**
```java
@Service
public class MarketDataGenerator {
    @Autowired
    private KafkaTemplate<String, Tick> kafkaTemplate;
    
    public void sendTick(Tick tick) {
        kafkaTemplate.send("market-data", tick.getSymbol(), tick);
        //                 ↑ topic       ↑ key           ↑ value
    }
}
```

**KafkaTemplate methods:**

**send(topic, key, value):**
```java
kafkaTemplate.send("market-data", "AAPL", tick);
```

Returns `CompletableFuture<SendResult>` (async)

**send(topic, value):**
```java
kafkaTemplate.send("market-data", tick);
```

No key (Kafka chooses partition randomly)

**sendDefault(value):**
```java
kafkaTemplate.sendDefault(tick);
```

Uses default topic (set in config)

**We use send(topic, key, value):**
- Explicit topic name
- Key ensures all AAPL messages go to same partition (ordering)

---

## Testing Configuration

### Step 1: Compile

```bash
mvn compile
```

**Expected:**
```
[INFO] BUILD SUCCESS
```

**If errors:**
- Check imports (auto-import in IntelliJ: Cmd+Shift+O)
- Check Tick class exists in model package

### Step 2: Verify Bean Creation

**Temporarily add to `GeneratorApplication.java`:**

```java
@SpringBootApplication
@EnableScheduling
public class GeneratorApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(GeneratorApplication.class, args);
        
        // Check if KafkaTemplate bean exists
        KafkaTemplate<String, Tick> kafkaTemplate = context.getBean(KafkaTemplate.class);
        System.out.println("✓ KafkaTemplate bean created: " + kafkaTemplate);
        
        context.close();
    }
}
```

**Run:**
```bash
mvn spring-boot:run
```

**Expected output:**
```
✓ KafkaTemplate bean created: org.springframework.kafka.core.KafkaTemplate@1a2b3c4d
```

**Remove test code after verification.**

---

## Common Issues

### Issue 1: "Could not resolve placeholder 'spring.kafka.bootstrap-servers'"

**Error:**
```
java.lang.IllegalArgumentException: Could not resolve placeholder 'spring.kafka.bootstrap-servers'
```

**Cause:** `application.yml` missing or has wrong property name

**Fix:** Verify `src/main/resources/application.yml` has:
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
```

### Issue 2: "Connection refused to localhost:9092"

**Error (in logs):**
```
WARN  o.a.k.c.NetworkClient : Connection to node -1 could not be established
```

**Cause:** Kafka not running

**Fix:**
```bash
docker-compose up -d
docker-compose logs kafka | grep "started"
```

Wait for: `[KafkaServer id=1] started`

### Issue 3: "No serializer found for class Tick"

**Error:**
```
org.apache.kafka.common.errors.SerializationException: Can't convert value of class Tick
```

**Cause:** JsonSerializer not finding Jackson

**Fix:** Add to `pom.xml` (should already be included via spring-boot-starter):
```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
</dependency>
```

### Issue 4: Bean Creation Error

**Error:**
```
Error creating bean with name 'kafkaTemplate'
```

**Cause:** Check logs for root cause

**Common causes:**
- Typo in configuration property names
- Missing `@Configuration` annotation
- Wrong ProducerConfig constants

---

## Summary

**KafkaProducerConfig.java:**
- **@Configuration** → Spring calls `@Bean` methods
- **@Value** → Injects bootstrap-servers from application.yml
- **producerFactory()** → Creates factory with serialization config
- **kafkaTemplate()** → Creates high-level API for sending messages

**Key configurations:**
- **BOOTSTRAP_SERVERS:** `localhost:9092` (where Kafka is)
- **KEY_SERIALIZER:** StringSerializer (symbol → bytes)
- **VALUE_SERIALIZER:** JsonSerializer (Tick → JSON → bytes)
- **ACKS:** `all` (most durable)
- **RETRIES:** 3 (handle transient failures)
- **COMPRESSION:** `snappy` (fast compression)
- **LINGER_MS:** 10ms (batch messages)
- **BATCH_SIZE:** 16KB (batch size limit)

**What you can now do:**
```java
@Autowired
private KafkaTemplate<String, Tick> kafkaTemplate;

kafkaTemplate.send("market-data", "AAPL", tick);
```

**Next:** Implement GBM price simulator (`guides/generator-gbm-implementation.md`)
