# Module 2 — Pointers & references

Two ways to refer to an object *indirectly*. Everything (data structures, function parameters, the whole order book graph) is built on these.

## Every object has an address

Memory is a huge byte array; each byte has a numeric address. `&` ("address-of") gives an object's address:

```cpp
int x = 5;
std::cout << &x;  // e.g. 0x7ffee3b8 — where x lives
```

## Pointers: a variable holding an address

```cpp
int x = 5;
int* p = &x;   // "p is a pointer to int"; p holds x's address
```

Dereference with `*` to reach the value:

```cpp
std::cout << *p;   // 5  — "the value AT that address"
*p = 10;           // writes through the pointer: x is now 10
```

> The `*` symbol is two things. In a **declaration** (`int* p`) it's part of the type. In an **expression** (`*p`) it's the dereference operator. Same glyph, opposite directions.

### Null pointers

```cpp
int* p = nullptr;      // points to nothing
if (p) { /*...*/ }     // pointers convert to bool
std::cout << *p;       // dereferencing null -> UB / crash
```

Always ask "can this be null?" before dereferencing. (Use `nullptr`, never `NULL` or `0`.)

### Pointers reassign and do arithmetic

```cpp
int a = 1, b = 2;
int* p = &a;   // -> a
p = &b;        // -> b now (re-seated)

int arr[3] = {10, 20, 30};
int* q = arr;          // arr decays to &arr[0]
std::cout << *(q + 1); // 20 — q+1 advances by sizeof(int), not 1 byte
```

Pointer arithmetic walks contiguous memory — the basis of cache-friendly array traversal (Module 15).

### `const` and pointers (read right-to-left)

```cpp
const int* p;        // pointer to const int   — can't change *p, can re-seat p
int* const p = &x;   // const pointer to int   — can change *p, can't re-seat
const int* const p = &x; // both fixed
```

## References: an alias for an existing object

```cpp
int x = 5;
int& ref = x;   // ref IS x — another name for it
ref = 10;       // no deref; changes x
```

Two rules make references safe:

1. **Must be initialized** at declaration — no null references.
2. **Can never be re-seated** — `ref` aliases `x` forever. Assigning to `ref` changes `x`'s *value*, it doesn't repoint `ref`.

```cpp
int a = 1, b = 2;
int& r = a;
r = b;   // this is a = b (a becomes 2). r STILL aliases a.
```

That's the core pointer-vs-reference difference: pointers can be null and re-seated; references cannot.

## Why references exist: passing without copying

Default is **pass by value** — a copy:

```cpp
void f(int n) { n = 99; }   // n is a copy; caller unchanged
```

Fine for `int`. Expensive for big objects. **Pass by reference** avoids the copy and can modify the original:

```cpp
void f(int& n) { n = 99; }  // caller's variable changes
```

### `const&` — the workhorse parameter

Pass big objects with no copy *and* no modification:

```cpp
void print(const std::string& s) { std::cout << s; }  // no copy, read-only
```

`const T&` is the default way to pass anything bigger than a couple of machine words. You'll write it constantly.

## Pointer vs reference — which?

- **Reference**: the thing exists and won't change identity. Safer default. Most parameters.
- **Pointer**: you need nullability ("might be absent"), re-seating ("points at different things over time"), or arithmetic (arrays, memory walking). Linked lists/trees need pointers because links are *optional* (nullable).

## In the order book

- `const Order&` to pass an order into a function cheaply, read-only.
- Raw pointers (or 32-bit indices — Module 15) to link orders in a doubly-linked list at each price level, and to point at `highestBuy`/`lowestSell`.
- A hash map from order-ID → pointer for O(1) cancel/modify lookup.

Every one of those is a deliberate pointer-vs-reference choice.
