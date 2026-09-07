# Module 1 — OS foundations: processes, threads, scheduler, syscalls, virtual memory

The OS is the layer between your program and the hardware. For HFT the recurring theme is: **the OS adds convenience and unpredictability; you keep it off the hot path.** This module is the mental model everything else builds on.

## Process vs thread

- **Process** — a running program with its *own* virtual address space (isolated memory), file descriptors, etc. Two processes can't touch each other's memory directly (that isolation is a feature, enforced by the OS + hardware).
- **Thread** — a unit of execution *within* a process. Threads of one process **share the same address space** (same heap, globals, code) but have their **own stack and registers**.

Consequence that drives everything in Modules 2–3: threads share memory → cheap communication, but you must synchronize access (data races). Processes are isolated → safer, but IPC is more expensive.

```
Process
 ├─ address space (heap, globals, code)  ← shared by all threads
 ├─ Thread 1: own stack + registers + program counter
 └─ Thread 2: own stack + registers + program counter
```

## The scheduler and context switches

The OS scheduler decides which thread runs on which CPU core and when. With more threads than cores, it **time-slices**: run thread A, pause it, run thread B, etc.

A **context switch** = saving one thread's registers/state and loading another's. It costs:
- Direct: ~1–5 µs of CPU saving/restoring state.
- Indirect (worse): the new thread's data isn't in cache → **cache pollution**, so the first accesses miss (~100 ns each). TLB may flush too.

**HFT implication:** context switches are latency poison. The fix (Module 8) is **CPU pinning + core isolation** — dedicate a core to your hot thread so the scheduler never preempts it. A busy-polling trading thread that owns its core never context-switches.

### Preemption, time slices, priorities

- **Preemptive** scheduling: the OS can interrupt a thread at any time (via a timer interrupt). You don't control when.
- Real-time priorities (`SCHED_FIFO`, `SCHED_RR` on Linux) let a thread run until *it* yields — used for hot threads.
- `nice` values tune normal-priority scheduling.

## System calls — crossing into the kernel

A **syscall** is how user code asks the kernel to do privileged work (I/O, memory from the OS, networking). It's a **mode switch** (user → kernel → user) costing ~100 ns–1 µs, plus cache/TLB effects.

Examples: `read`, `write`, `send`, `recv`, `mmap`, `futex` (underlies mutexes). Every one is a boundary crossing.

**HFT implication:** syscalls on the hot path = unpredictable latency. This is *the* motivation for **kernel bypass** (Module 7): talk to the network card directly from user space, skipping `send`/`recv` entirely. Also why you pre-allocate memory (no `mmap`/`brk` during trading — Module 4) and avoid logging-to-disk on the hot path.

## Virtual memory (the big one)

Every process sees a private, contiguous **virtual address space**. The hardware **MMU** + OS translate virtual addresses → physical RAM addresses, in fixed-size **pages** (typically 4 KB).

Why it exists: isolation (processes can't see each other), flexibility (physical memory can be fragmented/swapped while virtual looks contiguous), and protection (pages marked read-only/no-execute).

### Page tables and the TLB

- **Page table**: the OS's map from virtual pages → physical frames. Walking it costs several memory accesses.
- **TLB (Translation Lookaside Buffer)**: a small hardware cache of recent translations. A **TLB hit** makes translation ~free; a **TLB miss** triggers a page-table walk (slow).
- **Page fault**: accessing a page not currently in physical RAM → the OS must load it (from disk if swapped, or allocate a fresh frame). Very slow (µs–ms). The *first* touch of freshly-allocated memory faults it in.

**HFT implications (all Module 8 techniques):**
- **Huge pages** (2 MB / 1 GB instead of 4 KB): fewer pages → fewer TLB entries needed → fewer TLB misses covering the same memory. Big win for large working sets.
- **Pre-fault / pre-touch memory** at startup (write to every page) so no page faults happen during trading. Combined with `mlock` to pin pages in RAM (never swapped).
- Pre-allocation (Module 4/6) means the allocator never asks the OS mid-trade.

## Interrupts

Hardware signals the CPU (a packet arrived, a timer fired) via **interrupts**, which preempt whatever's running to run an **interrupt handler (ISR)**. Network packets normally arrive as interrupts.

**HFT implications:**
- Interrupts cause jitter (they preempt your hot thread). Fix: **interrupt affinity** — route device interrupts to *other* cores, away from your trading core.
- **Busy-polling** the NIC (kernel bypass) replaces interrupt-driven receipt: instead of waiting to be interrupted, the thread spins reading the card — lower and more predictable latency at the cost of burning a core.

## Tradeoffs / interview "why"

- Process (isolated, expensive IPC) vs thread (shared memory, cheap but needs sync) — know the tradeoff cold.
- Context switch cost = direct (state save) + indirect (cache/TLB pollution); avoided via pinning.
- Syscalls are mode-switch boundaries; hot paths minimize them (kernel bypass, pre-allocation).
- Virtual memory / TLB / page faults: explain why huge pages and pre-faulting reduce tail latency.
- The unifying HFT idea: **determinism**. Every OS mechanism that can fire unpredictably (preemption, page fault, interrupt, syscall) is something you pin down or remove on the hot path.

## In the order book / trading system

The matching thread is pinned to an isolated core, runs at real-time priority, touches only pre-faulted + `mlock`ed pooled memory (no page faults, no `new`), and receives market data via a busy-polled kernel-bypass NIC (no syscalls, no interrupts on its core). Everything in this module is a lever to make that thread's latency flat.
