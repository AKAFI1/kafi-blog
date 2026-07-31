+++
title = "Stack vs heap: what actually lives where"
date = 2026-07-31T15:00:00+01:00
draft = false
description = "The two regions every running program uses, what Java actually puts in each, and the places the usual explanation quietly gets it wrong."
jdk = "21"
tags = ["jvm", "memory", "fundamentals"]
+++

The stack and the heap are where almost all of a running program's data sits. Most
explanations of them are roughly right and specifically wrong, usually in the same four
places. This is my attempt at getting it right, checked against a running VM rather than
recalled.

Both are regions of a *process's* virtual address space. They are backed by RAM, but a
given page may be in cache, in RAM, or swapped to disk, and the addresses a program sees
are virtual ones the operating system maps on its behalf. RAM is called volatile because
it loses its contents without power. That is all the word means here — it is not a
collective name for the regions a program runs in.

A process gets several regions. The machine code, a region for global and static data,
one stack per thread, and the heap.

That middle region is where the language matters. In C and C++, globals and statics are
laid out at compile time into fixed-size sections, and the size really is known before
the program runs. **Java does not work this way.** A `static` field lives in the heap,
attached to the class, and the class metadata describing it lives in Metaspace. Neither
is sized at compile time. Both are allocated as classes load.

## The stack

LIFO, exactly like the data structure. One stack per thread, never shared.

The stack holds *frames*. A frame is pushed when a method is called and popped when it
returns, which is what makes the call stack a record of how you got here.

A frame holds the return address, the parameters, and the method's local variables. Two
things worth separating, because the usual phrasing runs them together:

- A **local** of primitive type holds its value directly in the frame.
- A **field** of primitive type is part of its object, and lives wherever the object
  lives. For an object on the heap, so is the `int`.

So "primitives go on the stack" is only true of locals. It is not a property of the type.

{{< fig src="/diagrams/stack-frames.svg" >}}
A frame is pushed on call and popped on return. Because a method's locals are known when
it is compiled, the frame's size is known too, and allocating one is a pointer bump.
{{< /fig >}}

### How stack allocation works

- Frames are contiguous, and a frame's layout is fixed at compile time. Allocation is
  moving a pointer; deallocation is moving it back.
- Locals are allocated on entry and gone on return. There is nothing to free.
- Run out of stack and Java throws `StackOverflowError`. In C or C++ overflowing the
  stack is undefined behaviour, which on common platforms shows up as a segmentation
  fault, but the standard promises nothing.
- Access is fast for reasons worth naming: allocation is a pointer bump, the memory is
  private to one thread so no synchronisation is needed, and the top of the stack is
  almost always already in cache.

One clarification, because it is easy to state two contradictory things here. A thread's
stack has a **fixed maximum**, chosen when the thread starts. The *used* portion grows
and shrinks as calls come and go. On the build I checked, that maximum defaults to 2 MB:

```text
$ java -XX:+PrintFlagsFinal -version | grep ThreadStackSize
   intx ThreadStackSize    = 2048    {pd product} {default}
```

That is `2048` KB, per thread, on Zulu 21.0.10 running macOS on aarch64. It is
platform-dependent, and `-Xss` overrides it.

## The heap

The heap is the larger region, shared by every thread, and it is where objects go. Its
defining property is that lifetime is not tied to a call: an object outlives the method
that created it, for as long as something still refers to it.

So how do you refer to it?

In C you would say a pointer, and a pointer is a memory address. **Java gives you
references, and the distinction is not pedantic.** You cannot do arithmetic on a Java
reference, and a moving collector is free to relocate the object it names during
compaction. A reference is a handle the VM can keep valid across a move, not an address
you can rely on.

{{< fig src="/diagrams/stack-heap-references.svg" >}}
The frame holds references; the objects are elsewhere. Two locals can name one object,
in which case there is one object and mutating through either is visible through both.
{{< /fig >}}

{{< snippet file="code/memory/src/main/java/dev/kafi/memory/WhereItLives.java" region="frame" >}}

That sample prints `11`, and the reason it prints 11 rather than 10 is the aliasing:
`alias` and `order` are the same object, so `alias.quantity += 1` is visible through
`order`.

Sharing is where the heap gets dangerous. Because every thread can reach the same
object, unsynchronised mutation is a data race, and the result is not merely a stale read
— it can be a torn or impossible state.

Two failures are worth naming precisely, because both are usually described wrongly:

- A `NullPointerException` is what you get for dereferencing a reference whose value is
  `null`. `null` means *no object*. It is not a reference to an empty region of the heap;
  there is nothing on the other end to point at.
- Exhausting the heap gives you `OutOfMemoryError` — an `Error`, not an exception, and
  not `OutOfMemoryException`. It also has causes beyond "the heap filled up", such as
  the collector spending too much time recovering too little.

And the deallocation story is the opposite of the C one. In Java you **cannot** free an
object manually; there is no `free`, and `finalize` is deprecated for removal. The
garbage collector reclaims anything unreachable, automatically. The failure mode is not
forgetting to free — it is *retaining a reference* to something you are finished with,
at which point the collector is obliged to keep it. A cache or a static collection that
only ever grows is the usual culprit.

Automatic is not the same as deterministic, which is the part that matters if you care
about latency. You do not choose when the work happens.

### Regions of the heap

{{< fig src="/diagrams/heap-regions.svg" >}}
Metaspace is drawn outside the heap because that is where it is. It replaced the
permanent generation, which was removed in Java 8.
{{< /fig >}}

A generational collector splits the heap:

- **Young generation** — where new objects are allocated, in Eden. Most die young, and
  collecting the young generation is cheap because it copies out the few survivors rather
  than tracing the many dead.
- **Old generation** — objects that survive enough young collections get promoted here.
  Nothing is ever "explicitly removed": Java has no explicit removal. The old generation
  is reclaimed by major collections, less often and more expensively than the young one.

The third item in that list is usually wrong. It is not "permanent generation":

- **PermGen was removed in Java 8**, by [JEP 122](https://openjdk.org/jeps/122).
- Its replacement, **Metaspace**, holds class metadata in **native memory**, not in the
  heap.

That last claim is checkable rather than something to take on faith. Give the heap and
Metaspace different limits, and native memory tracking reports them as separate
categories:

```text
$ java -Xmx64m -XX:MaxMetaspaceSize=32m -XX:NativeMemoryTracking=summary \
       -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -version
-  Java Heap (reserved=67108864, committed=67108864)
-      Class (reserved=33623824, committed=134928)
```

64 MB of heap and roughly 32 MB of class space, counted separately. On this JDK there is
no `MaxPermSize` flag at all — `-XX:+PrintFlagsFinal` has nothing matching `PermSize`.

One caveat on the diagram: Eden, survivors and a promoted old generation are how Serial,
Parallel and G1 present the heap. The layout is a property of the collector, not of the
heap itself.

## Objects do not always go on the heap

"Objects go on the heap" is the default, not a guarantee. When C2 can prove an allocation
never escapes the method, it can take the object apart and keep the fields in registers
or in the frame — scalar replacement. Both switches are on by default:

```text
$ java -XX:+PrintFlagsFinal -version | grep -E 'DoEscapeAnalysis|EliminateAllocations'
   bool DoEscapeAnalysis      = true    {C2 product} {default}
   bool EliminateAllocations  = true    {C2 product} {default}
```

This matters when you are chasing allocation. An object that appears in the source need
not appear in the profile, and an object that escapes into a field or another thread will
never be eliminated no matter how short its apparent life.

## Key differences

| | Stack | Heap |
|---|---|---|
| Shared | One per thread, private | One per JVM, shared by all threads |
| Holds | Frames: return address, parameters, locals | Objects and arrays, plus static fields |
| Lifetime | Tied to the call; gone on return | Until nothing refers to it |
| Allocation | Pointer bump, layout fixed at compile time | Bump within a thread-local buffer, then slower paths |
| Reclaimed by | Popping the frame; nothing to free | The garbage collector, at a time you do not choose |
| Size | Fixed maximum per thread, `-Xss` | Bounded by `-Xmx`, grows within that |
| Exhaustion | `StackOverflowError` | `OutOfMemoryError` |
| Fragmentation | None; strictly LIFO | Possible, which is why collectors compact |

## Checking any of this yourself

Everything above came from these, on Zulu 21.0.10, macOS, aarch64:

```bash
java -XX:+PrintFlagsFinal -version | grep -iE 'ThreadStackSize|Metaspace|PermSize'
java -XX:+PrintFlagsFinal -version | grep -iE 'DoEscapeAnalysis|EliminateAllocations'
java -Xmx64m -XX:MaxMetaspaceSize=32m -XX:NativeMemoryTracking=summary \
     -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -version
```

Flag defaults move between releases and differ by platform, so the numbers above are
mine, not yours. Run them on the JDK you actually ship on.
