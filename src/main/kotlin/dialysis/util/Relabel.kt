package dialysis.util

import dialysis.graph.Graph
import kotlin.random.Random

/** Returns a new graph with vertex labels remapped by [permutation] (old -> new). */
fun relabel(graph: Graph, permutation: Map<Int, Int>): Graph {
    val perm = IntArray(graph.n) { permutation.getValue(it) }
    return graph.relabeled(perm)
}

/**
 * The permutation (old -> new) [randomRelabel] would apply for [graph]/[seed], WITHOUT building
 * the relabeled graph — for callers (soundness tests) that need to check a returned mapping
 * against the actual known correspondence, not just that some mapping was returned.
 */
fun randomRelabelPermutation(graph: Graph, seed: Long): Map<Int, Int> {
    val rng = Random(seed)
    val (partA, partB) = graph.bipartition() ?: error("Graph is not bipartite")
    val a = partA.sorted()
    val b = partB.sorted()
    return (a.zip(a.shuffled(rng)) + b.zip(b.shuffled(rng))).toMap()
}

/**
 * Returns a new graph with vertices permuted randomly within each bipartition class,
 * preserving bipartiteness. Uses [Graph.bipartition] to detect the two sides.
 */
fun randomRelabel(graph: Graph, seed: Long): Graph = relabel(graph, randomRelabelPermutation(graph, seed))