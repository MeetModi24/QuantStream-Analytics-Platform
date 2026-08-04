# Frontend Structure — How the Dashboard Is Wired (and Render Throttling)

## Who this is for

A beginner to real-time frontends. It walks the whole `frontend/` app top to bottom — how
data gets in, where it's stored, how pages read it — and then explains **render throttling**,
the one performance technique that makes a fast feed feel smooth. If you've never built a
live UI, read this start to finish; each section builds on the last.

For the *backend* half (the FastAPI service that feeds this app) see
[`04-serving-and-frontend-layer.md`](04-serving-and-frontend-layer.md). For latency
measurement specifically see [`06-latency-measurement.md`](06-latency-measurement.md).

---

## The 30-second mental model

```
              ┌──────────────────────── the browser tab ────────────────────────┐
              │                                                                   │
  REST  ──────┼──▶ TanStack Query cache ──▶ pages (history, on-load, polling)     │
  (history)   │                                                                   │
              │                                     ┌── Market Overview           │
  WebSocket ──┼──▶ useLiveFeed ──▶ liveStore ──────▶├── Live Signals              │
  (live tip)  │      (1 socket)     (Zustand)       ├── Positions                 │
              │         │              │            └── Token Detail / Strategies │
              │         └── latencyStore (timing) ──▶ header LatencyMeter          │
              └───────────────────────────────────────────────────────────────────┘
```

Two data sources, two stores, many pages. The whole app is small and centralized on
purpose: **all live data enters through one socket and one store**, so there's exactly one
place to optimize.

---

## Part 1 — The two data sources

A dashboard needs two different things, and they have opposite access patterns:

| Need | Example | Pattern | Tool |
|------|---------|---------|------|
| **History** | "the last 200 feature rows", candles, PnL series | *pull* — ask once on load, re-poll occasionally | **REST** via TanStack Query |
| **Live tip** | "what just happened this second" | *push* — server tells us the instant it changes | **WebSocket** via a Zustand store |

Trying to force one tool to do both is the classic beginner mistake:

- History over the WebSocket → you'd replay the whole stream on every reconnect.
- Live updates over REST polling → either laggy (poll slowly) or you hammer the database
  (poll fast).

So we use each for what it's good at. On load, a page paints history from REST immediately;
then the live store takes over, and for any token where a fresher live value exists, the
live tip **overrides** the REST snapshot (`live[token] ?? series[last]` in
`MarketOverview.tsx`).

---

## Part 2 — The data layer (`src/lib`)

Five small files. This is the "plumbing" every page depends on.

### `api.ts` — the REST client
A typed function per endpoint (`api.features(...)`, `api.candles(...)`, `api.positions()`,
…). Nothing clever — it just fetches JSON and returns typed objects.

### `types.ts` — the shared shapes
Every shape the API returns (`FeatureRow`, `SignalRow`, `PositionRow`, …) in one file, so
pages and hooks can't disagree about what a row looks like. Also defines the WebSocket
`LiveEnvelope`.

### `useLiveFeed.ts` — the one socket
A React hook, mounted **once** near the app root. It:
- opens a single WebSocket to `/ws/live`,
- **auto-reconnects with exponential backoff** (capped at 10s) if the connection drops,
- on each message: captures the receive time, samples latency, then hands the envelope to
  the live store.

One socket for the whole app — not one per page or per component. N sockets would multiply
the server's fan-out work and the browser's connection count for zero benefit.

### `liveStore.ts` — the single live-data sink (Zustand)
A tiny global store that holds everything live:
- connection `status` + `lastMessageAt` (for the header dot),
- `latestFeature`: a **map of token → newest FeatureRow** (Market Overview reads this),
- `signals`: a **bounded ring** of recent signals (capped at `MAX_SIGNALS = 500` so a
  long-lived tab can't grow memory forever),
- the server-reported `lagging` drop count.

Everything live flows through its one function, `ingest(env)`. **That is why it's the place
we add render throttling (Part 4).**

### `latencyStore.ts` — timing (read Part on latency in doc 06)
A separate store that measures event→screen latency. Kept apart from `liveStore` on purpose:
it samples *every* message but publishes summaries on a throttle, so timing never causes
per-message re-renders.

> **Why TanStack Query *and* Zustand?** They solve different problems. TanStack Query is a
> **server-cache** — polling, caching, refetch, loading/error states — perfect for pull data.
> Zustand is a tiny **client-state** store for the push stream, where we want a custom merge
> (latest-per-token map, bounded ring) that a query cache isn't built for. Each does what
> it's good at.

---

## Part 3 — The shell and the pages

### `AppShell.tsx` — the frame
The responsive frame around every page: a fixed sidebar rail on desktop that becomes an
off-canvas drawer below `lg` (with a backdrop and Esc-to-close). The header holds the live
connection dot and the latency meter. The sidebar nav links show an **active accent bar** for
the current route and a subtle hover shift, so "where am I" is always obvious.

### The pages
Each page is REST-on-load + live-override where it helps:

- **Market Overview** — sortable token table; live tip overrides the REST snapshot per token.
- **Live Signals** — the streaming signal feed. Shows the latest **25** by default with a
  "show more"; combined with throttling, it updates in smooth per-frame batches instead of
  jumping on every message.
- **Positions & Exposure** — open positions ranked by |PnL| (top 12 default) and a Consensus
  grid that leads with conflicts (top 9 default). Both cap what's shown so 100s of rows don't
  dump at once.
- **Token Detail** — candles (Lightweight Charts) + signal markers + microstructure for one
  token.
- **Strategy Performance** — the per-strategy PnL leaderboard.

The "show a small amount by default, expand on demand" pattern is deliberate: at scale the
first bottleneck is the **human eye and the DOM**, not the network. Rendering 500 rows nobody
reads is wasted work (see [`03-hft-monitoring-at-scale.md`](03-hft-monitoring-at-scale.md)).

---

## Part 4 — Render throttling (the key performance idea)

### The problem in one sentence
A live feed can push **hundreds or thousands of messages per second**, but a screen only
repaints ~**60 times per second**, and the eye perceives far fewer changes than that.
Re-rendering once per message does far more work than anyone can see — and it backfires.

### Why per-message re-rendering backfires
In React, changing state triggers a re-render. The naive version of `liveStore.ingest`
called `set(...)` — a state change — on **every** WebSocket message:

```
message → set() → re-render → reconcile → (maybe) repaint
message → set() → re-render → reconcile → (maybe) repaint
...  100+ times/second at 100 tokens; vastly more at 30k
```

Two costs, both bad:

1. **Wasted renders.** The monitor can't show more than ~60 frames/second, so most of those
   render cycles never reach the screen. Pure wasted CPU.
2. **Head-of-line blocking (the subtle one).** JavaScript is **single-threaded**. Each
   WebSocket `onmessage` callback runs on the same thread as the rendering it triggers. When
   a burst arrives, message #2's callback has to wait in the event-loop queue *behind* the
   render that message #1 kicked off. The line that stamps "when did this message arrive?"
   lives *inside* `onmessage` — so a message stuck behind a render gets a **late** arrival
   stamp. This is why per-message rendering doesn't just waste CPU; it actually *inflates the
   measured latency tail* (see doc 06 for the before/after numbers).

### The fix: buffer, then flush once per frame
Three moving parts, all in `liveStore.ts`:

1. **A buffer outside React state.** Incoming messages go into plain module-level variables —
   a `Map` for features, an array for signals. Writing them touches *no* React state, so it
   triggers *no* re-render. Just a map insert or array push — cheap, so `onmessage` returns
   almost instantly and the event loop stays unblocked.

2. **One scheduled flush per frame.** The first message after a flush schedules a single
   `requestAnimationFrame` callback. Every message that arrives before that callback fires
   just adds to the buffer — it does **not** schedule another flush. So no matter how many
   messages land in one frame, exactly **one** state update is queued.

3. **The flush drains the buffer in one `set()`.** When the frame fires, we apply the whole
   batch in a single store update, then clear the buffer.

```
msg msg msg msg msg      (say 100 in this ~16ms frame)
  │   │   │   │   │
  ▼   ▼   ▼   ▼   ▼
 [ buffer: Map + array ]      ← no re-render, event loop stays free
        │
        │  requestAnimationFrame  (the browser's "about to paint" signal)
        ▼
   ONE set()  →  ONE re-render  →  ONE repaint
```

`requestAnimationFrame` is the browser's own "I'm about to paint" hook. Flushing on it aligns
our updates to the actual refresh rate: **N messages/sec → at most ~60 repaints/sec, usually
far fewer.** Bonus: a backgrounded tab stops calling rAF entirely, so a hidden dashboard does
*zero* render work — a free win.

### Why a `Map` for features but an array for signals
- **Features conflate by token.** Market Overview only wants the *latest* value per token. If
  a token ticks five times in one frame, the four intermediate values were never on screen —
  showing them is pointless. A `Map<token, FeatureRow>` makes a later tick **overwrite** an
  earlier one, so five ticks collapse to one write. This is *conflation* — the same
  recency-over-completeness instinct as the server's drop-oldest queue.
- **Signals accumulate.** The signal feed is a log; each signal is a distinct event, so they
  go into an array, are prepended newest-first on flush, then capped at `MAX_SIGNALS`. We
  don't conflate signals — we bound them.

### What it does *not* change
- **Correctness.** The same data ends up in the store, just in frame-aligned batches.
  Newest-first signal order is preserved (iterate the oldest-first buffer with `unshift`, so
  the last arrival lands on top). The map ends a frame with the last value seen per token.
- **The API surface.** `ingest(env)` is called exactly as before from `useLiveFeed`. Batching
  is entirely internal to the store — no page or hook changed. That's the payoff of making
  the store the single choke point.
- **Latency honesty.** The latency sampler runs *before* `ingest`, so it still times every
  message individually (doc 06). The only added delay is at most one frame (~16 ms) between
  arrival and paint — imperceptible, and bounded.

### How to see it working
Open the dashboard with the feed running and watch Live Signals or Market Overview. In React
DevTools' Profiler, commits-per-second drop from "one per message" to "at most one per frame,"
and CPU during a burst falls with it. The header latency meter's p99 also drops, because the
head-of-line blocking described above is gone.

---

## Part 5 — Where the frontend goes next

Render throttling is **step one** of the frontend-scaling sequence in
[`03-hft-monitoring-at-scale.md`](03-hft-monitoring-at-scale.md):

1. **Render throttling** *(done)* — decouple data-rate from render-rate. One function, no API
   change; caps render cost no matter how fast the feed runs.
2. **Table virtualization** — mount only the visible rows, so a 30k-row table costs about the
   same as a 30-row one.
3. **WebSocket subscription model** — the client subscribes only to the tokens currently on
   screen, so the server never sends what nobody is looking at.

Throttling comes first because it's the cheapest, highest-leverage change. The later steps cap
*how much* is in each render; throttling caps *how often* you render. Together they're how a
monitoring plane stays smooth whether it's showing 1 token or 30,000.
