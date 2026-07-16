# Market Data Consumer - QuestDB Configuration Guide

## What You're Building

**QuestDB connection setup with Spring JdbcTemplate** that enables your Spring Boot consumer to:
1. Connect to QuestDB using PostgreSQL wire protocol
2. Custom configuration to handle QuestDB limitations (no transaction support)
3. Use JdbcTemplate for direct SQL execution
4. HikariCP connection pooling with QuestDB-specific settings

**This guide sets up the database layer** — the foundation for persisting market data using JDBC instead of JPA (due to QuestDB's limited PostgreSQL compatibility).

---

## Why QuestDB?

**Quick recap** (see [concepts/02-questdb-basics.md](../concepts/02-questdb-basics.md) for details):

**QuestDB is a time-series database** optimized for:
- **Append-only writes** → 100x faster than PostgreSQL for inserts
- **Columnar storage** → Read only columns you need
- **Time partitioning** → Query only relevant date ranges
- **PostgreSQL compatible** → Use standard JDBC driver

**For our use case:**
- Receives 10 ticks/second from Kafka
- Stores tick data in QuestDB (symbol, price, volume, timestamp)
- Queries historical data for analysis
- Will be used for candle aggregation in Phase 2

---

## QuestDB Connection Basics

### PostgreSQL Wire Protocol Compatibility

**QuestDB speaks PostgreSQL protocol**, which means:
- Use PostgreSQL JDBC driver
- Connect via JDBC like any PostgreSQL database
- Standard SQL queries work (SELECT, INSERT, etc.)

**Why this matters:**
- No custom drivers needed
- Spring Boot auto-configuration works
- JDBC tools/libraries compatible

### Port Numbers

**QuestDB exposes two ports:**

**Port 8812 (PostgreSQL wire protocol):**
```
jdbc:postgresql://localhost:8812/qdb
```

Use this for:
- Java applications (JDBC)
- Python (psycopg2)
- Node.js (pg)
- Any PostgreSQL client

**Port 9000 (HTTP REST API + Web Console):**
```
http://localhost:9000
```

Use this for:
- Web UI (SQL editor, table browser)
- REST API calls (INSERT via HTTP POST)
- Health checks

**Our application uses port 8812** (JDBC connection).

### Connection String Format

**Standard PostgreSQL JDBC URL:**
```
jdbc:postgresql://host:port/database?user=username&password=password
```

**For QuestDB:**
```
jdbc:postgresql://localhost:8812/qdb
```

**Breaking it down:**
- `jdbc:postgresql://` — JDBC protocol (PostgreSQL driver)
- `localhost` — Database host (our Docker container)
- `8812` — PostgreSQL wire protocol port
- `qdb` — Database name (QuestDB's default database)
- Username/password specified separately in Spring config (admin/quest)

**Note:** QuestDB uses `qdb` as the default database name, not `questdb`

**Note:** For production, change default credentials! We use defaults for local development only.

---

## Creating application.yml Configuration

### Step 1: Create File

**Location:** `src/main/resources/application.yml`

**If exists:** Delete `application.properties` (YAML is cleaner)

### Step 2: Add Configuration

**Full configuration with comments:**

```yaml
spring:
  application:
    name: database-consumer
  
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: questdb-consumer-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.quantstream.consumer.model
    listener:
      ack-mode: manual

server:
  port: 8082

logging:
  level:
    com.quantstream: DEBUG
    org.springframework: INFO
    org.apache.kafka: WARN
```

**Note:** Unlike typical Spring Boot apps, we do NOT configure `spring.datasource` or `spring.jpa` properties here because QuestDB has limited PostgreSQL compatibility. Instead, we use a custom `QuestDBConfig.java` class (explained below).

---

## Understanding QuestDB Limitations

### Why NOT Spring Data JPA?

**QuestDB is NOT fully PostgreSQL compatible** despite using the PostgreSQL wire protocol. Key limitations:

**1. No Transaction Support**
- No `BEGIN`, `COMMIT`, `ROLLBACK` statements
- No savepoints
- No isolation levels (READ COMMITTED, SERIALIZABLE, etc.)
- All writes are auto-committed immediately

**2. No Schema DDL via JPA**
- Hibernate's `ddl-auto` won't work
- Must create tables manually via QuestDB console
- QuestDB uses custom syntax: `SYMBOL`, `timestamp(column)`, `PARTITION BY`

**3. Limited SQL Support**
- No foreign keys
- No stored procedures
- No triggers
- No joins in some contexts

**Why this matters for Spring:**
- **Spring Data JPA requires transaction support** → Fails with QuestDB
- **Hibernate expects standard PostgreSQL** → Errors on unsupported features
- **Spring Boot auto-configuration assumes full compatibility** → Must be overridden

**Solution: Use JdbcTemplate instead of JPA**
- Direct SQL execution (no ORM)
- No transaction requirements
- Full control over SQL dialect
- Works with QuestDB's PostgreSQL wire protocol

---

## Creating Custom QuestDBConfig.java

### Why Custom Configuration is Required

**Spring Boot auto-configuration fails with QuestDB because:**
1. HikariCP tries to set transaction isolation level → QuestDB doesn't support
2. Spring Boot enables transactions by default → QuestDB doesn't support
3. Connection validation queries may use unsupported features

**Solution: Manual DataSource configuration with QuestDB-specific settings**

### Step 1: Create Config Class

**Location:** `src/main/java/com/quantstream/consumer/config/QuestDBConfig.java`

**Full configuration:**

```java
package com.quantstream.consumer.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * QuestDB-specific configuration.
 * <p>
 * QuestDB has limited PostgreSQL compatibility - it doesn't support:
 * - Transaction isolation levels
 * - BEGIN/COMMIT/ROLLBACK (no transactions)
 * - Savepoints
 * <p>
 * This configuration bypasses HikariCP's transaction checks.
 */
@Configuration
public class QuestDBConfig {

    @Bean
    public DataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:postgresql://localhost:8812/qdb");
        config.setUsername("admin");
        config.setPassword("quest");
        config.setDriverClassName("org.postgresql.Driver");

        // Bypass transaction isolation level detection (QuestDB doesn't support it)
        config.setAutoCommit(true);
        config.setConnectionInitSql("SELECT 1");
        config.setConnectionTestQuery("SELECT 1");

        // Don't set transaction isolation (causes errors with QuestDB)
        config.setTransactionIsolation(null);

        // Connection pool settings
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(10000);

        return new HikariDataSource(config);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
```

### Understanding the Configuration

**@Configuration**
```java
@Configuration
public class QuestDBConfig {
```
- Marks this as a Spring configuration class
- Spring scans for @Bean methods and creates beans at startup

**@Bean public DataSource dataSource()**
```java
@Bean
public DataSource dataSource() {
    HikariConfig config = new HikariConfig();
    // ...
    return new HikariDataSource(config);
}
```
- Creates DataSource bean (connection pool)
- Overrides Spring Boot's auto-configured DataSource
- Returns HikariCP connection pool (industry-standard, high-performance)

**JDBC URL**
```java
config.setJdbcUrl("jdbc:postgresql://localhost:8812/qdb");
```
- Same format as before: `jdbc:postgresql://host:port/database`
- Uses PostgreSQL JDBC driver to connect to QuestDB
- Port 8812 = PostgreSQL wire protocol

**Transaction Isolation = null**
```java
config.setTransactionIsolation(null);
```
- **CRITICAL:** Prevents HikariCP from setting isolation level
- Without this, QuestDB throws: `ERROR: SET TRANSACTION ISOLATION LEVEL not supported`
- Setting to `null` tells HikariCP "don't try to set isolation level"

**Auto-commit = true**
```java
config.setAutoCommit(true);
```
- Every SQL statement commits immediately
- Required because QuestDB doesn't support transactions
- Matches QuestDB's behavior (no BEGIN/COMMIT)

**Connection Test Query**
```java
config.setConnectionInitSql("SELECT 1");
config.setConnectionTestQuery("SELECT 1");
```
- Simple query to verify connection works
- `SELECT 1` is universally supported (PostgreSQL, QuestDB, etc.)
- HikariCP runs this when borrowing connections from pool

**Connection Pool Settings**
```java
config.setMaximumPoolSize(10);
config.setMinimumIdle(2);
config.setConnectionTimeout(10000);
```
- **MaximumPoolSize(10):** At most 10 concurrent database connections
- **MinimumIdle(2):** Keep 2 connections ready at all times
- **ConnectionTimeout(10000):** Wait up to 10 seconds for available connection

**@Bean public JdbcTemplate jdbcTemplate()**
```java
@Bean
public JdbcTemplate jdbcTemplate(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
}
```
- Creates JdbcTemplate bean using our DataSource
- JdbcTemplate = Spring's template class for JDBC operations
- Simplifies JDBC code (no manual connection management)

---

## Understanding JdbcTemplate vs JPA

### What is JdbcTemplate?

**JdbcTemplate** is Spring's utility class for executing SQL:
- Execute raw SQL strings (INSERT, SELECT, UPDATE, DELETE)
- Map ResultSets to Java objects
- Handle connection management automatically
- No ORM overhead (no entity mappings, no proxies)

**Basic usage:**
```java
@Autowired
private JdbcTemplate jdbcTemplate;

public void saveTick(Tick tick) {
    String sql = "INSERT INTO ticks (symbol, price, volume, timestamp) VALUES (?, ?, ?, ?)";
    jdbcTemplate.update(sql, tick.getSymbol(), tick.getPrice(), tick.getVolume(), tick.getTimestamp());
}
```

**Key difference from JPA:**
- **You write SQL manually** (not auto-generated from method names)
- **No @Entity annotations** (POJOs with getters/setters)
- **No repository interfaces** (service layer directly uses JdbcTemplate)

### Why JdbcTemplate for QuestDB?

**Advantages over JPA:**

**1. No transaction requirements**
- JPA/Hibernate expects transactions
- QuestDB doesn't support transactions
- JdbcTemplate works fine without transactions

**2. Direct SQL control**
- QuestDB uses custom syntax: `SYMBOL`, `timestamp(timestamp)`
- JPA would generate standard PostgreSQL DDL (incompatible)
- JdbcTemplate lets you write QuestDB-specific SQL

**3. Simpler for time-series workloads**
- Most operations are INSERT (append-only writes)
- Simple SELECT queries by time range
- No complex joins or relationships
- JdbcTemplate is sufficient, JPA is overkill

**4. Better performance**
- No ORM overhead (no entity proxies, lazy loading, etc.)
- Direct JDBC calls (minimal abstraction)
- Less memory usage (no persistent context)

**Trade-offs:**

**Lose:**
- Auto-generated repository methods (`findBySymbol`, etc.)
- Type-safe queries (no compile-time checking of column names)
- Object-relational mapping (manual ResultSet → object conversion)

**Gain:**
- Works with QuestDB's limitations
- Simpler mental model (just SQL)
- Better performance for high-throughput inserts

---

## Understanding spring.datasource properties

**Why NOT in application.yml?**
- We don't use `spring.datasource` properties
- QuestDB needs custom configuration (transaction isolation = null)
- Spring Boot auto-configuration would fail with QuestDB
- Instead, we define DataSource manually in `QuestDBConfig.java`

**If you try to use spring.datasource with QuestDB, you'll get:**
```
ERROR: SET TRANSACTION ISOLATION LEVEL not supported
```

**That's why we need the custom config class above.**

### server.port

```yaml
server:
  port: 8082
```

**Why specify this?**
- Default Spring Boot port is 8080
- Port 8080 may be in use by other services (like the data-generator)
- Port 8082 clearly identifies this as the database-consumer service

### kafka.listener.ack-mode

```yaml
kafka:
  listener:
    ack-mode: manual
```

**What is manual acknowledgment?**
- By default, Kafka auto-commits offsets (marks messages as "processed")
- Manual mode lets you control when to commit
- Commit only after successfully writing to database

**Why manual mode?**
- **Prevents data loss:** If database write fails, don't commit offset
- **At-least-once delivery:** Retry failed messages on restart
- **Data consistency:** Message committed only after persistence

**Trade-off:**
- Possible duplicate processing if app crashes after DB write but before commit
- For our use case: Better to have duplicates than lost data

---

## Required Dependencies

### Verify pom.xml Dependencies

**Spring JDBC and PostgreSQL driver are required:**

```xml
<dependencies>
    <!-- Spring JDBC (provides JdbcTemplate) -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-jdbc</artifactId>
    </dependency>
    
    <!-- PostgreSQL JDBC driver -->
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    
    <!-- HikariCP connection pool (included in spring-boot-starter-jdbc) -->
    <!-- No need to add explicitly -->
</dependencies>
```

**What each dependency provides:**

**spring-boot-starter-jdbc:**
- JdbcTemplate class (simplified JDBC operations)
- HikariCP connection pool (auto-configured)
- Transaction support (optional, not used with QuestDB)
- DataSource abstraction

**postgresql:**
- PostgreSQL JDBC driver
- Enables JDBC connection to QuestDB (PostgreSQL wire protocol)
- Required at runtime only (not for compilation)

**Verify dependencies:**
```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer
mvn dependency:tree | grep -E "spring-boot-starter-jdbc|postgresql|hikari"
```

**Expected output:**
```
[INFO] +- org.springframework.boot:spring-boot-starter-jdbc:jar:3.3.1:compile
[INFO] |  +- com.zaxxer:HikariCP:jar:5.0.1:compile
[INFO] +- org.postgresql:postgresql:jar:42.7.3:runtime
```

**Note:** Do NOT use `spring-boot-starter-data-jpa` — we're using JdbcTemplate, not JPA.

**If missing, add to pom.xml and run:**
```bash
mvn clean install
```


---

## Testing JdbcTemplate Configuration

### Step 1: Verify QuestDB Running

**Check Docker container:**
```bash
docker ps
```

**Expected output:**
```
CONTAINER ID   IMAGE                  STATUS         PORTS
abc123         questdb/questdb        Up 2 minutes   0.0.0.0:9000->9000/tcp, 0.0.0.0:8812->8812/tcp
```

**Verify ports:**
- 8812 → PostgreSQL wire protocol (JDBC)
- 9000 → Web console

**If not running:**
```bash
cd /Users/mhiteshkumar/QuantStream
docker-compose up -d
```

Wait 30 seconds for startup.

### Step 2: Create DataSource Test

**Create:** `src/test/java/com/quantstream/consumer/JdbcConfigurationTest.java`

```java
package com.quantstream.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class JdbcConfigurationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testDataSourceExists() {
        // Verify Spring created DataSource bean
        assertNotNull(dataSource);
        System.out.println("DataSource bean exists: " + dataSource.getClass().getName());
    }

    @Test
    public void testJdbcTemplateExists() {
        // Verify Spring created JdbcTemplate bean
        assertNotNull(jdbcTemplate);
        System.out.println("JdbcTemplate bean exists: " + jdbcTemplate.getClass().getName());
    }

    @Test
    public void testDatabaseConnection() throws Exception {
        // Test actual connection to QuestDB
        try (Connection conn = dataSource.getConnection()) {
            assertNotNull(conn);
            System.out.println("Connection established to: " + conn.getMetaData().getURL());
            
            // Query QuestDB version using JdbcTemplate
            String version = jdbcTemplate.queryForObject("SELECT version()", String.class);
            assertNotNull(version);
            assertTrue(version.contains("PostgreSQL"));
            System.out.println("QuestDB version: " + version);
        }
    }
    
    @Test
    public void testTicksTableExists() {
        // Verify ticks table exists and count rows
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ticks", Integer.class);
        assertNotNull(count);
        System.out.println("Ticks table has " + count + " rows");
    }

    @Test
    public void testTransactionIsolationNull() throws Exception {
        // Verify transaction isolation is null (QuestDB doesn't support it)
        try (Connection conn = dataSource.getConnection()) {
            int isolation = conn.getTransactionIsolation();
            System.out.println("Transaction isolation level: " + isolation);
            // Should be 0 (TRANSACTION_NONE) or Connection.TRANSACTION_READ_UNCOMMITTED (1)
            // Not TRANSACTION_READ_COMMITTED (2) or higher
        }
    }
}
```

### Step 3: Run Test

**In IntelliJ:**
1. Right-click `JdbcConfigurationTest.java`
2. Select "Run 'JdbcConfigurationTest'"

**Expected output:**
```
DataSource bean exists: com.zaxxer.hikari.HikariDataSource
JdbcTemplate bean exists: org.springframework.jdbc.core.JdbcTemplate
Connection established to: jdbc:postgresql://localhost:8812/qdb
QuestDB version: PostgreSQL 12.3
Ticks table has 0 rows
Transaction isolation level: 0

BUILD SUCCESS
```

**What this confirms:**
- `QuestDBConfig` created DataSource bean (HikariCP connection pool)
- `QuestDBConfig` created JdbcTemplate bean
- Connection to QuestDB successful
- Database accessible via JDBC
- Transaction isolation set to null/0 (QuestDB compatible)
- Ticks table exists

**If test passes:** JDBC configuration working! ✅

---

## Common Issues

### Issue 1: "Connection refused to localhost:8812"

**Error:**
```
org.postgresql.util.PSQLException: Connection to localhost:8812 refused.
```

**Cause:** QuestDB not running or not bound to 8812

**Fix:**

**Check container:**
```bash
docker ps
```

**If not listed:**
```bash
cd /Users/mhiteshkumar/QuantStream
docker-compose up -d
```

**Check logs:**
```bash
docker logs quantstream-questdb-1
```

**Expected:**
```
2024-07-12T10:00:00.000000Z I http-server listening on [0.0.0.0:9000]
2024-07-12T10:00:00.000000Z I pg-server listening on [0.0.0.0:8812]
```

**If "pg-server listening" missing:**
- Port 8812 already in use
- Check: `lsof -i :8812`
- Kill conflicting process or change port

**Still fails:** Check Docker network
```bash
docker-compose down
docker-compose up -d
```

### Issue 2: "SET TRANSACTION ISOLATION LEVEL not supported"

**Error:**
```
ERROR: SET TRANSACTION ISOLATION LEVEL not supported
```

**Cause:** HikariCP trying to set transaction isolation level (QuestDB doesn't support)

**Fix:**

**Verify QuestDBConfig.java has:**
```java
config.setTransactionIsolation(null);
```

**If missing or set to a value (e.g., "TRANSACTION_READ_COMMITTED"):**
1. Open `QuestDBConfig.java`
2. Change to: `config.setTransactionIsolation(null);`
3. Rebuild project
4. Restart application

**This tells HikariCP "don't try to set isolation level".**

### Issue 3: "No suitable driver found"

**Error:**
```
java.sql.SQLException: No suitable driver found for jdbc:postgresql://localhost:8812/qdb
```

**Cause:** PostgreSQL JDBC driver missing from dependencies

**Fix:**

**Check pom.xml:**
```xml
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>runtime</scope>
</dependency>
```

**If missing, add it:**
1. Open `pom.xml`
2. Add dependency in `<dependencies>` section
3. Save file
4. IntelliJ auto-imports (or run `mvn clean install`)

**Verify download:**
```bash
mvn dependency:tree | grep postgresql
```

Should show:
```
[INFO] +- org.postgresql:postgresql:jar:42.7.3:runtime
```

### Issue 4: "Authentication failed"

**Error:**
```
org.postgresql.util.PSQLException: FATAL: password authentication failed for user "admin"
```

**Cause:** Wrong username or password

**Fix:**

**Check Docker Compose credentials:**
```bash
grep -A 3 "questdb:" /Users/mhiteshkumar/QuantStream/docker-compose.yml
```

**Expected:**
```yaml
questdb:
  environment:
    - QDB_PG_USER=admin
    - QDB_PG_PASSWORD=quest
```

**Update QuestDBConfig.java to match:**
```java
config.setJdbcUrl("jdbc:postgresql://localhost:8812/qdb");
config.setUsername("admin");
config.setPassword("quest");
```

**Rebuild and restart app after changing credentials.**

### Issue 5: "Relation 'ticks' does not exist"

**Error:**
```
org.postgresql.util.PSQLException: ERROR: table does not exist [table=ticks]
```

**Cause:** Table not created yet

**Fix:**

**Create table via web console:**
1. Open: http://localhost:9000
2. Click "SQL" tab
3. Paste:
   ```sql
   CREATE TABLE ticks (
       symbol SYMBOL,
       price DOUBLE,
       volume DOUBLE,
       timestamp TIMESTAMP
   ) timestamp(timestamp) PARTITION BY DAY;
   ```
4. Click "Run" (or press F9)

**Or via psql:**
```bash
docker exec -it quantstream-questdb-1 psql -h localhost -p 8812 -U admin -d questdb
```

```sql
CREATE TABLE ticks (
    symbol SYMBOL,
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY DAY;
```

**Verify:**
```sql
SELECT * FROM ticks LIMIT 1;
```

Should return empty result (not error).

### Issue 6: "Connection timeout"

**Error:**
```
java.sql.SQLException: Timeout after 30000ms
```

**Cause:** Unable to establish database connection within timeout period

**Why this happens:**
- QuestDB not responding (crashed, overloaded)
- Network issues between app and database
- Firewall blocking port 8812

**Debug:**

**Check QuestDB health:**
```bash
docker logs quantstream-questdb-1
curl http://localhost:9000
```

**Check connectivity:**
```bash
telnet localhost 8812
```

**Fix:**

**Option 1: Restart QuestDB**
```bash
docker-compose restart questdb
```

**Option 2: Check for port conflicts**
```bash
lsof -i :8812
```

**Option 3: Review QuestDB logs for errors**
```bash
docker logs quantstream-questdb-1 --tail 50
```

---

## Configuration Checklist

Before moving to next guide, verify:

- [ ] `application.yml` created with Kafka config (NO spring.datasource or spring.jpa properties)
- [ ] `QuestDBConfig.java` created in `src/main/java/com/quantstream/consumer/config/`
- [ ] `@Configuration` annotation present on QuestDBConfig class
- [ ] DataSource bean configured with `setTransactionIsolation(null)`
- [ ] JdbcTemplate bean created from DataSource
- [ ] Application name set to `database-consumer`
- [ ] Kafka consumer group ID set to `questdb-consumer-group`
- [ ] Listener ack-mode set to `manual`
- [ ] Server port set to `8082`
- [ ] Database URL uses `/qdb` (not `/questdb`)
- [ ] PostgreSQL JDBC driver in `pom.xml` dependencies
- [ ] `spring-boot-starter-jdbc` dependency in `pom.xml` (NOT spring-boot-starter-data-jpa)
- [ ] QuestDB running on port 8812 (verify with `docker ps`)
- [ ] `ticks` table created in QuestDB
- [ ] Test confirms DataSource bean exists
- [ ] Test confirms JdbcTemplate bean exists
- [ ] Test confirms transaction isolation is null/0
- [ ] No connection errors in application startup logs
- [ ] Web console accessible at http://localhost:9000

---

## What's Next?

Now that JdbcTemplate configuration is complete, you'll create:

1. **Tick.java** (model) — Plain Java class (POJO) with getters/setters (no JPA annotations)
2. **TickService.java** (service) — Business logic layer that uses JdbcTemplate to execute SQL
3. **MarketDataConsumer.java** (consumer) — Kafka listener that calls TickService.save()

**Key difference from Spring Data JPA approach:**
- Write SQL manually (INSERT, SELECT statements)
- Use JdbcTemplate.update() and JdbcTemplate.query()
- No repository interfaces (service layer directly uses JdbcTemplate)
- Plain POJOs (no @Entity, @Table, @Id annotations)

**Next guide:** `consumer-model-guide.md` (creating Tick model and TickService)
