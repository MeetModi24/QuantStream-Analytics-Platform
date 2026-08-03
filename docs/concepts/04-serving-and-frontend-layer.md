# The Serving & Frontend Layer

## Purpose

This document explains the **read/serve half** of QuantStream — the `dashboard-api`
(Python/FastAPI) and the `frontend` (React/TypeScript) — as a layer: what each piece does,
how they fit together, and *why they are shaped the way they are* for real-time
performance.

The Java pipeline (generator → feature calculator → strategy engine → aggregator → DB
writer) is the **write path**: it produces data. Everything in this document is the
**read path**: it serves that data to a human, live. The two are deliberately independent
processes so the read workload can never back-pressure the hot pipeline.

If [`03-hft-monitoring-at-scale.md`](03-hft-monitoring-at-scale.md) is the *theory* of
scaling a monitoring plane, this document is the *concrete anatomy* of the layer we would
apply that theory to — including what is already optimized and what is measured.

---

## The two data paths (the central design choice)

The single most important idea in this layer: **there are two paths, and they are
different on purpose.**

```
                        dashboard-api (FastAPI)
                        ┌───────────────────────────────────────────┐
   QuestDB  ── HTTP ──▶ │  REST endpoints  ──────────────┐          │
   (durable store)      │  (historical / on-load queries) │          │
                        │                                 ▼          │
   Kafka   ── tail ───▶ │  LiveFeed ── per-client queues ── /ws/live │
   (features, signals)  │  (live tip of the stream)                  │
                        └───────────────────────────────────────────┘
                                        │ HTTP (REST)   │ WebSocket (live)
                                        ▼               ▼
                        frontend (React)
                        ┌───────────────────────────────────────────┐
   TanStack Query ◀─────┤ REST cache (polling, on-load history)      │
   Zustand liveStore ◀──┤ WebSocket (live tip, one socket app-wide)  │
                        └───────────────────────────────────────────┘
```

- **REST over QuestDB** — historical and on-load queries: recent features, signals, latest
  order book, candles, PnL series, positions. This reads the **durable store**, so a
  freshly-opened dashboard is painted with history immediately, and it survives restarts.
- **WebSocket over Kafka** — the **live tip** of the stream, pushed as it happens with
  sub-second latency. It carries no history; it only ever shows "what's happening now."

**Why split them?** Each is optimized for its job. History is a *pull* problem (the client
asks once, on load, and occasionally re-polls) — REST + a query cache is exactly right.
"What's happening now" is a *push* problem (the server should tell the client the instant
something changes) — a WebSocket is exactly right. Trying to do live updates over REST
polling means either high latency (poll slowly) or hammering the DB (poll fast); trying to
do history over the WebSocket means replaying the whole stream on every connect. The split
lets each side be simple and fast.

**The handshake on the frontend:** a page loads history over REST to paint immediately,
then the live store takes over — for any token where the WebSocket has delivered a fresher
value, the live tip *overrides* the REST snapshot. (See `MarketOverview.tsx`: `live[token]
?? series[last]`.)

---

## Backend anatomy — `dashboard-api`

Five small modules, ~500 lines total. Small on purpose: this service only reads and serves.

### `main.py` — the HTTP/WS surface

The FastAPI app and every endpoint. Notable choices:

- **`lifespan`** starts the QuestDB client and the Kafka `LiveFeed` once at boot and tears
  them down cleanly on shutdown — no per-request connection setup.
- **One batched endpoint for many tokens.** `GET /api/features/recent` fetches recent rows
  for *all* tokens in a **single** QuestDB query and groups them server-side, rather than
  one request per token. This is the difference between the Market Overview making 1 call
  and making 100 — it is why the page scales past a handful of tokens. (An earlier version
  did N calls; collapsing it was a deliberate fix.)
- **QuestDB's time-series operators do the heavy lifting**, not Python:
  - `SAMPLE BY {interval}` builds OHLC candles inside the database (`/api/candles`) —
    aggregation happens where the data lives, not by pulling raw rows into the API.
  - `LATEST ON ts PARTITION BY ...` gives the efficient last-row-per-key for the PnL
    leaderboard and current positions, instead of scanning and de-duplicating in Python.
- **Defensive SQL string-escaping** (`_esc`) — QuestDB's `/exec` takes no bind parameters,
  and although tokens come from a fixed config-controlled universe, single quotes are
  escaped anyway so a quote can never break out of a string literal.

### `questdb.py` — the async query client

A thin wrapper over QuestDB's HTTP `/exec` endpoint. It:

- holds **one long-lived `httpx.AsyncClient`** (connection reuse — no per-query TCP/TLS
  setup),
- turns QuestDB's `{columns, dataset}` shape into a list of dicts the API can serialize
  directly,
- centralizes error handling so a QuestDB-side SQL error becomes a clean `QuestDBError`
  rather than a silent empty result.

Async matters here: the API is **read-heavy and I/O-bound** (it spends its time waiting on
QuestDB and sockets, not computing). `async`/`await` lets one process handle many
concurrent requests and WebSocket clients without threads, because while one request waits
on the database another can make progress.

### `live_feed.py` — Kafka → WebSocket fan-out (the interesting one)

This is where the live-performance thinking lives.

- **One Kafka consumer, many clients.** A single `AIOKafkaConsumer` tails the `features`
  and `signals` topics. Each message is fanned out to *every* connected browser. One
  consumer for the process, not one per client — connections are cheap, Kafka consumers
  are not.
- **A fresh random consumer group per process start** (`group_id=None`,
  `auto_offset_reset=latest`) means the dashboard always reads from the **live tip** and
  never replays history. History is REST's job; the live feed is strictly "now."
- **Bounded per-client queues are the back-pressure boundary.** Each subscriber has an
  `asyncio.Queue(maxsize=1000)`. When a message arrives, the feed does a *non-blocking*
  `put`. If a browser tab stalls (backgrounded, slow network) and its queue fills, the feed
  **drops the oldest message** to make room and increments a `dropped` counter — it never
  blocks the shared consumer and never grows memory without limit.
- **Lagging is surfaced, not hidden.** The drop count rides along to the client as a
  `lagging` field, so the UI can honestly show "you fell behind by N" instead of silently
  presenting stale data as fresh. *This is the instinct most naive live dashboards miss* —
  a monitoring tool must be honest about its own staleness.
- **The lock is held only to snapshot the subscriber set**, then released before the
  fan-out `put`s — so one slow client can't hold up delivery to the others.

Why "drop-oldest" and not "buffer everything"? A live dashboard cares about **recency, not
completeness**. If you've fallen behind, the *newest* value is the one worth showing; the
stale ones in the queue are already worthless. This is a **conflation-adjacent** decision
(see the scaling doc) — and it's the philosophically correct default for this domain.

### `config.py` — everything tunable

All operational parameters (broker list, QuestDB URL, timeouts, `client_queue_size`) are
pydantic settings overridable by env var. Nothing about scaling requires a code change.

---

## Frontend anatomy — `frontend`

React + TypeScript + Vite. The data layer is small and centralized; the pages read from it.

### The data layer (`src/lib`)

- **`api.ts`** — a typed client for the REST endpoints.
- **`types.ts`** — the shapes every endpoint returns, in one place so pages and hooks agree.
- **`useLiveFeed.ts`** — opens **one** app-wide WebSocket near the root, with
  **auto-reconnect and exponential backoff** (capped at 10s). One socket for the whole app,
  not one per page or per component.
- **`liveStore.ts`** — a **Zustand** store that is the single sink for live data. It keeps:
  - connection status + last-message time (for the header indicator),
  - the **latest `FeatureRow` per token** (a map — the Market Overview reads the live tip),
  - a **bounded ring of the most recent signals** (capped at `MAX_SIGNALS = 500`, so memory
    is bounded no matter how long the tab stays open),
  - the server-reported `lagging` drop count.

**Why one central store fed by one socket?** Two reasons, both about performance and
correctness:

1. **One connection.** N components opening N sockets would multiply the server's fan-out
   work and the browser's connection count. One socket, shared via the store, is the scalable
   shape.
2. **One place to optimize.** Because *all* live data flows through `liveStore.ingest`, that
   one function is the natural choke point to add batching/throttling later (see the scaling
   doc's "decouple data-rate from render-rate"). You optimize one function, not fifty
   components.

**Why TanStack Query for REST *and* Zustand for live?** They solve different problems.
TanStack Query is a **server-cache**: it handles polling intervals, caching, refetching,
and loading/error states for pull data — perfect for history. Zustand is a tiny
**client-state** store for the push stream, where we want a custom merge (latest-per-token,
bounded ring) that a query cache isn't built for. Using each for what it's good at keeps
both simple.

### The pages

Five pages, each backed by REST-on-load + live-override where it makes sense: Market
Overview, Token Detail, Strategy Performance, Positions & Exposure, Live Signals. The
`AppShell` provides the responsive frame — a fixed sidebar rail on desktop that becomes an
off-canvas drawer below `lg`, so the layout works on any width.

---

## What is already optimized (and why it counts)

This layer was built with the scaling principles in mind from the start. Concretely, it
already does:

| Optimization | Where | Why it matters |
|---|---|---|
| **Split read/write processes** | whole layer | Read load can never back-pressure the hot Java pipeline. |
| **Push for live, pull for history** | REST vs. WebSocket | Each path optimized for its access pattern; no fast-polling the DB. |
| **One batched multi-token query** | `/api/features/recent` | 1 request instead of N — the page scales past a handful of tokens. |
| **DB-side aggregation** | `SAMPLE BY`, `LATEST ON` | Candles and last-per-key computed in QuestDB, not by pulling raw rows. |
| **Connection reuse** | one `httpx.AsyncClient`, one Kafka consumer | No per-request/per-client setup cost. |
| **Bounded per-client queues + drop-oldest** | `live_feed.py` | One slow tab can't block others or leak memory; recency over completeness. |
| **Honest back-pressure signal** | `lagging` field | The UI can admit staleness instead of lying. |
| **One socket, one central live store** | `useLiveFeed` + `liveStore` | Scalable connection shape; a single choke point for future throttling. |
| **Bounded client-side history** | `MAX_SIGNALS`, latest-per-token map | Browser memory stays bounded on a long-lived tab. |
| **Auto-reconnect with backoff** | `useLiveFeed` | Survives transient drops without hammering the server. |

---

## Latency instrumentation (measure before you optimize)

You cannot improve a latency you don't measure, and "it feels laggy" is not an engineering
statement. So this layer measures its own **event → screen** latency, end to end, live.

### The three clocks

Every live message can be timed against three points in wall-clock time:

- **event** — `Features.timestamp`, set as `Instant.now()` at the pipeline's *source* (the
  order-book generator) and copied unchanged downstream, so it is a true event-origin stamp.
- **api** — `api_ts`, an epoch-ms timestamp the API stamps onto the envelope the moment the
  message leaves Kafka for the API (`live_feed.py`).
- **receive** — `Date.now()` captured in the browser the instant the WebSocket frame is
  decoded, *before* `JSON.parse` of the body, so our own decode cost doesn't inflate the number.

From those, three legs (ms):

```
pipeline = api - event        generate → feature → strategy/Kafka → API
delivery = receive - api      API → WebSocket → browser
total    = receive - event    event → screen  (the number that matters to a human)
```

The split is what makes it *diagnostic*: if total latency spikes, the pipeline/delivery
breakdown tells you *which half* to look at.

### How it's computed (and why that way)

`frontend/src/lib/latencyStore.ts` keeps a **bounded ring of the last 512 samples** per leg
and recomputes **p50 / p99 / max on a throttle** (at most every 500 ms), *not* on every
message. That is deliberate and is itself a demonstration of the scaling doctrine:

> At high message rates you never do O(n) work per message on the render path. You push to
> a cheap ring in O(1) and summarize at a human-perceivable cadence.

Pushing to the ring does **not** touch React state (so it triggers no re-render); only the
throttled percentile recompute publishes into the store, which the header `LatencyMeter`
reads. So 30k messages/second would still cause only ~2 store updates/second.

We report **percentiles, not averages** — in a real-time system the **tail** (p99) is what
users feel, and an average hides it. Green/amber/red thresholds (250 ms / 1 s) are
**monitoring-plane** targets, not trading-path targets — a reminder of which latency we're
even talking about.

### The clock caveat (stated honestly)

During development the generator, API, and browser all run on **one host**, so they share a
wall clock and the subtractions are meaningful with no clock synchronization. Across
machines this would be dominated by clock skew and would require synchronized clocks
(NTP/PTP) — or you'd measure only the legs that share a clock. This is called out in the
code and here so the number is never over-claimed.

---

## Where this layer goes next

The gaps are the ones named in [`03-hft-monitoring-at-scale.md`](03-hft-monitoring-at-scale.md),
and this layer is deliberately shaped to accept them additively:

- **Render throttling** — batch `liveStore` updates onto a `requestAnimationFrame` loop.
  The central store is already the one place to do this.
- **Table virtualization** — mount only the visible rows of the Market Overview.
- **WebSocket subscription model** — let the client subscribe to only the visible tokens;
  the server already has the per-subscriber structure to filter on.
- **Server-side conflation** — a last-value-per-token cache flushed on a cadence, extending
  the drop-oldest instinct that's already in `live_feed.py`.

None of these require touching the Java pipeline or the strategies — that separation is
exactly what the two-path design buys us.
