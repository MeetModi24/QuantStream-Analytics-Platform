# Module 8 — Low-latency OS tuning + breadth appendix

Two parts: (A) the OS tuning that ties Modules 1–7 into a coherent low-latency system, and (B) a brisk sweep of remaining OS/networking topics an interview might touch, so you have breadth without a hefty read.

---

## Part A — Low-latency OS tuning (the checklist)

These are the levers that turn a normal Linux box into a predictable trading host. Each traces back to an earlier module's "unpredictability source."

| Technique | Removes | Module |
|-----------|---------|--------|
| **CPU pinning** (`taskset`, `pthread_setaffinity_np`) | scheduler moving your thread, cache loss | 1 |
| **Core isolation** (`isolcpus`, `nohz_full`, `rcu_nocbs`) | OS scheduling *anything* on your hot core; timer ticks | 1 |
| **Busy-polling** (spin instead of block) | context switches, wakeup latency | 1, 7 |
| **Interrupt affinity** (route IRQs off hot cores) | interrupt jitter | 1 |
| **Real-time priority** (`SCHED_FIFO`) | preemption by normal tasks | 1 |
| **Pre-fault + `mlockall`** | page faults, swapping | 4 |
| **Huge pages** | TLB misses | 1, 4 |
| **NUMA pinning** (`numactl`) | cross-socket memory latency | 4 |
| **Disable hyper-threading** | sibling threads contending for one core's cache/units | — |
| **`TCP_NODELAY`, kernel bypass** | Nagle delay, syscall/stack/interrupt cost | 7 |
| **Disable CPU frequency scaling** (performance governor) | clock-speed changes causing latency variance | — |

The unifying principle (repeat it in interviews): **HFT optimization is variance reduction, not just average-latency reduction.** Every knob above removes a source of *unpredictable* delay so the P99.9 tail collapses toward the median. Erik Rigtorp's low-latency guide (in your research doc) is the canonical reference — cite it.

### The mental model of a tuned hot thread

> A trading thread pinned to an isolated core, at real-time priority, that never sleeps (busy-polls the NIC via kernel bypass), never page-faults (pre-faulted + mlocked memory), never crosses NUMA (local allocation), never context-switches (owns its core), and never syscalls on the hot path. It just spins, reading packets and making decisions, at flat latency.

---

## Part B — Breadth appendix (know these exist)

Brief coverage so nothing is a total blank in an interview.

### Inter-process communication (IPC)
Ways processes (isolated address spaces — Module 1) communicate:
- **Shared memory** (`shm`, `mmap` a shared region) — fastest; the processes read/write the same physical pages (still need synchronization). Common between a feed handler process and a strategy process.
- **Pipes / named pipes (FIFOs)** — byte streams between processes.
- **Unix domain sockets** — like network sockets but local, faster (no TCP/IP stack).
- **Message queues, signals** (below).

### Signals
Asynchronous notifications to a process (`SIGINT`, `SIGTERM`, `SIGSEGV`). Handlers must be minimal and **async-signal-safe** (very limited what you can call). Used for shutdown, crash handling. Don't do real work in a signal handler.

### Filesystems & disk I/O
- Disk is orders of magnitude slower than RAM; **never on the hot path**. Log asynchronously (a separate thread/process drains a queue to disk).
- **Page cache**: the OS caches file data in RAM; reads/writes hit RAM, not disk, when cached.
- `O_DIRECT`, `fsync`, memory-mapped files (`mmap`) — know they exist.
- Async I/O: `io_uring` (modern Linux) for high-throughput async disk/network without thread-per-op.

### Networking extras
- **DNS** — name → IP resolution (a network round trip; cache it, never resolve on the hot path).
- **MTU / fragmentation** — max frame size (~1500 B Ethernet, or jumbo frames ~9000 B); packets larger fragment. Feeds are sized to avoid fragmentation.
- **NIC offloads** — checksum, segmentation (TSO/GRO) done in hardware; sometimes *disabled* in HFT for lower latency / more control.
- **Switched network latency** — even switch hops add nanoseconds; HFT co-locates servers in the exchange's datacenter (colocation) and tunes cable lengths.
- **FIX protocol** — the standard text-based order-entry protocol (over TCP); binary variants (SBE, ITCH/OUCH) for speed.

### Concurrency extras
- **Amdahl's law** — max speedup from parallelism is capped by the serial fraction; explains why more cores ≠ proportional speedup.
- **Livelock / starvation** — threads active but making no progress / a thread never getting the resource. Cousins of deadlock (Module 3).
- **Memory barriers / fences** — hardware ordering primitives underneath `std::atomic` orderings (`../guide/16`).

### Security / correctness (brief)
- **ASLR, DEP/NX, stack canaries** — OS/hardware exploit mitigations (relevant to why memory-safety bugs matter).
- **Sanitizers** — ASan (address), TSan (thread/data-race — invaluable for Module 3 code), UBSan. Run your order book under TSan/ASan; it catches races and memory bugs tests miss.

---

## Tradeoffs / interview "why"

- Be able to list the tuning knobs *and tie each to the unpredictability it removes* — that's the senior-level answer, not just naming them.
- "Variance reduction, not average reduction" is the framing that signals you understand HFT.
- For breadth topics: one sentence each is enough — know what it is and when it'd come up. Depth on Modules 1–7's priorities, breadth here.

## In the trading system

Deployment: colocated server, hot threads pinned to isolated NUMA-local cores at real-time priority, huge-paged mlocked pools, kernel-bypass NIC with IRQs routed away, HT and frequency-scaling disabled, async logging to a separate core/process, TSan/ASan in CI. This module is the operational layer that makes the code in Modules 1–7 (and the whole C++ guide) actually hit its latency targets in production.
