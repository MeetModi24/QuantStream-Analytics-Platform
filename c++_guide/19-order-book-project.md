# Module 19 — Order-book architecture: building it with everything above

This is where the language becomes a system. Every module so far taught a tool; this module spends
them. We build the **Limit Order Book & Matching Engine** in two phases — a **correct naive version
(rung 1)** and a **cache-optimized version (rung 3)** — so your portfolio has a real *before/after*
story with measured numbers, and so every design choice traces back to a concept you can defend in
an interview. Read this last; it assumes everything before it.

---

## 1. What a limit order book does

An exchange's job is to match buyers and sellers. The **order book** is the data structure that
holds every resting order and decides what trades when. It holds resting **bids** (buy orders) and
**asks** (sell orders), grouped by price. When a new order arrives:

- If it **crosses** the opposite side — a buy priced ≥ the best ask, or a sell priced ≤ the best bid
  — it **matches**: trades execute immediately against resting orders.
- Any quantity left over after matching **rests** in the book at its own price, waiting for a future
  counterparty.

The matching rule almost every exchange uses is **price–time priority**: the best price is served
first, and among orders at the *same* price, the one that arrived earliest is filled first (FIFO).
That "earliest first" is why time ordering inside a price level matters so much to the data-structure
choice.

```
        ASKS (sell)                 BIDS (buy)
price   qty   orders                price  qty  orders
102     50    [o7]                  100    30   [o1,o4]   <- best bid (highest buy)
101     20    [o5,o6]  <- best ask  99     80   [o2]
                                    98     10   [o3]
                        ^ spread = best ask - best bid = 101 - 100 = 1
```

A buy order for 25 @ 101 would cross the best ask (101 ≤ 101): it fills 20 against `o5`, then 5
against `o6` (FIFO within the level), leaving `o6` with reduced quantity — no leftover to rest. A
buy for 25 @ 100 would *not* cross (100 < 101), so it rests at price 100 behind `o1` and `o4`.

---

## 2. The data structures in memory (and why)

Three structures work together — the canonical order-book design (Module 12) — plus an object pool
so the hot path never allocates. Here's how they sit in memory:

```
PRICE LEVELS (sorted / indexed by price)      ORDERS WITHIN A LEVEL (intrusive doubly-linked, FIFO)

  price ladder                                 level 100.50:  head            tail
  ┌─────────┐                                                  │               │
  │ 100.52  │──▶ level ...                                     ▼               ▼
  │ 100.51  │──▶ level ...        bestAsk ─▶ 100.51          [O1]⇄[O2]⇄[O3]   (oldest → newest)
  │ 100.50  │──▶ level (O1,O2,O3) bestBid ─▶ 100.50           ▲   ▲
  │ 100.49  │──▶ level ...                                    │   └ prev/next are 32-bit
  └─────────┘                                                 │     indices into the pool
                                                              │
  ORDER-ID MAP (hash)          OBJECT POOL (one big array, allocated once at startup)
  ┌───────────────────┐        ┌────┬────┬────┬────┬────┬─── ... ───┐
  │ id 4001 ─▶ slot 12 │        │ O0 │ O1 │ O2 │ O3 │ O4 │           │  free-list: [7,5,9,...]
  │ id 4002 ─▶ slot 5  │        └────┴────┴────┴────┴────┴─── ... ───┘
  │ ...  O(1) cancel   │        contiguous → cache-friendly; links are indices, not pointers
  └───────────────────┘
```

1. **Price levels** — ordered by price so you can find the best. Naive: `std::map<Price, Limit>`.
   Optimized: a flat array indexed by price ticks + a bitset of occupied levels. *(Modules 8, 12,
   15, 18)*
2. **Orders within a level** — an **intrusive doubly-linked list**. Doubly-linked so you can splice
   any order out in O(1) on cancel; FIFO order (append at tail, match from head) gives time
   priority. *(Modules 2, 12)*
3. **Order ID → order** — a hash map for O(1) lookup on cancel/modify, since cancels arrive by ID,
   not by price. *(Module 12)*

Plus an **object pool** for `Order`s — memory carved out once at startup, so there is **no `new` on
the hot path** (Module 3). This is the single most important latency decision in the whole system:
a heap allocation can cost hundreds of nanoseconds and is unpredictable; a pool `acquire()` is a
vector `pop_back`.

---

## 3. The types (Modules 5, 6, 8, 15)

```cpp
#include <cstdint>

enum class Side : std::uint8_t { Buy, Sell };

// Strong value types (Module 8) — integer ticks, never double (see Module 8 / Module 18 §5)
struct Price { std::int64_t ticks; auto operator<=>(const Price&) const = default; };
struct Qty   { std::uint32_t v; };
struct OrderId { std::uint64_t v; auto operator<=>(const OrderId&) const = default; };

// Compact, layout-optimized POD (Module 15): hot fields packed, 32-bit link indices
struct Order {
    OrderId       id;
    std::int64_t  price;      // ticks
    std::uint32_t qty;
    Side          side;
    std::uint32_t next;       // index of next order at this level (intrusive list)
    std::uint32_t prev;       // index of prev order
};  // trivially copyable -> move == copy, which is fine (Module 9)
```

Three deliberate choices here, each defensible:

- **Integer ticks, not `double`.** Doubles don't represent decimals exactly (0.1 is inexact) and
  compare as `partial_ordering` because of NaN — both unacceptable when you compare and sum prices
  millions of times. Ticks (price × scale) are exact and give `strong_ordering` (Modules 8, 18).
- **Strong types** (`Price`, `Qty`, `OrderId`) instead of raw integers, so you can't accidentally
  pass a quantity where a price is expected — the compiler catches it (Module 8).
- **32-bit index links, not pointers.** Half the size of 64-bit pointers (Module 15), and they stay
  valid even if the pool's backing storage is relocated — a pointer would dangle, an index wouldn't.

---

## 4. The object pool (Modules 3, 7, 13)

```cpp
class OrderPool {
    std::vector<Order> storage_;      // allocated ONCE at startup
    std::vector<std::uint32_t> free_; // free-list of slot indices
public:
    explicit OrderPool(std::size_t n) : storage_(n) {
        free_.reserve(n);
        for (std::uint32_t i = 0; i < n; ++i) free_.push_back(n - 1 - i);
    }
    OrderPool(const OrderPool&) = delete;   // Rule of Five: non-copyable (Module 7)
    OrderPool& operator=(const OrderPool&) = delete;

    std::uint32_t acquire() { auto i = free_.back(); free_.pop_back(); return i; } // O(1)
    void release(std::uint32_t i) { free_.push_back(i); }                          // O(1)
    Order& operator[](std::uint32_t i) { return storage_[i]; }
};
```

The pool owns one big contiguous array and hands out **indices** into it. `acquire()` and
`release()` are just a vector `pop_back`/`push_back` — O(1), no syscall, no allocator lock,
deterministic latency. Because links are indices (not pointers), they survive a future relocation of
`storage_` and are half the size of pointers (Module 15). It's non-copyable (Rule of Five, Module 7)
because there must be exactly one owner of the storage. This is RAII (Module 7) applied to the
system's memory: allocate once, reuse forever, free once at shutdown.

---

## 5. Phase 1 (rung 1): the correct naive book

**Get it right before you make it fast.** Use `std::map` for price levels — it's obviously correct,
trivially reasoned about, and gives you sorted best-price at `begin()`. Correctness first buys you a
reference implementation to test the fast version against later.

```cpp
#include <map>
#include <unordered_map>
#include <list>

class NaiveBook {
    // price -> FIFO list of order IDs at that price
    std::map<std::int64_t, std::list<OrderId>, std::greater<>> bids_;  // high->low
    std::map<std::int64_t, std::list<OrderId>>                 asks_;  // low->high
    std::unordered_map<std::uint64_t, Order> orders_;                  // id -> order
public:
    void addLimit(const Order& incoming);   // match then rest
    bool cancel(OrderId id);
    // best bid = bids_.begin(), best ask = asks_.begin()
};
```

`bids_` uses `std::greater<>` so the highest price sorts first (best bid = `begin()`); `asks_` sorts
ascending so the lowest price is best (best ask = `begin()`). The matching loop is the heart of the
whole system:

```
addLimit(buy order X):
  while X.qty > 0 AND asks_ not empty AND bestAsk.price <= X.price:   // crosses?
      match against front order at bestAsk (FIFO / time priority)
      execute min(X.qty, resting.qty); reduce both; emit a Trade
      if resting.qty == 0: remove it (pop_front); if level empty: erase level
  if X.qty > 0: rest X in bids_[X.price] (push_back for FIFO)
```

Read that loop carefully — it *is* price–time priority: `bestAsk` (the map's front) enforces price
priority; `pop_front`/`push_back` on the per-level list enforces time priority. At rung 1 you
support **limit and market** orders, add/cancel, and you write **GoogleTest** unit tests (the
matching path uses status codes, not exceptions — Module 14). This is already a real, defensible
project — roughly where a typical open-source order-book repo sits (some use an AVL tree instead of
`std::map`, plus stop orders).

---

## 6. Phase 2 (rung 3): the cache-optimized book — your differentiation

Now make the leap that produces the *before/after* story. Replace the tree with a **flat array
indexed by price + a bitset of occupied levels** (Modules 15, 18):

```cpp
#include <bit>
#include <array>

class FastBook {
    static constexpr std::size_t Levels = 1 << 16;   // constexpr size (Module 5)

    struct Level { std::uint32_t head = 0, tail = 0; std::uint32_t total = 0; };
    std::array<Level, Levels> levels_;                // price tick -> level; contiguous (Module 15)

    // bitset of occupied levels: one bit per level, packed into 64-bit words
    std::array<std::uint64_t, Levels / 64> occupied_{};

    OrderPool pool_;
    std::unordered_map<std::uint64_t, std::uint32_t> idToSlot_;  // id -> pool index

    void setBit(std::size_t lvl)   { occupied_[lvl/64] |=  (1ull << (lvl%64)); }
    void clearBit(std::size_t lvl) { occupied_[lvl/64] &= ~(1ull << (lvl%64)); }
public:
    // best ask = lowest occupied level (Module 18: countr_zero = one instruction)
    std::size_t bestAskLevel() const {
        for (std::size_t w = 0; w < occupied_.size(); ++w)
            if (occupied_[w]) return w*64 + std::countr_zero(occupied_[w]);
        return Levels;  // empty
    }
    // add/cancel/match operate on levels_ by direct index — no tree, no rebalance
};
```

**What changed and why it's faster:**

- **No tree rebalancing.** A `std::map`/AVL rebalances on insert and erase; benchmarks on real
  order-book repos found rebalancing dominated latency (~2500 ns per rebalance in one measured case).
  A direct array index has none — inserting at a price level is `levels_[tick]` plus a bit-set. *You
  are literally deleting the bottleneck those projects identified.*
- **Cache-friendly.** Levels are contiguous, so the hardware prefetcher streams them (Module 15); no
  pointer chasing across scattered tree nodes each costing a cache miss.
- **O(1) best-price.** `countr_zero` on the bitset finds the best occupied level in a single
  hardware instruction (Module 18) instead of walking to a tree's leftmost node.
- **Branch elimination.** Template the side (`FastBook<Side::Buy>` internals) so the buy/sell
  comparison is compile-time (Module 17).

**Tradeoff to state honestly:** the array costs fixed memory proportional to the price *range*
(`Levels` entries) no matter how many are occupied — great for a bounded tick range (equities,
futures), wasteful for a huge sparse range. Naming that tradeoff — and the mitigations (a windowed
array around the touchable range, or a hybrid array-plus-map for the tails) — is exactly the
engineering judgment interviewers are probing for. Never present the fast version as strictly
better; present it as the right choice *given a bounded price range*.

---

## 7. Complexity table — what each operation costs

| Operation | NaiveBook (`std::map`) | FastBook (array + bitset) |
|-----------|------------------------|---------------------------|
| Add resting order | O(log L) tree insert + O(1) list append | **O(1)** index + bit-set |
| Cancel by ID | O(1) map lookup + O(1) list splice | **O(1)** lookup + splice |
| Find best bid/ask | O(1) `begin()` (but pointer-chasing, rebalanced) | **O(1)** `countr_zero` (branch-free, one word usually) |
| Match one price level | O(1) per fill (pop_front) | **O(1)** per fill |
| Memory | O(active orders) | O(price range) — fixed |

L = number of distinct price levels. The asymptotics look similar; the *constants* and *cache
behavior* are where FastBook wins — that's the whole point, and the reason you must measure rather
than argue from big-O alone.

---

## 8. Optional: multithreading (Module 16)

Keep **matching single-threaded**. This surprises people, but a single-threaded matching core is
simpler *and often faster* than sharing the book across cores, because sharing forces locking or
lock-free coordination whose coherency traffic can cost more than it saves. Instead, decouple I/O
from matching with a lock-free **SPSC queue** (Module 16):

```
network/parser thread ──push──▶  SpscQueue<Message, N>  ──pop──▶  matching thread (owns the book)
        (producer)               alignas(64) indices              (consumer, single-threaded)
```

`alignas(64)` the queue's head/tail indices to kill false sharing (Module 15/16). Resist the urge to
parallelize the book itself — it rarely helps and adds enormous complexity (correctness of
concurrent matching is genuinely hard). The scalable pattern in real systems is *sharding by
symbol*: one single-threaded matching engine per instrument, many engines across cores — not one
book shared by many threads.

---

## 9. Measure everything (the portfolio-defining part)

A fast order book with no numbers is a claim; with numbers it's evidence. This section is what turns
the project into an interview asset.

- **Feed 5M+ orders** with a realistic distribution (or replay real Binance/ITCH market data), so
  the access pattern resembles production.
- **Record a latency histogram** and report **P50, P99, P99.9** — *not* the average. HFT lives and
  dies on the tail: one slow order in ten thousand can be the one that matters (Modules 3, 14). An
  average hides the tail; a histogram exposes it.
- **Benchmark rung 1 vs rung 3** on identical data and hardware. Your headline sentence: *"Replacing
  the tree with a price-indexed array + bitset eliminated the rebalance cost; P99 latency dropped
  from X ns to Y ns."*
- **Profile** (`perf`, VTune) and show a flame graph of *where the time went* — the self-diagnosis
  skill that separates a candidate who tuned by guessing from one who tuned by measuring.

---

## 10. The full module map, applied

| Module | Where it shows up in the order book |
|--------|-------------------------------------|
| 1 Stack/heap | orders outlive their call → heap/pool, not stack |
| 2 Pointers/refs | intrusive links, `const Order&` params |
| 3 Dynamic memory | `OrderPool`, no `new` on hot path |
| 4/9 Move | trivial for POD orders; matters in input/result queues |
| 5 const/constexpr | `constexpr Levels`, `const` query methods |
| 6 Classes | `Order`, `Level`, `Book` |
| 7 RAII/Rule of 5 | pool is non-copyable, owns storage |
| 8 Operators | `Price`/`OrderId` with `<=>`, integer ticks |
| 10 Templates/concepts | side-templated book, constrained price types |
| 11 Virtual (avoided) | enum+switch / templates on hot path |
| 12 STL | map→array, unordered_map, intrusive list |
| 13 Smart pointers | own the pool; non-owning indices into it |
| 14 Exceptions | status codes hot, exceptions cold |
| 15 Cache/layout | compact `Order`, contiguous levels, false-sharing padding |
| 16 Atomics | SPSC queue between I/O and matching |
| 17 Zero-cost/CRTP | inlined comparators, branch elimination |
| 18 C++20 | `<bit>`, `<=>`, concepts, `span`, `[[likely]]` |

---

## 11. Build order (do it in this sequence)

1. Types + `OrderPool` + GoogleTest scaffold.
2. `NaiveBook` (map-based) — limit + market orders, add/cancel. **Get tests green.**
3. Add stop / stop-limit / modify. Benchmark; record the baseline histogram.
4. `FastBook` (array + bitset). Re-run the *identical* benchmark. **Capture the delta.**
5. SPSC queue + separate I/O thread (optional). Re-measure tail latency.
6. Write the README: architecture, the before/after numbers, the profiling flame graph, and *why*
   each choice. **That README is your interview.**

Build rung 1 first; everything else compounds on top of a correct baseline.

---

## 12. Common pitfalls & gotchas

- **Optimizing before it's correct.** If rung 1 has a matching bug, rung 3 just makes wrong answers
  faster. The naive book is also your test oracle for the fast one.
- **Using `double` for price.** Rounding error and NaN ordering will bite you; use integer ticks.
- **`new`/`delete` on the hot path.** Even one heap allocation per order destroys tail latency and
  determinism. Everything hot comes from the pool.
- **Storing pointers into a `std::vector` pool.** A `push_back` that reallocates invalidates every
  pointer. Store *indices* (Module 15) — they survive relocation.
- **Reporting only the average latency.** The average hides the tail; HFT interviewers immediately
  ask for P99/P99.9.
- **Parallelizing the single book.** Sharing the book across threads adds locking/coherency cost and
  correctness risk that usually outweigh the benefit. Shard by symbol instead.
- **Forgetting FIFO within a level.** Match from the head, append at the tail. Reversing it breaks
  time priority — a subtle correctness bug the naive-vs-fast diff will catch if you test it.
- **Erasing an empty level but leaving its bit set** (FastBook) — `bestAskLevel` will then point at
  a level with no orders. Clear the bit exactly when the level empties.

---

## 13. Quiz — tough design problems

**Q1.** Why is *cancel* O(1) in this design, and which two structures cooperate to make it so? What
would make it O(n) if you got it wrong?

**Answer:** The id→slot hash map finds the order in O(1); because the per-level list is **doubly**
linked, you have the victim's `prev`/`next` and can splice it out in O(1) without scanning. It
becomes O(n) if the list is *singly* linked (you'd have to walk from the head to find the
predecessor) or if you lack the id map and must search levels for the order.

---

**Q2.** You must find the best bid and best ask on every incoming order. Compare a `std::map` and a
price-indexed array + bitset for this specific operation — asymptotically and in real nanoseconds —
and state when the map is actually the better choice.

**Answer:** `std::map::begin()` is O(1) to *reach* but the tree is maintained at O(log L) per
insert/erase and each access chases pointers through cache-cold nodes (~tens–hundreds of ns per
miss) plus rebalancing. The bitset find is O(words) ≈ O(1), branch-free (`countr_zero`), over a few
contiguous cache-hot words — single-digit ns. The **map wins when the price range is huge and
sparse** (the array would waste gigabytes) or when you genuinely need ordered iteration over a vast
key space; the array wins for a bounded tick range, which real instruments have.

---

**Q3.** How would you support **order modify** (change price and/or quantity) correctly under
price–time priority? What's the subtle rule?

**Answer:** A pure quantity *decrease* can keep the order's place in the FIFO queue (many exchanges
allow it). But a **price change, or a quantity increase, loses time priority** — the exchange treats
it as cancel-then-new, so the order goes to the *back* of the new price level. Implement modify as:
if only decreasing qty, mutate in place; otherwise remove (O(1) splice + clear bit if level empties),
then re-add at the new price/qty (append at tail). Getting this wrong silently gives orders unfair
priority — a correctness bug interviewers love to probe.

---

**Q4.** The array-based `FastBook` uses `Levels = 1 << 16`. What's the memory cost, and what do you
do when the traded price can be anywhere in a range of billions of ticks?

**Answer:** `Levels` entries of `Level` (say 12 bytes) ≈ 786 KB for `levels_`, plus 8 KB for the
bitset — fixed regardless of occupancy. For a range of billions of ticks a full array is infeasible
(gigabytes). Solutions: **(a)** a *windowed* array covering only the touchable band around the
current price, sliding as the market moves; **(b)** a *hybrid* — array for the dense band near the
touch, `std::map` for the sparse tails; **(c)** map ticks to a compact index via the set of prices
that actually appear. Naming the tradeoff and a mitigation is the point of the question.

---

**Q5.** Give a rough **latency budget** for processing one order in a low-latency (not
ultra-low-latency-FPGA) software matching engine, and where the time goes.

**Answer:** Order of magnitude, warm cache, single-threaded core: parse/validate ~tens of ns, id-map
lookup ~20–100 ns (a hash + possible cache miss), best-price find ~single-digit ns (bitset), matching
loop a few ns per fill, pool acquire/release ~few ns, trade emit ~tens of ns. Total on the order of
a few hundred ns per order in software; the dominant *variable* costs are cache misses on the id map
and any accidental allocation. The tail (P99.9) is set by cache misses and TLB misses, which is why
pooling, contiguity, and avoiding `new` matter more than shaving the average.

---

**Q6.** Why keep matching single-threaded, and how do you then scale to many instruments and use all
cores?

**Answer:** A single-threaded matching core avoids all locking/lock-free coordination on the book;
sharing one book across threads costs cache-coherency traffic and correctness risk that usually
outweigh the parallelism. To use all cores you **shard by symbol**: run one independent
single-threaded engine per instrument (or group), each pinned to a core, fed by its own SPSC queue.
Instruments are naturally independent, so this scales linearly without any shared-book locking.

---

**Q7.** How do you *test* that the fast book matches the exchange's rules, given the naive book
exists?

**Answer:** Differential (property-based) testing: generate large random streams of add/cancel/modify
orders, feed the identical stream to `NaiveBook` and `FastBook`, and assert the sequence of emitted
trades and the resting book state are byte-for-byte identical after each operation. The naive book is
the oracle. Add targeted unit tests for the tricky rules (FIFO within a level, price–time priority on
modify, self-trade prevention if required, partial fills, market orders sweeping multiple levels).

---

## 14. Indian HFT interview questions

**Q (the classic system-design grill — Tower Research / Optiver / IMC): "Design a limit order book
and matching engine."**
Walk them through it top-down: (1) *Semantics* — price–time priority, limit vs market orders,
crossing rule. (2) *Data structures* — sorted price levels (map → array+bitset), intrusive
doubly-linked FIFO list per level for O(1) cancel and time priority, id→order hash map for O(1)
cancel lookup, object pool so no `new` on the hot path. (3) *Operations & complexity* — add O(1),
cancel O(1), best-price O(1) via `countr_zero` on a bitset. (4) *Concurrency* — single-threaded
matching, SPSC queue from the I/O thread, shard by symbol to scale. (5) *Correctness & perf* —
differential testing against a naive reference, P50/P99/P99.9 histograms, profiling. Leading with
semantics and closing with measurement is what separates a strong answer.

**Q (Graviton / Quadeye): "How do you make cancel O(1)?"**
Hash map from order ID to the order's slot, plus a *doubly*-linked list per price level so you can
splice the order out with its `prev`/`next` in O(1) — no scan. Both structures are required; either
alone forces a search.

**Q (AlphaGrep / NK Securities): "What data structure holds price levels and why not just a
`std::map`?"**
`std::map` (a red-black tree) is correct and gives sorted best-price, but it rebalances on every
insert/erase and chases pointers through cache-cold nodes — the rebalance and cache misses dominate
latency. For a bounded tick range I use a flat array indexed by price tick plus a bitset of occupied
levels, so add is a direct index and best-price is one `countr_zero`. I keep the map version as the
correctness reference and I'm explicit about the memory-vs-range tradeoff.

**Q (WorldQuant / Da Vinci): "Single-threaded or multi-threaded matching?"**
Single-threaded per book — it avoids locking and coherency traffic and is usually faster than a
shared book. I scale across cores by sharding on symbol (one engine per instrument, each on its own
core, fed by an SPSC queue), because instruments match independently.

**Q (Jump / HRT): "Why no `new` on the hot path, and how do you avoid it?"**
Heap allocation is slow (hundreds of ns) and, worse, *unpredictable* — it can hit the allocator lock
or a syscall, wrecking tail latency. I pre-allocate an object pool at startup and hand out indices;
acquire/release are O(1) vector operations. Determinism matters more than the average here.

**Q (Squarepoint / Millennium): "How do you measure and prove your engine is fast?"**
Replay a realistic (or real ITCH/Binance) order stream of millions of messages, record a per-order
latency histogram, and report P50/P99/P99.9 — never just the mean, because the tail is what trading
cares about. I benchmark the naive and fast versions on identical data/hardware to quantify the
delta, and I profile with `perf` to show where the remaining time goes (id-map cache misses, mostly).

**Q (Mansard / Qube): "What's the hardest correctness bug in a matching engine?"**
Time-priority violations on modify — allowing an order that increased quantity or changed price to
keep its place in the FIFO queue. The rule is: quantity decrease may keep priority, but price change
or quantity increase loses it (cancel-then-new to the back of the level). I catch this with
differential testing against the naive reference and targeted FIFO tests.

---

## 15. In the order book — the through-line

This whole module *is* "in the order book." The point of the C++ guide is that a limit order book is
the problem where every language feature earns its place: object lifetime and the pool (Modules 1,
3, 7), pointers vs indices and cache layout (Modules 2, 15), value semantics and strong types for
price (Modules 6, 8), templates and zero-cost dispatch to delete the side branch (Modules 10, 17),
the STL choices and their replacements (Module 12), error handling split hot/cold (Module 14),
lock-free hand-off between threads (Module 16), and the specific C++20 features that make the fast
path fast (Module 18). Build rung 1 until it's provably correct, then build rung 3 and *measure* the
difference — the before/after numbers, the flame graph, and the "why" behind each choice are the
portfolio.

---

## Key takeaways

- A limit order book matches by **price–time priority**: best price first, FIFO within a price. That
  rule dictates every data-structure choice.
- The canonical design is three cooperating structures — **sorted price levels**, an **intrusive
  doubly-linked FIFO list per level**, and an **id→order hash map** — plus an **object pool** so the
  hot path never calls `new`.
- **Correct first (rung 1, `std::map`), fast second (rung 3, array + bitset).** The naive book is
  also your differential-testing oracle.
- The speedup comes from **deleting tree rebalancing, laying levels out contiguously for the cache,
  and finding best-price with one `countr_zero`** — not from better big-O, so you must *measure*
  (P50/P99/P99.9, not averages).
- Keep **matching single-threaded**, decouple I/O with an **SPSC queue**, and scale by **sharding on
  symbol** — don't parallelize a single book.
- The honest tradeoff is **memory ∝ price range** for the array design; know the windowed/hybrid
  mitigations. Naming tradeoffs is what interviewers reward.
- The measured before/after story, the profiling flame graph, and the "why" for each choice are the
  interview — build them deliberately.

**Next:** You've reached the end of the guide. Return to the [index](00-index.md) to review, or start
building — types + pool + tests first.
