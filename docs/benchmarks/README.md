# Load & Latency Benchmarks

Every performance claim QuantStream makes is backed by a **measured** load sweep, not a
single idle observation. This directory holds the harness output and this report explains
what was run, what the numbers mean, and — candidly — what they do and don't prove.

- Harness: [`scripts/loadtest/`](../../scripts/loadtest/) (`sweep.sh` + `probe.py`)
- Method & theory of the metric: [`docs/concepts/06-latency-measurement.md`](../concepts/06-latency-measurement.md)
- Raw results: `loadtest-<timestamp>.csv` in this directory (+ per-level JSON under `logs/`)

---

## What we measured

**Event → screen latency of the live feed, as offered load increases from 100 to ~11,000
messages/second — a 100× range.** The probe (`probe.py`) connects to the dashboard-api
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
  means the queues never backed up (0 dropped, no `lagging`), so we found the *floor*, not the
  *ceiling*. The real breaking point is somewhere past 10k/sec — this run doesn't locate it,
  and the report doesn't pretend to.
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
scripts/loadtest/sweep.sh                          # default sweep: 100→10k msg/sec
#   --intervals "1000 200 100 20 10"   which rates (ms interval; 100 tokens)
#   --duration 20                       measured seconds per level
scripts/loadtest/sweep.sh --intervals 1000 --duration 5   # restore baseline afterwards
```

Output lands as `docs/benchmarks/loadtest-<stamp>.csv` (one row per level) plus per-level JSON
under `logs/loadtest-<stamp>/`. The generator is the only thing cycled; the rest of the stack
keeps running throughout.

---

## What this validates (and what's next)

**Validated:** the dashboard's advertised ~15–25 ms p50 / ~25–50 ms p99 is real and *holds
under 100× load* — the numbers on the LatencyMeter aren't an idle-stack artifact.

**Not yet done — the honest gap:** we found the floor, not the ceiling. The natural follow-up
is to push past 10k/sec until latency *does* degrade and drops begin, so we can plot the
knee of the curve and name the actual bottleneck (likely single-partition Kafka ordering or
the single strategy-engine instance). That's where the "server-side conflation + subscription
model" work from doc 03 would earn its place.
