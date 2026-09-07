# Module 5 — Cache coherency: MESI, false sharing, cache-friendly code

Your listed priority #6. The C++ guide covered cache layout and false sharing from the code angle (`../guide/15`). Here we go one level down: **how multicore CPUs keep caches consistent (MESI)** — the hardware protocol that explains *why* false sharing and atomics cost what they do.

## The problem: each core has its own cache

Each core has private L1/L2 caches. If core 0 and core 1 both cache the same memory line and core 0 writes it, core 1's copy is now **stale**. Something must keep them consistent — that's **cache coherency**, and the hardware handles it via a coherency protocol (MESI and variants).

Coherency operates on **cache lines** (64 bytes), not individual bytes — this is the root of false sharing.

## MESI — the coherency protocol

Every cache line, in every core's cache, is in one of four states:

| State | Meaning |
|-------|---------|
| **M**odified | This core has the only copy, and it's dirty (differs from RAM). Must write back before others read. |
| **E**xclusive | This core has the only copy, and it's clean (matches RAM). |
| **S**hared | Multiple cores have this line, all clean/identical. Read-only-ish. |
| **I**nvalid | This copy is stale/unusable; must re-fetch. |

Transitions (the part that costs latency):
- Core reads a line no one has → loads it **E**xclusive (or **S** if others hold it).
- **A core writes a line** → it must become **M**odified, which requires **invalidating every other core's copy** (send "invalidate" messages across the interconnect; they drop to **I**). This is the expensive event.
- Another core then reads that line → the **M** owner writes back / forwards it; both may end **S**.

The takeaway: **writes to shared lines cause invalidation traffic between cores.** Reads of shared data are cheap (all **S**); writes to contended data are expensive (constant invalidation).

## Why atomics and locks cost what they do

An atomic RMW (e.g. `fetch_add`) or a `compare_exchange` must get the line into **M** state exclusively — invalidating other cores — and often inserts a memory fence. That's why (`../guide/16`):
- An uncontended atomic is cheap (you already own the line).
- A **contended** atomic (many cores hammering one counter) is slow — the line ping-pongs M→I→M between cores, each transfer crossing the interconnect (~tens of ns). This is **cache-line contention**.
- A `shared_ptr` refcount (`../guide/13`) is atomic → copying one across cores causes this ping-pong. Hence "avoid `shared_ptr` on hot paths."

## False sharing — the trap MESI creates

Two threads write **different variables that share one 64-byte cache line**. Logically independent, but MESI sees one line: each write invalidates the other core's copy, so the line ping-pongs endlessly. Throughput collapses with zero logical sharing.

```cpp
struct Bad { std::atomic<long> a; std::atomic<long> b; };  // a,b likely same line
// thread 1 writes a, thread 2 writes b -> constant M/I ping-pong on that line
```

Fix — put each on its own line (`../guide/15`):

```cpp
struct Good {
    alignas(64) std::atomic<long> a;   // own cache line
    alignas(64) std::atomic<long> b;   // own cache line
};
// C++17: std::hardware_destructive_interference_size (== 64 on x86) is the portable constant
```

This is *the* classic multicore performance bug and a guaranteed interview topic. It's real in the SPSC-queue projects (the `head`/`tail` indices must be on separate lines — `../guide/16`).

## Writing cache-friendly / coherency-friendly code

1. **Don't share mutable data across cores if you can avoid it.** Read-only shared data is cheap (**S** state); read-write shared data is expensive. Single-threaded hot paths sidestep coherency entirely.
2. **Pad hot cross-thread variables to their own cache line** (`alignas(64)`) — kill false sharing.
3. **Keep data contiguous and access it sequentially** (`../guide/15`) — the prefetcher streams it, and you touch each line once.
4. **Separate read-mostly from write-heavy data** — don't let a hot counter share a line with data others read.
5. **Batch writes** to reduce invalidation frequency where possible.

## Tradeoffs / interview "why"

- Explain MESI states and the key event: **a write invalidates other cores' copies**.
- Connect it to atomics cost: contended atomic = line ping-pong across the interconnect.
- False sharing: define precisely (different vars, same line), fix with `alignas(64)` / `hardware_destructive_interference_size`.
- Read-shared (cheap, **S**) vs write-shared (expensive, **M/I** churn).
- The deep HFT point: **the cheapest coherency is no sharing** — single-threaded hot path + message-passing beats shared-memory-plus-locks not just on locks but on coherency traffic.

## In the trading system

Matching thread: single-threaded → no coherency traffic on the book at all. The one place cross-core sharing exists — the SPSC queue between I/O and matcher — has its `head`/`tail` indices `alignas(64)` on separate lines so the producer and consumer don't false-share. That single alignment decision is often the difference between a queue that scales and one that collapses under load.
