# Signal Aggregator (Component 5)

## Purpose

The Signal Aggregator turns the raw, per-strategy **signal stream** into **observable
portfolio state and performance metrics**. Signals on their own answer "what would this
strategy do right now?" — the aggregator answers the question the dashboard actually
exists to answer: **"is this strategy any good, and what is it holding?"**

QuantStream is a **monitoring / visualization** system, not a trading system. So the
aggregator does **paper trading**: it simulates fills at the signal's reference price to
*measure* strategy quality. Nothing here places a real order. "Position" and "PnL" mean
*simulated* position and *simulated* profit-and-loss, used purely to score strategies on
the dashboard.

---

## 1. Where it sits in the pipeline

```
strategy-engine ──signals──┐
                           ↓
feature-calculator ─features─→  ┌────────────────────────┐
   (latest microprice          │  5. SIGNAL AGGREGATOR   │
    per token, for             │  - paper-trade fills    │
    mark-to-market)            │  - position tracking    │
                               │  - realized/unrealized  │
                               │    PnL                  │
                               │  - consensus/conflict   │
                               └───────┬────────────────┬┘
                                       │                │
                        positions topic│                │strategy-pnl topic
                                       ↓                ↓
                               ┌────────────────────────────┐
                               │  database-writer → QuestDB  │
                               │  ├─ positions               │
                               │  └─ strategy_pnl            │
                               └────────────┬────────────────┘
                                            ↓
                                     dashboard-api (REST + live)
```

**Key point:** the aggregator consumes **two** topics, not one:

- `signals` — the events that open/close/adjust paper positions.
- `features` — used only to keep a **latest-microprice-per-token** map, so open
  positions can be **marked to market** continuously (unrealized PnL updates every
  second even when no new signal fires).

---

## 2. What it computes

### 2.1 Paper-trade portfolio (per `strategy` × `token`)

Each strategy is scored independently — that is the whole point of a strategy monitor.

| Field | Meaning |
|-------|---------|
| `net_position` | signed quantity held (+long / −short), in units (notional ÷ fill price) |
| `avg_entry_price` | average price of the currently-open position |
| `realized_pnl` | locked-in PnL from trades that reduced/closed a position |
| `unrealized_pnl` | mark-to-market on the open position vs. latest microprice |

**Fill model.** Each `BUY`/`SELL` signal simulates a fill at the signal's `price`, sized
by a **fixed notional** (`notional-per-fill`, e.g. $10k) rather than a fixed unit count:
`units = notional / price`. This is deliberate — a flat 100-unit lot would put $5.8M of
risk on a $58k BTC but only $20k on a $196 JPM, so a strategy's PnL would be dominated by
*which* high-priced tokens it happened to touch rather than by whether its signals were
good. Notional sizing gives every token comparable dollar risk, making per-strategy PnL
comparable across tokens. A `CLOSE` flattens the position. The accounting handles the
three cases every position keeper must:

- **Opening / increasing** a position → update `avg_entry_price` (volume-weighted).
- **Reducing / closing** → realize PnL on the closed quantity at the fill price; keep
  `avg_entry_price` for any remainder.
- **Flipping** (e.g. long → short in one larger opposite fill) → realize on the full old
  position, then open the remainder on the other side at the fill price.

**Mark-to-market.** `unrealized_pnl = net_position × (latest_microprice −
avg_entry_price)`. Recomputed on a scheduler so the dashboard shows live PnL, not just
PnL-at-last-signal.

### 2.2 Per-strategy performance rollup

Emitted on a fixed interval (not only on signals), so performance is live:

| Field | Meaning |
|-------|---------|
| `realized_pnl` | sum of realized PnL across the strategy's tokens |
| `unrealized_pnl` | sum of mark-to-market across open positions |
| `total_pnl` | realized + unrealized |
| `num_trades` | count of simulated fills |
| `win_rate` | fraction of *closed* trades with positive realized PnL |

### 2.3 Consensus & conflict detection (per token, across strategies)

- **Consensus** — how many strategies currently agree on a direction for a token
  (e.g. 3 strategies all long AAPL). With one strategy today this is trivially 1; it
  becomes meaningful as the strategy count grows toward 30.
- **Conflict** — flag when strategies disagree on the same token (one long, one short).
  In a real system this gates execution; here it's a **dashboard alert** — a visible,
  interesting event that says "the strategies don't agree on this name."

---

## 3. Design decisions (and why)

### D1: Paper-trade at signal price — *not* signal-stats-only

The alternative (dedup + conflict only, no positions/PnL) was rejected because it would
gut the strategy-performance view the dashboard is built around. Measuring quality
requires a PnL number, and a PnL number requires a position model. See
`docs/planning/04-design-decisions.md` (this will be recorded as an ADR).

### D2: Consume `features` too, for mark-to-market

Unrealized PnL is only meaningful against a *current* price. Rather than re-query the
database, the aggregator keeps the latest microprice per token in memory from the
`features` stream it is already positioned to consume. This keeps PnL live at ~1s
granularity with zero database load.

### D3: The aggregator does **not** write QuestDB directly

Persistence is owned by the **database-writer** (the single-writer pattern, ADR-8). The
aggregator **emits** `positions` and `strategy-pnl` to Kafka; the database-writer
persists them (with `DEDUP UPSERT KEYS` for idempotency) and the dashboard tails them
live. The architecture diagram in doc 02 showing *aggregator → QuestDB* is a
simplification; the real path routes through the dedicated writer so there is exactly
one component that talks to the database, one place that owns schema and dedup, and no
duplicated PG-wire ingestion code.

### D4: Config-driven, per the project mandate

Lot size, rollup interval, and topic names are configuration. The portfolio is keyed by
`(strategy, token)` and built from whatever signals arrive, so it scales from 1 token /
1 strategy to 100 / 30 with no code change — the same config-driven principle as the
rest of the pipeline.

### D5: State is in-memory and forward-built (no backfill)

Consistent with the intraday, no-backfill model (doc 03): the portfolio is built forward
from the live signal stream. After a restart the aggregator starts flat and rebuilds as
signals arrive. This is acceptable for a monitoring view and avoids state-rehydration
complexity; durable history still lives in QuestDB via the writer.

---

## 4. Emitted messages

### 4.1 Position (topic: `positions`)

```json
{
  "strategy": "obi_market_making",
  "token": "AAPL",
  "netPosition": 100.0,
  "avgEntryPrice": 180.50,
  "realizedPnl": 0.0,
  "unrealizedPnl": 7.00,
  "timestamp": "2026-07-24T10:30:00.000Z"
}
```

### 4.2 Strategy PnL (topic: `strategy-pnl`)

```json
{
  "strategy": "obi_market_making",
  "realizedPnl": 12.50,
  "unrealizedPnl": 7.00,
  "totalPnl": 19.50,
  "numTrades": 8,
  "winRate": 0.625,
  "timestamp": "2026-07-24T10:30:00.000Z"
}
```

Keyed by `token` (positions) / `strategy` (pnl) on Kafka to preserve per-entity
ordering, exactly like the rest of the pipeline.

---

## 5. Database tables (written by database-writer)

```sql
CREATE TABLE positions (
    strategy SYMBOL,
    token SYMBOL,
    net_position DOUBLE,
    avg_entry_price DOUBLE,
    realized_pnl DOUBLE,
    unrealized_pnl DOUBLE,
    ts TIMESTAMP
) TIMESTAMP(ts) PARTITION BY DAY WAL
DEDUP UPSERT KEYS(ts, strategy, token);

CREATE TABLE strategy_pnl (
    strategy SYMBOL,
    realized_pnl DOUBLE,
    unrealized_pnl DOUBLE,
    total_pnl DOUBLE,
    num_trades LONG,
    win_rate DOUBLE,
    ts TIMESTAMP
) TIMESTAMP(ts) PARTITION BY DAY WAL
DEDUP UPSERT KEYS(ts, strategy);
```

The dedup keys encode the domain fact that a `(ts, strategy, token)` position and a
`(ts, strategy)` PnL snapshot are each unique — a redelivered message upserts rather
than duplicating, matching the idempotency approach used everywhere else (ADR-5).

---

## 6. What this component is *not*

- **Not a trade executor.** No orders, no venue, no fills against a real book. Fills are
  simulated at signal price to score strategies.
- **Not a risk engine.** No margin, no position limits, no stop-outs (a natural future
  extension, but out of scope for the monitoring MVP).
- **Not the persistence layer.** It computes and emits; the database-writer persists.

---

## 7. Relationship to the rest of the roadmap

- Building this **before** adding more strategies means every strategy added afterward
  automatically gets position and PnL tracking — no retrofit.
- It makes the upcoming **stateful strategies** (VPIN, Ornstein-Uhlenbeck) immediately
  measurable: as soon as one emits signals, its simulated PnL and positions appear on
  the dashboard, giving a complete vertical slice to evaluate.
