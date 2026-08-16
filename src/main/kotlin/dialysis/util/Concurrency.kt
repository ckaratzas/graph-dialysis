package dialysis.util

import java.util.concurrent.ForkJoinPool

/**
 * A [ForkJoinPool] sized well below [Runtime.availableProcessors] for `.parallelStream()` call
 * sites (`DecompositionStore.build`, `initialPhase`'s Phase 2) that turned out to compete for
 * CPU/memory with everything else running on the machine when run through the JVM's default
 * common pool (sized to cores-1 — 15 threads here, each doing O(n) BFS work on a large graph
 * simultaneously). Submit stream operations into it explicitly, e.g.
 * `boundedPool.submit { items.parallelStream().forEach { ... } }.get()` — a stream's parallel
 * ops run in whichever pool is invoking them, per `java.util.stream`'s own documented behavior.
 * Size configurable via `-Ddialysis.parallelism=N`, default 4.
 */
val boundedPool: ForkJoinPool = ForkJoinPool(Integer.getInteger("dialysis.parallelism", 4))