package dialysis.sat.cadical

import dialysis.sat.SatQueryResult

/**
 * Asks "does some automorphism map `u` to `v`?" against an already-built [encoding] --
 * assumption-based, so it never adds a permanent clause and the same [solver] can be reused for
 * many queries. A timeout is [SatQueryResult.Unknown], never [SatQueryResult.Unsat].
 */
fun queryOrbitMateCadical(solver: CadicalSolver, encoding: CadicalEncoding, u: Int, v: Int, timeoutMs: Long): SatQueryResult {
    val varUV = encoding.varOf[u][v]
    if (varUV < 0) return SatQueryResult.Unsat // not even admissible -- unconditionally not orbit-mates
    solver.assume(varUV)
    return when (solver.solve(timeoutMs)) {
        CadicalSolver.Result.SAT -> SatQueryResult.Sat(decodeModel(encoding, solver))
        CadicalSolver.Result.UNSAT -> SatQueryResult.Unsat
        CadicalSolver.Result.UNKNOWN -> SatQueryResult.Unknown
    }
}

/** [CadicalSolver.value]'s sign convention already matches DIMACS (positive = true) -- decode the
 *  permutation directly from that. */
fun decodeModel(encoding: CadicalEncoding, solver: CadicalSolver): IntArray {
    val n = encoding.g.n
    val alpha = IntArray(n) { -1 }
    for (members in encoding.groups) {
        for (i in members) for (j in members) {
            val vid = encoding.varOf[i][j]
            if (vid > 0 && solver.value(vid) > 0) alpha[i] = j
        }
    }
    return alpha
}