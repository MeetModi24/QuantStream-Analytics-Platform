# Design Decisions (ADR-style)

## Purpose

The **why** behind QuantStream's architecture — the decisions that aren't obvious from
reading the code, the alternatives considered, and the trade-offs accepted. Written as
lightweight ADRs (Architecture Decision Records) so each can be revisited independently
if its assumptions change.

Each record: **Context → Decision → Alternatives considered → Consequences.**

---

## ADR-1: Polyglot stack — Java pipeline, Python (FastAPI) dashboard API

**Context.** The system has two very different workloads: (a) a high-throughput,
always-on streaming pipeline (generate → feature → persist → strategy), and (b) a
request/response + live-push API serving a dashboard.

**Decision.** Build the streaming pipeline in **Java 26 / Spring Boot 4.0.7** as a
multi-module Maven monorepo, and build the dashboard backend as a separate **Python
FastAPI** service.

**Alternatives considered.**
- *All-Java (Spring Boot API too).* Simplest operationally, one toolchain. Rejected
  because the API layer benefits from Python's ecosystem and FastAPI's async
  WebSocket/ASGI ergonomics, and keeping it separate enforces a clean boundary.
- *All-Python pipeline.* Rejected — the pipeline is the throughput-critical part where
  the JVM's performance, mature Kafka client, and strong typing pay off, and where the
  1→100 token scaling has to hold up.

**Consequences.**
- (+) Each side uses the right tool; the API can be developed/deployed independently.
- (+) The Kafka topics are the contract between them — clean seam, language-agnostic.
- (−) Two toolchains and two dependency ecosystems to maintain.
- (−) Domain models (order book, features, signal JSON shapes) are defined twice — once
  as Java records, once implicitly in the Python layer. Mitigated by keeping the JSON
  shapes small and stable, and treating the Kafka message as the versioned contract.

---

## ADR-2: Config-driven scaling (1 → 100 tokens is config, never code)

**Context.** Development runs with **1 token** (AAPL). Production targets ~**30,000
tokens' worth of messages** across ~100 symbols. A design that works for 1 token but
needs rewriting for 100 would be worthless.

**Decision.** The token universe lives in `common/src/main/resources/tokens.yml` as the
single source of truth. Every service builds its per-token state from
`TokenRegistry.enabledTokens()`. Scaling up is flipping `enabled: false → true` (and
tuning Kafka partitions / consumer concurrency via config). No code change.

**Alternatives considered.**
- *Hard-code the single token to move fast.* Explicitly rejected by project mandate —
  "not just a minimalistic piece working for 1 token." The scaling path has to be real
  from day one.
- *Database-backed token config.* Overkill for a fixed, curated universe; a YAML file
  under version control is simpler and reviewable.

**Consequences.**
- (+) The generator, feature-calculator, and strategy-engine all fan out over the same
  registry, so adding tokens is uniform and testable.
- (+) The dashboard derives its token list from a live query, so it picks up new tokens
  with zero change.
- (−) Some things still need capacity planning at 100 tokens (Kafka partition count,
  consumer concurrency, QuestDB write rate) — config, but not *zero-thought* config.
  Documented so it isn't forgotten.

---

## ADR-3: Kafka as the backbone, keyed by token

**Context.** Four independent pipeline stages must pass data with ordering guarantees
per instrument, and the dashboard needs a live tap into the same stream.

**Decision.** Every stage communicates over Kafka topics (`order-book-data`, `features`,
`signals`), and **every message is keyed by token**. Per-token keying means all events
for one symbol land on the same partition and are processed in order.

**Alternatives considered.**
- *Direct service-to-service calls.* Rejected — tight coupling, no buffering, no replay,
  no easy fan-out to the dashboard.
- *A shared database as the integration point.* Rejected — polling latency and write
  contention; Kafka gives push semantics and natural back-pressure.

**Consequences.**
- (+) Stages scale and restart independently; the dashboard is just another consumer.
- (+) Per-token ordering is preserved into the database.
- (−) At-least-once delivery means the write path must be idempotent (see ADR-5).

---

## ADR-4: QuestDB ingestion over PG-wire (JDBC), not the native ILP client

**Context.** QuestDB's *recommended* high-throughput write path is its ILP client. But
that client is **incompatible with Java 17+**: it formats doubles via the
package-private `jdk.internal.math.FDBigInteger`, throwing `IllegalAccessError` that no
JVM flag can fix (full write-up in `docs/engineering/01-hurdles-and-fixes.md`, #1).

**Decision.** Ingest over the **PostgreSQL wire protocol** using Spring's
`JdbcTemplate` + a HikariCP pool. Formatting happens server-side; the broken client
formatter is never touched. Requires QuestDB server **≥ 8.3.x** for PG-wire
compatibility with HikariCP's connection validation.

**Alternatives considered.**
- *ILP client + `--add-exports`/`--add-opens`.* Disproven — package-private access can't
  be granted by module flags.
- *Downgrade to Java 17 to appease the ILP client.* Rejected — regressing the whole
  stack to work around one library's bug is the wrong direction.
- *A different time-series DB.* Out of scope for now; QuestDB otherwise fits well
  (SYMBOL columns, partitioning, WAL, dedup).

**Consequences.**
- (+) Fully JDK-26 compatible; thread-safe via the pool (also solved a `Sender`
  thread-safety bug for free).
- (+) `DEDUP UPSERT KEYS` still applies — idempotency is unaffected by the path choice.
- (−) PG-wire per-row `INSERT` is slower than batched ILP. Acceptable here: ~100 tokens
  × ~1 msg/s is a few hundred tiny inserts/sec, far below QuestDB's PG-wire capacity. If
  throughput ever demands it, batch with `addBatch()`/`executeBatch()` before revisiting
  the client.

---

## ADR-5: Idempotency at the database, not the application

**Context.** Kafka delivers at-least-once; consumer restarts and redeliveries produce
duplicate messages (observed: 23 duplicate rows after a restart).

**Decision.** Make the *write* idempotent using QuestDB `DEDUP UPSERT KEYS` — `(ts,
token)` for order books and features, `(ts, token, strategy)` for signals. A
re-delivered row upserts on its key instead of duplicating.

**Alternatives considered.**
- *App-side dedup* (track seen keys). Rejected — stateful, memory-bound, and fragile
  across restarts.
- *Exactly-once Kafka semantics.* Heavier, and still wouldn't cover reprocessing after
  an offset reset. Idempotent writes are simpler and cover more cases.

**Consequences.**
- (+) Zero app-side dedup logic; robust across restarts and offset resets.
- (+) The dedup key encodes a real domain fact: two strategies may legitimately signal
  the same token at the same instant, so `strategy` must be in the signals key.
- (−) Requires the designated timestamp to be the *event* time, not ingest time (it is).

---

## ADR-6: No backfill on the live path; warmup replaces history

**Context.** Intraday microstructure strategies react to *current* order-book state.
There is no historical-data backfill step on the live path (detailed in
`docs/planning/03-historical-data-and-retention.md`).

**Decision.** Strategies build any needed state **forward from the live stream** using
in-memory rolling windows. Stateless strategies (e.g. OBI threshold) need zero history;
stateful ones (pairs, Ornstein-Uhlenbeck, VPIN) declare a **warmup period** and don't
emit signals until warm. Kafka retains only hours; QuestDB retains durably for the
dashboard/analysis path with tiered retention + partition-drop cleanup.

**Alternatives considered.**
- *Backfill windows from the database on startup.* Rejected for the live path — adds
  latency and coupling for data the stream provides within the warmup period anyway.

**Consequences.**
- (+) Simple, low-latency live path; each strategy owns its warmup contract.
- (−) After a restart, stateful strategies are silent during warmup — acceptable and
  explicit, versus the complexity of state rehydration.

---

## ADR-7: Strategy SPI — one instance per token, factory-discovered, edge-triggered

**Context.** ~30 strategies eventually, each running per token, each with its own
warmup and state. Adding a strategy should not mean touching the engine.

**Decision.** A `Strategy` SPI (`name`, `token`, `warmupObservations`, `onFeatures`)
with an `AbstractStrategy` base handling token binding, observation counting, and the
warmup gate. `StrategyFactory` beans are auto-discovered by the engine, which builds a
`Map<token, List<Strategy>>` from enabled factories × enabled tokens. Signals are
**edge-triggered** (fire on threshold *crossing*, with a neutral-band reset) to avoid
emitting a signal every tick.

**Alternatives considered.**
- *One big switch/if-chain in the engine.* Rejected — doesn't scale to 30 strategies and
  couples the engine to every strategy.
- *Level-triggered signals* (fire whenever the condition holds). Rejected — would spam a
  signal every second while OBI stays above threshold; edge-triggering with hysteresis
  matches how a trader thinks ("act on the change").

**Consequences.**
- (+) Adding a strategy is a new `Strategy` + `StrategyFactory` — no engine change.
- (+) Warmup and per-token isolation are handled once in the base class.
- (−) Per-token × per-strategy instances multiply at 100 tokens; bounded and cheap here,
  but a thing to watch as strategy count grows.

---

## ADR-8: Two data paths to the dashboard — REST for history, WebSocket for live

**Context.** A freshly opened dashboard needs recent history *and* sub-second live
updates.

**Decision.** Serve **history over REST** (async `httpx` against QuestDB's `/exec`) and
the **live tip over WebSocket** (an `aiokafka` consumer tailing `features`/`signals`,
fanned out to clients). The page paints history once on load, then switches to the
socket. The Kafka consumer uses a fresh group each start (`auto_offset_reset=latest`) so
it tails the live tip; history comes from the durable store, not stream replay.

**Alternatives considered.**
- *Poll QuestDB from the WebSocket loop.* Rejected — ~1s latency and query load that
  grows with tokens × clients.
- *Everything over WebSocket, including history.* Rejected — replaying history through
  the live socket conflates two concerns and complicates reconnection.

**Consequences.**
- (+) Clean separation: durable store owns history, Kafka owns liveness.
- (+) Per-client **bounded queues** in the broadcaster mean one slow browser tab drops
  its own messages (marked "lagging") rather than back-pressuring the whole feed.
- (−) Two code paths and a brief seam at load time (history then live) the client must
  stitch — handled in the page's load sequence.
