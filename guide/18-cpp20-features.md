# Module 18 — C++20 features that matter (for HFT and this project)

Your resume says **C++20** — you must be able to name features you *actually used* and why (Module notes earlier: "I set the flag" won't survive a follow-up). Here are the C++20 additions that matter for systems/HFT work, with the ones directly usable in the order book flagged.

## 1. Concepts (★ used in project)

Named, enforceable template constraints (full treatment in Module 10):

```cpp
template <std::integral T> T next(T x) { return x + 1; }

template <typename T>
concept PriceLike = std::totally_ordered<T> && std::is_trivially_copyable_v<T>;
```

Why HFT cares: readable template errors, self-documenting constraints, and you can constrain `Price`/`Qty` to be trivially-copyable and ordered — catching misuse at compile time.

## 2. `<bit>` header (★ used in project)

Standard, portable bit manipulation — replaces compiler intrinsics (`__builtin_ctz`) and hand-rolled tricks:

```cpp
#include <bit>
std::countr_zero(x);   // count trailing zeros (ctz) — find lowest set bit
std::countl_zero(x);   // count leading zeros  (clz) — find highest set bit
std::popcount(x);      // number of set bits
std::bit_width(x);     // bits needed to represent x
std::has_single_bit(x);// is power of two?
std::bit_ceil(x);      // round up to power of two
```

**Directly relevant**: a price-level **bitset** where bit *i* = "price level *i* has orders." Finding the best bid/ask becomes a single `countr_zero`/`countl_zero` — an O(1) hardware instruction instead of a tree traversal. This is the core of the "27M orders/sec" design in your research doc.

```cpp
// best ask = lowest occupied price level:
std::uint64_t occupied = /* bitmask of levels with orders */;
int bestLevel = std::countr_zero(occupied);   // one instruction
```

## 3. `std::span` (★ usable in project)

A non-owning view over a contiguous sequence (pointer + length) — pass array slices with zero copy and no template noise:

```cpp
#include <span>
void process(std::span<const Order> orders) {   // works for vector, array, C array
    for (const auto& o : orders) { /* ... */ }
}
```

Replaces `(pointer, length)` parameter pairs safely. Great for zero-copy parsing (view into a receive buffer) — a feed-handler staple.

## 4. Ranges

Composable, lazy algorithms — cleaner than iterator pairs:

```cpp
#include <ranges>
namespace rng = std::ranges;
rng::sort(v);                                   // no v.begin(), v.end()
auto bids = book | std::views::filter(isBid) | std::views::take(10);  // lazy, no allocation
```

Views are **lazy** (compute on iteration, no intermediate containers) and often zero-overhead. Nice for analytics/reporting layers; be measured about using them on the hottest path (compiler support/inlining has matured but verify).

## 5. Three-way comparison `<=>` (★ used in project)

The spaceship operator (Module 8) — one line generates all comparisons:

```cpp
struct Price { std::int64_t ticks; auto operator<=>(const Price&) const = default; };
```

Directly used for ordering prices in the book.

## 6. `constinit` / expanded `constexpr` (★ usable)

`constinit` guarantees compile-time static init (avoids the static-init-order fiasco); C++20 broadened what `constexpr` can do (including `constexpr` allocation, `std::vector` in constexpr). Use for compile-time lookup tables/masks.

## 7. `[[likely]]` / `[[unlikely]]` (★ usable)

Branch-probability hints (Module 17) for hot-path layout.

## 8. Designated initializers

```cpp
Order o{ .id = 1, .price = 100, .qty = 5 };   // named, readable aggregate init
```

## 9. Coroutines (know of them, likely not in this project)

Language-level `co_await`/`co_yield`/`co_return` for async code (network I/O, generators). Powerful but with allocation subtleties; more relevant to async feed handlers than to a synchronous matching engine. Mention you know they exist and where they'd fit.

## 10. Others worth naming

- `std::jthread` — a joining, cooperatively-cancellable thread (auto-joins in destructor — RAII for threads).
- Modules (the `import` system) — faster builds; toolchain support still maturing.
- `consteval` (Module 5) — immediate functions.
- Calendar/timezone additions to `<chrono>` — useful for timestamping.

## Tradeoffs / interview "why"

- Don't claim "C++20" generically — name the *specific* features you used: concepts to constrain price types, `<bit>` for O(1) best-price via bitset, `<=>` for price ordering, `std::span` for zero-copy views, `[[likely]]` on the hot path.
- Some features (ranges on the hottest loop, modules, coroutines) have maturity/perf caveats — showing you know *when not to use* them is as strong as using them.

## In the order book (your defensible C++20 story)

> "I used C++20 **concepts** to constrain the price/quantity value types, `<bit>`'s `countr_zero`/`countl_zero` on a price-level **bitset** to find best bid/ask in a single instruction instead of a tree traversal, `operator<=>` to order prices, `std::span` for zero-copy views into the input buffer, and `[[likely]]`/`[[unlikely]]` on the matching path."

That sentence turns the "C++20" on your resume from a flag into a defensible claim.
