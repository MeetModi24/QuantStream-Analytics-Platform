# Market Data Consumer - QuestDB Configuration Guide

## What You're Building

**QuestDB connection setup** that enables your Spring Boot consumer to:
1. Connect to QuestDB using PostgreSQL wire protocol
2. Write tick data to the `ticks` table
3. Use JdbcTemplate for simple, efficient inserts
4. Handle connection pooling automatically

**This guide sets up the database layer** — the foundation for persisting market data.

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
jdbc:postgresql://localhost:8812/questdb
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
jdbc:postgresql://localhost:8812/questdb?user=admin&password=quest
```

**Breaking it down:**
- `jdbc:postgresql://` — JDBC protocol (PostgreSQL driver)
- `localhost` — Database host (our Docker container)
- `8812` — PostgreSQL wire protocol port
- `questdb` — Database name (always "questdb")
- `user=admin` — Default username (from Docker Compose)
- `password=quest` — Default password (from Docker Compose)

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
    name: data-consumer
  
  # Kafka Consumer Configuration
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: market-data-consumer-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: com.quantstream.consumer.model
  
  # QuestDB Database Configuration
  datasource:
    url: jdbc:postgresql://localhost:8812/questdb?user=admin&password=quest
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 5
      minimum-idle: 2
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000

# Logging Configuration
logging:
  level:
    com.quantstream: DEBUG
    org.springframework: INFO
    org.apache.kafka: WARN
    org.postgresql: WARN
```

---

## Understanding JDBC Connection Properties

### spring.datasource.url

**Format:**
```yaml
url: jdbc:postgresql://localhost:8812/questdb?user=admin&password=quest
```

**Parts explained:**

**jdbc:postgresql://**
- JDBC subprotocol
- Tells Java to use PostgreSQL driver

**localhost:8812**
- Host: `localhost` (QuestDB running in Docker)
- Port: `8812` (PostgreSQL wire protocol)

**Why not `localhost:9000`?**
- 9000 is HTTP/web console port
- JDBC needs wire protocol on 8812

**questdb**
- Database name (always "questdb", not configurable)

**?user=admin&password=quest**
- Query parameters for authentication
- Default credentials from Docker Compose

**Alternative format (separate properties):**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:8812/questdb
    username: admin
    password: quest
```

Both work identically. We embed credentials in URL for simplicity.

### spring.datasource.driver-class-name

```yaml
driver-class-name: org.postgresql.Driver
```

**Why specify this?**
- Tells Spring which JDBC driver to load
- Normally auto-detected, but explicit is clearer

**Why PostgreSQL driver works with QuestDB:**
- QuestDB implements PostgreSQL wire protocol
- Driver thinks it's talking to PostgreSQL
- QuestDB handles messages and responds accordingly

**Think of it as:** QuestDB speaks "PostgreSQL language" fluently.

### spring.datasource.hikari (Connection Pooling)

**What is connection pooling?**

**Without pooling:**
```
Request 1 → Open connection → Query → Close connection
Request 2 → Open connection → Query → Close connection
Request 3 → Open connection → Query → Close connection
```

Opening connections is expensive (TCP handshake, authentication, etc.).

**With pooling:**
```
App startup → Open 5 connections → Keep them open

Request 1 → Borrow connection #1 → Query → Return to pool
Request 2 → Borrow connection #2 → Query → Return to pool
Request 3 → Borrow connection #1 (reused!) → Query → Return to pool
```

Connections are reused, much faster!

**HikariCP** is the connection pool (default in Spring Boot, fastest available).

**Configuration explained:**

```yaml
hikari:
  maximum-pool-size: 5        # Max 5 connections open at once
  minimum-idle: 2              # Keep at least 2 connections ready
  connection-timeout: 30000    # Wait 30s for connection before error
  idle-timeout: 600000         # Close idle connections after 10 minutes
  max-lifetime: 1800000        # Close connections after 30 minutes (refresh)
```

**Why these values?**

**maximum-pool-size: 5**
- We consume 10 messages/sec, insert 1 at a time
- 5 connections can handle 50-100 inserts/sec (plenty of headroom)
- More connections = more resources, diminishing returns

**minimum-idle: 2**
- Keep 2 connections warm and ready
- App starts receiving Kafka messages immediately (no connection delay)

**connection-timeout: 30000 (30 seconds)**
- If all 5 connections busy, wait 30s for one to free up
- Should never happen at our scale (10 inserts/sec)
- If hits timeout → you have a problem (slow queries, connection leak)

**idle-timeout: 600000 (10 minutes)**
- Close connections idle for 10+ minutes (free resources)
- New connections opened when needed

**max-lifetime: 1800000 (30 minutes)**
- Close and re-open connections every 30 minutes
- Prevents stale connections (network issues, database restarts)
- Good practice for long-running apps

---

## JdbcTemplate vs JPA

### Why JdbcTemplate?

**Our use case:**
```java
// Simple INSERT
INSERT INTO ticks (symbol, price, volume, timestamp) 
VALUES ('AAPL', 180.50, 1000.0, '2024-07-12 10:00:00');
```

**JdbcTemplate approach:**
```java
jdbcTemplate.update(
    "INSERT INTO ticks VALUES (?, ?, ?, ?)",
    tick.getSymbol(),
    tick.getPrice(),
    tick.getVolume(),
    tick.getTimestamp()
);
```

**Clean, simple, direct SQL.**

**JPA/Hibernate approach:**
```java
@Entity
@Table(name = "ticks")
public class Tick {
    @Id @GeneratedValue
    private Long id;
    
    @Column(name = "symbol")
    private String symbol;
    // ... more annotations
}

// Save
tickRepository.save(tick);  // Generates SQL automatically
```

**More boilerplate, more magic, less control.**

### When to Use Each

**Use JdbcTemplate when:**
- ✅ Simple CRUD operations
- ✅ You know SQL well
- ✅ You want full control over queries
- ✅ Time-series/append-only workload (no complex relations)
- ✅ Performance is critical (no ORM overhead)

**Examples:** Logging, metrics, time-series data (our case)

**Use JPA when:**
- Complex object graphs (Order → LineItems → Products)
- Many relationships (one-to-many, many-to-many)
- Need object-oriented model
- Frequent updates to entities
- Team less comfortable with SQL

**Examples:** E-commerce, CMS, user management

**For our project:** JdbcTemplate is the right choice.
- Simple table structure
- Append-only inserts
- No relationships
- Performance matters

---

## Testing Connection

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

### Step 2: Create Test Configuration

**Create:** `src/test/java/com/quantstream/consumer/QuestDBConnectionTest.java`

```java
package com.quantstream.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
public class QuestDBConnectionTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void testConnection() {
        // Query QuestDB version
        String version = jdbcTemplate.queryForObject(
            "SELECT version()", 
            String.class
        );
        
        assertNotNull(version);
        assertTrue(version.contains("PostgreSQL"));
        System.out.println("QuestDB version: " + version);
    }
    
    @Test
    public void testTicksTableExists() {
        // Check if ticks table exists
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM ticks", 
            Integer.class
        );
        
        assertNotNull(count);
        System.out.println("Ticks table has " + count + " rows");
    }
}
```

### Step 3: Run Test

**In IntelliJ:**
1. Right-click `QuestDBConnectionTest.java`
2. Select "Run 'QuestDBConnectionTest'"

**Expected output:**
```
QuestDB version: PostgreSQL 12.3
Ticks table has 0 rows

BUILD SUCCESS
```

**If test passes:** Connection working! ✅

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

### Issue 2: "No suitable driver found"

**Error:**
```
java.sql.SQLException: No suitable driver found for jdbc:postgresql://localhost:8812/questdb
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

### Issue 3: "Authentication failed"

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

**Update application.yml to match:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:8812/questdb?user=admin&password=quest
```

**Or use separate properties:**
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:8812/questdb
    username: admin
    password: quest
```

**Restart app after changing credentials.**

### Issue 4: "Relation 'ticks' does not exist"

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

### Issue 5: "Connection pool exhausted"

**Error:**
```
com.zaxxer.hikari.pool.HikariPool$PoolInitializationException: Failed to initialize pool: Connection is not available, request timed out after 30000ms.
```

**Cause:** All connections in use, waiting for one to free up

**Why this happens:**
- Connection leak (not closing connections)
- Extremely slow queries (blocking connections)
- Pool size too small for workload

**Debug:**

**Check active connections:**
```sql
-- Run in QuestDB web console
SELECT * FROM pg_stat_activity;
```

**Fix:**

**Option 1: Increase pool size**
```yaml
hikari:
  maximum-pool-size: 10  # Increase from 5
```

**Option 2: Check for leaks**
```yaml
hikari:
  leak-detection-threshold: 60000  # Log warning if connection held > 60s
```

**Option 3: Review slow queries**
```yaml
logging:
  level:
    com.zaxxer.hikari: DEBUG  # Log connection pool activity
```

**For our use case (10 inserts/sec):** Pool size of 5 is plenty. If hitting this error, you have a bug (connection leak or blocking operation).

---

## Configuration Checklist

Before moving to next guide, verify:

- [ ] `application.yml` created with datasource configuration
- [ ] PostgreSQL JDBC driver in `pom.xml` dependencies
- [ ] QuestDB running on port 8812 (verify with `docker ps`)
- [ ] `ticks` table created in QuestDB
- [ ] Test connection succeeds (JdbcTemplate can query database)
- [ ] No connection errors in application startup logs
- [ ] Web console accessible at http://localhost:9000

---

## What's Next?

Now that QuestDB connection is configured, you'll create:

1. **Tick.java** (model) — Data class representing one price update (matching Kafka message)
2. **TickRepository.java** (repository) — Database access layer using JdbcTemplate
3. **MarketDataConsumer.java** (service) — Kafka listener that saves ticks to QuestDB

**Next guide:** `consumer-model-guide.md` (creating Tick.java and TickRepository.java)
