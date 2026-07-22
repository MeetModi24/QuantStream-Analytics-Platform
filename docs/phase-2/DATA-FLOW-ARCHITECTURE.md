# Phase 2 Data Flow Architecture

**Date:** 2026-07-21  
**Status:** Current Implementation  
**Purpose:** Document complete data flow from ticks to trading signals

---

## Overview

Phase 2 adds intelligence to the raw market data pipeline:
1. **Aggregator** - Creates 1-minute OHLC candles from ticks
2. **Strategy Engine** - Generates trading signals using technical indicators
3. **Extended Database Consumer** - Persists candles and signals

**Key Architectural Decision:** Strategy Engine queries QuestDB (not Kafka) for historical data.

---

## Complete Data Flow Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        PHASE 1: DATA INGESTION                  │
└─────────────────────────────────────────────────────────────────┘

┌──────────────────┐
│ Data Generator   │  Generates 1 tick/second per symbol
│ (Spring Boot)    │  - 10 symbols (AAPL, MSFT, GOOGL, TSLA, AMZN,
│                  │                BTC, ETH, SOL, AVAX, MATIC)
│                  │  - Geometric Brownian Motion
└────────┬─────────┘
         │ KafkaTemplate.send("market-data", tick)
         ↓
╔════════════════════╗
║ Kafka Topic:       ║
║ "market-data"      ║  Message: Tick {symbol, price, volume, timestamp}
║                    ║  Retention: 1 day
╚════════┬═══════════╝
         │
         ├──────────────────────────┬────────────────────────┐
         ↓                          ↓                        ↓

┌─────────────────────────────────────────────────────────────────┐
│                    PHASE 2: INTELLIGENCE LAYER                   │
└─────────────────────────────────────────────────────────────────┘

┌────────────────┐      ┌─────────────────┐      ┌────────────────┐
│ Database       │      │ Aggregator      │      │ (reserved for  │
│ Consumer       │      │ (Kafka Streams) │      │ future services)│
│                │      │                 │      └────────────────┘
│ Consumes:      │      │ Processing:     │
│ - market-data  │      │ 1. Groups by    │
│                │      │    symbol       │
│ Writes to:     │      │ 2. Windows by   │
│ - ticks table  │      │    1-min tumble │
│                │      │ 3. Aggregates:  │
└────────────────┘      │    - open       │
                        │    - high       │
                        │    - low        │
                        │    - close      │
                        │    - volume     │
                        │                 │
                        │ Emits when      │
                        │ window closes   │
                        └────────┬────────┘
                                 │ KafkaStream.to("candles-1m")
                                 ↓
                        ╔════════════════════╗
                        ║ Kafka Topic:       ║
                        ║ "candles-1m"       ║  Message: Candle {symbol, OHLC, volume, window_start}
                        ║                    ║  Retention: 1 day
                        ╚════════┬═══════════╝
                                 │
                                 ↓
                        ┌────────────────────┐
                        │ Database Consumer  │
                        │ (Extended)         │
                        │                    │
                        │ Consumes:          │
                        │ - candles-1m       │
                        │                    │
                        │ Writes to:         │
                        │ - candles_1m table │
                        └──────────┬─────────┘
                                   │
                                   ↓
╔════════════════════════════════════════════════════════════════╗
║                         QUESTDB                                ║
║                                                                ║
║  ┌──────────────────┐  ┌──────────────────┐                  ║
║  │ ticks            │  │ candles_1d       │                  ║
║  │                  │  │                  │                  ║
║  │ Raw price data   │  │ 1-day OHLC       │                  ║
║  │ 10 rows/sec      │  │ 10 rows/day      │                  ║
║  │ Retention: 7 days│  │ Retention: ∞     │                  ║
║  └──────────────────┘  └─────────┬────────┘                  ║
║                                   │                            ║
║                                   │ JDBC Query                 ║
║                                   │ SELECT close FROM          ║
║                                   │ candles_1d WHERE...        ║
╚═══════════════════════════════════╩════════════════════════════╝
                                    │
                                    ↓
                        ┌───────────────────────────┐
                        │   Strategy Engine         │
                        │   (Spring Boot)           │
                        │                           │
                        │   @Scheduled(60 seconds)  │
                        │   ┌─────────────────────┐ │
                        │   │ For each strategy:  │ │
                        │   │   For each symbol:  │ │
                        │   │     1. Query        │ │
                        │   │        candles_1d   │ │
                        │   │     2. Calculate    │ │
                        │   │        indicators   │ │
                        │   │     3. Detect       │ │
                        │   │        patterns     │ │
                        │   │     4. Generate     │ │
                        │   │        signal       │ │
                        │   └─────────────────────┘ │
                        │                           │
                        │   10 Strategies:          │
                        │   - RSI                   │
                        │   - MA Crossover          │
                        │   - MACD                  │
                        │   - Bollinger Bands       │
                        │   - Stochastic            │
                        │   - Williams %R           │
                        │   - ADX                   │
                        │   - Donchian Channel      │
                        │   - ROC                   │
                        │   - VWAP                  │
                        └───────────┬───────────────┘
                                    │ KafkaTemplate.send("trading-signals", signal)
                                    ↓
                        ╔════════════════════╗
                        ║ Kafka Topic:       ║
                        ║ "trading-signals"  ║  Message: Signal {symbol, action, strategy, 
                        ║                    ║                    confidence, timestamp}
                        ║                    ║  Retention: 7 days
                        ╚════════┬═══════════╝
                                 │
                                 ↓
                        ┌────────────────────┐
                        │ Database Consumer  │
                        │ (Extended)         │
                        │                    │
                        │ Consumes:          │
                        │ - trading-signals  │
                        │                    │
                        │ Writes to:         │
                        │ - signals table    │
                        └──────────┬─────────┘
                                   │
                                   ↓
                        ╔══════════════════════╗
                        ║ QuestDB              ║
                        ║                      ║
                        ║ ┌──────────────────┐ ║
                        ║ │ signals          │ ║
                        ║ │                  │ ║
                        ║ │ Trading signals  │ ║
                        ║ │ ~100 rows/hour   │ ║
                        ║ │ Retention: ∞     │ ║
                        ║ └──────────────────┘ ║
                        ╚══════════════════════╝
```

---

## Critical: Why Strategy Engine Queries QuestDB (Not Kafka)

### The Question

**Should Strategy Engine consume from Kafka topic `candles-1m` or query QuestDB table `candles_1m`?**

### The Answer: Query QuestDB

**Reasoning:**

| Aspect | Kafka Consumption | QuestDB Query |
|--------|-------------------|---------------|
| **Data needed** | Historical (last N candles) | Historical (last N candles) |
| **Access pattern** | Would only get ONE candle at a time | Get exactly N candles in one query |
| **State management** | Must buffer 50 candles per symbol in memory | Stateless (data in database) |
| **Restart behavior** | Lose all buffered candles | Always has full history |
| **Query flexibility** | Can't query arbitrary ranges | Can query any time range |
| **Complexity** | Complex (buffering, state, cleanup) | Simple (one SQL query) |

### Example: MA(50) Calculation

**If consuming from Kafka:**
```java
// WRONG APPROACH
@KafkaListener(topics = "candles-1m")
public void onCandle(Candle candle) {
    // Problem: Only get ONE candle at a time
    String symbol = candle.getSymbol();
    
    // Must maintain buffer per symbol
    List<Candle> buffer = candleBuffers.get(symbol);
    buffer.add(candle);
    
    // Keep only last 50
    if (buffer.size() > 50) {
        buffer.remove(0);
    }
    
    // Can only calculate MA(50) once we have 50 candles buffered
    if (buffer.size() == 50) {
        double ma50 = calculateMA(buffer);
        // Generate signal...
    }
    
    // What if strategy engine restarts? Lost all buffered candles!
    // What about backtesting? Can't query arbitrary date ranges!
}
```

**Problems:**
1. ❌ Complex state management (buffer per symbol)
2. ❌ Memory overhead (50 candles × 10 symbols = 500 candles in RAM)
3. ❌ Lose state on restart
4. ❌ Can't backtest (no historical query ability)
5. ❌ Can't query arbitrary date ranges

**Querying QuestDB:**
```java
// CORRECT APPROACH
@Scheduled(fixedRate = 60000)
public void runStrategies() {
    for (String symbol : symbols) {
        // Simple: Get exactly what we need
        // Note: candles_1d table must be created and backfilled with 50+ days
        List<Double> prices = jdbcTemplate.query(
            "SELECT close FROM candles_1d " +
            "WHERE symbol = ? " +
            "ORDER BY date DESC " +
            "LIMIT 50",
            (rs, rowNum) -> rs.getDouble("close"),
            symbol
        );
        
        if (prices.size() >= 50) {
            double ma50 = calculateMA(prices, 50);  // MA(50 DAYS)
            // Generate signal...
        }
    }
}
```

**Benefits:**
1. ✅ Stateless (no buffering needed)
2. ✅ Zero memory overhead
3. ✅ Restart anytime, data persists
4. ✅ Can backtest (query any date range)
5. ✅ Simple, clean code

---

## When to Use Kafka Consumption vs QuestDB Queries

### Use Kafka Consumption For:

✅ **Real-time event processing** (just need latest event)
- Example: Frontend WebSocket pushing latest price to charts
- Example: Alerting service checking if price crosses threshold

✅ **Stream-to-stream transformations**
- Example: Aggregator creating candles from ticks
- Example: 5-minute candles aggregated from 1-minute candles

✅ **Event-driven reactions** (no historical context needed)
- Example: Send notification when new signal arrives
- Example: Log every trade to audit trail

### Use QuestDB Queries For:

✅ **Historical analysis** (need past N records)
- Example: Strategy Engine calculating MA(50) from last 50 candles ✓
- Example: Backtesting replaying past 30 days of data

✅ **Ad-hoc queries** (arbitrary time ranges)
- Example: Frontend fetching candles from 9 AM to 5 PM for chart
- Example: Analytics query: "Show me all BUY signals from last week"

✅ **Scheduled batch processing** (not continuous stream)
- Example: Strategy Engine running every 60 seconds ✓
- Example: Daily report generation

---

## Timing Example: Single Cycle

```
Time: 10:00:00 - 10:00:59
  Action: Data Generator produces 60 ticks (1/sec)
  Action: Aggregator accumulates ticks in 1-min window state

Time: 10:01:00.100 (window closes)
  Action: Aggregator emits candle to Kafka "candles-1m"
  Message: Candle {symbol: "AAPL", open: 180.0, high: 181.5, 
                    low: 179.5, close: 180.5, volume: 1.2M, 
                    window_start: 10:00:00}

Time: 10:01:00.150
  Action: Database Consumer receives candle from Kafka
  Action: Writes to QuestDB candles_1m table
  Query: INSERT INTO candles_1m VALUES (...)

Time: 10:01:05.000 (scheduler wakes up)
  Action: Strategy Engine executes
  
  For symbol "AAPL":
    Query: SELECT close FROM candles_1d 
           WHERE symbol = 'AAPL' 
           ORDER BY date DESC 
           LIMIT 50
    
    Result: [180.5, 179.8, 181.2, ..., 175.3]  (50 daily candles, newest first)
    
    Calculation: MA(50) = (180.5 + 179.8 + ... + 175.3) / 50 = 178.25  # MA(50 DAYS)
    
    Decision: If MA(10) crosses above MA(50) → Generate BUY signal
    
    Action: Signal {symbol: "AAPL", action: "BUY", strategy: "MA_CROSSOVER",
                    confidence: 0.85, timestamp: 10:01:05}
    
    Produce: KafkaTemplate.send("trading-signals", signal)

Time: 10:01:05.100
  Action: Database Consumer receives signal from Kafka
  Action: Writes to QuestDB signals table
  Query: INSERT INTO signals VALUES (...)
```

**Total latency (tick to signal): ~5 seconds** ✓

---

## QuestDB Table Usage

### ticks (Phase 1)
- **Purpose:** Raw price data for aggregation
- **Write rate:** 10 rows/second (1 per symbol)
- **Read rate:** Never queried by strategies (only by aggregator via Kafka)
- **Retention:** 7 days (rolling deletion)
- **Storage:** ~280 MB per week

### candles_1m (Phase 2)
- **Purpose:** 1-minute OHLC candles for strategy analysis
- **Write rate:** 10 rows/minute (1 per symbol)
- **Read rate:** 100 queries/minute (10 strategies × 10 symbols)
- **Retention:** Forever (strategies need historical data)
- **Storage:** ~730 MB per year

### signals (Phase 2)
- **Purpose:** Trading signals generated by strategies
- **Write rate:** ~20-50 rows/hour (depends on market conditions)
- **Read rate:** Queried by frontend/backtester (Phase 3+)
- **Retention:** Forever (for performance analysis)
- **Storage:** ~50 KB per year

---

## Why This Architecture is Correct

### 1. Separation of Concerns
- **Aggregator:** Stream processing (stateful, continuous)
- **Strategy Engine:** Batch processing (stateless, scheduled)
- **Database Consumer:** Persistence (writes only)

### 2. Resilience
- Strategy Engine can restart anytime (no state loss)
- Aggregator failure doesn't stop strategy execution (they use QuestDB)
- Database failure is isolated (doesn't affect stream processing)

### 3. Scalability
- Aggregator scales via Kafka partitions (if needed)
- Strategy Engine scales via thread pool (parallel strategy execution)
- QuestDB scales via time-based partitioning

### 4. Flexibility
- Backtesting: Query any historical date range from QuestDB
- Strategy changes: Just restart strategy-engine (no data migration)
- Frontend: Can show real-time candles (Kafka) OR historical charts (QuestDB)

---

## Common Misconceptions

### ❌ "Strategy Engine should consume candles from Kafka for real-time"

**Response:** Strategies run every 60 seconds (not real-time). They need historical context (last N candles), which requires database queries, not stream consumption. Kafka is for sub-second real-time updates (like frontend charts), not for batch analysis.

### ❌ "Querying database every 60 seconds is too slow"

**Response:** QuestDB can handle 10,000+ queries/second. Our load is 100 queries/minute (1.67/sec). Each query takes ~5-10ms. Total query time per cycle: 100 queries × 10ms = 1 second (acceptable for 60-second cycle).

### ❌ "We should cache candles in strategy engine to avoid database queries"

**Response:** This duplicates state (defeats the purpose of database). Cache invalidation is complex. QuestDB is already optimized for time-series queries. Premature optimization.

### ❌ "All data should flow through Kafka"

**Response:** Kafka is for event streaming, not data warehousing. QuestDB is for historical queries. Use the right tool for the right job.

---

## Implementation Status

### ✅ What Works
1. Data Generator → Kafka → Database Consumer → QuestDB `ticks` ✓
2. Aggregator creating candles from ticks → Kafka `candles-1m` ✓
3. Database Consumer writing to `candles_1m` table ✓
4. Strategy Engine framework with 10 strategies ✓
5. All indicator calculations (MA, RSI, MACD, etc.) ✓

### ❌ What Needs Fixing
**Problem:** All 10 strategies currently query `ticks` table instead of `candles_1d`

**Why this is wrong:**
- Ticks are irregular (arrive whenever trades happen)
- Technical indicators require regular time intervals
- MA(50) on "last 50 ticks" is meaningless (could be 50 seconds or variable duration)
- MA(50) on "last 50 daily candles" is correct (always 50 DAYS)

**Fix required:**
```java
// BEFORE (wrong)
SELECT price FROM ticks WHERE symbol = ? ORDER BY timestamp DESC LIMIT 50

// AFTER (correct)
// Note: candles_1d table must be created and backfilled with 50+ days
SELECT close FROM candles_1d WHERE symbol = ? ORDER BY date DESC LIMIT 50
```

**Estimated fix time:** 1-2 hours (create BaseStrategy class, refactor all 10 strategies)

---

## Next Steps

1. **Fix strategy queries** - Change from `ticks` to `candles_1d` (all 10 strategies)
2. **Test end-to-end** - Run system for 1 hour, verify signals generated correctly
3. **Add cleanup job** - Delete ticks older than 7 days (save storage)
4. **Document indicators** - Update comments to say "50 DAYS" not "50 periods"

---

## References

- [Kafka Streams vs QuestDB Queries](./concepts/04-kafka-streams-vs-questdb-queries.md) - Detailed comparison
- [Strategy Framework Guide](./guides/strategy-framework-guide.md) - How strategies are structured
- [Phase 2 Overview](./PHASE-2-OVERVIEW.md) - High-level architecture
