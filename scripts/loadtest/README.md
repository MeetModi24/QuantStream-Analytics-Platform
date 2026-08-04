# Load-test harness

Drives the QuantStream pipeline at increasing message rates and records the end-to-end
latency curve. Results and the analysis report live in
[`docs/benchmarks/`](../../docs/benchmarks/).

## Files

- **`probe.py`** — headless WebSocket client for `/ws/live`. Measures the same three latency
  legs the dashboard's LatencyMeter shows (pipeline / delivery / total, p50/p99/max) plus the
  achieved msg/sec, and prints JSON. Runs with `uv run` inside `dashboard-api/` — the only
  dependency (`websockets`) ships with `uvicorn[standard]`, already in that venv.
- **`sweep.sh`** — restarts *only* the generator at each load level (via the Spring env var
  `QUANTSTREAM_GENERATOR_INTERVAL_MS`), waits for it to settle, runs `probe.py`, and writes a
  CSV row per level. No production code changes — load is purely the generator's tick rate.

## Prerequisite

The full stack must already be up (`scripts/start.sh`). The sweep assumes infra + the four
downstream JVM services + dashboard are running; it only cycles the generator.

## Usage

```bash
scripts/loadtest/sweep.sh                                  # 100 → 10k msg/sec, 20s each
scripts/loadtest/sweep.sh --intervals "1000 100 10" --duration 30
scripts/loadtest/sweep.sh --intervals 1000 --duration 5    # restore baseline (1 msg/s/token)

# One-off single measurement without cycling the generator:
cd dashboard-api && uv run python ../scripts/loadtest/probe.py --duration 20
```

Load math: with N enabled tokens, `interval-ms` → `N * 1000 / interval-ms` msg/sec. At 100
tokens: 1000ms→100/s, 100ms→1k/s, 10ms→10k/s.
