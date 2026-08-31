# Module 4 — lvalues, rvalues & an intro to moving

The distinction beginners find hardest — and it's really a *performance* feature. It exists so the compiler knows when it can **steal** instead of **copy**. (Module 9 goes deep; this builds the intuition.)

## Value categories

Every expression is an **lvalue** or an **rvalue** (C++ has finer categories — glvalue/prvalue/xvalue — but this split is what you need first):

- **lvalue** — has identity/a name, persists, you can take its address. Can sit on the left of `=`.
- **rvalue** — a temporary, no lasting identity, about to be destroyed.

```cpp
int x = 5;
//  ^   ^-- rvalue: literal 5 (a temporary, no address)
//  lvalue: x (named, addressable)

int y = x + 1;   // (x + 1) is an rvalue — a temporary result
&x;              // OK
// &(x + 1);     // ERROR — can't take the address of a temporary
```

**Why care?** A temporary is about to die anyway, so instead of copying its contents we can *cannibalize* them. That's a move.

## The problem moving solves

A class that owns a heap buffer:

```cpp
class Buffer {
    int* data;      // owns a heap array
    std::size_t n;
    // ctor: data = new int[n];   dtor: delete[] data;
};

Buffer make() {
    Buffer b;       // fills a million elements
    return b;       // returns a temporary (rvalue)
}
Buffer x = make();  // what happens?
```

Without moving, `Buffer x = make()` would **copy** a million elements from the returned temporary into `x`, then destroy the temporary's buffer. Absurd — the source is dying anyway.

## The insight: steal the pointer

The temporary's heap buffer is about to be freed. Instead of copying contents, **take its pointer** and null out the source so its destructor frees nothing. O(1) instead of O(n):

```cpp
Buffer(Buffer&& other) noexcept {   // Buffer&& = rvalue reference: binds to temporaries
    data = other.data;   // steal
    n    = other.n;
    other.data = nullptr; // leave source empty so ITS dtor frees nothing
    other.n    = 0;
}
```

`T&&` (double ampersand) is an **rvalue reference** — a reference that binds *only to rvalues*. It's how a function says "I know this argument is disposable, so I may gut it."

The compiler then chooses:

```cpp
Buffer a;
Buffer c = a;          // a is an lvalue (lives on) -> COPY (mustn't damage it)
Buffer d = make();     // rvalue temporary        -> MOVE (safe to gut)
```

Same syntax; copy-vs-move chosen by whether the source is an lvalue or rvalue. *That's the entire reason value categories exist.*

## `std::move`: "treat this lvalue as disposable"

Sometimes you have an lvalue you're done with and want to force a move. `std::move` moves nothing — it just **casts** an lvalue to an rvalue reference, granting permission to steal:

```cpp
Buffer a;
Buffer b = std::move(a);  // force MOVE: b steals a's guts
// a is now valid-but-empty. Don't rely on its value; you may reassign it.
```

Read `std::move(a)` as: "I promise I'm finished with `a`'s contents — feel free to cannibalize."

## Tradeoffs / interview "why"

- Moving is why modern C++ returns big objects by value cheaply, why `std::vector` growth is efficient, why passing things doesn't secretly copy megabytes.
- Getting copy-vs-move wrong is a routine "why is this 10× slower than it should be" bug.
- Interview trap: after `std::move(a)`, `a` is *valid but unspecified* — not destroyed. You can reassign or destroy it; don't read its old value.
- **RVO/NRVO**: the compiler often elides the move on `return` entirely (constructs directly in the caller). So `return b;` is frequently zero-cost — don't write `return std::move(b);` (it can *disable* RVO).

## In the order book

When an order moves between data structures (e.g. handed from an input queue into the book), a move is a pointer swap; a copy might duplicate a buffer. With pooled fixed-size `Order` objects you often pass indices/pointers instead — but understanding move-vs-copy is what lets you reason about where hidden copies would otherwise cost you latency.
