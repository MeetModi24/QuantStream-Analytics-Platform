# QuantStream Pipeline Execution Guide

**Date:** 2026-07-23  
**Purpose:** Complete end-to-end execution of 60-day backfill with daily candles  
**Expected Duration:** 15-20 minutes

---

## Table of Contents
1. [Prerequisites](#prerequisites)
2. [Architecture Overview](#architecture-overview)
3. [Pre-Execution Checklist](#pre-execution-checklist)
4. [Execution Steps](#execution-steps)
5. [Verification](#verification)
6. [Troubleshooting](#troubleshooting)
7. [Cleanup](#cleanup)

---

## Prerequisites

### System Requirements
- Docker & Docker Compose installed
- Java 21
- Maven 3.9+
- 8GB RAM minimum
- 10GB disk space

### Services
- **Kafka** (message broker)
- **Zookeeper** (Kafka metadata)
- **QuestDB** (time-series database)
- **Kafka UI** (optional, for monitoring)

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│ Data Flow Pipeline                                          │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  BackfillRunner (60 days)                                   │
│       │                                                      │
│       ├─→ Kafka "market-data" (864,000 ticks)              │
│       │        ↓                                            │
│       │   Aggregator (Kafka Streams)                        │
│       │        ↓                                            │
│       ├─→ Kafka "candles-1m" (~14,400 candles)             │
│       │        ↓                                            │
│       │   Database Consumer                                 │
│       │        ↓                                            │
│       ├─→ QuestDB "candles_1m" table                       │
│       │                                                      │
│       ├─→ QuestDB "ticks" table                            │
│                                                              │
│  Manual SQL Aggregation                                     │
│       │                                                      │
│       ├─→ QuestDB "candles_1d" table (~600 daily candles)  │
│                                                              │
│  Strategy Engine (10 strategies)                            │
│       │                                                      │
│       ├─→ Query candles_1d (MA(50) = 50 DAYS)             │
│       │                                                      │
│       ├─→ Kafka "trading-signals"                          │
│       │        ↓                                            │
│       │   Database Consumer                                 │
│       │        ↓                                            │
│       └─→ QuestDB "signals" table                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Pre-Execution Checklist

### 1. Verify Docker Containers

```bash
cd /Users/mhiteshkumar/QuantStream

# Start Docker containers
docker-compose up -d

# Wait for startup (30 seconds)
sleep 30

# Verify all containers running
docker ps --format "table {{.Names}}\t{{.Status}}"
```

**Expected Output:**
```
NAMES       STATUS
kafka-ui    Up X seconds
kafka       Up X seconds
questdb     Up X seconds
zookeeper   Up X seconds
```

### 2. Verify QuestDB Connection

```bash
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT 1" | jq .
```

**Expected:** `{"query":"SELECT 1", ... "dataset":[[1]]}`

### 3. Verify Tables Exist

```bash
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SHOW TABLES" | jq -r '.dataset[]'
```

**Expected:**
```
["candles_1d"]
["candles_1m"]
["ticks"]
["signals"]
```

### 4. Clean Environment (CRITICAL!)

```bash
# Stop any running Java services
pkill -f "data-generator" 2>/dev/null
pkill -f "aggregator" 2>/dev/null
pkill -f "database-consumer" 2>/dev/null
pkill -f "strategy-engine" 2>/dev/null
sleep 3

# Delete Kafka topics to avoid stale data
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic market-data 2>/dev/null || true
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic candles-1m 2>/dev/null || true
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic trading-signals 2>/dev/null || true

# Truncate QuestDB tables
curl -s -G http://localhost:9001/exec --data-urlencode "query=TRUNCATE TABLE ticks" > /dev/null
curl -s -G http://localhost:9001/exec --data-urlencode "query=TRUNCATE TABLE candles_1m" > /dev/null
curl -s -G http://localhost:9001/exec --data-urlencode "query=TRUNCATE TABLE candles_1d" > /dev/null
curl -s -G http://localhost:9001/exec --data-urlencode "query=TRUNCATE TABLE signals" > /dev/null

# Clean Kafka Streams state
rm -rf /tmp/kafka-streams

echo "✅ Environment cleaned"
```

**Verification:**
```bash
# All tables should have 0 rows
for table in ticks candles_1m candles_1d signals; do
  count=$(curl -s -G http://localhost:9001/exec \
    --data-urlencode "query=SELECT count(*) FROM $table" | jq -r '.dataset[0][0]')
  echo "$table: $count"
done
```

**Expected:** All tables show `0`

---

## Execution Steps

### Step 1: Start Aggregator Service ⚙️

**Purpose:** Processes ticks from Kafka → creates 1-minute candles  
**MUST START FIRST** before backfill sends data

```bash
cd /Users/mhiteshkumar/QuantStream/aggregator

# Start in background
nohup mvn spring-boot:run > /tmp/aggregator.log 2>&1 &

# Save PID for later
AGGREGATOR_PID=$!
echo "Aggregator PID: $AGGREGATOR_PID"

# Wait for startup (30 seconds)
echo "Waiting for aggregator to start..."
sleep 30

# Verify started
tail -20 /tmp/aggregator.log | grep "Started"
```

**Expected Log Output:**
```
Started AggregatorApplication in X.XXX seconds
stream-client [aggregator-service-...] Started 1 stream threads
```

**If Failed:**
- Check: `tail -50 /tmp/aggregator.log | grep ERROR`
- Common issue: "State directory locked" → Run: `rm -rf /tmp/kafka-streams`

---

### Step 2: Start Database Consumer 💾

**Purpose:** Persists ticks and candles from Kafka → QuestDB  
**MUST START SECOND** before backfill

```bash
cd /Users/mhiteshkumar/QuantStream/database-consumer

# Start in background
nohup mvn spring-boot:run > /tmp/database-consumer.log 2>&1 &

# Save PID
DB_CONSUMER_PID=$!
echo "Database Consumer PID: $DB_CONSUMER_PID"

# Wait for startup (30 seconds)
echo "Waiting for database consumer to start..."
sleep 30

# Verify started
tail -20 /tmp/database-consumer.log | grep "Started"
```

**Expected Log Output:**
```
Started DatabaseConsumerApplication in X.XXX seconds
Listening to Kafka topics: [market-data, candles-1m, trading-signals]
```

**Verification:**
```bash
# Check consumer groups registered
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --list 2>/dev/null | grep questdb
```

**Expected:** `questdb-consumer-group`

---

### Step 3: Run Backfill (60 Days) 📊

**Purpose:** Generate 60 days of historical market data  
**Data Generated:** 51,840,000 ticks (60 days × 24 hours × 3600 ticks/hour × 10 symbols)  
**Rate:** 1 tick per second per symbol = 3,600 ticks/hour per symbol

```bash
cd /Users/mhiteshkumar/QuantStream/data-generator

echo "Starting 60-day backfill..."
echo "Expected: ~5-7 minutes to generate 51,840,000 ticks"
echo "Progress will be logged to /tmp/backfill.log"

# Run backfill (will exit automatically when done)
mvn spring-boot:run \
  -Dspring-boot.run.arguments="--backfill.enabled=true --backfill.days=60 --backfill.ticks-per-hour=3600" \
  > /tmp/backfill.log 2>&1

echo "✅ Backfill completed"
```

**Monitor Progress (in another terminal):**
```bash
tail -f /tmp/backfill.log | grep -E "(Progress|Generated|✅)"
```

**Expected Final Output:**
```
✅ Generated 51840000 ticks from 2026-05-24T... to 2026-07-23T...
BACKFILL COMPLETE - Exiting
```

**Verification:**
```bash
# Check Kafka topic message count
docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 \
  --topic market-data \
  --time -1 | awk -F: '{sum+=$3} END {print "Total ticks in Kafka:", sum}'
```

**Expected:** `Total ticks in Kafka: 51840000`

---

### Step 4: Wait for Processing Completion ⏳

**Purpose:** Aggregator and database-consumer process data asynchronously  
**Expected Time:** 10-15 minutes (processing 51.8M ticks)

```bash
echo "Waiting for aggregator and database-consumer to process all data..."
echo "Monitoring candles_1m table (expected: 864,000 rows)..."
echo "Note: 51.8M ticks ÷ 60 ticks per candle = 864K candles"

# Monitor progress (check every 30 seconds due to large volume)
for i in {1..40}; do
  count=$(curl -s -G http://localhost:9001/exec \
    --data-urlencode "query=SELECT count(*) FROM candles_1m" | jq -r '.dataset[0][0]')
  echo "[$i/40] candles_1m: $count rows (target: 864,000)"
  
  # Stop when count reaches expected value and stabilizes
  if [ "$count" -ge 860000 ] && [ "$i" -gt 3 ]; then
    sleep 20
    count2=$(curl -s -G http://localhost:9001/exec \
      --data-urlencode "query=SELECT count(*) FROM candles_1m" | jq -r '.dataset[0][0]')
    
    if [ "$count" == "$count2" ]; then
      echo "✅ Processing complete (count stabilized at $count)"
      break
    fi
  fi
  
  sleep 30
done
```

**Expected Final Count:** ~864,000 candles (60 days × 24 hours × 60 minutes × 10 symbols)

**Verification:**
```bash
# Check counts per symbol
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT symbol, count(*) as candles FROM candles_1m GROUP BY symbol ORDER BY symbol" | jq .

# Check date range
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT min(timestamp) as earliest, max(timestamp) as latest, count(*) as total FROM candles_1m" | jq .
```

**Expected:**
- Each symbol: ~86,400 candles (60 days × 24 hours × 60 minutes)
- Date range: ~60 days from earliest to latest
- Total: ~864,000 candles

**Sample Data Check:**
```bash
# View sample candles for AAPL (should have proper OHLC with H≠L)
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT * FROM candles_1m WHERE symbol='AAPL' ORDER BY timestamp LIMIT 5" \
  | jq -r '.dataset[] | "Time: \(.[6]) | O:\(.[1]) H:\(.[2]) L:\(.[3]) C:\(.[4]) V:\(.[5])"'
```

**Expected:** Each candle should have meaningful OHLC variation (not O=H=L=C), indicating 60 ticks were aggregated per candle.

---

### Step 5: Aggregate to Daily Candles 📅

**Purpose:** Create candles_1d table by aggregating 1-minute candles  
**SQL:** Groups 86,400 1-minute candles → 1 daily candle per symbol

```bash
echo "Aggregating candles_1m → candles_1d..."

# Execute aggregation query
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=
INSERT INTO candles_1d
SELECT 
  symbol,
  first(open) as open,
  max(high) as high,
  min(low) as low,
  last(close) as close,
  sum(volume) as volume,
  timestamp_floor('d', timestamp) as date
FROM candles_1m
SAMPLE BY 1d
ALIGN TO CALENDAR
" | jq .
```

**Expected Output:** `{"ddl":"OK"}`

**Verification:**
```bash
# Check row count
total=$(curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT count(*) as total FROM candles_1d" | jq -r '.dataset[0][0]')
echo "Total daily candles: $total"
echo "Expected: ~600 (60 days × 10 symbols)"

# Check per symbol
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT symbol, count(*) as days FROM candles_1d GROUP BY symbol ORDER BY symbol" | jq .

# Check date range
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT min(date) as earliest, max(date) as latest FROM candles_1d" | jq .

# Sample data for AAPL
echo ""
echo "Sample AAPL daily candles (last 5 days):"
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT date, open, high, low, close FROM candles_1d WHERE symbol='AAPL' ORDER BY date DESC LIMIT 5" | jq -r '.dataset[] | "\(.[0]) | O:\(.[1]) H:\(.[2]) L:\(.[3]) C:\(.[4])"'
```

**Expected:**
- Total: ~600 rows
- Each symbol: ~60 days
- Date range: ~60 days span
- Sample shows valid OHLC data (High > Low, Open/Close between High/Low)

---

### Step 6: Start Strategy Engine 🎯

**Purpose:** Query candles_1d, calculate indicators, generate trading signals

```bash
cd /Users/mhiteshkumar/QuantStream/strategy-engine

echo "Starting Strategy Engine..."
echo "Strategies will query candles_1d for 50 days of data"

# Start in background
nohup mvn spring-boot:run > /tmp/strategy-engine.log 2>&1 &

# Save PID
STRATEGY_PID=$!
echo "Strategy Engine PID: $STRATEGY_PID"

# Wait for startup (30 seconds)
echo "Waiting for strategy engine to start..."
sleep 30

# Verify started
tail -20 /tmp/strategy-engine.log | grep "Started"
```

**Expected Log Output:**
```
Started StrategyEngineApplication in X.XXX seconds
Scheduler started - strategies will run every 60 seconds
```

**Monitor Signal Generation:**
```bash
# Watch for first signal generation (wait 1-2 minutes)
tail -f /tmp/strategy-engine.log | grep -E "(analyzing|signal|BUY|SELL)"
```

**Expected Logs:**
```
RSI strategy analyzing AAPL: prices=[180.5, 179.8, ...]
MA Crossover detected: AAPL MA(10)=181.2 crosses above MA(50)=178.5
Signal generated: {"symbol":"AAPL","action":"BUY","strategy":"MA_CROSSOVER","confidence":0.85}
```

---

### Step 7: Verify Signals in Database 📈

**Purpose:** Confirm signals are persisted to QuestDB

```bash
# Wait 2 minutes for signals to generate and persist
echo "Waiting 2 minutes for signal generation..."
sleep 120

# Check signals table
signal_count=$(curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT count(*) FROM signals" | jq -r '.dataset[0][0]')
echo "Total signals generated: $signal_count"

# Show recent signals
echo ""
echo "Recent signals:"
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT * FROM signals ORDER BY timestamp DESC LIMIT 10" | jq .

# Count per strategy
echo ""
echo "Signals per strategy:"
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT strategy, count(*) as count FROM signals GROUP BY strategy ORDER BY count DESC" | jq .
```

**Expected:**
- Total signals: > 0 (should have multiple signals)
- Strategies represented: RSI, MA_CROSSOVER, MACD, etc.
- Actions: Mix of BUY and SELL
- Confidence: Values between 0.0 and 1.0

---

## Verification

### Complete Pipeline Health Check

```bash
echo "========================================="
echo "QUANTSTREAM PIPELINE VERIFICATION"
echo "========================================="
echo ""

# 1. Services Running
echo "1. Services Status:"
ps aux | grep -E "aggregator|database-consumer|strategy-engine" | grep -v grep | awk '{print "   ✓ " $11 " (PID: " $2 ")"}'
service_count=$(ps aux | grep -E "aggregator|database-consumer|strategy-engine" | grep -v grep | wc -l)
echo "   Total: $service_count/3 services running"
echo ""

# 2. QuestDB Tables
echo "2. QuestDB Data:"
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=
SELECT 
  (SELECT count(*) FROM ticks) as ticks,
  (SELECT count(*) FROM candles_1m) as candles_1m,
  (SELECT count(*) FROM candles_1d) as candles_1d,
  (SELECT count(*) FROM signals) as signals
" | jq -r '.dataset[0] | "   ticks:       \(.[0])\n   candles_1m:  \(.[1])\n   candles_1d:  \(.[2])\n   signals:     \(.[3])"'
echo ""

# 3. Data Quality Checks
echo "3. Data Quality:"

# Check for 50+ days per symbol (needed for MA(50))
min_days=$(curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT min(cnt) as min_days FROM (SELECT symbol, count(*) as cnt FROM candles_1d GROUP BY symbol)" | jq -r '.dataset[0][0]')
echo "   Min days per symbol: $min_days (need: ≥50)"

if [ "$min_days" -ge 50 ]; then
  echo "   ✅ PASS: All symbols have ≥50 days for MA(50)"
else
  echo "   ❌ FAIL: Some symbols have <50 days"
fi

# Check date range
date_range=$(curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT min(date) as earliest, max(date) as latest FROM candles_1d" | jq -r '.dataset[0] | "\(.[0]) to \(.[1])"')
echo "   Date range: $date_range"
echo ""

# 4. Strategy Queries
echo "4. Strategy Engine Test:"
aapl_days=$(curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT count(*) FROM candles_1d WHERE symbol='AAPL'" | jq -r '.dataset[0][0]')
echo "   AAPL daily candles: $aapl_days"

if [ "$aapl_days" -ge 50 ]; then
  echo "   ✅ PASS: Strategies can calculate MA(50)"
else
  echo "   ❌ FAIL: Not enough data for MA(50)"
fi
echo ""

# 5. Signal Generation
echo "5. Signal Generation:"
signal_count=$(curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT count(*) FROM signals" | jq -r '.dataset[0][0]')
strategy_count=$(curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT count(DISTINCT strategy) FROM signals" | jq -r '.dataset[0][0]')
echo "   Total signals: $signal_count"
echo "   Active strategies: $strategy_count/10"

if [ "$signal_count" -gt 0 ]; then
  echo "   ✅ PASS: Signals being generated"
else
  echo "   ❌ FAIL: No signals yet (wait 2 minutes and re-check)"
fi
echo ""

echo "========================================="
echo "VERIFICATION COMPLETE"
echo "========================================="
```

**Expected Output:**
```
=========================================
QUANTSTREAM PIPELINE VERIFICATION
=========================================

1. Services Status:
   ✓ aggregator (PID: XXXXX)
   ✓ database-consumer (PID: XXXXX)
   ✓ strategy-engine (PID: XXXXX)
   Total: 3/3 services running

2. QuestDB Data:
   ticks:       864000
   candles_1m:  14400
   candles_1d:  600
   signals:     42

3. Data Quality:
   Min days per symbol: 60 (need: ≥50)
   ✅ PASS: All symbols have ≥50 days for MA(50)
   Date range: 2026-05-24T00:00:00.000000Z to 2026-07-23T00:00:00.000000Z

4. Strategy Engine Test:
   AAPL daily candles: 60
   ✅ PASS: Strategies can calculate MA(50)

5. Signal Generation:
   Total signals: 42
   Active strategies: 8/10
   ✅ PASS: Signals being generated

=========================================
VERIFICATION COMPLETE
=========================================
```

---

## Troubleshooting

### Issue 1: Aggregator Won't Start

**Error:** `Unable to obtain lock as state directory is already locked`

**Solution:**
```bash
rm -rf /tmp/kafka-streams
cd aggregator && mvn spring-boot:run
```

---

### Issue 2: No Data in candles_1m

**Symptoms:**
- Backfill completed but candles_1m is empty
- Aggregator logs show no activity

**Diagnosis:**
```bash
# Check if aggregator is running
ps aux | grep aggregator | grep -v grep

# Check aggregator logs
tail -50 /tmp/aggregator.log | grep -E "(ERROR|Exception)"

# Check Kafka topic exists
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list | grep market-data
```

**Solution:**
- Ensure aggregator was started BEFORE backfill
- Restart: `pkill -f aggregator && rm -rf /tmp/kafka-streams && cd aggregator && mvn spring-boot:run &`

---

### Issue 3: Strategies Report "Not Enough Data"

**Error in logs:** `Not enough data for AAPL: 30 candles (need 50)`

**Diagnosis:**
```bash
# Check candles_1d per symbol
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT symbol, count(*) FROM candles_1d GROUP BY symbol" | jq .
```

**Solution:**
- If < 50 days per symbol: Run longer backfill (increase days)
- If data exists but strategies don't see it: Restart strategy-engine

---

### Issue 4: Wrong Date Aggregation

**Symptoms:**
- candles_1d has 1000+ rows per symbol (should be ~60)
- Multiple rows per calendar day

**Diagnosis:**
```bash
# Check sample data
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=SELECT date, count(*) FROM candles_1d WHERE symbol='AAPL' GROUP BY date ORDER BY date LIMIT 10" | jq .
```

**Solution:**
```bash
# Truncate and re-aggregate with correct query
curl -s -G http://localhost:9001/exec --data-urlencode "query=TRUNCATE TABLE candles_1d"

# Use timestamp_floor function (NOT cast)
curl -s -G http://localhost:9001/exec \
  --data-urlencode "query=
INSERT INTO candles_1d
SELECT 
  symbol,
  first(open) as open,
  max(high) as high,
  min(low) as low,
  last(close) as close,
  sum(volume) as volume,
  timestamp_floor('d', timestamp) as date
FROM candles_1m
SAMPLE BY 1d
ALIGN TO CALENDAR
"
```

---

### Issue 5: Kafka Topic Has Stale Data

**Symptoms:**
- Inconsistent symbol counts
- Old timestamps in data

**Solution:**
```bash
# Delete all Kafka topics
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic market-data
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic candles-1m
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic trading-signals

# Truncate all tables
for table in ticks candles_1m candles_1d signals; do
  curl -s -G http://localhost:9001/exec --data-urlencode "query=TRUNCATE TABLE $table"
done

# Re-run from Step 1
```

---

## Cleanup

### Stop All Services

```bash
# Stop Java services
pkill -f "data-generator"
pkill -f "aggregator"
pkill -f "database-consumer"
pkill -f "strategy-engine"

# Verify stopped
ps aux | grep -E "aggregator|database-consumer|strategy-engine" | grep -v grep
```

### Stop Docker Containers

```bash
cd /Users/mhiteshkumar/QuantStream
docker-compose down

# Verify stopped
docker ps
```

### Clean State (Optional)

```bash
# Remove Kafka Streams state
rm -rf /tmp/kafka-streams

# Remove QuestDB data (CAUTION: Deletes all data!)
docker volume rm quantstream_questdb-data

# Remove log files
rm -f /tmp/aggregator.log /tmp/database-consumer.log /tmp/strategy-engine.log /tmp/backfill.log
```

---

## Success Criteria Checklist

- [ ] Docker containers running (4/4: kafka, zookeeper, questdb, kafka-ui)
- [ ] Aggregator started successfully (with 365-day grace period)
- [ ] Database consumer started successfully
- [ ] Backfill completed (51,840,000 ticks generated at 1 tick/second)
- [ ] candles_1m populated (~864,000 rows = 60 days × 24 hrs × 60 min × 10 symbols)
- [ ] candles_1d populated (~600 rows = 60 days × 10 symbols)
- [ ] Each symbol has ≥50 days in candles_1d
- [ ] Each 1-minute candle has proper OHLC variation (60 ticks aggregated)
- [ ] Strategy engine started successfully
- [ ] Signals generated and persisted to QuestDB
- [ ] All 10 strategies represented in signals table
- [ ] No errors in service logs

---

## Key Numbers Summary

### Data Volume
- **Total Ticks:** 51,840,000 (60 days × 24 hrs × 3600 sec × 10 symbols)
- **Tick Rate:** 1 tick/second per symbol = 3,600 ticks/hour per symbol
- **Total Candles (1-min):** 864,000 (60 days × 24 hrs × 60 min × 10 symbols)
- **Ticks per Candle:** 60 ticks aggregated into each 1-minute candle
- **Total Daily Candles:** 600 (60 days × 10 symbols)
- **Minutes per Day:** 1,440 (aggregated into 1 daily candle)

### Expected Processing Time
- **Backfill:** 5-7 minutes (generating 51.8M ticks)
- **Aggregation:** 10-15 minutes (processing 51.8M ticks → 864K candles)
- **Daily Aggregation:** < 1 minute (864K candles → 600 daily candles)
- **Total:** ~20-25 minutes end-to-end

### Data Quality Verification
Each 1-minute candle should have:
- **60 ticks aggregated** (not just 1 tick)
- **OHLC variation:** High > Low, Close ≠ Open (proper price movement)
- **Meaningful volume:** Sum of 60 tick volumes

---

## Quick Reference

### Important URLs
- **QuestDB Console:** http://localhost:9001
- **Kafka UI:** http://localhost:8080

### Important Ports
- **Kafka:** 9092
- **Zookeeper:** 2181
- **QuestDB Web Console:** 9001
- **QuestDB PostgreSQL:** 8812
- **Kafka UI:** 8080

### Service Ports
- **Aggregator:** 8084
- **Database Consumer:** 8082
- **Strategy Engine:** 8083

### Log Files
- **Aggregator:** `/tmp/aggregator.log`
- **Database Consumer:** `/tmp/database-consumer.log`
- **Strategy Engine:** `/tmp/strategy-engine.log`
- **Backfill:** `/tmp/backfill.log`

### Key Queries
```sql
-- Check all table counts
SELECT 
  (SELECT count(*) FROM ticks) as ticks,
  (SELECT count(*) FROM candles_1m) as candles_1m,
  (SELECT count(*) FROM candles_1d) as candles_1d,
  (SELECT count(*) FROM signals) as signals;

-- Check days per symbol
SELECT symbol, count(*) as days 
FROM candles_1d 
GROUP BY symbol 
ORDER BY symbol;

-- Check recent signals
SELECT * FROM signals 
ORDER BY timestamp DESC 
LIMIT 20;
```

---

**End of Execution Guide** 🚀
