package dialysis.sat

import dialysis.graph.Graph

/** The result of asking "does some automorphism map `u` to `v`?" -- shared by every SAT backend
 *  in this codebase (see `dialysis.sat.cadical`). A timeout is [Unknown], never [Unsat]: an
 *  unresolved query must never be reported as a proof of non-membership. */
sealed interface SatQueryResult {
    data class Sat(val alpha: IntArray) : SatQueryResult
    data object Unsat : SatQueryResult
    data object Unknown : SatQueryResult
}

/** The only arbiter of a claimed automorphism: independent of whichever SAT solver produced
 *  [alpha], independent of the encoding's own bookkeeping, a direct definitional check against
 *  [g]'s actual edges, in O(m). Every witness a driver reports must pass this before its
 *  generator closure is trusted. */
fun verifyAutomorphism(g: Graph, alpha: IntArray): Boolean {
    val n = g.n
    if (alpha.size != n || alpha.any { it < 0 }) return false
    if (alpha.toHashSet().size != n) return false
    return (0 until n).all { u -> g.adj[u].map { alpha[it] }.toHashSet() == g.adj[alpha[u]].toHashSet() }
}