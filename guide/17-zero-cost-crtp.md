# Module 17 — Zero-cost abstraction, CRTP, branch elimination

The C++ promise: "you don't pay for what you don't use, and what you do use, you couldn't hand-code better." This module is the toolkit for abstraction *without* runtime cost — the thing that lets HFT code be both clean and fast.

## What "zero-cost abstraction" means

A high-level construct compiles to the same machine code you'd write by hand. Examples: a range-for loop compiles to the same as an index loop; a lambda passed to `std::sort` inlines to the same as an inline comparison; `unique_ptr` is the same size and speed as a raw pointer. The abstraction exists only at compile time.

The enemies of zero-cost are **runtime indirection**: virtual calls (Module 11), `std::function`, and unpredictable branches. This module removes them.

## CRTP — the Curiously Recurring Template Pattern

Static (compile-time) polymorphism that looks like inheritance but has **no vtable, no indirect call, full inlining**:

```cpp
template <typename Derived>
class Strategy {
public:
    void run() {
        // dispatch to derived WITHOUT a virtual call:
        static_cast<Derived*>(this)->onTick();
    }
};

class MomentumStrategy : public Strategy<MomentumStrategy> {
public:
    void onTick() { /* ... */ }   // no 'virtual', no 'override'
};

MomentumStrategy s;
s.run();   // Strategy::run inlines the static_cast + onTick — resolved at compile time
```

Compare with virtual dispatch:

| | Virtual functions | CRTP |
|-|-------------------|------|
| Dispatch | runtime (vtable) | compile time |
| Inlinable | no | yes |
| Object overhead | +8 bytes (vptr) | none |
| Flexibility | new types at runtime | types fixed at compile time |
| Use when | cold path, plugins | hot path, known types |

CRTP uses: static interfaces, mixins (add behavior to many classes), and expression templates. Cost: types must be known at compile time (no runtime plugin loading), and it's harder to read.

## Branch elimination via templates

A branch checked millions of times can be lifted to compile time. Example — a book side that's always buy or sell:

```cpp
enum class Side { Buy, Sell };

// Runtime branch — checked on EVERY call:
bool crosses(Side s, std::int64_t restPrice, std::int64_t incoming) {
    return (s == Side::Buy) ? incoming <= restPrice : incoming >= restPrice;
}

// Compile-time — the branch DISAPPEARS; two specialized functions generated:
template <Side S>
bool crosses(std::int64_t restPrice, std::int64_t incoming) {
    if constexpr (S == Side::Buy) return incoming <= restPrice;
    else                          return incoming >= restPrice;
}
crosses<Side::Buy>(rest, in);   // no branch at runtime — compiler picked the path
```

`if constexpr` (Module 10) discards the untaken branch at compile time. Your research doc calls this "side-specialized templates (compile-time branch elimination)" — this is exactly it, and it's a real, citable optimization.

## `[[likely]]` / `[[unlikely]]` (C++20)

When a branch *must* stay at runtime, hint its probability so the compiler lays out the hot path linearly (fewer i-cache misses, better prediction):

```cpp
if (order.qty > 0) [[likely]] { match(order); }
else               [[unlikely]] { reject(order); }
```

## `inline`, `__attribute__((always_inline))`, and `constexpr`

- `inline` today mostly means "this definition can appear in multiple translation units" (ODR), and is a *hint* to inline. The optimizer decides.
- Force it (rarely, and measure) with `[[gnu::always_inline]]` for tiny hot functions.
- `constexpr` (Module 5) moves computation to compile time entirely — the ultimate zero-cost.

## Tag dispatch / policy-based design (brief)

Select behavior at compile time via types (policies) instead of runtime flags — the STL allocator model. `std::vector<T, MyAllocator>` swaps allocation strategy with zero runtime cost. In HFT you might template a book on its allocation/matching policy.

## Tradeoffs / interview "why"

- Zero-cost = abstraction resolved at compile time; the enemies are virtual calls, `std::function`, unpredictable branches.
- CRTP vs virtual: same "polymorphism" shape, compile-time vs runtime — know the table above.
- Compile-time branch elimination via templates + `if constexpr`.
- Cost of these: compile time, code bloat (one instantiation per type), reduced runtime flexibility, harder-to-read code. Use them on hot paths, not everywhere — premature templating hurts maintainability.

## In the order book

- `BookSide<Side::Buy>` / `BookSide<Side::Sell>` — side is a template parameter, so the buy/sell comparison branch is eliminated from the matching loop.
- The matching engine avoids `std::function` for callbacks (trade reporting) — uses templates/lambdas so they inline.
- `[[likely]]` on the common "order rests / partially fills" path, `[[unlikely]]` on rejects. These are small but genuine, defensible optimizations that show cost-awareness in an interview.
