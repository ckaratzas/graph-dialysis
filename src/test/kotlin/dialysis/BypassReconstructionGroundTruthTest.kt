package dialysis

import dialysis.gadgetxor.RealFileBypassedGadgetReconstruction
import dialysis.gadgetxor.RealFileGadgetReconstruction
import dialysis.graph.Graph
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** Validates the Kotlin port of the bypass reconstruction algorithm against a KNOWN
 *  ground-truth structure: take a real, already-correctly-reconstructed r2 file and bypass it
 *  ourselves (clique substitution on its known outer vertices), producing a graph with the same
 *  shape real t2 files have (base-reduced + bypassed) but with full ground truth available. */
class BypassReconstructionGroundTruthTest {

    @Test
    fun reconstructsAllGadgetsFromABypassedRealR2File() {
        val path = "graphs/cfi-rigid-r2/cfi-rigid-r2-1008-01-1"
        val r2Recon = RealFileGadgetReconstruction.loadAndReconstruct(path)
        val g = r2Recon.g
        val adjSets = Array(g.n) { g.adj[it].toHashSet() }
        val trueCliques = (0 until g.n).filter { adjSets[it].size != 3 }.map { adjSets[it].toSet() }
        val inner = (0 until g.n).filter { adjSets[it].size == 3 }
        val newAdj = HashMap<Int, MutableSet<Int>>()
        for (v in inner) newAdj[v] = mutableSetOf()
        for (clique in trueCliques) for (a in clique) for (b in clique) if (a != b) newAdj.getValue(a).add(b)

        val remap = inner.withIndex().associate { (idx, v) -> v to idx }
        val bypassedAdj = Array(inner.size) { i ->
            newAdj.getValue(inner[i]).map { remap.getValue(it) }.sorted().toIntArray()
        }
        val bypassedGraph = Graph(inner.size, bypassedAdj, Array(inner.size) { "v$it" })

        val trueGadgetCount = r2Recon.gadgets.size
        println("expected true gadgets: $trueGadgetCount, bypassed graph n=${bypassedGraph.n}")

        val recon = RealFileBypassedGadgetReconstruction.reconstruct(bypassedGraph, path)
        println("reconstructed gadgets: ${recon.gadgets.size}")
        assertEquals(trueGadgetCount, recon.gadgets.size)

        val allMembers = recon.gadgets.flatMap { it.members }.toHashSet()
        assertEquals(bypassedGraph.n, allMembers.size, "every vertex must appear in exactly one gadget")

        // Every gadget must actually be a K4 (sanity -- should always hold by construction).
        for (gadget in recon.gadgets) {
            val (m, x, y, z) = gadget.members
            val quad = listOf(m, x, y, z)
            for (i in quad.indices) for (j in i + 1 until 4) {
                check(quad[j] in bypassedGraph.adj[quad[i]].toHashSet()) { "gadget ${gadget.members} is not a valid K4" }
            }
        }
        println("all ${recon.gadgets.size} gadgets are valid K4s covering all ${bypassedGraph.n} vertices exactly once")
    }
}
