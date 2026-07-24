#!/usr/bin/env bash
#
# stop.sh — stop QuantStream services cleanly, in reverse dependency order.
#
# By default stops the app tier (dashboard + JVM services) but LEAVES the infra
# containers (Kafka/QuestDB) running, since QuestDB holds your data and Kafka restarts
# are the slow part. Use --all to also stop containers, or --down to `docker compose down`
# (removes containers but keeps the named questdb-data volume, so data survives).
#
# Usage:
#   scripts/stop.sh            # stop dashboard + JVM services (keep containers)
#   scripts/stop.sh --all      # also `docker compose stop`
#   scripts/stop.sh --down     # also `docker compose down` (removes containers, keeps volume)
#
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

STOP_INFRA=false; COMPOSE_DOWN=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --all)  STOP_INFRA=true; shift ;;
    --down) STOP_INFRA=true; COMPOSE_DOWN=true; shift ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

cd "$REPO_ROOT"

# ---- Dashboard -------------------------------------------------------------
info "Stopping dashboard API"
if [ -f "${PID_DIR}/dashboard-api.pid" ] && kill "$(cat "${PID_DIR}/dashboard-api.pid")" 2>/dev/null; then
  ok "dashboard stopped"; rm -f "${PID_DIR}/dashboard-api.pid"
else
  # Fallback: uvicorn on the dashboard port.
  if pids="$(lsof -ti "tcp:${DASHBOARD_PORT}" 2>/dev/null)"; then kill $pids 2>/dev/null && ok "dashboard stopped (by port)"; else warn "dashboard not running"; fi
fi

# ---- JVM services (reverse order) ------------------------------------------
info "Stopping JVM pipeline services"
for (( idx=${#JVM_SERVICES[@]}-1 ; idx>=0 ; idx-- )); do
  IFS='|' read -r module mainclass port <<< "${JVM_SERVICES[$idx]}"
  pid="$(jvm_pid "$mainclass")"
  if [ -n "$pid" ]; then
    kill "$pid" 2>/dev/null || true
    ok "${module} stopped (pid ${pid})"
  else
    warn "${module} not running"
  fi
  rm -f "${PID_DIR}/${module}.pid"
done

# Reap any Maven spring-boot:run wrappers that outlive their child.
pkill -f 'spring-boot:run' 2>/dev/null || true

# ---- Infra -----------------------------------------------------------------
if $COMPOSE_DOWN; then
  info "docker compose down (containers removed, questdb-data volume kept)"
  docker compose down
  ok "containers removed"
elif $STOP_INFRA; then
  info "docker compose stop (containers stopped, state kept)"
  docker compose stop
  ok "containers stopped"
else
  warn "Infra containers left running (use --all or --down to stop them)"
fi

info "Done."
