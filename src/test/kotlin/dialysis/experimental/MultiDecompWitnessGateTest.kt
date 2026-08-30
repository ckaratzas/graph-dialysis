package dialysis.experimental

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.refinement.dispatchColouring
import dialysis.sat.cadical.estimateGlobalEncodingSize
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MULTIDECOMP_WITNESS_SPEC.md 4.1: "measure before building the full loop" -- no solver anywhere
 * in this file, only split-quality counts. Reuses the EXISTING `peel`/`pieceKey`/
 * `positionSignatures` machinery (dialysis/decomposition/Peel.kt, built for
 * DECOMPOSITION_ORDERING_SPEC.md) for section 3.1's `sig(v) = (piece_key, ahu_position, depth)` --
 * nothing new needed there. This file only adds: an explicit per-run root override (peel's own
 * default root rule always picks from the CURRENT smallest class; 3.2's root schedule instead needs
 * caller-chosen, farthest-first roots drawn from one specific target class C), the farthest-first
 * root schedule itself (3.2), and a formula-size read for the largest resulting group, reusing
 * [estimateGlobalEncodingSize] against a colouring that isolates just that one group (every other
 * vertex gets its own unique singleton colour, so the estimate is dominated by the group's own
 * cost) rather than writing a new estimator.
 *
 * Reading the numbers (4.1's own decision rule): groups of size <= ~30 with formula sizes in the
 * thousands of clauses -> proceed to 4.2 (coverage). One group holding ~95% of the class -> the
 * decomposition does not split it any better than the global colouring already fails to -> stop,
 * the negative is explained by that number alone.
 */
class MultiDecompWitnessGateTest {
    /** Formula size for one isolated group, reusing [estimateGlobalEncodingSize] unchanged: every
     *  vertex outside [groupMembers] gets its own unique singleton colour (contributes a fixed,
     *  negligible O(n) of 1-variable/no-cost classes), so the returned totals are dominated by the
     *  group's own O(k^2) cost -- exactly "formula size for the largest group" without a new
     *  estimator. */
    private fun isolatedGroupFormulaSize(g: Graph, groupMembers: List<Int>): Pair<Long, Long> {
        val memberSet = groupMembers.toHashSet()
        val isolating = Array<Content>(g.n) { v ->
            if (v in memberSet) Content.Str("GROUP") else Content.Tup(listOf(Content.Str("S"), Content.Num(v.toLong())))
        }
        val est = estimateGlobalEncodingSize(g, { v -> isolating[v] })
        return est.variables to est.edgeConflictClauses
    }

    private fun run(path: String, kMax: Int = 8) {
        val g = GraphIO.loadDimacs(File(path).toPath())
        val colouring: Array<Content> = dispatchColouring(g, true).colouring
        val dg = decompositionGraphFor(g)
        val classes = (0 until g.n).groupBy { colouring[it] }.values.sortedByDescending { it.size }
        println(
            "instance=$path n=${g.n} m=${g.m} classes=${classes.size} class_sizes=${classes.map { it.size }} " +
                "decompositionGraphN=${dg.n} (${if (dg.n > g.n) "subdivided" else "original"})",
        )

        for (cls in classes) {
            if (cls.size < 3) continue
            println("  classSize=${cls.size}")
            val roots = rootSchedule(g, cls, kMax)
            println("  rootsUsed=$roots")
            for ((idx, r) in roots.withIndex()) {
                val sig = decomposeWithRoot(g, dg, r, colouring)
                val groups = cls.groupBy { sig.getValue(it) }.values.sortedByDescending { it.size }
                val sizes = groups.map { it.size }.sorted()
                val largest = groups.maxByOrNull { it.size }!!
                val (formulaVars, formulaEdgeClauses) = isolatedGroupFormulaSize(g, largest)
                println(
                    "    k=${idx + 1} root=$r distinctSigsInC=${groups.size} " +
                        "groupSizeMin=${sizes.first()} groupSizeMedian=${sizes[sizes.size / 2]} groupSizeMax=${sizes.last()} " +
                        "largestGroupPctOfC=%.1f formulaVarsMax=$formulaVars formulaEdgeClausesMax=$formulaEdgeClauses"
                            .format(100.0 * largest.size / cls.size),
                )
            }
        }
    }

    @Test
    fun ag216() = run("graphs/ag/ag2-16")

    @Test
    fun latin20() = run("graphs/latin/latin-20")

    // Order of work step 2: the families the spec's own prior data flagged as diverse
    // (7-107 distinct signatures observed previously), checked before committing to 4.2's full
    // WITNESS_HUNT build.
    @Test
    fun had64() = run("graphs/had/had-64")

    @Test
    fun lattice20() = run("graphs/lattice/lattice-20")

    @Test
    fun triang20() = run("graphs/triang/triang-20")
}
