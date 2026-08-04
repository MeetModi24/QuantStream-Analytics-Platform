#!/usr/bin/env bash
#
# sweep.sh — drive the QuantStream pipeline at increasing message rates and record the
# end-to-end latency curve as it degrades.
#
# HOW IT DRIVES LOAD (no production code changes):
#   The generator emits one snapshot per enabled token every `interval-ms`. With 100 tokens:
#       interval-ms=1000 ->   100 msg/sec   (baseline)
#       interval-ms=200  ->   500 msg/sec
#       interval-ms=100  -> 1,000 msg/sec
#       interval-ms=20   -> 5,000 msg/sec
#       interval-ms=10   -> 10,000 msg/sec
#   The interval is a Spring property (quantstream.generator.interval-ms). We override it via
#   the relaxed-binding env var QUANTSTREAM_GENERATOR_INTERVAL_MS and restart ONLY the
#   generator at each level — the rest of the stack keeps running.
#
# WHAT IT MEASURES:
#   At each level, probe.py connects to /ws/live and measures the same three latency legs the
#   dashboard shows (pipeline/delivery/total, p50/p99/max) plus the ACHIEVED msg/sec. The
#   requested-vs-achieved gap shows where the pipeline stops keeping up.
#
# PREREQUISITE: the full stack must already be up (scripts/start.sh). This script assumes
#   infra + feature-calculator + strategy-engine + database-writer + aggregator + dashboard
#   are running; it only cycles the generator.
#
# Usage:
#   scripts/loadtest/sweep.sh                       # default sweep
#   scripts/loadtest/sweep.sh --intervals "1000 200 100 20 10" --duration 20
#
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/lib.sh"

GEN_MODULE="order-book-generator"
GEN_MAINCLASS="com.quantstream.generator.OrderBookGeneratorApplication"
WS_URL="${WS_URL:-ws://localhost:${DASHBOARD_PORT}/ws/live}"
API_DIR="${REPO_ROOT}/dashboard-api"

INTERVALS="1000 200 100 20 10"   # ms; 100 tokens -> 100/500/1k/5k/10k msg/sec
DURATION=20                       # measured seconds per level
SETTLE=8                          # seconds to let the restarted generator settle before probing

while [[ $# -gt 0 ]]; do
  case "$1" in
    --intervals) INTERVALS="$2"; shift 2 ;;
    --duration)  DURATION="$2"; shift 2 ;;
    --settle)    SETTLE="$2"; shift 2 ;;
    -h|--help)   sed -n '2,40p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

# Count enabled tokens so we can turn interval-ms into a requested msg/sec figure.
TOKEN_COUNT="$(curl -s "http://localhost:${DASHBOARD_PORT}/api/tokens" | tr ',' '\n' | grep -c '"' || echo 0)"
if [ "$TOKEN_COUNT" -eq 0 ]; then TOKEN_COUNT=100; warn "token count probe failed; assuming ${TOKEN_COUNT}"; fi

OUT_DIR="${REPO_ROOT}/docs/benchmarks"
mkdir -p "$OUT_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
CSV="${OUT_DIR}/loadtest-${STAMP}.csv"
JSON_DIR="${LOG_DIR}/loadtest-${STAMP}"
mkdir -p "$JSON_DIR"

echo "interval_ms,requested_msgs_per_sec,achieved_msgs_per_sec,lagging_max,pipeline_p50,pipeline_p99,delivery_p50,delivery_p99,total_p50,total_p99,total_max" > "$CSV"

info "Load sweep: tokens=${TOKEN_COUNT}, intervals=[${INTERVALS}] ms, ${DURATION}s each"
info "Results -> ${CSV}"

restart_generator() {  # $1 = interval_ms
  local interval="$1"
  local pid; pid="$(jvm_pid "$GEN_MAINCLASS")"
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null || true
    # Wait for the JVM to actually exit so the port/producer frees up.
    for _ in $(seq 1 20); do [ -z "$(jvm_pid "$GEN_MAINCLASS")" ] && break; sleep 0.5; done
  fi
  local logfile="${LOG_DIR}/${GEN_MODULE}.log"
  ( cd "$REPO_ROOT" && QUANTSTREAM_GENERATOR_INTERVAL_MS="$interval" \
      nohup mvn -q -pl "$GEN_MODULE" spring-boot:run > "$logfile" 2>&1 & \
      echo "$!" > "${PID_DIR}/${GEN_MODULE}.pid" )
  # Wait until the generator process registers.
  for _ in $(seq 1 40); do [ -n "$(jvm_pid "$GEN_MAINCLASS")" ] && break; sleep 0.5; done
}

for interval in $INTERVALS; do
  requested=$(( TOKEN_COUNT * 1000 / interval ))
  info "── interval ${interval}ms  (~${requested} msg/sec requested) ──"
  restart_generator "$interval"
  ok "generator restarted; settling ${SETTLE}s"
  sleep "$SETTLE"

  json_out="${JSON_DIR}/interval-${interval}.json"
  result="$( cd "$API_DIR" && uv run python "${REPO_ROOT}/scripts/loadtest/probe.py" \
              --url "$WS_URL" --duration "$DURATION" --json "$json_out" 2>/dev/null )"

  # Pull fields with a tiny python one-liner (jq may not be present).
  read -r achieved lag pp50 pp99 dp50 dp99 tp50 tp99 tmax <<< "$(
    python3 - "$json_out" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
print(d["achieved_msgs_per_sec"], d["lagging_max"],
      d["pipeline"]["p50"], d["pipeline"]["p99"],
      d["delivery"]["p50"], d["delivery"]["p99"],
      d["total"]["p50"], d["total"]["p99"], d["total"]["max"])
PY
  )"
  echo "${interval},${requested},${achieved},${lag},${pp50},${pp99},${dp50},${dp99},${tp50},${tp99},${tmax}" >> "$CSV"
  ok "achieved ${achieved} msg/sec | total p50 ${tp50}ms p99 ${tp99}ms | lagging_max ${lag}"
done

echo
info "Sweep complete. CSV: ${CSV}"
info "Per-level JSON: ${JSON_DIR}/"
info "Restore baseline generator with: scripts/loadtest/sweep.sh --intervals 1000 --duration 5"
