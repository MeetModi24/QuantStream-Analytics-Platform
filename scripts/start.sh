#!/usr/bin/env bash
#
# start.sh — bring up the ENTIRE QuantStream stack, in the right order, idempotently.
#
# Order is not cosmetic — it is the difference between a clean boot and the bugs we
# actually hit during development:
#   1. Infra containers (Zookeeper -> Kafka -> QuestDB) via docker compose, each waited
#      on until truly ready (QuestDB's SQL engine, not just its port — HikariCP fails if
#      it connects before the engine answers).
#   2. `mvn install` the whole reactor once, so every module's dependency (esp. `common`)
#      is in the local repo. Running a single module with `spring-boot:run` before this
#      fails with "Could not find artifact com.quantstream:common".
#   3. JVM pipeline services: producer (generator) first, then feature-calculator,
#      strategy-engine, database-writer, aggregator. Kafka auto-creates topics, so a
#      consumer starting before a producer is fine, but this order makes logs readable.
#   4. Dashboard API (FastAPI/uv) last — it only reads QuestDB + tails Kafka.
#
# Idempotent: already-running containers/services are detected and skipped, so re-running
# start.sh never double-starts anything.
#
# Usage:
#   scripts/start.sh              # full stack
#   scripts/start.sh --no-build   # skip the mvn install (fast restart of unchanged code)
#   scripts/start.sh --no-dashboard
#   scripts/start.sh --infra-only # just docker compose + waits
#
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

DO_BUILD=true; DO_DASHBOARD=true; DO_FRONTEND=true; INFRA_ONLY=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --no-build)     DO_BUILD=false; shift ;;
    --no-dashboard) DO_DASHBOARD=false; shift ;;
    --no-frontend)  DO_FRONTEND=false; shift ;;
    --infra-only)   INFRA_ONLY=true; shift ;;
    -h|--help)      sed -n '2,30p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

cd "$REPO_ROOT"
mkdir -p "$LOG_DIR" "$PID_DIR"

# ---- 1. Infra containers ---------------------------------------------------
info "Starting infra containers (docker compose up -d)"
docker compose up -d
wait_for_port localhost 2181 "Zookeeper" 60
wait_for_port localhost 9092 "Kafka" 90
wait_for_port localhost "$QUESTDB_PG_PORT" "QuestDB PG-wire" 60
wait_for_questdb 60

if $INFRA_ONLY; then info "Infra only — done."; exit 0; fi

# ---- 2. Build the reactor --------------------------------------------------
if $DO_BUILD; then
  info "Building all modules (mvn install -DskipTests) so 'common' is resolvable"
  if mvn -q install -DskipTests; then ok "reactor build succeeded"; else err "build failed — aborting"; exit 1; fi
else
  warn "Skipping build (--no-build); using existing target/ classes"
fi

# ---- 3. JVM pipeline services ----------------------------------------------
info "Starting pipeline JVM services"
for entry in "${JVM_SERVICES[@]}"; do
  IFS='|' read -r module mainclass port <<< "$entry"
  existing="$(jvm_pid "$mainclass")"
  if [ -n "$existing" ]; then
    warn "${module} already running (pid ${existing}) — skipping"
    continue
  fi
  logfile="${LOG_DIR}/${module}.log"
  nohup mvn -q -pl "$module" spring-boot:run > "$logfile" 2>&1 &
  echo "$!" > "${PID_DIR}/${module}.pid"
  ok "${module} launching (port ${port}) -> ${logfile}"
  # Small stagger so startup logs don't interleave and Kafka group joins settle.
  sleep 3
done

# Give Spring Boot a moment, then confirm each came up via jps.
info "Waiting for JVM services to register"
sleep 12
for entry in "${JVM_SERVICES[@]}"; do
  IFS='|' read -r module mainclass port <<< "$entry"
  if [ -n "$(jvm_pid "$mainclass")" ]; then ok "${module} up"; else err "${module} NOT running — check ${LOG_DIR}/${module}.log"; fi
done

# ---- 4. Dashboard API ------------------------------------------------------
if $DO_DASHBOARD; then
  info "Starting dashboard API (FastAPI/uv) on port ${DASHBOARD_PORT}"
  if lsof -ti "tcp:${DASHBOARD_PORT}" >/dev/null 2>&1; then
    warn "port ${DASHBOARD_PORT} already in use — assuming dashboard already running"
  else
    ( cd "${REPO_ROOT}/dashboard-api" && uv sync -q && \
      nohup uv run uvicorn app.main:app --host 0.0.0.0 --port "${DASHBOARD_PORT}" \
        > "${LOG_DIR}/dashboard-api.log" 2>&1 & echo "$!" > "${PID_DIR}/dashboard-api.pid" )
    ok "dashboard launching -> ${LOG_DIR}/dashboard-api.log"
  fi
fi

# ---- 5. Frontend (Vite dev server) -----------------------------------------
if $DO_FRONTEND; then
  info "Starting frontend (Vite) on port ${FRONTEND_PORT}"
  if lsof -ti "tcp:${FRONTEND_PORT}" >/dev/null 2>&1; then
    warn "port ${FRONTEND_PORT} already in use — assuming frontend already running"
  elif [ ! -d "${FRONTEND_DIR}/node_modules" ]; then
    warn "frontend deps missing — run 'npm install' in ${FRONTEND_DIR} first; skipping"
  else
    ( cd "$FRONTEND_DIR" && \
      nohup npm run dev > "${LOG_DIR}/frontend.log" 2>&1 & echo "$!" > "${PID_DIR}/frontend.pid" )
    ok "frontend launching -> ${LOG_DIR}/frontend.log"
  fi
fi

echo
info "Stack up. Endpoints:"
echo "  Frontend     http://localhost:${FRONTEND_PORT}"
echo "  Dashboard    http://localhost:${DASHBOARD_PORT}"
echo "  QuestDB UI   ${QUESTDB_HTTP}"
echo "  Kafka UI     http://localhost:8080"
echo "  Logs         ${LOG_DIR}/"
echo "  Check status: scripts/status.sh   |   Stop all: scripts/stop.sh"
echo
warn "Note: the Ornstein-Uhlenbeck strategy has a ~10 min warmup (600 obs) before it emits."
