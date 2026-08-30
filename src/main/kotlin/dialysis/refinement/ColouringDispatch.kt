package dialysis.refinement

import dialysis.content.Content
import dialysis.graph.Graph

/** Which invariant colouring [ColouringDispatch.colouring] actually holds. */
enum class ColouringSource { PI, WL1_ORIGINAL, PI_TO_ORIGINAL }

/**
 * FINAL_MEASUREMENTS_SPEC.md Task 1's colouring-dispatch decision for one graph. [colouring] is
 * ALWAYS sized to the ORIGINAL graph's own vertex count -- solving happens on the original graph
 * regardless of [used]/[subdivided], never on a subdivision (that's the whole point: subdivision,
 * when needed at all, is confined to producing a colouring, never to the SAT encoding/solve).
 */
class ColouringDispatch(
    val colouring: Array<Content>,
    val used: ColouringSource,
    /** Whether sd(G) was built at all, for this decision -- true for both outcomes of the
     *  non-bipartite branch (it's built to run the comparison even when 1-WL(G) ends up winning),
     *  false only for the bipartite branch (never built). */
    val subdivided: Boolean,
    /** 1-WL(G)'s own stable-partition class count. Null only for the bipartite branch, where it is
     *  never computed (Pi(G) is never coarser than 1-WL(G), so there's nothing to compare). */
    val wl1OriginalClasses: Int?,
    /** 1-WL(G)'s own per-vertex colouring (over G's own n vertices) -- exposed so a caller that
     *  ALSO needs plain 1-WL (e.g. for a WL_DIST ablation config, or the `classes_1wl` report
     *  column) can reuse this instead of recomputing `colorRefine1WL(g, uniformSeed(g.n))` a second
     *  time. Null only for the bipartite branch (never computed there -- see [wl1OriginalClasses]). */
    val wl1Colouring: Array<Content>?,
    /** Pi(sd(G))'s own stable-partition class count, over sd(G)'s FULL n+m vertices. Null iff
     *  [subdivided] is false. */
    val piSubdivisionClasses: Int?,
    /** Distinct-colour count of Pi(sd(G)) restricted to the original n vertices -- what actually
     *  gets compared against [wl1OriginalClasses]. Null iff [subdivided] is false. */
    val piToOriginalClasses: Int?,
)

/**
 * Chooses the invariant colouring the SAT driver should use for [g] -- always over [g]'s own n
 * vertices, whether or not a subdivision was built along the way.
 *
 * **Soundness** of colouring via sd(G) while solving on G directly: subdivision vertices correspond
 * bijectively to G's edges (`s_{uv} <-> {u,v}`), and every automorphism of G extends UNIQUELY to
 * sd(G) via `s_{uv} -> s_{alpha(u)alpha(v)}` -- so `Aut(sd(G)) === Aut(G)`, and [initialPhase]'s
 * stable colouring of sd(G), restricted back to the original n vertices, is itself an isomorphism-
 * invariant colouring of G (Pi is equivariant, and the vertex correspondence is canonical -- no
 * arbitrary tie-break is made anywhere in building it). Verified directly, not just argued: see
 * [dialysis.ColouringDispatchSoundnessTest] -- on a small hand-built non-bipartite graph, Traces'
 * own automorphism generator for sd(G) was EXACTLY G's generator extended by the `s_{uv} ->
 * s_{alpha(u)alpha(v)}` rule on the matching subdivision-vertex pair, not just a matching orbit
 * COUNT.
 *
 * - Bipartite [g]: Pi(g) directly, no subdivision at all -- Pi is never coarser than 1-WL (see
 *   [dialysis.InitialPhaseTest.neverCoarserThanPlain1WL]), so there's nothing to compare against.
 * - Non-bipartite [g]: compares 1-WL(g) (on the original graph) against project(Pi(sd(g))) (Pi run
 *   on the subdivision, restricted to the original n vertices) by CLASS COUNT -- whichever is
 *   finer wins; a tie keeps 1-WL(g), the cheaper of the two to have computed.
 *
 * [allowSubdivision] = false skips the non-bipartite branch's subdivision entirely (no `sd(g)`
 * built, no [DecompositionStore] on it) and returns plain 1-WL(g) unconditionally -- for families
 * where the comparison always resolves to 1-WL anyway, this avoids paying for `sd(g)`'s initial
 * phase at all, which for a graph whose subdivision exceeds several thousand vertices is both slow
 * and, via [dialysis.decomposition.DecompositionStore]'s O(n^2) scratch file, the thing that can
 * exhaust a small `java.io.tmpdir` partition (see `dialysis.util.dialysisTempFile`). Does nothing
 * for a bipartite [g] -- that branch never subdivides regardless.
 */
fun dispatchColouring(g: Graph, allowSubdivision: Boolean = true): ColouringDispatch {
    if (g.bipartition() != null) {
        val pi = initialPhase(g, uniformSeed(g.n))
        return ColouringDispatch(pi.color, ColouringSource.PI, subdivided = false, null, wl1Colouring = null, null, null)
    }

    val wl1 = colorRefine1WL(g, uniformSeed(g.n))
    if (!allowSubdivision) {
        return ColouringDispatch(wl1.color, ColouringSource.WL1_ORIGINAL, subdivided = false, wl1.cells.size, wl1.color, null, null)
    }
    val sd = g.subdivided()
    val piSd = initialPhase(sd, uniformSeed(sd.n))
    // sd(g) keeps the original graph's vertex ids 0 until g.n unchanged (see Graph.subdivided),
    // so restricting to the original vertices is a direct slice, no id translation needed.
    val projected = Array(g.n) { v -> piSd.color[v] }
    val projectedClasses = projected.toHashSet().size

    return if (projectedClasses > wl1.cells.size) {
        ColouringDispatch(projected, ColouringSource.PI_TO_ORIGINAL, subdivided = true, wl1.cells.size, wl1.color, piSd.cells.size, projectedClasses)
    } else {
        ColouringDispatch(wl1.color, ColouringSource.WL1_ORIGINAL, subdivided = true, wl1.cells.size, wl1.color, piSd.cells.size, projectedClasses)
    }
}
