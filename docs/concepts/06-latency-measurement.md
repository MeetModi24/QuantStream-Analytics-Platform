# Latency — How It's Measured, In General and In QuantStream

## Who this is for

Anyone who wants to understand latency measurement from first principles and then see exactly
how QuantStream does it. No prior real-time-systems background assumed. Read Part 1 for the
general theory, Part 2 for our concrete implementation, and Part 3 for the story of why our
p99 *dropped* when we added render throttling — a genuinely instructive result.

Related: the frontend structure and render throttling live in
[`05-frontend-structure.md`](05-frontend-structure.md); the scaling theory in
[`03-hft-monitoring-at-scale.md`](03-hft-monitoring-at-scale.md).

---

# Part 1 — Latency in general

## 1.1 What "latency" actually means

**Latency is the time between a cause and its observable effect.** For a data system: the
time from *an event happening* to *a human seeing it on screen*. It is a duration, measured in
time units (here, milliseconds).

Do not confuse it with two neighbours:

- **Throughput** — *how many* events per second you can handle. A system can have high
  throughput and terrible latency (a batch job), or low throughput and great latency (a
  calculator). They're independent axes.
- **Bandwidth** — how much *data* per second the pipe carries. Related to throughput, not to
  how long any single item takes.

A useful analogy: latency is how long *one* car takes to drive the highway; throughput is how
many cars pass the toll booth per minute; bandwidth is how many lanes the highway has.

## 1.2 Latency is a sum of legs

End-to-end latency is never one number — it's the **sum of every stage** an event passes
through. If an order-book event becomes a pixel, it might traverse:

```
event happens → serialize → network → queue → process → network → deserialize → render → paint
   └─ leg 1 ─┘  └─ leg 2 ─┘ └─leg 3┘ └leg4┘  └─leg 5─┘ └─leg 6─┘  └─ leg 7 ──┘ └leg8┘ └leg9┘
```

The single most important measurement principle follows directly:

> **Break latency into legs and measure each, because the total alone tells you *that* it's
> slow, not *where*.** A 250 ms total is un-actionable; "240 ms of it is in the queue" tells
> you exactly what to fix.

You measure a leg by stamping a clock at its start and its end and subtracting. To measure a
leg you therefore need a timestamp at each boundary that matters.

## 1.3 Why you measure percentiles, not averages

This is the deepest idea in the doc, and the one beginners most often get wrong.

Suppose 100 messages arrive. 99 take 20 ms; one takes 2000 ms.
- **Average** = (99×20 + 2000) / 100 ≈ **40 ms**. Looks fine!
- But a user hit that 2000 ms stall and felt the app freeze.

The average **hides the tail** — and in a real-time system, *the tail is what people feel*. A
UI that's usually snappy but janks once a second feels broken, and the average will never show
it. So we report **percentiles**:

- **p50 (median)** — half the samples are faster than this. "The typical experience."
- **p99** — 99% are faster; the slowest 1% are worse. "The bad-but-not-rare experience."
- **max** — the single worst sample. "The worst thing that happened."

p99 is the headline number for real-time systems because the tail is where jank, missed
frames, and "why did it freeze?" live. Optimizing the average is optimizing the wrong thing.

## 1.4 The clock-synchronization caveat (why cross-machine timing is hard)

To subtract two timestamps and get a meaningful duration, **both clocks must agree.** If the
start stamp comes from machine A and the end stamp from machine B, and B's clock is 50 ms
ahead of A's, your measured leg is off by 50 ms — possibly *negative*, which is nonsense.

- **Same machine:** every stamp reads the same OS clock, so subtraction is exact. Easy.
- **Across machines:** clocks drift. You need synchronization — **NTP** (millisecond-ish,
  common) or **PTP** (sub-microsecond, used in real trading infra) — or you only measure legs
  whose two ends share a clock.

This is why serious latency work in distributed systems is partly a *clock* problem, not just
a *measurement* problem. Always ask "do these two timestamps come from the same clock?" before
trusting a subtraction.

## 1.5 The observer effect

Measuring costs something. If stamping a clock or computing a percentile is expensive and you
do it on the hot path per message, **the measurement itself adds latency** — you've changed
the thing you're measuring. The discipline:

> Capture timestamps as early and as cheaply as possible (a clock read is nearly free), and do
> the expensive part — sorting, percentiles — *off* the hot path, on a throttle.

QuantStream follows this exactly (Part 2.4).

---

# Part 2 — Latency in QuantStream

## 2.1 What we chose to measure

QuantStream is a **monitoring** system, so the latency that matters is **event → screen**: from
the moment an order-book snapshot is generated to the moment the browser has it in hand. (One
honesty note: we stamp "receive," i.e. when the frame is decoded, not the literal paint — see
2.6. The gap is bounded at one animation frame.)

Crucially this is the **monitoring-path** latency (tens of ms), *not* the trading-path latency
(nanoseconds–microseconds, done in C++/FPGA/colocation) that real HFT execution lives in. We
measure the latency our system actually has. See doc 03 for that distinction.

## 2.2 The three clocks

Every live message is timed against three points in wall-clock time:

| Clock | What it is | Where it's set | Code |
|-------|-----------|----------------|------|
| **event** | when the order book was generated | `Instant.now()` at the pipeline's source (the generator), copied unchanged downstream | `Features.timestamp` |
| **api** | when the message left Kafka for the API | epoch-ms stamped onto the envelope | `live_feed.py` → `api_ts` |
| **receive** | when the browser decoded the frame | `Date.now()`, captured *before* `JSON.parse` | `useLiveFeed.ts` |

`event` is a *true event-origin* stamp because it's set once at the source and never
overwritten — every downstream stage carries the original.

## 2.3 The three legs

From those three clocks we derive three durations (ms):

```
pipeline = api - event        generate → feature calc → strategy/Kafka → API
delivery = receive - api      API → WebSocket → browser decode
total    = receive - event    event → screen   (the number a human feels)
```

This is the "measure each leg" principle (1.2) made concrete. If `total` spikes, the
`pipeline` vs `delivery` split tells you *which half* to investigate — a backed-up pipeline
vs. a slow/lagging delivery are completely different problems.

## 2.4 How it's computed — cheaply, off the hot path

In `frontend/src/lib/latencyStore.ts`:

- Each leg has a **bounded ring buffer of the last 512 samples**. Pushing a sample is O(1) and
  — critically — **touches no React state**, so it triggers no re-render (the observer-effect
  discipline from 1.5).
- Percentiles (p50/p99/max) are recomputed **on a throttle, at most every 500 ms**, *not* per
  message. Sorting 512 numbers is cheap, but doing it per message at high rates would be O(n)
  work on the hot path. So:

  > At 30,000 messages/second, the ring still just does O(1) pushes, and the store still
  > publishes only ~2 updates/second. The measurement cost is constant regardless of feed rate.

- Only the throttled recompute calls `set()`, which the header `LatencyMeter` reads. So the
  meter itself never causes per-message renders.

The sampler runs in `useLiveFeed.onmessage` **before** `ingest`, with `receiveMs` captured on
the very first line — so our own decode and render costs don't inflate the measured `delivery`
leg:

```js
const receiveMs = Date.now();                 // 1. stamp arrival immediately
const env = JSON.parse(ev.data);              // 2. decode
sample(eventMillisFromPayload(env.data),      // 3. record latency (cheap, no render)
       env.api_ts, receiveMs);
ingest(env);                                   // 4. buffer for render (throttled)
```

## 2.5 Percentiles and the traffic-light thresholds

The meter shows **p50 and p99** (per 1.3 — the tail is what matters), and colours by p99:

- **green < 250 ms**, **amber < 1 s**, **red beyond**.

These are **monitoring-plane** targets, deliberately generous — a reminder that we are *not*
claiming trading-path latency. Observed on the same-host dev stack: **~20–25 ms p50**, and
after render throttling **~25–50 ms p99** (down from ~250 ms — see Part 3).

## 2.6 The two honesty caveats (stated plainly)

A measurement you over-claim is worse than none. Two caveats ride in the code and here:

1. **Same-host clock.** In development the generator, API, and browser all run on one machine,
   so all three clocks are the same OS clock and the subtractions are exact **with no
   synchronization**. Across machines this would need NTP/PTP (1.4) or the numbers would be
   dominated by clock skew. We don't pretend otherwise.
2. **"receive," not "paint."** We stamp when the frame is *decoded*, not when pixels hit the
   screen. Render throttling (doc 05) adds at most one animation frame (~16 ms) between decode
   and paint. So true event→paint is up to ~16 ms longer than shown — bounded and within the
   noise at these magnitudes, but worth knowing. If we wanted strict "→ paint" we'd take a
   second stamp inside a `requestAnimationFrame` in the flush.

---

# Part 3 — Why p99 *dropped* when we added render throttling

This surprised us in a good way, and it's the most instructive part.

## 3.1 The observation
Before render throttling: p99 ≈ **250 ms**. After: p99 ≈ **25–50 ms**. p50 barely moved
(~20–25 ms both times). So throttling crushed the *tail* while leaving the *typical* case
alone. Why?

## 3.2 The cause: main-thread head-of-line blocking
JavaScript is **single-threaded**. The `receiveMs = Date.now()` stamp is captured *inside*
`onmessage`, which runs on that one thread. Before throttling, `ingest` called `set()` — a
full React re-render — **synchronously inside the message path**. So during a burst:

```
msg1 arrives → onmessage runs → set() → React renders (blocks the thread for X ms)
msg2 arrived during that render → its onmessage callback WAITS in the event-loop queue
msg2 finally runs → its receiveMs is stamped LATE by ~X ms
```

Message 2's *actual* network arrival was on time, but it couldn't get its `receiveMs` stamp
until the thread finished rendering message 1. That late stamp inflates `delivery` (and
`total`) — **for the unlucky messages caught behind a render**. Those unlucky ones are exactly
the p99 tail. The typical message (p50) usually didn't land mid-render, so p50 stayed low.

In other words: the old 250 ms p99 was **not** network or pipeline latency — it was our own UI
blocking the thread that measures arrival. A self-inflicted tail.

## 3.3 The fix removed the blocker
With throttling, `onmessage` only does a cheap buffer push — no `set()`, no render. The thread
frees up instantly, so the next message's `onmessage` (and its `receiveMs` stamp) runs on time.
No head-of-line blocking → no inflated tail → p99 collapses toward p50. The rendering still
happens, but now batched on `requestAnimationFrame`, *off* the measured path.

## 3.4 The lesson
> A latency measurement can be dominated by the measuring system's own contention, not by the
> thing you think you're measuring. Getting expensive work off the hot path didn't just make
> rendering cheaper — it made the *measurement honest*, because the arrival stamp is no longer
> stuck behind a render.

This is section 1.5 (the observer effect) and 1.3 (percentiles expose the tail) meeting in one
real result: the average would have partly hidden this, and only the p99 made the improvement
legible.

---

## Quick reference

| Question | Answer |
|----------|--------|
| What do we measure? | event → screen latency of each live message |
| Which clocks? | event (generator), api (Kafka→API), receive (browser decode) |
| Which legs? | pipeline = api−event, delivery = receive−api, total = receive−event |
| Average or percentile? | percentile (p50/p99/max) — the tail is what users feel |
| Window / cadence? | last 512 samples per leg; percentiles recomputed ≤ every 500 ms |
| Cost per message? | O(1) ring push, no re-render — measurement is rate-independent |
| Observed | ~20–25 ms p50, ~25–50 ms p99 (post-throttling) |
| Caveats | same-host clock (no cross-machine sync); "receive" ≠ "paint" (≤ 1 frame gap) |
| Where in code | `latencyStore.ts`, `useLiveFeed.ts`, `live_feed.py`, `AppShell.tsx` (meter) |
