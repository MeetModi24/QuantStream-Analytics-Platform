# Module 3 — Dynamic memory (`new`/`delete`) & why the heap is slow

Stack objects die when scope ends. Order-book orders must *outlive* the function that made them. That's the heap: memory whose lifetime **you** control.

## Allocating on the heap

```cpp
int* p = new int;   // allocate one int on the heap; p holds its address
*p = 5;
delete p;           // return the memory
p = nullptr;        // hygiene: avoid a dangling pointer

int* arr = new int[100];  // 100 contiguous ints
delete[] arr;             // delete[] for arrays — NOT delete
```

The heap object lives **until you `delete` it** — not when the function returns. That's the point. Match forms exactly: `new`↔`delete`, `new[]`↔`delete[]`. Mixing them is UB.

## The three deadly heap bugs

```cpp
// 1. LEAK — new without delete
void leak() { int* p = new int(5); }  // p dies, heap memory lost forever

// 2. USE-AFTER-FREE (dangling)
int* p = new int(5);
delete p;
std::cout << *p;   // UB — using freed memory

// 3. DOUBLE FREE
int* q = new int(5);
delete q;
delete q;          // UB — corrupts the heap allocator
```

These are why C++ earned its dangerous reputation — and why the modern language works to eliminate them.

## Modern C++: you rarely write raw `new`/`delete`

Understand them (that's this module), but in real code use tools that call them for you and guarantee cleanup:

```cpp
#include <vector>
#include <memory>

std::vector<int> v(100);          // heap array; frees itself in its destructor

auto p = std::make_unique<int>(5); // heap int owned by p
// ... use *p ...
// p goes out of scope -> automatically deletes. No leak, no manual delete.
```

This is **RAII** (Module 7): tie the heap object's lifetime to a *stack* object, so automatic stack cleanup handles the heap. Heap flexibility, stack safety — the most important idiom in modern C++.

## "If RAII ties it to a scope, why not just use the stack?"

Fair question — and mostly right. RAII ties lifetime to the **owner** object, not blindly to a scope, and the owner can be *moved out*. Prefer the stack by default; reach for the heap only when the stack **can't** do the job. Four reasons it can't:

1. **Too big.** The stack is a fixed 1–8 MB block. `int big[10'000'000];` overflows it; `std::vector<int> big(10'000'000);` puts 40 MB on the heap with a small handle on the stack.
2. **Size known only at runtime.** Stack frame sizes are fixed at *compile* time. `int arr[n];` (runtime `n`) isn't legal C++, requires compile time constant; anything that grows (`vector::push_back`, a filling map) *must* live on the heap.
3. **Must outlive the creating scope.** You can't return a pointer to a stack local — it dies at the `}`. The heap lets an object be created here and handed back, via **move**:

   ```cpp
   std::unique_ptr<Order> makeOrder() {
       auto o = std::make_unique<Order>(...);  // heap object
       return o;    // ownership MOVES to caller; the Order does NOT die here
   }
   // Order* bad() { Order o; return &o; }  // ❌ dangling — stack o dies at }
   ```

   The handle dies at the `}`; the heap payload survives, now owned by the caller.
4. **Shared or polymorphic.** Several owners sharing one object until the *last* is done (`shared_ptr`), or "some subclass of `Shape`, type unknown until runtime" (varying size → can't fit a fixed stack slot). The stack expresses neither.

Mental model: **handle on the stack, payload on the heap.** Small handle → cheap to move, gets automatic cleanup; big/variable/long-lived/shared payload → lives on the heap where those are allowed. Use the stack directly when the object is small *and* compile-time-sized *and* scope-local (most locals). Otherwise: heap via an RAII owner.

## Why the heap is *slow* (the HFT-critical part)

Stack alloc ≈ one register op. Heap `new` is dramatically more:

1. **Search** — the allocator hunts for a free block big enough; maintains bookkeeping; may lock (threads share the heap).
2. **Unpredictable** — sometimes fast, sometimes it must ask the OS for more memory (a *system call* — very slow). This *variance* is poison for HFT, where the worst case (P99.9) is what matters.
3. **Scatter** — two `new`s can land far apart; walking them thrashes the CPU cache (miss ≈ 100 ns vs hit ≈ 1 ns).

Rough intuition: stack ≈ 1 ns; heap `new` ≈ tens–hundreds of ns, occasionally microseconds. In a loop doing millions of orders/sec, `new` per order is fatal.

## The HFT fix: allocate once, reuse forever (object pool / slab)

```cpp
struct Order { /* id, price, qty, ... */ };

class OrderPool {
    std::vector<Order> storage;    // allocated ONCE at startup (off hot path)
    std::vector<Order*> freeList;  // pointers to available slots
public:
    explicit OrderPool(std::size_t n) : storage(n) {
        for (auto& o : storage) freeList.push_back(&o);
    }
    Order* acquire() {             // O(1), no heap call
        Order* o = freeList.back();
        freeList.pop_back();
        return o;
    }
    void release(Order* o) { freeList.push_back(o); }  // O(1)
};
```

During trading, "allocate an order" = pop a pointer off a vector. No search, no syscall, no cache scatter (all slots sit in one contiguous `storage` block). This is the "O(1) allocation" HFT projects brag about.

## Tradeoffs / interview "why"

- Raw `new`/`delete`: full control, full responsibility (all three bugs). Almost never the right default.
- `std::vector`/smart pointers: safe, idiomatic, negligible overhead. Default.
- Object pool: fastest and most predictable, but you pre-size it and manage the free-list. The HFT default on the hot path.
- Know the difference between **latency** (per-op) and **jitter/variance** (spread) — HFT cares about the tail, which is exactly what `new` ruins.

## In the order book

Orders come from an `OrderPool`. The book never calls `new` while trading. Pre-allocate at startup for the max expected orders; recycle slots via the free-list on cancel/fill. This alone is a major chunk of "how it hits 1M+ TPS with low tail latency."
