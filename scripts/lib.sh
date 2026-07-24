#!/usr/bin/env bash
#
# lib.sh — shared helpers for the QuantStream dev scripts. Sourced, not run directly.
#
# Defines the canonical service topology in ONE place so start/stop/status agree:
#   - INFRA_CONTAINERS : docker-compose services (order matters: deps first)
#   - JVM_SERVICES     : "module|MainClass|port" for each Spring Boot pipeline service
#   - dashboard is handled separately (Python/uv, not Maven)

# Resolve repo root regardless of where the script is invoked from.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LOG_DIR="${REPO_ROOT}/logs"
PID_DIR="${REPO_ROOT}/logs/pids"

QUESTDB_HTTP="${QUESTDB_HTTP:-http://localhost:9001}"
QUESTDB_PG_PORT="${QUESTDB_PG_PORT:-8812}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"
DASHBOARD_PORT="${DASHBOARD_PORT:-8000}"

# Docker containers, in dependency order (compose handles depends_on, but we wait per-tier).
INFRA_CONTAINERS=(zookeeper kafka questdb kafka-ui)

# Pipeline JVM services in logical start order: producer first, then consumers.
# Format: "module|fully.qualified.MainClass|serverPort"
JVM_SERVICES=(
  "order-book-generator|com.quantstream.generator.OrderBookGeneratorApplication|8081"
  "feature-calculator|com.quantstream.features.FeatureCalculatorApplication|8083"
  "strategy-engine|com.quantstream.strategy.StrategyEngineApplication|8084"
  "database-writer|com.quantstream.dbwriter.DatabaseWriterApplication|8082"
  "signal-aggregator|com.quantstream.aggregator.SignalAggregatorApplication|8085"
)

# ---- pretty output ---------------------------------------------------------
c_reset=$'\033[0m'; c_grn=$'\033[32m'; c_ylw=$'\033[33m'; c_red=$'\033[31m'; c_cyn=$'\033[36m'
info() { echo "${c_cyn}==>${c_reset} $*"; }
ok()   { echo "  ${c_grn}ok${c_reset}   $*"; }
warn() { echo "  ${c_ylw}warn${c_reset} $*"; }
err()  { echo "  ${c_red}FAIL${c_reset} $*" >&2; }

# ---- process helpers -------------------------------------------------------
# We identify a running JVM service by its Spring Boot main class via jps, which is
# more reliable than a PID file that can go stale across reboots.
jvm_pid() {  # $1 = MainClass
  jps -l 2>/dev/null | awk -v c="$1" '$2==c {print $1}'
}

is_container_up() {  # $1 = container name
  [ "$(docker inspect -f '{{.State.Running}}' "$1" 2>/dev/null)" = "true" ]
}

# ---- readiness probes ------------------------------------------------------
# Block until a TCP port accepts a connection, or time out.
wait_for_port() {  # $1=host $2=port $3=label $4=timeout_s
  local host="$1" port="$2" label="$3" timeout="${4:-60}" i=0
  while ! (exec 3<>"/dev/tcp/${host}/${port}") 2>/dev/null; do
    i=$((i+1))
    if [ "$i" -ge "$timeout" ]; then err "${label} not reachable on ${host}:${port} after ${timeout}s"; return 1; fi
    sleep 1
  done
  exec 3>&- 2>/dev/null || true
  ok "${label} reachable (${host}:${port})"
}

# QuestDB is "ready" only once its SQL engine answers, not just when the port opens.
wait_for_questdb() {  # $1=timeout_s
  local timeout="${1:-60}" i=0
  while true; do
    if curl -s --get "${QUESTDB_HTTP}/exec" --data-urlencode "query=SELECT 1" 2>/dev/null | grep -q '"dataset"'; then
      ok "QuestDB SQL engine ready (${QUESTDB_HTTP})"; return 0
    fi
    i=$((i+1))
    if [ "$i" -ge "$timeout" ]; then err "QuestDB SQL not answering after ${timeout}s"; return 1; fi
    sleep 1
  done
}
