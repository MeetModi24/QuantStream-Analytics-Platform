# Kafka Consumer Configuration Guide

## What You're Building

**KafkaConsumerConfig** is a Spring configuration class that sets up the Kafka consumer for receiving messages.

**Think of it as:** The "setup instructions" for how Spring should create a Kafka consumer bean that listens to topics.

---

## Why Configuration Class?

### Without Configuration Class (Manual Setup)

```java
@Service
public class TickProcessor {
    
    public void processTicks() {
        // Create consumer manually every time
        Properties props = new Properties();
        props.put("bootstrap.servers", "localhost:9092");
        props.put("group.id", "analytics-group");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.springframework.kafka.support.serializer.JsonDeserializer");
        
        KafkaConsumer<String, Tick> consumer = new KafkaConsumer<>(props);
        consumer.subscribe(Arrays.asList("market-data"));
        
        while (true) {
            ConsumerRecords<String, Tick> records = consumer.poll(Duration.ofMillis(100));
            for (ConsumerRecord<String, Tick> record : records) {
                processTick(record.value());
            }
        }
    }
}
```

**Problems:**
- Manual polling loop (blocks thread)
- No offset management abstraction
- Hard to handle errors and retries
- Can't scale to multiple threads easily
- Tightly coupled (hard to test)

### With Configuration Class (Spring Way)

```java
@Configuration
public class KafkaConsumerConfig {
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Tick> kafkaListenerContainerFactory() {
        // Spring manages consumers, threads, offsets
        return factory;
    }
}

@Service
public class TickProcessor {
    @KafkaListener(topics = "market-data", groupId = "analytics-group")
    public void processTick(Tick tick) {
        // Process tick - Spring handles polling, threading, offsets
        System.out.println("Received: " + tick);
    }
}
```

**Benefits:**
- Spring manages consumer lifecycle automatically
- Annotations (@KafkaListener) instead of polling loops
- Automatic offset management
- Easy concurrency (thread pools built-in)
- Easy to test (can send test messages)

---

## Understanding Kafka Deserialization

### What is Deserialization?

**Deserialization** = Converting bytes → Java object

**Opposite of serialization** (which producer did).

**Flow:**

```
Kafka (bytes)
    ↓
Deserializer (JsonDeserializer)
    ↓
JSON String
    ↓
Java Object (Tick)
    ↓
Your @KafkaListener method
```

**Example:**

**Kafka stores (bytes):**
```
7B 22 73 79 6D 62 6F 6C 22 3A 22 41 41 50 4C 22 2C 22 70 72 69 63 65 22 3A 31 38 30 2E 35 ...
```

**After byte → JSON:**
```json
{
  "symbol": "AAPL",
  "price": 180.5,
  "volume": 1000.0,
  "timestamp": "2024-07-12T10:00:01Z"
}
```

**After JsonDeserializer:**
```java
Tick tick = new Tick("AAPL", 180.50, 1000.0, Instant.parse("2024-07-12T10:00:01Z"));
```

### Key vs Value Deserializers

**Kafka message = Key + Value**

**Key:** `"AAPL"` (String) - bytes → String
**Value:** `Tick` object - bytes → JSON → Tick

**We need 2 deserializers:**
1. **Key deserializer:** bytes → String
2. **Value deserializer:** bytes → Tick

**Configuration:**
```yaml
spring:
  kafka:
    consumer:
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
```

**CRITICAL:** Deserializers must match what producer used!

```
Producer uses:                Consumer uses:
StringSerializer     →→→      StringDeserializer ✓
JsonSerializer       →→→      JsonDeserializer ✓

StringSerializer     ×××      IntegerDeserializer ✗ (Error!)
```

---

## Creating KafkaConsumerConfig.java

### Step 1: Create the File

**In IntelliJ:**

1. Right-click `src/main/java/com/quantstream/analytics/config`
2. New → Java Class
3. Name: `KafkaConsumerConfig`
4. Click OK

### Step 2: Write the Code

```java
package com.quantstream.analytics.config;

import com.quantstream.analytics.model.Tick;
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
 * Kafka Consumer configuration for receiving Tick messages.
 */
@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id:analytics-group}")
    private String groupId;

    /**
     * Creates ConsumerFactory with configuration for String keys and Tick values.
     */
    @Bean
    public ConsumerFactory<String, Tick> consumerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        
        // Kafka broker address
        configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        
        // Consumer group ID (CRITICAL - identifies this consumer group)
        configProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        
        // Key deserializer (bytes → String)
        configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        
        // Value deserializer (bytes → JSON → Tick)
        configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        
        // JsonDeserializer specific: trust our Tick class
        configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.analytics.model");
        
        // Start reading from earliest message if no previous offset
        configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        
        // Automatically commit offsets every 5 seconds
        configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
        configProps.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 5000);
        
        // How much data to fetch in one request
        configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
        
        return new DefaultKafkaConsumerFactory<>(
            configProps,
            new StringDeserializer(),
            new JsonDeserializer<>(Tick.class, false)  // false = don't use type headers
        );
    }

    /**
     * Creates KafkaListenerContainerFactory for @KafkaListener annotations.
     * This manages consumer threads and message delivery.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Tick> kafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, Tick> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        
        factory.setConsumerFactory(consumerFactory());
        
        // Number of concurrent consumer threads (parallelism)
        factory.setConcurrency(3);
        
        return factory;
    }
}
```

---

## Understanding Each Part

### @EnableKafka

```java
@EnableKafka
@Configuration
public class KafkaConsumerConfig {
```

**What it does:**
- Enables Kafka's `@KafkaListener` annotation processing
- Tells Spring to look for listener methods
- Required for consumer functionality

**Without @EnableKafka:**
- `@KafkaListener` methods never called
- No error - just silently ignored
- Messages accumulate in Kafka (not consumed)

**Where it goes:**
- Usually on consumer config class
- Only needed once per application

### @Configuration

```java
@Configuration
public class KafkaConsumerConfig {
```

**What it does:**
- Tells Spring: "This class provides beans"
- Spring calls `@Bean` methods at startup
- Beans stored in application context

**Without @Configuration:**
- Consumer never created
- Application fails with "No qualifying bean of type ConsumerFactory found"

### @Value Injection

```java
@Value("${spring.kafka.bootstrap-servers}")
private String bootstrapServers;

@Value("${spring.kafka.consumer.group-id:analytics-group}")
private String groupId;
```

**What it does:**
- Reads values from `application.yml`
- Second one has default value (`analytics-group`)

**From application.yml:**
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: analytics-group
```

**Result:**
```java
bootstrapServers = "localhost:9092"
groupId = "analytics-group"
```

**Why defaults?**
```java
@Value("${spring.kafka.consumer.group-id:analytics-group}")
                                       ↑
                                       Default if property missing
```

**Use case:**
- Dev environment: uses default
- Production: override via environment variable

### ConsumerFactory Bean

```java
@Bean
public ConsumerFactory<String, Tick> consumerFactory() {
    Map<String, Object> configProps = new HashMap<>();
    // ... configuration
    return new DefaultKafkaConsumerFactory<>(
        configProps,
        new StringDeserializer(),
        new JsonDeserializer<>(Tick.class, false)
    );
}
```

**What is ConsumerFactory?**
- Factory that creates Kafka consumers
- Manages consumer instances
- Handles connection lifecycle

**Generic types:**
```java
ConsumerFactory<String, Tick>
                ↑      ↑
                │      └─ Value type (Tick object)
                └──────── Key type (symbol string)
```

---

## Key Configuration Properties

### BOOTSTRAP_SERVERS_CONFIG

```java
configProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
```

**What:** Kafka broker addresses (comma-separated)

**Example:**
```java
"localhost:9092"                    // Single broker (dev)
"kafka1:9092,kafka2:9092,kafka3:9092"  // Production cluster
```

**Same as producer** - consumer needs to find Kafka brokers too.

### GROUP_ID_CONFIG

```java
configProps.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
```

**MOST IMPORTANT CONSUMER PROPERTY**

**What:** Unique identifier for this consumer group

**Value:** `"analytics-group"`

**Why critical:** Determines how Kafka assigns partitions and tracks progress

**See "Consumer Groups Explained" section below for full details.**

### KEY_DESERIALIZER_CLASS_CONFIG

```java
configProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
```

**What:** Converts key bytes → String

**Example:**
```
[0x41, 0x41, 0x50, 0x4C] → "AAPL"
```

**MUST match producer's key serializer:**
```
Producer: StringSerializer
Consumer: StringDeserializer ✓
```

### VALUE_DESERIALIZER_CLASS_CONFIG

```java
configProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
```

**What:** Converts value bytes → JSON → Tick object

**Example:**
```
[0x7B, 0x22, ...] 
    ↓
{"symbol":"AAPL","price":180.5,...}
    ↓
Tick("AAPL", 180.5, 1000.0, ...)
```

**MUST match producer's value serializer:**
```
Producer: JsonSerializer
Consumer: JsonDeserializer ✓
```

### TRUSTED_PACKAGES

```java
configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.analytics.model");
```

**What:** Security - which packages can JsonDeserializer instantiate

**Why needed:**
- Prevents deserializing malicious classes
- JsonDeserializer won't create objects from untrusted packages

**Without this:**
```
ERROR: The class 'com.quantstream.analytics.model.Tick' is not in the trusted packages
```

**Options:**
```java
"com.quantstream.analytics.model"     // Specific package
"com.quantstream.*"                   // All subpackages
"*"                                   // Trust all (DANGEROUS - don't use in prod)
```

**Our choice:** Specific package for security

**Alternative approach (in constructor):**
```java
new JsonDeserializer<>(Tick.class, false)
                                   ↑
                                   false = don't use type headers, trust class parameter
```

### AUTO_OFFSET_RESET_CONFIG

```java
configProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
```

**What:** Where to start reading when no previous offset exists

**When this matters:**
1. First time this consumer group runs
2. Consumer group has been idle > retention period (offsets deleted)
3. New topic partition added

**Options:**

**earliest:** Start from beginning of topic
```
Topic messages: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
                 ↑
                 Start here (read everything)
```

**latest (default):** Start from end of topic
```
Topic messages: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]
                                            ↑
                                            Start here (only new messages)
```

**Comparison:**

**earliest:**
- **Use case:** Analytics, batch processing, reprocessing
- **Benefit:** Don't miss any data
- **Risk:** Large backlog on first run

**latest:**
- **Use case:** Real-time alerts, monitoring
- **Benefit:** Don't process old data
- **Risk:** Miss messages sent before consumer started

**Our choice:** `earliest`
- For analytics, we want all historical data
- First run will process all existing ticks
- Subsequent runs continue from last offset

**Example scenario:**

**Producer sends messages at 10:00 AM:**
```
Partition 0: [msg1, msg2, msg3, msg4, msg5]
```

**Consumer starts at 10:05 AM:**

**With earliest:**
```
Reads: msg1, msg2, msg3, msg4, msg5 (all)
```

**With latest:**
```
Reads: (nothing until new messages arrive)
```

**Message at 10:06 AM:**
```
Both read: msg6
```

### ENABLE_AUTO_COMMIT_CONFIG

```java
configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
configProps.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 5000);
```

**What:** Automatically commit offsets every 5 seconds

**See "Offset Management" section below for full explanation.**

**Options:**

**Auto-commit (true):**
- Spring commits offsets periodically
- Simple, hands-off
- Small risk of duplicate processing on crash

**Manual commit (false):**
- You call `acknowledge()` manually
- Full control
- More complex code

**Our choice:** `true` (auto-commit)
- Simpler for learning
- Good enough for analytics (occasional duplicate OK)

### MAX_POLL_RECORDS_CONFIG

```java
configProps.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 500);
```

**What:** Maximum number of records returned in one poll

**Why limit?**
- Prevents overwhelming consumer with too many messages
- Controls batch size for processing

**Example:**

**Topic has 10,000 messages:**
```
With MAX_POLL_RECORDS=500:
Poll 1: Returns 500 messages
Poll 2: Returns 500 messages
Poll 3: Returns 500 messages
...
```

**Without limit:**
```
Poll 1: Returns all 10,000 messages
Consumer runs out of memory ✗
```

**Trade-off:**
- **Lower value (50-100):** Process faster, more frequent commits
- **Higher value (500-1000):** Better throughput, less frequent commits

**Default:** 500

**Our choice:** 500 (default)
- Good balance for 10 msg/sec rate
- Batch size manageable

---

## ConcurrentKafkaListenerContainerFactory Bean

```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, Tick> kafkaListenerContainerFactory() {
    ConcurrentKafkaListenerContainerFactory<String, Tick> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
    
    factory.setConsumerFactory(consumerFactory());
    factory.setConcurrency(3);
    
    return factory;
}
```

**What is this?**
- Factory for creating listener containers
- Manages consumer threads
- Handles message delivery to `@KafkaListener` methods

**Generic types:**
```java
ConcurrentKafkaListenerContainerFactory<String, Tick>
                                        ↑      ↑
                                        │      └─ Value type
                                        └──────── Key type
```

### setConcurrency(3)

```java
factory.setConcurrency(3);
```

**What:** Number of concurrent consumer threads

**Example with 3 threads:**

**Topic has 3 partitions:**
```
Partition 0 → Thread 1
Partition 1 → Thread 2
Partition 2 → Thread 3

Each thread polls independently
```

**Topic has 2 partitions:**
```
Partition 0 → Thread 1
Partition 1 → Thread 2
Thread 3 → Idle (no partition to consume)
```

**Topic has 5 partitions:**
```
Partition 0 → Thread 1
Partition 1 → Thread 2
Partition 2 → Thread 3
Partition 3 → Thread 1 (shares)
Partition 4 → Thread 2 (shares)
```

**Rule:** 
```
Threads = min(concurrency, number of partitions)
```

**Why 3 threads?**
- Our topic has 3 partitions
- 1 thread per partition (optimal)
- Parallel processing

**Trade-off:**
- **More threads:** Better throughput, more CPU
- **Fewer threads:** Less resources, sequential processing

**How it works:**

**Usage in services:**
```java
@Service
public class TickProcessor {
    
    @KafkaListener(topics = "market-data", groupId = "analytics-group")
    public void processTick(Tick tick) {
        System.out.println(Thread.currentThread().getName() + ": " + tick);
    }
}
```

**Output:**
```
consumer-analytics-group-0-C-1: Tick(AAPL, 180.5, ...)
consumer-analytics-group-1-C-1: Tick(GOOGL, 2800.0, ...)
consumer-analytics-group-2-C-1: Tick(MSFT, 340.0, ...)
```

3 different threads processing in parallel.

---

## Consumer Groups Explained

**MOST IMPORTANT KAFKA CONSUMER CONCEPT**

### What is a Consumer Group?

**Consumer Group** = Set of consumers working together to consume a topic

**Identified by:** `group.id` property

**Purpose:** Load balancing and fault tolerance

### Key Rules

**Rule 1:** Each partition assigned to **exactly one consumer** in the group

**Rule 2:** One consumer can handle **multiple partitions**

**Rule 3:** Different consumer groups **independently** consume the topic

### Single Consumer Group

**Topic: 3 partitions, 1 consumer, group: "analytics-group"**

```
Topic: market-data
┌─────────────┐
│ Partition 0 │───┐
├─────────────┤   │
│ Partition 1 │───┼──→ Consumer 1 (analytics-group)
├─────────────┤   │    Reads ALL partitions
│ Partition 2 │───┘
└─────────────┘
```

**Consumer 1 reads from all 3 partitions:**
```
Consumer 1: [msg0, msg1, msg2, msg3, msg4, msg5, msg6, msg7, msg8, msg9, ...]
             ↑ P0   ↑ P1   ↑ P2   ↑ P0   ↑ P1   ↑ P2   ...
```

**Performance:** Sequential processing (single thread)

### Multiple Consumers in One Group

**Topic: 3 partitions, 3 consumers, group: "analytics-group"**

```
Topic: market-data
┌─────────────┐
│ Partition 0 │──────→ Consumer 1 (analytics-group)
├─────────────┤          Reads P0 only
│ Partition 1 │──────→ Consumer 2 (analytics-group)
├─────────────┤          Reads P1 only
│ Partition 2 │──────→ Consumer 3 (analytics-group)
└─────────────┘          Reads P2 only
```

**Load balanced:**
```
Consumer 1: [msg0, msg3, msg6, msg9, ...]   (all from P0)
Consumer 2: [msg1, msg4, msg7, msg10, ...]  (all from P1)
Consumer 3: [msg2, msg5, msg8, msg11, ...]  (all from P2)
```

**Performance:** 3x faster (parallel processing)

**Key point:** Each partition consumed by ONE consumer only

### Too Many Consumers

**Topic: 3 partitions, 5 consumers, group: "analytics-group"**

```
Topic: market-data
┌─────────────┐
│ Partition 0 │──────→ Consumer 1 (analytics-group)
├─────────────┤
│ Partition 1 │──────→ Consumer 2 (analytics-group)
├─────────────┤
│ Partition 2 │──────→ Consumer 3 (analytics-group)
└─────────────┘
                       Consumer 4 (analytics-group) → IDLE
                       Consumer 5 (analytics-group) → IDLE
```

**Consumer 4 & 5:** Idle (no partitions to consume)

**Wasted resources!**

**Rule:** Don't run more consumers than partitions in a group

### Multiple Consumer Groups

**Topic: 3 partitions, 2 groups**

```
Topic: market-data
┌─────────────┐
│ Partition 0 │──┬──→ Consumer A1 (analytics-group)
├─────────────┤  │
│ Partition 1 │──┼──→ Consumer A2 (analytics-group)
├─────────────┤  │
│ Partition 2 │──┼──→ Consumer A3 (analytics-group)
└─────────────┘  │
                 │
                 └──→ Consumer B1 (alerting-group)
                        Reads ALL partitions
```

**BOTH groups read ALL messages independently:**

**analytics-group (3 consumers):**
```
Consumer A1: [msg0, msg3, msg6, ...]   (P0)
Consumer A2: [msg1, msg4, msg7, ...]   (P1)
Consumer A3: [msg2, msg5, msg8, ...]   (P2)
```

**alerting-group (1 consumer):**
```
Consumer B1: [msg0, msg1, msg2, msg3, msg4, msg5, msg6, msg7, msg8, ...]
             (all messages from all partitions)
```

**Key insight:** Different groups track offsets independently

**Use cases:**
- **analytics-group:** Stores data in database
- **alerting-group:** Sends alerts for price spikes

**Both process the same messages!**

### Consumer Group Rebalancing

**What:** Kafka redistributes partitions when consumers join/leave

**Scenario 1: Consumer joins**

**Before (2 consumers):**
```
P0 ──→ Consumer 1
P1 ──→ Consumer 1
P2 ──→ Consumer 2
```

**After Consumer 3 joins:**
```
P0 ──→ Consumer 1
P1 ──→ Consumer 2
P2 ──→ Consumer 3

Rebalanced! More evenly distributed.
```

**Scenario 2: Consumer crashes**

**Before (3 consumers):**
```
P0 ──→ Consumer 1
P1 ──→ Consumer 2
P2 ──→ Consumer 3
```

**After Consumer 2 crashes:**
```
P0 ──→ Consumer 1
P1 ──→ Consumer 1  (reassigned)
P2 ──→ Consumer 3
```

**Fault tolerance:** Remaining consumers pick up the work

**During rebalancing:**
- Brief pause (no messages consumed)
- Offsets committed
- Partitions reassigned
- Consumption resumes

**Duration:** Usually 1-5 seconds

---

## Offset Management

### What is an Offset?

**Offset** = Position in a partition

**Think of it as:** Bookmark in a book

**Each partition has independent offsets:**

```
Partition 0:
Offset:  0     1     2     3     4     5     6     7
Msg:   [msg0][msg1][msg2][msg3][msg4][msg5][msg6][msg7]
        ↑
        Consumer starts here

After reading 3 messages:
Offset:  0     1     2     3     4     5     6     7
Msg:   [msg0][msg1][msg2][msg3][msg4][msg5][msg6][msg7]
                          ↑
                          Consumer is here (offset 3)
```

**Offset = 3** means "next message to read is at position 3"

### Where Offsets Are Stored

**Offsets stored in special Kafka topic:** `__consumer_offsets`

**Structure:**
```
Key:   (group.id, topic, partition)
Value: (offset, metadata)

Example:
Key:   ("analytics-group", "market-data", 0)
Value: (offset: 1500, timestamp: 2024-07-12T10:00:00Z)

Means: analytics-group has read up to offset 1500 in partition 0
```

**Why stored in Kafka?**
- Durable (replicated)
- Kafka manages it (no external database needed)
- Fast access

### Committing Offsets

**Committing** = Saving current position to `__consumer_offsets`

**Two modes:**

#### Auto-commit (our choice)

```java
configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, true);
configProps.put(ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 5000);
```

**How it works:**

```
Time 0ms:   Consume msg (offset 100)
Time 1000ms: Consume msg (offset 101)
Time 2000ms: Consume msg (offset 102)
Time 3000ms: Consume msg (offset 103)
Time 4000ms: Consume msg (offset 104)
Time 5000ms: Auto-commit! Save offset 105 to Kafka
             (105 = next message to read)
```

**Every 5 seconds:**
- Spring commits current offset
- Happens in background

**Pros:**
- Simple (no code needed)
- Automatic

**Cons:**
- Crash before commit = duplicate processing

**Example of duplicate:**
```
Time 0ms:    Last commit: offset 100
Time 1000ms: Consume msg 100
Time 2000ms: Consume msg 101
Time 3000ms: Consume msg 102
Time 4000ms: Consumer crashes!
             (No commit happened yet)

Consumer restarts:
             Reads from offset 100 (last commit)
             Processes 100, 101, 102 again (duplicates!)
```

**For analytics:** Usually acceptable (idempotent processing)

#### Manual commit

```java
configProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
```

```java
@KafkaListener(topics = "market-data")
public void processTick(Tick tick, Acknowledgment acknowledgment) {
    try {
        // Process message
        saveToDB(tick);
        
        // Explicitly commit
        acknowledgment.acknowledge();
    } catch (Exception e) {
        // Don't commit on error
        log.error("Failed to process", e);
    }
}
```

**Pros:**
- Full control (commit only after successful processing)
- No duplicates on crash

**Cons:**
- More complex code
- Must handle exceptions carefully

**When to use:**
- Financial transactions (no duplicates allowed)
- Exactly-once semantics required

**Our project:** Auto-commit (simpler, learning-focused)

### Offset Reset

**When consumer has no committed offset:**

**AUTO_OFFSET_RESET_CONFIG** determines starting point.

**Options:**

**earliest:** Start from beginning
```
Partition: [msg0][msg1][msg2][msg3][msg4][msg5]
            ↑
            Start here
```

**latest:** Start from end
```
Partition: [msg0][msg1][msg2][msg3][msg4][msg5]
                                            ↑
                                            Start here
```

**none:** Throw exception
```
No offset found → Error
(forces you to handle explicitly)
```

**Our choice:** `earliest` (don't miss data)

### Offset Management Flow

**Full lifecycle:**

```
1. Consumer starts
   ├─ Checks __consumer_offsets for (group.id, topic, partition)
   │
   ├─ If offset exists: Resume from that offset
   │  Example: offset 1500 → read from 1500, 1501, 1502, ...
   │
   └─ If no offset: Use AUTO_OFFSET_RESET_CONFIG
      ├─ earliest: Start from 0
      └─ latest: Start from end

2. Consumer reads messages
   ├─ Poll() returns batch of messages
   ├─ Deliver to @KafkaListener method
   └─ Track current offset in memory

3. Auto-commit (every 5 seconds)
   ├─ Commit current offset to __consumer_offsets
   └─ Example: Save (group="analytics-group", topic="market-data", partition=0, offset=1520)

4. Consumer crashes
   └─ Last committed offset persists in Kafka

5. Consumer restarts
   └─ Resume from last committed offset (step 1)
```

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
- Check imports (Cmd+Shift+O in IntelliJ)
- Check Tick class exists in model package
- Verify pom.xml has spring-kafka dependency

### Step 2: Verify Bean Creation

**Temporarily add to `AnalyticsApplication.java`:**

```java
@SpringBootApplication
@EnableKafka
public class AnalyticsApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(AnalyticsApplication.class, args);
        
        // Check if consumer beans exist
        ConsumerFactory<String, Tick> consumerFactory = context.getBean(ConsumerFactory.class);
        System.out.println("✓ ConsumerFactory bean created: " + consumerFactory);
        
        ConcurrentKafkaListenerContainerFactory<String, Tick> factory = 
            context.getBean(ConcurrentKafkaListenerContainerFactory.class);
        System.out.println("✓ KafkaListenerContainerFactory bean created: " + factory);
        
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
✓ ConsumerFactory bean created: org.springframework.kafka.core.DefaultKafkaConsumerFactory@1a2b3c4d
✓ KafkaListenerContainerFactory bean created: org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory@5e6f7g8h
```

**Remove test code after verification.**

### Step 3: Test Actual Consumption

**Create test listener:**

```java
@Service
public class TestListener {
    
    @KafkaListener(topics = "market-data", groupId = "test-group")
    public void testConsume(Tick tick) {
        System.out.println("Received: " + tick);
    }
}
```

**Start consumer:**
```bash
mvn spring-boot:run
```

**In another terminal, produce test message (assuming generator running):**
```bash
# Generator should already be sending messages
# Check Kafka UI: http://localhost:8080
```

**Expected consumer output:**
```
Received: Tick(symbol=AAPL, price=180.5, volume=1000.0, timestamp=2024-07-12T10:00:01Z)
Received: Tick(symbol=GOOGL, price=2800.0, volume=1500.0, timestamp=2024-07-12T10:00:02Z)
...
```

---

## Common Issues

### Issue 1: "Could not resolve placeholder 'spring.kafka.bootstrap-servers'"

**Error:**
```
java.lang.IllegalArgumentException: Could not resolve placeholder 'spring.kafka.bootstrap-servers'
```

**Cause:** `application.yml` missing or wrong property

**Fix:** Verify `src/main/resources/application.yml`:
```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: analytics-group
```

### Issue 2: Connection Refused to localhost:9092

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

### Issue 3: "The class 'Tick' is not in the trusted packages"

**Error:**
```
org.springframework.kafka.support.serializer.DeserializationException: 
The class 'com.quantstream.analytics.model.Tick' is not in the trusted packages
```

**Cause:** JsonDeserializer security check

**Fix 1:** Add trusted package to config:
```java
configProps.put(JsonDeserializer.TRUSTED_PACKAGES, "com.quantstream.analytics.model");
```

**Fix 2:** Use constructor parameter:
```java
new JsonDeserializer<>(Tick.class, false)
```

### Issue 4: Consumer Not Receiving Messages

**Symptom:** Consumer starts, but no messages

**Troubleshooting:**

**1. Check producer is sending:**
```bash
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic market-data \
  --from-beginning
```

Should see messages.

**2. Check consumer group offset:**
```bash
docker exec -it kafka kafka-consumer-groups \
  --bootstrap-server localhost:9092 \
  --describe \
  --group analytics-group
```

**Output:**
```
TOPIC          PARTITION  CURRENT-OFFSET  LAG
market-data    0          1000            0
market-data    1          1050            0
market-data    2          980             0
```

**LAG=0:** Consumer is caught up (good)
**LAG>0:** Consumer is behind (processing old messages)

**3. Check topic has data:**
```bash
docker exec -it kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic market-data
```

**Output:**
```
market-data:0:1000
market-data:1:1050
market-data:2:980
```

Numbers are end offsets. If 0, topic is empty.

**4. Check consumer logs for errors:**
```bash
mvn spring-boot:run | grep ERROR
```

### Issue 5: Consumer Rebalancing Too Often

**Symptom (logs):**
```
Revoking previously assigned partitions
Partitions assigned: [market-data-0, market-data-1]
Revoking previously assigned partitions
Partitions assigned: [market-data-0]
...
```

**Cause:** Consumer processing too slowly (session timeout)

**Fix:** Increase session timeout:
```java
configProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 30000);  // 30 seconds
configProps.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300000);  // 5 minutes
```

**Explanation:**
- **SESSION_TIMEOUT_MS:** How long Kafka waits for heartbeat before considering consumer dead
- **MAX_POLL_INTERVAL_MS:** Maximum time between poll() calls

**If processing takes longer than these timeouts → rebalancing**

### Issue 6: Duplicate Message Processing

**Symptom:** Same message processed multiple times

**Causes:**

**1. Auto-commit + crash:**
- Consumer processes message
- Crashes before auto-commit interval
- Restarts and reprocesses

**Solution:** Accept duplicates OR switch to manual commit

**2. Multiple consumer groups:**
- Both groups process all messages (expected behavior)
- Check group IDs

**3. Rebalancing:**
- During rebalance, offset might not be committed yet
- Duplicate processing of last few messages

**Solution:** Make processing idempotent
```java
@KafkaListener(topics = "market-data")
public void processTick(Tick tick) {
    // Check if already processed
    if (db.exists(tick.getId())) {
        return;  // Skip duplicate
    }
    
    db.save(tick);
}
```

---

## Summary

**KafkaConsumerConfig.java:**
- **@EnableKafka** → Enables `@KafkaListener` processing
- **@Configuration** → Spring creates beans
- **@Value** → Injects bootstrap-servers, group-id from application.yml
- **consumerFactory()** → Creates factory with deserialization config
- **kafkaListenerContainerFactory()** → Creates container for listeners (manages threads)

**Key configurations:**
- **BOOTSTRAP_SERVERS:** `localhost:9092` (where Kafka is)
- **GROUP_ID:** `analytics-group` (consumer group identity) **[CRITICAL]**
- **KEY_DESERIALIZER:** StringDeserializer (bytes → String)
- **VALUE_DESERIALIZER:** JsonDeserializer (bytes → JSON → Tick)
- **TRUSTED_PACKAGES:** Security for deserialization
- **AUTO_OFFSET_RESET:** `earliest` (start from beginning)
- **ENABLE_AUTO_COMMIT:** `true` (automatic offset management)
- **AUTO_COMMIT_INTERVAL_MS:** 5000 (commit every 5 seconds)
- **MAX_POLL_RECORDS:** 500 (batch size)
- **Concurrency:** 3 (number of consumer threads)

**Consumer groups:**
- **One group:** Load balancing (partitions split among consumers)
- **Multiple groups:** Independent consumption (each group reads all messages)
- **Rule:** max consumers = number of partitions (per group)

**Offset management:**
- **Offset:** Position in partition (bookmark)
- **Stored in:** `__consumer_offsets` topic
- **Auto-commit:** Saves offset every 5 seconds
- **On crash:** Resume from last committed offset
- **Duplicate risk:** Messages between last commit and crash

**What you can now do:**
```java
@Service
public class TickProcessor {
    @KafkaListener(topics = "market-data", groupId = "analytics-group")
    public void processTick(Tick tick) {
        // Process tick - Spring handles everything
        System.out.println("Processing: " + tick);
    }
}
```

**Next:** Implement time-series database storage (`guides/consumer-timescale-integration.md`)
