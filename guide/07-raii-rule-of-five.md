# Module 7 — RAII & the Rule of 0/3/5

The single most important idiom in C++. Everything about safe resource handling flows from it. This module builds it up from the problem it solves, not from rules to memorize.

## The problem: cleanup you have to remember is cleanup you'll forget

A "resource" is anything you **acquire and must later release**: heap memory (`new`→`delete`), a file (`fopen`→`fclose`), a lock, a socket. The naive approach is to release it by hand:

```cpp
void f() {
    int* data = new int[100];   // acquire
    doWork(data);
    delete[] data;              // release — IF we get here
}
```

This leaks on every path that skips the `delete`:

- an early `return` above it,
- a thrown **exception** from `doWork` (control jumps straight out),
- someone editing the function later and adding a `return` without noticing.

Manual release is fragile *because* it depends on a human remembering it on **every** exit path. RAII removes the human.

## RAII: Resource Acquisition Is Initialization

The idea in one line: **tie the resource's lifetime to an object's lifetime.** Acquire the resource in a **constructor**; release it in the **destructor**. Then lean on the one guarantee C++ gives you for free (Module 1, 6): *a stack object's destructor always runs when it leaves scope — including while an exception is unwinding.*

So if the release lives in a destructor, it **cannot be skipped**. You're no longer relying on memory or discipline; you're relying on the language's scope-exit machinery.

```cpp
class Lock {
    std::mutex& m_;
public:
    explicit Lock(std::mutex& m) : m_{m} { m_.lock(); }   // acquire in ctor
    ~Lock() { m_.unlock(); }                              // release in dtor
};

void f(std::mutex& m) {
    Lock guard{m};        // locked here
    doWork();             // even if doWork THROWS...
    // more work...
}                         // ...guard's destructor runs on the way out → unlocks. Always.
```

Read the payoff carefully: there is **no `unlock()` call in `f`**. You can't forget it, can't skip it on an early return, and an exception can't leak the lock — because unlocking isn't something `f` does, it's something `guard`'s destruction does, automatically, on every exit.

That's the whole idiom. Every RAII type is just "acquire in constructor, release in destructor." The ones you'll use constantly are already written for you:

| RAII wrapper | Manages | Releases (in its destructor) |
|---|---|---|
| `std::unique_ptr<T>` | one heap object | `delete` |
| `std::vector<T>` | a dynamic array | `delete[]` |
| `std::string` | a character buffer | frees it |
| `std::lock_guard` | a mutex | `unlock()` |
| `std::fstream` | an open file | `close()` |

Whenever you reach for `new`, ask first: *is there an RAII type that already owns this for me?* Usually yes.

## When RAII isn't done for you: managing a raw resource

Sometimes you *are* the person writing the wrapper — a memory pool, a handle around a C API, an allocator. Now your class holds a **raw** resource (a `new`'d pointer, a file handle), and you have to make it behave correctly when it's copied, moved, and destroyed. That's where the "Rule of Five" comes in.

Let's build such a class and watch what breaks. Start with just a constructor and destructor:

```cpp
class Buffer {
    int* data_;         // raw owning pointer
    std::size_t n_;
public:
    Buffer(std::size_t n) : data_{new int[n]}, n_{n} {}  // acquire
    ~Buffer() { delete[] data_; }                        // release
};
```

So far so good — one buffer, allocated in the ctor, freed in the dtor. The trouble starts the moment you **copy** it.

### The disaster: what the *default* copy does

If you don't write a copy constructor, the compiler generates one. It does a **member-by-member copy** — and for a pointer, copying the member just copies the **address**:

```cpp
Buffer a(100);   // a.data_ ─▶ 0xfff00 ─▶ [ 100 ints ]
Buffer b = a;    // compiler-default copy: b.data_ = a.data_  →  0xfff00
```

Now **both** `a.data_` and `b.data_` hold `0xfff00` — they point at the *same* heap array. This is a **shallow copy**, and it's a time bomb. When both go out of scope:

```
~b runs: delete[] 0xfff00     // frees the array
~a runs: delete[] 0xfff00     // frees it AGAIN → DOUBLE FREE, heap corruption, crash
```

(And if `b` had modified "its" array, it would have silently modified `a`'s too — they're the same memory.) This is exactly the double-free / dangling bug from Module 3, reintroduced silently by a `=` you didn't think twice about.

**The core rule this leads to:** the moment your class owns a raw resource, the compiler's default copy/move/destroy are *wrong*, and you must take control of them. There are **five** such special member functions. Let's motivate each as a fix.

### 1. Destructor — release the resource

Already have it. This is the piece that makes the class RAII in the first place:

```cpp
~Buffer() { delete[] data_; }
```

Writing this is the *signal* that your class owns something — which is precisely why the compiler's copy/move defaults can no longer be trusted (they don't know about the ownership this destructor implies).

### 2. Copy constructor — a *deep* copy, not a shallow one

To copy a `Buffer` safely, don't copy the pointer — **allocate a new array and copy the contents.** Now the two objects own separate memory, and each destructor frees its own:

```cpp
Buffer(const Buffer& o) : data_{new int[o.n_]}, n_{o.n_} {  // fresh allocation
    std::copy(o.data_, o.data_ + n_, data_);                // copy the elements
}
// a.data_ ─▶ 0xfff00 ─▶ [ ints ]
// b.data_ ─▶ 0xaa100 ─▶ [ separate copy of the ints ]   ← no sharing, no double free
```

That's the fix for the disaster above: a **deep copy** gives each object its own buffer.

### 3. Copy assignment — the same, but for `b = a` on an *already-built* `b`

Copy *construction* builds a brand-new object (`Buffer b = a;`). Copy *assignment* overwrites one that already exists (`b = a;` where `b` was made earlier). It's trickier because `b` already owns a buffer you must not leak, and you have to survive two hazards:

- **self-assignment** (`b = b;`) — if you naively `delete[]` your own buffer then copy from it, you just freed the thing you're reading.
- **an exception** while allocating the new buffer — you must not leave `b` half-destroyed.

The clean trick that handles both for free is **copy-and-swap**:

```cpp
Buffer& operator=(Buffer o) {   // take the argument BY VALUE — the copy happens HERE
    swap(*this, o);             // swap our guts with the fresh copy's
    return *this;
}                               // 'o' (now holding our OLD buffer) is destroyed here
```

Walk through why this is elegant:

- The parameter is **by value**, so the caller's argument is copied into `o` *using the copy constructor you already wrote* (which allocates fresh + deep-copies). If that allocation throws, it throws **before** you've touched `*this` — so `*this` is left intact. Exception-safe, automatically.
- `swap` exchanges the pointers of `*this` and `o` — cheap, can't throw. After it, `*this` holds the new data and `o` holds our **old** buffer.
- When `o` goes out of scope at the `}`, *its* destructor frees the old buffer. You never wrote a `delete[]` here at all.
- Self-assignment just works: `b = b` copies `b` into `o`, swaps, and frees the (now redundant) copy. No special-case check needed.

```cpp
friend void swap(Buffer& a, Buffer& b) noexcept {   // the helper copy-and-swap uses
    std::swap(a.data_, b.data_);
    std::swap(a.n_,    b.n_);
}
```

If copy-and-swap feels like a lot at first, that's fine — the takeaway is *why* it exists: it's the one formulation that is self-assignment-safe and exception-safe without fiddly manual checks.

### 4 & 5. Move constructor and move assignment — steal instead of copy

Deep copies are correct but expensive (allocate + copy a million ints). When the source is a **temporary about to die** (an rvalue — Module 4), copying is wasteful: just **steal its pointer and null out the source** so its destructor frees nothing:

```cpp
// 4. Move constructor
Buffer(Buffer&& o) noexcept : data_{o.data_}, n_{o.n_} {  // take o's pointer
    o.data_ = nullptr;  o.n_ = 0;                         // empty the source (Module 4)
}

// 5. Move assignment — like move ctor, but free our own buffer first
Buffer& operator=(Buffer&& o) noexcept {
    if (this != &o) {           // guard against b = std::move(b)
        delete[] data_;         // free what we currently hold
        data_ = o.data_;  n_ = o.n_;   // steal o's
        o.data_ = nullptr;  o.n_ = 0;  // empty the source
    }
    return *this;
}
```

This is the shallow-copy-plus-null-out from Module 4 — the thing a raw shallow copy got *wrong* is now correct precisely *because* we empty the source, so only one object ends up owning the buffer.

### The Rule of Five, stated

**If you write (or `=delete`) any one of these five — destructor, copy ctor, copy assign, move ctor, move assign — you almost certainly need to handle all five.** They're a set: providing a destructor (you own something) but leaving the default copy means copies still shallow-copy and double-free. Getting one right and forgetting the rest silently reintroduces the exact bug we started with.

> **Why `noexcept` on the moves?** `std::vector`, when it grows and must relocate its elements, will only **move** them (fast) if their move is `noexcept` — otherwise it falls back to **copying** them (slow) to preserve its strong exception guarantee. A missing `noexcept` on your move can silently turn container operations O(n)-copies. Classic interview question, and it genuinely matters for performance.

## The Rule of Three (the pre-C++11 version)

Before move semantics existed (C++98/03), there were only **three**: destructor, copy constructor, copy assignment. Same reasoning, minus the two move members. You'll still see it in older code; the Rule of Five is just the Rule of Three plus moves.

## The Rule of Zero — what you should actually aim for

Here's the punchline that makes all of the above rare in practice: **don't manage raw resources yourself.** If your members are already RAII types, the compiler-generated five are all *correct*, because each member cleans up, copies, and moves itself:

```cpp
class Buffer {
    std::vector<int> data_;   // owns the memory AND handles all five correctly
public:
    explicit Buffer(std::size_t n) : data_(n) {}
    // No destructor. No copy/move. The defaults are right. THIS is the Rule of Zero.
};
```

Compare this to the 30-line raw-pointer version above — same behavior, none of the hazards, because `std::vector` already got the Rule of Five right *once*, and you're reusing it. Copying this `Buffer` deep-copies (vector's copy ctor does), moving it steals (vector's move ctor does), destroying it frees (vector's destructor does) — all for free.

**So the real guidance is:** aim for the **Rule of Zero** — hold `vector`/`unique_ptr`/`string` and write none of the five. Reach for the **Rule of Five** only when you're writing the low-level wrapper that *no existing type provides* (a custom pool, a handle around a C API, an allocator). In HFT you do occasionally write those, which is why you must understand both — but even there, most of your code should be Rule-of-Zero.

## `=default` and `=delete` — being explicit about the five

You can tell the compiler "generate the default for me" (`=default`) or "ban this operation entirely" (`=delete`):

```cpp
class NonCopyable {
public:
    NonCopyable() = default;                              // give me the default ctor
    NonCopyable(const NonCopyable&) = delete;             // banning copy → compile error if tried
    NonCopyable& operator=(const NonCopyable&) = delete;
};
```

`=delete` turns a misuse into a **compile-time error** instead of a runtime bug. It's how you express "this type must never be copied" — exactly right for a pool, a socket, or a lock, where a silent copy would be a correctness disaster.

## Tradeoffs / interview "why"

- RAII is *the* answer to "how does C++ avoid leaks without a garbage collector?" — **deterministic** destruction at scope exit, guaranteed even through exceptions.
- Rule of Zero = less code, fewer bugs, always your default. Rule of Five = full manual control, only when you own a raw resource no existing type wraps.
- `noexcept` moves unlock container optimizations — know *why* (move-on-realloc vs copy-on-realloc).
- Deterministic destruction (no GC pauses) is a *reason HFT uses C++*: you control exactly when memory is freed, so there are no unpredictable garbage-collector stalls in the middle of a trade.

## In the order book

- `OrderPool` (Module 3) is a **Rule-of-Five** type: it owns a big buffer, must **not** be copied (`=delete` the copy operations — two pools sharing one slab would be chaos), and can be moved. It's exactly the low-level wrapper where you write the special members by hand.
- Everything above it — `Book`, `Limit` — follows the **Rule of Zero**: they hold `std::vector`s or references into the pool, so the compiler-generated special members are already correct and you write none of them. That's the layering you want: a *small* amount of hand-written Rule-of-Five at the bottom, Rule-of-Zero everywhere above it.
