package dialysis.experimental

import dialysis.cl.TracesJni
import dialysis.content.Content
import dialysis.fvs.FeedbackVertexSet
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.dispatchColouring
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.cadical.CadicalEncoding
import dialysis.sat.cadical.CadicalSolver
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.buildCadicalEncodingSideSwapped
import dialysis.sat.cadical.queryOrbitMateCadical
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/**
 * EXPERIMENTAL, exploratory -- does FVS-seeded 1-WL (FVS_SEEDED_1WL_SPEC.md) recover the true orbit
 * partition on families OTHER than `cfi-rigid-*`? Small-scale, full-per-vertex-partition-verified
 * against Traces directly, same discipline as [FvsSeeded1WLExperimentTest] (which only ever covered
 * `cfi-rigid-*`).
 *
 * Two real differences from that test:
 *
 * 1. Uses `dispatchColouring(g, allowSubdivision = true)` (the actual production dispatch), not the
 *    `allowSubdivision = false` that test hardcodes. That hardcoding was safe there because every
 *    `cfi-rigid-*` family happens to fall in `FINAL_MEASUREMENTS_SPEC.md` Task 1's "subdivision NOT
 *    needed" bucket -- plain 1-WL(original) is already as fine as the projected initial phase, so
 *    forcing it off changes nothing. `cfi`, `mz`, `mz-aug`, `mz-aug2`, `sts`, `sts-sw` are the
 *    OPPOSITE case (Task 1's own table: e.g. `cfi` is 1-vs-800 classes) -- with subdivision forced
 *    off there, `colorOf` would collapse to one giant class, `encoding.groups` would be the whole
 *    graph as a single group, and "FVS-seeded closure" would trivially become 100% of the graph
 *    before a single query runs. Using the real dispatch keeps the base colouring meaningful for
 *    those families while still always solving on the ORIGINAL graph (`dispatchColouring`'s own
 *    invariant, unrelated to this experiment).
 *
 * 2. ALSO builds and queries [buildCadicalEncodingSideSwapped] (production's own mechanism,
 *    `driveToOrbitsCadical`'s `swapPair` parameter) whenever the graph is bipartite with equal-sized
 *    parts, not just the "preserve" encoding [FvsSeeded1WLExperimentTest] used exclusively. This was
 *    NOT a design choice made up front -- it was forced by a real finding: a first pass without it
 *    produced `pg`/`had`/`grid-w` OVER-refining relative to true orbits (e.g. `pg2-2`: 2 WL cells vs
 *    1 true orbit) -- which the seeded-refinement theorem (`FVS_SEEDED_1WL_SPEC.md` 1.3) says is
 *    IMPOSSIBLE for a correctly-seeded refinement. Diagnosed directly rather than assumed: verified
 *    the base colouring itself was perfectly invariant (every vertex the same colour, matching a
 *    true single orbit), which meant the bug had to be in what "colour-admissible" meant for the SAT
 *    step -- `computePreserveGroups` groups by `(colorOf, degree, SIDE)`, so even a globally-constant
 *    colouring still splits into one group per bipartition side. All three failing families turned
 *    out to have equal-sized bipartitions (self-dual incidence-style structures with a genuine
 *    side-swapping automorphism) -- confirmed directly (`computeSwapGroups` merges both sides into
 *    one group, `buildCadicalEncodingSideSwapped` builds successfully) -- so the preserve-only search
 *    was provably incomplete there: it can never find a witness that swaps sides, so it wrongly
 *    treats genuine orbit-mates on opposite sides as separated, breaking the seed colouring's
 *    required invariant (same true orbit -> same seed colour) before 1-WL even runs. Not a bug in
 *    `dispatchColouring`/`initialPhase`, not a violation of the theorem -- a gap in this harness
 *    (and in the original `FvsSeeded1WLExperimentTest`, which has the same gap but happens never to
 *    hit it: every `cfi-rigid-*` bipartite family's two sides differ in size/role, so no swap is
 *    even possible there and preserve-only is trivially complete).
 */
class FvsSeededOtherFamiliesTest {

    private class Result(
        val path: String, val n: Int, val fvsSize: Int, val seededSize: Int, val fvsQueriesIssued: Int,
        val wlCellCount: Int, val plainWlCellCount: Int, val trueOrbitCount: Int, val partitionsMatch: Boolean,
        val fullAdmissiblePairs: Long, val colouringUsed: String, val sideSwapUsed: Boolean,
    )

    private fun run(path: String): Result {
        val g = GraphIO.loadDimacs(File(path).toPath())

        val fvs = FeedbackVertexSet.compute(g)

        val dispatch = dispatchColouring(g, allowSubdivision = true)
        val colorOf = { v: Int -> dispatch.colouring[v] }
        val (preserveSolver, preserveEncoding) = buildCadicalEncoding(g, colorOf)
        val swapPair: Pair<CadicalSolver, CadicalEncoding>? = buildCadicalEncodingSideSwapped(g, colorOf)
        val uf = SeparatingUnionFind(g.n)

        // Seeded closure over BOTH the preserve groups AND the swap groups (when available), to a
        // fixpoint -- an FVS vertex on one side of a self-dual bipartition must pull in its cross-
        // side swap-group partners too, or the seed silently drops half of a true orbit (see this
        // class's own doc on how that manifested before this fix existed).
        val seeded = HashSet<Int>()
        var changed = true
        while (changed) {
            changed = false
            for (members in preserveEncoding.groups) {
                if (members.any { it in fvs || it in seeded } && seeded.addAll(members)) changed = true
            }
            swapPair?.second?.groups?.let { swapGroups ->
                for (members in swapGroups) {
                    if (members.any { it in fvs || it in seeded } && seeded.addAll(members)) changed = true
                }
            }
        }

        var queriesIssued = 0
        try {
            for (members in preserveEncoding.groups) {
                val inSeeded = members.filter { it in seeded }
                if (inSeeded.size <= 1) continue
                for (u in inSeeded) for (v in inSeeded) {
                    if (u == v) continue
                    if (uf.find(u) == uf.find(v) || uf.separated(u, v)) continue
                    queriesIssued++
                    when (val r = queryOrbitMateCadical(preserveSolver, preserveEncoding, u, v, 30_000)) {
                        is SatQueryResult.Sat -> for (w in 0 until g.n) uf.union(w, r.alpha[w])
                        SatQueryResult.Unsat -> uf.markSeparated(u, v)
                        SatQueryResult.Unknown -> {}
                    }
                }
            }
            swapPair?.let { (swapSolver, swapEncoding) ->
                for (members in swapEncoding.groups) {
                    val inSeeded = members.filter { it in seeded }
                    for (u in inSeeded) for (v in inSeeded) {
                        if (u == v || swapEncoding.varOf[u][v] < 0) continue // same-side pair in this group -- no cross variable
                        if (uf.find(u) == uf.find(v) || uf.separated(u, v)) continue
                        queriesIssued++
                        when (val r = queryOrbitMateCadical(swapSolver, swapEncoding, u, v, 30_000)) {
                            is SatQueryResult.Sat -> for (w in 0 until g.n) uf.union(w, r.alpha[w])
                            SatQueryResult.Unsat -> uf.markSeparated(u, v)
                            SatQueryResult.Unknown -> {}
                        }
                    }
                }
            }
        } finally {
            preserveSolver.close()
            swapPair?.first?.close()
        }

        val initial = Array<Content>(g.n) { v ->
            if (v in seeded) Content.Str("fvs-orbit-${uf.find(v)}") else Content.Str("non-fvs")
        }
        val refined = colorRefine1WL(g, initial)
        val plainRefined = colorRefine1WL(g, Array(g.n) { Content.Str("u") })

        val traces = TracesJni()
        val trueOrbitLabel = traces.orbits(g, listOf(IntArray(g.n) { it }))
        val trueOrbitCount = trueOrbitLabel.toHashSet().size

        val wlCellOf = IntArray(g.n)
        for ((cellId, cell) in refined.cells.withIndex()) for (v in cell) wlCellOf[v] = cellId
        var partitionsMatch = refined.cells.size == trueOrbitCount
        if (partitionsMatch) {
            outer@ for (u in 0 until g.n) for (v in 0 until g.n) {
                val sameWl = wlCellOf[u] == wlCellOf[v]
                val sameTrue = trueOrbitLabel[u] == trueOrbitLabel[v]
                if (sameWl != sameTrue) { partitionsMatch = false; break@outer }
            }
        }

        val fullAdmissiblePairs = preserveEncoding.groups.sumOf { it.size.toLong() * (it.size - 1) }

        return Result(
            path, g.n, fvs.size, seeded.size, queriesIssued,
            refined.cells.size, plainRefined.cells.size, trueOrbitCount, partitionsMatch, fullAdmissiblePairs,
            dispatch.used.name, swapPair != null,
        )
    }

    private fun runFamily(name: String, paths: List<String>) {
        println("=== $name ===")
        var allMatch = true
        for (path in paths) {
            val r = run(path)
            val vsPlain = if (r.plainWlCellCount == r.trueOrbitCount) "plain-1WL-ALREADY-SUFFICIENT" else "plain-1WL-insufficient-FVS-helped"
            println(
                "  $path: n=${r.n} colouring=${r.colouringUsed} sideSwap=${r.sideSwapUsed} |FVS|=${r.fvsSize} |seeded|=${r.seededSize} " +
                    "fvsQueries=${r.fvsQueriesIssued} (full-admissible-pairs=${r.fullAdmissiblePairs}) " +
                    "wlCells=${r.wlCellCount} plainWlCells=${r.plainWlCellCount} trueOrbits=${r.trueOrbitCount} " +
                    "MATCH=${r.partitionsMatch} ($vsPlain)",
            )
            if (!r.partitionsMatch) allMatch = false
        }
        println("$name: ALL MATCH = $allMatch")
    }

    @Test
    fun cfi() = runFamily("cfi", listOf("graphs/cfi/cfi-20", "graphs/cfi/cfi-22"))

    @Test
    fun mz() = runFamily("mz", listOf("graphs/mz/mz-2", "graphs/mz/mz-4"))

    @Test
    fun mzAug() = runFamily("mz-aug", listOf("graphs/mz-aug/mz-aug-2", "graphs/mz-aug/mz-aug-4"))

    @Test
    fun mzAug2() = runFamily("mz-aug2", listOf("graphs/mz-aug2/mz-aug2-4", "graphs/mz-aug2/mz-aug2-6"))

    @Test
    fun sts() = runFamily("sts", listOf("graphs/sts/sts-7", "graphs/sts/sts-9", "graphs/sts/sts-13"))

    @Test
    fun stsSw() = runFamily("sts-sw", listOf("graphs/sts-sw/sts-sw-19-1", "graphs/sts-sw/sts-sw-21-1"))

    @Test
    fun rnd3reg() = runFamily("rnd-3-reg", listOf("graphs/rnd-3-reg/rnd-3-reg-1000-1"))

    @Test
    fun ag() = runFamily("ag", listOf("graphs/ag/ag2-2", "graphs/ag/ag2-3"))

    @Test
    fun pg() = runFamily("pg", listOf("graphs/pg/pg2-2", "graphs/pg/pg2-3"))

    @Test
    fun had() = runFamily("had", listOf("graphs/had/had-1", "graphs/had/had-4"))

    @Test
    fun latin() = runFamily("latin", listOf("graphs/latin/latin-2", "graphs/latin/latin-4"))

    @Test
    fun paley() = runFamily("paley", listOf("graphs/paley/paley-5", "graphs/paley/paley-9"))

    @Test
    fun lattice() = runFamily("lattice", listOf("graphs/lattice/lattice-4"))

    @Test
    fun triang() = runFamily("triang", listOf("graphs/triang/triang-4"))

    @Test
    fun gridW() = runFamily("grid-w", listOf("graphs/grid-w/grid-w-3-2"))
}
