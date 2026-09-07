# Operating Systems & Networking for HFT — Focused Guide

A companion to the C++ guide (`../guide/`). This one covers the **OS and hardware mechanisms** underneath low-latency code, plus **networking**. It's deliberately tighter than the C++ guide — deep on your priority topics, brief on the rest — so it's finishable.

## Design of this guide

- **Deep (your listed priorities):** multithreading, cross-thread safety, memory management, cache coherency, custom memory pools — but from the **OS/hardware angle** (the C++ guide covered the language side; here we cover the machine underneath). Plus **networking (TCP/IP, UDP, sockets)** in full, since it's new.
- **Brief (breadth):** everything else an interview might touch — module 8's appendix.
- **No duplication:** where the C++ guide already nailed something, I cross-reference `../guide/NN` instead of repeating.

## Modules

1. [OS foundations: processes, threads, scheduler, syscalls, virtual memory](01-os-foundations.md)
2. [Multithreading: lifecycle, `std::thread`/`jthread`, pools, parallelism vs concurrency](02-multithreading.md)
3. [Cross-thread safety: mutexes, condvars, races, deadlock, patterns](03-cross-thread-safety.md)
4. [Memory management (OS view): virtual memory, fragmentation, huge pages, NUMA](04-memory-management-os.md)
5. [Cache coherency: MESI, false sharing, cache-friendly code](05-cache-coherency.md)
6. [Custom memory pools: arena, slab, free-list; integration](06-custom-memory-pools.md)
7. [Networking: TCP/IP, UDP, sockets, multicast, kernel bypass](07-networking.md)
8. [Low-latency OS tuning + breadth appendix](08-tuning-and-breadth.md)

## The through-line

> Your C++ compiles to instructions; the **OS** decides when they run (scheduler), where data lives (virtual memory), and how threads coordinate (synchronization); the **hardware** decides how fast memory moves (cache/MESI); the **network stack** decides how fast data arrives (TCP/UDP, kernel bypass). HFT is the discipline of removing the OS and stack from the hot path wherever they add unpredictable latency.

Cross-refs: C++ cache/atomics/pools live in `../guide/15`, `../guide/16`, `../guide/03`, `../guide/13`.
