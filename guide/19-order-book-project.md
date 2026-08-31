# Module 19 — Order-book architecture: building it with everything above

This is where the language becomes a system. We'll build the Limit Order Book & Matching Engine in two phases — a **correct naive version (rung 1)** and a **cache-optimized version (rung 3)** — so your portfolio has a *before/after* story with real measurements. Every design choice links back to a module.

## What a limit order book does

An exchange matches buyers and sellers. The book holds resting **bids** (buy orders) and **asks** (sell orders) at each price. On a new order:
- If it **crosses** the opposite side (a buy priced ≥ the best ask, or a sell ≤ the best bid), it **matches** — trades execute.
- Any leftover **rests** in the book at its price.

The matching rule is **price-time priority**: best price first; at equal price, earliest order first (FIFO).

```
        ASKS (sell)                 BIDS (buy)
price   qty   orders                price  qty  orders
102     50    [o7]                  100    30   [o1,o4]   <- best bid (highest buy)
101     20    [o5,o6]  <- best ask  99     80   [o2]
                                    98     10   [o3]
                        ^ spread = best ask - best bid = 101 - 100 = 1
```

## Core data structures (and why)

Three structures working together — the canonical design (Module 12):

1. **Price levels** — ordered by price. Naive: `std::map<Price, Limit>`. Optimized: flat array indexed by price ticks + a bitset. *(Modules 8, 12, 15, 18)*
2. **Orders within a level** — an **intrusive doubly-linked list** for O(1) cancel and FIFO time priority. *(Modules 2, 12)*
3. **Order ID → order** — a hash map for O(1) lookup on cancel/modify. *(Module 12)*

Plus an **object pool** for orders — no `new` on the hot path. *(Module 3)*

## The types (Modules 5, 6, 8, 15)

```cpp
#include <cstdint>

enum class Side : std::uint8_t { Buy, Sell };

// Strong value types (Module 8) — integer ticks, never double (Module 8 note)
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
};  // trivially copyable -> move == copy, fine (Module 9)
```

## The object pool (Module 3, 7, 13)

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

Indices (not pointers) into `storage_` mean links are 32-bit and stay valid even if you later relocate storage — and they're half the size of pointers (Module 15).

## Phase 1 (rung 1): the correct naive book

Get it *right* before fast. Use `std::map` for levels — clear, obviously correct, easy to reason about.

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

Matching logic (the heart):

```
addLimit(buy order X):
  while X.qty > 0 AND asks_ not empty AND bestAsk.price <= X.price:   // crosses?
      match against front order at bestAsk (FIFO / time priority)
      execute min(X.qty, resting.qty); reduce both; emit a Trade
      if resting.qty == 0: remove it (pop_front); if level empty: erase level
  if X.qty > 0: rest X in bids_[X.price] (push_back for FIFO)
```

At rung 1 you support **limit + market** orders, add/cancel, and you write **GoogleTest** unit tests (Module 14 uses status codes, not exceptions, on this path). This is already a real, defensible project — it's roughly where the brprojects repo sits (AVL instead of `std::map`, plus stop orders).

## Phase 2 (rung 3): the cache-optimized book — your differentiation

Now make the leap that produces your portfolio's *before/after* story. Replace the tree with a **flat array indexed by price + a bitset** (Modules 15, 18):

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

What changed and why it's faster:
- **No AVL rebalancing** — brprojects' own benchmark found rebalancing dominated latency (~2500 ns per rebalance). A direct array index has none. *(This is literally the bottleneck their README identified — you're removing it.)*
- **Cache-friendly** — levels are contiguous; the prefetcher streams them (Module 15). No pointer chasing across scattered tree nodes.
- **O(1) best-price** — `countr_zero` on the bitset finds the best level in one hardware instruction (Module 18) instead of walking to a tree's leftmost node.
- **Branch elimination** — template the side (`FastBook<Side::Buy>` internals) so buy/sell comparison is compile-time (Module 17).

Tradeoff to state honestly: the array costs fixed memory proportional to the price range (`Levels` entries) regardless of how many are occupied — fine for a bounded tick range, wasteful for a huge sparse range. That's the real engineering judgment call, and naming it is what interviewers want.

## Optional: multithreading (Module 16)

Keep matching **single-threaded** (simpler, often faster than sharing the book). Decouple I/O with an `SpscQueue<Message, N>`: a network/parser thread produces messages; the matching thread consumes. `alignas(64)` the queue indices to kill false sharing. Resist the urge to parallelize the book itself — it rarely helps and adds huge complexity.

## Measure everything (the portfolio-defining part)

- Feed **5M+ orders** with a realistic distribution (or replay real Binance/ITCH data).
- Record a **latency histogram** — report **P50, P99, P99.9**, not just the average. HFT cares about the tail (Modules 3, 14).
- Benchmark **rung 1 vs rung 3** on the same data and hardware. Your headline: *"Replaced the tree with a price-indexed array + bitset, eliminating the AVL-rebalance cost; P99 latency dropped from X to Y."*
- Profile (perf, VTune) to *show where the time went* — the self-diagnosis skill that separates candidates.

## The full module map, applied

| Module | Where it shows up |
|--------|-------------------|
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

## Build order (do it in this sequence)

1. Types + `OrderPool` + GoogleTest scaffold.
2. `NaiveBook` (map-based) — limit + market orders, add/cancel. **Get tests green.**
3. Add stop / stop-limit / modify. Benchmark; record baseline histogram.
4. `FastBook` (array + bitset). Re-run identical benchmark. **Capture the delta.**
5. SPSC queue + separate I/O thread (optional). Re-measure tail latency.
6. Write the README: architecture, the before/after numbers, the profiling flame graph, and *why* each choice. That README is your interview.

That's the whole thing — language fundamentals through a measured, differentiated system. Build rung 1 first; everything else compounds on top.
