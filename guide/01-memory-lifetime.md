# Module 1 — Memory & object lifetime: stack vs heap

Before pointers, before classes, you need a correct model of **where data lives** and **when it dies**. Nearly every C++ bug and every performance decision traces back to this.

## The regions of memory

When your program runs, the OS hands it memory divided into regions. Four matter conceptually; two matter constantly:

| Region | Holds | Lifetime | Speed |
|--------|-------|----------|-------|
| **Stack** | local variables, function call frames | automatic (scope-bound) | fastest |
| **Heap** (free store) | objects you allocate explicitly | manual (you decide) | slower, variable |
| Static/global | globals, `static` locals | whole program | — |
| Code/text | the compiled instructions | whole program | — |

## The stack: automatic storage

```cpp
void foo() {
    int x = 5;       // created on the stack
    double y = 3.2;  // created right after x
}                    // <-- x and y DESTROYED automatically here
```

Calling `foo()` reserves a **stack frame** for its locals. Returning releases the whole frame by moving one register (the stack pointer). You clean up nothing. This is **automatic storage duration**.

The stack grows/shrinks as calls nest:

```cpp
int main() {
    int a = 1;   // frame: [a]
    foo();       // push:  [a][x,y]  then pop -> [a]
    return 0;
}                // [a] popped, a destroyed
```

**Why it's fast:** allocating a local is essentially free — part of reserving the frame, one subtraction on the stack pointer. No search, no bookkeeping. In HFT you keep everything on the stack when you can.

## Lifetime vs scope

- **Lifetime** = when the object exists in memory.
- **Scope** = where in code you can name it.

For stack variables they coincide: enter the block, it's born; leave the block, it dies. This tight coupling is C++'s central guarantee and the basis of RAII (Module 7): tie a resource to a stack object, and it's released automatically when scope exits — *even on an exception*.

```cpp
{
    int x = 5;   // lifetime + scope begin
}                // both end here
```

## The trap this prevents: dangling references to locals

```cpp
int* getPointer() {
    int local = 42;
    return &local;   // address of a stack variable
}                    // <-- local DESTROYED here

// caller: int* p = getPointer();  // p points to dead memory -> UB
```

`local` dies on return; the returned address points at abandoned stack. This is a **dangling pointer** — one of the most common serious C++ bugs. Rule: **never return the address/reference of a local.** The heap exists partly to hold data that must outlive the function that made it.

## Object initialization (learncpp detail worth nailing)

C++ has several init syntaxes; prefer brace init:

```cpp
int a;        // default-init: for int, INDETERMINATE value (garbage) — reading it is UB
int b = 5;    // copy-init
int c{5};     // direct-list (brace) init — preferred; also catches narrowing
int d{};      // value-init -> 0 for built-ins
```

`int d{}` zero-initializes; `int a;` at block scope leaves garbage. Uninitialized reads are a classic bug and undefined behavior.

## Tradeoffs / interview "why"

- **Stack**: fast, automatic, but small (~1–8 MB) and scope-bound. Blowing it (deep recursion, huge arrays) = stack overflow.
- **Heap**: large, lifetime you control, but slower and error-prone (Module 3).
- Interviewers ask "stack or heap?" to see if you reach for the heap only when lifetime demands it — not by habit.

## In the order book

Orders must rest in the book across many function calls (until filled/cancelled) — they can't live on the stack of the function that received them, or they'd vanish. So order storage is heap-backed (or, better, a pre-allocated pool — Module 3). Transient things (a loop index, a temporary price) stay on the stack. Knowing which is which is the first design decision.
