package dialysis.sat.cadical

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.refinement.StablePartition
import dialysis.refinement.perQueryColouring
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.verifyAutomorphism

data class PerQueryDriveResult(
    val orbits: List<List<Int>>,
    val queriesIssued: Int,
    val queriesSkippedWitness: Int,
    val queriesSkippedSeparation: Int,
    val queriesSkippedTooLarge: Int,
    val sat: Int,
    val unsat: Int,
    val unknown: Int,
    val witnessesVerified: Int,
    val witnessesRejected: Int,
    val perQueryMs: List<Long>,
    val perQueryVars: List<Int>,
)

/**
 * Drives ONE base colour class [members] entirely through Task 2.1's per-query filter, closing
 * witnesses via [SeparatingUnionFind] exactly like [driveToOrbitsCadicalParallel] does (Task 2.3:
 * "confirm the driver applies full generator closure") -- one verified SAT witness settles every
 * other pair it also happens to map, for free, via `union(w, alpha(w))` for every `w`.
 *
 * [maxEdgeClauses] is a hard safety cap, checked via [estimatePerQueryEncodingSize] BEFORE building
 * any solver -- solver-free, safe regardless of how large the real answer is. Deliberately checks
 * EDGE-CONFLICT CLAUSES, the same quantity [dialysis.benchmark.BenchmarkRunner]'s own
 * `edgeClauseThreshold` gate used to decide PER_QUERY mode was worth trying in the first place, not
 * the (usually much smaller, and NOT what actually dominates a dense graph's encoding cost) plain
 * admissible-VARIABLE-pair count. That gate only ever measures ONE representative pair from the
 * largest class before this per-class loop runs over every class and every pair in it, so a query
 * whose own edge-clause estimate is far larger than the one that justified entering PER_QUERY mode
 * is skipped here -- recorded in [PerQueryDriveResult.queriesSkippedTooLarge], never silently
 * dropped -- rather than risking the memory blowup this whole mechanism exists to avoid. This is a
 * per-query, not a per-class, decision: two different queries within the same class can have very
 * different edge-clause counts.
 *
 * [deadlineEpochMs] (default: none) is a hard WALL-CLOCK cutoff (`System.currentTimeMillis()`
 * value, not a duration) checked before each query -- once passed, every remaining pair in
 * [members] is counted as [PerQueryDriveResult.unknown] (the same meaning a per-query timeout
 * already has: an unresolved query, never treated as a proof of non-membership) and the loop
 * returns immediately, rather than potentially working through this one class for an unbounded
 * amount of wall-clock time. This exists because per-query timeouts alone only bound a SINGLE
 * query's cost, not the cost of a class with many members needing many queries before generator
 * closure catches up -- a campaign driving many instances needs the OUTER loop to keep making
 * forward progress regardless of how any one class behaves.
 */
fun drivePerQueryOrbits(
    g: Graph,
    base: Array<Content>,
    members: List<Int>,
    refine: (Graph, Array<Content>) -> StablePartition,
    timeoutMs: Long,
    maxEdgeClauses: Long,
    deadlineEpochMs: Long = Long.MAX_VALUE,
): PerQueryDriveResult {
    val uf = SeparatingUnionFind(g.n)
    var issued = 0
    var skippedWitness = 0
    var skippedSeparation = 0
    var skippedTooLarge = 0
    var sat = 0
    var unsat = 0
    var unknown = 0
    var verified = 0
    var rejected = 0
    val perQueryMs = mutableListOf<Long>()
    val perQueryVars = mutableListOf<Int>()

    // Individualizing a vertex is a pure function of (g, base, vertex, refine) -- cache across the
    // O(|members|^2) query loop so each vertex is only individualized/refined once, not once per
    // query it appears in.
    val colouringCache = HashMap<Int, Array<Content>>()
    fun colouringFor(vertex: Int): Array<Content> = colouringCache.getOrPut(vertex) { perQueryColouring(g, base, vertex, refine) }

    outer@ for (u in members) {
        for (v in members) {
            if (v == u) continue
            if (uf.find(u) == uf.find(v)) { skippedWitness++; continue }
            if (uf.separated(u, v)) { skippedSeparation++; continue }
            if (System.currentTimeMillis() >= deadlineEpochMs) {
                // Whatever hasn't been resolved or skipped by now is unresolved, exactly like an
                // individual query timing out -- count every remaining (u,v) pair still owed a
                // decision as BOTH issued and unknown (matching the meaning "issued, resolved
                // Unknown" already has elsewhere), so the sat+unsat+unknown == issued invariant
                // callers check still holds, and no pair is silently dropped from the accounting.
                var remaining = 0
                for (uu in members) for (vv in members) {
                    if (vv == uu) continue
                    if (uf.find(uu) == uf.find(vv)) continue
                    if (uf.separated(uu, vv)) continue
                    remaining++
                }
                issued += remaining
                unknown += remaining
                break@outer
            }

            val cU = colouringFor(u)
            val cV = colouringFor(v)

            val estimate = estimatePerQueryEncodingSize(g, cU, cV)
            // bijectionClauses (O(k^3) per matching colour bucket -- see its own doc) is checked
            // here too, not just edgeConflictClauses: this exact gap (a pair whose edge-clause
            // estimate stays under maxEdgeClauses but whose bijection cost is enormous) is what
            // blew up `ag2-16` on 2026-08-29 despite this "solver-free, safe regardless" check
            // already being in place -- it just wasn't measuring the quantity that mattered here.
            if (estimate.edgeConflictClauses > maxEdgeClauses || estimate.bijectionClauses > maxEdgeClauses) {
                skippedTooLarge++; continue
            }

            val t0 = System.currentTimeMillis()
            val (result, encoding) = queryPerQueryCadical(g, cU, cV, u, v, timeoutMs)
            perQueryMs.add(System.currentTimeMillis() - t0)
            perQueryVars.add(encoding.numVars)
            issued++
            when (result) {
                is SatQueryResult.Sat -> {
                    sat++
                    if (verifyAutomorphism(g, result.alpha)) {
                        verified++
                        for (w in 0 until g.n) uf.union(w, result.alpha[w])
                    } else {
                        rejected++
                    }
                }
                SatQueryResult.Unsat -> { unsat++; uf.markSeparated(u, v) }
                SatQueryResult.Unknown -> unknown++
            }
        }
    }

    val orbits = members.groupBy { uf.find(it) }.values.toList()
    return PerQueryDriveResult(
        orbits = orbits,
        queriesIssued = issued,
        queriesSkippedWitness = skippedWitness,
        queriesSkippedSeparation = skippedSeparation,
        queriesSkippedTooLarge = skippedTooLarge,
        sat = sat, unsat = unsat, unknown = unknown,
        witnessesVerified = verified, witnessesRejected = rejected,
        perQueryMs = perQueryMs, perQueryVars = perQueryVars,
    )
}
