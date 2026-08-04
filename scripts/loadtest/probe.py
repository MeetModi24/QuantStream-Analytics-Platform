#!/usr/bin/env python3
"""
probe.py — headless latency probe for the QuantStream live feed.

Connects to the dashboard-api WebSocket (/ws/live) exactly as the browser does, and
measures the SAME three latency legs the dashboard's LatencyMeter shows, from the SAME
envelope fields:

    event    = data.timestamp   (ISO-8601, stamped at the generator source)
    api      = api_ts           (epoch-ms, stamped when the message left Kafka for the API)
    receive  = time.time()*1000 (captured the instant we receive the frame, before parse)

    pipeline = api - event      (generate -> feature -> strategy/Kafka -> API)
    delivery = receive - api    (API -> WebSocket -> here)
    total    = receive - event  (event -> here)

Because it reuses the identical instrumentation, the numbers this prints VALIDATE the
dashboard's own numbers — it is not measuring something different.

It also reports the ACHIEVED message rate (messages/sec actually observed), which at high
load diverges from the requested rate — that gap is itself part of the load story.

Usage:
    uv run python probe.py --url ws://localhost:8000/ws/live --duration 20 [--json out.json]

Requires only `websockets`, which ships with uvicorn[standard] (already in the API venv),
so it runs with `uv run` inside dashboard-api/ with no extra dependency.

CAVEAT (same as the dashboard): during dev the generator, API, and this probe share one
host clock, so the subtractions are exact with no clock-sync. Across machines this would
need NTP/PTP. See docs/concepts/06-latency-measurement.md.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
from datetime import datetime

import websockets


def parse_event_ms(data: dict) -> float | None:
    """Event-origin timestamp from a payload. Java emits `timestamp` as ISO-8601; tolerate
    a numeric epoch-ms too. Returns None if unusable (so the sample is skipped, not poisoned)."""
    t = data.get("timestamp")
    if t is None:
        return None
    if isinstance(t, (int, float)):
        return float(t)
    if isinstance(t, str):
        try:
            # fromisoformat handles the Instant.now() ISO form; normalize trailing Z.
            return datetime.fromisoformat(t.replace("Z", "+00:00")).timestamp() * 1000.0
        except ValueError:
            return None
    return None


def percentile(sorted_vals: list[float], p: float) -> float:
    if not sorted_vals:
        return 0.0
    idx = min(len(sorted_vals) - 1, int((p / 100.0) * len(sorted_vals)))
    return sorted_vals[idx]


def summarize(vals: list[float]) -> dict:
    if not vals:
        return {"p50": 0.0, "p99": 0.0, "max": 0.0, "n": 0}
    s = sorted(vals)
    return {
        "p50": round(percentile(s, 50), 2),
        "p99": round(percentile(s, 99), 2),
        "max": round(s[-1], 2),
        "n": len(s),
    }


async def run(url: str, duration: float, warmup: float) -> dict:
    pipeline: list[float] = []
    delivery: list[float] = []
    total: list[float] = []
    msg_count = 0
    lagging_max = 0
    # We count messages only during the measured window (after warmup) for an honest rate.
    measured_count = 0

    deadline = None  # set once connected
    measure_start = None

    async with websockets.connect(url, max_queue=None, ping_interval=None) as ws:
        start = time.time()
        deadline = start + warmup + duration
        measure_start = start + warmup
        async for raw in ws:
            recv_ms = time.time() * 1000.0
            now = time.time()
            if now >= deadline:
                break
            try:
                env = json.loads(raw)
            except json.JSONDecodeError:
                continue
            msg_count += 1
            lag = env.get("lagging")
            if isinstance(lag, int):
                lagging_max = max(lagging_max, lag)

            # Only record samples in the measured window (let the pipeline settle first).
            if now < measure_start:
                continue
            measured_count += 1

            data = env.get("data", {})
            api_ms = env.get("api_ts")
            event_ms = parse_event_ms(data)

            if event_ms is not None:
                total.append(max(0.0, recv_ms - event_ms))
                if isinstance(api_ms, (int, float)):
                    pipeline.append(max(0.0, api_ms - event_ms))
            if isinstance(api_ms, (int, float)):
                delivery.append(max(0.0, recv_ms - api_ms))

    achieved_rate = round(measured_count / duration, 1) if duration > 0 else 0.0
    return {
        "achieved_msgs_per_sec": achieved_rate,
        "measured_messages": measured_count,
        "warmup_messages": msg_count - measured_count,
        "lagging_max": lagging_max,
        "pipeline": summarize(pipeline),
        "delivery": summarize(delivery),
        "total": summarize(total),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="QuantStream headless latency probe")
    ap.add_argument("--url", default="ws://localhost:8000/ws/live")
    ap.add_argument("--duration", type=float, default=20.0, help="measured seconds")
    ap.add_argument("--warmup", type=float, default=3.0, help="discard seconds before measuring")
    ap.add_argument("--json", default=None, help="also write results as JSON to this path")
    args = ap.parse_args()

    try:
        result = asyncio.run(run(args.url, args.duration, args.warmup))
    except (OSError, websockets.exceptions.WebSocketException) as e:
        print(f"probe: connection failed: {e}", file=sys.stderr)
        return 1

    print(json.dumps(result, indent=2))
    if args.json:
        with open(args.json, "w") as f:
            json.dump(result, f, indent=2)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
