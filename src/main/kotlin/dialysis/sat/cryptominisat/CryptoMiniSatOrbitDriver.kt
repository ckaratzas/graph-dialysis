package dialysis.sat.cryptominisat

import dialysis.graph.Graph
import dialysis.sat.OrbitDriveResult
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.cadical.CadicalEncoding
import dialysis.sat.verifyAutomorphism

/** [dialysis.sat.cadical.driveToOrbitsCadical], targeting [CryptoMiniSatSolver] -- same two-pass
 *  (cheap [shortMs] sweep, then only the survivors pay [timeoutMs]) scheduling, same
 *  [SeparatingUnionFind] economies. No side-swapped pair parameter: see
 *  [buildCryptoMiniSatEncoding]'s own doc on why this experiment only needs the preserve side. */
private data class PendingCmsPair(val u: Int, val v: Int)

fun driveToOrbitsCryptoMiniSat(
    g: Graph,
    solver: CryptoMiniSatSolver,
    encoding: CadicalEncoding,
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

    fun recordFinal(q: PendingCmsPair, r: SatQueryResult) {
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

    val queue = mutableListOf<PendingCmsPair>()
    for (members in encoding.groups) {
        if (members.size <= 1) continue
        for (u in members) for (v in members) {
            if (v == u) continue
            queue.add(PendingCmsPair(u, v))
        }
    }

    val survivors = mutableListOf<PendingCmsPair>()
    for (q in queue) {
        if (uf.find(q.u) == uf.find(q.v) || uf.separated(q.u, q.v)) { skipped++; continue }
        when (val r = queryOrbitMateCryptoMiniSat(solver, encoding, q.u, q.v, shortMs)) {
            SatQueryResult.Unknown -> survivors.add(q)
            else -> recordFinal(q, r)
        }
    }
    for (q in survivors) {
        if (uf.find(q.u) == uf.find(q.v) || uf.separated(q.u, q.v)) { skipped++; continue }
        recordFinal(q, queryOrbitMateCryptoMiniSat(solver, encoding, q.u, q.v, timeoutMs))
    }

    val orbits = (0 until g.n).groupBy { uf.find(it) }.values.toList()
    return OrbitDriveResult(orbits, queriesIssued, sat, unsat, unknown, skipped, generators, verified, 0)
}
