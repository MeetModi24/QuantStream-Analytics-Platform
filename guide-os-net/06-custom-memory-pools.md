# Module 6 — Custom memory pools: arena, slab, free-list; integration

Your listed priority #7. `../guide/03` introduced the object pool; this module is the full toolkit — the pool *types*, when each fits, and how to integrate them with STL/third-party code.

## Why custom pools at all

Recap of the motivation (Modules 1, 4; `../guide/03`): general `malloc`/`new` is slow, variable-latency, thread-locked, and fragments. A custom pool trades generality for **speed + determinism**: allocate a big block once at startup, then hand out pieces with O(1), no syscalls, no fragmentation, no locks. On the hot path, this is non-negotiable.

## The three core designs

### 1. Arena / bump allocator (fastest, no individual free)

Allocate a big buffer; keep a pointer to the next free spot; each allocation just **bumps** the pointer. Deallocation is all-or-nothing (reset the pointer to the start).

```cpp
class Arena {
    char* base_; char* cur_; char* end_;
public:
    Arena(std::size_t bytes) : base_{new char[bytes]}, cur_{base_}, end_{base_+bytes} {}
    ~Arena() { delete[] base_; }
    void* alloc(std::size_t n, std::size_t align = alignof(std::max_align_t)) {
        auto p = reinterpret_cast<std::uintptr_t>(cur_);
        p = (p + align - 1) & ~(align - 1);           // round up to alignment
        char* out = reinterpret_cast<char*>(p);
        if (out + n > end_) return nullptr;           // out of space
        cur_ = out + n;
        return out;
    }
    void reset() { cur_ = base_; }                    // free EVERYTHING at once, O(1)
};
```

- **Pros:** allocation is a pointer add — the fastest possible. Zero fragmentation. Trivial reset.
- **Cons:** can't free individual objects. Perfect for per-request/per-tick scratch memory you discard together ("frame allocator").
- **HFT use:** temporary buffers within one message's processing, then `reset()`.

### 2. Fixed-size free-list pool (the object pool)

For many objects of the *same* type/size. A free-list threads through unused slots; allocate = pop, free = push. (This is `../guide/03`'s `OrderPool`.)

```cpp
template <typename T, std::size_t N>
class FixedPool {
    alignas(T) std::byte storage_[N * sizeof(T)];
    std::size_t freeList_[N];
    std::size_t top_;
public:
    FixedPool() : top_{N} { for (std::size_t i = 0; i < N; ++i) freeList_[i] = i; }
    T* acquire() {                                  // O(1)
        if (top_ == 0) return nullptr;
        return reinterpret_cast<T*>(storage_) + freeList_[--top_];
    }
    void release(T* p) {                            // O(1)
        auto idx = p - reinterpret_cast<T*>(storage_);
        freeList_[top_++] = static_cast<std::size_t>(idx);
    }
};
```

- **Pros:** O(1) alloc/free of individual objects, zero external fragmentation (all slots identical), cache-dense (one contiguous block).
- **Cons:** one size only; fixed capacity (must size for the max).
- **HFT use:** the order pool — the canonical case.

### 3. Slab allocator (multiple fixed sizes)

Maintain several fixed-size pools ("slabs"), one per size class (32 B, 64 B, 128 B…). Route each request to the smallest slab that fits. This is essentially how the Linux kernel and tcmalloc work internally.

- **Pros:** handles varied sizes while keeping each slab fragmentation-free and O(1).
- **Cons:** more complex; internal fragmentation (rounding up to a size class).
- **HFT use:** when you have several distinct fixed-size object types (orders, trades, messages) — one slab each.

## Integration with STL and third-party code

STL containers accept a **custom allocator** template parameter, so you can back them with your pool:

```cpp
// A minimal allocator wrapping a pool (sketch — real ones need rebind, etc.)
template <typename T>
struct PoolAllocator {
    using value_type = T;
    Pool* pool_;
    T* allocate(std::size_t n) { return static_cast<T*>(pool_->alloc(n * sizeof(T))); }
    void deallocate(T* p, std::size_t) { pool_->free(p); }
    // ... rebind, ==, != ...
};

std::vector<Order, PoolAllocator<Order>> orders{PoolAllocator<Order>{&myPool}};
```

**C++17 `std::pmr` (polymorphic memory resources)** makes this far cleaner — a runtime-polymorphic allocator you plug into `pmr` containers without templating the container type:

```cpp
#include <memory_resource>
std::array<std::byte, 1 << 20> buf;
std::pmr::monotonic_buffer_resource arena{buf.data(), buf.size()};  // arena on the stack buffer
std::pmr::vector<Order> orders{&arena};   // vector allocates from the arena, not the heap
// std::pmr::unsynchronized_pool_resource for a pooling resource
```

`std::pmr` is the modern, standard way to give any STL container a custom pool — worth knowing by name.

For **third-party libraries**: many accept allocator hooks or let you override global `operator new`/`operator delete`. Overriding global `new` to route through a pool is a heavy hammer (affects everything) — prefer per-container allocators or `pmr`.

## Placement new — constructing in pool memory

Pools hand back *raw* memory; you must construct the object in it with **placement new**, and destroy it manually:

```cpp
void* mem = pool.acquire();
Order* o = new (mem) Order{id, price, qty};   // placement new: construct in existing memory
// ... use o ...
o->~Order();                                   // explicit destructor call
pool.release(mem);
```

(For trivial types like a POD `Order`, you can skip construction/destruction, which is why POD order structs are convenient.)

## Tradeoffs / interview "why"

- Know the three designs and when each fits: **arena** (bulk free, scratch), **fixed pool** (same-size objects, O(1) individual free), **slab** (several sizes).
- Pools trade generality for speed + determinism + zero fragmentation.
- Integration: STL custom allocators, `std::pmr` (name it), global `new` override (heavy).
- Placement new + manual destructor for non-trivial types in pool memory.
- Thread-safety: a single-threaded pool needs no locks (fastest); a shared pool needs a lock or lock-free free-list — another reason to keep the hot path single-threaded.

## In the trading system

`OrderPool` (fixed-size, `../guide/03`) for orders; a slab or separate fixed pools for trades/messages; an arena for per-message scratch that's reset each tick. All sized and pre-faulted at startup (Module 4), single-threaded on the matching core (no locks). This is the memory foundation that makes "no `new` during trading" real.
