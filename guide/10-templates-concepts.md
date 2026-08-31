# Module 10 — Templates → Concepts

Templates are C++'s compile-time generics: write code once, the compiler stamps out a specialized version per type — with **zero runtime overhead** (no boxing, no virtual dispatch). This is the backbone of "zero-cost abstraction" (Module 17) and of the entire STL.

## Function templates

```cpp
template <typename T>
T max(T a, T b) { return (a < b) ? b : a; }

max(3, 7);        // T=int   — compiler generates max<int>
max(2.5, 1.5);    // T=double — generates max<double>
```

The compiler **instantiates** a concrete function per type used. Each is as fast as if you'd hand-written it. Contrast with runtime polymorphism (Module 11), which pays a virtual-call cost.

## Class templates

```cpp
template <typename T, std::size_t N>   // N is a NON-TYPE template parameter
class RingBuffer {
    T   data_[N];
    std::size_t head_ = 0, tail_ = 0;
public:
    bool push(const T& v) { /* ... */ return true; }
    bool pop(T& out)      { /* ... */ return true; }
};

RingBuffer<Order, 1024> queue;   // T=Order, N=1024 — size baked in at compile time
```

**Non-type template parameters** (`std::size_t N`) let the size be a compile-time constant → the buffer is a fixed inline array, no heap, no runtime size checks. This is a core HFT trick (fixed-capacity lock-free queues).

## Template specialization

Provide a custom implementation for a specific type:

```cpp
template <typename T> struct Serializer { /* generic */ };
template <> struct Serializer<Order> { /* optimized for Order */ };  // full specialization
```

Also **partial specialization** for class templates (e.g. specialize `Serializer<T*>` for all pointer types). Used to select the best algorithm per type at compile time.

## The pre-C++20 pain: awful error messages

Before concepts, a template that required (say) a `<` operator would fail deep inside the instantiation with a wall of errors, because constraints were implicit ("duck typing at compile time"). Concepts fix this.

## Concepts (C++20) — named, checkable constraints

A **concept** is a named compile-time predicate on types. It documents and *enforces* what a template requires:

```cpp
#include <concepts>

template <typename T>
concept Orderable = requires(T a, T b) {
    { a < b }  -> std::convertible_to<bool>;   // must support <
    { a == b } -> std::convertible_to<bool>;   // must support ==
};

template <Orderable T>          // constrain the template
T max(T a, T b) { return (a < b) ? b : a; }

max(3, 7);              // OK
// max(SomeType{}, ...) // if SomeType has no <, CLEAR error: "does not satisfy Orderable"
```

Benefits:
- **Readable errors**: "T does not satisfy Orderable" instead of 200 lines.
- **Self-documenting**: the signature states requirements.
- **Overload selection**: pick different implementations based on which concept a type satisfies.

Standard concepts you'll use: `std::integral`, `std::floating_point`, `std::convertible_to`, `std::same_as`, `std::totally_ordered`.

```cpp
template <std::integral T>       // only integer types
T next(T x) { return x + 1; }
```

## `if constexpr` — compile-time branching

Choose code paths at compile time; the untaken branch isn't even compiled:

```cpp
template <typename T>
void serialize(const T& v) {
    if constexpr (std::integral<T>) {
        writeInt(v);       // only compiled when T is integral
    } else {
        writeGeneric(v);
    }
}
```

Replaces old tag-dispatch/SFINAE tricks with readable code. Zero runtime cost (the branch is resolved at compile time).

## Variadic templates (brief)

Templates taking any number of args — how `std::make_unique`, `emplace_back`, `printf`-safe logging work:

```cpp
template <typename... Args>
void log(Args&&... args) { (std::cout << ... << args); }  // fold expression (C++17)
```

## Tradeoffs / interview "why"

- **Templates = compile-time polymorphism, zero runtime cost** vs virtual functions = runtime polymorphism, per-call cost. Interviewers love this contrast — it's the "when do you pay for abstraction?" question.
- Cost of templates: longer compile times, code bloat (one instantiation per type), errors historically brutal (concepts fix this).
- Templates are *duck-typed* at compile time — concepts make the contract explicit.
- `if constexpr` and concepts are the modern, readable replacements for SFINAE.

## In the order book

- Side-specialized templates: `template <Side S> class BookSide` lets the compiler eliminate the buy-vs-sell branch entirely (a real HFT optimization — mentioned in your research doc as "compile-time branch elimination").
- A `RingBuffer<Message, N>` fixed-capacity queue feeds orders into the engine with no heap.
- Concepts constrain `Price`/`Qty` to be orderable/integral, catching misuse at compile time.
