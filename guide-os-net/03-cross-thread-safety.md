# Module 3 — Cross-thread safety: mutexes, condvars, races, deadlock, patterns

Your listed priority #4. Threads share memory (Module 1); this module is how to share it *correctly*. The lock-free/atomic side is in `../guide/16` — here we cover the OS-level synchronization primitives and the bugs they prevent (and cause).

## The data race — the core hazard

A **data race** = two threads access the same memory, at least one writes, with no synchronization. It's **undefined behavior** — not "you get a slightly wrong number," but *anything* (torn values, crashes, compiler assuming it can't happen and miscompiling).

```cpp
int counter = 0;
// Thread A: ++counter;   Thread B: ++counter;
// ++counter is really: load, add 1, store — three steps.
// Interleaving loses updates: both read 0, both write 1. Result: 1, not 2. Or worse (UB).
```

Fix: make the access **atomic** (`../guide/16`) or protect it with a **lock**.

## `std::mutex` — mutual exclusion

A mutex ("mutual exclusion") ensures only one thread holds it at a time. Under the hood on Linux it's built on the `futex` syscall (fast path in user space, slow path in kernel when contended — Module 1).

**Always lock via RAII (`std::lock_guard`/`std::scoped_lock`), never manual lock/unlock:**

```cpp
#include <mutex>
std::mutex m;
int counter = 0;

void inc() {
    std::scoped_lock lock{m};   // locks here; unlocks at scope exit (even on exception)
    ++counter;                  // critical section — exclusive access
}                               // unlock automatic (RAII — ../guide/07)
```

`std::scoped_lock` (C++17) is the modern default; `std::lock_guard` is the older single-mutex version. `std::unique_lock` is heavier but flexible (deferred/timed/manual unlock, needed with condition variables).

## Condition variables — waiting for a state change

A mutex protects data; a **condition variable** lets a thread *sleep until* some condition becomes true (instead of busy-waiting), and be woken by another thread. This is how a thread pool's workers wait for tasks, or a consumer waits for a queue to fill.

```cpp
#include <condition_variable>
std::mutex m;
std::condition_variable cv;
std::queue<Task> q;

void producer(Task t) {
    { std::scoped_lock lk{m}; q.push(std::move(t)); }
    cv.notify_one();   // wake one waiter
}

void consumer() {
    std::unique_lock lk{m};          // unique_lock: cv needs to unlock/relock
    cv.wait(lk, []{ return !q.empty(); });  // sleeps, releasing m; rechecks predicate on wake
    Task t = std::move(q.front()); q.pop();
    lk.unlock();
    run(t);
}
```

Two must-knows:
- **Always use the predicate form** (`cv.wait(lk, pred)`) to guard against **spurious wakeups** (a wait can return without a notify) and lost wakeups.
- `wait` atomically releases the mutex and sleeps, then re-acquires on wake — that atomicity is why a condvar needs a mutex.

## Deadlock — the classic multithreading bug

Two threads each hold a lock and wait for the other's → both stuck forever.

```cpp
// Thread 1: lock A, then lock B.   Thread 2: lock B, then lock A.  -> deadlock
```

The four **Coffman conditions** (all needed for deadlock): mutual exclusion, hold-and-wait, no preemption, circular wait. Break any one to prevent it.

Prevention techniques:
- **Lock ordering**: always acquire multiple locks in a fixed global order. Simplest, most common.
- **`std::scoped_lock` with multiple mutexes** locks them atomically without deadlock (uses a deadlock-avoidance algorithm):

```cpp
std::scoped_lock lock{mutexA, mutexB};   // locks both, deadlock-free ordering internally
```

- **Try-lock with backoff**, timeouts, or avoiding nested locks entirely.

## Other primitives (brief)

- **`std::atomic`** — lock-free single-variable operations (`../guide/16`). Faster than a mutex for simple counters/flags.
- **`std::shared_mutex`** (C++17) — reader/writer lock: many readers OR one writer. Good for read-heavy data (a config read by many, written rarely).
- **`std::latch` / `std::barrier`** (C++20) — one-shot / reusable rendezvous points ("all N threads wait here until everyone arrives").
- **`std::counting_semaphore`** (C++20) — limit concurrent access to N resources.
- **`thread_local`** — per-thread storage; no sharing → no synchronization needed (a way to *avoid* the problem).

## Best practices for cross-thread communication

1. **Prefer not sharing.** Immutable data (never written after creation) needs no locks. `thread_local` and message-passing (queues) avoid shared mutable state entirely.
2. **Minimize the critical section.** Hold locks for the shortest time; do heavy work outside the lock.
3. **One lock, one purpose.** Don't reuse a mutex for unrelated data (false contention).
4. **RAII locks always** — never manual `lock()`/`unlock()`.
5. **Prefer message-passing over shared memory** where you can — a lock-free queue handing data between threads (`../guide/16`) is often simpler and faster than shared state + locks.
6. Guard the data, and *document* which lock guards which data.

## Tradeoffs / interview "why"

- Data race = UB; mutex or atomic to fix.
- Mutex (blocks, ~µs when contended via futex) vs atomic (lock-free, ns) vs lock-free queue (no blocking) — pick by contention and latency needs.
- Condition variable: predicate form, spurious wakeups, why it needs a mutex.
- Deadlock: Coffman conditions + lock ordering / `scoped_lock`.
- `shared_mutex` for read-heavy; `thread_local`/immutability to sidestep sync.
- HFT stance: on the hot path, **avoid locks** — use single-threaded design + lock-free queues (`../guide/16`). Locks live in the cold/control plane.

## In the trading system

The matching thread shares nothing mutable (single-threaded), so it needs no locks at all — the strongest form of thread safety. Cross-thread hand-off (I/O → matcher) uses a lock-free SPSC queue. Locks/condvars appear only in the thread pool and control plane (config reload via `shared_mutex`, shutdown coordination via `stop_token`/`latch`).
