package dialysis.sat.cadical

import dialysis.graph.Graph
import dialysis.sat.ImpliedClauseStats
import dialysis.sat.distanceBucket
import dialysis.sat.selectAnchors

/**
 * Adds implied distance clauses to an already-built [encoding]/[solver]: an automorphism preserves
 * distance, so for admissible images `v` of `u` and `a'` of anchor `a`, `x[u][v] AND x[a][a']` is
 * impossible whenever their distance buckets disagree. This is entailed by the edge-preservation
 * and bijection constraints [encoding] already has -- adding it changes propagation strength, not
 * which automorphisms are representable, so completeness is untouched. Only [anchorK] anchors
 * (drawn from the smallest colour classes first) are compared against each vertex, not all pairs,
 * keeping this a pure post-processing step over the existing `x[u][v]` variables -- no new
 * variables, plain 2-literal clauses throughout.
 *
 * [computeAllPairsDistances]/[distanceBucket]/[selectAnchors]/[ImpliedClauseStats] are shared,
 * solver-agnostic pieces from [dialysis.sat] -- none of them depend on which solver built the
 * encoding.
 */
fun addImpliedDistanceClausesCadical(
    g: Graph,
    solver: CadicalSolver,
    encoding: CadicalEncoding,
    dist: Array<IntArray>,
    dmax: Int,
    anchorK: Int,
): ImpliedClauseStats {
    val anchors = selectAnchors(encoding.groups, anchorK)
    val n = g.n

    val imagesOf = Array(n) { v -> (0 until n).filter { encoding.varOf[v][it] >= 0 } }

    var clausesAdded = 0
    for (u in 0 until n) {
        val vImages = imagesOf[u]
        if (vImages.size <= 1) continue
        for (a in anchors) {
            val aImages = imagesOf[a]
            if (aImages.isEmpty()) continue
            val uaBucket = distanceBucket(dist[u][a], dmax)
            for (v in vImages) {
                for (ap in aImages) {
                    val vApBucket = distanceBucket(dist[v][ap], dmax)
                    if (uaBucket != vApBucket) {
                        solver.addClause(intArrayOf(-encoding.varOf[u][v], -encoding.varOf[a][ap]))
                        clausesAdded++
                    }
                }
            }
        }
    }
    return ImpliedClauseStats(dmax, anchorK, anchors, clausesAdded)
}