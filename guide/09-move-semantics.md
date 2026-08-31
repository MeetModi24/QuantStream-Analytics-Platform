# Module 9 — Move semantics & rvalue references (deep)

Module 4 gave the intuition (steal from temporaries). This module makes you *fluent* — the subtleties that separate "heard of moves" from "understands moves," which is exactly what HFT interviews probe.

## Recap in one line

An rvalue reference `T&&` binds to temporaries; a move constructor/assignment transfers a resource's ownership (a pointer swap) instead of duplicating it (a deep copy).

## Reference collapsing & forwarding references

`T&&` means different things depending on context:

```cpp
void f(Widget&& w);          // rvalue reference — binds only to rvalues

template <typename T>
void g(T&& x);               // FORWARDING (universal) reference — binds to ANYTHING
```

In a **deduced template context**, `T&&` is a *forwarding reference*: it binds to lvalues and rvalues, preserving their category. This works via **reference collapsing**: `& && -> &`, `&& && -> &&`. This is the mechanism behind perfect forwarding.

## `std::forward` — perfect forwarding

Inside `g(T&& x)`, the *named* parameter `x` is an lvalue (it has a name!), even if it was bound to an rvalue. To pass it onward *preserving* its original category, use `std::forward`:

```cpp
template <typename T>
void relay(T&& x) {
    consume(std::forward<T>(x));  // forwards as lvalue if lvalue came in, rvalue if rvalue
}
```

- `std::move(x)` — *unconditionally* casts to rvalue ("I'm done with this").
- `std::forward<T>(x)` — *conditionally* casts, preserving the caller's category ("pass it on as whatever it was").

Use `std::move` for rvalue references, `std::forward` for forwarding references. Mixing them up is a classic bug.

## Why moves must be `noexcept`

`std::vector` reallocation gives the killer example:

```cpp
std::vector<Buffer> v;
v.push_back(...);   // when capacity is exceeded, vector allocates a bigger array
                    // and must transfer existing elements to it.
```

Vector wants **strong exception safety**: if transferring element k throws, it must roll back. It can only guarantee that if moving *can't throw*. So:

- Move ctor is `noexcept` → vector **moves** elements (fast). 
- Move ctor might throw → vector **copies** elements (slow) to preserve rollback.

So a missing `noexcept` on your move constructor can silently turn every vector growth into a full deep copy. **Always mark correct moves `noexcept`.**

```cpp
Buffer(Buffer&& o) noexcept { /* steal */ }   // the noexcept is not optional in practice
```

## The moved-from state

After `auto b = std::move(a)`, `a` is **valid but unspecified**:
- Valid: its destructor will run correctly; you may assign a new value to it.
- Unspecified: don't rely on what value it holds.

Design your move to leave the source in a safe empty state (nulled pointers, zero size) so its destructor is a no-op.

## Copy elision / RVO / NRVO

The compiler often skips moves entirely:

```cpp
Buffer make() {
    Buffer b(1000);
    return b;         // NRVO: b constructed directly in the caller's storage — no move at all
}
Buffer x = make();    // zero copies, zero moves
```

- **RVO** (returning a temporary) is *mandatory* in C++17.
- **NRVO** (returning a named local) is optional but common.
- **Don't write `return std::move(b);`** — it can *prevent* NRVO by turning the return into an explicit rvalue expression the compiler won't elide. Just `return b;`.

## When does move actually help?

Only for types that **own a resource** (heap buffer, file, socket). Moving a type that's all value members (`int`, `double`) is just a copy — nothing to steal. So:
- `std::string`, `std::vector`, `std::unique_ptr` → move is a big win.
- A POD `struct Order { uint64_t id; int64_t price; ... }` → move == copy (all trivial members). That's fine; no benefit, no harm.

## Pass-by-value + move ("sink" parameters)

When a function *stores* its argument, take by value and move in:

```cpp
class Book {
    std::vector<Order> log_;
public:
    void record(Order o) { log_.push_back(std::move(o)); }  // caller can move OR copy in
};
// record(std::move(existing));  // moves twice-cheaply
// record(makeOrder());          // rvalue -> move
```

This one overload handles both lvalue-copy and rvalue-move callers optimally.

## Tradeoffs / interview "why"

- `std::move` vs `std::forward`; forwarding references and reference collapsing — the deep-end questions.
- `noexcept` moves and the vector-realloc consequence — a favorite.
- Moved-from = valid-but-unspecified, not destroyed.
- Don't `return std::move(local)` — it disables NRVO.
- Moves only help resource-owning types.

## In the order book

Pooled `Order`s are trivially-movable PODs, so moves are copies (cheap, fixed-size). The move machinery matters more in the *surrounding* infrastructure: input queues of variable-size messages, `std::vector<Trade>` result logs that grow (needs `noexcept` moves to grow cheaply), and sink-parameter APIs for recording fills.
