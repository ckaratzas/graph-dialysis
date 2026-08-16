package dialysis.sat

import dialysis.refinement.uniformSeed
import dialysis.refinement.initialPhase
import dialysis.util.GraphIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Guard rail on [initialPhase]'s Phase 2: its incremental remainder-anchoring is deliberately
 * order-sensitive, so a change to Phase 1's tree/orphan anchoring key that looks like a pure
 * relabeling (same equality, same partition at Phase 1) can still silently change the ORDER Phase
 * 2 processes cells in, changing the final class count even though nothing about Phase 1's own
 * output changed. A pinned golden value on a fixed instance is the only way to catch that class of
 * regression.
 */
class InitialPhaseRegressionTest {
    @Test
    fun z3_0180_01_2_classCount() {
        val path = "graphs/cfi-rigid-z3/cfi-rigid-z3-0180-01-2"
        val loaded = GraphIO.loadDimacs(File(path).toPath())
        val g = if (loaded.bipartition() != null) loaded else loaded.subdivided()
        val p = initialPhase(g, uniformSeed(g.n))
        println("initialPhase classCount=${p.cells.size} n=${g.n}")
        assertEquals(36, p.cells.size, "regression guard: Phase 2's order-sensitive anchoring must not be perturbed")
    }
}
