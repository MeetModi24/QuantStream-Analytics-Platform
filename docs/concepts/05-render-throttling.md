# Render Throttling — Decoupling Data-Rate from Render-Rate

## The problem, in one sentence

A live feed can push **hundreds or thousands of messages per second**, but a screen only
repaints about **60 times per second**, and a human eye perceives far fewer changes than
that. If you re-render the UI once for *every* message, you do far more work than anyone can
see — and the browser chokes.

This document explains the fix we implemented in `frontend/src/lib/liveStore.ts`, aimed at a
reader who has never built a real-time UI before.

---

## Why re-rendering per message is a trap

In React, changing state triggers a re-render. Our live data flows through a single Zustand
store (`liveStore`). The old code called `set(...)` — a state change — on **every WebSocket
message**:

```
message → set() → React re-render → reconcile → repaint
message → set() → React re-render → reconcile → repaint
message → set() → React re-render → reconcile → repaint
...  (100+ times a second at 100 tokens, far more at 30k)
```

Each `set()` wakes up every component subscribed to that slice of the store, React diffs the
virtual DOM, and the browser may repaint. At 100 messages/second that's 100 render cycles a
second. The catch: **the monitor physically cannot show more than ~60 frames a second, and
the eye cannot follow even that.** Every render beyond what lands on screen is wasted CPU —
and it competes with the very repaint you *do* want, making the whole page feel *more*
laggy, not less.

This is the frontend mirror of a rule we already apply on the backend and in the latency
meter: **never do O(n) work per message on the hot path.** You accumulate cheaply and
summarize at a human-perceivable cadence.

---

## The fix: batch onto animation frames

The idea has three moving parts:

1. **A buffer that lives outside React state.** Incoming messages are dropped into plain
   module-level variables (a `Map` and an array). Writing them touches *no* React state, so
   it triggers *no* re-render. This is cheap — just a map insert or an array push.

2. **A single scheduled flush per frame.** The first message that arrives after a flush
   schedules one `requestAnimationFrame` callback. Every message that arrives before that
   callback runs just adds to the buffer; it does **not** schedule another flush. So no
   matter how many messages land in a frame, exactly **one** state update is queued.

3. **The flush drains the buffer in one `set()`.** When the animation frame fires, we apply
   the whole batch — every buffered feature and signal — in a single store update, then clear
   the buffer for the next frame.

```
msg msg msg msg msg   (100 in this frame)
  │   │   │   │   │
  ▼   ▼   ▼   ▼   ▼
 [ buffer ]           ← plain Map/array, no re-render
      │
      │  requestAnimationFrame (fires ~once per 16ms)
      ▼
   ONE set()  →  ONE re-render  →  ONE repaint
```

`requestAnimationFrame` is the browser's own "I'm about to paint" signal. By flushing on it,
we align our updates to the screen's actual refresh rate: **N messages/second become at most
~60 repaints/second — and usually far fewer, because the callback only runs when the tab is
visible.** (A backgrounded tab stops calling rAF entirely, so a hidden dashboard does zero
render work — a free win.)

---

## Why a `Map` for features but an array for signals

The two live data types want different batching, and the buffer shapes reflect that:

- **Features conflate by token.** The Market Overview only cares about the *latest* value
  per token. If a token ticks five times in one frame, showing the intermediate four is
  pointless — they were never on screen. So features go into a `Map<token, FeatureRow>`:
  a second update for the same token in the same frame simply **overwrites** the first.
  Five ticks for one token collapse to one write. This is *conflation* — the same
  recency-over-completeness instinct as the server's drop-oldest queue.

- **Signals accumulate.** The Live Signals feed is a log; each signal is a distinct event you
  might want to see, so they go into an array and are all prepended (newest-first) on flush,
  then capped at `MAX_SIGNALS`. We don't conflate signals — we bound them.

---

## What this does *not* change

- **Correctness.** The same data ends up in the store; it just arrives in batches aligned to
  frames instead of one-at-a-time. Newest-first signal ordering is preserved (we iterate the
  oldest-first buffer with `unshift`, so the last arrival lands on top). The latest-per-token
  map ends a frame with exactly the last value seen for each token.
- **Latency.** The extra wait is at most one frame (~16 ms) — imperceptible, and dwarfed by
  the ~20–25 ms p50 pipeline+delivery latency the feed already has. The latency meter still
  samples *every* message on receive (before this batching), so the measured number is
  honest.
- **The API surface.** `ingest(env)` is called exactly as before from `useLiveFeed`. The
  batching is entirely internal to the store — no page or hook changed. This is why the store
  was built as the single choke point in the first place.

---

## How to see it working

Open the dashboard with the feed running and watch the Live Signals or Market Overview page.
Before throttling, a burst of signals would repaint the table on every arrival; now the table
updates in smooth per-frame batches. In React DevTools' Profiler, the number of commits per
second drops from "one per message" to "at most one per frame," and CPU during a burst falls
correspondingly.

---

## Where this sits in the scaling story

This is step one of the frontend-scaling sequence from
[`03-hft-monitoring-at-scale.md`](03-hft-monitoring-at-scale.md):

1. **Render throttling** *(this doc)* — decouple data-rate from render-rate. Done in one
   function because all live data flows through one store.
2. **Table virtualization** — mount only the visible rows, so a 30k-row table costs the same
   as a 30-row one.
3. **WebSocket subscription model** — the client subscribes only to the tokens currently on
   screen, so the server never sends what nobody is looking at.

Throttling comes first because it's the cheapest, highest-leverage change: one function, no
API change, and it caps render cost no matter how fast the feed runs. The later steps then
cap *how much* is in each render. Together they're how a monitoring plane stays responsive
whether it's showing 1 token or 30,000.
