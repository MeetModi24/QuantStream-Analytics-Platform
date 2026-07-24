# Historical Data Requirements, Windows, and Retention

## Purpose

This document answers three connected questions:

1. **At each point in the system, how much historical data is actually needed?**
2. **Where does that history live — in memory, in Kafka, or in QuestDB?**
3. **Given (1) and (2), how do we clean up / retain data without breaking anything?**

The central insight: **the live trading path needs almost no stored history, while the dashboard and backtesting path needs a lot.** These are two separate data lifetimes, and they drive two separate retention policies.

---

## 1. The Core Distinction: Backfill vs Lookback vs Retention

Three terms that get confused. They are not the same thing.

| Term | Definition | Where it lives | Example |
|------|-----------|----------------|---------|
| **Backfill** | Loading historical data from a database *at startup* before a component can produce output | QuestDB → read into service | ❌ We do NOT do this for live trading |
| **Lookback** | A window of *recent* observations needed to compute the current signal | In-memory rolling buffer, built forward from the live stream | ✅ Pairs trading needs last 300 spreads |
| **Retention** | How long we *keep* data stored for humans (dashboard, backtest, audit) | QuestDB partitions on disk | ✅ Keep order books 7 days, signals 90 days |

**Key rule for intraday HFT:**
> Strategies never read from the database to start trading. They build their lookback window live, forward in time, from the Kafka stream. The database exists for the dashboard, backtesting, and audit — NOT for the live strategy path.

This is the exact bug that broke the old swing-trading design: it tried to `SELECT last 50 days` from the DB on every cycle (backfill-driven lookback). Intraday systems move that window into RAM and fill it forward.

---

## 2. Historical Data Required at Each Component

Walking through the pipeline, component by component, stating exactly what history each one holds.

### **Component 1: Order Book Generator**

- **History needed:** 1 previous value per token (the last price, to do the random walk `price[t] = price[t-1] + ...`).
- **Where:** In memory, a single `double` per token (100 doubles total).
- **Backfill:** None. Starts from a configured seed price.
- **Cleanup impact:** Negligible. Nothing persisted by this component itself.

```
State per token: { last_price, last_obi }   ← 2 numbers, that's it
```

---

### **Component 2: Feature Calculator**

This is where it gets interesting — it depends on *which* feature.

#### **Stateless features (need only the current snapshot):**
| Feature | Lookback | Reason |
|---------|----------|--------|
| OBI (imbalance) | **0** | Pure function of current bid/ask volumes |
| Microprice | **0** | Pure function of current top-of-book |
| Spread | **0** | best_ask − best_bid, right now |
| Mid-price | **0** | (best_bid + best_ask) / 2, right now |
| Depth (L5) | **0** | Sum of current 5 levels |

#### **Stateful features (need a rolling window):**
| Feature | Lookback window | Why |
|---------|----------------|-----|
| Rolling VWAP (1 min) | **60 snapshots** | Volume-weighted avg price over last 60 sec |
| Realized volatility (5 min) | **300 snapshots** | Std of returns over last 300 sec |
| VPIN (toxicity) | **50 volume buckets** | Accumulate buy/sell imbalance across buckets |
| OBI moving average | **N snapshots** | Smooth the raw OBI signal |

- **Where:** In-memory rolling buffers, one per (token, feature).
- **Backfill:** None. Stateful features are `null`/`NaN` during warmup until the buffer fills.

```
State per token: ring buffer of last 300 snapshots  ← ~300 × 40 bytes = 12 KB/token
For 100 tokens: ~1.2 MB total RAM   ← trivial
```

---

### **Component 3: Database Writer**

- **History needed:** **0** in memory. It's write-only; each message is inserted and forgotten.
- **Backfill:** None.
- **Cleanup impact:** This component *creates* the data whose retention we manage (Section 5). It writes; it never reads.

---

### **Component 4: Strategy Engine**

The lookback requirement is entirely strategy-dependent. This is the most important table in the document.

| Strategy | Type | Lookback window | State held in memory |
|----------|------|-----------------|----------------------|
| **OBI Market Making** | Stateless | **0** | None — one snapshot in, one decision out |
| **Microprice momentum** | Stateless | **0** | None |
| **Pairs Trading** | Stateful | **~300 obs** (5 min) | Rolling spread history per pair, to compute mean/std → Z-score |
| **Ornstein-Uhlenbeck** | Stateful | **~600 obs** (10 min) | Recent price window to estimate θ (reversion speed), μ (mean), σ |
| **Kalman Filter Trend** | Stateful (recursive) | **1 prior estimate** | Carries `(state, covariance)` forward — NOT a window, just last estimate |
| **VPIN / Toxicity** | Stateful | **50 buckets** | Rolling buckets of signed volume |
| **Realized-vol breakout** | Stateful | **300 obs** (5 min) | Return window to compute volatility |

**Two flavors of "state" to note:**

1. **Windowed** (pairs, O-U, vol) — keep the last N raw observations, recompute statistics each tick. Memory = N × size.
2. **Recursive** (Kalman) — keep only the *last* estimate and update it. Memory = constant, tiny. This is why Kalman is popular in HFT: no window at all, just `estimate[t] = f(estimate[t-1], observation[t])`.

- **Backfill:** None for any of them. Windowed strategies warm up; recursive strategies converge from an initial guess.

```
Worst-case memory: 600 obs × 40 bytes × 100 tokens × (few windowed strategies)
≈ a few MB. Completely fine on a laptop.
```

---

### **Component 5: Signal Aggregator**

- **History needed:** **Open positions only** (not a time window). It tracks current state: what each strategy holds right now, at what average price.
- **Where:** In-memory position map + periodic DB snapshot for durability.
- **Backfill on restart:** ⚠️ This is the ONE place backfill *does* matter. If the aggregator crashes and restarts, it must reload open positions from the DB, or it forgets what it's holding. This is state recovery, not lookback.

```
State: { (strategy, token) → (shares, avg_entry_price, unrealized_pnl) }
Only for OPEN positions. Closed positions flushed to DB and dropped from memory.
```

---

### **Component 6 & 7: API Gateway + Dashboard**

- **History needed:** Whatever the *user* asks to see. This is the ONLY consumer of long-lived stored history.
- **Where:** Reads from QuestDB on demand (REST) + live tail (WebSocket).
- **Typical queries:**
  - "Show me AAPL's last hour" → 3,600 rows from `features`
  - "Show strategy PnL today" → aggregate from `strategy_pnl`
  - "Replay this morning's signals" → query `signals` by time range

**This component is why we retain data at all.** Nothing else needs disk history.

---

## 3. Summary: Memory Windows at a Glance

```
┌────────────────────┬──────────────┬───────────────────────────┐
│ Component           │ Lookback     │ Storage location          │
├────────────────────┼──────────────┼───────────────────────────┤
│ Order Book Gen      │ 1 value      │ RAM (per token)           │
│ Feature Calc        │ 0–300 obs    │ RAM ring buffer           │
│ Database Writer     │ 0            │ (write-only)              │
│ Strategy: stateless │ 0            │ none                      │
│ Strategy: pairs     │ ~300 obs     │ RAM ring buffer per pair  │
│ Strategy: O-U       │ ~600 obs     │ RAM ring buffer           │
│ Strategy: Kalman    │ 1 estimate   │ RAM (recursive state)     │
│ Strategy: VPIN      │ 50 buckets   │ RAM buckets               │
│ Signal Aggregator   │ open pos.    │ RAM + DB snapshot         │
│ API / Dashboard     │ user-defined │ QuestDB (on demand)       │
└────────────────────┴──────────────┴───────────────────────────┘

TOTAL live-path RAM for history: ~5–10 MB for 100 tokens.
The live trading path barely touches disk.
```

---

## 4. The Warmup Period (Intraday's Answer to Backfill)

Since stateful strategies build their window forward from the stream, they produce **no signals** until the window is full.

```
Strategy startup timeline (Pairs Trading, 300-obs window, 1 obs/sec):

t=0s    Service starts. window = []
t=1s    window = [obs1]                    → no signal (warming up)
t=2s    window = [obs1, obs2]              → no signal
...
t=299s  window = [obs1..obs299]            → no signal
t=300s  window full (300 obs)              → FIRST signal possible ✅
t=301s+ rolling: drop oldest, add newest   → signals flow normally
```

**Design implications:**

1. **Each strategy must expose a `warmupPeriod` (in seconds/observations).** The dashboard should show a "warming up" badge, not a silent gap that looks like a bug.
2. **Longest warmup = O-U at ~600s = 10 minutes.** After 10 minutes of running, all strategies are live.
3. **Optional accelerator (production):** On startup, *seed* the in-memory window with a single historical query from QuestDB (e.g., `SELECT last 300 FROM features WHERE token='AAPL'`). This is backfill-as-optimization — it skips the warmup wait. It is a convenience, not a requirement. For development, plain forward warmup is simpler and fine.

---

## 5. Retention & Cleanup Policy

Now the payoff: because the live path needs almost no history, **retention is driven entirely by dashboard/backtest/audit needs**, and we can be aggressive about deleting raw data.

QuestDB partitions by time, so cleanup = dropping old partitions (cheap, no row-by-row delete).

### **Retention tiers**

| Table | Granularity | Retention | Why | Daily volume |
|-------|-------------|-----------|-----|--------------|
| `order_book_snapshots` | 1/sec/token (raw) | **3–7 days** | Bulky; only needed for recent deep-dive & short backtests | ~4.3 GB/day |
| `features` | 1/sec/token | **30 days** | Smaller; drives most dashboard charts | ~1.7 GB/day |
| `signals` | event-driven | **90 days** | Audit trail — "why did we trade?" | ~260 MB/day |
| `positions` | on change | **90 days** | Reconstruct exposure history | small |
| `strategy_pnl` | 1/sec or 1/min | **1 year+** | Performance history is the product's value | small |

### **Downsampling (rollups) instead of raw deletion**

Rather than losing history entirely, roll raw data up before deleting it:

```
Raw order_book_snapshots (1/sec)   ──[after 7 days]──►  drop raw partition
                                    └─[before drop]───►  aggregate into
                                                          features_1m (1/min OHLC + avg OBI)

features_1m kept 1 year (tiny), raw features kept 30 days.
```

This gives you: recent data at full resolution, older data at coarse resolution, cheaply.

### **Cleanup mechanism (QuestDB)**

```sql
-- Time-partitioned tables make cleanup a partition drop, not a DELETE scan
CREATE TABLE order_book_snapshots (...) TIMESTAMP(timestamp) PARTITION BY DAY;

-- Daily cron job drops partitions older than retention window
ALTER TABLE order_book_snapshots DROP PARTITION
  WHERE timestamp < dateadd('d', -7, now());
```

Run via a scheduled job (cron / Spring `@Scheduled`) once per day, off-peak.

### **What we can safely delete immediately**

| Data | Safe to drop? | Reason |
|------|---------------|--------|
| Raw order books > 7 days | ✅ Yes | No strategy reads them; dashboard rarely goes back that far raw |
| Kafka messages after consumption | ✅ Yes (retention.ms) | Kafka is transport, not storage — set topic retention to hours, not days |
| In-memory windows on restart | ✅ Yes (except aggregator positions) | Rebuilt via warmup |
| Closed positions from RAM | ✅ Yes | Flushed to DB first |
| `strategy_pnl` | ❌ No (keep long) | This is the historical performance record — the whole point of the dashboard |

### **Kafka retention (separate from QuestDB)**

Kafka is a transport buffer, not a datastore. Set short retention:

```properties
# Topic-level: keep only enough to survive a brief consumer outage
retention.ms=3600000        # 1 hour
retention.bytes=1073741824  # or cap at 1 GB per partition
```

Rationale: once the Feature Calculator and Database Writer have consumed a message, Kafka doesn't need it. The durable copy lives in QuestDB.

---

## 6. Putting It Together: Two Data Lifetimes

```
                 LIVE TRADING PATH                    HUMAN / ANALYSIS PATH
                 (needs ~0 stored history)            (needs long history)

  Generator ─► Kafka ─► Feature Calc ─► Strategy ─► Aggregator
                 │  (1hr retention)   │ (RAM window)          │
                 │                    │                       │
                 └────────► Database Writer ──► QuestDB ◄──────┘ (positions snapshot)
                                                  │
                                                  ▼
                                            API / Dashboard
                                          (queries days–months)

  Retention driven by ───────────────────────────► THIS SIDE, not the live path.
```

**Bottom line:**
- Live path: history lives in RAM, measured in minutes, rebuilt on restart via warmup. Retention here ≈ zero.
- Analysis path: history lives in QuestDB, measured in days-to-years, tiered by table. This is where all cleanup policy applies.
- Kafka in the middle: buffer only, hours of retention max.

---

## 7. Configuration Sketch

```yaml
# config/retention.yml
kafka:
  order-book-data:   { retention_ms: 3600000 }    # 1 hour
  features:          { retention_ms: 3600000 }
  signals:           { retention_ms: 86400000 }    # 1 day (safety)

questdb:
  order_book_snapshots: { partition_by: DAY,  retain_days: 7 }
  features:             { partition_by: DAY,  retain_days: 30 }
  features_1m:          { partition_by: DAY,  retain_days: 365 }  # rollup
  signals:              { partition_by: DAY,  retain_days: 90 }
  positions:            { partition_by: DAY,  retain_days: 90 }
  strategy_pnl:         { partition_by: HOUR, retain_days: 730 }  # 2 years

strategy_windows:
  obi_market_making:    { warmup_obs: 0 }
  microprice_momentum:  { warmup_obs: 0 }
  pairs_trading:        { warmup_obs: 300 }   # 5 min
  ornstein_uhlenbeck:   { warmup_obs: 600 }   # 10 min
  kalman_trend:         { warmup_obs: 1 }      # recursive, 1 estimate
  vpin_toxicity:        { warmup_buckets: 50 }
```

---

## 8. Key Takeaways

1. **Live trading needs no backfill.** Strategies build lookback windows in RAM, forward from the stream.
2. **Lookback ≠ backfill ≠ retention.** Three distinct concepts with three distinct storage locations.
3. **Stateless strategies (OBI, microprice) need zero history.** Stateful ones (pairs, O-U, VPIN) need in-memory windows of 5–10 minutes. Kalman needs only its last estimate.
4. **Warmup period replaces backfill.** Strategies emit nothing until their window fills (max ~10 min). Show a "warming up" state, don't treat it as a bug.
5. **The database exists for humans, not strategies.** Retention policy is driven by dashboard/backtest/audit needs.
6. **Retention is tiered:** raw order books days, features weeks, signals/positions months, PnL years. Use partition drops + rollups, not row deletes.
7. **Kafka retains hours, not days** — it's transport, not storage.
8. **Only the Signal Aggregator needs state recovery on restart** (open positions), and that's recovery, not lookback.

---

## 9. Next Steps

1. ⏭️ Define exact warmup periods per strategy as they're implemented
2. ⏭️ Implement the daily partition-drop cron job
3. ⏭️ Implement `features_1m` rollup aggregation
4. ⏭️ Add `warmupPeriod` + "warming up" status to the strategy interface and dashboard

---

## References

- **QuestDB partitioning & TTL:** https://questdb.io/docs/concept/partitions/
- **Kafka retention config:** https://kafka.apache.org/documentation/#topicconfigs
- **Related:** `02-system-components-and-data-flow.md` (component overview)
- **Related:** `../concepts/01-order-book-fundamentals.md` (order book basics)
