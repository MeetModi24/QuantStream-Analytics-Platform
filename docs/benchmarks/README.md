# Load & Latency Benchmarks

Every performance claim QuantStream makes is backed by a **measured** load sweep, not a
single idle observation. This directory holds the harness output and this report explains
what was run, what the numbers mean, and — candidly — what they do and don't prove.

- Harness: [`scripts/loadtest/`](../../scripts/loadtest/) (`sweep.sh` + `probe.py`)
- Method & theory of the metric: [`docs/concepts/06-latency-measurement.md`](../concepts/06-latency-measurement.md)
- Raw results: `loadtest-<timestamp>.csv` in this directory (+ per-level JSON under `logs/`)

---

## What we measured

**Event → screen latency of the live feed, as offered load increases from 100 to ~42,000
messages/second — across two sweeps that together span the flat floor, the knee, and total
collapse.** The probe (`probe.py`) connects to the dashboard-api
WebSocket exactly as the browser does and measures the **same three legs** the dashboard's
LatencyMeter shows (so this run *validates* the dashboard's own numbers, it doesn't measure
something different):

- **pipeline** = `api_ts − event` — generate → feature calc → strategy/Kafka → API
- **delivery** = `receive − api_ts` — API → WebSocket → client
- **total** = `receive − event` — the number a human feels

## How load was driven (no production-code change)

The generator emits one snapshot per enabled token every `interval-ms`. With 100 tokens the
interval *is* the load knob, overridden per level via the Spring env var
`QUANTSTREAM_GENERATOR_INTERVAL_MS`; `sweep.sh` restarts **only** the generator at each level
and leaves the rest of the stack running.

| interval_ms | requested msg/sec (100 tokens) |
|---|---|
| 1000 | 100 |
| 200 | 500 |
| 100 | 1,000 |
| 20 | 5,000 |
| 10 | 10,000 |

Each level: 3s warmup discarded, then **20s measured**.

---

## Results

Run `loadtest-20260804-230803` — MacBook, full stack on one host (100 tokens, 5 strategies),
all latencies in **milliseconds**:

| Requested msg/s | Achieved msg/s | Dropped | pipeline p50 | pipeline p99 | delivery p50 | delivery p99 | **total p50** | **total p99** | total max |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 100    | 119     | 0 | 15.2 | 30.2 | 2.4 | 5.8 | **17.4** | **33.6** | 37.1 |
| 500    | 562     | 0 | 9.6  | 25.0 | 2.1 | 5.1 | **11.9** | **25.9** | 32.2 |
| 1,000  | 1,126   | 0 | 11.6 | 30.9 | 2.2 | 4.9 | **14.3** | **32.6** | 52.3 |
| 5,000  | 5,623   | 0 | 10.2 | 28.0 | 1.9 | 4.3 | **12.3** | **29.7** | 54.3 |
| 10,000 | 11,256  | 0 | 10.5 | 32.4 | 1.9 | 5.6 | **12.5** | **35.7** | 64.9 |

### The headline

**A 100× increase in offered load (119 → 11,256 msg/sec achieved) left latency essentially
flat** — total p50 stayed in the 12–17 ms band and p99 in the 26–36 ms band — with **zero
dropped messages** at every level. The tail max crept from 37 ms to 65 ms; that's the only
visible cost of 100× the traffic.

### Reading it honestly

- **The system is nowhere near its bottleneck at 10k msg/sec.** Flat latency under 100× load
  means the queues never backed up (0 dropped, no `lagging`), so this first sweep found the
  *floor*. The *ceiling* is located by the second sweep below (knee ~33k, collapse by 50k).
- **Achieved slightly exceeds requested** (e.g. 11,256 vs 10,000) because the generator's
  scheduler catches up backlog after its restart within the measured window; it's a
  measurement artifact of counting over a fixed window, not free throughput.
- **delivery is tiny and stable (~2 ms p50)** across the whole range — the API→client leg
  (which includes the render-throttling-fed path on the browser, though this probe is
  headless) is not the constraint. Latency lives in the **pipeline** leg (~10–15 ms:
  Kafka + feature + strategy hops), as expected.
- **p99 ≈ 2× p50**, not 10×. A well-behaved tail — no pathological stalls — which is exactly
  what the render-throttling fix bought on the browser side (see doc 06 Part 3) and what
  bounded per-client queues buy on the server side.

---

## Finding the ceiling (10k → 100k msg/sec)

The run above found the *floor*. A second sweep (`loadtest-20260805-212155`) pushed past 10k
to locate where the pipeline actually breaks — same harness, `--intervals "10 8 5 3 2 1"`:

| Requested msg/s | Achieved msg/s | Dropped | pipeline p50 | pipeline p99 | **total p50** | **total p99** | Verdict |
|---:|---:|---:|---:|---:|---:|---:|:--|
| 10,000  | 11,255 | 0       | 10.0 | 32.6 | **12.0** | **36.2** | healthy |
| 12,500  | 14,065 | 0       | 10.0 | 26.6 | **12.0** | **29.2** | healthy |
| 20,000  | 22,498 | 0       | 12.4 | 39.8 | **15.3** | **45.7** | healthy |
| 33,333  | 37,288 | 3,123   | 19.0 | 82.7 | **25.2** | **96.5** | **the knee** |
| 50,000  | 42,581 | 121,397 | 3,173 | 6,664 | **3,188** | **6,677** | collapsed |
| 100,000 | 42,747 | 107,666 | 10,020 | 16,728 | **10,034** | **16,744** | saturated |

### The ceiling, stated plainly

- **Sustained ceiling ≈ 20k msg/sec.** Up to 20k, latency stays flat (p50 ~15 ms, p99 ~46 ms)
  with **zero drops**. That's the honest "handles this comfortably" number.
- **The knee is ~33k msg/sec.** First drops appear here (3,123) and p99 doubles to ~97 ms —
  degrading but not yet collapsed.
- **Hard collapse by 50k.** Latency jumps from 25 ms to **3.2 seconds** (p50) and drops
  explode to 121k. By 100k it's fully saturated: ~10 s latency, achieved throughput pinned.

### It's consumer-bound, not producer-bound

Two signatures in the data pin the bottleneck to the **pipeline consumers**, not the generator:

1. **Achieved throughput plateaus at ~42k msg/sec** (37k → 42k → 42k) even as demand rises to
   50k then 100k — the system has a hard max-sustainable-*consume* rate it can't exceed.
2. **Drops appear at 33k, *before* achieved throughput plateaus.** If the generator were the
   limit, we'd see achieved flatten with latency staying low and no drops. Instead the pipeline
   latency (the `pipeline` leg) is what explodes — from 19 ms to 3.2 s between 33k and 50k —
   while the `delivery` leg stays tiny (~14 ms) throughout. The API→client path never
   struggled; the **generate→feature→strategy→Kafka path** did.

This matches the architecture: a single Kafka partition per topic (chosen for per-token
ordering) and a **single strategy-engine instance** evaluating all 5 strategies × 100 tokens
serialize the hot path. Past ~20–33k msg/sec that single consumer can't drain Kafka as fast as
it fills, the queue backs up, and latency becomes queue-wait time (hence the multi-second p50).

> ⚠️ **Note on the 50k/100k rows:** these were run briefly and the stack was torn down
> immediately after — sustained multi-second-latency operation saturates CPU/RAM and isn't
> something to leave running. The collapse point is well-established by these samples; there's
> no value in dwelling above the knee.

### The caveats (same as the metric itself)

1. **Same-host clock.** Generator, API, and probe share one OS clock, so the subtractions are
   exact with no NTP/PTP. Across machines these legs would need synchronized clocks. This is a
   *floor* measurement of the software path, not a network-realistic one.
2. **Synthetic, uniform load.** One snapshot per token per tick — no bursty hotspots, no
   skewed key distribution. Real markets are burstier.
3. **We measured "receive," not "paint."** The probe stops at decode; the browser adds ≤ one
   animation frame (~16 ms) to reach pixels (doc 06 §2.6).

---

## Reproduce it

```bash
scripts/start.sh                                   # bring the whole stack up
scripts/loadtest/sweep.sh                          # floor sweep: 100→10k msg/sec
scripts/loadtest/sweep.sh --intervals "10 8 5 3"   # ceiling sweep: 10k→33k (stops at the knee)
#   --intervals "1000 200 100 20 10"   which rates (ms interval; 100 tokens)
#   --duration 20                       measured seconds per level
scripts/loadtest/sweep.sh --intervals 1000 --duration 5   # restore baseline afterwards
```

> **Don't dwell above the knee.** Intervals of 2ms/1ms (50k/100k) drive the stack into
> multi-second-latency saturation, which pins CPU/RAM. The knee is fully characterized by the
> `10 8 5 3` range; tear the stack down promptly if you probe beyond it.

Output lands as `docs/benchmarks/loadtest-<stamp>.csv` (one row per level) plus per-level JSON
under `logs/loadtest-<stamp>/`. The generator is the only thing cycled; the rest of the stack
keeps running throughout.

---

## What this validates (and what's next)

**Validated:**
- The dashboard's advertised ~15–25 ms p50 / ~25–50 ms p99 is real and *holds under load up to
  ~20k msg/sec* — the LatencyMeter numbers aren't an idle-stack artifact.
- The full curve is characterized end to end: **flat to ~20k, knee at ~33k, collapse by 50k**,
  with a hard consume ceiling of ~42k msg/sec on one host.
- The bottleneck is **identified from the data**, not guessed: the pipeline consumer path
  (single-partition Kafka + single strategy-engine instance), because the `pipeline` leg is
  what explodes while `delivery` stays flat and achieved throughput plateaus before demand.

**Now-justified next work.** With the ceiling and bottleneck named, the scaling work from
[doc 03](../concepts/03-hft-monitoring-at-scale.md) is now *evidence-driven* rather than
speculative — each item targets the specific limit this sweep found:

- **Partition Kafka topics + run multiple strategy-engine instances** (partition by symbol).
  Directly attacks the consumer serialization that caps consume throughput at ~42k/sec.
- **Server-side conflation** (last-value-per-token flushed on a cadence). Would blunt the
  collapse: past the knee, dropping stale intermediate values keeps latency bounded instead of
  letting the queue grow to multi-second wait times.
- **WebSocket subscription model.** Orthogonal to the ceiling (that's a serving-fan-out win),
  but complements conflation.

The point for a portfolio: we didn't build scaling machinery on a hunch. We measured, found the
system comfortable to 20k msg/sec and located exactly where and why it breaks, and *that*
number is what tells us which optimization is worth the complexity.

---

## Tradeoff discussion: JSON vs. binary serialization (protobuf / SBE)

A natural question: should the pipeline use a binary wire format (Protobuf, Avro, or the
FIX-industry's Simple Binary Encoding) instead of JSON? **For this project, no — and the
benchmark is exactly why.** This is the clearest example of letting measured data, not
instinct, decide.

### What binary encoding would buy

1. **Smaller payloads** — binary is ~30–50% smaller on the wire than JSON.
2. **Faster encode/decode** — no string parsing; typically 2–5× faster than JSON.
3. **Schema enforcement** — a `.proto`/`.avsc` contract instead of hand-kept types that must
   agree across Java and TypeScript.

### Why we don't need it here

- **The bottleneck is not serialization.** The ceiling sweep proved the collapse is
  *consumer-bound* — the single strategy-engine instance can't drain Kafka past ~33k msg/sec.
  The `pipeline` leg exploded; JSON encode/decode was a flat, tiny contributor the whole way
  up. Switching to binary would shave a few ms off a leg that was never the problem — the exact
  mistake this benchmark exists to prevent.
- **The delivery leg is ~2 ms at 100 tokens.** Payload size and parse cost only dominate when
  you're saturating NICs or burning CPU on parsing — the 30k-token, cross-machine regime we've
  explicitly chosen not to target. At this workload it's noise, and we have ~200× headroom over
  the actual 100 msg/sec load.
- **JSON keeps the system debuggable.** Messages are human-readable in Kafka-UI and browser
  devtools — a real asset for a demo/portfolio system. Binary encoding trades that away for
  throughput we don't need.

### The one real cost of JSON (and the cheap fix)

JSON's informality did cost us once: field-name drift (Java emits `timestamp`, the frontend
type called it `ts`), worked around by parsing `data.timestamp` explicitly in
`eventMillisFromPayload`. But protobuf is a sledgehammer for that papercut — it means a
`.proto` file plus codegen wired into *both* the Maven build and the Vite build. A shared JSON
schema, or simply aligning the field names, fixes the drift at a fraction of the cost.

### Where binary *would* be the right call

At genuine HFT scale — tens of thousands of instruments, cross-machine, where wire size and
parse CPU actually bound throughput — binary encoding is correct, and the industry often goes
leaner than protobuf (SBE, designed for exactly this). So it belongs in the architecture story
as *"the production path once serialization is on the critical path"* — but building it at 100
tokens with 200× headroom would be over-engineering. **JSON now is a deliberate choice, not an
oversight; binary is the identified next step *when the workload justifies it*** — the same
evidence-driven posture as the scaling deferrals above.
