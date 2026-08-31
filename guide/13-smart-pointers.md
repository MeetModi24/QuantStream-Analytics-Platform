# Module 13 — Smart pointers & ownership

Smart pointers encode *ownership* — who is responsible for freeing an object — in the type system, so RAII (Module 7) handles deletion automatically. Ownership clarity is a design skill interviewers probe directly.

## `std::unique_ptr` — exclusive ownership

One owner; deletes automatically when it goes out of scope. Zero overhead over a raw pointer (same size, no runtime cost).

```cpp
#include <memory>
auto p = std::make_unique<Order>(id, price, qty);  // heap Order, owned by p
p->reduce(10);
// ... p goes out of scope -> Order deleted automatically. No leak.

std::unique_ptr<Order> q = std::move(p);  // ownership TRANSFERRED; p now null
// unique_ptr is move-only — can't be copied (copying would mean two owners)
```

Use `unique_ptr` as the **default** for heap ownership. `make_unique` over raw `new` (exception-safe, no explicit `delete`).

## `std::shared_ptr` — shared ownership

Reference-counted: the object lives until the *last* `shared_ptr` dies. Convenient, but not free.

```cpp
auto a = std::make_shared<Order>(...);   // refcount = 1
auto b = a;                               // refcount = 2 (copy allowed)
// object destroyed when refcount hits 0
```

**Costs (why HFT avoids it on hot paths):**
- The reference count is **atomic** (thread-safe increment/decrement) → every copy/destroy is an atomic op, which is expensive and causes cache-line contention across cores.
- Extra allocation for the control block (mitigated by `make_shared`, which fuses it with the object).
- Bigger than a raw pointer (holds two pointers).
- Obscures ownership ("everyone owns it" often means "nobody thought about lifetime").

Use `shared_ptr` only when ownership is *genuinely* shared and lifetime is unclear — not as a lazy default.

## `std::weak_ptr` — non-owning observer, breaks cycles

Two `shared_ptr`s pointing at each other never hit refcount 0 → leak. `weak_ptr` observes without owning:

```cpp
std::weak_ptr<Node> parent;         // doesn't keep the parent alive
if (auto p = parent.lock()) {        // promote to shared_ptr if still alive
    p->doThing();
}
```

## The ownership decision tree

1. **Does anything own it on the heap?** If it can live on the stack, do that (Module 1).
2. **Single owner?** → `unique_ptr`. (Almost always the answer.)
3. **Truly shared lifetime?** → `shared_ptr`. (Rare; justify it.)
4. **Observe without owning?** → `weak_ptr`, or a **raw pointer/reference as a non-owning view**.

Key modern idiom: **raw pointers and references are fine as long as they're non-owning.** A raw `T*` today means "I observe this, I don't own it." Owning raw pointers are the thing to eliminate.

```cpp
void inspect(const Order* o);   // non-owning: "I look, I don't free" — perfectly fine
std::unique_ptr<Order> owner;   // owning: this frees it
```

## `unique_ptr` with custom deleters (C API resources)

```cpp
auto file = std::unique_ptr<std::FILE, decltype(&std::fclose)>(
    std::fopen("data", "r"), &std::fclose);   // fclose called automatically
```

Wraps any C resource in RAII.

## Tradeoffs / interview "why"

- `unique_ptr` = zero-cost exclusive ownership → the default.
- `shared_ptr` = atomic refcount → real cost + contention → not for hot paths.
- `weak_ptr` breaks cycles.
- "Raw pointer = non-owning view" is the modern convention; owning raw pointers are legacy.
- Interviewers ask "unique vs shared?" to hear you default to unique and treat shared as a deliberate, justified choice.

## In the order book

- Orders come from an **object pool** (Module 3), not individual `make_unique` — even `unique_ptr`'s per-object allocation is too much on the hot path. The pool owns the storage; the book holds **non-owning raw pointers/indices** into it.
- `unique_ptr`/`shared_ptr` appear in the *cold* infrastructure (owning the pool, the config, the network session), not the matching loop.
- This is the nuance: smart pointers are the right default in general code, but the hot path goes one level lower to pooled storage + non-owning views. Knowing *when to drop below smart pointers* is the HFT-specific insight.
