# Engineering Hurdles and Fixes

## Purpose

This is a running log of the *real* problems hit while building QuantStream, written so
each one can be **talked through in an interview** — the kind of "tell me about a hard
bug you debugged" or "what hurdles did you face?" question.

Each entry follows the same shape:

- **Symptom** — what I actually saw (the error, the wrong behaviour).
- **Investigation** — how I narrowed it down (this is the part interviewers care about).
- **Root cause** — the real reason, stated precisely.
- **Fix** — what I changed and *why that* and not a workaround.
- **Takeaway** — the transferable lesson / the one-liner to lead with.

The entries are ordered roughly by how interesting they are to discuss, not
chronologically. The headline war story is #1.

---

## 1. QuestDB ILP client crashes on Java 26 with `IllegalAccessError` — and no JVM flag fixes it

**This is the strongest story to lead with: a misdiagnosis I caught, a wrong fix I
disproved with evidence, and a correct fix grounded in how the JVM module system
actually works.**

### Symptom

The `database-writer` service, consuming from Kafka and writing to QuestDB via the
recommended ILP (InfluxDB Line Protocol) client, crashed intermittently:

```
java.lang.IllegalAccessError: failed to access class jdk.internal.math.FDBigInteger
from class io.questdb.std.Numbers (jdk.internal.math.FDBigInteger is in module
java.base of loader 'bootstrap'; io.questdb.std.Numbers is in unnamed module of loader 'app')
    at io.questdb.std.Numbers.appendDouble0(Numbers.java:1906)
    at io.questdb.cutlass.line.http.LineHttpSender.doubleColumn(LineHttpSender.java:180)
    at com.quantstream.dbwriter.questdb.QuestDbWriter.writeFeatures(QuestDbWriter.java:51)
```

The maddening part: **order-book rows persisted fine (700+), but feature rows crashed
the container.** Same code path, different data.

### Investigation

1. **First hypothesis (wrong, but reasonable):** "It's the JVM module system hiding a
   JDK-internal class. I'll export it." I added
   `--add-exports java.base/jdk.internal.math=ALL-UNNAMED` to both the Spring Boot run
   plugin and the packaged jar manifest.
2. **It still crashed.** I confirmed via `ps aux` that the flag was actually on the
   running process — so this wasn't a "flag didn't apply" problem. The flag was live
   and the error persisted. That's the moment the easy explanation died.
3. **Why order books survive but features don't?** The clue was in the *data*. Order
   book prices were mostly clean values; the feature OBI was a full-precision double
   like `0.11510235612205484`. The stack trace pointed at `appendDouble0` — a **slow
   path** in double-to-string formatting (Java's shortest-round-trip algorithm) that is
   only taken for values needing full precision.
4. **The decisive check.** I ran `javap -p jdk.internal.math.FDBigInteger` and got:
   ```
   final class jdk.internal.math.FDBigInteger { ... }
   ```
   **No `public`.** It's a *package-private* class. Then `javap -p -c` on the QuestDB
   jar confirmed `io.questdb.std.Numbers` calls it directly via `invokestatic` /
   `invokevirtual` bytecode.

### Root cause

`--add-exports` only makes the **public** types of a package accessible across modules.
It does **nothing** for a package-private class — Java's package-private visibility rule
is orthogonal to the module system and cannot be relaxed by any launcher flag
(`--add-opens` only affects *reflection*, not this direct bytecode access). QuestDB
8.x's ILP client compiles against the JDK's own `FDBigInteger`, which stopped being
cross-package-accessible on modern JDKs. So on Java 17+ the ILP double-formatting slow
path is **fundamentally broken and unfixable from the outside**. Upgrading the client
(8.1.2 → 8.3.2, the latest) did not change it.

### Fix

Abandon the ILP client entirely and ingest over the **PostgreSQL wire protocol** (JDBC)
instead — the same protocol I was already using for schema DDL. Double formatting then
happens **server-side** inside QuestDB, so the broken client-side formatter is never
touched. Concretely: `spring-boot-starter-jdbc` + a HikariCP `DataSource` +
`JdbcTemplate` with parameterized `INSERT`s. Throughput is a non-issue (100 tokens ×
~1 msg/s is a few hundred tiny inserts/sec, trivial for QuestDB PG-wire), and QuestDB's
table-level `DEDUP UPSERT KEYS` still gives idempotency on this path.

### Takeaway

> "The JDK module system's `--add-exports` exposes *public* members of a package — it
> cannot grant access to a package-private class. I proved that with `javap`, which is
> why I stopped fighting the flag and changed the ingestion path instead. The bug was
> also a good reminder that an *intermittent* crash that correlates with data values is
> almost always a code path being taken conditionally — here, the full-precision
> double-formatting slow path."

---

## 2. QuestDB `Sender` shared across threads — `duplicated table` errors

### Symptom

```
io.questdb.cutlass.line.LineSenderException: duplicated table.
call sender.at() or sender.atNow() to finish the current row first
```

Appeared once the pipeline ran all topics at once.

### Investigation

The single `Sender` bean was injected into one writer, called from **three**
`@KafkaListener` methods (order books, features, signals). Kafka runs each listener on
its own container thread. The error message — "finish the current row first" — is the
tell: two threads were interleaving row-building on one shared buffer.

### Root cause

A QuestDB `Sender` builds one row at a time in an internal, **non-thread-safe** buffer.
Concurrent `table()` calls from different threads corrupt that buffer.

### Fix

Initially I moved to **one `Sender` per thread** via a `ThreadLocal` (tracking created
senders so they flush/close on shutdown) — the right shape for a non-thread-safe,
per-thread resource. That fix became moot once hurdle #1 forced the switch to PG-wire:
`JdbcTemplate` + HikariCP is inherently thread-safe (each call borrows a pooled
connection), so the whole class of problem disappeared. Two bugs, one change.

### Takeaway

> "Shared mutable I/O clients and multi-threaded listeners don't mix. The clean options
> are: make it thread-local, pool it, or confine it to one thread. A connection pool
> like HikariCP gives you that safety for free — which is one more reason the PG-wire
> path was the better design, not just the working one."

---

## 3. HikariCP can't connect to QuestDB 8.1.2 over PG-wire — "No results were returned"

### Symptom

Right after switching to JDBC, the service failed to *start*:

```
CannotGetJdbcConnectionException: Failed to obtain JDBC Connection
Caused by: org.postgresql.util.PSQLException: No results were returned by the query.
    at org.postgresql.jdbc.PgConnection.getTransactionIsolation(...)
    at com.zaxxer.hikari.pool.PoolBase.checkDefaultIsolation(...)
```

Confusingly, my earlier **raw `DriverManager`** probe against the same QuestDB had
worked perfectly.

### Investigation

The stack trace named the exact call: HikariCP validates each new connection by calling
`getTransactionIsolation()`, which the Postgres driver implements as
`SHOW TRANSACTION ISOLATION LEVEL`. My raw probe worked because it *skipped* that
validation step. So the difference wasn't JDBC vs. ILP — it was Hikari's connection
setup probing a command the server didn't answer with a result set. I checked the
running image: `questdb/questdb:8.1.2`.

### Root cause

QuestDB 8.1.2's PG-wire implementation didn't return a proper result set for that
`SHOW` command, so the driver threw. Newer QuestDB builds fixed their PG-wire
compatibility.

### Fix

Bump the **server** image `8.1.2 → 8.3.2` (which also aligned it with the 8.3.2 client
already on the classpath). The named Docker volume meant existing data survived the
recreate. Also set `sslmode=disable` in the Hikari data-source properties since QuestDB
doesn't negotiate PG SSL.

### Takeaway

> "When a pooled `DataSource` fails but a raw connection works, the difference is almost
> always the pool's *connection validation* step. HikariCP probes isolation level and
> validity on checkout; here the DB didn't answer one probe. Reading the stack trace to
> the exact driver call — `getTransactionIsolation` — is what pointed straight at the
> server version."

---

## 4. `CREATE TABLE IF NOT EXISTS` silently kept a stale schema — signals never persisted

### Symptom

After everything else worked, features and order books flowed, but `signals` was stuck
at 9 old rows. Querying `SELECT ts ... FROM signals` returned `Invalid column: ts`.

### Investigation

`SHOW COLUMNS FROM signals` revealed columns from an *earlier experiment*
(`symbol`, `strategy_name`, `timestamp`) — not the current schema's `ts`. My
`INSERT ... (…, ts)` failed every time; Kafka retried to exhaustion (`Backoff …
exhausted`) and never committed the offset. `CREATE TABLE IF NOT EXISTS` had seen a
table already named `signals` and skipped creation, so the schema never updated.

### Root cause

`CREATE TABLE IF NOT EXISTS` is a *create-or-skip*, not a *migrate*. A leftover table
with a drifted schema is silently honoured.

### Fix

`DROP TABLE signals` and let `SchemaInitializer` recreate it with the correct DDL on the
next start. The failed (uncommitted) Kafka offsets then replayed and persisted cleanly.

### Takeaway

> "`IF NOT EXISTS` protects you from duplicate-create errors but hides schema drift.
> In a real system you'd use versioned migrations (Flyway/Liquibase) rather than
> idempotent `CREATE`; the incident is a concrete argument for that. Also: a
> silently-failing writer looks identical to 'no data' from the outside — the fix was to
> read the *writer's* logs, not just query the table."

---

## 5. Kafka `NodeExistsException` on startup — stale broker registration

### Symptom

Kafka refused to start cleanly, complaining a broker node already existed in Zookeeper.

### Investigation

Left over from a previous unclean shutdown: the broker's ephemeral registration was
still in Zookeeper, and Zookeeper had no persistent volume in the compose file.

### Root cause

Stale `/brokers/ids` registration in Zookeeper from a prior run.

### Fix

`docker compose down` + `up -d` to recreate cleanly (Zookeeper state is ephemeral here,
so this is safe in dev).

### Takeaway

> "Ephemeral coordination state (Zookeeper znodes) can outlive an unclean shutdown. In
> dev the fix is a clean recreate; in prod you'd rely on proper broker
> deregistration / session timeouts."

---

## 6. Duplicate rows in QuestDB — at-least-once delivery meets restarts

### Symptom

23 duplicate feature rows appeared after restarting the consumer.

### Investigation

Kafka is at-least-once by default; on restart with an early offset the consumer
re-processed messages, and each re-processed message became a new row. `count()` >
`count_distinct(ts)` confirmed dupes.

### Root cause

At-least-once delivery + naive insert = duplicates. The write path had no idempotency.

### Fix

Add `DEDUP UPSERT KEYS(ts, token)` (and `(ts, token, strategy)` for signals) to the
QuestDB DDL. QuestDB then upserts on the key instead of inserting a duplicate —
idempotency handled by the store, with zero app-side dedup logic. Verified with
`count() == count_distinct(ts)`.

### Takeaway

> "You don't fight at-least-once delivery by trying to make Kafka exactly-once; you make
> the *write idempotent*. QuestDB's `DEDUP UPSERT KEYS` pushes that into the database,
> which is simpler and more robust than app-side dedup. Choosing the dedup key
> (including `strategy` for signals, because two strategies can legitimately fire on the
> same token at the same instant) was the real design thought."

---

## 7. Fixed-unit lot sizing made cross-strategy PnL meaningless

**A subtler class of bug than the others: the code was correct, the *model* was wrong.
Nothing threw. The numbers just quietly stopped meaning anything — the kind of defect a
compiler and a passing pipeline will never catch for you.**

### Symptom

The Signal Aggregator's paper-trading portfolio persisted fine, but the per-strategy
rollup was absurd: `total_pnl` swinging between **-$121,008 and -$179,349** for a
strategy whose individual token positions showed unrealized PnL of only **±$15** and a
net position of **-100**. A -100-share position marked ±$15 cannot roll up to -$120k.

### Investigation

1. My first instinct was "the rollup is double-counting" — but reading
   `snapshotStrategyPnl` showed a plain sum over positions, no multiplication bug.
2. So I stopped trusting the aggregate and queried the *per-token* positions directly
   (`LATEST ON ts PARTITION BY token`). That was the decisive move:
   ```
   BTC : net=-100  avg_entry=58447   unrealized=-80307
   JPM : net=-200  avg_entry=196.65  unrealized=-120
   ```
3. The arithmetic reconciled *exactly*: `-100 × (mark − 58447) ≈ -80307`. The engine
   was right. A -100 **unit** BTC position is **$5.8M of notional**; a normal ~1% BTC
   move is a ~$58k PnL swing. JPM's -200 units is ~$40k notional — three orders of
   magnitude smaller.

### Root cause

`lot-size: 100` was applied as a flat **unit count** across every token. So one fill
meant $5.8M of BTC but $20k of JPM. A strategy's PnL was therefore dominated by *which
high-priced tokens it happened to trade*, not by whether its signals were any good —
which defeats the entire purpose of a strategy monitor that compares strategies.

### Fix

Size fills by **fixed notional**, not fixed units: `units = notional-per-fill / price`
(e.g. $10k per fill). BTC and JPM then take comparable dollar risk, and per-strategy PnL
becomes comparable across tokens. After the change, the same strategy's total settled at
a believable **-$181** mark-to-market instead of -$120k. The change stayed fully
config-driven (`lot-size: 100` → `notional-per-fill: 10000`), consistent with the
project's 1→100-token "config, not code" scaling rule — no accounting logic changed.

### Takeaway

> "The engine was correct; the *sizing model* was wrong, and no test or type-checker
> would ever tell me that — only sanity-checking the persisted numbers did. A -100-share
> position can't produce a -$120k swing, and noticing that impossibility is what led me
> to per-token notional. The lesson I lead with: **when an aggregate looks insane, drop
> to the per-entity rows and check the arithmetic reconciles — then ask whether the model
> the correct arithmetic encodes is the one you actually want.** Fixed-unit sizing across
> assets that differ 300× in price is a classic quant-modelling trap."

---

## 8. Realized PnL and win-rate sat at 0 — a real state, not a bug

### Symptom

Right after the notional-sizing fix, the first `strategy_pnl` snapshots showed
`realized_pnl = 0` and `win_rate = 0`, even though unrealized PnL was moving. Suspicious,
because the *previous* run had shown a large nonzero realized PnL.

### Investigation

I checked the signal stream first — both `BUY` (75) and `SELL` (72) were firing, so it
wasn't a one-sided stream that could never close a position. Then I re-read
`realizeAndRecord`: realized PnL and the win/loss counters only move on a fill that
**reduces, closes, or flips** a position — never on one that opens or accumulates. The
run was only ~40 seconds old (I had truncated the tables and restarted). Letting it run
another minute, realized PnL moved to **-$59.35** and win-rate to **0.667** (2 of 3
closed trades won).

### Root cause

Not a bug at all — a legitimate transient. A freshly-started paper portfolio spends its
first fills *opening* positions; there is simply nothing to realize until a later signal
crosses to the opposite side. `0 / 0` was the correct state of a young portfolio.

### Fix

None needed. Worth noting the metric is still not *meaningful* at 3 closed trades —
win-rate needs dozens of closes before it says anything, which is a caveat to surface on
the dashboard rather than a code change.

### Takeaway

> "Before 'fixing' a zero, I asked whether zero was the *correct* answer given how little
> time had elapsed — and it was. Realized PnL is an event-driven quantity: it only exists
> once a position is reduced. The reflex to distrust a suspicious number is good; the
> discipline is to prove it's wrong (or right) with the data before touching code. Here
> the fix was patience and a re-query, not a patch."

---

## 9. Smaller ones worth a sentence each

- **Spring Boot 4 `KafkaTemplate` bean not found.** Boot 4's autoconfig didn't hand me a
  ready `KafkaTemplate` the way I expected; I defined explicit `ProducerFactory` +
  `KafkaTemplate` beans. *Lesson: when autoconfig assumptions break across a major
  version, fall back to explicit bean definitions.*
- **Maven multi-module resolution** (`Could not find artifact com.quantstream:common`).
  Running a single module needs its dependencies installed first: `mvn -N install` for
  the parent, then `mvn -pl common install`. *Lesson: reactor order matters when you
  build one module in isolation.*
- **Double `next()` call in the generator.** Logging called `simulator.next()` a second
  time, silently advancing the stochastic simulation an extra step per tick. Reused the
  existing snapshot instead. *Lesson: side-effecting methods in log statements are a
  classic subtle bug.*
- **XML comment with `--` broke the POM.** `--add-exports` inside an XML comment is
  illegal (`--` can't appear in XML comments). *Lesson: even comments have syntax.*

---

## How to use this in an interview

- Lead with **#1** — it has a wrong turn, evidence-driven correction, and a precise
  root cause about the JVM. That arc (hypothesis → disproof → real cause) is exactly
  what interviewers score.
- Keep **#4** and **#6** in your pocket for "tell me about a data-integrity bug."
- Use **#3** for "a bug that only appeared in one environment / configuration."
- Use **#7** for "tell me about a bug that wasn't a crash" or "a domain/modelling
  mistake" — it shows you reason about whether correct code encodes the *right* model,
  not just whether it runs. Pairs well with quant/fintech interviews.
- Use **#8** for "a time you *didn't* jump to a fix" — distrusting a suspicious `0`, then
  proving it was the correct transient state before touching code, is a maturity signal.
- The meta-point across all of them: **read the stack trace to the exact call, and let
  the data tell you which code path is running — and when there's no crash at all, drop
  to the per-entity rows and check the arithmetic reconciles.** Almost every fix here
  came from one decisive observation, not from guessing.
