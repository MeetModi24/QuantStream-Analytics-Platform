# Module 16 — `std::atomic`, memory ordering, lock-free basics

Concurrency without locks. This is "table stakes for HFT" (your research doc's words) and the hardest interview territory. Goal here: correct mental model + the SPSC queue you'll actually build.

## Why not just use a mutex?

A `std::mutex` is correct and simple, but:
- Locking/unlocking has overhead; contention causes threads to **block** (OS deschedules them → microsecond+ stalls).
- Unpredictable latency (a thread can be paused holding the lock). Poison for tail latency.

Lock-free structures let threads make progress without blocking — bounded, predictable latency. The price: they're subtle and easy to get wrong.

## `std::atomic` — indivisible operations

```cpp
#include <atomic>
std::atomic<int> counter{0};
counter.fetch_add(1);      // atomic increment — no torn reads/writes, no lost updates
int v = counter.load();
counter.store(5);
```

An atomic operation is indivisible: no other thread can observe it half-done. Without atomics, `++counter` from two threads is a **data race** = undefined behavior (not just "wrong number" — UB).

### Compare-and-swap (CAS) — the foundation of lock-free

```cpp
std::atomic<int> x{0};
int expected = 0;
// atomically: if x == expected, set x = 10 and return true; else load x into expected, return false
while (!x.compare_exchange_weak(expected, 10)) {
    // expected now holds the current value; recompute and retry
}
```

CAS is how you build lock-free algorithms: read a value, compute a new one, and *atomically* install it only if nobody changed it meanwhile. If they did, retry. This is a **CAS loop**.

## Memory ordering — the genuinely hard part

Modern CPUs and compilers **reorder** memory operations for speed. Within one thread that's invisible; across threads it means another thread can see your writes in a *different order* than you issued them. Memory ordering controls what reorderings are allowed.

```cpp
std::atomic<bool> ready{false};
int data = 0;

// Producer:
data = 42;                              // (1) plain write
ready.store(true, std::memory_order_release);  // (2) release: (1) cannot move AFTER (2)

// Consumer:
while (!ready.load(std::memory_order_acquire)) {}  // acquire: reads below can't move BEFORE
use(data);                              // guaranteed to see 42
```

The orderings, weakest to strongest:

- **`relaxed`** — atomic (no torn values) but **no ordering** guarantees vs other operations. Use for simple counters where order doesn't matter (fastest).
- **`acquire`** (on loads) / **`release`** (on stores) — the workhorse pair. A release-store *publishes* everything written before it; a matching acquire-load *sees* all of it. This is how you hand data between threads correctly.
- **`acq_rel`** — both, for read-modify-write ops.
- **`seq_cst`** (default) — sequential consistency: one global order all threads agree on. Easiest to reason about, **strongest, slowest** (may insert full memory fences).

**Rule of thumb**: use the default `seq_cst` until you understand why you'd relax it; then use `acquire`/`release` on the hot path where you've proven it correct. Relaxing ordering is a common source of "works on x86, breaks on ARM" bugs (x86 has a strong memory model that hides mistakes; ARM doesn't).

## The SPSC ring buffer (single-producer, single-consumer)

The lock-free structure you'll build — the simplest genuinely useful one. One thread pushes, one pops; no CAS needed, just acquire/release:

```cpp
template <typename T, std::size_t N>   // N a power of two
class SpscQueue {
    alignas(64) std::atomic<std::size_t> head_{0};  // consumer reads/writes — own cache line
    alignas(64) std::atomic<std::size_t> tail_{0};  // producer reads/writes — own cache line
    T buffer_[N];
public:
    bool push(const T& v) {
        auto t = tail_.load(std::memory_order_relaxed);
        auto next = (t + 1) & (N - 1);
        if (next == head_.load(std::memory_order_acquire)) return false;  // full
        buffer_[t] = v;
        tail_.store(next, std::memory_order_release);   // publish
        return true;
    }
    bool pop(T& out) {
        auto h = head_.load(std::memory_order_relaxed);
        if (h == tail_.load(std::memory_order_acquire)) return false;     // empty
        out = buffer_[h];
        head_.store((h + 1) & (N - 1), std::memory_order_release);
        return true;
    }
};
```

Notice the payoffs from earlier modules:
- `alignas(64)` on `head_`/`tail_` prevents **false sharing** (Module 15) — the classic SPSC optimization.
- Power-of-two `N` lets `& (N-1)` replace `% N` (Module 18, bit tricks).
- `relaxed` for your own index (only you write it), `acquire`/`release` to synchronize with the other thread.
- **Local index caching** (a further optimization): cache the other thread's index to avoid re-loading its atomic every call — reduces cache-coherency traffic. This is the rigtorp SPSCQueue trick.

## The ABA problem (interview keyword)

In a CAS loop, a value can change A→B→A between your read and your CAS; the CAS succeeds but the world changed underneath you. Fixes: tagged pointers (version counter), hazard pointers, or epoch-based reclamation. Know the *name* and the *idea* even if you don't implement it.

## Tradeoffs / interview "why"

- Mutex: simple, correct, but blocks → unpredictable latency. Lock-free: bounded, predictable, but subtle.
- Explain acquire/release precisely (publish/consume) — the #1 memory-ordering question.
- `relaxed` for independent counters; `seq_cst` when unsure; `acquire`/`release` on proven hot paths.
- False sharing on the queue indices; power-of-two masking; local index caching.
- ABA problem by name.
- x86 strong vs ARM weak memory model (why testing on one isn't enough).

## In the order book

An `SpscQueue<Message, N>` decouples the network/input thread (producer) from the matching thread (consumer): the input thread parses messages and pushes; the matching thread pops and processes — no lock, no blocking, predictable latency. The matching engine itself is single-threaded (simpler and often *faster* than sharing the book across cores), fed by these lock-free queues.
