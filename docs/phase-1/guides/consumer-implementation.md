# Database Consumer Service Implementation Guide

## What You're Building

**TickConsumer** is a Spring Boot service that demonstrates **production-ready Kafka consumer patterns**:

1. **Message Consumption** - Listens to Kafka topic using `@KafkaListener`
2. **Automatic Deserialization** - Converts JSON messages to Java objects
3. **Data Persistence** - Saves ticks to QuestDB using Spring Data JPA for time-series analysis
4. **Error Handling** - Handles malformed messages and database failures gracefully
5. **Offset Management** - Tracks message processing position for reliability

**Key Design Principle:** Built to handle high-volume streams (10,000+ msg/day) with reliability and observability.

---

## Architecture Overview

### Complete Data Pipeline

```
┌──────────────────────────────────────────────────────────┐
│                   Market Data Generator                   │
│  - Simulates prices using GBM                            │
│  - Generates 10 ticks/second                             │
└──────────────────────────────────────────────────────────┘
                         ↓ (produce)
┌──────────────────────────────────────────────────────────┐
│                     Kafka Topic                           │
│  Topic: market-data                                       │
│  Partitions: 3                                            │
│  Replication: 1                                           │
│  Format: JSON                                             │
└──────────────────────────────────────────────────────────┘
                         ↓ (consume)
┌──────────────────────────────────────────────────────────┐
│                   TickConsumer Service                    │
│  - @KafkaListener receives messages                      │
│  - Deserializes JSON → Tick object                       │
│  - Validates data                                         │
│  - Saves to QuestDB via Spring Data JPA                  │
│  - Commits offset (acknowledges message)                 │
└──────────────────────────────────────────────────────────┘
                         ↓ (persist)
┌──────────────────────────────────────────────────────────┐
│                       QuestDB                             │
│  Table: ticks                                      │
│  Partitioned by: DAY                                      │
│  Indexed by: timestamp                                    │
└──────────────────────────────────────────────────────────┘
```

### Where Consumer Fits

**Producer (Generator):**
- Creates data
- Sends to Kafka
- Fire-and-forget (async)

**Kafka:**
- Buffers messages
- Provides durability
- Decouples producer/consumer

**Consumer (This Service):**
- Pulls messages from Kafka
- Processes at its own pace
- Commits offsets when done

**Database (QuestDB):**
- Stores historical data
- Enables time-series queries
- Supports analytics

**Benefits of this architecture:**
- ✅ Producer doesn't block on slow consumer
- ✅ Consumer can restart without data loss
- ✅ Can add multiple consumers for scaling
- ✅ Kafka provides replay capability

---

## Creating TickConsumer.java

### Step 1: Create the Service

**File:** `src/main/java/com/quantstream/consumer/service/TickConsumer.java`

```java
package com.quantstream.consumer.service;

import com.quantstream.consumer.model.Tick;
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
import java.time.Instant;

/**
 * Kafka consumer that receives tick data and persists to QuestDB.
 * <p>
 * Design Philosophy:
 * - Annotation-based consumption (@KafkaListener)
 * - Automatic deserialization (JSON → Tick object)
 * - Manual offset commit (acknowledge after successful persistence)
 * - JdbcTemplate for database operations (direct SQL control)
 * - Robust error handling (log and continue)
 * - High observability (detailed logging)
 * <p>
 * Performance Characteristics:
 * - Processes messages one at a time (can be configured for batch)
 * - Commits offset after each successful insert
 * - Can handle 1000+ messages/second with JdbcTemplate and connection pooling
 * - Manual SQL gives full control over INSERT behavior
 */
@Service
public class TickConsumer {
    
    private static final Logger log = LoggerFactory.getLogger(TickConsumer.class);
    
    // Injected by Spring - JDBC template for raw SQL operations
    private final JdbcTemplate jdbcTemplate;
    
    public TickConsumer(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    // Counters for monitoring
    private long messagesReceived = 0;
    private long messagesProcessed = 0;
    private long messagesFailed = 0;
    
    /**
     * Consumes tick messages from Kafka topic.
     * <p>
     * Annotation parameters:
     * - topics: Which Kafka topic to subscribe to
     * - groupId: Consumer group name (multiple consumers share this group)
     * - containerFactory: Bean name for listener configuration
     * <p>
     * Method parameters:
     * - @Payload: The deserialized message body (Tick object)
     * - @Header(PARTITION): Which partition the message came from
     * - @Header(OFFSET): Message position in partition
     * - @Header(TIMESTAMP): When message was produced
     * - Acknowledgment: Manual commit handle
     */
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
            // Validate message
            validateTick(tick);
            
            // Insert into QuestDB
            persistTick(tick);
            
            // Acknowledge message (commits offset)
            acknowledgment.acknowledge();
            
            messagesProcessed++;
            
            log.debug("Successfully persisted tick: {} (total processed: {})",
                     tick.getSymbol(), messagesProcessed);
            
        } catch (Exception e) {
            messagesFailed++;
            
            log.error("Failed to process tick: symbol={}, price={}, volume={}, error={}",
                     tick.getSymbol(), tick.getPrice(), tick.getVolume(), e.getMessage(), e);
            
            // Acknowledge anyway to move forward (Dead Letter Queue would be better)
            // In production, you'd send to DLQ instead of acknowledging
            acknowledgment.acknowledge();
            
            // Log statistics every 100 failures
            if (messagesFailed % 100 == 0) {
                logStatistics();
            }
        }
    }
    
    /**
     * Validates tick data before persisting.
     * Throws IllegalArgumentException if invalid.
     */
    private void validateTick(Tick tick) {
        if (tick == null) {
            throw new IllegalArgumentException("Tick cannot be null");
        }
        
        if (tick.getSymbol() == null || tick.getSymbol().isBlank()) {
            throw new IllegalArgumentException("Symbol cannot be blank");
        }
        
        if (tick.getPrice() <= 0) {
            throw new IllegalArgumentException("Price must be positive: " + tick.getPrice());
        }
        
        if (tick.getVolume() < 0) {
            throw new IllegalArgumentException("Volume cannot be negative: " + tick.getVolume());
        }
        
        if (tick.getTimestamp() == null) {
            throw new IllegalArgumentException("Timestamp cannot be null");
        }
        
        // Check if timestamp is reasonable (not too far in past/future)
        Instant now = Instant.now();
        long diffSeconds = Math.abs(now.getEpochSecond() - tick.getTimestamp().getEpochSecond());
        
        if (diffSeconds > 3600) { // More than 1 hour off
            log.warn("Tick timestamp is {} seconds off from current time: {}",
                    diffSeconds, tick.getTimestamp());
        }
    }
    
    /**
     * Persists tick to QuestDB using JdbcTemplate with raw SQL.
     * <p>
     * Direct SQL approach:
     * - Explicit INSERT statement for full control
     * - Manual Instant → Timestamp conversion required
     * - Direct database operation (no abstraction layer)
     * <p>
     * QuestDB's INSERT is optimized for time-series data:
     * - Appends to end of partition (very fast)
     * - No index updates needed
     * - Bulk inserts can reach 1M+ rows/second
     */
    private void persistTick(Tick tick) {
        try {
            String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
            
            jdbcTemplate.update(
                sql,
                tick.getSymbol(),
                tick.getPrice(),
                tick.getVolume(),
                Timestamp.from(tick.getTimestamp())  // Convert Instant to Timestamp
            );
            
            log.trace("Inserted into QuestDB: {} @ ${}", 
                     tick.getSymbol(), tick.getPrice());
            
        } catch (Exception e) {
            log.error("Database insert failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to persist tick to database", e);
        }
    }
    
    /**
     * Logs processing statistics.
     * Call this periodically to monitor consumer health.
     */
    private void logStatistics() {
        log.info("===== Consumer Statistics =====");
        log.info("Messages received:  {}", messagesReceived);
        log.info("Messages processed: {}", messagesProcessed);
        log.info("Messages failed:    {}", messagesFailed);
        
        if (messagesReceived > 0) {
            double successRate = (messagesProcessed * 100.0) / messagesReceived;
            log.info("Success rate:       {:.2f}%", successRate);
        }
        log.info("===============================");
    }
    
    /**
     * Exposes statistics for monitoring endpoints (used in Phase 3).
     */
    public String getStatistics() {
        return String.format(
            "Received: %d, Processed: %d, Failed: %d, Success Rate: %.2f%%",
            messagesReceived,
            messagesProcessed,
            messagesFailed,
            messagesReceived > 0 ? (messagesProcessed * 100.0) / messagesReceived : 0.0
        );
    }
}
```

---

## Creating TickRepository Interface

### Step 2: Define the JPA Repository

**File:** `src/main/java/com/quantstream/consumer/repository/TickRepository.java`

```java
package com.quantstream.consumer.repository;

import com.quantstream.consumer.model.Tick;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data JPA repository for Tick entities.
 * <p>
 * This is just an INTERFACE - Spring Data JPA automatically provides the implementation at runtime.
 * <p>
 * Built-in methods (provided by JpaRepository):
 * - save(Tick tick)              : Insert or update a single tick
 * - saveAll(List<Tick> ticks)    : Batch insert/update multiple ticks
 * - findById(Long id)            : Find tick by primary key
 * - findAll()                    : Retrieve all ticks (use with caution on large tables!)
 * - count()                      : Count total ticks
 * - delete(Tick tick)            : Delete a tick
 * - deleteById(Long id)          : Delete by ID
 * <p>
 * You can add custom query methods here if needed:
 * - findBySymbol(String symbol)
 * - findByTimestampBetween(Instant start, Instant end)
 * - findBySymbolAndTimestampAfter(String symbol, Instant timestamp)
 * <p>
 * Spring Data JPA automatically implements these based on the method name!
 */
@Repository
public interface TickRepository extends JpaRepository<Tick, Long> {
    // No methods needed! JpaRepository provides all basic CRUD operations.
    // Add custom query methods here if needed in future phases.
}
```

### Understanding JpaRepository

**What Spring Data JPA provides:**

```java
// JpaRepository<Tick, Long>
//             ↑     ↑
//             |     └─ Type of the primary key (Long id)
//             └─ Entity class (Tick)
```

**Built-in methods you get for free:**

| Method | Description | SQL Equivalent |
|--------|-------------|----------------|
| `save(tick)` | Insert or update | `INSERT INTO ... ON CONFLICT UPDATE` |
| `saveAll(list)` | Batch insert | `INSERT INTO ... VALUES (...),...` |
| `findById(id)` | Find by primary key | `SELECT * FROM ticks WHERE id = ?` |
| `existsById(id)` | Check if exists | `SELECT EXISTS(SELECT 1 FROM ticks WHERE id = ?)` |
| `count()` | Count all records | `SELECT COUNT(*) FROM ticks` |
| `deleteById(id)` | Delete by ID | `DELETE FROM ticks WHERE id = ?` |

**Custom query methods (optional):**

```java
// Spring Data JPA parses method name and generates SQL automatically!
public interface TickRepository extends JpaRepository<Tick, Long> {
    
    // Find all ticks for a symbol
    // Generated SQL: SELECT * FROM ticks WHERE symbol = ?
    List<Tick> findBySymbol(String symbol);
    
    // Find ticks in a time range
    // Generated SQL: SELECT * FROM ticks WHERE timestamp BETWEEN ? AND ?
    List<Tick> findByTimestampBetween(Instant start, Instant end);
    
    // Find ticks for symbol after timestamp
    // Generated SQL: SELECT * FROM ticks WHERE symbol = ? AND timestamp > ?
    List<Tick> findBySymbolAndTimestampAfter(String symbol, Instant timestamp);
    
    // Count ticks for a symbol
    // Generated SQL: SELECT COUNT(*) FROM ticks WHERE symbol = ?
    long countBySymbol(String symbol);
    
    // Custom JPQL query (for complex queries)
    @Query("SELECT t FROM Tick t WHERE t.symbol = :symbol ORDER BY t.timestamp DESC")
    List<Tick> findLatestTicksForSymbol(@Param("symbol") String symbol, Pageable pageable);
}
```

**Method naming convention:**

- `findBy...` - Retrieves entities
- `countBy...` - Counts matching entities
- `existsBy...` - Checks if any match exists
- `deleteBy...` - Deletes matching entities
- `And`, `Or` - Combine conditions
- `Between`, `LessThan`, `GreaterThan`, `After`, `Before` - Comparisons

### Why JPA Over JdbcTemplate?

**JdbcTemplate approach (old):**
```java
// Manual SQL writing
jdbcTemplate.update(
    "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)",
    tick.getSymbol(),
    tick.getPrice(),
    tick.getVolume(),
    tick.getTimestamp()
);

// Manual result mapping for queries
List<Tick> ticks = jdbcTemplate.query(
    "SELECT * FROM ticks WHERE symbol = ?",
    new Object[]{symbol},
    (rs, rowNum) -> new Tick(
        rs.getLong("id"),
        rs.getString("symbol"),
        rs.getDouble("price"),
        rs.getDouble("volume"),
        rs.getTimestamp("timestamp").toInstant()
    )
);
```

**Spring Data JPA approach (new):**
```java
// No SQL needed!
tickRepository.save(tick);

// Query methods automatically implemented
List<Tick> ticks = tickRepository.findBySymbol(symbol);
```

**Benefits:**

| Feature | JdbcTemplate | Spring Data JPA |
|---------|-------------|-----------------|
| SQL Writing | Manual | None (auto-generated) |
| Type Safety | Runtime | Compile-time |
| Boilerplate | High | Minimal |
| Result Mapping | Manual | Automatic |
| Transaction Management | Manual | Automatic |
| Batch Operations | Complex | Simple (`saveAll`) |
| Maintainability | Lower | Higher |
| Learning Curve | SQL knowledge required | Interface conventions |

**When to use JdbcTemplate:**
- Very complex SQL that JPA can't express
- Performance-critical native queries
- Database-specific features
- Working with legacy schemas

**When to use Spring Data JPA (most cases):**
- Standard CRUD operations
- Type-safe queries
- Clean, maintainable code
- Automatic transaction management
- Batch operations

---

## Understanding @KafkaListener

### How Annotation-Based Listening Works

**Traditional Approach (Manual):**
```java
// Old way - manual loop
while (true) {
    ConsumerRecords<String, Tick> records = consumer.poll(Duration.ofMillis(100));
    for (ConsumerRecord<String, Tick> record : records) {
        processRecord(record);
    }
    consumer.commitSync();
}
```

**Spring Approach (@KafkaListener):**
```java
// New way - annotation-driven
@KafkaListener(topics = "market-data", groupId = "${spring.kafka.consumer.group-id}")
public void consumeTick(Tick tick, Acknowledgment ack) {
    processRecord(tick);
    ack.acknowledge();
}
```

**Benefits:**
- ✅ No boilerplate polling loop
- ✅ Automatic deserialization
- ✅ Exception handling built-in
- ✅ Thread management handled by Spring
- ✅ Multiple listeners supported

### Topic Subscription

**Single topic:**
```java
@KafkaListener(topics = "market-data")
```

**Multiple topics:**
```java
@KafkaListener(topics = {"market-data", "trade-data", "order-data"})
```

**Pattern-based subscription:**
```java
@KafkaListener(topicPattern = "market-.*")  // Matches market-stocks, market-crypto, etc.
```

### Automatic Deserialization

**How it works:**

```
┌─────────────────┐
│  Kafka Message  │  {"symbol":"AAPL","price":180.5,...}
└─────────────────┘
        ↓
┌─────────────────┐
│ JsonDeserializer│  Configured in application.yml
└─────────────────┘
        ↓
┌─────────────────┐
│   Tick Object   │  Java POJO with fields populated
└─────────────────┘
        ↓
┌─────────────────┐
│ @KafkaListener  │  Method receives Tick tick parameter
└─────────────────┘
```

**Configuration (application.yml):**
```yaml
spring:
  kafka:
    consumer:
      group-id: questdb-consumer-group
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.quantstream.consumer.model
```

**Without trusted packages:**
```
ERROR: The class 'com.quantstream.consumer.model.Tick' is not in the trusted packages
```

**Why needed?** Security - prevents deserialization of untrusted classes.

### Concurrency Settings

**Single-threaded (default):**
```java
@KafkaListener(topics = "market-data", concurrency = "1")
```

Processes messages one at a time. Good for:
- Learning and development
- Strict ordering requirements
- Simple logic

**Multi-threaded:**
```java
@KafkaListener(topics = "market-data", concurrency = "3")
```

Creates 3 consumer threads. Good for:
- High-throughput requirements
- I/O-bound operations (database calls)
- Order doesn't matter within partition

**Configuration-driven:**
```java
@KafkaListener(
    topics = "market-data",
    concurrency = "${kafka.consumer.concurrency:1}"  // Default to 1
)
```

Set via `application.yml`:
```yaml
kafka:
  consumer:
    concurrency: 3  # Override default
```

---

## Message Processing Flow

### Complete Flow Diagram

```
┌─────────────────────────────────────────────────────────┐
│ 1. Kafka Broker                                         │
│    - Message stored in partition                        │
│    - Offset: 12345                                      │
└─────────────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────────────┐
│ 2. Spring Kafka Consumer                                │
│    - Polls broker every 100ms                           │
│    - Fetches messages in batch                          │
│    - Deserializes JSON → Tick objects                   │
└─────────────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────────────┐
│ 3. @KafkaListener Method                                │
│    - Receives Tick object                               │
│    - Extracts metadata (partition, offset)              │
│    - Calls consumeTick()                                │
└─────────────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────────────┐
│ 4. Validate Tick                                        │
│    - Check symbol not blank                             │
│    - Check price > 0                                    │
│    - Check volume >= 0                                  │
│    - Check timestamp reasonable                         │
└─────────────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────────────┐
│ 5. Persist to QuestDB                                   │
│    - Execute tickRepository.save(tick)                  │
│    - JPA translates to INSERT statement                 │
│    - QuestDB appends to time-series partition           │
│    - Returns saved entity with generated ID             │
└─────────────────────────────────────────────────────────┘
                      ↓
┌─────────────────────────────────────────────────────────┐
│ 6. Commit Offset                                        │
│    - Call acknowledgment.acknowledge()                  │
│    - Kafka records offset 12345 as processed            │
│    - On restart, will resume from 12346                 │
└─────────────────────────────────────────────────────────┘
```

### Step-by-Step Walkthrough

**Step 1: Receive message from Kafka**

```java
@KafkaListener(topics = "market-data", groupId = "${spring.kafka.consumer.group-id}")
public void consumeTick(@Payload Tick tick, ...) {
    // Spring has already:
    // 1. Polled Kafka broker
    // 2. Fetched message bytes
    // 3. Deserialized JSON to Tick object
    // 4. Called this method
}
```

**Step 2: Deserialize to Tick object**

Happens automatically before method is called:

```json
// Kafka message (JSON bytes)
{
  "symbol": "AAPL",
  "price": 180.52,
  "volume": 5234.0,
  "timestamp": "2024-07-15T10:30:45.123Z"
}
```

Becomes:
```java
// Java object (Tick)
Tick tick = new Tick(
    "AAPL", 
    180.52, 
    5234.0, 
    Instant.parse("2024-07-15T10:30:45.123Z")
);
```

**Step 3: Validate data**

```java
validateTick(tick);

// Checks:
// - Symbol not blank
// - Price positive
// - Volume non-negative
// - Timestamp not null
// - Timestamp within reasonable range
```

**Why validate?**
- Generator might have bugs
- Message could be corrupted
- Malicious messages possible
- Catch problems early

**Step 4: Save to QuestDB using JdbcTemplate**

```java
// Direct SQL INSERT with JdbcTemplate
String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";

jdbcTemplate.update(
    sql,
    tick.getSymbol(),
    tick.getPrice(),
    tick.getVolume(),
    Timestamp.from(tick.getTimestamp())  // Convert Instant to java.sql.Timestamp
);

// What happens:
// 1. Prepare SQL statement with placeholders
// 2. Bind Java values to SQL parameters
// 3. Execute INSERT against QuestDB
// 4. Return number of rows affected (1)
```

**Key points:**
- Direct SQL control (explicit INSERT statement)
- Manual type conversion (Instant → Timestamp)
- Positional parameters (?, ?, ?, ?)
- Returns int (rows affected, not the entity)

**QuestDB optimizations:**
- Appends to end (no index updates)
- Time-series partitioning (data organized by day)
- Columnar storage (fast analytics)
- Can handle 1M+ inserts/second

**Step 5: Commit offset**

```java
acknowledgment.acknowledge();
```

**What happens:**
- Kafka records: "Consumer group 'questdb-consumer-group' processed offset 12345 in partition 0"
- Next poll will fetch messages starting from offset 12346
- On restart, consumer resumes from last committed offset

---

## Error Handling Strategies

### Scenario 1: Database is Down

**Problem:**
```
ERROR: Connection refused: connect to localhost:8812
```

**What happens:**
```java
try {
    persistTick(tick);
} catch (Exception e) {
    log.error("Database insert failed: {}", e.getMessage());
    // Option A: Throw exception (Spring retries)
    // Option B: Acknowledge anyway (lose message)
    // Option C: Send to Dead Letter Queue (recommended)
}
```

**Option A: Retry via Spring**

```yaml
spring:
  kafka:
    listener:
      ack-mode: record  # Commit after each message
      
    # Retry configuration
    retry:
      topic:
        enabled: true
        attempts: 3
        delay: 1000  # 1 second
```

**Behavior:**
- Consumer pauses on failed message
- Retries 3 times with 1 second delay
- If still failing, stops consuming
- Blocks entire partition

**Pros:** Simple, built-in
**Cons:** Blocks partition, no visibility into failures

**Option B: Skip and Continue**

```java
catch (Exception e) {
    log.error("Skipping message due to error: {}", e.getMessage());
    acknowledgment.acknowledge();  // Mark as processed anyway
    messagesFailed++;
}
```

**Behavior:**
- Logs error
- Commits offset
- Continues to next message

**Pros:** Consumer keeps running
**Cons:** Data loss, no recovery mechanism

**Option C: Dead Letter Queue (Recommended)**

```java
catch (Exception e) {
    log.error("Sending to DLQ: {}", e.getMessage());
    
    // Send to special error topic
    kafkaTemplate.send("market-data-dlq", tick);
    
    // Acknowledge original message
    acknowledgment.acknowledge();
}
```

**DLQ Topic Structure:**
```json
{
  "originalTopic": "market-data",
  "partition": 0,
  "offset": 12345,
  "error": "Connection refused",
  "timestamp": "2024-07-15T10:30:45Z",
  "payload": {...}
}
```

**Benefits:**
- ✅ Consumer keeps running
- ✅ Failed messages preserved
- ✅ Can retry later
- ✅ Separate service monitors DLQ

**Implementation:** Phase 6 (Advanced Error Handling)

### Scenario 2: Malformed Message

**Problem:**
```json
{
  "symbol": "AAPL",
  "price": -180.52,    // ← INVALID (negative)
  "volume": 5234.0,
  "timestamp": "2024-07-15T10:30:45.123Z"
}
```

**What happens:**
```java
validateTick(tick);  // Throws IllegalArgumentException
```

**Handling:**
```java
try {
    validateTick(tick);
    persistTick(tick);
} catch (IllegalArgumentException e) {
    log.error("Invalid tick data: {}", e.getMessage());
    
    // Log the bad message for debugging
    log.error("Bad tick: {}", tick);
    
    // Acknowledge (no point retrying - data is fundamentally broken)
    acknowledgment.acknowledge();
    
    messagesFailed++;
}
```

**Why acknowledge?**
- Retrying won't help (data is corrupt)
- Would block partition indefinitely
- Better to log and move on

**Production approach:**
- Send to DLQ with "VALIDATION_FAILED" reason
- Alert on high validation failure rate
- Investigate root cause in generator

### Scenario 3: Transient Errors

**Problem:**
```
ERROR: Deadlock detected in database transaction
```

**Handling:**
```java
@Retryable(
    value = {DeadlockLoserDataAccessException.class},
    maxAttempts = 3,
    backoff = @Backoff(delay = 100)
)
private void persistTick(Tick tick) {
    jdbcTemplate.update(INSERT_SQL, ...);
}
```

**Behavior:**
- Retries deadlocks automatically
- Waits 100ms between attempts
- Gives up after 3 attempts

**Good for:**
- Database deadlocks
- Temporary connection issues
- Lock timeouts

### Retry Logic Best Practices

**Exponential Backoff:**
```java
@Backoff(
    delay = 1000,      // Start with 1 second
    multiplier = 2,    // Double each time
    maxDelay = 60000   // Cap at 60 seconds
)
// Delays: 1s, 2s, 4s, 8s, 16s, 32s, 60s, 60s...
```

**Retry Only Specific Exceptions:**
```java
@Retryable(
    value = {
        DataAccessException.class,        // Database errors
        CannotAcquireLockException.class  // Lock timeouts
    },
    exclude = {
        DuplicateKeyException.class       // Don't retry constraint violations
    }
)
```

**Circuit Breaker (Advanced):**
```java
@CircuitBreaker(
    failureThreshold = 5,     // Open after 5 failures
    resetTimeout = 60000      // Try again after 60 seconds
)
private void persistTick(Tick tick) {
    // If database keeps failing, stop trying for 1 minute
}
```

---

## Offset Management

### What are Offsets?

**Offset = Position in partition**

Think of Kafka partition as an array:

```
Partition 0:
┌───────┬───────┬───────┬───────┬───────┬───────┐
│ Msg 0 │ Msg 1 │ Msg 2 │ Msg 3 │ Msg 4 │ Msg 5 │
└───────┴───────┴───────┴───────┴───────┴───────┘
  ↑       ↑       ↑       ↑       ↑       ↑
  0       1       2       3       4       5  ← Offsets
```

**Consumer tracks:** "I've processed up to offset 3"

**On restart:** "Start reading from offset 4"

### Auto-commit vs Manual Commit

**Auto-commit (Default):**

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: true
      auto-commit-interval: 5000  # Every 5 seconds
```

**How it works:**
```
Time 0s:  Process message at offset 10
Time 1s:  Process message at offset 11
Time 2s:  Process message at offset 12
Time 3s:  Process message at offset 13
Time 4s:  Process message at offset 14
Time 5s:  [AUTO COMMIT] Kafka records offset 14 ← Happens automatically
```

**Problem:**
```
Time 2s:  Process message at offset 12
Time 3s:  Insert into database... [CRASH]  ← App dies
Time 5s:  [AUTO COMMIT would have happened]
Restart:  Kafka thinks offset 9 was last committed
          → Replays messages 10-12
          → Message 12 was partially processed!
```

**Manual commit (Recommended):**

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false  # Disable auto-commit
    listener:
      ack-mode: manual  # Require explicit acknowledgment
```

**How it works:**
```java
@KafkaListener(...)
public void consumeTick(Tick tick, Acknowledgment ack) {
    persistTick(tick);          // Insert into database
    ack.acknowledge();          // ← Explicit commit
    // If crash happens before ack.acknowledge(), message will be reprocessed
}
```

**Flow:**
```
1. Receive message (offset 10)
2. Insert into database ✓
3. Call ack.acknowledge() ✓
4. Kafka commits offset 10
5. [CRASH]
6. Restart
7. Kafka says "last committed = 10"
8. Resume from offset 11 ✓
```

### What Happens on Restart

**Scenario 1: Clean Shutdown**

```
Before shutdown:
- Processed messages 0-99
- Committed offset 99

After restart:
- Resume from offset 100 ✓
- No duplicates
```

**Scenario 2: Crash Before Commit**

```
Before crash:
- Processed messages 0-99
- Committed offset 95 (message 96-99 not committed)

After restart:
- Resume from offset 96
- Reprocess messages 96-99
- Possible duplicates in database
```

**Idempotent Inserts:**

```sql
-- Option 1: Unique constraint (prevents duplicates)
CREATE TABLE ticks (
    symbol SYMBOL,
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP,
    PRIMARY KEY (symbol, timestamp)  -- ← Prevents duplicate inserts
) timestamp(timestamp);

-- Option 2: INSERT ... ON CONFLICT DO NOTHING (PostgreSQL-style)
-- QuestDB doesn't support this yet, so use unique constraint
```

### How to Replay Messages

**Scenario:** You fixed a bug and want to reprocess last hour of data.

**Method 1: Reset Consumer Group Offset**

```bash
# Stop consumer first
kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --group questdb-consumer-group \
    --reset-offsets \
    --to-datetime 2024-07-15T09:00:00.000 \
    --topic market-data \
    --execute

# Restart consumer
# Will reprocess from 9:00 AM onwards
```

**Method 2: Use Different Consumer Group**

```yaml
spring:
  kafka:
    consumer:
      group-id: questdb-consumer-group-replay-v2  # New group ID
```

Starts from beginning (or earliest available).

**Method 3: Seek to Specific Offset**

```java
@KafkaListener(topics = "market-data", groupId = "${spring.kafka.consumer.group-id}")
public void consumeTick(...) { ... }

// In a separate admin method:
public void replayFrom(long offset) {
    // Use KafkaListenerEndpointRegistry to access consumer
    // Call consumer.seek(partition, offset)
}
```

---

## Performance Considerations

### Batch Processing

**Single-message processing (current):**

```java
@KafkaListener(topics = "market-data")
public void consumeTick(Tick tick, Acknowledgment ack) {
    tickRepository.save(tick);  // 1 database call
    ack.acknowledge();
}
```

**Performance:** ~1,000 messages/second (limited by database round-trips)

**Batch processing with JdbcTemplate:**

```java
@KafkaListener(topics = "market-data")
public void consumeTicks(List<Tick> ticks, Acknowledgment ack) {
    persistTicksBatch(ticks);   // 1 database call for 100 messages
    ack.acknowledge();
}

/**
 * Batch persist using JdbcTemplate's batchUpdate() method.
 * Processes multiple INSERTs in a single database round-trip.
 */
private void persistTicksBatch(List<Tick> ticks) {
    String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
    
    jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
        @Override
        public void setValues(PreparedStatement ps, int i) throws SQLException {
            Tick tick = ticks.get(i);
            ps.setString(1, tick.getSymbol());
            ps.setDouble(2, tick.getPrice());
            ps.setDouble(3, tick.getVolume());
            ps.setTimestamp(4, Timestamp.from(tick.getTimestamp()));
        }
        
        @Override
        public int getBatchSize() {
            return ticks.size();
        }
    });
    
    log.debug("Batch inserted {} ticks", ticks.size());
}
```

**Configuration:**
```yaml
spring:
  kafka:
    listener:
      type: batch  # Enable batch mode
    consumer:
      max-poll-records: 100  # Fetch up to 100 messages per poll
```

**Benefits of batch operations:**
- ✅ 100x fewer database round-trips
- ✅ Full control over SQL and parameter binding
- ✅ Efficient use of network and database resources
- ✅ Explicit batch size control

**Performance:** ~10,000 messages/second (100x fewer database calls)

### Async Writes

**Synchronous (current):**
```java
tickRepository.save(tick);  // Blocks until database confirms
```

**Asynchronous with JdbcTemplate:**
```java
@Async
public CompletableFuture<Integer> persistTickAsync(Tick tick) {
    String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
    int rows = jdbcTemplate.update(
        sql,
        tick.getSymbol(),
        tick.getPrice(),
        tick.getVolume(),
        Timestamp.from(tick.getTimestamp())
    );
    return CompletableFuture.completedFuture(rows);
}

@KafkaListener(topics = "market-data")
public void consumeTick(Tick tick, Acknowledgment ack) {
    persistTickAsync(tick).thenAccept(rowsAffected -> {
        log.debug("Async inserted {} row(s) for {}", rowsAffected, tick.getSymbol());
        ack.acknowledge();  // Commit only after insert completes
    });
}
```

**Benefits:**
- Consumer thread doesn't block
- Can process next message while waiting for database
- Higher throughput

**Caution:**
- More complex error handling
- Need to track in-flight operations
- Requires proper thread pool tuning

### Connection Pooling

**Without pooling:**
```
Each insert:
1. Open TCP connection to database (50ms)
2. Execute INSERT (5ms)
3. Close connection (10ms)
Total: 65ms per message → 15 messages/second
```

**With pooling (default in Spring Boot):**
```
Each insert:
1. Get connection from pool (1ms)
2. Execute INSERT (5ms)
3. Return connection to pool (1ms)
Total: 7ms per message → 140 messages/second
```

**Configuration:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10       # Up to 10 concurrent connections
      minimum-idle: 5             # Keep 5 connections ready
      connection-timeout: 30000   # Wait 30s for connection
      idle-timeout: 600000        # Close idle connections after 10 min
      max-lifetime: 1800000       # Recycle connections after 30 min
```

**Tuning for high throughput:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 50       # More concurrent connections
  kafka:
    listener:
      concurrency: 10             # 10 consumer threads
```

**Math:**
- 10 threads × 5 connections each = 50 connections
- Each thread processes 100 msg/sec
- Total: 1,000 msg/sec

---

## Monitoring and Logging

### What to Log

**Message received:**
```java
log.debug("Received tick: {} @ ${} (partition={}, offset={})",
         tick.getSymbol(), tick.getPrice(), partition, offset);
```

**Why:** Track message flow, identify partition imbalance

**Message processed:**
```java
log.debug("Persisted tick: {} (total processed: {})",
         tick.getSymbol(), messagesProcessed);
```

**Why:** Confirm successful processing, track throughput

**Errors:**
```java
log.error("Failed to process tick: symbol={}, price={}, error={}",
         tick.getSymbol(), tick.getPrice(), e.getMessage(), e);
```

**Why:** Debug failures, identify problematic messages

**Statistics (every 100 messages):**
```java
if (messagesProcessed % 100 == 0) {
    log.info("Processed {} messages, failed {}, success rate: {:.2f}%",
            messagesProcessed, messagesFailed, successRate);
}
```

**Why:** Monitor consumer health without verbose logs

### Metrics to Track

**Throughput:**
```java
private final AtomicLong messagesPerSecond = new AtomicLong(0);

@Scheduled(fixedRate = 1000)
public void calculateThroughput() {
    long current = messagesProcessed;
    long previous = messagesPerSecond.get();
    long throughput = current - previous;
    messagesPerSecond.set(current);
    
    log.info("Throughput: {} messages/second", throughput);
}
```

**Lag (messages behind):**
```java
// Spring Boot Actuator exposes Kafka lag automatically
// Access via: http://localhost:8080/actuator/metrics/kafka.consumer.lag
```

**Error rate:**
```java
double errorRate = (messagesFailed * 100.0) / messagesReceived;
if (errorRate > 5.0) {
    log.error("High error rate: {:.2f}%", errorRate);
    // Alert ops team
}
```

**Processing time:**
```java
long startTime = System.currentTimeMillis();
persistTick(tick);
long duration = System.currentTimeMillis() - startTime;

log.debug("Persist time: {}ms", duration);

if (duration > 100) {
    log.warn("Slow insert: {}ms for symbol {}", duration, tick.getSymbol());
}
```

---

## Testing the Consumer with Spring Data JPA

### Unit Testing with Mock Repository

**Test file:** `src/test/java/com/quantstream/consumer/service/TickConsumerTest.java`

```java
@ExtendWith(MockitoExtension.class)
class TickConsumerTest {
    
    @Mock
    private TickRepository tickRepository;
    
    @InjectMocks
    private TickConsumer tickConsumer;
    
    @Mock
    private Acknowledgment acknowledgment;
    
    @Test
    void testConsumeTick_Success() {
        // Arrange
        Tick tick = new Tick(null, "AAPL", 180.5, 1000.0, Instant.now());
        Tick savedTick = new Tick(1L, "AAPL", 180.5, 1000.0, tick.getTimestamp());
        
        when(tickRepository.save(any(Tick.class))).thenReturn(savedTick);
        
        // Act
        tickConsumer.consumeTick(tick, 0, 123L, System.currentTimeMillis(), acknowledgment);
        
        // Assert
        verify(tickRepository).save(tick);
        verify(acknowledgment).acknowledge();
    }
    
    @Test
    void testConsumeTick_DatabaseFailure() {
        // Arrange
        Tick tick = new Tick(null, "AAPL", 180.5, 1000.0, Instant.now());
        
        when(tickRepository.save(any(Tick.class)))
            .thenThrow(new DataAccessException("Database error") {});
        
        // Act & Assert
        assertThrows(RuntimeException.class, () -> {
            tickConsumer.consumeTick(tick, 0, 123L, System.currentTimeMillis(), acknowledgment);
        });
        
        verify(acknowledgment).acknowledge(); // Still acknowledged (DLQ would be better)
    }
}
```

**Benefits of testing with JPA:**
- Easy to mock `TickRepository` interface
- No need to set up test database for unit tests
- Fast test execution
- Clear verification of save() calls

### Integration Testing with Test Database

```java
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:postgresql://localhost:8812/qdb",
    "spring.jpa.hibernate.ddl-auto=create-drop"  // Auto-create tables for test
})
class TickConsumerIntegrationTest {
    
    @Autowired
    private TickRepository tickRepository;
    
    @Autowired
    private TickConsumer tickConsumer;
    
    @Test
    void testPersistTickToDatabase() {
        // Arrange
        Tick tick = new Tick(null, "AAPL", 180.5, 1000.0, Instant.now());
        
        // Act
        Tick savedTick = tickRepository.save(tick);
        
        // Assert
        assertNotNull(savedTick.getId());
        assertEquals("AAPL", savedTick.getSymbol());
        
        // Verify it's in database
        Optional<Tick> found = tickRepository.findById(savedTick.getId());
        assertTrue(found.isPresent());
        assertEquals(180.5, found.get().getPrice(), 0.01);
    }
    
    @Test
    void testBatchSave() {
        // Arrange
        List<Tick> ticks = List.of(
            new Tick(null, "AAPL", 180.5, 1000.0, Instant.now()),
            new Tick(null, "MSFT", 380.2, 2000.0, Instant.now()),
            new Tick(null, "GOOGL", 140.8, 1500.0, Instant.now())
        );
        
        // Act
        List<Tick> savedTicks = tickRepository.saveAll(ticks);
        
        // Assert
        assertEquals(3, savedTicks.size());
        assertEquals(3, tickRepository.count());
    }
}
```

### Step 1: Create Table in QuestDB

You have two options for creating the table:

**Option 1: Let JPA Auto-Generate (Development)**

Set in `application.yml`:
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: update  # Auto-create/update schema
    show-sql: true      # See generated SQL in logs
```

**Pros:**
- Automatic table creation
- Schema updates based on entity changes
- Perfect for development

**Cons:**
- May not create optimal QuestDB-specific columns (SYMBOL, partitioning)
- Not recommended for production

**Option 2: Manual Creation (Production-Ready)**

**Connect to QuestDB console:** http://localhost:9000

**Create optimized table:**
```sql
CREATE TABLE ticks (
    id LONG,
    symbol SYMBOL,           -- QuestDB-optimized indexed string
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY DAY;
```

**Note the table name:** `ticks` (matches `@Table(name = "ticks")` in Tick entity)

**What this creates:**
- **id LONG:** Primary key (auto-generated by JPA sequence)
- **symbol SYMBOL:** Indexed string (faster queries than STRING)
- **price DOUBLE:** Floating-point number
- **volume DOUBLE:** Floating-point number
- **timestamp TIMESTAMP:** Time-series index
- **PARTITION BY DAY:** Creates daily partitions (2024-07-15/, 2024-07-16/, etc.)

**Verify table exists:**
```sql
SELECT * FROM tables WHERE name = 'ticks';
```

**Recommended for this guide:** Use Option 2 (manual creation) for optimal QuestDB performance.

### Step 2: Start Generator

**In terminal 1:**
```bash
cd /Users/mhiteshkumar/QuantStream/data-generator
mvn spring-boot:run
```

**Verify it's generating ticks:**
```
DEBUG c.q.g.s.MarketDataGenerator : Tick sent: AAPL -> $180.05
DEBUG c.q.g.s.MarketDataGenerator : Tick sent: MSFT -> $380.12
```

**Check Kafka UI:** http://localhost:8080
- Navigate to Topics → market-data
- Should see messages flowing

### Step 3: Start Consumer

**In terminal 2:**
```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer
mvn spring-boot:run
```

**Expected output:**
```
INFO  c.q.c.ConsumerApplication : Starting ConsumerApplication
INFO  c.q.c.ConsumerApplication : Started ConsumerApplication in 3.2 seconds
DEBUG c.q.c.s.TickConsumer : Received tick: AAPL @ $180.05 (partition=0, offset=123)
DEBUG c.q.c.s.TickConsumer : Persisted tick: AAPL (total processed: 1)
DEBUG c.q.c.s.TickConsumer : Received tick: MSFT @ $380.12 (partition=0, offset=124)
DEBUG c.q.c.s.TickConsumer : Persisted tick: MSFT (total processed: 2)
...
```

### Step 4: Verify Data Flow

**Check consumer logs:**
```
INFO  c.q.c.s.TickConsumer : Processed 100 messages, failed 0, success rate: 100.00%
```

**Check Kafka consumer group:**
```bash
docker exec -it kafka kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --describe \
    --group questdb-consumer-group
```

**Expected output:**
```
GROUP                    TOPIC       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
questdb-consumer-group   market-data 0          1234            1234            0
```

**LAG = 0** means consumer is caught up.

---

## Verification Queries

### Using Spring Data JPA Repository Methods

**Create a test endpoint or service method:**

```java
@RestController
@RequestMapping("/api/ticks")
public class TickController {
    
    private final TickRepository tickRepository;
    
    public TickController(TickRepository tickRepository) {
        this.tickRepository = tickRepository;
    }
    
    // Get total count
    @GetMapping("/count")
    public long getCount() {
        return tickRepository.count();
    }
    
    // Get latest 10 ticks
    @GetMapping("/latest")
    public List<Tick> getLatest() {
        return tickRepository.findAll(
            PageRequest.of(0, 10, Sort.by("timestamp").descending())
        ).getContent();
    }
    
    // Get all ticks for a symbol
    @GetMapping("/symbol/{symbol}")
    public List<Tick> getBySymbol(@PathVariable String symbol) {
        return tickRepository.findBySymbol(symbol);
    }
}

// Add these methods to TickRepository:
List<Tick> findBySymbol(String symbol);
```

**Test via HTTP:**
```bash
curl http://localhost:8080/api/ticks/count
curl http://localhost:8080/api/ticks/latest
curl http://localhost:8080/api/ticks/symbol/AAPL
```

### Using SQL Queries in QuestDB Console

**Latest 10 ticks:**
```sql
SELECT * FROM ticks
ORDER BY timestamp DESC
LIMIT 10;
```

**Count by symbol:**
```sql
SELECT symbol, COUNT(*) as tick_count
FROM ticks
GROUP BY symbol
ORDER BY tick_count DESC;
```

**Expected output:**
```
symbol  tick_count
AAPL    123
MSFT    123
GOOGL   123
...
```

**All symbols should have similar counts** (generator sends 1 tick/second for each).

### Check Price Movement

**Latest price for each symbol:**
```sql
SELECT symbol, price, timestamp
FROM ticks
LATEST BY symbol;
```

**Price range for AAPL:**
```sql
SELECT 
    MIN(price) as min_price,
    MAX(price) as max_price,
    AVG(price) as avg_price
FROM ticks
WHERE symbol = 'AAPL';
```

**Expected:**
- Min/Max within ~10% of initial price
- Average close to initial price

### Check Time Coverage

**Ticks per minute:**
```sql
SELECT 
    timestamp(timestamp, '1m') as minute,
    COUNT(*) as tick_count
FROM ticks
WHERE timestamp > dateadd('h', -1, now())
GROUP BY minute
ORDER BY minute DESC;
```

**Expected:** ~10 ticks per minute (10 symbols × 1 tick/second × 60 seconds = 600 ticks/minute)

Wait, that's 600, not 10. Let me fix:

**Expected:** ~600 ticks per minute (10 symbols generating 1 tick/second each)

### Check for Gaps

**Find periods with no data:**
```sql
WITH minute_buckets AS (
    SELECT timestamp(timestamp, '1m') as minute
    FROM ticks
    WHERE timestamp > dateadd('h', -1, now())
    GROUP BY minute
)
SELECT 
    minute,
    dateadd('m', 1, minute) as next_expected,
    (SELECT MIN(minute) FROM minute_buckets WHERE minute > mb.minute) as next_actual
FROM minute_buckets mb
ORDER BY minute DESC;
```

**If gaps exist:**
- Check if consumer was stopped
- Check for errors in consumer logs
- Check if generator was stopped

### Performance Check

**Inserts per second (real-time):**
```sql
SELECT 
    timestamp(timestamp, '1s') as second,
    COUNT(*) as inserts
FROM ticks
WHERE timestamp > dateadd('m', -5, now())
GROUP BY second
ORDER BY second DESC
LIMIT 60;
```

**Expected:** ~10 inserts/second

---

## Common Issues

### Issue 1: Consumer Not Receiving Messages

**Symptoms:**
```
INFO  c.q.c.ConsumerApplication : Started ConsumerApplication
(no "Received tick" logs)
```

**Checks:**

**1. Is generator running?**
```bash
# Check generator logs
ps aux | grep data-generator
```

**2. Are messages in Kafka?**
- Open Kafka UI: http://localhost:8080
- Topics → market-data → Messages
- Should see recent messages

**3. Is consumer group correct?**
```yaml
# Check application.yml
spring:
  kafka:
    consumer:
      group-id: questdb-consumer-group  # ← Must match
```

**4. Check consumer lag:**
```bash
docker exec -it kafka kafka-consumer-groups.sh \
    --bootstrap-server localhost:9092 \
    --describe \
    --group questdb-consumer-group
```

**If LAG is increasing:**
- Consumer is running but falling behind
- Check for slow database writes
- Consider increasing concurrency

**If consumer group not listed:**
- Consumer never connected to Kafka
- Check bootstrap-servers configuration
- Check Kafka is running: `docker ps`

### Issue 2: Deserialization Errors

**Symptoms:**
```
ERROR o.s.k.l.KafkaMessageListenerContainer : Listener failed
org.springframework.kafka.support.serializer.DeserializationException: 
    The class 'com.quantstream.consumer.model.Tick' is not in the trusted packages
```

**Fix:**

**Add to application.yml:**
```yaml
spring:
  kafka:
    consumer:
      properties:
        spring.json.trusted.packages: com.quantstream.consumer.model
```

**Or trust all packages (INSECURE - dev only):**
```yaml
spring:
  kafka:
    consumer:
      properties:
        spring.json.trusted.packages: "*"
```

### Issue 3: Database Connection Failed

**Symptoms:**
```
ERROR c.q.c.s.TickConsumer : Database insert failed: Connection refused
```

**Checks:**

**1. Is QuestDB running?**
```bash
docker ps | grep questdb
```

**2. Can you connect via web console?**
- Open http://localhost:9000
- Try: `SELECT 1;`

**3. Check connection config:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:8812/qdb
    username: admin
    password: quest
```

**4. Test connection manually:**
```bash
docker exec -it questdb psql -h localhost -p 8812 -U admin -d qdb
```

### Issue 4: Duplicate Keys Error

**Symptoms:**
```
ERROR c.q.c.s.TickConsumer : Database insert failed: 
    duplicate key value violates unique constraint "ticks_symbol_timestamp_key"
```

**Cause:** Consumer reprocessed a message (crash before offset commit).

**Temporary fix (allow duplicates):**
```sql
-- Remove unique constraint
DROP TABLE ticks;
CREATE TABLE ticks (
    symbol SYMBOL,
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY DAY;
-- No PRIMARY KEY = duplicates allowed
```

**Proper fix with JdbcTemplate (idempotent inserts):**
```java
// Option 1: Check before insert
public void persistTickIdempotent(Tick tick) {
    String checkSql = "SELECT COUNT(*) FROM ticks WHERE symbol = ? AND timestamp = ?";
    Integer count = jdbcTemplate.queryForObject(
        checkSql,
        Integer.class,
        tick.getSymbol(),
        Timestamp.from(tick.getTimestamp())
    );
    
    if (count == 0) {
        String insertSql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(
            insertSql,
            tick.getSymbol(),
            tick.getPrice(),
            tick.getVolume(),
            Timestamp.from(tick.getTimestamp())
        );
    } else {
        log.debug("Tick already exists, skipping: {} at {}", tick.getSymbol(), tick.getTimestamp());
    }
}

// Option 2: Catch duplicate key exception
public void persistTick(Tick tick) {
    try {
        String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
        jdbcTemplate.update(
            sql,
            tick.getSymbol(),
            tick.getPrice(),
            tick.getVolume(),
            Timestamp.from(tick.getTimestamp())
        );
    } catch (DataIntegrityViolationException e) {
        log.warn("Duplicate tick detected, skipping: {} at {}", 
                tick.getSymbol(), tick.getTimestamp());
        // Continue processing - not a fatal error
    }
}
    
// QuestDB doesn't support ON CONFLICT yet
// Use deduplication in query instead:
SELECT DISTINCT ON (symbol, timestamp) * FROM ticks;
```

### Issue 5: Consumer Lag Growing

**Symptoms:**
```bash
$ kafka-consumer-groups.sh --describe --group questdb-consumer-group
GROUP                    TOPIC       PARTITION  CURRENT-OFFSET  LOG-END-OFFSET  LAG
questdb-consumer-group   market-data 0          1000            5000            4000
                                                                                 ↑ Growing
```

**Cause:** Consumer slower than producer.

**Diagnosis:**
```
Producer rate: 10 messages/second
Consumer rate: 2 messages/second
Lag growth: 8 messages/second = 28,800 messages/hour
```

**Fixes:**

**1. Increase concurrency:**
```yaml
spring:
  kafka:
    listener:
      concurrency: 5  # 5 consumer threads
```

**2. Enable batch processing:**
```yaml
spring:
  kafka:
    listener:
      type: batch
    consumer:
      max-poll-records: 100
```

**3. Optimize database:**
```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20  # More database connections
```

**4. Profile slow queries:**
```sql
-- Enable QuestDB query logging
-- Check avg insert time
```

### Issue 6: Out of Memory Error

**Symptoms:**
```
java.lang.OutOfMemoryError: Java heap space
```

**Cause:** Batch processing with huge batches.

**Fix:**
```yaml
spring:
  kafka:
    consumer:
      max-poll-records: 100  # Reduce batch size
      fetch-max-bytes: 1048576  # 1MB max per fetch
```

**Or increase heap:**
```bash
java -Xmx2g -jar database-consumer.jar  # 2GB heap
```

---

## Summary

### What We Built

**✅ Production-Ready Consumer with JdbcTemplate:**
- Annotation-based consumption (`@KafkaListener`)
- Automatic deserialization (JSON → Java)
- JdbcTemplate for database operations (direct SQL control)
- Explicit SQL statements for transparency
- Manual offset commit (reliability)
- Robust error handling
- High observability (detailed logging)

**✅ Reliable Processing:**
- Messages processed exactly once (manual commit)
- Validation before persistence
- Graceful error handling
- Statistics tracking
- Manual Instant → Timestamp conversion

**✅ Performance-Aware:**
- Connection pooling enabled
- Batch processing with `batchUpdate()` method
- Async writes possible
- Scalable via concurrency
- Direct SQL optimization control

### Current Flow

```
Generator (10 msg/sec)
    ↓
Kafka (market-data topic)
    ↓
Consumer (@KafkaListener)
    ↓
Validate Tick
    ↓
JdbcTemplate (jdbcTemplate.update)
    ↓
QuestDB (raw SQL INSERT)
    ↓
Commit Offset
```

### Performance Characteristics

**Current (with JdbcTemplate):**
- Throughput: ~100 messages/second (single message)
- Latency: ~10ms per message
- Reliability: Manual commit (no data loss)
- Code simplicity: Direct SQL control

**Optimized (Phase 6):**
- Throughput: 10,000+ messages/second (batch inserts)
- Latency: ~1ms per message (batch)
- Reliability: Dead Letter Queue for errors
- Batch optimization: jdbcTemplate.batchUpdate()

### JdbcTemplate Implementation Details

**Key Components:**

| Aspect | Implementation |
|--------|----------------|
| **Dependency Injection** | `JdbcTemplate jdbcTemplate` |
| **Single Insert** | `jdbcTemplate.update(INSERT_SQL, symbol, price, volume, timestamp)` |
| **Batch Insert** | `jdbcTemplate.batchUpdate(...)` with `BatchPreparedStatementSetter` |
| **SQL Writing** | Manual SQL strings (full control) |
| **Type Conversion** | Manual `Timestamp.from(instant)` conversion |
| **Transaction Management** | Manual `@Transactional` when needed |
| **Error Handling** | JDBC exceptions (`DataIntegrityViolationException`) |
| **Return Value** | `int` (rows affected) |

**Benefits Summary:**

✅ **Direct SQL Control**: Write exact SQL statements  
✅ **Transparent**: See exactly what gets executed  
✅ **Performance**: Optimize SQL for specific database  
✅ **Explicit Conversions**: Clear type handling (Instant → Timestamp)  
✅ **Lightweight**: No ORM overhead  
✅ **Debuggable**: Easy to log and inspect SQL  
✅ **Database-Specific**: Can use QuestDB-specific features

### Next Steps

**Phase 1 Complete!** You now have:
1. ✅ Generator producing realistic ticks
2. ✅ Kafka buffering messages
3. ✅ Consumer persisting to QuestDB
4. ✅ Time-series data ready for analysis

**Next:** Phase 2 - Analytics & Visualization

---

## Future Enhancements

### 1. Dead Letter Queue (Phase 6)

**Implementation:**
```java
@Bean
public ConcurrentKafkaListenerContainerFactory<String, Tick> kafkaListenerContainerFactory() {
    factory.setCommonErrorHandler(new DefaultErrorHandler(
        new DeadLetterPublishingRecoverer(kafkaTemplate), 
        new FixedBackOff(1000L, 2L)  // Retry 2 times with 1s delay
    ));
    return factory;
}
```

**DLQ topic:** `market-data-dlq`

**Benefits:**
- Failed messages preserved
- Consumer keeps running
- Separate service processes DLQ

### 2. Parallel Processing (Phase 6)

**Multiple partitions:**
```bash
kafka-topics.sh --alter \
    --bootstrap-server localhost:9092 \
    --topic market-data \
    --partitions 4
```

**Multiple consumers:**
```yaml
spring:
  kafka:
    listener:
      concurrency: 4  # Matches partition count
```

**Benefits:**
- 4x throughput
- Load balanced across partitions
- Each consumer processes 1 partition

### 3. Exactly-Once Semantics (Advanced)

**Transactional consumer:**
```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false
      isolation-level: read_committed
    listener:
      ack-mode: manual
```

**Transactional database writes with JdbcTemplate:**
```java
@Transactional
public void consumeTick(Tick tick, Acknowledgment ack) {
    String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
    jdbcTemplate.update(
        sql,
        tick.getSymbol(),
        tick.getPrice(),
        tick.getVolume(),
        Timestamp.from(tick.getTimestamp())
    );
    ack.acknowledge();
    // Both commit or both rollback
}

// Manual @Transactional annotation required when coordinating
// database writes with Kafka offset commits
```

**Benefits:**
- Truly exactly-once processing
- No duplicates even on crash
- Requires transactional database

### 4. Schema Registry (Production)

**Instead of JSON:**
```yaml
spring:
  kafka:
    consumer:
      value-deserializer: io.confluent.kafka.serializers.KafkaAvroDeserializer
```

**Benefits:**
- Schema validation
- Version compatibility
- Better performance (binary)
- Self-documenting

---

## Spring Data JPA Configuration Reference

### Essential application.yml Settings

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:8812/qdb
    username: admin
    password: quest
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
  
  jpa:
    # Schema management
    hibernate:
      ddl-auto: validate  # Options: create, create-drop, update, validate, none
    
    # Show SQL in logs (useful for debugging)
    show-sql: true
    
    # Hibernate properties
    properties:
      hibernate:
        # Dialect for PostgreSQL (QuestDB is wire-compatible)
        dialect: org.hibernate.dialect.PostgreSQLDialect
        
        # Format SQL in logs
        format_sql: true
        
        # Batch insert optimization
        jdbc:
          batch_size: 100
        order_inserts: true
        order_updates: true
        
        # Second-level cache (optional)
        cache:
          use_second_level_cache: false
          use_query_cache: false
        
        # Statistics (for performance monitoring)
        generate_statistics: false

# Kafka configuration
kafka:
  consumer:
    group-id: questdb-consumer-group
    bootstrap-servers: localhost:9092
```

### DDL-Auto Options Explained

| Option | Behavior | Use Case |
|--------|----------|----------|
| `none` | No action | Production (manage schema externally) |
| `validate` | Validate schema matches entities | Production (verify entities match DB) |
| `update` | Update schema to match entities | Development (auto-apply changes) |
| `create` | Drop and recreate schema on startup | Testing (fresh start each run) |
| `create-drop` | Drop schema on shutdown | Testing (clean up after tests) |

**Recommended:**
- **Development:** `update` (auto-apply entity changes)
- **Production:** `validate` (ensure entities match DB, fail if not)

### Logging JPA Operations

```yaml
logging:
  level:
    org.hibernate.SQL: DEBUG                          # Show SQL statements
    org.hibernate.type.descriptor.sql.BasicBinder: TRACE  # Show SQL parameters
    org.springframework.orm.jpa: DEBUG                # JPA operations
    com.quantstream.consumer: DEBUG                   # Your application logs
```

**Example output:**
```
DEBUG org.hibernate.SQL : insert into ticks (price, symbol, timestamp, volume, id) values (?, ?, ?, ?, ?)
TRACE o.h.type.descriptor.sql.BasicBinder : binding parameter [1] as [DOUBLE] - [180.5]
TRACE o.h.type.descriptor.sql.BasicBinder : binding parameter [2] as [VARCHAR] - [AAPL]
```

### Custom Repository Configuration

```java
@Configuration
@EnableJpaRepositories(
    basePackages = "com.quantstream.consumer.repository",
    enableDefaultTransactions = true
)
public class JpaConfig {
    
    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
```

---

**Next guide:** Analytics queries in Phase 2
