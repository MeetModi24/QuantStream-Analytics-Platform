# C++ for HFT / Quant Developer — Complete Guide

A superset guide covering the full language surface an HFT C++ interview probes, in learncpp/Cherno style — plain explanations, focused snippets, tradeoffs, and the "why" behind each rule — then applying everything to building a **Limit Order Book & Matching Engine**.

Starts from the memory model (no assumed prior C++ beyond basic syntax, loops, functions) and climbs to lock-free systems programming.

## How to read this

Go top-to-bottom. Each module: concept → snippet → tradeoffs → interview "why" → how it appears in the order book. Snippets are illustrative (they show one idea), not full programs — but they're written to be correct so you can drop them into a file and compile with `-std=c++20`.

## Part 0 — Foundations (the memory mental model)

1. [Memory & object lifetime: stack vs heap](01-memory-lifetime.md)
2. [Pointers & references](02-pointers-references.md)
3. [Dynamic memory (`new`/`delete`) & why the heap is slow](03-dynamic-memory.md)
4. [lvalues, rvalues & an intro to moving](04-value-categories.md)

## Part I — Language mastery

5. [`const`, `constexpr`, `consteval`, const-correctness](05-const-constexpr.md)
6. [Classes: constructors, destructors, `this`, access control](06-classes.md)
7. [RAII & the Rule of 0/3/5](07-raii-rule-of-five.md)
8. [Operator overloading & value semantics](08-operator-overloading.md)
9. [Move semantics & rvalue references (deep)](09-move-semantics.md)
10. [Templates → Concepts](10-templates-concepts.md)
11. [Inheritance, virtual functions, vtables — and when not to use them](11-inheritance-virtual.md)
12. [The STL: containers, iterators, algorithms, complexity & cache](12-stl.md)
13. [Smart pointers & ownership](13-smart-pointers.md)
14. [Exceptions, `noexcept`, error handling without exceptions](14-exceptions-errors.md)

## Part II — Systems / HFT-specific C++

15. [Memory model, cache, alignment, false sharing](15-memory-cache.md)
16. [`std::atomic`, memory ordering, lock-free basics](16-atomics-lockfree.md)
17. [Zero-cost abstraction, CRTP, branch elimination](17-zero-cost-crtp.md)
18. [C++20 features that matter: concepts, `<bit>`, `std::span`, ranges](18-cpp20-features.md)

## Part III — The project

19. [Order-book architecture: building it with everything above](19-order-book-project.md)

## The mental through-line

> Objects live somewhere (stack/heap) for some lifetime. You refer to them via pointers/references. Ownership decides who frees what (RAII/smart pointers). Templates give abstraction with zero runtime cost. The hardware (cache, atomics) decides how fast it actually runs. The order book is where all of this meets a real problem.
