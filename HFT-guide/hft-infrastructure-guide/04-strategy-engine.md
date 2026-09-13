# Module 04 — The strategy engine: knowhow + design problems

The feed handler (Module 02) gave you a trustworthy market-data stream; the order book (Module 03)
turned it into a live picture of the market. The **strategy engine** is the brain that reads that
picture and *decides what to trade*. This module is built in four parts:

1. **Knowhow** — what a strategy engine is, what it's for, its core responsibilities and hard
   constraints. Read this until it's second nature; every design decision later traces back to it.
2. **Problem 1 (LLD)** — a market-making quoter: the canonical "decide orders from book updates"
   problem, worked from brute force to HFT-optimized *the way you'd reason in an interview*.
3. **Problem 2 (LLD)** — the rolling-signal sub-problem ("be as efficient as possible in update and
   access"), the direct analog of a top-K / sliding-window question.
4. **Problem 3 (HLD)** — architecting one engine to run many strategies over many instruments, live
   *and* in backtest, with identical code.

> The pattern mirrors Module 03: **understand the component, state the problem, build the obvious
> correct version, then earn the fast version** — never drop the optimized answer cold. In an
> interview the *reasoning path* is the score, not the final data structure.

---

# Part 1 — What the strategy engine is

## 1.1 Where it sits and what flows through it

```
   feed handler ─▶ order book ─▶ ┌──────────────────┐ ─▶ risk ─▶ OMS ─▶ exchange
     (Module 02)   (Module 03)   │  STRATEGY ENGINE │
                                 │   (Module 04)    │ ◀── fills / exec reports (from OMS)
   market data in  ───────────▶  └──────────────────┘  ── order intents out (new/modify/cancel)
```

The strategy engine is a **function of events**. Its inputs are:

- **Market-data events** — book updates (top-of-book or full depth changed), trades (a print
  happened), from the book/feed handler.
- **Its own execution events** — fills, partial fills, acks, rejects, cancels — coming back from the
  OMS. The strategy *must* consume these: your decisions depend on what you actually have working and
  what position you hold.
- **Timer events** — "it's been 5 ms, re-evaluate" — driven by *event time*, not wall-clock (see
  §1.4).

Its outputs are **order intents**: NEW, MODIFY (amend), CANCEL. It does **not** talk to the exchange
directly — intents flow through the pre-trade **risk** gate and the **OMS** (Module 05). The strategy
proposes; risk disposes.

## 1.2 Core responsibilities

A strategy engine does six things, every one on the hot path:

1. **Consume & dispatch events** — a tight event loop that routes each incoming event to the right
   handler (`onBookUpdate`, `onTrade`, `onFill`, `onTimer`).
2. **Maintain signal state** — derive features from the market incrementally: microprice, order-book
   imbalance, short-term momentum, rolling volatility, spread. Incrementally, because recomputing from
   scratch each tick is too slow (Problem 2).
3. **Decide target orders** — given signals + current position + risk limits, compute *what orders it
   wants to have live right now* (e.g. "a bid for 100 @ 20050 and an ask for 100 @ 20060").
4. **Reconcile target vs live (order management)** — diff what it *wants* against what it *has* and
   emit the minimal set of NEW/MODIFY/CANCEL to close the gap. This is where latency, order-to-trade
   ratio, and queue priority are won or lost.
5. **Track position & PnL** — update inventory from fills; PnL feeds risk and inventory skew.
6. **Stay within its own guardrails** — soft limits (max position, don't cross, throttle) *before*
   the hard risk gate, because the cheapest rejected order is the one you never sent.

## 1.3 The interface (the contract you design to)

Almost every real engine expresses strategies against a small callback interface plus a handle to
send orders. This boundary is the most important design decision in the whole component (see Problem
3):

```cpp
struct OrderRouter {                        // the strategy's ONLY way to act on the world
    virtual OrderId send(Side, Price, Qty) = 0;
    virtual void    modify(OrderId, Price, Qty) = 0;
    virtual void    cancel(OrderId) = 0;
};

struct Strategy {                           // the engine calls these; the strategy never blocks
    virtual void onBookUpdate(const BookView&, Timestamp) = 0;
    virtual void onTrade(const Trade&, Timestamp) = 0;
    virtual void onFill(const Fill&, Timestamp) = 0;
    virtual void onTimer(Timestamp) = 0;
};
```

Why it matters: if the strategy only ever *reads* a `BookView` and *acts* through an `OrderRouter`,
then you can plug a **live** router (→ risk → OMS → exchange) or a **backtest** router (→ simulated
fills) behind the same interface, and run the *identical strategy binary* in both. That parity is the
difference between a toy and a real system.

## 1.4 The hard constraints (non-functionals that shape everything)

- **Determinism.** Same event sequence → same order intents, bit-for-bit. This is non-negotiable: it's
  what makes backtests trustworthy and production bugs reproducible. Killers of determinism:
  reading the **wall clock** in logic (use the event's timestamp), iterating an **unordered_map** in
  an order that affects decisions, uninitialized memory, unseeded randomness, floating-point
  non-associativity across runs. Bake determinism in from line one.
- **Single-threaded hot path.** One strategy shard runs on one pinned core. No locks on the decision
  path. Concurrency is achieved by *sharding instruments across cores*, not by sharing a strategy's
  state across threads.
- **Bounded, allocation-free work per event.** Tick-to-decision is a few hundred nanoseconds. No
  `new`, no syscalls, no unbounded loops in `onBookUpdate`. Heavy/slow work (logging, recalibration)
  goes off the hot path.
- **React, don't poll.** The engine is event-driven; it wakes on data, does O(1)-ish work, emits
  intents, and goes quiet.

## 1.5 What it is *not*

It's not the alpha research (that's offline). It's not risk (that's a separate gate — though the
strategy is risk-*aware*). It's not the OMS (it doesn't own exchange sessions or order state
machines). Keeping these boundaries clean is exactly what the HLD problem tests.

---

# Part 2 — Problem 1 (LLD): the market-making quoter

## 2.1 Problem statement

> You receive a stream of **top-of-book (L1) updates** for a single instrument. Each update carries
> `bidPrice, bidQty, askPrice, askQty` as integer ticks. You also receive your own **fills**
> (`orderId, side, qty, price`).
>
> Implement a **market-making strategy** that continuously keeps a two-sided quote in the market:
> - Compute a **fair value** from the book (start with the microprice).
> - Target a **bid** at `fair − halfSpread` and an **ask** at `fair + halfSpread`, each for size `Q`.
> - **Skew by inventory**: when long, shade both quotes down (lean to sell); when short, shade up.
> - **Constraints**: never quote at/through the opposite side; respect a max absolute position `P`;
>   and **minimize order messages** (don't churn quotes on every tiny tick — venues penalize a high
>   order-to-trade ratio).
>
> On each event, output the **list of order actions** (NEW / MODIFY / CANCEL) needed to move your live
> quotes to target. Be as efficient as possible per event.

This single problem exercises signal computation, state management, the target-vs-live diff,
inventory/risk, OTR, and determinism — the whole component in miniature.

## 2.2 How to think about it (before writing code)

Clarifying questions you'd ask (and that score points):

- **What's "fair"?** Microprice `(bidP·askQ + askP·bidQ)/(bidQ+askQ)` leans toward the heavier side —
  a better predictor than the mid. Confirm the definition; it's a config knob.
- **Modify vs cancel+new?** Does the venue support in-place **amend**, and does an amend that changes
  price lose queue priority? (On most venues a price change loses priority regardless — so the choice
  is about *message count*, not priority. A quantity *decrease* often keeps priority.)
- **What triggers a re-quote?** Every L1 change? Only when the target moves by ≥ some threshold?
  This is the OTR lever.
- **Integer or float prices?** Integer ticks — always (Module 03 §9). Fair value in fixed-point.
- **What's the latency budget and expected update rate?** Sets how aggressive the optimization must be.

Then state the shape of the answer: "It's O(1) per event — compute fair, derive two target quotes,
diff against my two live quotes, emit at most a couple of messages. There's no container to speak of
for single-level MM; the interesting parts are the *diff logic*, the *inventory skew*, and the *OTR
threshold*."

## 2.3 Brute force (correct first, latency-agnostic)

Get the semantics right with the obvious structures — a map of live orders, floating-point math,
cancel-everything-and-re-quote each tick:

```cpp
class NaiveQuoter {
    std::map<OrderId, LiveOrder> live_;   // all my working orders
    int    position_ = 0;
    OrderRouter& r_;
    const int Q = 100, halfSpread = 5, maxPos = 500;
public:
    void onBookUpdate(const BookView& b, Timestamp) {
        double fair = (double(b.bidPrice)*b.askQty + double(b.askPrice)*b.bidQty)
                      / (b.bidQty + b.askQty);            // microprice (float — a smell)
        long targetBid = std::lround(fair) - halfSpread;
        long targetAsk = std::lround(fair) + halfSpread;

        // cancel-all, then re-quote from scratch
        for (auto& [id, o] : live_) r_.cancel(id);        // ← two CANCELs every tick
        live_.clear();
        if (position_ <  maxPos) live_[r_.send(Side::Buy,  Price{targetBid}, Qty{Q})] = {};
        if (position_ > -maxPos) live_[r_.send(Side::Sell, Price{targetAsk}, Qty{Q})] = {};
    }
    void onFill(const Fill& f, Timestamp) {
        position_ += (f.side == Side::Buy ? +int(f.qty.v) : -int(f.qty.v));
    }
};
```

It's **correct and easy to reason about** — and that's its whole value: it's your test oracle. But
critique it out loud, because the interviewer wants the critique:

- **OTR catastrophe.** Two cancels + two news on *every* L1 tick. At thousands of ticks/second you
  blow the venue's order-to-trade ratio and get throttled or fined. This alone disqualifies it for
  production.
- **Throws away queue priority** needlessly: even when the target price didn't change, you cancel and
  resend, going to the back of the queue.
- **`double` fair value** → non-determinism across builds/orders and `partial_ordering` hazards.
- **`std::map` + per-tick clear** → allocation and pointer-chasing on the hot path.
- **No skew, no cross-guard yet** — semantics incomplete.

## 2.4 Optimized (the HFT version)

Reason to the fixes one constraint at a time:

**(a) There is no container.** Single-level MM has exactly two live quotes. Model them as two fixed
slots — no `map`, no allocation:

```cpp
struct Quote { OrderId id{}; long price = 0; int qty = 0; bool live = false; };

class Quoter {
    Quote bid_, ask_;
    int   position_ = 0;
    OrderRouter& r_;
    static constexpr int Q = 100, halfSpread = 5, maxPos = 500;
    static constexpr int reqoteTicks = 1;     // OTR threshold: only move if target shifts ≥ this
```

**(b) Integer, deterministic fair value.** Microprice in fixed-point (scale by a power of two or ten),
no float:

```cpp
    static long microprice(const BookView& b) {
        // (bidP*askQ + askP*bidQ) / (bidQ + askQ), integer division — deterministic
        long num = long(b.bidPrice)*b.askQty + long(b.askPrice)*b.bidQty;
        return num / (b.bidQty + b.askQty);
    }
```

**(c) Inventory skew + max-position, in integers.** Shade quotes against inventory; stop quoting the
side that would breach `maxPos`:

```cpp
    void onBookUpdate(const BookView& b, Timestamp) {
        long fair  = microprice(b);
        long skew  = position_ / (maxPos / halfSpread + 1);   // long → shade down, short → shade up
        long tBid  = fair - halfSpread - skew;
        long tAsk  = fair + halfSpread - skew;

        // (d) never quote through the opposite best (no accidental aggressive cross)
        tBid = std::min(tBid, long(b.askPrice) - 1);
        tAsk = std::max(tAsk, long(b.bidPrice) + 1);

        reconcile(bid_, Side::Buy,  tBid, position_ <  maxPos);   // (e) diff, don't churn
        reconcile(ask_, Side::Sell, tAsk, position_ > -maxPos);
    }
```

**(e) The diff — this is the crux.** Only send a message when the target actually moved past the
threshold, and prefer a single MODIFY over cancel+new:

```cpp
    void reconcile(Quote& q, Side side, long target, bool wantLive) {
        if (!wantLive) { if (q.live) { r_.cancel(q.id); q.live = false; } return; }
        if (!q.live)                         { q.id = r_.send(side, Price{target}, Qty{Q});
                                               q.price = target; q.live = true; return; }
        if (std::abs(target - q.price) < reqoteTicks) return;   // within threshold → keep it, save OTR + priority
        r_.modify(q.id, Price{target}, Qty{Q});                 // one message, not cancel+new
        q.price = target;
    }
    void onFill(const Fill& f, Timestamp) {
        position_ += (f.side == Side::Buy ? +int(f.qty.v) : -int(f.qty.v));
        if (f.remainingIsZero) (f.side == Side::Buy ? bid_ : ask_).live = false;  // fully filled → slot free
    }
};
```

**What changed and why it's faster/safer:**

| Concern | Naive | Optimized |
|---|---|---|
| Messages per tick | 2 cancel + 2 new, *always* | 0–2, only when target moved ≥ threshold |
| Queue priority | discarded every tick | kept whenever price unchanged |
| Fair value | `double` (non-deterministic) | integer microprice (deterministic) |
| Live-order storage | `std::map` + clear/alloc | two fixed structs, zero allocation |
| Cross protection | none | clamp to inside the opposite best |
| Inventory | ignored | integer skew + max-position cutoff |
| Work per event | O(orders) + allocation | O(1), branch-lean |

## 2.5 Discussion & tradeoffs (what the interviewer digs into)

- **Threshold tuning is the whole game.** Too tight (`reqoteTicks` small) → you re-quote constantly →
  OTR + latency; too loose → your quotes lag the market → **adverse selection** (you get filled
  exactly when the price is about to move against you). There's no free lunch; it's a tunable.
- **Modify vs cancel/new.** A single amend is fewer messages, but a *price* change loses queue
  priority on most venues anyway; a *size decrease* usually keeps it. So: shrink in place, but treat a
  price move as a genuine re-quote. Know your venue's rules.
- **Microprice vs mid.** Microprice weights toward the side with more size and predicts the next mid
  better; mid is simpler. It's a config knob, not a hard-coded choice.
- **Fills feedback loop.** After a fill you're skewed; the next `onBookUpdate` shades quotes to unwind
  — the strategy self-corrects toward flat. Make sure `onFill` updates position *before* the next
  decision (event ordering matters — determinism again).
- **Latency budget.** Everything here is O(1) integer math + at most two router calls; the router/OMS
  and the wire dominate, not this logic. Which is the point: keep the brain cheap.

---

# Part 3 — Problem 2 (LLD): the rolling signal ("efficient update *and* access")

Real fair value uses more than the current book. This sub-problem is the strategy-engine analog of
the classic "maintain top-K / sliding-window efficiently" question — and it's asked exactly like your
order-book top-10 example.

## 3.1 Problem statement

> Augment the quoter's fair value with a **rolling signal** over a window of the most recent events —
> e.g. **order-flow imbalance (OFI)** or signed trade volume over the last `N` events, *or* over the
> last `T` microseconds. Support, per update, **O(1)** insertion and **O(1)** read of the current
> value. Also maintain a **rolling max and min** of price over the window (a breakout guard).

## 3.2 Brute force

Keep every event in a `std::deque`; recompute on demand:

```cpp
std::deque<Event> win_;
double signal() {                       // O(N) every query — recompute the whole window
    long s = 0; for (auto& e : win_) s += e.signedQty; return s;
}
void onEvent(const Event& e) {
    win_.push_back(e);
    while (win_.size() > N) win_.pop_front();
    // max/min: scan the deque → O(N)
}
```

Correct, obvious — and O(N) per update/query, which at HFT event rates is a non-starter. Say so, then
fix each part.

## 3.3 Optimized

**(a) Count window → ring buffer + running sum.** Keep a fixed array of `N` slots and a live sum;
each push subtracts the evicted slot and adds the new one:

```cpp
class RollingSum {
    std::array<long, N> buf_{};
    long sum_ = 0; std::size_t i_ = 0; bool full_ = false;
public:
    void push(long x) {
        sum_ -= buf_[i_];          // remove the value leaving the window
        buf_[i_] = x; sum_ += x;   // add the new value
        if (++i_ == N) { i_ = 0; full_ = true; }
    }
    long value() const { return sum_; }   // O(1) read, O(1) update, zero allocation
};
```

**(b) Time window → ring buffer of `(timestamp, value)` + running sum**, evicting from the front while
`ts < now − T`. Amortized O(1) (each event is added once and evicted once). Prefer a fixed-capacity
ring sized to the max events you expect in `T`, so there's still no allocation.

**(c) The cheaper alternative — EWMA (often *preferred* in HFT).** Skip the window entirely; decay
exponentially:

```cpp
double ewma_ = 0;
void push(double x) { ewma_ = alpha*x + (1-alpha)*ewma_; }   // O(1), O(1) memory, no eviction
```

EWMA isn't an exact window, but it needs **no buffer, no eviction, one FMA** — and its smooth decay is
usually *desirable*. Naming this tradeoff (exact sliding window vs exponential decay) is a strong
signal you understand the domain, not just the data structure.

**(d) Rolling max/min → monotonic deque.** For an exact-window max, keep a deque of indices whose
values are monotonically decreasing; the front is always the window max. Each element is pushed and
popped at most once → **amortized O(1)** per update, **O(1)** query:

```cpp
// on push(x at index k): pop_back while buf[back] <= x; push_back k;
//                        pop_front while front index <= k - N;  max = buf[front]
```

## 3.4 Tradeoffs

| Approach | Update | Query | Memory | Notes |
|---|---|---|---|---|
| Deque + recompute | O(N) | O(N) | O(N) | brute force / oracle only |
| Ring buffer + running sum | O(1) | O(1) | O(N) fixed | exact count window, no alloc |
| Time-window ring | amortized O(1) | O(1) | O(max events in T) | exact time window |
| EWMA | O(1) | O(1) | O(1) | not exact; smooth; cheapest — often the right call |
| Monotonic deque (max/min) | amortized O(1) | O(1) | O(N) | the only cheap exact rolling extremum |

The lesson (same as the order-book top-10): **the naive answer recomputes; the good answer maintains
an invariant incrementally.** Recognize which structure preserves your invariant in O(1), and whether
you even need an *exact* window (EWMA) — that judgment is the interview.

---

# Part 4 — Problem 3 (HLD): architecting the engine

## 4.1 Problem statement

> Design a strategy engine that runs **K strategies** across **M instruments**, in two environments
> with the **same strategy code**: **live** (real feed → risk → OMS → exchange) and **backtest**
> (recorded market data → simulated fills). Cover event routing, threading, determinism,
> backtest/live parity, order/fill attribution, and where risk sits.

## 4.2 How to think about it

Drive it top-down; each decision below is a talking point.

**(1) The abstraction boundary (the key insight).** A strategy depends on *two* interfaces only: the
events it receives (`Strategy` callbacks) and the `OrderRouter` it acts through (§1.3). Everything
environment-specific lives *behind* those interfaces:

```
                         ┌──────────────────────── STRATEGY ENGINE (one shard, one core) ─────────┐
   event source ──────▶  │  EventQueue ─▶ Dispatcher ─▶ [Strategy A][Strategy B]… (sequential)     │
   (live feed OR         │                                   │ send/modify/cancel                  │
    recorded file)       │                                   ▼                                     │
                         │                              OrderRouter ── live: → Risk → OMS → exch    │
                         │                                          └ backtest: → Fill simulator    │
                         └──────────────────────────────────────────────────────────────────────┘
              fills/exec reports flow back in as events ─────────────────┘
```

Live and backtest differ *only* in what feeds the `EventQueue` and what sits behind the
`OrderRouter`. The strategy binary is identical — that's parity.

**(2) Event model & determinism.** Everything a strategy sees is a timestamped event; the engine is a
**deterministic function of the ordered event stream**. Rules that enforce it: strategies read *event
time*, never the wall clock; timers are events scheduled on the event clock; no decision depends on
`unordered_map` iteration order; the dispatcher processes events in a fixed, total order. Get this
right and a production incident replays exactly in backtest.

**(3) Dispatch / subscription.** A table `instrument → [strategies subscribed]`; on each market-data
event, the dispatcher calls each subscriber's `onBookUpdate/onTrade`. Keep it a flat, cache-friendly
structure (vector of subscribers per instrument), not a map of maps.

**(4) Threading & scaling.** Single-threaded *per shard*; a shard owns a disjoint set of instruments
and the strategies on them, pinned to one core. Scale by **sharding instruments across cores** — no
shared strategy state, no locks. A strategy that needs two instruments must live in the same shard
(co-locate correlated instruments). This is the same "single-threaded core, shard to scale" principle
as the matching engine (Module 03 §7.7).

**(5) Order & fill attribution.** The engine (not the strategy) assigns **client order IDs** and keeps
a map `clientOrderId → owning strategy`. Exchange fills/rejects come back tagged with that id; the
engine routes each to the originating strategy's `onFill`. Strategies never see each other's orders.

**(6) Where risk sits.** A **pre-trade risk gate** between `OrderRouter` and the OMS enforces *hard*
limits (max position, max order value, rate limits, kill-switch) — unbypassable, on the critical
path. Strategies keep *soft* limits (§1.2) to avoid sending doomed orders, but risk is the backstop.
Two layers, different jobs.

**(7) Isolation caveat.** Single-threaded means one strategy's slow `onBookUpdate` stalls its
shard-mates. Mitigate by *bounding per-event work* (no unbounded loops, no allocation, no I/O in
handlers) and pushing heavy/slow work (recalibration, logging) to an off-hot-path thread that
communicates via a lock-free queue (Module 16). Don't try to preempt strategies mid-event — that
breaks determinism.

## 4.3 Tradeoffs to name

- **Determinism vs isolation.** Single-threaded gives perfect determinism and no locks, but no
  fault-isolation between co-located strategies. Accept it and bound per-event work; isolate blast
  radius by sharding, not threading.
- **Parity vs realism.** Same code in backtest and live is parity; but backtest realism depends on the
  **fill model** (queue position, latency) behind the backtest router — that's Module 07. The engine's
  job is only to make the boundary clean enough that swapping routers is trivial.
- **Shard granularity.** Fewer, bigger shards = better core utilization but more head-of-line blocking;
  more, smaller shards = better isolation but more cores and cross-shard coordination for
  multi-instrument strategies.
- **Virtual `Strategy`/`OrderRouter` calls on the hot path.** The clean interface uses virtual
  dispatch (a vtable indirection per event). If profiling shows it matters, template the engine on a
  concrete strategy type (Module 17 CRTP) for the hottest single-strategy shards — trading flexibility
  for a few ns. Measure before doing it.

---

# Interview Q&A — strategy engine

**Q. "What does the strategy engine actually do, in one breath?"**
It's a deterministic, single-threaded, event-driven function that turns market-data + fill events into
order intents: consume events, update signals incrementally, decide target orders from signals +
position + limits, diff target against live orders, and emit the minimal NEW/MODIFY/CANCEL — all in a
few hundred nanoseconds with no allocation.

**Q. "Why must it be deterministic, and how do you enforce it?"**
So backtests are trustworthy and production bugs are reproducible. Enforce it: use event timestamps
not the wall clock, never let `unordered_map` iteration order affect a decision, seed any randomness,
use integer/fixed-point math, and process events in a fixed total order.

**Q. "Your quoter — how do you avoid getting throttled for too many orders?"**
A re-quote threshold: only move a quote when the target shifts by ≥ N ticks; prefer a single amend
over cancel+new; and skip the side you don't want to grow. It's a direct OTR-vs-staleness tradeoff —
too tight churns messages, too loose invites adverse selection.

**Q. "How do the same strategy run live and in backtest?"**
The strategy only reads events and acts through an `OrderRouter` interface. Live puts a real feed on
the event queue and risk→OMS behind the router; backtest puts recorded data on the queue and a fill
simulator behind the router. Identical strategy binary — that's parity.

**Q. "Rolling imbalance over the last N events, O(1) per update — how?"**
Ring buffer of N slots plus a running sum: each push subtracts the evicted slot and adds the new one.
For a *time* window, a ring of (ts,value) evicting from the front. If an exact window isn't required,
an EWMA is O(1) time and memory with no eviction — usually the better call.

**Q. "How do you scale to thousands of instruments and many strategies?"**
Shard instruments across cores; each shard is a single-threaded engine pinned to a core running the
strategies for its instruments. No shared strategy state, no locks. Co-locate correlated instruments
in one shard so multi-instrument strategies stay lock-free.

**Q. "Where does risk live — in the strategy?"**
Two layers: soft limits in the strategy (don't send doomed orders), hard limits in a pre-trade risk
gate between the router and the OMS (unbypassable, on the critical path). The strategy is
risk-*aware*; risk is the backstop.

---

# Key takeaways

- The strategy engine is the **decision brain**: a deterministic, single-threaded, event-driven
  function from market/fill events to order intents. Its six jobs — dispatch, signal state, decide,
  reconcile, track position, self-guard — are all O(1)-ish and allocation-free.
- **Determinism and the `Strategy`/`OrderRouter` interface** are the load-bearing design choices: they
  give you backtest/live parity and reproducibility. Design them first.
- **Problem 1 (quoter):** the hard parts aren't the math — they're the **target-vs-live diff**
  (message minimization + queue priority), **inventory skew**, **cross protection**, and integer
  determinism. Brute force cancels-all every tick and dies on OTR; the optimized version emits 0–2
  messages only when the target actually moved.
- **Problem 2 (rolling signal):** the naive answer recomputes O(N); the good answer maintains an
  invariant in O(1) — ring buffer + running sum, or EWMA when an exact window isn't needed, or a
  monotonic deque for rolling max/min. Same class as the top-K question.
- **Problem 3 (HLD):** clean interface boundary + deterministic event stream + single-threaded shards
  + instrument sharding + engine-owned order/fill attribution + a separate pre-trade risk gate. Name
  the tradeoffs (determinism vs isolation, parity vs realism, shard granularity, virtual vs templated
  dispatch).
- Across all of it: **build the obvious correct version, critique it against the real constraints
  (OTR, latency, determinism, priority), then earn the optimized version.** The reasoning path is the
  interview.

**Next:** Module 05 — the OMS / order gateway (the box the strategy's intents flow into: exchange
session, order state machine, fills reconciliation, recovery). Return to the [index](../00-index.md).
