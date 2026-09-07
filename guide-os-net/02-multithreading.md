# Module 2 — Multithreading: lifecycle, `std::thread`/`jthread`, pools, parallelism vs concurrency

Your listed priority #3. Here we cover threads as OS objects and their C++ API; Module 3 covers making shared data safe.

## Concurrency vs parallelism (get this right first)

- **Concurrency** — *dealing with* multiple things at once (structure): tasks interleave, may run on one core via time-slicing. About *composition*.
- **Parallelism** — *doing* multiple things at once (execution): tasks literally run simultaneously on multiple cores. About *speedup*.

You can be concurrent without being parallel (one core, time-sliced) and parallel is a subset of concurrent execution. Interview one-liner: *"Concurrency is about structure, parallelism is about execution."* (Rob Pike.)

## A thread is an OS-scheduled execution context

Creating a thread asks the OS for a new schedulable entity sharing your address space (Module 1). It has its own stack (~1–8 MB reserved) and registers. Creation isn't free (~10–100 µs + memory) — which is why you use **pools** (below) rather than spawning per task.

## `std::thread` — the basics

```cpp
#include <thread>

void work(int id) { /* ... */ }

std::thread t{work, 42};   // starts running immediately
// ... main continues in parallel ...
t.join();                  // wait for t to finish (blocks caller)
// OR: t.detach();         // let it run independently (you lose the handle)
```

**Critical rule:** a `std::thread` must be **joined or detached before it's destroyed**, or its destructor calls `std::terminate()` (crash). This is a classic bug.

### `std::jthread` (C++20) — RAII for threads

```cpp
#include <thread>
{
    std::jthread t{work, 42};   // "joining thread"
}   // t's destructor AUTOMATICALLY joins — no manual join(), no terminate() risk
```

`jthread` also supports cooperative cancellation via `std::stop_token`:

```cpp
std::jthread t{[](std::stop_token st){
    while (!st.stop_requested()) { /* loop */ }
}};
// t.request_stop();  // or automatic on destruction
```

Prefer `jthread` — it's the Rule-of-Zero (`../guide/07`) applied to threads.

## Passing data to threads (a gotcha)

Arguments are **copied by default**. To share, pass a pointer or `std::ref`:

```cpp
int counter = 0;
std::thread t{[&counter]{ ++counter; }};   // capture by reference — shared (needs sync!)
std::thread t2{work, std::ref(counter)};   // std::ref to pass a reference to a fn
```

The moment two threads touch `counter`, you're in Module 3 territory (data race).

## Returning results: `std::future` / `std::async`

For "run this and give me the result later" without manual synchronization:

```cpp
#include <future>
std::future<int> f = std::async(std::launch::async, []{ return compute(); });
// ... do other work ...
int result = f.get();   // blocks until ready, returns the value (or rethrows its exception)
```

`std::promise`/`std::future` is the lower-level pair for handing one value between threads.

## Thread pools — the production pattern

Spawning a thread per task is wasteful (creation cost, and more threads than cores just adds context-switch overhead). A **thread pool** creates N worker threads once (usually N ≈ hardware core count) and feeds them tasks from a queue:

```
[task queue] ──> worker 1
             ──> worker 2      (N workers, created once, reused)
             ──> worker N
```

- Workers loop: pop a task (blocking on a condition variable when empty — Module 3), run it, repeat.
- `std::hardware_concurrency()` gives a hint for N.
- C++ has no standard thread pool yet; you write one (a great portfolio exercise) or use one from a library / the parallel STL (`std::execution::par`).

**HFT nuance:** the matching engine is usually **single-threaded** (`../guide/16` explains why — no lock contention, cache-hot, deterministic). Thread pools appear in the *surrounding* work: parsing, analytics, logging, backtesting across instruments in parallel.

## Thread lifecycle summary

```
created → runnable → running ⇄ blocked (waiting on I/O, lock, condvar) → terminated → joined
```

The OS moves threads between runnable/running (scheduler) and blocked (waiting on a resource). A blocked thread consumes no CPU but costs a context switch to wake.

## Tradeoffs / interview "why"

- Concurrency vs parallelism definition.
- `join`/`detach` rule and why `jthread` is safer (RAII).
- Args copied by default; `std::ref` / capture-by-ref to share (and then you need sync).
- Thread pool rationale: amortize creation, bound thread count to cores, avoid context-switch thrash.
- More threads than cores ≠ faster — often slower (oversubscription).
- When to *not* multithread: if the work is inherently serial or the sync cost exceeds the parallel gain (Amdahl's law: speedup is capped by the serial fraction).

## In the trading system

- Matching engine: one pinned thread (`../guide/16`).
- I/O thread(s): parse network data, hand off via a lock-free SPSC queue (`../guide/16`).
- Thread pool: parallel backtests, per-symbol analytics, non-latency-critical batch work.
- All hot threads are `jthread`s pinned to isolated cores (Module 8).
