# Module 15 — Memory model, cache, alignment, false sharing

This is where "systems programming" becomes literal: you optimize for how the CPU *physically* moves data. In HFT, the difference between an L1 hit and a main-memory miss (~1 ns vs ~100 ns) is the whole game.

## The memory hierarchy

| Level | Latency (approx) | Size |
|-------|------------------|------|
| Register | ~0 | dozens |
| L1 cache | ~1 ns (~4 cycles) | 32–64 KB |
| L2 cache | ~4 ns (~12 cycles) | 256 KB–1 MB |
| L3 cache | ~15 ns (~40 cycles) | several–tens MB |
| Main memory (RAM) | ~100 ns (~200+ cycles) | GBs |

A main-memory access costs ~100× an L1 hit — roughly the time of 100+ arithmetic instructions. **Your code's speed is dominated by cache hits/misses, not instruction count.** This reframes optimization entirely.

## Cache lines — the unit of transfer

The CPU never loads one byte; it loads a **cache line** (64 bytes on x86) containing it. Consequences:

- **Spatial locality wins**: data used together should sit together. Accessing `arr[i]` pulls in `arr[i+1..]` for free.
- **Sequential access is fast**: the hardware **prefetcher** detects linear scans and loads lines ahead of you → near-zero misses walking an array.
- This is *the* reason `std::vector` beats `std::list` (Module 12) and a flat price array beats `std::map`: contiguous data streams through cache; scattered nodes miss on every hop.

## Data-oriented design: struct layout matters

```cpp
struct Bad {
    char  a;      // 1 byte
    double b;     // 8 bytes — needs 8-byte alignment
    char  c;      // 1 byte
};  // sizeof likely 24: padding inserted after a and c for alignment

struct Good {
    double b;     // 8
    char  a;      // 1
    char  c;      // 1
};  // sizeof 16: less padding — order members largest-to-smallest
```

**Alignment**: each type must sit at an address that's a multiple of its size; the compiler inserts **padding** to enforce it. Reordering members largest→smallest minimizes padding → smaller objects → more fit per cache line → fewer misses. `sizeof`/`alignof` tell you the layout; `alignas` controls it.

### SoA vs AoS

- **Array of Structs (AoS)**: `struct P{double x,y,z;}; P pts[N];` — good when you use all fields together.
- **Struct of Arrays (SoA)**: `double xs[N], ys[N], zs[N];` — good when you process one field across all elements (only that field's cache lines load). SIMD-friendly.

Choose based on access pattern. Iterating just `price` over a million orders? SoA loads only prices, not the whole order.

## Hot/cold splitting

Keep frequently-accessed ("hot") fields together and separate from rarely-touched ("cold") fields, so hot data packs densely into cache:

```cpp
struct Order {
    // hot: touched every match
    std::int64_t price; std::uint32_t qty; std::uint32_t next;
    // cold: rarely touched — consider moving to a side array
    // std::uint64_t timestamp; char clientTag[16];
};
```

## False sharing — the multicore trap

Two threads writing *different* variables that happen to share one cache line will **ping-pong that line between cores' caches**, each write invalidating the other's copy. Performance collapses even though there's no logical sharing.

```cpp
struct Counters {
    std::atomic<int> a;   // thread 1 writes this
    std::atomic<int> b;   // thread 2 writes this
};  // a and b likely share a 64-byte line -> false sharing, cores fight
```

Fix: pad/align so each lands on its own line:

```cpp
struct Counters {
    alignas(64) std::atomic<int> a;   // own cache line
    alignas(64) std::atomic<int> b;   // own cache line
};
// C++17: std::hardware_destructive_interference_size == the padding to use
```

This is a top-tier HFT interview topic and a real bug in the SPSC-queue projects your research doc lists.

## Branch prediction & prefetching (brief)

- The CPU speculatively executes the *predicted* branch; a **misprediction** flushes the pipeline (~15–20 cycles). Predictable branches (loops, rare error checks) are cheap; data-dependent random branches are costly. `[[likely]]`/`[[unlikely]]` (C++20) hint the compiler.
- `__builtin_prefetch` (or `std::assume_aligned`) can hint upcoming accesses, but measure — the hardware prefetcher is already good at linear patterns.

## Tradeoffs / interview "why"

- Optimize for cache misses, not instruction count — the 100× gap dominates.
- Struct layout, alignment, padding, SoA/AoS, hot/cold splitting are your levers.
- False sharing: explain it precisely and fix with `alignas(64)` / `hardware_destructive_interference_size`.
- Everything here is invisible in the language spec and visible only in benchmarks — which is why "show measurements" matters so much.

## In the order book

- `Order` is a compact, alignment-optimized struct; hot fields (price, qty, link index) packed, cold fields (timestamps, tags) split out.
- Price levels in a **contiguous array indexed by price ticks** → sequential, prefetcher-friendly, no pointer chasing. This is the single biggest win over the `std::map`/AVL design (recall brprojects' own finding that AVL rebalancing dominated latency).
- 32-bit indices instead of 64-bit pointers for links → two links fit where one pointer did, denser cache lines.
- If multithreaded, producer/consumer counters get `alignas(64)` to kill false sharing.
