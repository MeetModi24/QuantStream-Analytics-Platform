# QuantStream Dev Scripts

One-command lifecycle for the whole stack, so a fresh clone (or a machine restart) comes
up the same way every time — no remembering start order, ports, or the gotchas we hit
during development.

## TL;DR

```bash
scripts/start.sh      # bring up everything (infra + pipeline + dashboard)
scripts/status.sh     # is it all healthy? are rows growing?
scripts/stop.sh       # stop the app tier (keep Kafka/QuestDB + data)
scripts/dev-clean.sh  # wipe accumulated QuestDB data (dev only)
```

## What starts, and in what order

The order is deliberate — it encodes the failures we actually debugged (see
`docs/engineering/01-hurdles-and-fixes.md`):

1. **Infra containers** (`docker compose`): Zookeeper → Kafka → QuestDB → Kafka UI.
   Each is *waited on until truly ready* — QuestDB in particular is probed with a real
   `SELECT 1`, because HikariCP throws if it connects before the SQL engine is up (that
   was hurdle #3).
2. **`mvn install`** of the whole reactor, so `common` is in the local repo. Launching a
   single module before this fails with *"Could not find artifact com.quantstream:common"*.
3. **JVM pipeline services** in stream order: `order-book-generator` → `feature-calculator`
   → `strategy-engine` → `database-writer` → `signal-aggregator`.
4. **Dashboard API** (FastAPI via `uv`) last — it only reads QuestDB and tails Kafka.

| Service | Port | Kind |
|---------|------|------|
| order-book-generator | 8081 | Spring Boot |
| database-writer | 8082 | Spring Boot |
| feature-calculator | 8083 | Spring Boot |
| strategy-engine | 8084 | Spring Boot |
| signal-aggregator | 8085 | Spring Boot |
| dashboard-api | 8000 | FastAPI/uv |
| QuestDB web console | 9001 | container |
| QuestDB PG-wire | 8812 | container |
| Kafka | 9092 | container |
| Kafka UI | 8080 | container |

## Scripts

### `start.sh`
Idempotent — already-running containers/services are detected and skipped, so re-running
it never double-starts anything. Flags:
- `--no-build` — skip `mvn install` (fast restart when code is unchanged)
- `--no-dashboard` — pipeline only
- `--infra-only` — just containers + readiness waits

Logs go to `logs/<service>.log`; PIDs to `logs/pids/`.

### `stop.sh`
Stops in reverse order. By default **leaves containers running** (QuestDB holds your
data; Kafka is slow to restart). Flags:
- `--all` — also `docker compose stop`
- `--down` — also `docker compose down` (removes containers, **keeps** the `questdb-data`
  volume, so data survives)

### `status.sh`
One-glance health: containers, JVM services (pid + port), dashboard, and live QuestDB row
counts. Run it twice a few seconds apart — growing counts mean data is flowing.

### `dev-clean.sh`
**Development-only** data reset. `TRUNCATE`s the QuestDB tables (keeps schema + DEDUP
keys, so the running writers keep inserting with no restart needed). This is *not* the
production retention system (partition-drops + rollups — see
`docs/planning/03-historical-data-and-retention.md §5`). Flags: `--dry-run`, `--kafka`
(also purge topics), `--table <names...>`.

## Common flows

```bash
# First time / after machine reboot
scripts/start.sh

# Restart just the app after a code change (containers stay up)
scripts/stop.sh && scripts/start.sh

# Fast app restart, no rebuild
scripts/stop.sh && scripts/start.sh --no-build

# Reset accumulated dev data without stopping anything
scripts/dev-clean.sh

# Full teardown (keeps QuestDB data volume)
scripts/stop.sh --down
```

## Notes / expected behaviour

- **O-U warmup:** the Ornstein-Uhlenbeck strategy emits nothing for its first ~600
  observations (~10 min). A gap in its signals right after start is expected, not a bug —
  see `docs/concepts/02-signal-confidence-and-design-notes.md §3`.
- **Requirements:** Docker + `docker compose`, JDK 26, Maven, and `uv` (for the
  dashboard). `jps`, `curl`, and `lsof` are used by the scripts.
- **Topology lives in `lib.sh`** — the container list and the `module|MainClass|port`
  table are defined once there, so `start`/`stop`/`status` never disagree. Add a new
  service by editing that one array.
