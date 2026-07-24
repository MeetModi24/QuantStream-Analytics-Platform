# Understanding OHLC Candles

## The Problem: Too Much Data

**Scenario:** BTC updates every second for 24 hours
- 1 price/second × 60 seconds × 60 minutes × 24 hours = **86,400 data points**
- Cannot display 86,400 points on a chart (unreadable)
- Browser would crash trying to render this

**Solution:** Aggregate data into time intervals (candles)

---

## What is an OHLC Candle?

**OHLC = Open, High, Low, Close**

A candle represents **all price movement** in a specific time interval, compressed into 4 values.

### Example: BTC from 2:00:00 PM to 2:00:59 PM

**Raw tick data (60 updates):**
```
14:00:00 → $50,000.00
14:00:01 → $50,001.50
14:00:02 → $50,000.50
14:00:03 → $50,002.00
14:00:04 → $49,999.00  <- Lowest
...
14:00:58 → $50,100.00  <- Highest
14:00:59 → $50,050.00
```

**Aggregated into 1-minute candle:**
```
Open:   $50,000.00  (first price at 14:00:00)
High:   $50,100.00  (highest price in this minute)
Low:    $49,999.00  (lowest price in this minute)
Close:  $50,050.00  (last price at 14:00:59)
Volume: 15,000      (total volume traded)
```

**Data reduction:** 60 values → 5 values (12x compression)

---

## Visual Representation

### Candlestick Chart

```
     │         <- Wick (high)
   ┌───┐
   │   │       <- Body (open to close)
   │   │       Green if close > open (price went up)
   │   │       Red if close < open (price went down)
   └───┘
     │         <- Wick (low)
```

### Bullish Candle (Price Increased)
```
     │  $50,100  <- High
   ┌───┐
   │   │  $50,050 <- Close (top of body)
   │ ■ │
   │   │  $50,000 <- Open (bottom of body)
   └───┘
     │  $49,999  <- Low
```
Green body shows price went up ($50,000 → $50,050)

### Bearish Candle (Price Decreased)
```
     │  $51,000  <- High
   ┌───┐
   │   │  $50,500 <- Open (top of body)
   │ ■ │
   │   │  $50,000 <- Close (bottom of body)
   └───┘
     │  $49,900  <- Low
```
Red body shows price went down ($50,500 → $50,000)

---

## Time Intervals

### 1-Second Candle
- Aggregates 1 second of data
- For ultra-high-frequency trading
- Our system: 1,000 candles/sec (one per token)

### 1-Minute Candle
- Aggregates 60 seconds (60 ticks)
- Standard for day trading
- Our system: ~17 candles/sec (1,000 tokens ÷ 60 sec)

### 5-Minute Candle
- Aggregates 5 minutes (300 ticks)
- For swing trading
- Our system: ~3 candles/sec (1,000 tokens ÷ 300 sec)

### 1-Hour Candle
- Aggregates 3,600 ticks
- For trend analysis
- Our system: ~0.3 candles/sec

---

## Calculating OHLC

### Manual Approach (Don't Do This)
```java
List<Double> prices = new ArrayList<>();

// Collect prices for 1 minute
for (int i = 0; i < 60; i++) {
    prices.add(getNextPrice());
}

// Calculate OHLC
double open = prices.get(0);
double close = prices.get(prices.size() - 1);
double high = Collections.max(prices);
double low = Collections.min(prices);
```

**Problems:**
- What if prices arrive out of order?
- What if service crashes mid-aggregation?
- What about multiple tokens? (need 1,000 separate lists)
- How to handle multiple instances? (distributed state)

### Kafka Streams Approach (What We'll Use)
```java
KStream<String, Tick> ticks = builder.stream("market-data");

KTable<Windowed<String>, OHLCCandle> candles = ticks
    .groupByKey()
    .windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
    .aggregate(
        () -> new OHLCCandle(),           // Initial empty candle
        (key, tick, candle) -> {
            if (candle.isEmpty()) {
                candle.setOpen(tick.getPrice());
            }
            candle.setHigh(Math.max(candle.getHigh(), tick.getPrice()));
            candle.setLow(Math.min(candle.getLow(), tick.getPrice()));
            candle.setClose(tick.getPrice());  // Always update to latest
            candle.addVolume(tick.getVolume());
            return candle;
        }
    );
```

**Benefits:**
- Handles out-of-order messages
- State persisted (survives crashes)
- Automatically handles 1,000 tokens in parallel
- Distributed across multiple instances

---

## Time Windows

### Tumbling Window (What We Use)
```
Time: ─────────────────────────────────────>

Window 1: [14:00:00 to 14:01:00)
Window 2: [14:01:00 to 14:02:00)
Window 3: [14:02:00 to 14:03:00)

No overlap, fixed size
```

Each tick belongs to **exactly one** window.

### Example: 1-Minute Tumbling Window
```
14:00:00 → Window 1
14:00:30 → Window 1
14:00:59 → Window 1
14:01:00 → Window 2  (new window starts)
14:01:30 → Window 2
```

### Why Tumbling?
- Simple to understand
- No duplicate data
- Standard in financial charts
- Efficient computation

---

## Data Flow in Our System

### 1. Raw Ticks (Kafka Topic: market-data)
```
{symbol: "BTC", price: 50000.00, volume: 100, timestamp: "14:00:00"}
{symbol: "BTC", price: 50001.50, volume: 120, timestamp: "14:00:01"}
{symbol: "BTC", price: 50000.50, volume: 110, timestamp: "14:00:02"}
...
```
**Rate:** 1,000 messages/sec (1 per token per second)

### 2. Kafka Streams Aggregator
- Reads from `market-data`
- Groups by symbol
- Aggregates into 1-minute windows
- Produces to `candles-1m`

### 3. 1-Minute Candles (Kafka Topic: candles-1m)
```
{symbol: "BTC", open: 50000.00, high: 50100.00, low: 49999.00, 
 close: 50050.00, volume: 15000, timestamp: "14:00:00"}
```
**Rate:** ~17 messages/sec (1,000 tokens × 1 candle/min ÷ 60 sec)

### 4. Stored in QuestDB
```sql
SELECT * FROM candles_1m WHERE symbol = 'BTC' ORDER BY timestamp DESC LIMIT 5;

symbol | open     | high     | low      | close    | volume | timestamp
-------|----------|----------|----------|----------|--------|-------------------
BTC    | 50000.00 | 50100.00 | 49999.00 | 50050.00 | 15000  | 2024-01-01 14:00
BTC    | 50050.00 | 50150.00 | 50030.00 | 50120.00 | 16000  | 2024-01-01 14:01
BTC    | 50120.00 | 50200.00 | 50100.00 | 50180.00 | 14500  | 2024-01-01 14:02
```

### 5. Displayed in Frontend
- Lightweight Charts library renders candlestick chart
- Updates in real-time via WebSocket

---

## Multiple Time Intervals

### Strategy: Pre-calculate All Intervals

We'll create separate Kafka Streams for each interval:

**Aggregator 1m:**
```java
.windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(1)))
→ produces to "candles-1m"
```

**Aggregator 5m:**
```java
.windowedBy(TimeWindows.ofSizeWithNoGrace(Duration.ofMinutes(5)))
→ produces to "candles-5m"
```

**Alternative: Calculate 5m from 1m Candles**
```java
// 5-minute candle = aggregate of five 1-minute candles
5m_candle.open  = 1m_candles[0].open
5m_candle.high  = max(1m_candles[0..4].high)
5m_candle.low   = min(1m_candles[0..4].low)
5m_candle.close = 1m_candles[4].close
5m_candle.volume = sum(1m_candles[0..4].volume)
```

We'll use the first approach (separate streams) for simplicity.

---

## Why This Matters

### For Users
- **Day traders** use 1m/5m candles
- **Swing traders** use 15m/1h candles
- **Long-term investors** use 1d candles

Different users need different time scales.

### For System Performance
- **Frontend can't handle 86,400 points per day**
- **1-minute candles** = 1,440 points per day (60x reduction)
- **5-minute candles** = 288 points per day (300x reduction)

Aggregation is essential for performance.

---

## Key Takeaways

1. **OHLC candles** compress time-series data into 4 values
2. **Tumbling windows** create fixed-size, non-overlapping intervals
3. **Kafka Streams** handles aggregation with fault tolerance
4. **Multiple intervals** serve different trading styles
5. **Pre-aggregation** reduces frontend load by 100x

---

## Next: Understanding Kafka Streams

See: `04-kafka-streams.md`
