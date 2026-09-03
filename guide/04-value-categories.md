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

### What actually happens byte-for-byte

`data = other.data;` is a **shallow copy of the pointer** — and that's the point. Say `other.data` holds the address `0xfff00`:

```
before:  other.data ─▶ 0xfff00 ─▶ [ a million ints ]

data = other.data;     // copy the ADDRESS 0xfff00 (one machine word), not the ints
         this->data ─▶ 0xfff00 ─▶ [ the array ]   ← both now point at the SAME block
         other.data ─▶ 0xfff00 ─┘

other.data = nullptr;  // sever the source's claim
         this->data ─▶ 0xfff00 ─▶ [ the array ]   ← sole owner
         other.data ─▶ nullptr                    ← owns nothing
```

If we skipped the null-out, **both** destructors would `delete[] 0xfff00` → **double free** (Module 3). Nulling the source makes its later `delete[] nullptr` a safe no-op, so the block is freed exactly once. A move is therefore: **shallow-copy the handle, then invalidate the old handle.** Nothing in the heap relocates — the array stays at `0xfff00` the whole time; only *which pointer is allowed to free it* changes. The **ownership** moves; the **data** stays put.

### This only works because the payload is on the heap

Moving beats copying *only when the object owns its payload indirectly* — it holds a small pointer and the real data lives elsewhere (heap). If the data is stored **inline / by value** inside the object, there is **no pointer to steal**, so move degrades to a full copy:

```cpp
struct Inline { int data[100]; };   // 400 bytes stored RIGHT HERE, in the object
// move ctor can only copy all 400 bytes element-by-element — nothing to reassign.
```

```
Buffer (heap-owning):            Inline (by-value):
  [ ptr ] ─▶ [ big buffer ]        [ 400 bytes, inline ]
   ^ steal the ptr, O(1)           ^ no handle to steal → copy all of it, O(n)
```

So for plain scalar / fixed-array / inline data (the stuff that lives directly on the stack or directly inside a pooled object), **move == copy** — `std::move` on it is legal but buys nothing. This is exactly why `OrderPool` hands out *pointers/indices* into `storage`: an `Order` is all inline fields, so moving its bytes is no cheaper than copying — you get O(1) transfer only by moving a *pointer to* the order, reintroducing the indirection yourself. **Rule: move helps only for types that own a resource through a pointer (heap buffer, file handle, socket).**

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

## Deep dive: what the compiler does on `return x;`

Common confusion: "`x` is a named, addressable variable — an lvalue — so how does `return x;` move from it?" Both facts are true at once, because *value category* and *how the return value is constructed* are different questions.

- **What category is the expression `x`?** → **lvalue**. Always. `&x` is legal; it names an object. `return` does not reclassify it.
- **How is the returned object constructed?** → the return statement is *allowed to move from `x`* even though `x` is an lvalue, because `x` is a local about to be destroyed the instant the frame pops. Keeping it intact is pointless.

The exact steps the compiler takes for `return x;` where `x` is a local of the return type:

1. **Try NRVO (elision) first.** If it can, the compiler constructs `x` *directly in the caller's return slot* — `x` and the returned object are literally the same memory. No copy, no move, not even a move-constructor call. This is Named Return Value Optimization. Frequently `return x;` costs **nothing**.
2. **If elision doesn't apply, try to move.** Overload resolution for building the return value treats `x` **as if it were an rvalue** — so it looks for a **move constructor** (binds to `T&&`) first. Found → the returned object steals `x`'s guts (pointer swap), `x` is left valid-but-empty and then destroyed.
3. **Fall back to copy.** No usable move constructor (e.g. the type only has a copy ctor) → treat `x` as the lvalue it is and **copy**.

So the order is **elide → move → copy**, best to worst. `x` stays an lvalue by category the whole time; step 2 is a special rule that *lets the return machinery move from a dying local*, often called **implicit move on return**.

```cpp
std::string make() {
    std::string x = "hello";
    return x;   // step 1 (NRVO) usually; else step 2 picks string's MOVE ctor.
}               // NEVER a full deep copy in practice.
```

Corollary (already in Tradeoffs): don't write `return std::move(x);` for a plain local — it forces step 2 and *disables step 1*, so it's usually slower.

## Two different "lvalue → rvalue" things — don't conflate them

`x + 1` is an rvalue and `return x;` can move from `x`, but these ride on **different mechanisms**:

**lvalue-to-rvalue conversion** — *reading the value out of an object.* When you use a variable's contents, the CPU reads the stored value into a register; that read yields a prvalue. `x` stays put and unchanged.

```cpp
int y = x + 1;
// x undergoes lvalue→rvalue conversion: read x's value (say 10) into a register.
// operator+ then MANUFACTURES a fresh temporary 11.
// The expression (x + 1) is a prvalue because + produces a NEW value —
// not because x was "turned into" an rvalue. x is untouched.
```

**Implicit move on return** — *which constructor builds the result.* Not a value read; it's overload resolution binding `x` to `T&&` so the move ctor wins, which **cannibalizes** `x`.

| | `x + 1` (lvalue→rvalue conversion) | `return x;` (implicit move) |
|---|---|---|
| About | **reading** the value stored in `x` | **which constructor** builds the result |
| Result | a fresh prvalue; `x` untouched | move ctor selected; `x` gutted (valid-but-empty) |
| Applies to | any read (mainly scalars) | class types with a move constructor |

Loosely both "involve an lvalue doing something rvalue-ish," but one is a **read that leaves the source intact**, the other lets the source be **plundered because it's about to die**.

## Tradeoffs / interview "why"

- Moving is why modern C++ returns big objects by value cheaply, why `std::vector` growth is efficient, why passing things doesn't secretly copy megabytes.
- Getting copy-vs-move wrong is a routine "why is this 10× slower than it should be" bug.
- Interview trap: after `std::move(a)`, `a` is *valid but unspecified* — not destroyed. You can reassign or destroy it; don't read its old value.
- **RVO/NRVO**: the compiler often elides the move on `return` entirely (constructs directly in the caller). So `return b;` is frequently zero-cost — don't write `return std::move(b);` (it can *disable* RVO).

## In the order book

When an order moves between data structures (e.g. handed from an input queue into the book), a move is a pointer swap; a copy might duplicate a buffer. With pooled fixed-size `Order` objects you often pass indices/pointers instead — but understanding move-vs-copy is what lets you reason about where hidden copies would otherwise cost you latency.
