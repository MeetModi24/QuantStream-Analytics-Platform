# Module 7 — RAII & the Rule of 0/3/5

The single most important idiom in C++. Everything about safe resource handling flows from it.

## RAII: Resource Acquisition Is Initialization

The idea: **tie a resource's lifetime to an object's lifetime.** Acquire in the constructor, release in the destructor. Because destructors run automatically at scope exit (Module 1) — *including during exception unwinding* — the resource is *always* released, with no manual cleanup and no leak path.

```cpp
class Lock {
    std::mutex& m_;
public:
    explicit Lock(std::mutex& m) : m_{m} { m_.lock(); }   // acquire
    ~Lock() { m_.unlock(); }                              // release — always runs
};

void f(std::mutex& m) {
    Lock guard{m};        // locked here
    doWork();             // even if this throws...
}                         // ...guard's destructor unlocks. Guaranteed.
```

"Resource" = anything you acquire and must release: heap memory, files, sockets, locks, GPU handles. RAII wrappers you'll use: `std::unique_ptr` (memory), `std::lock_guard` (mutex), `std::fstream` (files), `std::vector` (dynamic array).

## The Rule of Five

If your class **manages a resource directly** (owns a raw pointer/handle), the compiler-generated defaults are wrong (they copy the pointer, not the resource → double-free). You then must consider **five** special members:

```cpp
class Buffer {
    int* data_;
    std::size_t n_;
public:
    // 1. Destructor
    ~Buffer() { delete[] data_; }

    // 2. Copy constructor — DEEP copy (allocate + copy contents)
    Buffer(const Buffer& o) : data_{new int[o.n_]}, n_{o.n_} {
        std::copy(o.data_, o.data_ + n_, data_);
    }

    // 3. Copy assignment — copy-and-swap idiom (self-assignment + exception safe)
    Buffer& operator=(Buffer o) {   // note: by VALUE (copy made by caller)
        swap(*this, o);
        return *this;
    }

    // 4. Move constructor — steal (Module 4)
    Buffer(Buffer&& o) noexcept : data_{o.data_}, n_{o.n_} {
        o.data_ = nullptr; o.n_ = 0;
    }

    // 5. Move assignment
    Buffer& operator=(Buffer&& o) noexcept {
        if (this != &o) { delete[] data_; data_ = o.data_; n_ = o.n_; o.data_ = nullptr; o.n_ = 0; }
        return *this;
    }

    friend void swap(Buffer& a, Buffer& b) noexcept {
        std::swap(a.data_, b.data_); std::swap(a.n_, b.n_);
    }
};
```

**Rule of Five:** if you write (or `=delete`) any one of destructor / copy ctor / copy assign / move ctor / move assign, you almost certainly need to handle all five. Getting one and not the others silently reintroduces the copy/double-free bugs.

Note the **move members are `noexcept`** — this matters: `std::vector` will only *move* your elements on reallocation (instead of copying) if the move is `noexcept`. A non-`noexcept` move can silently make containers copy. Interview gold.

## The Rule of Three (pre-C++11)

Before move semantics: destructor + copy ctor + copy assign. Still valid reasoning; the Rule of Five extends it with the two move members.

## The Rule of Zero — the one you should aim for

**Best practice: manage no raw resources yourself.** Use RAII members (`std::vector`, `std::unique_ptr`, `std::string`). Then you write *none* of the five — the compiler-generated defaults are correct, because your members clean up themselves.

```cpp
class Buffer {
    std::vector<int> data_;   // owns the memory; handles all five for you
public:
    explicit Buffer(std::size_t n) : data_(n) {}
    // No destructor, no copy/move — defaults are correct. This is the Rule of Zero.
};
```

**Prefer the Rule of Zero. Reach for the Rule of Five only when writing a low-level resource wrapper** (an allocator, a pool, a handle around a C API). In HFT you *do* sometimes write those, so you must know both.

## `=default` and `=delete`

```cpp
class NonCopyable {
public:
    NonCopyable() = default;
    NonCopyable(const NonCopyable&) = delete;             // ban copying
    NonCopyable& operator=(const NonCopyable&) = delete;
};
```

`=delete` makes using that operation a compile error — great for types that must not be copied (a pool, a socket, a lock).

## Tradeoffs / interview "why"

- RAII is *the* answer to "how does C++ avoid leaks without a garbage collector?" — deterministic destruction at scope exit.
- Rule of Zero = less code, fewer bugs. Rule of Five = full control when you own a raw resource.
- `noexcept` moves unlock container optimizations — know why.
- Deterministic destruction (no GC pauses) is a *reason HFT uses C++*: you control exactly when memory is freed, avoiding unpredictable pauses.

## In the order book

- `OrderPool` (Module 3) is a Rule-of-Five type: it owns a big buffer, must not be copied (`=delete` copy), can be moved. It's exactly the low-level wrapper where you write the special members by hand.
- Everything above it (`Book`, `Limit`) follows the Rule of Zero — they hold `std::vector`/pool references, so defaults are correct.
