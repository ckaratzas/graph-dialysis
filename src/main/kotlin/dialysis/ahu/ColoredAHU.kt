package dialysis.ahu

import dialysis.graph.Graph
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.collections.iterator

/**
 * Colored AHU tree canonicalization via JNI.
 *
 * Given a tree and an initial string color per vertex, assigns each vertex a
 * compact integer AHU label that uniquely identifies the isomorphism class of
 * its colored subtree.  Two vertices (possibly in different trees) have the
 * same label iff their rooted colored subtrees are isomorphic.
 *
 * Canonical strings are stored in a **global intern pool** — one entry per
 * distinct isomorphism class ever seen, shared across all calls.  Each
 * [ColoredAHUResult] stores only a compact [Int] id per vertex; the string is
 * retrieved on demand via [ColoredAHUResult.treeCanonicalForm] or
 * [ColoredAHUResult.subtreeCanonicalString].  This means results from
 * different [compute] calls can be compared directly by their integer ids.
 *
 * Usage — explicit root:
 *
 *   val result = ColoredAHU.compute(tree, mapOf(0 to "RED", 1 to "BLUE"), root = 0)
 *   println(result.treeCanonicalForm)                  // e.g. "(RED(BLUE)(BLUE))"
 *   println(result.subtreeCanonicalString(someVertex))
 *   // cross-call comparison:
 *   result1.rootId == result2.rootId                   // true iff isomorphic
 *
 * Colors are arbitrary non-empty strings.  The string `"*"` is reserved as the
 * internal sentinel for uncolored vertices and must not be used as a color.
 */
object ColoredAHU {

    // ── Global intern pool ────────────────────────────────────────────────────
    // Maps canonical string → stable global id (and reverse).
    // Entries accumulate across all compute() calls; each entry represents one
    // distinct subtree isomorphism class.  Results store only Int ids, so
    // cross-call comparison is just rootId == otherRootId.

    private val stringToId = HashMap<String, Int>()
    private val idToString = ArrayList<String>()

    /** Returns the stable global id for [s], interning it if not yet seen. */
    private fun intern(s: String): Int = stringToId.getOrPut(s) {
        val id = idToString.size
        idToString.add(s)
        id
    }

    /** Returns the canonical string for a global [id]. */
    @Synchronized
    fun canonicalFormOf(id: Int): String = idToString[id]

    // ── JNI init ──────────────────────────────────────────────────────────────

    init {
        val stream = ColoredAHU::class.java.getResourceAsStream("/libcoloredahu.so")
            ?: error("libcoloredahu.so not found in resources — run src/main/cpp/build.sh first")
        val tmp = Files.createTempFile("libcoloredahu", ".so")
        tmp.toFile().deleteOnExit()
        stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        System.load(tmp.toAbsolutePath().toString())
    }

    // ── JNI surface ───────────────────────────────────────────────────────────

    /**
     * Computes Colored AHU labels for all vertices reachable from [root].
     *
     * [csrOffsets] / [csrNeighbors]: CSR representation of the tree — build once
     * per graph with [buildCsr].
     * [initialColors]: size maxVertex+1; null element = uncolored vertex.
     * Returns IntArray of size maxVertex+1: result[v] = AHU label, -1 if unreachable.
     */
    @JvmStatic
    external fun computeLabels(
        csrOffsets: IntArray,
        csrNeighbors: IntArray,
        maxVertex: Int,
        initialColors: Array<String?>,
        root: Int,
    ): IntArray

    /**
     * Returns the canonical string for [label].
     * Must be called after [computeLabels] and before [reset].
     * Format: "(color)" for a leaf, "(color(child1)(child2)...)" for internal
     * nodes with children sorted lexicographically.
     */
    @JvmStatic
    external fun canonicalString(label: Int): String

    /** Clears the native registry.  Called automatically inside [compute]. */
    @JvmStatic
    external fun reset()

    // ── High-level API ────────────────────────────────────────────────────────

    /**
     * Computes Colored AHU on [g] rooted at [root] with per-vertex [initialColors].
     *
     * [initialColors] maps vertex → color string.  Vertices absent from the map are
     * **uncolored** (canonical prefix `*`), which is a distinct class from any string
     * color.  Two uncolored subtrees with the same structure are still isomorphic.
     *
     * All canonical strings are materialized before returning so the result is
     * independent of the internal registry.
     */
    @Synchronized
    fun compute(
        g: Graph,
        initialColors: Map<Int, String>,
        root: Int,
    ): ColoredAHUResult {
        val maxVertex = g.n - 1
        val (csrOffsets, csrNeighbors) = g.toCsr()
        val colorsArr = arrayOfNulls<String>(maxVertex + 1)
        for ((v, c) in initialColors) if (v <= maxVertex) colorsArr[v] = c
        try {
            val labels = computeLabels(csrOffsets, csrNeighbors, maxVertex, colorsArr, root)
            // Map each unique session label → global id, computing the canonical
            // string exactly once per distinct class (not once per vertex).
            val sessionToGlobal = HashMap<Int, Int>()
            val vertexIds = IntArray(maxVertex + 1) { -1 }
            for (v in 0 until g.n) {
                val l = labels[v]
                if (l < 0) continue
                vertexIds[v] = sessionToGlobal.getOrPut(l) { intern(canonicalString(l)) }
            }
            val rootId = vertexIds[root].also {
                if (it < 0) error("root $root not reachable in tree")
            }
            return ColoredAHUResult(root = root, rootId = rootId, vertexIds = vertexIds)
        } finally {
            reset()
        }
    }

    // ── Utilities ─────────────────────────────────────────────────────────────
    /**
     * Result of a Colored AHU computation.
     *
     * Each vertex is represented by a **global integer id** that is stable across
     * [compute] calls: two vertices from different trees have the same id iff their
     * rooted colored subtrees are isomorphic.  Canonical strings are stored once
     * per distinct isomorphism class in the intern pool and retrieved on demand.
     *
     * @property root     The vertex used as the tree root.
     * @property rootId   Global isomorphism-class id for the whole tree.
     */
    data class ColoredAHUResult(
        val root: Int,
        val rootId: Int,
        private val vertexIds: IntArray,
    ) {
        /** Canonical string for the whole tree (rooted at [root]). Retrieved from the intern pool. */
        val treeCanonicalForm: String get() = canonicalFormOf(rootId)

        /**
         * Canonical string for the subtree rooted at [v].
         * Throws if [v] was not reachable from [root] during computation.
         */
        fun subtreeCanonicalString(v: Int): String {
            val id = vertexIds.getOrElse(v) { -1 }
            if (id < 0) error("vertex $v was not reachable from root $root")
            return canonicalFormOf(id)
        }

        /** Global isomorphism-class id of vertex [v], or -1 if not in the tree. */
        fun label(v: Int): Int = vertexIds.getOrElse(v) { -1 }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ColoredAHUResult) return false
            return root == other.root &&
                    rootId == other.rootId &&
                    vertexIds.contentEquals(other.vertexIds)
        }

        override fun hashCode(): Int = rootId
    }
}