# QuantStream

**A real-time market-microstructure analytics platform** — streaming order-book intelligence, from synthetic market to trading-desk dashboard.

QuantStream simulates a live limit-order-book market, computes microstructure features from it in real time, runs quantitative signal strategies over the stream, tracks paper-trading positions and PnL, and visualizes the whole thing in a trading-desk-style dashboard.

It is a **monitoring and analytics** system — it does not place real orders. The goal is to demonstrate an end-to-end streaming data platform: synthetic market generation → stream processing → time-series persistence → live API → interactive frontend.

---

## Highlights

- **End-to-end streaming pipeline** — synthetic order books flow through Kafka to feature computation, strategy evaluation, position/PnL aggregation, and time-series storage, all decoupled via topics.
- **Polyglot by design** — a high-throughput **Java 26 / Spring Boot** pipeline for the hot path, a **Python / FastAPI** backend for the read/serve path, and a **React + TypeScript** frontend. Each language is used where it's strongest.
- **Config-driven horizontal scaling** — the system runs today with **100 real-ticker instruments across 8 sectors** and is engineered to scale to tens of thousands purely through configuration (token set in YAML), never a code rewrite.
- **Real microstructure features** — order-book imbalance (OBI), microprice, spread (absolute + basis points), and L5 depth, computed per snapshot.
- **Five live quant strategies** — an OBI market-making signal, an Ornstein-Uhlenbeck mean-reversion, a dual-MA momentum crossover, a Kalman-filter adaptive trend estimator, and an order-flow-persistence (VPIN-style) signal — spanning three distinct theses (reversion, trend, flow).
- **Position & PnL tracking** — notional-normalized fills, realized/unrealized PnL, win rate, and cross-strategy consensus/conflict detection per instrument.
- **Live dashboard** — five pages (Market Overview, Token Detail, Strategy Performance, Positions & Exposure, Live Signals) backed by both REST polling and a live WebSocket feed.
- **Measured end-to-end latency** — the dashboard instruments and displays live event→screen latency (order-book event to browser paint): **~20–25 ms p50, ~250 ms p99**, split into a pipeline leg (event→API) and a delivery leg (API→screen). Percentiles are computed on a throttled rolling window, not per message.

---

## Architecture

```
┌──────────────────────┐
│ Order-Book Generator │  Java · stochastic order-book simulation (GBM price,
│ (Spring Boot)        │  mean-reverting imbalance, depth decay) · 1 snap/sec/token
└──────────┬───────────┘
           │ Kafka topic: order-book-snapshots
           ▼
     ┌─────┴──────────────────────────┐
     ▼                                ▼
┌──────────────────┐         ┌──────────────────┐
│ Feature          │         │ Database Writer  │  Java · batched
│ Calculator (Java)│         │ (Java)           │  writes to QuestDB
│ OBI, microprice, │         └────────┬─────────┘
│ spread, depth    │                  │
└────────┬─────────┘                  ▼
         │ Kafka topic: features   ┌───────────────────────────┐
         ▼                         │      QuestDB (TSDB)       │
┌──────────────────┐              │  order_book_snapshots     │
│ Strategy Engine  │  Java        │  features · signals       │
│ 5 strategies:    │              │  positions · strategy_pnl │
│ OBI·O-U·mom·     │              └────────────┬──────────────┘
│ Kalman·flow      │                           │
└────────┬─────────┘                           │
         │ Kafka topic: signals                │
         ▼                                      │
┌──────────────────┐                            │
│ Signal Aggregator│  Java · position tracking, │
│ notional sizing, │  realized/unrealized PnL,  │
│ PnL, conflicts   │──────────────┐             │
└──────────────────┘  writes ─────┘             │
                                                │
                          ┌─────────────────────┴────────────┐
                          │  Dashboard API (Python / FastAPI) │
                          │  REST over QuestDB (httpx async)  │
                          │  + WebSocket tailing Kafka topics │
                          └─────────────────┬─────────────────┘
                                            │ HTTP + WS
                                            ▼
                          ┌───────────────────────────────────┐
                          │   React + TypeScript Frontend     │
                          │   Vite · Lightweight Charts ·     │
                          │   Recharts · Zustand · TanStack   │
                          └───────────────────────────────────┘
```

---

## Tech Stack

| Layer | Technology | Why |
|-------|-----------|-----|
| **Streaming pipeline** | Java 26, Spring Boot 4.0.7, Spring for Apache Kafka | Throughput, threading, and mature Kafka integration on the hot path |
| **Message broker** | Apache Kafka (Confluent 7.6.1) | Decouples producers/consumers; the backbone of the streaming design |
| **Time-series store** | QuestDB 8.3.2 | Fast ingestion, SQL, `SAMPLE BY` / `LATEST ON` built for time-series |
| **Serving API** | Python 3.11+, FastAPI, httpx, `websockets` | Async I/O for read-heavy REST + a live WebSocket feed |
| **Frontend** | React 18, TypeScript, Vite 6, TanStack Query, Zustand | Typed, real-time UI; server cache (REST polling) + live store (WS) |
| **Charts** | TradingView Lightweight Charts (candles), Recharts (analytics) | Purpose-built financial candles + flexible analytics charts |
| **Infra** | Docker Compose | One-command local infra (Kafka, Zookeeper, QuestDB, Kafka-UI) |

---

## Data Flow

1. **Generate** — the order-book generator emits a 5-level bid/ask snapshot per instrument every second, using stochastic models (geometric Brownian motion for price, mean-reverting order-book imbalance, exponential depth decay).
2. **Feature-ize** — the feature calculator derives OBI, microprice, spread, spread (bps), and L5 depth from each snapshot and republishes to the `features` topic.
3. **Persist** — the database writer batches raw snapshots and features into QuestDB.
4. **Signal** — the strategy engine runs all five strategies over every token, emitting `BUY` / `SELL` / `CLOSE` signals with a confidence and a human-readable reason.
5. **Aggregate** — the signal aggregator turns signals into notional-normalized fills, maintains per-strategy/per-token positions, computes realized/unrealized PnL and win rate, and detects cross-strategy conflicts.
6. **Serve** — the FastAPI backend exposes REST endpoints over QuestDB and a WebSocket that tails the live Kafka `features`/`signals` streams.
7. **Visualize** — the React dashboard renders live market state, candles with signal markers, strategy leaderboards, positions, and a streaming signal feed.

---

## Strategies

Five strategies run concurrently over every token, spanning three distinct theses — **flow**, **mean reversion**, and **trend** — so the dashboard surfaces genuine cross-strategy agreement and conflict rather than variations of one idea.

| Strategy | Type | Thesis | Idea |
|----------|------|--------|------|
| **OBI Market Making** | Stateless | Flow | Reads order-book imbalance; goes long when buy pressure exceeds a threshold and short when sell pressure dominates. Reacts to an *instantaneous* imbalance. |
| **Flow Toxicity** | Stateful | Flow | A volume-free, VPIN-style order-flow proxy: a rolling mean of signed OBI. Fires only when imbalance stays one-sided across the whole window — *persistence*, not a spike — so it deliberately disagrees with the instantaneous OBI signal. |
| **Ornstein-Uhlenbeck Mean Reversion** | Stateful | Reversion | Rolling window of microprice with a z-score `(price − mean) / stdev`; enters against extremes and exits near the mean. |
| **Dual-MA Momentum** | Stateful | Trend | Fast/slow moving-average crossover of the microprice; rides the trend when the fast MA pulls decisively away from the slow, closes when they converge. The opposite thesis to O-U reversion. |
| **Kalman Adaptive Trend** | Stateful | Trend | A constant-velocity Kalman filter recursively estimating a hidden *level + velocity* from the microprice; trades the estimated velocity. Unlike the fixed-window dual-MA, its process/measurement-noise ratio self-tunes how fast it trusts a new move. |

All stateful strategies share the same discipline: edge-triggered `FLAT / LONG / SHORT` state (one signal per regime change, not per tick), hysteresis (entry band wider than exit band) to avoid whipsaw, and a warmup equal to the window size — **no historical backfill; state is built forward from the live stream**.

The strategy layer is an SPI (`Strategy` interface + factories auto-discovered as Spring beans), so new strategies are added — and tuned or disabled via YAML — without touching the engine wiring or anything downstream.

---

## Project Structure

```
.
├── common/                 # Shared domain models, Kafka config, TokenRegistry (YAML-driven)
├── order-book-generator/   # Java: synthetic order-book producer
├── feature-calculator/     # Java: microstructure feature computation
├── strategy-engine/        # Java: strategy SPI + implementations
├── signal-aggregator/      # Java: positions, PnL, conflict detection
├── database-writer/        # Java: QuestDB persistence
├── dashboard-api/          # Python/FastAPI: REST over QuestDB + WebSocket feed
├── frontend/               # React + TypeScript + Vite dashboard
├── scripts/                # start / stop / status / clean lifecycle scripts
├── docs/                   # Concepts, planning, and engineering notes
└── docker-compose.yml      # Kafka, Zookeeper, QuestDB, Kafka-UI
```

---

## Getting Started

### Prerequisites

- **JDK 26** and **Maven**
- **Docker Desktop** (Kafka + QuestDB)
- **Python 3.11+** and [`uv`](https://github.com/astral-sh/uv)
- **Node.js 18+**

### Run the whole stack

The lifecycle scripts bring everything up in the correct order (infra → build → pipeline → API → frontend), waiting on real readiness at each tier:

```bash
./scripts/start.sh      # bring up the entire stack
./scripts/status.sh     # one-glance health of every tier + live QuestDB row counts
./scripts/stop.sh --down  # clean teardown (removes containers, keeps QuestDB data volume)
```

Once up:

| Service | URL |
|---------|-----|
| Frontend dashboard | http://localhost:5173 |
| Dashboard API | http://localhost:8000 |
| QuestDB console | http://localhost:9001 |
| Kafka UI | http://localhost:8080 |

> **Note:** the Ornstein-Uhlenbeck strategy has a ~10-minute warmup (600 observations) before it emits signals — it builds its rolling window forward from the live stream rather than backfilling.

---

## Scaling Design

The project is deliberately built so that scaling the instrument universe is a **configuration change, not a rewrite**. It runs today with **100 instruments** — real tickers spanning 8 sectors (tech, financials, crypto, energy, healthcare, consumer, industrial, utilities), each with sector-calibrated volatility and spread — and the exact same code path served a single token during early development:

- The instrument universe is defined in external YAML and loaded by a shared `TokenRegistry`; no token lists are hardcoded, so 1 → 100 → tens of thousands is purely an edit to that file.
- Every service processes instruments generically (one generator/consumer path per enabled token), so throughput scales by adding Kafka partitions and running more consumer instances (horizontal partitioning by symbol).
- At 100 tokens the pipeline sustains ~100 snapshots/second end-to-end (100 feature rows/second) with all five strategies evaluating every token.
- The intended production target is on the order of tens of thousands of instruments at ~1 message/second each, distributed across a Kafka cluster and multiple strategy-engine instances.

---

## Notable Engineering Decisions

- **Notional-normalized position sizing** — fills are sized by notional (`units = notional / price`) rather than fixed lots, so PnL is comparable across instruments with wildly different price scales.
- **Intraday-only data model** — no historical backfill; stateful strategies build their windows forward from the live stream, with tiered retention handled separately from the hot path.
- **QuestDB ingestion via PG-wire JDBC** — chosen over the ILP client to avoid a JVM incompatibility; the serving layer reads via QuestDB's HTTP `/exec` with `SAMPLE BY` for candle aggregation and `LATEST ON` for current state.
- **Split read/write backends** — the Java pipeline owns the write path (ingestion, computation), while a separate FastAPI service owns the read/serve path, keeping the two workloads independent.

Deeper write-ups live in [`docs/`](docs/) — see `docs/concepts/` for microstructure fundamentals and design notes, and `docs/engineering/` for hurdles hit and how they were fixed.

---

## License

MIT — free to use for learning and portfolio purposes.
