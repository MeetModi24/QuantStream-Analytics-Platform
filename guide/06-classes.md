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

Runs automatically when the object dies (scope exit for stack objects, `delete` for heap objects). This is where RAII cleanup lives:

```cpp
class FileHandle {
    std::FILE* f_;
public:
    explicit FileHandle(const char* path) : f_{std::fopen(path, "r")} {}
    ~FileHandle() { if (f_) std::fclose(f_); }   // guaranteed cleanup
};
```

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
