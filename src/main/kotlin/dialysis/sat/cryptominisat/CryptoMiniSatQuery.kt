package dialysis.sat.cryptominisat

import dialysis.sat.SatQueryResult
import dialysis.sat.cadical.CadicalEncoding

/** [dialysis.sat.cadical.queryOrbitMateCadical], targeting [CryptoMiniSatSolver]. */
fun queryOrbitMateCryptoMiniSat(solver: CryptoMiniSatSolver, encoding: CadicalEncoding, u: Int, v: Int, timeoutMs: Long): SatQueryResult {
    val varUV = encoding.varOf[u][v]
    if (varUV < 0) return SatQueryResult.Unsat
    solver.assume(varUV)
    return when (solver.solve(timeoutMs)) {
        CryptoMiniSatSolver.Result.SAT -> SatQueryResult.Sat(decodeModel(encoding, solver))
        CryptoMiniSatSolver.Result.UNSAT -> SatQueryResult.Unsat
        CryptoMiniSatSolver.Result.UNKNOWN -> SatQueryResult.Unknown
    }
}

/** [CryptoMiniSatSolver.value]'s sign convention already matches DIMACS (positive = true). */
fun decodeModel(encoding: CadicalEncoding, solver: CryptoMiniSatSolver): IntArray {
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
