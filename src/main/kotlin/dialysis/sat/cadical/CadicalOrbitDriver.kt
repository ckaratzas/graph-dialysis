package dialysis.sat.cadical

import dialysis.graph.Graph
import dialysis.sat.OrbitDriveResult
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.verifyAutomorphism

/**
 * Drives every same-colour pair in [preserveEncoding] (and [swapPair], if the instance is an
 * equal-sized bipartition) to a definitive verdict, using [SeparatingUnionFind] so a verified
 * witness's full permutation closes every pair it also connects, and a verified non-witness
 * closes every pair between its two components -- most pairs are never queried at all.
 *
 * Two-pass timeout scheduling: a cheap [shortMs] sweep over every pair first, then only the
 * survivors pay the long [timeoutMs] budget -- a small number of genuinely hard queries dominate
 * wall-clock time far more than query count does, so resolving the easy majority cheaply first
 * (which can also close some of the hard ones via generator closure before they're ever attempted)
 * is what actually matters.
 */
private data class PendingCadicalPair(val u: Int, val v: Int, val solver: CadicalSolver, val encoding: CadicalEncoding)

fun driveToOrbitsCadical(
    g: Graph,
    preserveSolver: CadicalSolver,
    preserveEncoding: CadicalEncoding,
    swapPair: Pair<CadicalSolver, CadicalEncoding>?,
    timeoutMs: Long = 60_000,
    shortMs: Long = 1_000,
): OrbitDriveResult {
    val uf = SeparatingUnionFind(g.n)
    var queriesIssued = 0
    var sat = 0
    var unsat = 0
    var unknown = 0
    var skipped = 0
    var generators = 0
    var verified = 0

    fun recordFinal(q: PendingCadicalPair, r: SatQueryResult) {
        queriesIssued++
        when (r) {
            is SatQueryResult.Sat -> {
                sat++
                check(verifyAutomorphism(g, r.alpha)) { "rejected witness for (${q.u},${q.v}) -- the encoding is wrong, not this query; stop and report" }
                verified++
                generators++
                for (w in 0 until g.n) uf.union(w, r.alpha[w])
            }
            SatQueryResult.Unsat -> { unsat++; uf.markSeparated(q.u, q.v) }
            SatQueryResult.Unknown -> unknown++
        }
    }

    fun collect(solver: CadicalSolver, encoding: CadicalEncoding, admissiblePairsOnly: Boolean): List<PendingCadicalPair> {
        val result = mutableListOf<PendingCadicalPair>()
        for (members in encoding.groups) {
            if (members.size <= 1) continue
            for (u in members) {
                for (v in members) {
                    if (v == u) continue
                    if (admissiblePairsOnly && encoding.varOf[u][v] < 0) continue
                    result.add(PendingCadicalPair(u, v, solver, encoding))
                }
            }
        }
        return result
    }

    val queue = collect(preserveSolver, preserveEncoding, admissiblePairsOnly = false) +
        (swapPair?.let { (s, e) -> collect(s, e, admissiblePairsOnly = true) } ?: emptyList())

    val survivors = mutableListOf<PendingCadicalPair>()
    for (q in queue) {
        if (uf.find(q.u) == uf.find(q.v) || uf.separated(q.u, q.v)) { skipped++; continue }
        when (val r = queryOrbitMateCadical(q.solver, q.encoding, q.u, q.v, shortMs)) {
            SatQueryResult.Unknown -> survivors.add(q)
            else -> recordFinal(q, r)
        }
    }
    for (q in survivors) {
        if (uf.find(q.u) == uf.find(q.v) || uf.separated(q.u, q.v)) { skipped++; continue }
        recordFinal(q, queryOrbitMateCadical(q.solver, q.encoding, q.u, q.v, timeoutMs))
    }

    val orbits = (0 until g.n).groupBy { uf.find(it) }.values.toList()
    return OrbitDriveResult(orbits, queriesIssued, sat, unsat, unknown, skipped, generators, verified, 0)
}