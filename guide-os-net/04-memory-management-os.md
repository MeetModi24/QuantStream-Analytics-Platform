# Module 4 — Memory management (OS view): virtual memory, fragmentation, huge pages, NUMA

Your listed priority #5. The C++ side (`new`/`delete`, smart pointers, pools) is in `../guide/03` and `../guide/13`. Here we cover what happens *below* the language — how the OS and hardware actually give you memory, and the tuning that matters for HFT.

## Where memory comes from (the layers)

```
your code:  new / make_unique / vector
      ↓
C++ runtime allocator (malloc/free, e.g. glibc, tcmalloc, jemalloc)
      ↓ (when it needs more)
OS syscalls: brk / sbrk (grow heap) or mmap (map new region)
      ↓
physical RAM frames, mapped via page tables (Module 1)
```

Key insight: `new` usually does **not** hit the OS — the runtime allocator keeps a pool of memory from earlier syscalls and hands out chunks. It only calls `mmap`/`brk` (slow syscall — Module 1) when it runs out. But *when* that happens is unpredictable → latency spikes. This is the deeper reason HFT pre-allocates (Module 6).

## The allocator's job and its costs

`malloc`/`free` must: find a free block of the right size (search), split/merge blocks, track metadata, and be **thread-safe** (locking or per-thread arenas). Costs:
- **Time & variance** — search + possible lock + possible syscall (`../guide/03`).
- **Metadata overhead** — each allocation carries bookkeeping (size headers).
- **Fragmentation** — see below.

Alternative allocators (**tcmalloc**, **jemalloc**) reduce contention with per-thread caches and are common drop-ins in HFT for the *cold* path.

## Fragmentation (your listed concern)

- **External fragmentation**: free memory exists but is split into pieces too small to satisfy a request. Total free is enough; no single block is. Classic with variable-size allocations over time.
- **Internal fragmentation**: an allocator rounds your request up to a size class (e.g. asks for 40 B, gets a 64 B block) — the extra 24 B is wasted inside the block.

Why HFT hates fragmentation: it wastes memory (evicts hot data from cache) and makes allocation slower/less predictable over a long-running process.

**The fix is the same as the latency fix:** pre-allocate fixed-size slots up front (Module 6). Fixed-size pools have **zero external fragmentation** (every slot is identical and reusable) and no per-allocation search.

## Virtual memory recap + the HFT levers

(Full mechanism in Module 1.) The tuning knobs:

### Pre-faulting (pre-touching)

Freshly allocated memory is *virtual only* — physical frames aren't assigned until first touch, which **page-faults** (slow). So at startup, write a byte to every page of your pools so faults happen *before* trading, never during:

```cpp
std::vector<Order> storage(N);
for (auto& o : storage) o = Order{};   // touch every page now (or std::memset the buffer)
```

### `mlock` — pin pages in RAM

`mlock`/`mlockall` prevents the OS from swapping your pages to disk (a swap-in is a multi-ms catastrophe on the hot path):

```cpp
#include <sys/mman.h>
mlockall(MCL_CURRENT | MCL_FUTURE);   // lock all current + future pages in RAM
```

### Huge pages

Default pages are 4 KB. A 1 GB working set needs 262,144 page-table entries → TLB thrashing (Module 1). **Huge pages** (2 MB or 1 GB) cover the same memory with far fewer entries → far fewer TLB misses. Enable via `madvise(MADV_HUGEPAGE)`, `mmap(MAP_HUGETLB)`, or transparent huge pages. A real, measurable latency win for large books/buffers.

## NUMA — Non-Uniform Memory Access

On multi-socket servers, each CPU socket has its **own** local RAM. Accessing your *own* socket's RAM is fast; accessing *another* socket's RAM (across the interconnect) is slower. This is **NUMA**.

**HFT implications:**
- Pin your hot thread to a core, and allocate its memory on the **same NUMA node** (`numactl`, `libnuma`, `numa_alloc_onnode`). A trading thread reaching across sockets for its order book adds latency.
- "First-touch" policy: a page is placed on the NUMA node of the thread that *first writes* it — so pre-fault from the thread that will use it.

## Tradeoffs / interview "why"

- `new` rarely hits the OS (runtime pools it), but *when* it does is an unpredictable spike — the tail-latency argument for pre-allocation.
- External vs internal fragmentation — define both; fixed-size pools eliminate external.
- Pre-fault + `mlock` + huge pages: three knobs to remove page-fault / swap / TLB jitter.
- NUMA locality: keep a thread's data on its own socket.
- Alternative allocators (jemalloc/tcmalloc) for the cold path; custom pools for the hot path.

## In the trading system

At startup: allocate all pools, pre-fault every page, `mlockall`, place memory on the matching thread's NUMA node, back large buffers with huge pages. During trading: zero OS memory calls, zero page faults, zero swaps — memory latency is flat. Module 6 builds the pools that make this possible.
