# Understanding Time-Series Databases

## What is a Time-Series Database (TSDB)?

A database optimized for storing and querying **time-stamped data**.

### The Problem with Regular Databases

**Scenario:** You store 1,000 tokens × 1 update/sec = 86.4 million rows per day

**Regular Database (MySQL/PostgreSQL):**
```sql
CREATE TABLE ticks (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    symbol VARCHAR(10),
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP,
    INDEX (symbol, timestamp)
);
```

**Problems:**
1. **Slow time-range queries** - "Show me BTC from 2pm to 3pm" scans millions of rows
2. **Storage bloat** - Row-based storage stores all columns together (inefficient)
3. **No automatic data management** - Must manually delete old data
4. **Poor compression** - Similar values (prices) stored inefficiently

### Time-Series Database (QuestDB/InfluxDB/TimescaleDB)

**Same table in TSDB:**
```sql
CREATE TABLE ticks (
    symbol SYMBOL,           -- Special type, indexed efficiently
    price DOUBLE,
    volume DOUBLE,
    timestamp TIMESTAMP      -- Designated timestamp (special treatment)
) timestamp(timestamp)       -- Partition by time automatically
PARTITION BY DAY;            -- One partition per day
```

**Advantages:**
1. **Fast time-range queries** - Knows which partition to scan
2. **Columnar storage** - Reads only needed columns
3. **Automatic partitioning** - Data organized by time
4. **Better compression** - Similar values compress ~10x better
5. **Built-in retention** - Auto-delete data older than X days

---

## How TSDB Stores Data Differently

### Row-Based (Regular Database)
```
Row 1: [BTC, 50000.0, 1000, 2024-01-01 14:00:01]
Row 2: [BTC, 50001.0, 1100, 2024-01-01 14:00:02]
Row 3: [BTC, 50002.0, 1050, 2024-01-01 14:00:03]
...stored together on disk...
```

**Query: "SELECT price FROM ticks WHERE symbol='BTC'"**
- Must read ALL columns even though you only want `price`
- Reads unnecessary data (symbol, volume, timestamp)

### Column-Based (TSDB)
```
Column: symbol  [BTC, BTC, BTC, BTC, ...]
Column: price   [50000.0, 50001.0, 50002.0, ...]
Column: volume  [1000, 1100, 1050, ...]
Column: timestamp [14:00:01, 14:00:02, 14:00:03, ...]
```

**Query: "SELECT price FROM ticks WHERE symbol='BTC'"**
- Reads ONLY the `price` column
- Much faster, less I/O

---

## Time-Based Partitioning

### Without Partitioning
```
All data in one table:
[Jan 1 data] [Jan 2 data] [Jan 3 data] ... [Dec 31 data]

Query: "Show me Jan 15 data"
→ Must scan entire year's data to find Jan 15
```

### With Time Partitioning
```
Partition: 2024-01-01 → [Jan 1 data]
Partition: 2024-01-02 → [Jan 2 data]
Partition: 2024-01-03 → [Jan 3 data]
...
Partition: 2024-01-15 → [Jan 15 data]

Query: "Show me Jan 15 data"
→ Reads ONLY the Jan 15 partition
→ 365x less data to scan!
```

---

## Compression Example

### Prices Over 1 Minute (60 values)
```
50000.0, 50001.0, 50000.5, 50002.0, 50001.5, ...
```

**Without Compression (Row-Based):**
- 60 values × 8 bytes (double) = 480 bytes

**With Compression (Columnar):**
- Store base value: 50000.0
- Store deltas: [0, +1.0, +0.5, +2.0, +1.5, ...]
- Deltas are small integers → compress to ~60 bytes
- **8x compression ratio**

This is why TSDB can store 10x more data in same space.

---

## QuestDB (Our Choice)

### Why QuestDB?

1. **Fastest ingestion** - 1.6 million rows/sec (benchmarked)
2. **SQL interface** - No new query language to learn
3. **Embedded** - Can run in Spring Boot or standalone
4. **Built-in HTTP API** - Easy to query
5. **Small footprint** - Runs on laptop with 512MB RAM

### QuestDB Architecture

```
┌─────────────────────────────────────┐
│  HTTP API (Port 9000)               │
│  - Web console for queries          │
│  - REST API for programmatic access │
└─────────────────────────────────────┘
           ↓
┌─────────────────────────────────────┐
│  Query Engine (SQL)                 │
│  - Optimized for time-range scans   │
│  - Parallel query execution         │
└─────────────────────────────────────┘
           ↓
┌─────────────────────────────────────┐
│  Columnar Storage Engine            │
│  - One file per column              │
│  - Memory-mapped files              │
│  - Compressed on disk               │
└─────────────────────────────────────┘
           ↓
┌─────────────────────────────────────┐
│  Disk Partitions (by day)           │
│  /data/ticks/2024-01-01/            │
│  /data/ticks/2024-01-02/            │
│  ...                                │
└─────────────────────────────────────┘
```

---

## Our Database Schema

### 1. Ticks Table (Raw Price Data)
```sql
CREATE TABLE ticks (
    symbol SYMBOL,           -- Token symbol (BTC, ETH, ...)
    price DOUBLE,            -- Current price
    volume DOUBLE,           -- Trading volume
    timestamp TIMESTAMP      -- When this price occurred
) timestamp(timestamp)
PARTITION BY DAY;
```

**Ingestion:** 1,000 rows/sec (1,000 tokens × 1 update/sec)

**Retention:** 7 days (then auto-delete)

**Storage:** ~600 MB per day (with compression)

### 2. Candles Table (Aggregated Data)
```sql
CREATE TABLE candles_1m (
    symbol SYMBOL,
    open DOUBLE,             -- First price in interval
    high DOUBLE,             -- Highest price in interval
    low DOUBLE,              -- Lowest price in interval
    close DOUBLE,            -- Last price in interval
    volume DOUBLE,           -- Total volume in interval
    timestamp TIMESTAMP      -- Start of interval
) timestamp(timestamp)
PARTITION BY DAY;
```

**Ingestion:** ~17 rows/sec (1,000 tokens × 1 candle/min ÷ 60 sec)

**Retention:** 90 days

**Storage:** ~50 MB per day

---

## Query Performance Example

### Query: Last 1 Hour of BTC Data

**Regular Database (MySQL):**
```sql
SELECT * FROM ticks 
WHERE symbol = 'BTC' 
  AND timestamp > NOW() - INTERVAL 1 HOUR;

-- Query time: 2-5 seconds
-- Rows scanned: 3.6 million (all ticks in last hour)
-- Rows returned: 3,600 (BTC ticks only)
```

**QuestDB:**
```sql
SELECT * FROM ticks 
WHERE symbol = 'BTC' 
  AND timestamp > dateadd('h', -1, now());

-- Query time: 50-100 milliseconds
-- Rows scanned: 3,600 (only BTC partition)
-- Rows returned: 3,600
```

**50x faster!**

---

## Data Lifecycle

### Hot Tier (In-Memory)
- Last 5 minutes of data
- Ultra-fast queries (<10ms)
- Used for real-time dashboard

### Warm Tier (On Disk)
- Last 7 days of ticks
- Last 90 days of candles
- Fast queries (<100ms)
- Used for historical charts

### Cold Tier (Archived)
- Older data
- Exported to Parquet files (compressed)
- Stored on cheap storage (S3, local disk)
- Queried rarely (backtest historical strategies)

---

## Key Takeaways

1. **TSDB is optimized for time-stamped data** (logs, metrics, prices)
2. **Columnar storage** reads only needed columns → faster queries
3. **Time partitioning** scans only relevant time ranges → 100x speedup
4. **Compression** stores 10x more data in same space
5. **QuestDB** gives us 1.6M rows/sec ingestion with SQL interface
6. **Retention tiers** keep recent data hot, archive old data

---

## Next: Understanding OHLC Candles

See: `03-ohlc-candles.md`
