# Module 8 — Operator overloading & value semantics

Operator overloading lets your types behave like built-ins (`a + b`, `a < b`, `a == b`). Used well it makes code readable; abused it hides cost. HFT interviewers watch for both taste and cost-awareness.

## The basic idea

```cpp
struct Price {
    std::int64_t ticks;   // integer ticks, NOT double (see note below)

    // member operator: left operand is *this
    Price& operator+=(Price rhs) { ticks += rhs.ticks; return *this; }
};

// non-member (free) operator: symmetric, preferred for binary arithmetic
Price operator+(Price a, Price b) { return Price{a.ticks + b.ticks}; }
bool  operator==(Price a, Price b) { return a.ticks == b.ticks; }
bool  operator<(Price a, Price b)  { return a.ticks < b.ticks; }
```

Guidelines:
- **Compound assignment (`+=`) as a member; binary arithmetic (`+`) as a free function** implemented in terms of `+=`. Free functions allow conversions on the left operand too.
- Return by value for arithmetic; return `*this` by reference for assignment.
- Overload only when the meaning is *obvious*. `operator+` on two prices = clear. Cute overloads = confusing.

## The spaceship operator `<=>` (C++20)

Before C++20 you wrote all six comparisons (`< > <= >= == !=`) by hand. Now:

```cpp
#include <compare>
struct Price {
    std::int64_t ticks;
    auto operator<=>(const Price&) const = default;  // generates < <= > >= automatically
    bool operator==(const Price&) const = default;   // == generated separately
};
// Now Price supports all comparisons, and works in std::map / std::sort out of the box.
```

`<=>` returns an ordering (`std::strong_ordering` etc.). `= default` does memberwise comparison. Huge boilerplate saver for value types — and order books compare prices constantly.

## Value semantics

A type has **value semantics** when copying it produces an independent, equal object (like `int`): `b = a; b.x = 5;` doesn't change `a`. Contrast with **reference semantics** (Java objects, pointers) where `b = a` makes two names for one object.

C++ defaults to value semantics. This is why copy/move (Module 7) matter — copying a value type must duplicate its state. Value types are:
- Easy to reason about (no aliasing surprises).
- Safe to pass around and store.
- Potentially expensive to copy (hence move semantics and `const&`).

Small value types (`Price`, `Qty`, `OrderId`) should be cheap, `constexpr`-friendly, and passed **by value** (they fit in a register or two). Big ones (`std::vector`) pass by `const&`.

## `operator[]`, `operator()`, conversions

```cpp
class Book {
    std::vector<Limit> levels_;
public:
    Limit& operator[](std::size_t i) { return levels_[i]; }             // indexing
    const Limit& operator[](std::size_t i) const { return levels_[i]; } // const overload
};
```

Provide `const` and non-`const` overloads of `operator[]` so it works on both const and mutable objects.

`operator()` makes a **functor** (callable object) — used heavily with STL algorithms and as a faster alternative to `std::function`:

```cpp
struct ByPrice {
    bool operator()(const Order& a, const Order& b) const { return a.price < b.price; }
};
std::sort(v.begin(), v.end(), ByPrice{});  // functor: inlinable, zero indirection
```

## Strong typedefs — a quant-relevant pattern

Don't pass raw `int64_t` everywhere; wrap domain concepts so the compiler catches mix-ups:

```cpp
struct OrderId { std::uint64_t v; auto operator<=>(const OrderId&) const = default; };
struct Qty     { std::uint32_t v; };
// void cancel(OrderId id);  cancel(qty);  // now a COMPILE error — types don't match
```

This prevents "passed quantity where an ID was expected" bugs at compile time, for free.

## Tradeoffs / interview "why"

- Overloading improves readability but can hide expensive operations — never overload an operator to do something surprising or costly-looking-cheap.
- `<=>` and `= default` comparisons are C++20 wins; mention them.
- Value semantics + move semantics is *the* C++ design point; contrast with reference-semantic/GC languages.
- **Never represent price/money as `double`** — floating point can't represent 0.10 exactly, and rounding errors are unacceptable in finance. Use integer ticks/cents. This is a classic quant-interview filter.

## In the order book

`Price`, `Qty`, `OrderId` are strong value types with `<=>`. Price comparison drives the AVL tree / price-array ordering. A `ByPrice` functor orders resting orders. Integer-tick prices avoid float error and make prices usable as array indices (Module 15).
