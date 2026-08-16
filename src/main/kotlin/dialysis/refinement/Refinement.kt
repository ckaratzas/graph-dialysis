package dialysis.refinement

import dialysis.content.Content
import dialysis.decomposition.Decomposition
import dialysis.graph.Graph

/** A stable vertex partition: a colour per vertex, plus the same vertices grouped into cells in
 *  canonical colour order. */
class StablePartition(
    val color: Array<Content>,          // per-vertex stable color (content-addressed)
    val cells: List<IntArray>,          // cells in canonical colour order
    // When this partition came from initialPhase, a lookup back into the DecompositionStore it
    // already built for a given root vertex, so a caller that needs it doesn't recompute it. Null
    // for plain colorRefine1WL results, which never compute a decomposition at all.
    val decompositionOf: ((Int) -> Decomposition)? = null,
) {
    /** Number of vertices in non-singleton cells. 0 means fully discrete (every vertex its own
     *  colour); n means no discrimination happened at all. */
    fun pi(): Int = cells.filter { it.size > 1 }.sumOf { it.size }
    fun isDiscrete(): Boolean = pi() == 0
}

/**
 * Maps arbitrary [Content] colours to compact `0..k-1` ints for the native kernel, in [Content]'s
 * own total order (never hash/insertion order) so that two colour-isomorphic (graph, colouring)
 * pairs get identical initial int labellings for corresponding vertices -- required for the
 * native kernel to produce matching stable colours for corresponding vertices in turn.
 */
private fun contentsToCompactInts(g: Graph, initial: Array<Content>): IntArray {
    require(initial.size == g.n) { "initial coloring must have exactly g.n (${g.n}) entries, got ${initial.size}" }
    val colorToInt = initial.toHashSet().sorted().withIndex().associate { (i, c) -> c to i }
    return IntArray(g.n) { colorToInt.getValue(initial[it]) }
}

/** Wraps a native kernel's compact stable-colour array into a [StablePartition]; the native id
 *  itself becomes the [Content] colour, since it's already a pure function of graph + colouring
 *  structure, never of raw vertex identity. */
private fun stablePartitionFromNativeColors(n: Int, stableInts: IntArray): StablePartition {
    val color = Array<Content>(n) { Content.Num(stableInts[it].toLong()) }
    val cells = (0 until n).groupBy { stableInts[it] }
        .entries
        .sortedBy { (classId, _) -> Content.Num(classId.toLong()) }
        .map { (_, verts) -> verts.toIntArray() }
    return StablePartition(color, cells)
}

/** 1-WL colour refinement, backed by [NativeWL1] (a sparse Paige-Tarjan-style partition
 *  refinement). The base building block [initialPhase] refines further. */
fun colorRefine1WL(g: Graph, initial: Array<Content>): StablePartition {
    val initialInts = contentsToCompactInts(g, initial)
    val stableInts = NativeWL1.compute1WLColorsFrom(g, initialInts)
    return stablePartitionFromNativeColors(g.n, stableInts)
}

/** Same computation as [colorRefine1WL], but returns only the per-vertex colours, skipping the
 *  cell grouping -- for callers that only need that (e.g. [initialPhase]'s own remainder
 *  colouring), avoiding the extra sort/allocation on every call. */
fun colorRefine1WLColors(g: Graph, initial: Array<Content>): Array<Content> {
    val initialInts = contentsToCompactInts(g, initial)
    val stableInts = NativeWL1.compute1WLColorsFrom(g, initialInts)
    return Array(g.n) { Content.Num(stableInts[it].toLong()) }
}