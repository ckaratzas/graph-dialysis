package dialysis.cl

import dialysis.graph.Graph

/** Certificate bytes. Equality/order BY CONTENT ONLY — never compare by reference. */
class Certificate(val bytes: ByteArray) : Comparable<Certificate> {
    /** Lexicographic on unsigned byte values, then shorter-is-less on a common prefix. */
    override fun compareTo(other: Certificate): Int {
        val n = minOf(bytes.size, other.bytes.size)
        for (i in 0 until n) {
            val cmp = (bytes[i].toInt() and 0xFF) - (other.bytes[i].toInt() and 0xFF)
            if (cmp != 0) return cmp
        }
        return bytes.size - other.bytes.size
    }

    override fun equals(other: Any?): Boolean = other is Certificate && bytes.contentEquals(other.bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

/**
 * The exact canonizer CL: cert(G,c1)==cert(H,c2) iff colored-isomorphic.
 * Cells must be handed over in CANONICAL color order (content order), or certificates
 * of relabeled inputs will differ — cell ORDER is part of nauty/Traces' input.
 */
interface CanonicalLabeler {
    fun certificate(g: Graph, cells: List<IntArray>): Certificate
    fun canonicalLabeling(g: Graph, cells: List<IntArray>): IntArray

    /** Automorphism orbits of the COLORED graph — the exact orbit array, used for dedup. */
    fun orbits(g: Graph, cells: List<IntArray>): IntArray

    /** Search statistics for the results table (nodes, bad leaves) if the backend exposes them. */
    fun stats(g: Graph, cells: List<IntArray>): ClStats?

    /**
     * A generating set for Aut(colored graph) — each element a full permutation of `[0, g.n)`
     * (identity on any point fixed by that generator). NOT the whole group (that can be
     * exponentially larger than the generating set) and NOT necessarily minimal or the same set
     * two different calls would return — only guaranteed to generate the true automorphism group
     * of (g, cells) under composition.
     */
    fun generators(g: Graph, cells: List<IntArray>): List<IntArray>
}

data class ClStats(val treeNodes: Long, val badLeaves: Long, val autOrder: Double, val numOrbits: Int)