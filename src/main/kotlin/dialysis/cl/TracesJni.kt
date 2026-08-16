package dialysis.cl

import dialysis.graph.Graph
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JNI binding to the real Traces algorithm (Piperno) — `Traces()` in
 * `src/main/cpp/nauty_traces.cpp` — over the same vendored nauty/Traces
 * library (`src/main/cpp/nauty`, Apache 2.0 — see COPYRIGHT there) already
 * used for 1-WL ([dialysis.refinement.NativeWL1]). Traces is a distinct
 * algorithm from nauty's classical individualization-refinement
 * (`densenauty()`) — generally faster on graphs with large automorphism
 * groups, which is exactly the case that forces this scheme's recursion into
 * the expensive exact path in the first place.
 *
 * [cells] must be handed over in CANONICAL color order (see [CanonicalLabeler]
 * doc): cell i's vertices become Traces' i-th initial partition class, and
 * the search only ever refines WITHIN a cell, never merges or reorders across
 * cells — so the input cell order is not just a hint, it IS the coloring as
 * far as the native search is concerned. Handing the same actual coloring in
 * a different cell order would silently ask a different (if
 * isomorphic-looking) question.
 *
 * Not thread-safe: Traces' own scratch workspace is static and shared across
 * calls — serialize calls from the JVM side, same caveat as
 * [dialysis.refinement.NativeWL1] / [dialysis.ahu.ColoredAHU].
 */
class TracesJni : CanonicalLabeler {

    companion object {
        init {
            val stream = TracesJni::class.java.getResourceAsStream("/libtracesjni.so")
                ?: error("libtracesjni.so not found in resources — run src/main/cpp/build_traces.sh first")
            val tmp = Files.createTempFile("libtracesjni", ".so")
            tmp.toFile().deleteOnExit()
            stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
            System.load(tmp.toAbsolutePath().toString())
        }
    }

    /**
     * Runs Traces() once. [lab0]/[ptn0] encode [cells] as the initial
     * partition; [labOut] comes back holding the canonical labeling (Traces
     * overwrites the array in place); [orbitsOut] the automorphism orbits.
     * Returns `[numnodes, 0 (no badLeaves analogue in Traces), numorbits,
     * numgenerators, grpsize1, grpsize2]` — see [ClStats.autOrder] for why
     * grpsize is split in two.
     */
    private external fun nativeCanon(
        csrOffsets: IntArray,
        csrNeighbors: IntArray,
        maxVertex: Int,
        lab0: IntArray,
        ptn0: IntArray,
        labOut: IntArray,
        orbitsOut: IntArray,
        getcanon: Boolean,
    ): DoubleArray

    /** Everything one Traces search produces — see [certifyAll] to compute all of it in one call. */
    data class TracesResult(
        val canonicalLabeling: IntArray,
        val orbits: IntArray,
        val stats: ClStats,
    )

    /**
     * Runs the search once and returns every facet the [CanonicalLabeler]
     * interface exposes separately. Prefer this over calling
     * [certificate]/[canonicalLabeling]/[orbits]/[stats] individually on the
     * same (g, cells) — each of those runs its own independent search.
     *
     * [getcanon] defaults to true (needed by [certificate]/[canonicalLabeling], the
     * interface's real contract). Pass false when only orbits/group size are needed —
     * proving canonicity is real, often substantial extra search on top of just finding
     * the automorphism group, and skipping it when unneeded can be an order of magnitude
     * faster (measured ~12x on an uncolored rigid-CFI instance). [canonicalLabeling] in the
     * returned [TracesResult] is meaningless when getcanon=false — Traces leaves `lab`
     * as whatever the search happened to reach, not a canonical form.
     */
    fun certifyAll(g: Graph, cells: List<IntArray>, getcanon: Boolean = true): TracesResult {
        val n = g.n
        val lab0 = IntArray(n)
        val ptn0 = IntArray(n)
        var pos = 0
        for (cell in cells) {
            for (v in cell) {
                lab0[pos] = v
                ptn0[pos] = 1
                pos++
            }
            if (pos > 0) ptn0[pos - 1] = 0
        }
        check(pos == n) { "cells must partition all $n vertices exactly once (got $pos)" }

        val (offsets, neighbors) = g.toCsr()
        val labOut = IntArray(n)
        val orbitsOut = IntArray(n)
        val s = nativeCanon(offsets, neighbors, n - 1, lab0, ptn0, labOut, orbitsOut, getcanon)
        val stats = ClStats(
            treeNodes = s[0].toLong(),
            badLeaves = s[1].toLong(),   // always 0 — Traces doesn't track this nauty-specific metric
            autOrder = s[4] * Math.pow(10.0, s[5]),
            numOrbits = s[2].toInt(),
        )
        return TracesResult(labOut, orbitsOut, stats)
    }

    override fun certificate(g: Graph, cells: List<IntArray>): Certificate {
        val lab = certifyAll(g, cells).canonicalLabeling
        return Certificate(canonicalBytes(g, lab, cells))
    }

    override fun canonicalLabeling(g: Graph, cells: List<IntArray>): IntArray =
        certifyAll(g, cells).canonicalLabeling

    override fun orbits(g: Graph, cells: List<IntArray>): IntArray =
        certifyAll(g, cells).orbits

    override fun stats(g: Graph, cells: List<IntArray>): ClStats =
        certifyAll(g, cells).stats

    /** See [nativeGenerators] (`src/main/cpp/nauty_traces.cpp`) — collects every generator Traces'
     *  own search finds via its `userautomproc` callback, never previously wired to JNI (only
     *  group ORDER, via [ClStats.autOrder], was exposed before this). Always getcanon=false. */
    private external fun nativeGenerators(
        csrOffsets: IntArray,
        csrNeighbors: IntArray,
        maxVertex: Int,
        lab0: IntArray,
        ptn0: IntArray,
    ): Array<IntArray>

    override fun generators(g: Graph, cells: List<IntArray>): List<IntArray> {
        val n = g.n
        val lab0 = IntArray(n)
        val ptn0 = IntArray(n)
        var pos = 0
        for (cell in cells) {
            for (v in cell) {
                lab0[pos] = v
                ptn0[pos] = 1
                pos++
            }
            if (pos > 0) ptn0[pos - 1] = 0
        }
        check(pos == n) { "cells must partition all $n vertices exactly once (got $pos)" }
        val (offsets, neighbors) = g.toCsr()
        return nativeGenerators(offsets, neighbors, n - 1, lab0, ptn0).toList()
    }

    /**
     * Certificate bytes = (n, cell sizes in the given canonical order) ++
     * canonical adjacency bits.
     *
     * [lab] is the canonical labeling: canonical position i <-> original
     * vertex lab[i]. The canonical adjacency is rebuilt directly from [g]
     * here rather than marshaling Traces' own canonical sparsegraph across
     * JNI — we already hold [g] in memory, so "is canonical position i
     * adjacent to j" is just "is lab[i] adjacent to lab[j] in g".
     *
     * Cell SIZES (not the actual [dialysis.content.Content] colors — those
     * are exactly what the caller's canonical cell ORDER already encodes)
     * are prefixed because the adjacency bits alone carry no color
     * information: two differently-colored but adjacency-identical inputs
     * must not collide just because their canonical graphs happen to match.
     */
    private fun canonicalBytes(g: Graph, lab: IntArray, cells: List<IntArray>): ByteArray {
        val n = g.n
        val posOf = IntArray(n)
        lab.forEachIndexed { pos, v -> posOf[v] = pos }

        val packed = ByteArray((n * n + 7) / 8)
        for (pos1 in 0 until n) {
            for (v in g.adj[lab[pos1]]) {
                val bitIndex = pos1 * n + posOf[v]
                packed[bitIndex / 8] = (packed[bitIndex / 8].toInt() or (1 shl (7 - bitIndex % 8))).toByte()
            }
        }

        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).use { out ->
            out.writeInt(n)
            out.writeInt(cells.size)
            for (cell in cells) out.writeInt(cell.size)
            out.write(packed)
        }
        return buf.toByteArray()
    }
}