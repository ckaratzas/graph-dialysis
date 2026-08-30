package dialysis.ahu

import dialysis.graph.Graph
import dialysis.util.dialysisTempFile
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
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
/**
 * Append-only, memory-mapped store of interned strings, backing [ColoredAHU]'s dedup pool. Keeps
 * only a content hash and an on-disk byte offset per DISTINCT string in JVM heap -- never the
 * string's own character data, unlike a plain `HashMap<String, Int>` (which MUST retain every
 * distinct key object for the life of the map, since that's what a hash map needs to answer
 * lookups). That distinction is the entire fix: [dialysis.refinement.InitialPhase]'s Phase-1 loop
 * calls [ColoredAHU.compute] once per vertex needing work and never reads the canonical string
 * back at all (only the assigned int id), yet a family whose per-vertex subtrees never repeat
 * (e.g. `rnd-3-reg` -- see [ColoredAHU]'s own class doc) interned thousands of large, entirely
 * distinct canonical strings within a SINGLE graph instance's Phase 1 alone. Confirmed directly:
 * heap climbed from 9MB to 3773MB across one `rnd-3-reg-3000-1` instance's Phase 1 (subdivided to
 * 7500 vertices), while [dialysis.decomposition.DecompositionStore]'s own mmap-backed store, doing
 * the analogous job for per-vertex decompositions, stayed flat at 9MB the entire time -- the same
 * off-heap technique, generalized here to a store that grows as strings arrive instead of one
 * sized up front (a decomposition's byte size is computable before writing it; and interned
 * string's total count and lengths aren't known until every [ColoredAHU.compute] call has run).
 *
 * A hash collision between two DIFFERENT strings can only cost extra work (both candidates get
 * compared by actual content, read back from the mapped file, before either is trusted) -- it can
 * never merge two distinct strings under the same id, so this is not a probabilistic dedup.
 *
 * Not thread-safe by itself -- [ColoredAHU] scopes one instance per calling thread (same
 * convention the pool this replaces already used).
 */
private class MappedInternPool {
    companion object {
        // Generous relative to a single AHU canonical string's realistic size (low hundreds of KB
        // even for a many-thousand-vertex tree) -- this bounds segment COUNT for a large
        // distinct-string volume, not any one string's own length (checked below regardless).
        private const val SEGMENT_SIZE = 1L shl 26 // 64 MiB
    }

    private val hashToIds = HashMap<Long, MutableList<Int>>()
    private val recordOffset = ArrayList<Long>()   // id -> byte offset of its length-prefixed record
    private val tmpPath: Path = dialysisTempFile("ahu-intern", ".bin")
    private val raf = RandomAccessFile(tmpPath.toFile(), "rw")
    private val channel = raf.channel
    private val segments = ArrayList<MappedByteBuffer>()
    private var writePos = 0L

    init {
        tmpPath.toFile().deleteOnExit()
        ensureCapacity(SEGMENT_SIZE)
    }

    private fun ensureCapacity(minBytes: Long) {
        while (segments.size.toLong() * SEGMENT_SIZE < minBytes) {
            val idx = segments.size
            raf.setLength((idx + 1) * SEGMENT_SIZE)
            segments.add(channel.map(FileChannel.MapMode.READ_WRITE, idx * SEGMENT_SIZE, SEGMENT_SIZE))
        }
    }

    private fun readString(id: Int): String {
        val off = recordOffset[id]
        val buf = segments[(off / SEGMENT_SIZE).toInt()].duplicate()
        buf.position((off % SEGMENT_SIZE).toInt())
        val bytes = ByteArray(buf.int)
        buf.get(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** Appends [s], returning the byte offset its record starts at. Never lets a record straddle
     *  a segment boundary (same convention as [dialysis.decomposition.DecompositionStore.build]). */
    private fun appendString(s: String): Long {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val need = 4L + bytes.size
        require(need <= SEGMENT_SIZE) {
            "single interned string (${bytes.size} bytes) exceeds SEGMENT_SIZE -- unexpectedly large AHU canonical string"
        }
        var pos = writePos
        if (pos / SEGMENT_SIZE != (pos + need - 1) / SEGMENT_SIZE) {
            pos = (pos / SEGMENT_SIZE + 1) * SEGMENT_SIZE
        }
        ensureCapacity(pos + need)
        val buf = segments[(pos / SEGMENT_SIZE).toInt()].duplicate()
        buf.position((pos % SEGMENT_SIZE).toInt())
        buf.putInt(bytes.size)
        buf.put(bytes)
        writePos = pos + need
        return pos
    }

    /** Murmur3-style avalanche over UTF-8 bytes -- a heap-side dedup bucket key only, never an id
     *  or anything semantics-visible (see class doc on collision safety). */
    private fun contentHash(s: String): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a offset basis
        for (b in s.toByteArray(Charsets.UTF_8)) {
            h = h xor (b.toLong() and 0xFF)
            h *= 0x100000001b3L
        }
        return h
    }

    fun intern(s: String): Int {
        val candidates = hashToIds.getOrPut(contentHash(s)) { mutableListOf() }
        for (id in candidates) if (readString(id) == s) return id
        val id = recordOffset.size
        recordOffset.add(appendString(s))
        candidates.add(id)
        return id
    }

    fun get(id: Int): String = readString(id)

    /** Releases this pool's backing file. Safe to call while [segments]' mapped views are still
     *  reachable elsewhere -- on Linux, unlinking a file doesn't invalidate an existing mapping of
     *  it (same assumption [dialysis.decomposition.DecompositionStore.build] already relies on,
     *  deleting its own backing file immediately after mapping it). */
    fun close() {
        runCatching { channel.close() }
        runCatching { raf.close() }
        runCatching { Files.deleteIfExists(tmpPath) }
    }
}

object ColoredAHU {

    // ── Intern pool, one per calling thread -- see [MappedInternPool]'s class doc for what this
    // stores off-heap and why. Entries accumulate across all compute() calls made by that thread
    // (until [clearInternPool]); each entry represents one distinct subtree isomorphism class.
    // Results store only Int ids, so same-thread cross-call comparison is just
    // rootId == otherRootId.
    //
    // THREAD-LOCAL, not a single shared pool, for two reasons:
    //  1. Correctness under `--workers > 1`: [clearInternPool] (below) must be callable at an
    //     instance boundary without corrupting a DIFFERENT worker thread's in-flight instance,
    //     which may be mid-way through [dialysis.refinement.InitialPhase]'s own per-vertex loop --
    //     each call there depends on the pool still holding ids from EARLIER vertices of the SAME
    //     instance. A single shared pool cannot be cleared safely while any other thread might be
    //     between two of its own `compute()` calls; thread-local storage means each worker only
    //     ever touches (and clears) its own pool, never another worker's.
    //  2. `compute()` itself stays `@Synchronized` regardless (the NATIVE registry in
    //     colored_ahu.cpp is one C++-global structure shared by the whole process, not
    //     thread-local), so this JVM-side split doesn't reduce concurrency further -- it only
    //     changes which history a given call's `intern()` checks against.
    private val internPool = ThreadLocal.withInitial { MappedInternPool() }

    /** Returns the calling thread's stable id for [s], interning it if not yet seen on this thread. */
    private fun intern(s: String): Int = internPool.get().intern(s)

    /** Returns the canonical string for an [id] previously interned ON THIS SAME THREAD. */
    fun canonicalFormOf(id: Int): String = internPool.get().get(id)

    /**
     * Drops every entry the CALLING THREAD has accumulated so far (and releases that pool's
     * backing mmap file -- see [MappedInternPool.close]). Safe to call between graph instances:
     * nothing in this codebase compares a raw [ColoredAHUResult.rootId]/[ColoredAHUResult.label]
     * value across different graphs (`Peel.kt`'s [dialysis.decomposition.PositionSignature]
     * explicitly converts back to the canonical STRING before storing anything, and
     * [dialysis.refinement.InitialPhase] only compares ids among vertices of the SAME graph within
     * one call). Without this, every distinct subtree isomorphism class a thread has ever seen is
     * held for the life of the JVM process -- harmless (even a beneficial cache) for a campaign
     * dominated by repeated/shared substructure (`cfi-rigid-*`, gadget families), but an unbounded
     * ACROSS-INSTANCE leak for a family whose instances share no structure with each other
     * (confirmed directly: `rnd-3-reg-1000-*`, each instance's decomposition fully discretizes into
     * ~1000 unique trees that never recur, drove `formula_peak_rss_mb` from 979MB to 4421MB across
     * 11 sequential same-process instances with a trivial SAT side (`filter_mode=GLOBAL`,
     * single-digit-ms solves) -- eventually an actual `OutOfMemoryError: Java heap space` on a
     * longer campaign). That leak is distinct from (and this call does NOT address) the
     * WITHIN-one-instance blowup [MappedInternPool] itself now fixes -- a single `rnd-3-reg`
     * instance's own Phase 1 loop, on its own, interned enough distinct large strings to run heap
     * from 9MB to 3773MB, well before any cross-instance accumulation could even apply. Called once
     * per benchmark instance, on whichever worker thread is about to process it, in
     * [dialysis.benchmark.BenchmarkRunner] -- not per [compute] call, so within-instance dedup
     * across multiple roots is unaffected, only cross-instance accumulation is capped, and only on
     * the calling thread's own pool.
     */
    fun clearInternPool() {
        internPool.get().close()
        internPool.set(MappedInternPool())
    }

    // ── JNI init ──────────────────────────────────────────────────────────────

    init {
        val stream = ColoredAHU::class.java.getResourceAsStream("/libcoloredahu.so")
            ?: error("libcoloredahu.so not found in resources — run src/main/cpp/build.sh first")
        val tmp = dialysisTempFile("libcoloredahu", ".so")
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