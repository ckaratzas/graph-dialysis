package dialysis.experimental

import dialysis.content.Content
import dialysis.refinement.dispatchColouring
import dialysis.util.GraphIO
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import java.io.File

/**
 * MULTIDECOMP_WITNESS_SPEC.md 4.2: the coverage curve. Runs BOTH [witnessHunt] (fixed graph, only
 * the root varies each round) and [witnessHuntWithClearing] (quotients already-proven orbits out of
 * the graph before each round's decomposition) side by side, including on `ag2-16` -- it failed
 * 4.1's static split-quality gate, but clearing changes the GRAPH itself between rounds, which is a
 * genuinely different mechanism from anything 4.1 measured, so it is not pre-excluded here.
 *
 * Section 6's control assertion (`components_remaining >= true_orbits_in_C`) is applied only where
 * ground truth is already on hand in this repo; none of `had`/`latin`/`lattice`/`triang`/`ag` have a
 * merged ground-truth CSV (that's a separate offline nauty pass, `scripts/ground_truth.py`), so this
 * run reports the curve without that control -- flagged explicitly in the output, not silently
 * skipped.
 */
class MultiDecompWitnessHuntCoverageTest {
    private fun run(path: String, kMax: Int = 8) {
        val g = GraphIO.loadDimacs(File(path).toPath())
        val colouring: Array<Content> = dispatchColouring(g, true).colouring
        val colorOf = { v: Int -> colouring[v] }
        val classes = (0 until g.n).groupBy { colouring[it] }.values.sortedByDescending { it.size }
        println("instance=$path n=${g.n} m=${g.m} classes=${classes.size} class_sizes=${classes.map { it.size }} (no ground truth available -- control assertion skipped)")

        for (cls in classes) {
            if (cls.size < 3) continue
            val plain = witnessHunt(g, cls, colorOf, kMax)
            println("  [NO_CLEARING] classSize=${cls.size} rootsUsed=${plain.rootsUsed}")
            for ((idx, step) in plain.steps.withIndex()) {
                println(
                    "    k=${idx + 1} root=${step.root} distinctSigsInC=${step.distinctSigsInC} " +
                        "queriesIssued=${step.queriesIssued} sat=${step.sat} unsatDiscarded=${step.unsatDiscarded} " +
                        "unknownDiscarded=${step.unknownDiscarded} witnessesVerified=${step.witnessesVerified} " +
                        "componentsRemaining=${step.componentsRemainingAfter} skippedTooLarge=${step.skippedTooLarge}",
                )
            }
            println(
                "  [NO_CLEARING] FINAL classSize=${cls.size} componentsRemaining=${plain.finalComponentsRemaining} " +
                    "residualPairs=${plain.finalComponentsRemaining * (plain.finalComponentsRemaining - 1) / 2} " +
                    "totalQueries=${plain.totalQueries} totalTimeMs=${plain.totalTimeMs}",
            )

            val cleared = witnessHuntWithClearing(g, cls, colorOf, kMax)
            println("  [CLEARING] classSize=${cls.size} rootsUsed=${cleared.rootsUsed}")
            for ((idx, step) in cleared.steps.withIndex()) {
                println(
                    "    k=${idx + 1} root=${step.root} distinctSigsInC=${step.distinctSigsInC} " +
                        "queriesIssued=${step.queriesIssued} sat=${step.sat} unsatDiscarded=${step.unsatDiscarded} " +
                        "unknownDiscarded=${step.unknownDiscarded} witnessesVerified=${step.witnessesVerified} " +
                        "componentsRemaining=${step.componentsRemainingAfter} skippedTooLarge=${step.skippedTooLarge}",
                )
            }
            println(
                "  [CLEARING] FINAL classSize=${cls.size} componentsRemaining=${cleared.finalComponentsRemaining} " +
                    "residualPairs=${cleared.finalComponentsRemaining * (cleared.finalComponentsRemaining - 1) / 2} " +
                    "totalQueries=${cleared.totalQueries} totalTimeMs=${cleared.totalTimeMs}",
            )
        }
    }

    @Test
    fun ag216() = run("graphs/ag/ag2-16")

    @Test
    fun had64() = run("graphs/had/had-64")

    @Test
    fun triang20() = run("graphs/triang/triang-20")

    @Test
    fun lattice20() = run("graphs/lattice/lattice-20")

    // witnessHuntWithClearing's repeated re-decomposition of the (quotiented) subdivided graph
    // (11,800 vertices) SIGKILLed the JVM on latin-20 -- in the decomposition step itself, not the
    // encoding-size gate, so tooLargeToEncode() cannot catch it. Do not re-enable without an
    // explicit memory cap around the run (see feedback_avoid_singleinstanceexploration_test-style
    // guard rails elsewhere in this project).
    @Test
    @Disabled("latin-20 SIGKILLs in witnessHuntWithClearing's decomposition step, not gated by tooLargeToEncode -- see MULTIDECOMP_WITNESS_SPEC.md 6.3")
    fun latin20() = run("graphs/latin/latin-20")
}
