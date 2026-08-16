package dialysis.sat.cadical

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.verifyAutomorphism
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * BENCHMARK_SPEC.md Part 3 -- parallel orbit driving. Queries in distinct colour
 * classes are independent (no union crosses a colour class), so each worker gets its OWN
 * [SeparatingUnionFind] restricted (in practice, not in allocation -- see below) to its assigned
 * classes, with NO state exchanged between workers and no locking. A worker's local orbits for its
 * assigned classes are already the correct GLOBAL orbits for those vertices (an automorphism orbit
 * never spans two colour classes), so the join step is pure concatenation.
 *
 * DELIBERATE SIMPLIFICATION vs the spec's literal "build Φ(G,c) ONCE -- read-only, shared": CaDiCaL
 * (IPASIR) has no clause-database export/import or solver-cloning API, so a truly shared,
 * once-built formula can't be handed to N independent solver instances directly. Each worker
 * instead independently calls [buildCadicalEncoding] (and [buildCadicalEncodingSideSwapped] if
 * needed) -- deterministic construction from the same (g, colorOf) means every worker ends up with
 * an IDENTICAL formula, just recomputed rather than shared, which only affects the (typically
 * small, see BENCHMARK_SPEC.md's own Amdahl accounting) encode time, not correctness. Report this
 * cost via [WorkerReport.encodeMs] rather than hiding it.
 */
data class WorkUnit(val classIdx: Int, val admissiblePairsOnly: Boolean, val estimatedCost: Long)

data class WorkerReport(
    val workerIdx: Int,
    val unitsAssigned: Int,
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

    /** BENCHMARK_SPEC.md 3.2 -- near 1 means the LPT schedule is good; large means one class
     *  dominates and the speedup is capped by it. */
    val stragglerRatio: Double get() = if (wallMsMean == 0.0) 0.0 else wallMsMax / wallMsMean
}

private class WorkerResult(
    val localOrbits: List<List<Int>>,
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
 * Runs [colorOf]'s encoding across [workers] threads, colour classes partitioned by
 * longest-processing-time-first on `|C|^2` (BENCHMARK_SPEC.md 3.2). [useImpliedDistanceClauses]
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

    // Reference encoding, used ONLY to discover group sizes for scheduling -- discarded, never
    // driven against (each worker builds its own, see class doc).
    val (refSolver, refEncoding) = buildCadicalEncoding(g, colorOf)
    refSolver.close()
    val refSwap = buildCadicalEncodingSideSwapped(g, colorOf)
    refSwap?.first?.close()

    val units = mutableListOf<WorkUnit>()
    for ((idx, members) in refEncoding.groups.withIndex()) {
        if (members.size <= 1) continue
        units.add(WorkUnit(idx, admissiblePairsOnly = false, estimatedCost = members.size.toLong() * members.size))
    }
    if (refSwap != null) {
        for ((idx, members) in refSwap.second.groups.withIndex()) {
            if (members.size <= 1) continue
            units.add(WorkUnit(idx, admissiblePairsOnly = true, estimatedCost = members.size.toLong() * members.size))
        }
    }

    val effectiveWorkers = minOf(workers, maxOf(units.size, 1))
    val perWorkerUnits = Array(effectiveWorkers) { mutableListOf<WorkUnit>() }
    val perWorkerCost = LongArray(effectiveWorkers)
    for (u in units.sortedByDescending { it.estimatedCost }) {
        val w = perWorkerCost.indices.minByOrNull { perWorkerCost[it] }!!
        perWorkerUnits[w].add(u)
        perWorkerCost[w] += u.estimatedCost
    }

    val pool = Executors.newFixedThreadPool(effectiveWorkers)
    try {
        val futures = (0 until effectiveWorkers).map { w ->
            pool.submit(Callable {
                runWorker(g, colorOf, w, perWorkerUnits[w], timeoutMs, shortMs, useImpliedDistanceClauses, dmax, anchorK, dist)
            })
        }
        val results = futures.map { it.get() }

        // A vertex whose PRESERVE colour class is a singleton can still be "touched" via a
        // non-singleton SWAP class (a different grouping over the same vertices -- same colour
        // and degree, but the side split is dropped), so checking preserve-singleton classes
        // alone would double-count it (once from whichever worker's local orbit it really landed
        // in via the swap pass, once again as a spurious trivial singleton here). Instead: any
        // vertex no worker ever touched (in EITHER pass) is trivially its own orbit -- nothing
        // shares its colour/side to be an orbit-mate under either grouping.
        val touched = results.flatMap { it.localOrbits }.flatten().toHashSet()
        val untouchedSingletons = (0 until g.n).filter { it !in touched }.map { listOf(it) }

        return ParallelDriveResult(
            orbits = results.flatMap { it.localOrbits } + untouchedSingletons,
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

private data class PendingWork(val u: Int, val v: Int)

private fun runWorker(
    g: Graph,
    colorOf: (Int) -> Content,
    workerIdx: Int,
    assigned: List<WorkUnit>,
    timeoutMs: Long,
    shortMs: Long,
    useImpliedDistanceClauses: Boolean,
    dmax: Int,
    anchorK: Int,
    dist: Array<IntArray>?,
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

    val uf = SeparatingUnionFind(g.n)
    var issued = 0; var skippedWitness = 0; var skippedSeparation = 0
    var sat = 0; var unsat = 0; var unknown = 0; var verified = 0; var rejected = 0
    val perQueryMs = mutableListOf<Long>()
    val touchedClasses = mutableListOf<List<Int>>()

    fun queryAll(solver: CadicalSolver, encoding: CadicalEncoding, admissiblePairsOnly: Boolean, classIdx: Int) {
        val members = encoding.groups[classIdx]
        touchedClasses.add(members)
        val queue = mutableListOf<PendingWork>()
        for (u in members) for (v in members) {
            if (v == u) continue
            if (admissiblePairsOnly && encoding.varOf[u][v] < 0) continue
            queue.add(PendingWork(u, v))
        }

        fun attempt(u: Int, v: Int, ms: Long): SatQueryResult {
            val t0 = System.currentTimeMillis()
            val r = queryOrbitMateCadical(solver, encoding, u, v, ms)
            perQueryMs.add(System.currentTimeMillis() - t0)
            return r
        }

        val survivors = mutableListOf<PendingWork>()
        for (q in queue) {
            if (uf.find(q.u) == uf.find(q.v)) { skippedWitness++; continue }
            if (uf.separated(q.u, q.v)) { skippedSeparation++; continue }
            when (val r = attempt(q.u, q.v, shortMs)) {
                SatQueryResult.Unknown -> survivors.add(q)
                else -> {
                    issued++
                    when (r) {
                        is SatQueryResult.Sat -> {
                            sat++
                            if (verifyAutomorphism(g, r.alpha)) { verified++; for (w in 0 until g.n) uf.union(w, r.alpha[w]) } else rejected++
                        }
                        SatQueryResult.Unsat -> { unsat++; uf.markSeparated(q.u, q.v) }
                        else -> {}
                    }
                }
            }
        }
        for (q in survivors) {
            if (uf.find(q.u) == uf.find(q.v)) { skippedWitness++; continue }
            if (uf.separated(q.u, q.v)) { skippedSeparation++; continue }
            issued++
            when (val r = attempt(q.u, q.v, timeoutMs)) {
                is SatQueryResult.Sat -> {
                    sat++
                    if (verifyAutomorphism(g, r.alpha)) { verified++; for (w in 0 until g.n) uf.union(w, r.alpha[w]) } else rejected++
                }
                SatQueryResult.Unsat -> { unsat++; uf.markSeparated(q.u, q.v) }
                SatQueryResult.Unknown -> unknown++
            }
        }
    }

    val solveT0 = System.currentTimeMillis()
    for (unit in assigned) {
        if (unit.admissiblePairsOnly) {
            checkNotNull(swapPair) { "scheduled a swap work unit but no swap encoding exists" }
            queryAll(swapPair.first, swapPair.second, admissiblePairsOnly = true, classIdx = unit.classIdx)
        } else {
            queryAll(preserveSolver, preserveEncoding, admissiblePairsOnly = false, classIdx = unit.classIdx)
        }
    }
    val solveMs = System.currentTimeMillis() - solveT0

    preserveSolver.close()
    swapPair?.first?.close()

    val touchedVertices = touchedClasses.flatten().toHashSet()
    val localOrbits = touchedVertices.groupBy { uf.find(it) }.values.toList()

    val estimatedCost = assigned.sumOf { it.estimatedCost }
    return WorkerResult(
        localOrbits = localOrbits,
        issued = issued, skippedWitness = skippedWitness, skippedSeparation = skippedSeparation,
        sat = sat, unsat = unsat, unknown = unknown, verified = verified, rejected = rejected,
        perQueryMs = perQueryMs,
        report = WorkerReport(
            workerIdx = workerIdx, unitsAssigned = assigned.size, estimatedCost = estimatedCost,
            encodeMs = encodeMs, solveMs = solveMs, wallMs = System.currentTimeMillis() - wallT0,
        ),
    )
}