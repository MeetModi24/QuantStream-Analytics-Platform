# Module 11 — Inheritance, virtual functions, vtables — and when NOT to use them

Runtime polymorphism is a core OOP tool — and in HFT hot paths it's often the *wrong* tool. You must know how it works *and* why low-latency code avoids it.

## Inheritance basics

```cpp
class Order {
public:
    virtual ~Order() = default;          // virtual dtor — REQUIRED for base classes
    virtual double value() const = 0;    // pure virtual -> Order is abstract
};

class LimitOrder : public Order {
    double price_; std::uint32_t qty_;
public:
    double value() const override { return price_ * qty_; }  // override keyword
};
```

- `: public Order` = "is-a" relationship (`LimitOrder` is an `Order`).
- `virtual` enables **dynamic dispatch**: the call resolves to the *actual* runtime type.
- `override` (always use it) makes the compiler verify you're actually overriding — catches signature typos.
- `= 0` makes a method **pure virtual**, the class **abstract** (can't instantiate).

## Dynamic dispatch and the vtable

```cpp
void process(const Order& o) {
    o.value();   // which value()? Decided at RUNTIME based on the real object.
}
process(LimitOrder{...});  // calls LimitOrder::value()
```

How it works: each polymorphic type has a **vtable** (array of function pointers). Each object holds a hidden **vptr** to its class's vtable. A virtual call:
1. Follows the object's vptr to the vtable (a memory load).
2. Loads the function pointer from the right slot (another load).
3. Calls through the pointer (an *indirect* call).

## Why HFT hot paths avoid virtual functions

That machinery has real costs at nanosecond scale:

1. **Indirect call** — the CPU can't inline it (target unknown at compile time), and can mispredict the branch → pipeline stall (~15–20 cycles on a miss).
2. **No inlining** — kills the biggest optimization; the tiny `value()` can't be folded into the caller.
3. **Extra memory loads** — vptr + vtable lookups, and the vptr bloats every object by 8 bytes (hurts cache density).
4. **Cache pressure** — vtables are elsewhere in memory; polymorphic object arrays scatter.

For a function called millions of times/sec, this is the difference between hitting your latency target and missing it.

## The alternatives HFT reaches for

**1. Templates (compile-time polymorphism)** — Module 10. Resolved at compile time, fully inlinable, zero indirection. First choice when types are known at compile time.

**2. CRTP (static polymorphism)** — Module 17. Inheritance-shaped code with compile-time dispatch:

```cpp
template <typename Derived>
struct Strategy {
    void run() { static_cast<Derived*>(this)->runImpl(); }  // dispatch, no vtable
};
```

**3. `std::variant` + `std::visit`** — a closed set of types with type-safe dispatch, often faster than virtual and cache-friendlier:

```cpp
using AnyOrder = std::variant<LimitOrder, MarketOrder, StopOrder>;
std::visit([](auto& o){ o.value(); }, anyOrder);  // no heap, no vptr
```

**4. Tagged unions / enums + switch** — an `enum` field + `switch`. Old-school, branch-predictable, cache-dense — common in matching engines.

```cpp
enum class Type : std::uint8_t { Limit, Market, Stop };
switch (o.type) { case Type::Limit: /*...*/ break; /*...*/ }
```

## When inheritance/virtual IS fine

- **Cold paths**: setup, configuration, logging, admin — called rarely, clarity wins.
- **Plugin-style extensibility** where new types are added without recompiling callers.
- Small, stable interfaces where the indirection cost is irrelevant to throughput.

Don't cargo-cult "virtual is slow" — use it freely off the hot path. The skill is knowing *which* path you're on.

## Slicing — a classic trap

```cpp
void take(Order o);          // by VALUE — base type
take(LimitOrder{...});       // SLICED: the LimitOrder-specific parts are chopped off
```

Polymorphism needs a **reference or pointer** (`const Order&`, `Order*`), never by-value, or you slice the object down to the base part. Interview favorite.

## Tradeoffs / interview "why"

- Explain vtable/vptr mechanics precisely — the indirect call + missed inlining + cache cost is the whole HFT answer.
- Always `override`; always a `virtual` destructor on a base you delete polymorphically (else UB).
- Prefer composition and templates over deep inheritance hierarchies ("prefer composition over inheritance").
- Know the four alternatives (template, CRTP, variant, enum+switch) and when each fits.

## In the order book

The matching hot path uses **no virtual functions**. Order type is an `enum` + `switch`, or side is a template parameter (`BookSide<Side::Buy>`). Any polymorphism (e.g. pluggable strategies in a backtester) lives in the cold configuration layer, not the matching loop. Being able to justify that split is a strong interview signal.
