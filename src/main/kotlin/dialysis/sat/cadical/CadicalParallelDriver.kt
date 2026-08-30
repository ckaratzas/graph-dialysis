package dialysis.sat.cadical

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.verifyAutomorphism
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Parallel orbit driving via a shared dynamic work queue: [workers] threads all pull colour classes
 * from one shared atomic index into a list ordered largest-first, each processing whichever class
 * it drew until the queue is exhausted -- no class is pre-assigned to any particular worker.
 *
 * This replaces an earlier static schedule (longest-processing-time-first on `|C|^2`, computed once
 * before starting). That estimator predicts QUERY COUNT, but measured campaign data shows wall-clock
 * cost on families with near-uniform colour classes is NOT driven by query count -- it's driven by
 * a handful of individually hard queries near the per-query timeout, and which classes happen to
 * contain those hard queries has no correlation with `|C|`. Confirmed directly on
 * `cfi-rigid-d3-2160-01-1` (every class 6-9 members): 4 workers given IDENTICAL estimated cost
 * (4212 each) under the static schedule finished in 75s-114s -- a 40%+ spread from a metric that
 * carried no real signal for this family. Cost here is not predictable from any cheap structural
 * feature (SAT hardness isn't a function of class size), so the fix is not a better estimator, it's
 * not predicting at all: with a dynamic queue, a worker that draws a slow class simply ends up
 * processing fewer classes overall, bounding the straggler by at most ONE class's duration rather
 * than one worker's entire pre-assigned share. [ParallelDriveResult.stragglerRatio] now measures how
 * well the queue balanced itself (expect it near 1), not how good a cost estimate was -- there is no
 * cost estimate driving scheduling anymore.
 *
 * Classes are still queued largest-first -- not because size predicts cost well (it doesn't, per
 * above), but to avoid the specific tail case where the LAST class claimed off the queue also
 * happens to be the biggest one; standard practice for dynamic scheduling under unknown durations.
 *
 * The [SeparatingUnionFind] is SHARED across all workers (one instance, `synchronized` on every
 * access) rather than one private copy per worker. Queries are independent across colour classes,
 * so a private union-find per worker looks safe at first, but it isn't: a verified witness's
 * `for (w in 0 until g.n) uf.union(w, alpha[w])` can settle pairs in classes far from the one that
 * produced it (one automorphism can move many colour classes at once), and separation is transitive
 * across components (see [SeparatingUnionFind.union]'s carry-forward of `separatedWith`). With a
 * private union-find per worker, a fact discovered while processing class A is invisible to
 * whichever worker later draws class B, so that worker re-asks (and can time out on) questions a
 * single global worker would have skipped for free -- measured directly on `cfi-rigid-d3-3600-01-1`:
 * workers=7 with private state issued 774 queries against workers=1's 322, and the gap matched
 * `skippedWitness + skippedSeparation` almost exactly. Sharing the union-find/separation state (NOT
 * the CaDiCaL solvers -- those stay private per worker, see below) restores the query set exactly:
 * `skippedWitness` converges to the identical count regardless of worker count. Contention on the
 * shared structure is negligible (microsecond union-find ops guarding millisecond-to-second solves).
 *
 * Sharing state fixes what is KNOWN; it cannot fix WHEN it becomes known. Workers race through the
 * shared largest-first queue independently, so a class's separations only become available once
 * whichever worker drew it actually finishes -- unlike strictly sequential (workers=1) processing,
 * there is no guarantee an earlier-queued class has already contributed its facts by the time a
 * later class starts. A query can therefore still be issued with less context at higher worker
 * counts than the same query would have at workers=1, which shows up as substantially higher SAT
 * conflict counts on a small number of queries. This is a real, structural limit of parallelising
 * this driver, not a bug: pick [workers] and the per-query timeout so the cap does not bind at the
 * chosen worker count, rather than expecting linear scaling.
 *
 * An automorphism orbit never spans two colour classes, so sharing the union-find is still safe:
 * unioning globally on a witness never merges two different colours, it just reveals -- for free --
 * which OTHER same-coloured pairs (possibly in a class no worker has claimed yet) that witness also
 * happens to settle. The join step is no longer per-worker concatenation (that assumed disjoint
 * local orbits, which stops holding once a shared witness can span classes claimed by different
 * workers -- see the preserve/swap grouping overlap note at the join site): every worker reports
 * only the vertices it touched, and the caller does one single `uf.find()`-keyed grouping pass over
 * the union of all of them once every worker has finished.
 *
 * DELIBERATE SIMPLIFICATION vs a literal "build Φ(G,c) ONCE -- read-only, shared": CaDiCaL (IPASIR)
 * has no clause-database export/import or solver-cloning API, so a truly shared, once-built formula
 * can't be handed to N independent solver instances directly. Each worker instead independently
 * calls [buildCadicalEncoding] (and [buildCadicalEncodingSideSwapped] if needed) -- deterministic
 * construction from the same (g, colorOf) means every worker ends up with an IDENTICAL formula, just
 * recomputed rather than shared, which only affects encode time, not correctness. Report this cost
 * via [WorkerReport.encodeMs] rather than hiding it -- if it turns out to dominate on a machine with
 * genuinely free cores and no memory contention, that's the signal the "recompute instead of share"
 * simplification needs revisiting, not something to assume away.
 *
 * TWO-PASS SCHEDULING IS PER-WORKER-GLOBAL, NOT PER-CLASS: each worker short-passes every pair in
 * EVERY class it claims off the shared queue first, accumulating survivors across its whole share,
 * and only then long-passes those survivors -- mirroring [driveToOrbitsCadical]'s graph-wide
 * short-then-long structure, scoped to one worker's own solver. Collapsing this to short-then-long
 * per individual class (i.e. inside the claim loop) was tried and is wrong: a worker's solver only
 * warms up from the pairs it has actually queried, so resetting that warm-up on every class
 * starves later classes' hard queries of the accumulated learned clauses that made them resolve
 * cheaply in [driveToOrbitsCadical]. Measured directly: `cfi-rigid-d3-3600-02-1` at workers=1 (one
 * solver, so this MUST reduce to driveToOrbitsCadical's exact behavior) went from 15 unknowns with
 * per-class two-pass to 0 with per-worker-global two-pass, on the identical instance and timeout --
 * every one of the 322 queries resolved within the SHORT pass once warm-up wasn't being discarded.
 *
 * A shared warm-up prefix (every worker priming on the same small set of classes before racing for
 * the queue) was tried twice and dropped both times. First attempt: no synchronization, which let
 * whichever worker finished priming fastest get a head start and hoover up most of the queue
 * (521 of 560 classes to one worker, 12-14 each to the other three, straggler_ratio 1.35). Second
 * attempt: a barrier forcing every worker to finish priming before any of them starts racing --
 * this did NOT fix the imbalance (496/560 to one worker, ~21 each to the others, straggler_ratio
 * still 1.35, on the identical instance). That the barrier made no difference means the original
 * "unfair head start" diagnosis was wrong, or at least incomplete: the real cause is more likely a
 * small number of individually much harder classes that whichever worker draws gets stuck on for
 * close to the full timeout, repeatedly -- a synchronized start doesn't help if the imbalance comes
 * from WHICH classes get drawn, not WHEN the race starts. Not worth the added complexity
 * (barrier/exception-safety/extra parameters) for an effect that didn't reproduce.
 */
data class WorkUnit(val classIdx: Int, val admissiblePairsOnly: Boolean, val estimatedCost: Long)

data class WorkerReport(
    val workerIdx: Int,
    val threadName: String,
    val unitsProcessed: Int,
    val estimatedCost: Long,
    val encodeMs: Long,
    val solveMs: Long,
    val wallMs: Long,
)

data class ParallelDriveResult(
    val orbits: List<List<Int>>,
    val queriesIssued: Int,
    val queriesSkippedWitness: Int,       // find(u) == find(v)
    val queriesSkippedSeparation: Int,    // separated(u,v)
    val sat: Int,
    val unsat: Int,
    val unknown: Int,
    val witnessesVerified: Int,
    val witnessesRejected: Int,
    val perQueryMs: List<Long>,
    val workers: List<WorkerReport>,
) {
    val solveMsMax: Long get() = perQueryMs.maxOrNull() ?: 0
    val solveMsMedian: Long get() = if (perQueryMs.isEmpty()) 0 else perQueryMs.sorted()[perQueryMs.size / 2]
    val wallMsMax: Long get() = workers.maxOfOrNull { it.wallMs } ?: 0
    val wallMsMean: Double get() = if (workers.isEmpty()) 0.0 else workers.map { it.wallMs }.average()

    /** How well the dynamic work queue balanced itself -- near 1 is good; a large ratio means the
     *  queue drained unevenly (e.g. too few classes relative to workers) rather than an estimator
     *  problem, since scheduling no longer depends on any cost estimate. */
    val stragglerRatio: Double get() = if (wallMsMean == 0.0) 0.0 else wallMsMax / wallMsMean
}

private class WorkerResult(
    val touchedVertices: Set<Int>,
    val issued: Int,
    val skippedWitness: Int,
    val skippedSeparation: Int,
    val sat: Int,
    val unsat: Int,
    val unknown: Int,
    val verified: Int,
    val rejected: Int,
    val perQueryMs: List<Long>,
    val report: WorkerReport,
)

/**
 * Runs [colorOf]'s encoding across [workers] threads, colour classes drawn from a shared dynamic
 * work queue (see class doc) rather than a pre-computed static split. [useImpliedDistanceClauses]
 * toggles the (D) ablation (PI vs PI_DIST/WL_DIST in Part 2's config table) -- [dist] must be
 * non-null when true.
 */
fun driveToOrbitsCadicalParallel(
    g: Graph,
    colorOf: (Int) -> Content,
    workers: Int,
    timeoutMs: Long,
    shortMs: Long,
    useImpliedDistanceClauses: Boolean,
    dmax: Int,
    anchorK: Int,
    dist: Array<IntArray>?,
): ParallelDriveResult {
    require(workers >= 1) { "workers must be >= 1, got $workers" }
    require(!useImpliedDistanceClauses || dist != null) { "dist required when useImpliedDistanceClauses is true" }

    // Group sizes only, used to discover the queue's work units -- via computePreserveGroups/
    // computeSwapGroups, NOT a full buildCadicalEncoding(SideSwapped) call. A full build was
    // measured as pure waste here: a discarded CadicalSolver plus a discarded O(n^2) `varOf`
    // matrix (~1GiB at n≈16700) and every SAT clause, all for values nothing but `.groups` used.
    val preserveGroups = computePreserveGroups(g, colorOf)
    val swapGroups = computeSwapGroups(g, colorOf)

    val units = mutableListOf<WorkUnit>()
    for ((idx, members) in preserveGroups.withIndex()) {
        if (members.size <= 1) continue
        units.add(WorkUnit(idx, admissiblePairsOnly = false, estimatedCost = members.size.toLong() * members.size))
    }
    if (swapGroups != null) {
        for ((idx, members) in swapGroups.withIndex()) {
            if (members.size <= 1) continue
            units.add(WorkUnit(idx, admissiblePairsOnly = true, estimatedCost = members.size.toLong() * members.size))
        }
    }

    // Largest-first ordering only (see class doc for why) -- the shared index below is what
    // actually balances the load, not this ordering.
    val queue = units.sortedByDescending { it.estimatedCost }
    val nextIndex = AtomicInteger(0)

    // SHARED across every worker (see class doc) -- guarded by `synchronized(sharedUf)` at every
    // access site in runWorker, including reads (SeparatingUnionFind.find does path compression,
    // so even a "read" mutates it).
    val sharedUf = SeparatingUnionFind(g.n)

    val effectiveWorkers = minOf(workers, maxOf(queue.size, 1))
    val pool = Executors.newFixedThreadPool(effectiveWorkers)
    try {
        val futures = (0 until effectiveWorkers).map { w ->
            pool.submit(Callable {
                runWorker(g, colorOf, w, queue, nextIndex, timeoutMs, shortMs, useImpliedDistanceClauses, dmax, anchorK, dist, sharedUf)
            })
        }
        val results = futures.map { it.get() }

        // Every worker reports only the vertices IT touched -- with a shared union-find, a single
        // witness found while processing one class can settle pairs in a DIFFERENT class (see class
        // doc), including a class claimed by another worker, or the same vertices under the OTHER
        // grouping (a preserve class and a swap class can share members -- see
        // buildCadicalEncodingSideSwapped). So per-worker local orbits are no longer guaranteed
        // disjoint; group ALL touched vertices by their FINAL shared root in one pass instead of
        // concatenating each worker's own grouping. Every other worker has already returned by this
        // point (this runs after `futures.map { it.get() }`), so no synchronization is needed here.
        val touched = results.flatMap { it.touchedVertices }.toHashSet()
        val untouchedSingletons = (0 until g.n).filter { it !in touched }.map { listOf(it) }
        val orbits = touched.groupBy { sharedUf.find(it) }.values.toList() + untouchedSingletons

        return ParallelDriveResult(
            orbits = orbits,
            queriesIssued = results.sumOf { it.issued },
            queriesSkippedWitness = results.sumOf { it.skippedWitness },
            queriesSkippedSeparation = results.sumOf { it.skippedSeparation },
            sat = results.sumOf { it.sat },
            unsat = results.sumOf { it.unsat },
            unknown = results.sumOf { it.unknown },
            witnessesVerified = results.sumOf { it.verified },
            witnessesRejected = results.sumOf { it.rejected },
            perQueryMs = results.flatMap { it.perQueryMs },
            workers = results.map { it.report },
        )
    } finally {
        pool.shutdown()
    }
}

private data class PendingWork(val u: Int, val v: Int, val solver: CadicalSolver, val encoding: CadicalEncoding)

private fun runWorker(
    g: Graph,
    colorOf: (Int) -> Content,
    workerIdx: Int,
    queue: List<WorkUnit>,
    nextIndex: AtomicInteger,
    timeoutMs: Long,
    shortMs: Long,
    useImpliedDistanceClauses: Boolean,
    dmax: Int,
    anchorK: Int,
    dist: Array<IntArray>?,
    uf: SeparatingUnionFind,
): WorkerResult {
    val wallT0 = System.currentTimeMillis()
    val encodeT0 = System.currentTimeMillis()

    val (preserveSolver, preserveEncoding) = buildCadicalEncoding(g, colorOf)
    val swapPair = buildCadicalEncodingSideSwapped(g, colorOf)
    if (useImpliedDistanceClauses) {
        checkNotNull(dist)
        addImpliedDistanceClausesCadical(g, preserveSolver, preserveEncoding, dist, dmax, anchorK)
        if (swapPair != null) addImpliedDistanceClausesCadical(g, swapPair.first, swapPair.second, dist, dmax, anchorK)
    }
    val encodeMs = System.currentTimeMillis() - encodeT0

    // `uf` is SHARED across every worker (see class doc) -- every access, including reads, goes
    // through `synchronized(uf)` below, since SeparatingUnionFind.find does path compression and
    // so mutates even on a "read". Contention is negligible: microsecond union-find ops guarding
    // millisecond-to-second solves.
    fun isOrbitMate(a: Int, b: Int): Boolean = synchronized(uf) { uf.find(a) == uf.find(b) }
    fun isSeparated(a: Int, b: Int): Boolean = synchronized(uf) { uf.separated(a, b) }
    fun applyWitness(alpha: IntArray) = synchronized(uf) { for (w in 0 until g.n) uf.union(w, alpha[w]) }
    fun applySeparation(a: Int, b: Int) = synchronized(uf) { uf.markSeparated(a, b) }

    var issued = 0; var skippedWitness = 0; var skippedSeparation = 0
    var sat = 0; var unsat = 0; var unknown = 0; var verified = 0; var rejected = 0
    val perQueryMs = mutableListOf<Long>()
    val touchedClasses = mutableListOf<List<Int>>()

    fun attempt(solver: CadicalSolver, encoding: CadicalEncoding, u: Int, v: Int, ms: Long): SatQueryResult {
        val t0 = System.currentTimeMillis()
        val r = queryOrbitMateCadical(solver, encoding, u, v, ms)
        perQueryMs.add(System.currentTimeMillis() - t0)
        return r
    }

    fun recordFinal(q: PendingWork, r: SatQueryResult) {
        when (r) {
            is SatQueryResult.Sat -> {
                sat++
                if (verifyAutomorphism(g, r.alpha)) { verified++; applyWitness(r.alpha) } else rejected++
            }
            SatQueryResult.Unsat -> { unsat++; applySeparation(q.u, q.v) }
            SatQueryResult.Unknown -> unknown++
        }
    }

    fun collectClassQueue(solver: CadicalSolver, encoding: CadicalEncoding, admissiblePairsOnly: Boolean, classIdx: Int): List<PendingWork> {
        val members = encoding.groups[classIdx]
        touchedClasses.add(members)
        val result = mutableListOf<PendingWork>()
        for (u in members) for (v in members) {
            if (v == u) continue
            if (admissiblePairsOnly && encoding.varOf[u][v] < 0) continue
            result.add(PendingWork(u, v, solver, encoding))
        }
        return result
    }

    val solveT0 = System.currentTimeMillis()
    var unitsProcessed = 0
    var estimatedCostProcessed = 0L
    val survivors = mutableListOf<PendingWork>()

    // Both solvers are closed in `finally` -- an exception from anywhere in the two-pass loop
    // below (e.g. verifyAutomorphism, the synchronized union-find helpers) must never leak the
    // native CaDiCaL handle(s) for this worker for the rest of the JVM's life.
    try {
    // PASS 1 (short): claim classes from the shared queue one at a time until it's exhausted,
    // short-pass every pair in each claimed class, accumulating survivors GLOBALLY across every
    // class this worker ends up touching -- matching driveToOrbitsCadical's graph-wide two-pass
    // structure (one graph-wide short sweep, THEN one graph-wide long sweep on survivors) instead
    // of resetting the warm-up granularity down to one class at a time. A worker's own solver only
    // gets as warm as the pairs IT has queried so far (the CaDiCaL solvers stay private per worker,
    // see class doc), so collapsing this down to per-class two-pass would cost most of that warm-up
    // for no reason -- confirmed to matter: at workers=1 this must reduce to driveToOrbitsCadical's
    // exact behavior, and per-class two-pass measurably did not.
    while (true) {
        val idx = nextIndex.getAndIncrement()
        if (idx >= queue.size) break
        val unit = queue[idx]
        unitsProcessed++
        estimatedCostProcessed += unit.estimatedCost
        val (solver, encoding) = if (unit.admissiblePairsOnly) {
            checkNotNull(swapPair) { "scheduled a swap work unit but no swap encoding exists" }
            swapPair
        } else {
            preserveSolver to preserveEncoding
        }
        for (q in collectClassQueue(solver, encoding, unit.admissiblePairsOnly, unit.classIdx)) {
            if (isOrbitMate(q.u, q.v)) { skippedWitness++; continue }
            if (isSeparated(q.u, q.v)) { skippedSeparation++; continue }
            when (val r = attempt(q.solver, q.encoding, q.u, q.v, shortMs)) {
                SatQueryResult.Unknown -> survivors.add(q)
                else -> { issued++; recordFinal(q, r) }
            }
        }
    }

    // PASS 2 (long): only the survivors from every class this worker touched, now as warm as this
    // worker's solver ever gets -- from its own full short-pass history, not just one class's.
    for (q in survivors) {
        if (isOrbitMate(q.u, q.v)) { skippedWitness++; continue }
        if (isSeparated(q.u, q.v)) { skippedSeparation++; continue }
        issued++
        recordFinal(q, attempt(q.solver, q.encoding, q.u, q.v, timeoutMs))
    }
    } finally {
        preserveSolver.close()
        swapPair?.first?.close()
    }
    val solveMs = System.currentTimeMillis() - solveT0

    val touchedVertices = touchedClasses.flatten().toHashSet()

    return WorkerResult(
        touchedVertices = touchedVertices,
        issued = issued, skippedWitness = skippedWitness, skippedSeparation = skippedSeparation,
        sat = sat, unsat = unsat, unknown = unknown, verified = verified, rejected = rejected,
        perQueryMs = perQueryMs,
        report = WorkerReport(
            workerIdx = workerIdx, threadName = Thread.currentThread().name,
            unitsProcessed = unitsProcessed, estimatedCost = estimatedCostProcessed,
            encodeMs = encodeMs, solveMs = solveMs, wallMs = System.currentTimeMillis() - wallT0,
        ),
    )
}
