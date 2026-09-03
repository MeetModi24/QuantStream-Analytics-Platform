# Module 6 — Classes: constructors, destructors, `this`, access control

A class bundles **data** (members) with **behavior** (methods) and controls **who can touch what**. This module is the machinery; Module 7 (RAII) is the philosophy that gives it purpose.

## Anatomy

```cpp
class Order {
private:                 // hidden from outside — the default for `class`
    std::uint64_t id_;
    double        price_;
    std::uint32_t qty_;

public:                  // the interface
    Order(std::uint64_t id, double price, std::uint32_t qty)
        : id_{id}, price_{price}, qty_{qty}   // member initializer list
    {}

    double price() const { return price_; }   // accessor (const)
    void   reduce(std::uint32_t by) { qty_ -= by; }  // mutator
};
```

- `class` members default to **private**; `struct` members default to **public**. Otherwise identical. Convention: `struct` for plain data aggregates, `class` when you enforce invariants.
- Trailing underscore (`id_`) is a common convention to distinguish members from locals/params.

## Constructors and the member initializer list

The `: id_{id}, price_{price}` part is the **member initializer list** — it *initializes* members directly. Prefer it over assigning in the body:

```cpp
Order(...) { price_ = price; }   // WORSE: price_ is default-constructed, THEN assigned
Order(...) : price_{price} {}    // BETTER: directly initialized once
```

For `const` members and references, the init list is **mandatory** (they can't be assigned after construction).

> Members are initialized in **declaration order**, not init-list order. Listing them out of order is a common warning-generating bug.

### Kinds of constructors

```cpp
class Widget {
public:
    Widget() = default;                 // default ctor (no args)
    Widget(int x);                      // parameterized
    Widget(const Widget&) = default;    // copy ctor (Module 7)
    Widget(Widget&&) = default;         // move ctor (Modules 4, 9)
    explicit Widget(double d);          // explicit: no implicit conversions
};
```

`explicit` prevents surprising implicit conversions:

```cpp
void take(Widget w);
take(3.0);   // if ctor is explicit, ERROR (good — no silent double->Widget)
```

Mark single-argument constructors `explicit` unless you *want* implicit conversion.

## The `this` pointer

Inside a non-static method, `this` is a pointer to the object the method was called on:

```cpp
class Order {
    int qty_;
public:
    Order& setQty(int qty) {
        this->qty_ = qty;   // this-> optional here; needed if a param shadows a member
        return *this;       // return the object itself -> enables chaining
    }
};
// order.setQty(5).setQty(10);   // fluent chaining via return *this
```

## Destructors

`~ClassName()` — the cleanup half of an object's life (constructor acquires, destructor releases). No return type, no parameters, exactly **one** per class. You almost never call it explicitly; the compiler inserts the call. This is where RAII cleanup lives:

```cpp
class FileHandle {
    std::FILE* f_;
public:
    explicit FileHandle(const char* path) : f_{std::fopen(path, "r")} {}
    ~FileHandle() { if (f_) std::fclose(f_); }   // guaranteed cleanup
};
```

### When it runs (automatically, on every exit path)

1. **Stack object leaves scope** — at the closing `}`. This *is* the "automatic cleanup at scope exit" from Module 1; the destructor is what runs.
2. **Heap object is `delete`d** — `delete` runs the destructor *first*, then releases the raw memory.
3. **The enclosing object is destroyed** — each member's destructor runs too (see order below).
4. **Exception unwinding** — as an exception propagates, destructors of all locals between `throw` and `catch` fire. This is what makes RAII exception-safe (Module 14).

The guarantee across all four: cleanup happens no matter *how* you leave — normal return, early return, or exception. You write the `delete`/`fclose` once; it can't be skipped.

### Destruction order — reverse of construction

1. **Locals** are destroyed in the **reverse** order they were created (it's a stack — LIFO): `a; b; c;` → destroyed `c, b, a`.
2. **Members** are destroyed *after* the enclosing destructor's body runs, in **reverse declaration order**. You usually write nothing to clean them up — their own destructors run automatically. If every member is self-cleaning, your destructor body is often empty (or unneeded).
3. **Base class** destructor runs *after* the derived one (Module 11): derived cleanup first, then base.

### Virtual destructors (deleting through a base pointer)

If you `delete` a derived object through a **base pointer** and the base destructor isn't `virtual`, only the base part is destroyed — the derived part leaks. UB.

```cpp
struct Base { virtual ~Base() = default; };  // virtual → correct polymorphic cleanup
struct Derived : Base { std::vector<int> buf; };

Base* p = new Derived;
delete p;   // virtual:  ~Derived (frees buf) then ~Base.  ✅
            // non-virtual: only ~Base runs → buf LEAKS.  ✗ UB
```

Rule: **any class meant to be inherited from and deleted polymorphically needs a `virtual` destructor.** (Full treatment in Module 11.)

### "If I don't write one, won't it free the memory anyway?"

Only if your members are **self-cleaning**. The compiler always generates a destructor when you don't write one — but the generated destructor does exactly one thing: **destroy each member.** And "destroy" means *call that member's destructor.* So the answer depends entirely on what the member is:

```cpp
class Leaks {
    int* data_;                    // RAW pointer
public:
    Leaks(std::size_t n) : data_{new int[n]} {}
    // no ~Leaks()
};   // generated dtor destroys data_ → but destroying an int* does NOTHING to
     // the heap array. delete[] is never called. LEAK.
```

A raw pointer is **trivial to destroy** — "destroying" it just discards the 8-byte pointer variable; the heap block it points at is untouched. The compiler can't know `data_` *owns* that block (it might be a borrowed/non-owning pointer, where freeing would be a bug), so it does nothing. **A class that directly owns a raw resource MUST hand-write its destructor.**

Contrast with self-cleaning members — here you're right, no destructor needed:

```cpp
class Fine {
    std::vector<int> data_;   // owns a heap array, but has its OWN destructor
public:
    Fine(std::size_t n) : data_(n) {}
    // no ~Fine() needed
};   // generated dtor destroys data_ → runs vector's destructor → delete[]. Freed. ✅
```

| Member | Compiler-generated dtor frees it? |
|---|---|
| Raw owning pointer (`int*` + `new`) | ❌ **No — leaks.** Write `delete[]` yourself. |
| `std::vector` / `std::string` / `unique_ptr` | ✅ Yes — their own destructors run |
| Scalar (`int`, `double`) | ✅ nothing to free anyway |

The rule underneath: **destroying a member = calling that member's destructor.** Raw pointers have no meaningful one; RAII types do. This is the core reason to prefer `vector`/smart pointers over `new`/`delete` — with them, "no destructor needed" is genuinely safe; with raw pointers, a forgotten destructor is a silent leak. (And once a member manages the resource, you often need *no* destructor, copy ctor, or move ctor at all — the "Rule of Zero," Module 7.)

## Static members

Belong to the class, not any instance — one shared copy:

```cpp
class Order {
    static inline std::uint64_t nextId_ = 1;  // C++17 inline static: one shared counter
public:
    static std::uint64_t allocateId() { return nextId_++; }
};
```

Static methods have no `this` and can't touch non-static members.

## Access control & `friend`

`private`/`protected`/`public` control access. `friend` grants a specific function/class access to privates — use sparingly (it breaks encapsulation), commonly for operators (Module 8).

## Tradeoffs / interview "why"

- Encapsulation (private data + public methods) lets you enforce invariants (e.g. qty ≥ 0) and change internals without breaking callers.
- Prefer the init list; know declaration-order init; mark single-arg ctors `explicit`.
- `struct` vs `class` is *only* the default access — interviewers like asking this.
- For hot data-only types (a POD `Order`), a `struct` with public members and no methods can be the right call — encapsulation has a (tiny) ergonomic cost and sometimes clarity/layout control wins. Judgment, not dogma.

## In the order book

`Order`, `Limit` (a price level), and `Book` are classes. `Order` is nearly a POD struct (id, price, qty, side, links) for tight layout. `Book` enforces invariants (price-time priority) behind a clean public interface (`addLimitOrder`, `cancel`, `modify`). A static counter hands out order IDs.

Destructor angle: `OrderPool`'s `storage` vector has one destructor call that frees the whole slab at shutdown — and during trading **no per-order destructors fire** (you recycle slots via the free-list, you don't destroy `Order`s). Fewer destructor calls on the hot path is part of why it's fast. The same logic is behind the move ctor's `other.data = nullptr` in Module 4: you empty the source precisely so *its* destructor frees nothing (no double free).
