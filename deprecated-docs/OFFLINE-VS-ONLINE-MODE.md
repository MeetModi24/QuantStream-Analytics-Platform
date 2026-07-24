# Offline (Backfill) vs Online (Real-Time) Mode

## Overview

QuantStream supports two operational modes:
- **Offline/Backfill Mode**: Generate historical data quickly for backtesting
- **Online/Real-Time Mode**: Continuous data generation for live trading

This document explains the differences, use cases, and how to transition between modes.

---

## Current State: Offline/Backfill Mode

### What We Just Did

```
┌─────────────────┐
│ data-generator  │  Runs ONCE
│ (backfill=true) │  Generates 60 days in 6 seconds
└────────┬────────┘  Exits after completion
         │
         ↓ 864k candles (historical)
┌────────────────┐
│ candles-1m     │  Static dataset
│ Kafka topic    │  No new messages
└────────┬───────┘
         │
         ↓ Batch consume
┌────────────────┐
│ database-      │  Runs until queue empty
│ consumer       │  Then keeps listening (but nothing new)
└────────┬───────┘
         │
         ↓ 864k rows
┌────────────────┐
│ QuestDB        │  Historical data only
│ candles_1m     │  Suitable for backtesting
└────────────────┘
```

**Purpose**: Generate historical data for backtesting strategies

**Characteristics**:
- Runs once and exits
- Generates data as fast as possible (864k candles in 6 seconds)
- Historical timestamps (60 days ago → today)
- Static dataset (no new messages after completion)

---

## Online Mode: Real-Time Production

### How It Works

```
┌─────────────────┐
│ data-generator  │  Runs FOREVER (continuous loop)
│ (backfill=false)│  Generates 1 candle/minute/symbol
│                 │  Never exits - 24/7 operation
└────────┬────────┘
         │
         ↓ New candle every minute (10 candles/min for 10 symbols)
┌────────────────┐
│ candles-1m     │  LIVE stream
│ Kafka topic    │  Continuously receiving new candles
└────────┬───────┘
         │
         ├──→ Consumer 1: database-consumer (persist to QuestDB)
         ├──→ Consumer 2: api-gateway (WebSocket broadcast to frontend)
         └──→ Consumer 3: strategy-engine (could consume directly)
         
┌────────────────┐
│ database-      │  Runs FOREVER
│ consumer       │  Continuously persisting new candles
└────────┬───────┘
         │
         ↓ Growing dataset (appends every minute)
┌────────────────┐
│ QuestDB        │  Historical + Live data
│ candles_1m     │  Continuously growing
└────────────────┘
         ↑
         │ Query historical data
┌────────┴───────┐
│ api-gateway    │  Runs FOREVER
│                │  - Serves historical candles (REST)
│                │  - Broadcasts live candles (WebSocket)
└────────┬───────┘
         │
         ↓ WebSocket updates
┌────────────────┐
│ Frontend       │  User's browser
│ Dashboard      │  Chart updates in real-time
└────────────────┘
```

**Purpose**: Live trading system with real-time data

**Characteristics**:
- Runs forever (daemon process)
- Real-time pace (1 candle per minute per symbol = 10/min total)
- Current timestamps (always "now")
- Live stream (continuous new messages)
- Frontend updates in real-time

---

## Key Differences

| Aspect | Offline (Backfill) | Online (Real-Time) |
|--------|-------------------|-------------------|
| **Execution** | Run once and exit | Run forever (daemon) |
| **Speed** | As fast as possible (6 sec for 60 days) | Real-time pace (1 candle/min/symbol) |
| **Timestamps** | Historical (60 days ago → today) | Current (always "now") |
| **Data Pattern** | Static dataset | Continuous stream |
| **Kafka Behavior** | Consumers catch up quickly, then idle | Consumers continuously process |
| **Frontend** | Static charts (no updates) | Live charts (updates every minute) |
| **Use Case** | Backtesting, ML training, initial setup | Live trading, monitoring, production |

---

## How to Switch to Online Mode

### Option 1: Keep Backfill + Add Live Generation (Recommended)

Keep the 864k historical candles we already have, then start generating new candles in real-time going forward.

**Step 1**: Ensure backfill is disabled

File: `data-generator/src/main/resources/application.yml`

```yaml
backfill:
  enabled: false    # ← Must be false for online mode
```

**Step 2**: Verify MarketDataGenerator configuration

The code already has `MarketDataGenerator` for continuous generation. It's automatically enabled when `backfill.enabled=false`.

**Step 3**: Run data-generator in continuous mode

```bash
cd /Users/mhiteshkumar/QuantStream/data-generator
mvn spring-boot:run

# Runs forever, press Ctrl+C to stop
```

**Step 4**: Database-consumer keeps running

- Already running from earlier
- Will automatically consume new candles as they arrive
- QuestDB table grows: 864,000 → 864,010 → 864,020 → ...

**Result**:
- Historical data: 2026-05-25 to 2026-07-24 (864k candles) ✅
- Live data: 2026-07-24 onwards (continuous) ✅
- Gap-free dataset ✅

### Option 2: Fresh Start with Online Only

Delete historical data, start fresh with live generation only.

**Step 1**: Clean up

```bash
# Delete Kafka topic
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --delete --topic candles-1m

# Recreate topic
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --create --topic candles-1m --partitions 3 --replication-factor 1

# Drop QuestDB table
curl -G http://localhost:9001/exec --data-urlencode "query=DROP TABLE IF EXISTS candles_1m"

# Recreate table
curl -G http://localhost:9001/exec --data-urlencode "query=CREATE TABLE candles_1m (symbol SYMBOL, open DOUBLE, high DOUBLE, low DOUBLE, close DOUBLE, volume DOUBLE, timestamp TIMESTAMP) timestamp(timestamp) PARTITION BY DAY"
```

**Step 2**: Run data-generator in continuous mode (same as Option 1)

**Result**:
- Only live data from today onwards
- No historical backfill

---

## Code Architecture for Online Mode

### 1. Data Generator (Already Supports Both Modes!)

File: `data-generator/src/main/java/com/quantstream/generator/service/MarketDataGenerator.java`

```java
@Service
@ConditionalOnProperty(name = "backfill.enabled", havingValue = "false", 
                       matchIfMissing = true)
public class MarketDataGenerator {
    
    @Scheduled(fixedRate = 60000)  // Every 60 seconds
    public void generateCandles() {
        Instant now = Instant.now();
        
        for (TokenConfig token : activeTokens) {
            // Generate 1 candle per symbol
            Candle candle = simulateCandle(token, now);
            kafkaTemplate.send("candles-1m", token.symbol(), candle);
        }
    }
}
```

**How it works**:
- `@Scheduled(fixedRate = 60000)` triggers every 60 seconds
- Generates 1 candle per symbol (10 total)
- Uses `Instant.now()` for current timestamp
- Runs forever until application stops

**To enable**: Set `backfill.enabled=false` in `application.yml`

### 2. Database Consumer (No Changes Needed!)

Already designed for continuous consumption. Just keeps running and persisting whatever arrives.

### 3. Strategy Engine (Could Be Enhanced)

**Current Design**: Polls QuestDB every 60 seconds

```java
@Scheduled(fixedRate = 60000)
public void runStrategies() {
    // Query QuestDB for latest candles
    // Analyze
    // Generate signals
}
```

**Future Enhancement**: Event-driven (consume Kafka directly)

```java
@KafkaListener(topics = "candles-1m")
public void onNewCandle(Candle candle) {
    // Analyze immediately
    // Generate signal if needed
    // Lower latency!
}
```

---

## Practical Example: 1 Minute in Online Mode

### Timeline

```
00:00:00 - data-generator scheduled task triggers
00:00:01 - Generates 10 candles (1 per symbol) with timestamp=2026-07-24T06:00:00Z
00:00:02 - Sends all 10 candles to Kafka topic: candles-1m

00:00:03 - database-consumer receives batch
00:00:04 - Persists 10 candles to QuestDB

00:00:05 - api-gateway (if built) receives candles
00:00:06 - Broadcasts to all WebSocket clients

00:00:07 - Frontend chart updates (10 new candles appear)

00:01:00 - data-generator scheduled task triggers again
00:01:01 - Generates next 10 candles with timestamp=2026-07-24T06:01:00Z
          ...cycle repeats forever
```

### Strategy Engine (Current Design)

```
00:00:00 - Scheduled task triggers
00:00:01 - Queries QuestDB: "SELECT * FROM candles_1m WHERE timestamp > ..."
00:00:02 - Analyzes latest candles with all 10 strategies
00:00:03 - Generates signals (BUY/SELL) to trading-signals topic
```

### User Experience

- User opens browser at 6:00 AM
- Sees historical chart (864k candles from past 60 days)
- Every minute, chart extends by 1 new candle per symbol
- Chart "grows" in real-time
- Strategy signals appear in leaderboard

---

## Why Both Modes Are Needed

### Backfill Mode (What We Did)

**Purpose**: Generate historical data for:
- ✅ Backtesting strategies (did strategy work in past?)
- ✅ Training ML models (need historical patterns)
- ✅ Initial chart display (show 60 days immediately)
- ✅ Strategy validation before going live

**When to use**:
- First time setup
- After system downtime (fill gaps)
- Testing new strategies on historical data

### Online Mode (Production)

**Purpose**: Live trading system
- ✅ Real-time price tracking
- ✅ Live strategy signals
- ✅ Active trading decisions
- ✅ User monitoring

**When to use**:
- After initial setup complete
- In production environment
- When users are actively watching/trading

### Hybrid Approach (Recommended)

1. Run backfill once to populate historical data ✅ (Done!)
2. Switch to online mode for continuous operation
3. Result: Seamless historical + live data

---

## Technical Considerations for Online Mode

### 1. Kafka Retention

**Backfill**: Don't need to keep Kafka messages long (can delete after DB persist)

**Online**: Keep last 7 days for replay capability

```properties
# Kafka server.properties
log.retention.hours=168  # 7 days
```

### 2. Database Growth

- 10 candles/minute = 14,400 candles/day
- 1 year = 5.26M candles
- QuestDB partitions by day automatically (already configured)

### 3. WebSocket Connections

- Need to handle multiple concurrent browser connections
- Each user gets same broadcast (pub/sub pattern)
- Consider connection limits (1000s of users)

### 4. Error Handling

| Failure | Impact | Recovery |
|---------|--------|----------|
| data-generator crashes | No new data | Kafka retains last message, resume from there |
| QuestDB down | Data buffered in Kafka | Consumers catch up when QuestDB recovers |
| Frontend disconnects | User sees stale data | Reconnect, backfill missed candles via REST API |

### 5. Monitoring

**Metrics to Track**:
- ✅ Message lag (are consumers keeping up?)
- ✅ Candle generation rate (should be exactly 10/min)
- ✅ Alert if data stops flowing
- ✅ WebSocket connection count
- ✅ QuestDB query performance

---

## New Components Needed for Full Online Mode

### 1. API Gateway with WebSocket

**What it does**:
- Consumes `candles-1m` topic
- Broadcasts to all connected browser clients
- Each new candle triggers chart update

**Endpoints**:
- REST: `GET /api/candles/{symbol}?from=...&to=...` (historical data)
- WebSocket: `/ws/candles` (STOMP protocol for live updates)

### 2. Frontend Dashboard

**What it does**:
- WebSocket client (STOMP)
- Lightweight Charts library
- Updates chart when new candle received

**Features**:
- Candlestick chart with zoom/pan
- Real-time updates (new candle every minute)
- Strategy leaderboard
- Signal history

### 3. Strategy Engine Enhancement (Optional)

**Current**: Polls QuestDB every 60 seconds

**Enhancement**: Consume `candles-1m` topic directly (event-driven)

**Benefit**: Lower latency (immediate signal generation)

---

## Summary: Offline → Online Transition

### Current State (Offline)

✅ 864k historical candles generated  
✅ All data in QuestDB  
✅ Perfect for backtesting  
❌ No new data coming in  
❌ Charts would be static  

### To Enable Online Mode

1. Set `backfill.enabled=false` in data-generator
2. Run data-generator continuously (`mvn spring-boot:run`)
3. Database-consumer already handles live data (no changes)
4. Build api-gateway to expose WebSocket
5. Build frontend to display live charts

**Result**: Live trading system with real-time updates! 🚀

### Best Practice (Hybrid)

- ✅ Keep the 864k historical candles (don't delete)
- ✅ Start online mode from current time
- ✅ Result: 60 days history + live data going forward
- ✅ Users see full chart: past 60 days + today's live action

---

## Quick Reference

### Enable Online Mode

```bash
# 1. Edit config
vim data-generator/src/main/resources/application.yml
# Set: backfill.enabled: false

# 2. Run data-generator (continuous)
cd data-generator
mvn spring-boot:run

# 3. Database-consumer already running (keep it running)

# Result: 10 new candles every minute!
```

### Switch Back to Backfill Mode

```bash
# 1. Stop data-generator (Ctrl+C)

# 2. Edit config
vim data-generator/src/main/resources/application.yml
# Set: backfill.enabled: true

# 3. Run backfill
mvn spring-boot:run -Dspring-boot.run.arguments="--backfill.enabled=true --backfill.days=60"
```

### Monitor Live Data

```bash
# Check candles-1m topic (should see new messages every minute)
docker exec kafka kafka-console-consumer --bootstrap-server localhost:9092 --topic candles-1m --from-beginning | tail -10

# Check QuestDB count (should increase by 10 every minute)
curl -G http://localhost:9001/exec --data-urlencode "query=SELECT COUNT(*) FROM candles_1m"

# Watch real-time
watch -n 10 'curl -s -G http://localhost:9001/exec --data-urlencode "query=SELECT COUNT(*) FROM candles_1m" | grep dataset'
```

---

## Next Steps

Now that you understand both modes:

1. ✅ Keep system in offline mode (historical data ready for backtesting)
2. ⏭️ Test strategy-engine with historical data
3. ⏭️ Build API Gateway (REST + WebSocket)
4. ⏭️ Build Frontend Dashboard
5. ⏭️ Switch to online mode for live trading

See: [NEXT-STEPS.md](./NEXT-STEPS.md) for detailed implementation plan.
