#!/usr/bin/env bash
#
# dev-clean.sh — reset accumulating DEV data to a clean slate.
#
# This is a DEVELOPMENT convenience only. It is NOT the production retention/cleanup
# system (that is partition-drops + rollups, run on a schedule — see
# docs/planning/03-historical-data-and-retention.md §5). During development the pipeline
# runs continuously at ~1 msg/sec/token and QuestDB tables grow without bound; this
# script wipes them so a local run starts fresh.
#
# Usage:
#   scripts/dev-clean.sh                # TRUNCATE all QuestDB data tables
#   scripts/dev-clean.sh --table features signals   # only these tables
#   scripts/dev-clean.sh --kafka        # also purge Kafka topics (delete + recreate)
#   scripts/dev-clean.sh --dry-run      # print what it would do, change nothing
#
# Env overrides:
#   QUESTDB_HTTP   QuestDB REST endpoint (default http://localhost:9001)
#   KAFKA_CONTAINER  docker container name for Kafka (default kafka)
#
set -euo pipefail

QUESTDB_HTTP="${QUESTDB_HTTP:-http://localhost:9001}"
KAFKA_CONTAINER="${KAFKA_CONTAINER:-kafka}"

# All QuestDB tables the pipeline writes. Order is irrelevant for TRUNCATE.
ALL_TABLES=(order_book_snapshots features signals positions strategy_pnl)
# Kafka topics the pipeline uses.
ALL_TOPICS=(order-book-data features signals positions strategy-pnl)

DRY_RUN=false
DO_KAFKA=false
TABLES=("${ALL_TABLES[@]}")

# ---- arg parsing -----------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --kafka)   DO_KAFKA=true; shift ;;
    --table)
      shift
      TABLES=()
      while [[ $# -gt 0 && "$1" != --* ]]; do TABLES+=("$1"); shift; done
      ;;
    -h|--help)
      sed -n '2,25p' "$0"; exit 0 ;;
    *) echo "Unknown arg: $1" >&2; exit 2 ;;
  esac
done

run() { if $DRY_RUN; then echo "[dry-run] $*"; else eval "$*"; fi; }

# ---- QuestDB truncation ----------------------------------------------------
# QuestDB's HTTP /exec runs one statement per GET. TRUNCATE keeps the table (and its
# schema + DEDUP keys) but drops all rows — cheaper and safer than DROP for a reset.
echo "==> Truncating QuestDB tables at ${QUESTDB_HTTP}"
for t in "${TABLES[@]}"; do
  q="TRUNCATE TABLE ${t}"
  if $DRY_RUN; then
    echo "[dry-run] GET /exec  ${q}"
  else
    # --data-urlencode with --get issues a GET (QuestDB rejects POST on /exec).
    resp="$(curl -s --get "${QUESTDB_HTTP}/exec" --data-urlencode "query=${q}" || true)"
    if echo "$resp" | grep -q '"ddl":"OK"'; then
      echo "  ok: ${t}"
    elif echo "$resp" | grep -qi 'does not exist\|table does not exist'; then
      echo "  skip (absent): ${t}"
    else
      echo "  WARN: ${t} -> ${resp}"
    fi
  fi
done

# ---- Kafka purge (optional) ------------------------------------------------
# Deleting + recreating a topic is the simplest way to drop all buffered messages in
# dev. Consumers reconnect and resume from the new (empty) topic. Harmless if a topic
# is auto-created on first produce.
if $DO_KAFKA; then
  echo "==> Purging Kafka topics via container '${KAFKA_CONTAINER}'"
  for topic in "${ALL_TOPICS[@]}"; do
    run "docker exec ${KAFKA_CONTAINER} kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic ${topic} --if-exists"
    echo "  deleted: ${topic} (auto-recreates on next produce)"
  done
fi

echo "==> Done."
$DRY_RUN && echo "(dry run — nothing was changed)"
exit 0
