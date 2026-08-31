# Module 12 — The STL: containers, iterators, algorithms, complexity & cache

The Standard Template Library gives you battle-tested containers and algorithms. For HFT the key skill isn't *using* them — it's knowing each one's **complexity AND its cache behavior**, because Big-O lies about real-world speed.

## Containers and their tradeoffs

| Container | Layout | Lookup | Insert | Cache behavior | HFT verdict |
|-----------|--------|--------|--------|----------------|-------------|
| `std::vector` | contiguous | O(n) scan / O(1) index | O(1) amortized end | **excellent** | default choice |
| `std::array<T,N>` | contiguous, fixed | O(1) index | — (fixed) | excellent | when size known |
| `std::deque` | chunked | O(1) index | O(1) both ends | good | queues |
| `std::list` | linked nodes | O(n) | O(1) at a known position | **terrible** (pointer chasing) | rarely — but see below |
| `std::map` | red-black tree | O(log n) | O(log n) | **poor** (node-per-element, scattered) | avoid on hot path |
| `std::unordered_map` | hash + buckets | O(1) avg | O(1) avg | mediocre (bucket indirection) | ID lookups, carefully |
| `std::set` | red-black tree | O(log n) | O(log n) | poor | avoid on hot path |

### The vector-beats-list truth

`std::list` has O(1) insertion, `std::vector` has O(n) insertion-in-middle. Yet **vector usually wins even for middle inserts** up to surprisingly large n, because:
- Vector elements are contiguous → the CPU prefetcher streams them; near-zero cache misses.
- List nodes are scattered heap allocations → every hop is a potential cache miss (~100 ns).

Big-O counts operations; it ignores that one cache miss ≈ 100 arithmetic ops. **"Prefer `std::vector` by default; justify anything else"** is the correct HFT instinct. (This is the famous Stroustrup/Bjarne vector-vs-list result — good interview talking point.)

## Iterators

The glue between containers and algorithms — a generalized pointer:

```cpp
std::vector<int> v{3, 1, 2};
for (auto it = v.begin(); it != v.end(); ++it) std::cout << *it;
for (int x : v) std::cout << x;                 // range-for (preferred)
```

Iterator categories (input, forward, bidirectional, random-access) determine which algorithms work. `vector`/`array` give **random-access** iterators (fastest, enable `std::sort`); `list` gives only bidirectional.

**Invalidation** (critical bug source): modifying a container can invalidate iterators/pointers into it. `vector::push_back` that triggers reallocation invalidates *all* iterators and pointers. `std::map`/`list` keep node pointers valid across inserts (a reason they still exist).

## Algorithms

Prefer standard algorithms over hand-written loops — clearer, correct, often optimized:

```cpp
#include <algorithm>
#include <numeric>
std::sort(v.begin(), v.end());                              // O(n log n)
auto it = std::lower_bound(v.begin(), v.end(), key);        // O(log n) on sorted range
int sum = std::accumulate(v.begin(), v.end(), 0);
auto n  = std::count_if(v.begin(), v.end(), [](int x){ return x > 0; });
v.erase(std::remove_if(v.begin(), v.end(), pred), v.end()); // erase-remove idiom
```

C++20 **ranges** clean up the `begin()/end()` noise (Module 18):

```cpp
std::ranges::sort(v);
auto evens = v | std::views::filter([](int x){ return x % 2 == 0; });  // lazy view
```

## Lambdas (essential companion to algorithms)

```cpp
int threshold = 100;
auto expensive = [threshold](const Order& o) { return o.value() > threshold; };
//                ^capture      ^params
std::count_if(orders.begin(), orders.end(), expensive);
```

- `[=]` captures by copy, `[&]` by reference, `[threshold]` names specifics. Prefer explicit captures.
- A lambda is a compiler-generated functor → **inlinable, zero overhead**, unlike `std::function` (which type-erases and may allocate + adds an indirect call). Use lambdas/functors on hot paths; `std::function` only when you genuinely need type erasure.

## `reserve` — the free vector win

```cpp
std::vector<Trade> trades;
trades.reserve(10000);   // pre-allocate; avoids repeated realloc + element moves
```

If you know (or can estimate) the size, `reserve` eliminates growth reallocations — pure latency win, no downside.

## Tradeoffs / interview "why"

- Know each container's complexity *and* cache profile. "Why is vector faster than list despite O(n) inserts?" is a top-5 HFT interview question — answer: cache locality + prefetching.
- `map` (tree) vs `unordered_map` (hash): ordered iteration + O(log n) vs O(1) average but no ordering and hash/bucket cost. The order book needs *ordering* by price → tree-like, which is exactly why the flat-array optimization (Module 15) beats `std::map`.
- Iterator invalidation rules per container.
- Lambdas/functors inline; `std::function` doesn't — cost awareness.
- `reserve` when size is known.

## In the order book

- Orders within a price level: a `std::list`-like **intrusive** doubly-linked list (nodes embedded in the pooled `Order`, not separately allocated) — O(1) cancel, and pooling removes the usual list cache penalty.
- Order-ID → order: `unordered_map` (or a custom open-addressing map) for O(1) cancel/modify lookup.
- Price levels: `std::map` in the naive version (rung 1), replaced by a flat `std::vector`/array indexed by price ticks in the optimized version (rung 3) — the cache win that beats the tree.
