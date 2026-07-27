#!/usr/bin/env bash
#
# status.sh — one-glance health of the whole stack: containers, JVM services, dashboard,
# and live QuestDB row counts (so you can see data actually flowing).
#
# Usage: scripts/status.sh
#
set -uo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib.sh"

echo
info "Infra containers"
for c in "${INFRA_CONTAINERS[@]}"; do
  if is_container_up "$c"; then ok "$c"; else err "$c (down)"; fi
done

echo
info "JVM pipeline services"
for entry in "${JVM_SERVICES[@]}"; do
  IFS='|' read -r module mainclass port <<< "$entry"
  pid="$(jvm_pid "$mainclass")"
  if [ -n "$pid" ]; then ok "$(printf '%-22s pid %-7s port %s' "$module" "$pid" "$port")"; else err "$(printf '%-22s (not running)' "$module")"; fi
done

echo
info "Dashboard API"
if lsof -ti "tcp:${DASHBOARD_PORT}" >/dev/null 2>&1; then ok "listening on ${DASHBOARD_PORT}"; else err "not running (port ${DASHBOARD_PORT})"; fi

echo
info "Frontend (Vite)"
if lsof -ti "tcp:${FRONTEND_PORT}" >/dev/null 2>&1; then ok "listening on ${FRONTEND_PORT}"; else err "not running (port ${FRONTEND_PORT})"; fi

echo
info "QuestDB row counts (data flowing if these grow between runs)"
if curl -s --get "${QUESTDB_HTTP}/exec" --data-urlencode "query=SELECT 1" 2>/dev/null | grep -q '"dataset"'; then
  for t in order_book_snapshots features signals positions strategy_pnl; do
    c="$(curl -s --get "${QUESTDB_HTTP}/exec" --data-urlencode "query=SELECT count() FROM ${t}" 2>/dev/null | grep -o '"dataset":\[\[[0-9]*' | grep -o '[0-9]*$')"
    printf "  %-22s %s\n" "$t" "${c:-n/a}"
  done
else
  err "QuestDB not answering at ${QUESTDB_HTTP}"
fi
echo
