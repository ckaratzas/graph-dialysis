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

    // Per-(u,a) cost is |vImages| * |aImages| -- fine when colour classes are small (the common
    // case: refinement usually shatters instances into tiny classes, the "2-WL wall" documented
    // elsewhere), but on a family whose classes survive refinement LARGE (e.g. cmz), this is
    // O(n * anchorK * classSize^2) with no ceiling: n=2046, anchorK=8, a single ~2000-member class
    // measured at 172M+ clause literals added before the process was killed by the OOM-killer --
    // NOT a memory leak, just an unbounded combinatorial cost inside one function call (see the
    // 2026-08-29 MemoryLeakProbeTest investigation). Skipping some pairs here is SAFE per this
    // function's own doc comment above (these clauses only strengthen propagation; which
    // automorphisms are representable is untouched), so cap the per-(u,a) work instead of adding
    // an unbounded amount -- a large class just gets weaker (not wrong) propagation from this
    // anchor. This is called once per (preserve/swap) solver, and CadicalParallelDriver runs up to
    // `workers` of those CONCURRENTLY (each worker independently rebuilds its own preserve+swap
    // encoding -- see that file's own "DELIBERATE SIMPLIFICATION" doc), so the real per-process
    // ceiling is roughly n * anchorK * MAX_PAIRS_PER_ANCHOR * 2 * workers clauses all alive at
    // once, not just one build's worth -- confirmed empirically: cap=1000 alone still reached
    // 7GB+ in ~7s at workers=4 (8 concurrent builds), so this needs to be tight enough to survive
    // that multiplication, not just a single build in isolation. cap=100 keeps a single build's
    // worst case at 16368*100 ~= 1.6M clauses, ~13M across 8 concurrent builds -- re-tune against
    // real campaign data (query counts, not just memory) if it turns out to matter.
    val MAX_PAIRS_PER_ANCHOR = 100
    var clausesAdded = 0
    for (u in 0 until n) {
        val vImages = imagesOf[u]
        if (vImages.size <= 1) continue
        for (a in anchors) {
            val aImages = imagesOf[a]
            if (aImages.isEmpty()) continue
            if (vImages.size * aImages.size > MAX_PAIRS_PER_ANCHOR) continue
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