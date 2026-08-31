# Module 14 — Exceptions, `noexcept`, error handling without exceptions

How you signal and handle failure. General C++ uses exceptions; HFT hot paths often ban them. You must know both and articulate the tradeoff.

## Exceptions: the mechanism

```cpp
double divide(double a, double b) {
    if (b == 0) throw std::runtime_error("divide by zero");
    return a / b;
}

try {
    auto r = divide(x, 0);
} catch (const std::exception& e) {   // catch by const reference (avoids slicing/copy)
    std::cerr << e.what();
}
```

- `throw` unwinds the stack, destroying every local along the way (**RAII cleanup runs** — Module 7 — which is why exceptions are *safe* in well-written C++).
- `catch (const std::exception&)` catches the whole standard hierarchy. Always catch by reference.
- Unwinding is what makes RAII and exceptions a matched pair: resources release correctly even when an exception blows through.

## Exception safety guarantees

A function offers one of these (know the vocabulary):

- **No-throw**: never throws (mark `noexcept`).
- **Strong**: if it throws, state is unchanged (commit-or-rollback). E.g. copy-and-swap (Module 7).
- **Basic**: if it throws, invariants hold and nothing leaks, but state may have changed.
- **None**: throwing may corrupt/leak. Avoid.

## `noexcept`

Declares a function won't throw:

```cpp
void f() noexcept;   // promises not to throw; if it does -> std::terminate (hard crash)
```

Why it matters beyond documentation:
- Enables optimizations (no unwinding machinery needed around it).
- **`std::vector` moves elements only if the move ctor is `noexcept`** (Module 9) — otherwise it copies. This alone makes `noexcept` on moves mandatory in practice.
- Destructors are implicitly `noexcept` — **never let a destructor throw** (throwing during unwinding → `std::terminate`).

## The cost of exceptions (why HFT hot paths ban them)

Modern "zero-cost" exception implementations (Itanium ABI, table-based):
- **When no exception is thrown: truly ~zero runtime cost** on the happy path. This is real — exceptions don't slow down normal execution.
- **When thrown: very expensive and *unbounded/unpredictable*** — table lookups, stack unwinding, destructor calls. Could be microseconds.

The HFT problem isn't the happy-path cost (there is none) — it's:
1. **Unpredictable tail latency** when one *is* thrown (kills P99.9).
2. **Binary size / i-cache**: exception tables and cleanup code bloat the binary, evicting hot code from instruction cache.
3. Some shops compile with `-fno-exceptions` entirely for determinism.

So: exceptions for *exceptional, cold* conditions (startup config error, malformed input at the boundary) — never for control flow on the hot path.

## Error handling without exceptions

**1. `std::optional<T>`** — "a value, or nothing":

```cpp
std::optional<Order*> find(OrderId id);
if (auto o = find(id)) { (*o)->cancel(); }   // present
else { /* not found */ }
```

**2. `std::expected<T, E>` (C++23)** — "a value, or an error" (the modern Result type):

```cpp
std::expected<Order, ErrorCode> parse(std::string_view msg);
auto r = parse(buf);
if (r) use(*r); else handle(r.error());
```

**3. Error codes / status enums** — the classic, fully predictable path:

```cpp
enum class Status { Ok, Rejected, Unknown };
Status addOrder(const Order& o) noexcept;   // no throw, caller checks
```

**4. `std::error_code`** — for system/library errors without throwing.

## Tradeoffs / interview "why"

- Exceptions: clean happy path, RAII-safe, ~zero cost when not thrown — but unpredictable when thrown, and bloat the binary. Great for cold error paths.
- Error codes/`optional`/`expected`: predictable, no unwinding, but verbose and easy to ignore a return value. The hot-path choice.
- `noexcept` on moves and destructors is non-negotiable.
- Interview framing: "I'd use exceptions for exceptional conditions off the hot path, and status codes / `expected` on the matching path for deterministic latency." That sentence signals you understand *both* the idiom and the domain.

## In the order book

- Matching path: **no exceptions**. `addLimitOrder` returns a status/`enum` (`Ok`, `Rejected`, `Filled`). All hot-path functions `noexcept`.
- Cold path (parsing a malformed input message, bad config at startup): exceptions are fine and clearer.
- `std::optional<Order*>` for "find order by ID" (may be absent). This split — exceptions cold, codes hot — is exactly what you'd defend in an interview.
