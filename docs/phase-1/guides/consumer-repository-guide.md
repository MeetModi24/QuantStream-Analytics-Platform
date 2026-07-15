# Consumer Repository Guide

## 1. What You're Building

You're creating `TickRepository`, a data access layer component that handles all database operations for tick data. This repository will:

- Insert individual tick records into QuestDB
- Perform batch inserts for high-throughput scenarios
- Query tick data for verification and analysis
- Handle database errors gracefully

The repository sits between your Kafka consumer (business logic) and QuestDB (persistence layer), providing a clean abstraction for data operations.

## 2. Repository Pattern: Separation of Concerns

### Why Separate DB Logic from Business Logic?

The Repository Pattern isolates data access code from business logic, providing several benefits:

**Without Repository Pattern:**
```java
// ❌ Bad: Business logic mixed with SQL
@Service
public class TickConsumerService {
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    public void processTick(TickData tick) {
        // Business logic
        validateTick(tick);
        enrichTick(tick);
        
        // SQL mixed in - hard to test, hard to change
        jdbcTemplate.update(
            "INSERT INTO ticks VALUES (?, ?, ?, ?)",
            tick.getSymbol(), tick.getPrice(), tick.getVolume(), tick.getTimestamp()
        );
    }
}
```

**With Repository Pattern:**
```java
// ✅ Good: Clean separation
@Service
public class TickConsumerService {
    @Autowired
    private TickRepository tickRepository;
    
    public void processTick(TickData tick) {
        // Only business logic
        validateTick(tick);
        enrichTick(tick);
        
        // Delegate persistence to repository
        tickRepository.save(tick);
    }
}
```

**Benefits:**
- **Testability**: Mock the repository to test business logic without a database
- **Maintainability**: Database schema changes only affect the repository
- **Reusability**: Multiple services can use the same repository methods
- **Flexibility**: Swap database implementations without changing business logic

## 3. Creating TickRepository.java

Create the repository in `consumer/src/main/java/com/quantstream/consumer/repository/TickRepository.java`:

```java
package com.quantstream.consumer.repository;

import com.quantstream.consumer.model.TickData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

@Repository
public class TickRepository {
    
    private static final Logger logger = LoggerFactory.getLogger(TickRepository.class);
    
    private final JdbcTemplate jdbcTemplate;
    
    // SQL constants for clarity and maintainability
    private static final String INSERT_TICK_SQL = 
        "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
    
    private static final String SELECT_LATEST_BY_SYMBOL_SQL = 
        "SELECT * FROM ticks WHERE symbol = ? ORDER BY timestamp DESC LIMIT ?";
    
    private static final String COUNT_BY_SYMBOL_SQL = 
        "SELECT COUNT(*) FROM ticks WHERE symbol = ?";
    
    public TickRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    
    /**
     * Insert a single tick record.
     * Use this for low-volume scenarios or when immediate persistence is required.
     */
    public void save(TickData tick) {
        try {
            jdbcTemplate.update(INSERT_TICK_SQL,
                tick.getSymbol(),
                tick.getPrice(),
                tick.getVolume(),
                Timestamp.from(tick.getTimestamp())
            );
            logger.debug("Saved tick: {}", tick.getSymbol());
        } catch (Exception e) {
            logger.error("Failed to save tick: {}", tick, e);
            throw new RuntimeException("Database insert failed", e);
        }
    }
    
    /**
     * Insert multiple ticks in a single batch operation.
     * This is 10-100x faster than individual inserts for bulk operations.
     */
    @Transactional
    public void saveBatch(List<TickData> ticks) {
        if (ticks == null || ticks.isEmpty()) {
            return;
        }
        
        try {
            int[] updateCounts = jdbcTemplate.batchUpdate(
                INSERT_TICK_SQL,
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        TickData tick = ticks.get(i);
                        ps.setString(1, tick.getSymbol());
                        ps.setDouble(2, tick.getPrice());
                        ps.setLong(3, tick.getVolume());
                        ps.setTimestamp(4, Timestamp.from(tick.getTimestamp()));
                    }
                    
                    @Override
                    public int getBatchSize() {
                        return ticks.size();
                    }
                }
            );
            
            logger.info("Batch inserted {} ticks", updateCounts.length);
        } catch (Exception e) {
            logger.error("Failed to save batch of {} ticks", ticks.size(), e);
            throw new RuntimeException("Batch insert failed", e);
        }
    }
    
    /**
     * Query the latest N ticks for a given symbol.
     * Useful for verification and monitoring.
     */
    public List<TickData> findLatestBySymbol(String symbol, int limit) {
        return jdbcTemplate.query(
            SELECT_LATEST_BY_SYMBOL_SQL,
            new Object[]{symbol, limit},
            (rs, rowNum) -> {
                TickData tick = new TickData();
                tick.setSymbol(rs.getString("symbol"));
                tick.setPrice(rs.getDouble("price"));
                tick.setVolume(rs.getLong("volume"));
                tick.setTimestamp(rs.getTimestamp("timestamp").toInstant());
                return tick;
            }
        );
    }
    
    /**
     * Count total ticks for a symbol.
     * Useful for monitoring ingestion rates.
     */
    public long countBySymbol(String symbol) {
        Long count = jdbcTemplate.queryForObject(
            COUNT_BY_SYMBOL_SQL,
            Long.class,
            symbol
        );
        return count != null ? count : 0L;
    }
}
```

## 4. Understanding JdbcTemplate

### What is JdbcTemplate?

`JdbcTemplate` is Spring's central class for JDBC operations. It wraps raw JDBC with:
- Automatic resource management (connections, statements, result sets)
- Exception translation (SQL exceptions to Spring's DataAccessException)
- Boilerplate reduction (no manual try-catch-finally blocks)

### Why Not Use Raw JDBC?

**Raw JDBC requires manual resource management:**

```java
// ❌ Raw JDBC - verbose and error-prone
Connection conn = null;
PreparedStatement stmt = null;
try {
    conn = dataSource.getConnection();
    stmt = conn.prepareStatement("INSERT INTO ticks VALUES (?, ?, ?, ?)");
    stmt.setString(1, tick.getSymbol());
    stmt.setDouble(2, tick.getPrice());
    stmt.setLong(3, tick.getVolume());
    stmt.setTimestamp(4, Timestamp.from(tick.getTimestamp()));
    stmt.executeUpdate();
} catch (SQLException e) {
    // Handle exception
} finally {
    if (stmt != null) try { stmt.close(); } catch (SQLException e) {}
    if (conn != null) try { conn.close(); } catch (SQLException e) {}
}
```

**JdbcTemplate handles this automatically:**

```java
// ✅ JdbcTemplate - clean and safe
jdbcTemplate.update(
    "INSERT INTO ticks VALUES (?, ?, ?, ?)",
    tick.getSymbol(),
    tick.getPrice(),
    tick.getVolume(),
    Timestamp.from(tick.getTimestamp())
);
// Resources automatically closed, even on exceptions
```

### Why Not Use JPA/Hibernate for Time-Series Data?

JPA (Java Persistence API) and Hibernate are designed for **OLTP** (Online Transaction Processing) systems with:
- Complex object relationships
- Frequent updates and deletes
- Entity state management

**Time-series data like ticks is different:**
- **Write-heavy**: Millions of inserts, very few updates/deletes
- **Simple structure**: No complex relationships
- **Append-only**: Data is immutable once written
- **Performance-critical**: Need raw speed, not ORM overhead

**JPA overhead for time-series:**
```java
// ❌ JPA adds unnecessary complexity
@Entity
@Table(name = "ticks")
public class Tick {
    @Id
    @GeneratedValue
    private Long id;  // Don't need synthetic IDs
    
    private String symbol;
    private Double price;
    // ...
}

// EntityManager adds latency
entityManager.persist(tick);  // Slower than raw SQL
entityManager.flush();        // Extra network roundtrips
```

**JDBC gives direct control:**
```java
// ✅ Direct SQL for maximum throughput
jdbcTemplate.batchUpdate(sql, batchSetter);  // Optimized path
```

**When to use each:**
- **JPA/Hibernate**: User accounts, orders, inventory (CRUD operations, relationships)
- **JDBC/JdbcTemplate**: Logs, metrics, time-series data (high-volume writes, simple queries)

## 5. Writing Prepared Statements

### What Are Prepared Statements?

Prepared statements are **pre-compiled SQL templates** with placeholders (`?`) for dynamic values. The database parses the SQL structure once, then you bind different values for each execution.

### Why They Prevent SQL Injection

**Without prepared statements (vulnerable):**

```java
// ❌ NEVER DO THIS - vulnerable to SQL injection
String symbol = userInput;  // Could be: "AAPL'; DROP TABLE ticks; --"
String sql = "SELECT * FROM ticks WHERE symbol = '" + symbol + "'";
jdbcTemplate.query(sql, rowMapper);

// Becomes: SELECT * FROM ticks WHERE symbol = 'AAPL'; DROP TABLE ticks; --'
// Your table is now deleted!
```

**With prepared statements (safe):**

```java
// ✅ Safe - symbol is treated as data, not SQL code
String sql = "SELECT * FROM ticks WHERE symbol = ?";
jdbcTemplate.query(sql, new Object[]{symbol}, rowMapper);

// Even if symbol = "AAPL'; DROP TABLE ticks; --"
// It's treated as a literal string to match, not SQL commands
```

### Parameter Binding with `?`

Each `?` is a positional placeholder that gets bound to a value:

```java
// SQL with placeholders
String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";

// Bind values in order (1-indexed)
jdbcTemplate.update(sql,
    "AAPL",           // Position 1 (symbol)
    150.25,           // Position 2 (price)
    1000L,            // Position 3 (volume)
    Timestamp.now()   // Position 4 (timestamp)
);
```

**Manual binding in BatchPreparedStatementSetter:**

```java
ps.setString(1, tick.getSymbol());      // Binds to first ?
ps.setDouble(2, tick.getPrice());       // Binds to second ?
ps.setLong(3, tick.getVolume());        // Binds to third ?
ps.setTimestamp(4, timestamp);          // Binds to fourth ?
```

**Types map to SQL types:**
- `setString()` → VARCHAR, CHAR, TEXT
- `setDouble()` → DOUBLE
- `setLong()` → BIGINT
- `setTimestamp()` → TIMESTAMP

## 6. QuestDB-Specific SQL

### INSERT INTO Syntax

QuestDB supports standard SQL INSERT syntax, but column order matters if you omit column names:

```sql
-- Explicit columns (recommended - order-independent)
INSERT INTO ticks (symbol, price, volume, timestamp) 
VALUES ('AAPL', 150.25, 1000, '2026-07-15T10:30:00.000000Z');

-- Implicit columns (must match table definition order)
INSERT INTO ticks VALUES ('AAPL', 150.25, 1000, '2026-07-15T10:30:00.000000Z');
```

**Always use explicit column names** for clarity and resilience to schema changes.

### SYMBOL vs STRING Type

QuestDB has a special **SYMBOL** type optimized for repeated string values (like stock symbols):

```sql
CREATE TABLE ticks (
    symbol SYMBOL,      -- ✅ Optimized for 'AAPL', 'GOOGL', etc. (repeated values)
    description STRING, -- ✅ For unique text (company names, notes)
    price DOUBLE,
    timestamp TIMESTAMP
) TIMESTAMP(timestamp) PARTITION BY DAY;
```

**When to use SYMBOL:**
- Values repeat frequently (symbols, exchange codes, status flags)
- Low cardinality (hundreds to thousands of unique values, not millions)
- Used in WHERE clauses or GROUP BY

**Performance difference:**
- **SYMBOL**: Stored as integer ID + lookup table → ~8 bytes per row
- **STRING**: Full text repeated → variable bytes per row

**In Java, both map to String:**

```java
// From application perspective, both are strings
ps.setString(1, tick.getSymbol());      // Maps to SYMBOL column
ps.setString(2, tick.getDescription()); // Maps to STRING column
```

### Timestamp Handling

QuestDB requires **microsecond precision timestamps** in ISO 8601 format or as Java Timestamp objects:

```java
// ✅ Using java.sql.Timestamp (recommended)
import java.sql.Timestamp;
import java.time.Instant;

Instant now = Instant.now();
Timestamp timestamp = Timestamp.from(now);
ps.setTimestamp(4, timestamp);

// ✅ Alternative: String format
String isoTimestamp = "2026-07-15T10:30:00.123456Z";
ps.setString(4, isoTimestamp);

// ❌ Don't use: java.util.Date (deprecated, only millisecond precision)
```

**Time zones:**
- QuestDB stores all timestamps in UTC
- Java `Instant` is UTC by default
- Always convert local times to UTC before inserting

```java
// Convert local time to UTC
ZonedDateTime localTime = ZonedDateTime.now(ZoneId.of("America/New_York"));
Instant utcInstant = localTime.toInstant();
Timestamp timestamp = Timestamp.from(utcInstant);
```

## 7. Batch Insert Optimization

### Why Batch Inserts Are Faster

**Individual inserts (slow):**
```
Application → Database: INSERT tick #1
Application ← Database: OK
Application → Database: INSERT tick #2
Application ← Database: OK
Application → Database: INSERT tick #3
Application ← Database: OK
```
- **Network overhead**: 1 roundtrip per insert
- **Parsing overhead**: Database parses SQL for each insert
- **Disk I/O**: Each insert may trigger a disk write

**Batch insert (fast):**
```
Application → Database: INSERT tick #1, #2, #3, ..., #1000
Application ← Database: OK (1000 rows affected)
```
- **1 network roundtrip** for 1000 inserts
- **Parse once**, execute many times
- **Bulk disk I/O**: Optimized by database

**Performance gain:** 10-100x faster for bulk operations.

### How to Implement with JdbcTemplate.batchUpdate()

```java
public void saveBatch(List<TickData> ticks) {
    jdbcTemplate.batchUpdate(
        INSERT_TICK_SQL,
        new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                TickData tick = ticks.get(i);
                ps.setString(1, tick.getSymbol());
                ps.setDouble(2, tick.getPrice());
                ps.setLong(3, tick.getVolume());
                ps.setTimestamp(4, Timestamp.from(tick.getTimestamp()));
            }
            
            @Override
            public int getBatchSize() {
                return ticks.size();
            }
        }
    );
}
```

**How it works:**
1. `batchUpdate()` prepares the SQL statement once
2. Calls `setValues()` for each tick to bind parameters
3. Sends all statements to the database in one batch
4. Returns array of update counts (rows affected per statement)

### When to Flush Batches

**Batch size trade-offs:**
- **Too small** (< 100): Doesn't fully exploit batching benefits
- **Too large** (> 10,000): High memory usage, long latency if batch fails
- **Sweet spot**: 500-2,000 records per batch

**Buffering strategy for Kafka consumer:**

```java
@Service
public class TickConsumerService {
    private static final int BATCH_SIZE = 1000;
    private static final long FLUSH_INTERVAL_MS = 5000; // 5 seconds
    
    private final TickRepository tickRepository;
    private final List<TickData> buffer = new ArrayList<>();
    private long lastFlushTime = System.currentTimeMillis();
    
    public void processTick(TickData tick) {
        buffer.add(tick);
        
        // Flush on size threshold
        if (buffer.size() >= BATCH_SIZE) {
            flushBuffer();
        }
        
        // Flush on time threshold (prevent stale data)
        if (System.currentTimeMillis() - lastFlushTime > FLUSH_INTERVAL_MS) {
            flushBuffer();
        }
    }
    
    private void flushBuffer() {
        if (!buffer.isEmpty()) {
            tickRepository.saveBatch(new ArrayList<>(buffer));
            buffer.clear();
            lastFlushTime = System.currentTimeMillis();
        }
    }
    
    @PreDestroy
    public void shutdown() {
        flushBuffer(); // Flush remaining data on shutdown
    }
}
```

**Flush triggers:**
1. **Size threshold**: Batch is full (e.g., 1000 records)
2. **Time threshold**: Maximum latency reached (e.g., 5 seconds)
3. **Shutdown**: Application is stopping

## 8. Error Handling

### Database Exceptions

Spring translates SQL exceptions into its `DataAccessException` hierarchy:

```java
try {
    tickRepository.save(tick);
} catch (DataAccessException e) {
    if (e instanceof DuplicateKeyException) {
        logger.warn("Duplicate tick ignored: {}", tick.getSymbol());
    } else if (e instanceof DataIntegrityViolationException) {
        logger.error("Data integrity violation: {}", e.getMessage());
    } else {
        logger.error("Database error", e);
    }
}
```

**Common exceptions:**
- `DuplicateKeyException`: Primary key or unique constraint violation
- `DataIntegrityViolationException`: Foreign key, check constraint, or NOT NULL violation
- `QueryTimeoutException`: Query exceeded timeout limit
- `TransientDataAccessException`: Temporary failure (network, lock timeout) - retryable

### Retry Logic

**Transient errors** (network blips, temporary locks) should be retried:

```java
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;

@Repository
public class TickRepository {
    
    @Retryable(
        value = {TransientDataAccessException.class, QueryTimeoutException.class},
        maxAttempts = 3,
        backoff = @Backoff(delay = 1000, multiplier = 2.0)
    )
    public void save(TickData tick) {
        jdbcTemplate.update(INSERT_TICK_SQL,
            tick.getSymbol(),
            tick.getPrice(),
            tick.getVolume(),
            Timestamp.from(tick.getTimestamp())
        );
    }
}
```

**Retry configuration:**
- `maxAttempts = 3`: Try up to 3 times total
- `delay = 1000`: Wait 1 second before first retry
- `multiplier = 2.0`: Double wait time for each retry (1s, 2s, 4s)

**Enable retry in Spring Boot:**

Add dependency:
```xml
<dependency>
    <groupId>org.springframework.retry</groupId>
    <artifactId>spring-retry</artifactId>
</dependency>
```

Enable in application:
```java
@SpringBootApplication
@EnableRetry
public class ConsumerApplication {
    // ...
}
```

**Don't retry everything:**
- **Retry**: Network errors, lock timeouts, temporary unavailability
- **Don't retry**: Data integrity violations, malformed SQL, invalid data

## 9. Testing Repository

### Unit Tests with H2 In-Memory Database

Use H2 as a lightweight test database (QuestDB doesn't have an embedded mode):

```java
package com.quantstream.consumer.repository;

import com.quantstream.consumer.model.TickData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@JdbcTest
@Import(TickRepository.class)
class TickRepositoryTest {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;
    
    @Autowired
    private TickRepository tickRepository;
    
    @BeforeEach
    void setUp() {
        // Create test table
        jdbcTemplate.execute(
            "CREATE TABLE ticks (" +
            "  symbol VARCHAR(10)," +
            "  price DOUBLE," +
            "  volume BIGINT," +
            "  timestamp TIMESTAMP" +
            ")"
        );
    }
    
    @Test
    void testSaveSingleTick() {
        // Given
        TickData tick = createTick("AAPL", 150.25, 1000);
        
        // When
        tickRepository.save(tick);
        
        // Then
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ticks WHERE symbol = ?",
            Long.class,
            "AAPL"
        );
        assertEquals(1L, count);
    }
    
    @Test
    void testSaveBatch() {
        // Given
        List<TickData> ticks = Arrays.asList(
            createTick("AAPL", 150.25, 1000),
            createTick("GOOGL", 2800.50, 500),
            createTick("MSFT", 350.75, 750)
        );
        
        // When
        tickRepository.saveBatch(ticks);
        
        // Then
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ticks",
            Long.class
        );
        assertEquals(3L, count);
    }
    
    @Test
    void testFindLatestBySymbol() {
        // Given
        tickRepository.save(createTick("AAPL", 150.00, 1000));
        tickRepository.save(createTick("AAPL", 150.25, 1000));
        tickRepository.save(createTick("AAPL", 150.50, 1000));
        
        // When
        List<TickData> latest = tickRepository.findLatestBySymbol("AAPL", 2);
        
        // Then
        assertEquals(2, latest.size());
        assertEquals(150.50, latest.get(0).getPrice()); // Most recent first
        assertEquals(150.25, latest.get(1).getPrice());
    }
    
    @Test
    void testCountBySymbol() {
        // Given
        tickRepository.save(createTick("AAPL", 150.25, 1000));
        tickRepository.save(createTick("AAPL", 150.50, 1000));
        tickRepository.save(createTick("GOOGL", 2800.50, 500));
        
        // When
        long appleCount = tickRepository.countBySymbol("AAPL");
        long googleCount = tickRepository.countBySymbol("GOOGL");
        
        // Then
        assertEquals(2L, appleCount);
        assertEquals(1L, googleCount);
    }
    
    private TickData createTick(String symbol, double price, long volume) {
        TickData tick = new TickData();
        tick.setSymbol(symbol);
        tick.setPrice(price);
        tick.setVolume(volume);
        tick.setTimestamp(Instant.now());
        return tick;
    }
}
```

**Test configuration (`src/test/resources/application-test.yml`):**

```yaml
spring:
  datasource:
    url: jdbc:h2:mem:testdb
    driver-class-name: org.h2.Driver
    username: sa
    password: 
  test:
    database:
      replace: none
```

**H2 dependency for tests:**

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

### Integration Tests Against QuestDB

For end-to-end validation, use Testcontainers to run a real QuestDB instance:

```java
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class TickRepositoryIntegrationTest {
    
    @Container
    static GenericContainer<?> questdb = new GenericContainer<>("questdb/questdb:7.3.7")
        .withExposedPorts(8812, 9000);
    
    @Autowired
    private TickRepository tickRepository;
    
    @Test
    void testBatchInsertPerformance() {
        // Test with real QuestDB
        List<TickData> largeBatch = generateTicks(10000);
        
        long startTime = System.currentTimeMillis();
        tickRepository.saveBatch(largeBatch);
        long duration = System.currentTimeMillis() - startTime;
        
        System.out.println("Inserted 10,000 ticks in " + duration + "ms");
        assertTrue(duration < 5000, "Batch insert should complete within 5 seconds");
    }
}
```

## 10. Common Issues

### SQL Syntax Errors

**Problem:** QuestDB doesn't support all PostgreSQL syntax.

```java
// ❌ Won't work - QuestDB doesn't support RETURNING clause
String sql = "INSERT INTO ticks (...) VALUES (...) RETURNING id";

// ✅ Use QuestDB-compatible syntax
String sql = "INSERT INTO ticks (...) VALUES (...)";
```

**Solution:** Consult [QuestDB SQL Reference](https://questdb.io/docs/reference/sql/overview/) for supported syntax.

### Type Mismatches

**Problem:** Java type doesn't match database column type.

```java
// ❌ Wrong type - BIGINT column with int parameter
ps.setInt(3, tick.getVolume()); // volume is long, not int

// ✅ Correct type
ps.setLong(3, tick.getVolume());
```

**Type mapping:**
- Java `long` ↔ SQL `BIGINT`
- Java `int` ↔ SQL `INT`
- Java `double` ↔ SQL `DOUBLE`
- Java `String` ↔ SQL `VARCHAR`, `SYMBOL`, `STRING`
- Java `Timestamp` ↔ SQL `TIMESTAMP`

### Connection Pool Exhaustion

**Problem:** All database connections are in use, new requests wait or fail.

```
org.springframework.jdbc.CannotGetJdbcConnectionException: 
Failed to obtain JDBC Connection; nested exception is 
java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available
```

**Solution:** Tune HikariCP (Spring's default connection pool):

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20        # Increase pool size (default: 10)
      connection-timeout: 30000    # Wait up to 30s for connection
      idle-timeout: 600000         # Close idle connections after 10 min
      max-lifetime: 1800000        # Recycle connections after 30 min
```

**Diagnosis:**
```java
@Autowired
private HikariDataSource dataSource;

// Log pool stats
logger.info("Pool stats - Active: {}, Idle: {}, Total: {}, Waiting: {}",
    dataSource.getHikariPoolMXBean().getActiveConnections(),
    dataSource.getHikariPoolMXBean().getIdleConnections(),
    dataSource.getHikariPoolMXBean().getTotalConnections(),
    dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()
);
```

### Batch Insert Failures

**Problem:** One bad record fails the entire batch.

**Solution:** Catch and log failed batches, optionally retry individual records:

```java
public void saveBatch(List<TickData> ticks) {
    try {
        jdbcTemplate.batchUpdate(...);
    } catch (DataAccessException e) {
        logger.error("Batch insert failed, retrying individually", e);
        
        // Fallback: Insert one by one to identify bad records
        for (TickData tick : ticks) {
            try {
                save(tick);
            } catch (Exception ex) {
                logger.error("Failed to insert tick: {}", tick, ex);
                // Send to dead-letter queue or log for manual review
            }
        }
    }
}
```

### Timestamp Precision Loss

**Problem:** Microsecond timestamps truncated to milliseconds.

```java
// ❌ java.util.Date only has millisecond precision
Date date = new Date(); // 2026-07-15 10:30:00.123
Timestamp timestamp = new Timestamp(date.getTime()); // Loses microseconds

// ✅ Use Instant for full precision
Instant instant = Instant.now(); // 2026-07-15 10:30:00.123456789
Timestamp timestamp = Timestamp.from(instant); // Preserves microseconds
```

### Memory Leaks in Buffering

**Problem:** Buffer grows unbounded if flush fails repeatedly.

**Solution:** Implement max buffer size and circuit breaker:

```java
private static final int MAX_BUFFER_SIZE = 10000;

public void processTick(TickData tick) {
    if (buffer.size() >= MAX_BUFFER_SIZE) {
        logger.error("Buffer full, dropping tick: {}", tick);
        // Optionally: send to dead-letter queue
        return;
    }
    
    buffer.add(tick);
    
    if (buffer.size() >= BATCH_SIZE) {
        try {
            flushBuffer();
        } catch (Exception e) {
            logger.error("Flush failed, buffer size: {}", buffer.size(), e);
            // Circuit breaker: stop accepting new data if persistent failure
            if (consecutiveFailures > 3) {
                throw new RuntimeException("Circuit breaker open", e);
            }
        }
    }
}
```

## Next Steps

1. **Implement the repository** following the code examples above
2. **Write unit tests** using H2 to verify insert and query logic
3. **Add monitoring** - log batch sizes, insert rates, error counts
4. **Tune batch size** - experiment with different sizes for your workload
5. **Move to the next guide**: Integrate this repository with your Kafka consumer

**Related guides:**
- [Generator Project Setup](./generator-project-setup.md)
- Consumer Service Implementation (coming next)
- QuestDB Schema Design
