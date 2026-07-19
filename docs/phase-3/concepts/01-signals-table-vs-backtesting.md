# Signals Table vs Backtesting: Understanding the Difference

## The Question

**"Why do we need to regenerate signals in the backtest engine when we already have a signals table in QuestDB?"**

This is a crucial architectural distinction that affects how we use data and what results we show on the frontend.

---

## TL;DR (Quick Answer)

**Signals Table** = What the system **IS DOING** right now (real-time production signals)

**Backtest Engine** = What the system **WOULD HAVE DONE** in the past (simulated historical performance)

---

## The Problem with Using Existing Signals Table for Backtesting

The **signals table** in QuestDB contains signals that were generated **in real-time** by the strategy-engine (Phase 2). But for backtesting, we have several issues:

### Issue 1: **Limited History**

```
Today's Date: July 19, 2026
Strategy Engine Started: July 18, 2026 (1 day ago)

Signals Table Has: 1 day of signals ❌
Backtest Needs: 30 days of signals ✅

We can't backtest on 30 days if we only have 1 day of signals!
```

**Example:**

You want to evaluate: "How did RSI strategy perform over the last 30 days?"

But the strategy-engine only started running yesterday! The signals table only has 1 day of data.

**Solution:** Fetch 30 days of historical **tick data** from QuestDB and **replay** the RSI strategy logic to generate what signals **would have been created** if the strategy had been running for 30 days.

---

### Issue 2: **Different Parameters**

```
Production Strategy: RSI(period=14, oversold=30, overbought=70)
Backtest Question: "What if I used RSI(period=10, oversold=25, overbought=75)?"

Signals table has wrong parameters! ❌
Need to regenerate with new parameters ✅
```

**Example:**

You want to test: "Would RSI strategy work better with period=10 instead of period=14?"

The signals table has signals generated with period=14 (production config). You can't test different parameters without regenerating signals.

**Solution:** Replay the strategy with **custom parameters** on historical data to see "what if" scenarios.

---

### Issue 3: **Data Availability**

```
Signals Table: Only has signals from when strategy-engine was running
Historical Data: QuestDB has ticks/candles from weeks/months ago

The signals table doesn't exist for historical periods!
```

**Example:**

QuestDB has tick data from:
- June 1, 2026 onwards (2 months of data)

But strategy-engine only started running:
- July 18, 2026 (2 days ago)

The signals table has NO data for June! But we have tick data for June, so we can **backtest** on June data.

**Solution:** Use historical tick data (which goes back further) to simulate what signals would have been generated.

---

## Concrete Example: Testing RSI Strategy Performance

### Scenario

**Question:** "How would RSI strategy have performed on AAPL from June 1 to June 30, 2026?"

**Problem:**
- Strategy-engine only started running on July 18, 2026
- Signals table only has signals from July 18 onwards
- We have **NO signals for June 1-30** in the database

**Solution:**
1. ✅ Fetch historical AAPL ticks from June 1-30 (this data **exists** in `ticks` table)
2. ✅ Replay RSI strategy logic on that historical data
3. ✅ Generate "what signals **would have been created**" for June
4. ✅ Simulate trades based on those signals
5. ✅ Calculate performance metrics

---

## Architecture Comparison

### Phase 2: Real-Time Signal Generation (Production)

```
Current Time: July 19, 2026, 10:30 AM

┌─────────────────┐
│ Live Tick Data  │
│ (happening now) │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Strategy Engine │ Calculates RSI on recent ticks
│ (running live)  │ Detects RSI < 30 → BUY signal
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Kafka Topic     │ Publishes signal
│ trading-signals │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Signals Table   │ Stores: {symbol: AAPL, action: BUY, 
│ (QuestDB)       │          timestamp: 2026-07-19 10:30}
└─────────────────┘

This signal is for "right now" - can't be used for backtesting past dates!
```

**Key Point:** Signals table contains **ACTUAL signals** that were generated in **real-time** by the production strategy engine.

---

### Phase 3: Backtesting (Historical Simulation)

```
Want to Test: June 1-30, 2026 (already happened in the past)

┌─────────────────┐
│ Historical Data │ Query: Get AAPL ticks from June 1-30
│ (ticks table)   │ Result: 2.6M ticks from that period
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Backtest Engine │ REPLAYS RSI strategy logic
│ (Python)        │ Row-by-row through June data
│                 │ "On June 5 at 2pm, RSI was 28 → BUY"
│                 │ "On June 10 at 4pm, RSI was 72 → SELL"
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Generated       │ NOT saved to database!
│ Signals         │ Just used for simulation
│ (in-memory)     │
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Portfolio       │ Simulate trades: 
│ Simulation      │ - June 5: Buy at $175
│                 │ - June 10: Sell at $185
│                 │ Profit: $10/share (+5.7%)
└────────┬────────┘
         │
         ▼
┌─────────────────┐
│ Performance     │ Total Return: +12.5%
│ Metrics         │ Sharpe Ratio: 1.45
└─────────────────┘

These signals are RETROACTIVE - generated from past data!
```

**Key Point:** Backtest engine generates **SIMULATED signals** by replaying strategy logic on **historical data** to evaluate **past performance**.

---

## Why Not Just Query Signals Table?

### ❌ WRONG APPROACH (using existing signals)

```python
def backtest_using_signals_table():
    # Get signals from June 1-30
    signals = db.query("""
        SELECT * FROM signals 
        WHERE timestamp BETWEEN '2026-06-01' AND '2026-06-30'
    """)
    
    # Problem 1: This might return ZERO rows if strategy wasn't running in June!
    # Problem 2: Even if signals exist, they used production parameters
    # Problem 3: Can't test "what if I used different parameters"
    
    return calculate_metrics(signals)
```

**Why this fails:**
1. **No data:** If strategy-engine wasn't running in June, signals table is empty for that period
2. **Fixed parameters:** Can't test alternative configurations
3. **No flexibility:** Can't answer "what if" questions

---

### ✅ CORRECT APPROACH (regenerate signals)

```python
def backtest_by_replaying_strategy():
    # Step 1: Get TICK DATA from June 1-30 (this always exists)
    ticks = db.query("""
        SELECT * FROM ticks 
        WHERE timestamp BETWEEN '2026-06-01' AND '2026-06-30'
        ORDER BY timestamp ASC
    """)
    
    # Step 2: Replay strategy logic with custom parameters
    rsi = calculate_rsi(ticks, period=14)  # Can change period!
    
    signals = []
    for row in rsi:
        if row['rsi'] < 30:
            signals.append({'action': 'BUY', 'price': row['close']})
        elif row['rsi'] > 70:
            signals.append({'action': 'SELL', 'price': row['close']})
    
    # Step 3: Simulate trades with generated signals
    portfolio = Portfolio(initial_capital=10000)
    for signal in signals:
        if signal['action'] == 'BUY':
            portfolio.buy(signal['price'])
        elif signal['action'] == 'SELL':
            portfolio.sell(signal['price'])
    
    # Step 4: Calculate performance metrics
    return calculate_metrics(portfolio)
```

**Why this works:**
1. **Always has data:** Tick data goes back further than signals
2. **Flexible parameters:** Can test any configuration
3. **Answers "what if":** Simulates alternative scenarios

---

## QuestDB Tables: Two Different Purposes

```
┌─────────────────────────────────────────────────────────────────┐
│                    QuestDB TABLES                                │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 1. TICKS TABLE                                            │  │
│  │    Purpose: Raw price data                                │  │
│  │    Used For:                                              │  │
│  │    ✅ Backtesting (replay historical data)               │  │
│  │    ✅ Chart generation                                    │  │
│  │    ✅ Strategy calculations                               │  │
│  │    Retention: 30-90 days                                  │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 2. CANDLES_1M TABLE                                       │  │
│  │    Purpose: OHLC candlesticks                             │  │
│  │    Used For:                                              │  │
│  │    ✅ Frontend charts (TradingView)                       │  │
│  │    ✅ Technical analysis                                  │  │
│  │    Retention: 90+ days                                    │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐  │
│  │ 3. SIGNALS TABLE                                          │  │
│  │    Purpose: REAL-TIME production signals                  │  │
│  │    Used For:                                              │  │
│  │    ✅ Live signal feed on frontend (Page 1)              │  │
│  │    ✅ Real-time notifications                             │  │
│  │    ✅ Audit trail (what system ACTUALLY recommended)      │  │
│  │    ✅ Recent signals on Strategy Deep Dive (Page 3)       │  │
│  │    ❌ NOT used for backtesting performance metrics        │  │
│  │    Retention: 7-30 days                                   │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Frontend Pages: Where Each Table is Used

### **Page 1: Live Market Dashboard**

Uses: ticks, candles, **signals table** ✅

```
╔════════════════════════════════════════════════════════════════╗
║  📊 Market Overview                                             ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │ Symbol │ Price    │ Change  │ Active Signals │          │  ║
║  ├────────┼──────────┼─────────┼────────────────┤          │  ║
║  │ AAPL   │ $180.50  │ +2.3%   │ 🟢 BUY (3)     │ ← FROM   │  ║
║  │ BTC    │ $50,123  │ -2.4%   │ 🔴 SELL (2)    │   SIGNALS│  ║
║  └─────────────────────────────────────────────────────────┘   TABLE ✅
║                                                                 ║
║  📈 Price Chart                                                 ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │           AAPL - 1 Minute Candlestick Chart              │  ║
║  │  180 ┤  📊 📊 📊 📊 📊 📊 📊                             │  ║
║  │      │   ║  ║  ║  ║  ║  ║  ║                             │  ║
║  │  175 ┤   ║  ║  ║  ║  ║  ║  ║   ↑BUY                     │ ← FROM
║  └─────────────────────────────────────────────────────────┘   CANDLES_1M ✅
║                                                                 ║
║  🎯 Recent Signals (Last 10 - REAL-TIME)                       ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │ Time  │ Symbol│Strategy      │Action│Price   │Conf.    │  ║
║  ├───────┼───────┼──────────────┼──────┼────────┼─────────┤  ║
║  │ 11:15 │ AAPL  │ MA Crossover │ BUY  │ $180.5 │ 85%     │ ← FROM
║  │ 11:14 │ ETH   │ RSI Mean Rev │ BUY  │ $2,845 │ 92%     │   SIGNALS
║  │ 11:13 │ BTC   │ MACD         │ SELL │ $50.1K │ 78%     │   TABLE ✅
║  └─────────────────────────────────────────────────────────┘  ║
╚════════════════════════════════════════════════════════════════╝

Data Sources:
- Prices: SELECT latest price FROM ticks
- Chart: SELECT * FROM candles_1m WHERE symbol='AAPL' LIMIT 100
- Recent Signals: SELECT * FROM signals ORDER BY timestamp DESC LIMIT 10
```

**Query Example:**
```sql
-- Get recent signals for live display
SELECT 
    timestamp,
    symbol,
    strategy_name,
    action,
    confidence
FROM signals
WHERE timestamp > now() - INTERVAL '1 hour'
ORDER BY timestamp DESC
LIMIT 10;
```

---

### **Page 2: Strategy Leaderboard**

Uses: **BACKTEST RESULTS** (NOT signals table!) ✅

```
╔════════════════════════════════════════════════════════════════╗
║  🏆 Strategy Performance Rankings (Last 30 Days)               ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │Rank│ Strategy          │ Return │Sharpe│Win Rate│Signals│  ║
║  ├────┼───────────────────┼────────┼──────┼────────┼───────┤  ║
║  │ 1🥇│ RSI Mean Rev      │ +32.5% │ 1.85 │  68%   │  487  │ ← FROM
║  │ 2🥈│ MA Crossover      │ +28.3% │ 1.45 │  62%   │  342  │   BACKTEST
║  │ 3🥉│ Bollinger Bands   │ +18.1% │ 1.12 │  58%   │  521  │   ENGINE ✅
║  └─────────────────────────────────────────────────────────┘  ║
║                                                                 ║
║  📊 Cumulative Returns (Top 3 Strategies)                      ║
║  ┌─────────────────────────────────────────────────────────┐  ║
║  │ $13K ┤         ━━━ RSI (simulated)                      │  ║
║  │      │        ╱                                          │ ← FROM
║  │ $12K ┤       ╱    ━━━ MA (simulated)                    │   BACKTEST
║  │ $10K ┼─────────  (Starting Capital)                     │   RESULTS ✅
║  └─────────────────────────────────────────────────────────┘  ║
╚════════════════════════════════════════════════════════════════╝

Data Source:
- NOT from signals table! ❌
- FROM backtest engine simulations ✅
- API Call: GET /api/backtest/leaderboard
- Backtest engine queries ticks, replays strategies, calculates performance
```

**API Flow:**
```
Frontend → GET /api/backtest/leaderboard?period=30d
           ↓
Backtest Engine:
  1. For each strategy (10 total):
     a. Fetch ticks from last 30 days
     b. Replay strategy logic
     c. Simulate portfolio
     d. Calculate metrics
  2. Rank strategies by Sharpe ratio
  3. Return rankings
           ↓
Frontend displays leaderboard
```

---

### **Page 3: Strategy Deep Dive**

Uses: **BOTH** backtest results AND signals table ✅

```
╔════════════════════════════════════════════════════════════════╗
║  📊 RSI Mean Reversion Strategy                                ║
║                                                                 ║
║  🎯 Performance Metrics (30-Day BACKTEST)                      ║
║  ┌──────────────────────┬──────────────────────┐              ║
║  │ Total Return         │ +32.5%               │ ← FROM       ║
║  │ Sharpe Ratio         │ 1.85                 │   BACKTEST   ║
║  │ Win Rate             │ 68%                  │   ENGINE ✅  ║
║  └──────────────────────┴──────────────────────┘              ║
║                                                                 ║
║  📈 Equity Curve (SIMULATED)                                   ║
║  ┌────────────────────────────────────────────────────────┐   ║
║  │ $13.2K ┤                          ╱──────               │   ║
║  │ $10.0K ┼─────────╯  (Starting Capital - BACKTEST)      │ ← FROM
║  └────────────────────────────────────────────────────────┘   BACKTEST ✅
║                                                                 ║
║  🔍 Recent Signals (Last 10 - ACTUAL PRODUCTION)               ║
║  ┌────────────────────────────────────────────────────────┐   ║
║  │ Time  │Symbol│Action│Price   │RSI │Outcome│P/L      │   │   ║
║  ├───────┼──────┼──────┼────────┼────┼───────┼─────────┤   │   ║
║  │ 11:15 │ ETH  │ BUY  │ $2,845 │ 28 │ ⏳ Open│ -       │ ← FROM
║  │ 10:45 │ AAPL │ SELL │ $180.5 │ 72 │ ✅ Win │ +2.1%   │   SIGNALS
║  │ 10:30 │ BTC  │ BUY  │ $50.1K │ 27 │ ✅ Win │ +1.8%   │   TABLE ✅
║  └────────────────────────────────────────────────────────┘   ║
╚════════════════════════════════════════════════════════════════╝

Two Data Sources:
1. Performance Metrics: FROM backtest engine (simulated 30-day performance)
2. Recent Signals: FROM signals table (actual real-time signals generated today)
```

**Two Different Queries:**
```sql
-- 1. Backtest metrics (simulated)
API: GET /api/backtest/results/RSI_MEAN_REVERSION?period=30d
→ Backtest engine simulates 30 days, returns metrics

-- 2. Recent actual signals (real production)
SELECT * FROM signals
WHERE strategy_name = 'RSI'
ORDER BY timestamp DESC
LIMIT 10;
→ Shows what the strategy ACTUALLY generated today
```

---

## The Key Distinction

### **Signals Table = What the System IS DOING** (Real-Time)

```sql
-- Show me signals from the last hour (REAL production signals)
SELECT * FROM signals 
WHERE timestamp > now() - INTERVAL '1 hour'
ORDER BY timestamp DESC;

Result:
┌──────────┬────────┬──────────────┬────────┬───────────────────────┐
│ symbol   │ action │ strategy     │ conf   │ timestamp             │
├──────────┼────────┼──────────────┼────────┼───────────────────────┤
│ AAPL     │ BUY    │ RSI          │ 0.85   │ 2026-07-19 11:15:00   │
│ BTC      │ SELL   │ MACD         │ 0.78   │ 2026-07-19 11:13:00   │
└──────────┴────────┴──────────────┴────────┴───────────────────────┘

Use Case: Show this on frontend as "LIVE SIGNALS" ✅
```

### **Backtest Results = What the System WOULD HAVE DONE** (Historical Simulation)

```python
# Run backtest for last 30 days (SIMULATED performance)
backtest_result = backtester.run_backtest(
    strategy="RSI_MEAN_REVERSION",
    symbol="AAPL",
    start_date="2026-06-19",
    end_date="2026-07-19"
)

Result:
{
  "total_return": 32.5,
  "sharpe_ratio": 1.85,
  "win_rate": 68.0,
  "num_trades": 487,
  "equity_curve": [10000, 10050, 10125, ...],
  "simulated_signals": [
    {"timestamp": "2026-06-20 10:30", "action": "BUY", "price": 175.50},
    {"timestamp": "2026-06-22 14:15", "action": "SELL", "price": 179.80}
  ]
}

Use Case: Show this on frontend as "STRATEGY PERFORMANCE" ✅
```

---

## Why We Need BOTH

| Data | Purpose | Frontend Usage | Source |
|------|---------|----------------|--------|
| **Signals Table** | What system recommends RIGHT NOW | Live signal feed, notifications | strategy-engine → Kafka → QuestDB |
| **Ticks/Candles** | Raw market data | Charts, backtesting | data-generator → Kafka → QuestDB |
| **Backtest Results** | How strategies PERFORMED historically | Leaderboard, performance metrics | Backtest engine (computed on-demand) |

---

## Real-World Analogy

### **Signals Table = Your Actual Driving Record**
- Shows where you **DID** go last week
- Fixed, historical, cannot be changed
- Limited to when you had the car
- Example: "I drove to work on Monday at 8 AM"

### **Backtesting = GPS Route Simulation**
- Shows where you **COULD HAVE** gone if you took a different route
- Flexible, can test alternative scenarios
- Can simulate routes from before you had the car (using historical map data)
- Example: "If I had taken Highway 101 instead of I-280, I would have saved 10 minutes"

**Both are valuable:**
- Driving record: Shows actual behavior (audit trail)
- GPS simulation: Evaluates alternative strategies (optimization)

---

## Summary Table

| Aspect | Signals Table (Phase 2) | Backtest Signal Generator (Phase 3) |
|--------|------------------------|-------------------------------------|
| **Purpose** | Store real-time production signals | Simulate historical "what-if" scenarios |
| **Time** | Present/recent past | Any historical period |
| **Data Source** | Live incoming ticks | Historical ticks from database |
| **Parameters** | Fixed (production config) | Flexible (test different configs) |
| **Coverage** | Only since strategy started running | Any period with tick data |
| **Saved?** | Yes (to signals table) | No (in-memory only) |
| **Frontend Use** | Live signal feed (Page 1, Page 3) | Performance metrics (Page 2, Page 3) |
| **Example Query** | `SELECT * FROM signals WHERE timestamp > now() - INTERVAL '1h'` | `backtester.run_backtest(strategy, symbol, start, end)` |

---

## Why QuestDB for Signals Then?

**Three Important Reasons:**

### 1. **Real-Time Signal Feed** (Primary Use)

```javascript
// Frontend wants: "Show me signals happening RIGHT NOW"
fetch('/api/signals/recent?hours=1')
  .then(signals => displayLiveSignals(signals));

// Query behind the scenes:
SELECT * FROM signals 
WHERE timestamp > now() - INTERVAL '1 hour'
ORDER BY timestamp DESC;

// Result: Display live signal feed on Page 1 ✅
```

### 2. **Audit Trail**

```sql
-- Question: "What did the system recommend on June 15 at 2 PM?"
SELECT * FROM signals 
WHERE timestamp = '2026-06-15 14:00:00';

-- Result: ACTUAL signal that was generated (not simulated) ✅

-- This is different from backtest!
-- Backtest shows: "what would have happened"
-- Signals table shows: "what DID happen"
```

### 3. **Compare Backtest vs Reality** (Advanced Use)

```python
# Question: "Did my backtest predictions match reality?"

# Backtest prediction:
backtest_result = run_backtest("RSI", "AAPL", "2026-06-01", "2026-06-30")
print(f"Backtest: RSI would generate {len(backtest_result.signals)} signals")

# Actual reality:
actual_signals = db.query("""
    SELECT COUNT(*) FROM signals 
    WHERE strategy_name = 'RSI' 
      AND symbol = 'AAPL'
      AND timestamp BETWEEN '2026-06-01' AND '2026-06-30'
""")
print(f"Reality: RSI generated {actual_signals} signals")

# Compare:
# Backtest Says: 487 signals in 30 days
# Reality: 520 signals in 30 days
# Difference: +33 signals (backtest was slightly conservative)

# This helps validate if backtest is realistic!
```

---

## Common Misconceptions

### ❌ Misconception 1: "Signals table = Strategy performance"

**Wrong:** Signals table shows what signals were generated, NOT how they performed.

**Correct:** Performance metrics come from **backtesting** (simulating trades on historical data).

---

### ❌ Misconception 2: "We can use signals table for backtesting"

**Wrong:** Signals table only has data from when strategy started running (limited history).

**Correct:** Backtest engine replays strategy on **any historical period** with tick data.

---

### ❌ Misconception 3: "Backtest results should be saved to QuestDB"

**Wrong:** Backtest results are computed on-demand and can be cached in memory or Redis.

**Correct:** Storing backtest results in QuestDB is optional (for caching), but ticks/candles are the source of truth.

---

## Final Summary

**Bottom Line:**
- ✅ Signals table **IS** shown on frontend (Page 1: recent signals, Page 3: recent trades)
- ✅ Signals table **IS** useful (live feed, audit trail, compare vs backtest)
- ✅ Backtest engine generates **DIFFERENT** signals (simulated, not real)
- ✅ Performance metrics come from **backtest simulations** (not signals table)

**The Two Serve Different Purposes:**

1. **Signals Table** → "What is the system doing RIGHT NOW?"
   - Use: Live signal feed, notifications, audit trail

2. **Backtest Engine** → "How WOULD the system have performed HISTORICALLY?"
   - Use: Strategy evaluation, performance ranking, optimization

Both are essential for a complete trading platform!
