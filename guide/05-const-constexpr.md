# Module 5 — `const`, `constexpr`, `consteval`, const-correctness

`const` is about *promises*; `constexpr`/`consteval` are about *when computation happens* (compile time vs runtime). HFT cares about both: promises enable optimization, and compile-time work is zero runtime cost.

## `const` — a promise not to modify

```cpp
const int max = 100;   // can't reassign
max = 200;             // ERROR
```

On variables it prevents mutation. Its real power is in **interfaces**:

```cpp
class Order {
    int qty_;
public:
    int qty() const { return qty_; }  // const member fn: promises not to modify *this
    void setQty(int q) { qty_ = q; }  // non-const: may modify
};

void audit(const Order& o) {
    o.qty();       // OK — const method callable on const ref
    // o.setQty(5); // ERROR — can't call non-const method through const ref
}
```

**const-correctness** = marking everything `const` that doesn't need to mutate. Benefits:
- The compiler catches accidental writes.
- Callers know what a function *won't* touch (crucial in concurrent code — a truly `const` object is safe to share for reads).
- Enables optimizations (the compiler can cache values it knows won't change).

Rule of thumb: **make it `const` unless it needs to change.** Parameters, methods, locals.

### `const` member functions and `mutable`

A `const` method can't modify members — except those marked `mutable` (escape hatch for caches/counters that don't affect logical state):

```cpp
class Cache {
    mutable int hits_ = 0;   // bookkeeping, not logical state
public:
    int get() const { ++hits_; return 42; }  // allowed despite const
};
```

## `constexpr` — compute at compile time when possible

```cpp
constexpr int square(int x) { return x * x; }

constexpr int a = square(5);  // computed at COMPILE time -> baked-in 25
int n = readInput();
int b = square(n);            // n unknown at compile time -> runs at RUNTIME
```

`constexpr` means "usable in a constant expression." A `constexpr` function runs at compile time *if its inputs are compile-time constants*, otherwise at runtime. `constexpr` variables must be compile-time constants.

Why it matters: lookup tables, sizes, masks, bit patterns computed at compile time cost **zero** at runtime.

```cpp
constexpr std::size_t PriceLevels = 1 << 16;  // 65536, computed at compile time
int book[PriceLevels];                          // fixed-size, no runtime sizing
```

## `consteval` (C++20) — MUST run at compile time

```cpp
consteval int forceCompileTime(int x) { return x * x; }

constexpr int ok = forceCompileTime(5);  // fine
int n = readInput();
// int bad = forceCompileTime(n);        // ERROR — n isn't a constant expression
```

`consteval` = "immediate function": every call must be evaluated at compile time. Use when a runtime call would be a bug (e.g. building a compile-time-only table).

## `constinit` (C++20) — guarantee static init at compile time

Prevents the "static initialization order fiasco" for globals by forcing constant initialization:

```cpp
constinit int g = square(10);  // guaranteed initialized at compile time, not at runtime startup
```

## `const` vs `#define`

Never use macros for constants. `const`/`constexpr` are typed, scoped, and debuggable; `#define` is blind text substitution that ignores scope and types.

## Tradeoffs / interview "why"

- `const` costs nothing at runtime; it's a compile-time contract. Free correctness + optimization hints.
- `constexpr` moves work from runtime to compile time — the ultimate "zero-cost." But it inflates compile times and only works on inputs known at compile time.
- Interview trap: `const int* p` vs `int* const p` (Module 2) — pointee-const vs pointer-const. Read declarations right-to-left.
- A `const` object being read-only makes it *thread-safe for concurrent reads without a lock* — a big deal in Module 16.

## In the order book

- Query methods (`bestBid()`, `spread()`, `qtyAt(price)`) are `const` — they don't mutate the book, so an interviewer sees you understand read/write separation.
- `constexpr` for the price-array size, tick masks, and bitset widths (Module 15) — compile-time constants, zero runtime cost.
- `const Order&` parameters throughout for cheap, safe, read-only passing.
