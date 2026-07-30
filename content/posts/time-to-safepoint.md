+++
title = "Time to safepoint is not in your GC log"
date = 2026-07-30T09:00:00+01:00
draft = true
description = "A short loop, five collectors, and a pause that does not appear where you would look for it."
jdk = "21"
tags = ["jvm", "safepoints", "latency"]
+++

DRAFT SKELETON. Nothing below the first section has been verified. Do not publish.

TODO before this is publishable:

- Confirm `UseCountedLoopSafepoints` and `LoopStripMiningIter` values under each
  collector with `-XX:+PrintFlagsFinal` on the target JDK. Do not trust secondary sources.
- Read the loop strip mining code in the hotspot source for the release being tested.
- Run the harness under Serial, Parallel, G1, and ZGC and record the safepoint log.
- Decide whether the piece needs JMH at all. It probably does not.

## What a safepoint pause is made of

When the VM needs all Java threads stopped, it does not stop them. It asks, and then
waits. Each thread notices the request at its next poll point and parks itself. Only
once the last thread has parked can the operation begin.

{{< fig src="/diagrams/safepoint-anatomy.svg" alt="Timeline of a safepoint pause showing three threads reaching poll points at different times" >}}
The gap between the request and the last thread parking is time to safepoint. It is not
GC work, so it does not appear in the pause figure most people read.
{{< /fig >}}

On JDK 21 the log gives five phases, not three:

```text
[1.068s][info][safepoint] Safepoint "GenCollectFull", Time since last: 1028233356 ns,
  Reaching safepoint: 21416081 ns, Cleanup: 23803 ns, At safepoint: 2605287 ns,
  Leaving safepoint: 66045 ns, Total: 24111216 ns
```

Reaching safepoint was 21 ms. At safepoint was 2.6 ms. A tool that reports only the
collection time would call this a 2.6 ms pause.

## The loop

{{< snippet file="code/safepoints/src/main/java/dev/kafi/safepoints/PollDemo.java" region="spinner" >}}

## Asking for a safepoint and timing the wait

{{< snippet file="code/safepoints/src/main/java/dev/kafi/safepoints/PollDemo.java" region="probe" >}}

## How this was measured

{{< env >}}

## Where the folklore is wrong

TODO. The claim to test: "int counted loops have no safepoint poll, use a long counter."
Loop strip mining, JDK-8186027, landed in JDK 10 and changes this, but reportedly is not
enabled under every collector. JDK-8322631 tracks enabling it for all of them. Verify
against the source and the running VM before writing a word of this section.

## How to observe it yourself

TODO. `-Xlog:safepoint`, and async-profiler's wall-clock mode for finding which method
the straggler was in.
