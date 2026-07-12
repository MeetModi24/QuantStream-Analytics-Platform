# QuestDB Basics for Time-Series Data

## What is QuestDB?

QuestDB is a **time-series database** optimized for storing and querying timestamped data like stock prices, sensor readings, and logs.

**Think of it as:** PostgreSQL specifically designed for time-based data, 100x faster for certain queries.

---

## Why Not Use Regular Databases?

### The Scale Problem

**Our data volume:**
- 10 tokens × 1 update/second = 10 rows/second
- 10 rows/sec × 86,400 seconds/day = **864,000 rows/day**
- After 30 days: **25.9 million rows**

**PostgreSQL handling this:**
```sql
SELECT * FROM ticks 
WHERE symbol = 'AAPL' 
AND timestamp BETWEEN '2024-07-01' AND '2024-07-02';
```

**What PostgreSQL does:**
1. Scans index on `symbol` column
2. Finds all AAPL rows (maybe 2.5 million rows)
3. Checks timestamp for each row
4. Filters to date range
5. Returns results

**Time:** 2-5 seconds for 1 day of data ❌

**QuestDB doing the same:**
1. Knows data is partitioned by day
2. Opens only 2024-07-01 partition
3. Scans AAPL symbol (optimized SYMBOL type)
4. Returns results

**Time:** 50-100 milliseconds ✅

**50x faster!**

---

## How QuestDB Achieves Speed

### 1. Columnar Storage

**Row-based (PostgreSQL, MySQL):**
```
Row 1: [AAPL, 180.00, 1000, 2024-07-01 10:00:00]
Row 2: [AAPL, 180.05, 1100, 2024-07-01 10:00:01]
Row 3: [AAPL, 180.03, 1050, 2024-07-01 10:00:02]
```

All columns stored together on disk.

**Query: "SELECT price FROM ticks WHERE symbol='AAPL'"**
- Must read ALL columns (symbol, price, volume, timestamp)
- Even though you only need price
- Wastes I/O bandwidth

**Column-based (QuestDB):**
```
Column: symbol    [AAPL, AAPL, AAPL, ...]
Column: price     [180.00, 180.05, 180.03, ...]
Column: volume    [1000, 1100, 1050, ...]
Column: timestamp [10:00:00, 10:00:01, 10:00:02, ...]
```

Each column stored separately.

**Query: "SELECT price FROM ticks WHERE symbol='AAPL'"**
- Reads only symbol column (to filter)
- Reads only price column (to return)
- Never touches volume or timestamp columns
- Uses fraction of I/O

**Why this matters:**
- Analytical queries typically read few columns but many rows
- "Give me all AAPL prices" reads 1 column from 1M rows
- Row-based would read 4 columns from 1M rows (4x more data)

### 2. Compression

**Prices over time:**
```
180.00, 180.05, 180.03, 180.08, 180.12, 180.09, ...
```

**Row-based storage:**
- Each price stored as 8-byte double
- 1 million prices = 8 MB

**Column-based with compression:**
- Base value: 180.00
- Deltas: +0.05, -0.02, +0.05, +0.04, -0.03, ...
- Deltas are small integers: compress to 1-2 bytes each
- 1 million prices = 1-2 MB

**4-8x compression!**

**Same for symbols:**
```
AAPL, AAPL, AAPL, BTC, BTC, AAPL, ...
```

QuestDB's SYMBOL type:
- Stores each unique string once
- Uses integer IDs: 0=AAPL, 1=BTC, 2=ETH
- Column becomes: [0, 0, 0, 1, 1, 0, ...]
- 4 bytes per symbol instead of variable-length string

### 3. Time-Based Partitioning

**Without partitioning:**
```
All data in one table:
[Jan 1 data][Jan 2 data]...[Dec 31 data]

Query: "Show me Jan 15 data"
Database must scan entire year to find Jan 15 rows
```

**With time-based partitioning:**
```
Partition: 2024-01-01/  [Jan 1 data]
Partition: 2024-01-02/  [Jan 2 data]
...
Partition: 2024-01-15/  [Jan 15 data]
...
Partition: 2024-12-31/  [Dec 31 data]

Query: "Show me Jan 15 data"
Database opens ONLY 2024-01-15 partition
365x less data to scan
```

**QuestDB automatically creates partitions** based on designated timestamp column.

**Our table:**
```sql
CREATE TABLE ticks (
    symbol SYMBOL,
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP
) timestamp(timestamp) PARTITION BY DAY;
```

Every day gets its own partition folder on disk:
```
/data/ticks/2024-07-01/
/data/ticks/2024-07-02/
/data/ticks/2024-07-03/
...
```

### 4. Append-Only Writes

**Time-series data characteristic:**
- Always append new data (never update old data)
- Rarely delete (or delete entire old partitions)
- No UPDATE or DELETE of individual rows

**QuestDB optimized for this:**
- Write path is super fast (no indexes to update on insert)
- Can batch writes efficiently
- No need for complex transaction locking

**Ingestion speed:**
- PostgreSQL: 10,000-50,000 rows/second
- QuestDB: **1,600,000 rows/second** (benchmarked)

**Our needs:** 10 rows/second (easy for QuestDB, has 160,000x headroom)

---

## QuestDB Data Types

### SYMBOL Type (Important!)

**Purpose:** Store strings with low cardinality (few unique values)

**Example:** Token symbols (AAPL, BTC, ETH, etc.)
- Only 10 unique values
- But appears millions of times in data

**How it works:**
```
Internal mapping:
0 -> "AAPL"
1 -> "BTC"
2 -> "ETH"
...

Table stores integers, not strings:
[0, 0, 0, 1, 1, 2, 0, ...]
```

**Benefits:**
- 4 bytes per value instead of 4-10 bytes for string
- Faster comparisons (integer vs string)
- Better compression

**When to use SYMBOL:**
- Low cardinality (< 1000 unique values)
- Repeated frequently
- Examples: symbols, status codes, device IDs, user IDs

**When NOT to use SYMBOL:**
- High cardinality (millions of unique values)
- Examples: UUIDs, email addresses, descriptions

### TIMESTAMP Type

**Purpose:** Store nanosecond-precision timestamps

**QuestDB feature:** One column designated as "designated timestamp"
```sql
CREATE TABLE ticks (...) timestamp(timestamp);
                                    ↑ this column
```

**Benefits:**
- Used for partitioning
- Used for time-range queries optimization
- Must be monotonically increasing (each new row has later timestamp)

**Our usage:**
```java
Instant now = Instant.now();  // Java 8 time API
// Sent to QuestDB as microsecond epoch
```

### DOUBLE Type

**Purpose:** Store floating-point numbers

**Our usage:**
- price DOUBLE (stock price like 180.53)
- volume DOUBLE (trading volume like 1000.0)

**Note:** QuestDB also supports INT, LONG, FLOAT, but DOUBLE is most common for financial data

---

## SQL Dialect Differences

QuestDB uses **PostgreSQL wire protocol** but has some SQL differences:

### What Works (Standard SQL)

```sql
-- SELECT queries
SELECT * FROM ticks WHERE symbol = 'AAPL';

-- Aggregations
SELECT symbol, AVG(price), COUNT(*) 
FROM ticks 
GROUP BY symbol;

-- Time ranges
SELECT * FROM ticks 
WHERE timestamp BETWEEN '2024-07-01' AND '2024-07-02';

-- ORDER BY
SELECT * FROM ticks ORDER BY timestamp DESC LIMIT 100;
```

### QuestDB-Specific Features

**1. Time-based WHERE clauses:**
```sql
-- Last hour
SELECT * FROM ticks 
WHERE timestamp > dateadd('h', -1, now());

-- Specific date range
SELECT * FROM ticks
WHERE timestamp IN '2024-07-01';  -- Entire day
```

**2. SAMPLE BY (downsampling):**
```sql
-- Get 1-minute average prices (instead of per-second)
SELECT timestamp, symbol, AVG(price) 
FROM ticks
SAMPLE BY 1m;  -- Resample to 1-minute buckets
```

This is VERY useful for aggregating tick data.

**3. LATEST BY (get most recent per group):**
```sql
-- Get latest price for each symbol
SELECT * FROM ticks
LATEST BY symbol;

-- Much faster than:
SELECT * FROM ticks t1
WHERE timestamp = (SELECT MAX(timestamp) FROM ticks t2 WHERE t2.symbol = t1.symbol);
```

### What Doesn't Work (Not Supported Yet)

```sql
-- UPDATE (not needed for time-series)
UPDATE ticks SET price = 200 WHERE symbol = 'AAPL';  -- ❌

-- DELETE individual rows (use DROP PARTITION instead)
DELETE FROM ticks WHERE timestamp < '2024-01-01';  -- ❌

-- Joins are supported but not optimized
SELECT ... FROM ticks t1 JOIN ticks t2 ...;  -- ⚠️ Slow

-- Subqueries are limited
```

**Philosophy:** QuestDB optimizes for append-only, time-series queries, not general-purpose CRUD

---

## Our Table Schema

```sql
CREATE TABLE ticks (
    symbol SYMBOL,        -- Token symbol (AAPL, BTC, etc.)
    price DOUBLE,         -- Current price
    volume DOUBLE,        -- Trading volume
    timestamp TIMESTAMP   -- When this price occurred
) timestamp(timestamp)    -- Designated timestamp column
PARTITION BY DAY;         -- One partition per day
```

**Why this schema:**

**symbol SYMBOL:**
- Only 10 unique values (AAPL, MSFT, GOOGL, TSLA, AMZN, BTC, ETH, SOL, AVAX, MATIC)
- Perfect for SYMBOL type
- Fast filtering: WHERE symbol = 'AAPL'

**price DOUBLE:**
- Stock prices: $0.01 to $100,000+
- Need decimal precision
- DOUBLE provides 15-17 significant digits

**volume DOUBLE:**
- Trading volume varies wildly (100 to 1,000,000+)
- DOUBLE handles any scale

**timestamp TIMESTAMP:**
- Nanosecond precision
- Designated as "the" timestamp (for partitioning)
- Every insert must have increasing timestamp

**PARTITION BY DAY:**
- Each day's data in separate partition
- Easy to drop old data: DROP PARTITION '2024-06-01'
- Queries automatically scope to relevant partitions

---

## Querying Tips

### Get Latest Prices

```sql
-- Latest price for all symbols
SELECT * FROM ticks LATEST BY symbol;

-- Latest 10 ticks
SELECT * FROM ticks ORDER BY timestamp DESC LIMIT 10;
```

### Time-Range Queries

```sql
-- Last hour
SELECT * FROM ticks 
WHERE timestamp > dateadd('h', -1, now());

-- Specific day
SELECT * FROM ticks
WHERE timestamp IN '2024-07-12';

-- Date range
SELECT * FROM ticks
WHERE timestamp BETWEEN '2024-07-01' AND '2024-07-12';
```

### Aggregations

```sql
-- Average price per symbol
SELECT symbol, AVG(price), COUNT(*) 
FROM ticks
GROUP BY symbol;

-- Price range per symbol
SELECT symbol, MIN(price), MAX(price), MAX(price) - MIN(price) as range
FROM ticks
GROUP BY symbol;

-- Hourly averages (downsampling)
SELECT timestamp, symbol, AVG(price)
FROM ticks
SAMPLE BY 1h;
```

### Performance Queries

```sql
-- Count rows per symbol
SELECT symbol, COUNT(*) as count 
FROM ticks 
GROUP BY symbol;

-- Count rows per day
SELECT DATE(timestamp) as day, COUNT(*) as count
FROM ticks
GROUP BY day
ORDER BY day DESC;
```

---

## Connection from Java

QuestDB supports **PostgreSQL wire protocol**, so we use PostgreSQL JDBC driver:

```java
// JDBC URL
String url = "jdbc:postgresql://localhost:8812/questdb?user=admin&password=quest";

// Use standard JDBC
Connection conn = DriverManager.getConnection(url);
PreparedStatement stmt = conn.prepareStatement(
    "INSERT INTO ticks VALUES (?, ?, ?, ?)"
);

stmt.setString(1, "AAPL");           // symbol
stmt.setDouble(2, 180.50);           // price
stmt.setDouble(3, 1000.0);           // volume
stmt.setTimestamp(4, timestamp);     // timestamp
stmt.execute();
```

**Port 8812:** PostgreSQL wire protocol (not 9000 which is HTTP UI)

---

## Web Console

QuestDB has built-in web console: **http://localhost:9000**

**Features:**
- SQL editor with auto-complete
- Table browser
- Query results with charts
- Real-time table updates

**Useful during development:**
1. Test queries before writing Java code
2. Verify data is being written correctly
3. Explore table structure
4. Monitor ingestion rate

---

## Data Retention

### Manual Cleanup

```sql
-- Drop old partition
DROP PARTITION WHERE name = '2024-01-01';

-- Drop all partitions before date
DROP PARTITION WHERE timestamp < '2024-06-01';
```

### Automatic Cleanup (Not Built-in)

QuestDB doesn't have automatic TTL (time-to-live). You need to:

**Option 1:** Cron job
```bash
# Run daily at midnight
0 0 * * * psql -h localhost -p 8812 -c "DROP PARTITION WHERE timestamp < dateadd('d', -7, now());"
```

**Option 2:** Application-level
```java
@Scheduled(cron = "0 0 0 * * *")  // Daily at midnight
public void cleanupOldData() {
    jdbcTemplate.execute(
        "DROP PARTITION WHERE timestamp < dateadd('d', -7, now())"
    );
}
```

**For our project:** We'll keep 7 days of data (simple manual cleanup is fine)

---

## When QuestDB Shines

✅ **Use QuestDB when:**
- Append-only data (no updates to old rows)
- Time-series data (timestamped events)
- High ingestion rate (1000+ rows/sec)
- Time-range queries ("last hour", "yesterday")
- Analytical queries (aggregations, downsampling)

**Examples:**
- Stock market data ← We're here
- IoT sensor data
- Application logs
- Metrics/monitoring data

## When NOT to Use QuestDB

❌ **Don't use QuestDB when:**
- Frequent updates to old data
- Complex transactions (need ACID guarantees)
- Many joins across tables
- General-purpose CRUD app

**Examples:**
- E-commerce (orders get updated)
- User management (profiles get edited)
- Content management systems
- Social networks

**Use PostgreSQL or MySQL instead.**

---

## Summary

**QuestDB = PostgreSQL optimized for time-series**

**Key features:**
1. **Columnar storage** → Read only needed columns
2. **Compression** → 4-8x smaller data
3. **Time partitioning** → Query only relevant time ranges
4. **Append-optimized** → 100x faster writes
5. **SYMBOL type** → Efficient string storage
6. **PostgreSQL compatible** → Use standard JDBC

**For our project:**
- Stores all tick data (10 rows/sec)
- Fast queries for historical data
- Will store candle data in Phase 2
- Will store strategy signals in Phase 5

**Next:** Understand Spring Boot project structure before implementing.
