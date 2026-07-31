# HFT Monitoring Dashboards at Scale

## Purpose

This document is about a different problem than the rest of the docs. The others explain
*what QuantStream is* and *how it works today* at 100 tokens. This one asks:

> **What would it take for the monitoring plane to scale to ~30,000 tokens and many
> strategies at once — the way a real HFT firm's dashboards do — and which of those
> problems can we actually solve in this codebase without breaking it?**

It is written for someone new to the domain. It builds up from first principles, names
the real engineering challenges HFT full-stack engineers face, maps each one to *our*
system (are we already OK? is it a real gap?), and lays out a concrete, **additive**
roadmap — changes that layer on top of the working 100-token system rather than rewriting
it.

The goal is twofold: a **learning reference** (so the concepts are understood, not
cargo-culted) and a **portfolio artifact** (so the thinking behind the system is legible
to a reviewer).

---

## The single most important reframe: two different "latencies"

Before anything else, one distinction has to be crystal clear, because conflating the two
sends you down completely the wrong path.

### 1. Trading-path latency (the hot path)

The time from *market event → strategy decision → order leaves the machine*.

- Measured in **nanoseconds to microseconds**.
- Built in **C++ / Rust / FPGA**, with kernel-bypass NICs (Solarflare/Onload, DPDK),
  CPU pinning, lock-free data structures, cache-line-aware layouts, and **colocation** —
  the servers physically sit in the exchange's datacenter to shave microseconds of light-travel time.
- Every allocation, every syscall, every cache miss matters.

**QuantStream is not this, and never will be.** We run on Kafka → JVM services → Python
→ a web browser. Each hop adds milliseconds. That is completely fine — *nobody builds the
hot trading path in a web stack.* If someone asks "isn't Java/Python too slow for HFT?",
the correct answer is: "yes, for the trading path — which is why that lives in C++/FPGA.
This system is the **monitoring plane**, which is a different problem with different
latency targets."

### 2. Monitoring / observability-path latency (what we are)

The time from *the pipeline computed something → a human sees it on a dashboard*.

- Measured in **tens to low-hundreds of milliseconds**.
- This is exactly the layer **full-stack engineers at HFT firms actually build**: the
  real-time risk dashboards, the P&L monitors, the market-surveillance heatmaps, the
  "what are all our strategies doing right now" screens.
- The engineering is dominated not by nanosecond tricks but by **data-volume management**:
  how do you move, store, and *display* a firehose without melting the browser or the human.

**So the goal of this work is not to make the strategies faster.** It is to make the
monitoring plane scale to 30k tokens × many strategies while staying (a) real-time to the
eye and (b) actually usable by a human. That is a real, respectable, and *achievable*
engineering problem on our stack.

---

## The core principle that drives every decision

Here is the insight everything else follows from:

> **At scale, the first bottleneck is never the network or the CPU — it is the human eye
> and the browser's DOM. So never send, render, or hold-in-the-client data the human is
> not currently looking at.**

A trader monitoring 30,000 instruments is not reading 30,000 rows. They are looking at:

- a handful of **aggregates** (per-sector heatmap, total P&L, count of active signals,
  top movers), and
- a **small visible subset** — maybe 20–50 instruments they've drilled into.

Showing 30,000 live-updating rows at once is not just slow, it is **useless** — no human
can read it. (This is exactly the "it is awful to the eyes" instinct that motivated this
whole document, and it is correct.) So the architecture is built around **aggregation +
subscription to a visible subset**, never "firehose everything to everyone."

Every technique below is a consequence of this one principle.

---

## The challenges, tier by tier

For each: *what the challenge is* → *what real HFT dashboards do* → *where QuantStream
stands today.*

### Tier A — Server → client transport (our biggest lever)

**Challenge:** a naive live feed pushes every update for every instrument to every
connected client. At 30k tokens × 1 update/sec, that is 30,000 messages/second *per
browser tab*. The tab dies; the network saturates; nothing is readable.

**What HFT dashboards do:**

1. **Subscription model, not broadcast.** The client tells the server *which* instruments
   are on screen (`subscribe: [AAPL, MSFT, ...]`), and the server forwards **only those**.
   When the user scrolls or drills into a different token, the subscription changes. This
   is the number-one scaling change — it turns "30k msg/sec" into "50 msg/sec."

2. **Conflation / coalescing.** If a token ticks 10 times between two screen repaints, the
   client only needs the **latest** value. The server keeps a *last-value-per-token* cache
   and flushes on a fixed cadence (e.g. every 100 ms), collapsing bursts into one message.
   This **decouples the update rate from the human-perceivable rate** — the essential trick.

3. **Server-side aggregation.** The "overview of 30k tokens" is never 30k rows on the
   wire — it is *summary statistics*: a per-sector heatmap, top-N movers, a count of tokens
   with active signals. A few hundred bytes instead of megabytes.

4. **Binary framing (later/advanced).** JSON is verbose and slow to parse. At real scale,
   firms use protobuf / MessagePack / FlatBuffers over the wire. Big win eventually,
   premature for us.

**Where QuantStream stands:** our `/ws/live` currently **broadcasts every message to every
client** (see `dashboard-api/app/main.py` → `ws_live`). That is fine at 100 tokens and is
the single biggest thing that would *not* survive 30k. **But** we already have two things
most beginners miss: a live WebSocket (not polling) for the live path, and **backpressure**
— the server tracks `dropped`/`lagging` per subscriber and tells the client when it fell
behind. That backpressure instinct is exactly right and is the foundation conflation
builds on.

### Tier B — Frontend rendering (the second wall)

**Challenge:** even if the data arrives efficiently, the browser cannot render 30,000
live-updating DOM nodes. Mounting 30k `<tr>` elements and re-rendering them on every tick
is what actually freezes the tab — before the network is even the problem.

**What HFT dashboards do:**

1. **Virtualization / windowing.** Only the ~40 rows *in the viewport* are mounted as real
   DOM; the rest is virtual scroll space (`react-window`, `@tanstack/react-virtual`). A
   30k-row table becomes ~40 live DOM nodes.

2. **Decouple data-rate from render-rate.** Live data lands in a plain store/ref at
   whatever rate it arrives; the UI repaints on a **throttled `requestAnimationFrame`
   loop** (say 10 fps). 30k updates/second still cause only ~10 repaints/second. Without
   this, React tries to reconcile on every message and dies.

3. **Canvas / WebGL for dense visuals.** A 30k-cell heatmap or a 30k-point scatter cannot
   be DOM elements — they are drawn on a `<canvas>`. The DOM is for structure; canvas is
   for density.

4. **Default view = aggregates; detail = drill-down.** The landing page for 30k tokens is
   a sector heatmap or a top-movers list. You *click into* a token to get its live detail,
   candles, and per-strategy signals. (Again — exactly the right instinct.)

**Where QuantStream stands:** our pages already **reflow responsively** and the Market
Overview uses a **single batched query** for all tokens (not N requests). The live data is
already **centralized in one Zustand store** (`liveStore.ts`) — which is the ideal place to
add a throttled flush. What we do *not* have yet: table virtualization (invisible at 100
rows, essential at 30k) and a throttled render loop. Both are additive.

### Tier C — Backend / API

**Challenge:** read queries that scan the full universe on every poll (e.g. "top 50 by
|OBI| across 30k tokens every 2 seconds") become expensive; and the API must not become a
bottleneck between the fast pipeline and the client.

**What HFT dashboards do:**

1. **Push for live, poll for historical.** Live/changing data goes over the WebSocket;
   on-demand/historical data (candles, past signals) stays REST. (We already split this
   way.)

2. **Pre-aggregate hot queries.** Time-series rollups / materialized views so "top movers"
   or "per-sector averages" are cheap indexed reads, not full scans. QuestDB's `SAMPLE BY`
   and `LATEST ON` are built for exactly this.

3. **Backpressure and bounded queues** everywhere, so a slow client or a burst never blocks
   the fast producer. (We have this on the WS path.)

**Where QuantStream stands:** the read/serve path is a **separate FastAPI service** from
the write pipeline (good separation of concerns), uses **async I/O**, and already batches.
The gap at scale is aggregate/rollup endpoints (top-N, per-sector) that let the frontend
show summaries instead of everything.

### Tier D — Observability (measuring the thing we want to improve)

**Challenge:** you cannot optimize latency you do not measure. "It feels laggy" is not an
engineering statement.

**What HFT dashboards do:** stamp events with timestamps at each pipeline stage and track
**end-to-end latency distributions** (p50 / p99 / p99.9 — tails matter more than averages
in this world), plus per-stage breakdowns and throughput (messages/second). This is often
*itself displayed on the dashboard* as a health panel.

**Where QuantStream stands:** we don't yet measure end-to-end (event → screen) latency.
This is the recommended **first** piece of work — both because you can't prove any later
optimization helped without a baseline, and because a live "event→screen p50/p99 latency"
panel is one of the most compelling, HFT-flavored things this project could show.

---

## Feasibility on *this* codebase (nothing breaks)

Everything high-value here is an **additive layer** on the existing architecture — no
rewrite, no change to the Java pipeline or the strategies. That is not luck; the current
design is well-positioned for it:

- a live WebSocket already exists (transport is in place),
- live data is already centralized in one store (one place to add throttling),
- there is already a backpressure signal (the foundation for conflation),
- reads already go through a separate, async, batching API (room for aggregate endpoints).

Cheapest-to-hardest, all additive:

| Improvement | Tier | Effort | Risk | Payoff | Notes |
|---|---|---|---|---|---|
| **Latency instrumentation** (event→screen p50/p99 + per-stage) | D | Low | None | High | Do this first — the baseline that makes everything else provable, and a great demo artifact. |
| **Frontend render throttling** (rAF-batched flush in `liveStore`) | B | Low | Low | High | Decouples data-rate from repaint-rate. The store already centralizes live data. |
| **Table virtualization** (`react-window` on Market Overview) | B | Low–Med | Low | High | Invisible at 100 tokens, essential at 30k. |
| **Aggregate/summary endpoints** (top-N movers, per-sector rollups) | C | Med | Low | High | Enables the "don't render 30k rows" default view. |
| **Server-side conflation** (last-value-per-token, flush @ 100 ms) | A | Med | Low | High | Collapses bursts; the essence of scalable live delivery. |
| **WS subscription model** (client subscribes to visible tokens) | A | Med | Low | Very High | The real 30k architecture. Keep "subscribe to all" as default so the current UI keeps working. |
| **Binary WS protocol** (msgpack/protobuf) | A | High | Med | Med | Premature — skip until the above are in and measured. |

**Non-goals (explicitly):** we are *not* trying to reach microsecond trading-path latency,
*not* rewriting services in C++/Rust, and *not* adding more tokens or strategies. The
point is to make the **monitoring plane** scale and stay real-time to the eye.

---

## Recommended sequence

1. **Instrument latency end-to-end** — stamp a produced-at time at the generator, carry it
   through the pipeline, have the API stamp received-at, and surface event→screen p50/p99
   plus a per-stage breakdown in a UI health panel. *Measure the baseline before changing
   anything.*
2. **Frontend perf** — throttled render loop + table virtualization. Cheap, safe,
   immediately visible; needs no backend change.
3. **Backend scaling** — server-side conflation, then the WS subscription model. This is
   the architecture that actually reaches 30k.
4. **Aggregate views** — top-movers / per-sector summary endpoints and the drill-down UX,
   so 30k tokens is legible instead of overwhelming.

Each step is independently shippable and independently demoable, and each one is a concrete
talking point: *"here's how I measured it, here's the number before, here's the change,
here's the number after."*

---

## How to talk about this (interview framing)

If asked "how would this scale to production?", the strong answer is layered:

1. **Separate the latencies.** "The trading path is a different system — C++/FPGA,
   colocated, microseconds. This is the monitoring plane, where the target is tens of
   milliseconds to the human eye, and the hard part is data-volume management, not
   nanosecond tuning."
2. **State the principle.** "The first bottleneck at scale is the eye and the DOM, so you
   never push or render what nobody's looking at — you aggregate, conflate, and subscribe
   to the visible subset."
3. **Point at the concrete levers.** Subscription model, server-side conflation,
   virtualization, throttled rendering, pre-aggregated queries — and *why each one attacks
   a specific bottleneck.*
4. **Show you measured.** "I instrumented event→screen latency first, because 'it feels
   slow' isn't an engineering statement — you optimize against p99, not vibes."

That progression — reframe, principle, levers, measurement — is what distinguishes someone
who has *thought about* scale from someone who has only memorized buzzwords.
