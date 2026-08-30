package dialysis

import dialysis.gadgetxor.RealFileBypassedGadgetReconstruction
import dialysis.gadgetxor.RealFileBypassedGadgetXor
import dialysis.refinement.dispatchColouring
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.driveToOrbitsCadical
import dialysis.sat.cryptominisat.buildCryptoMiniSatEncoding
import dialysis.sat.cryptominisat.driveToOrbitsCryptoMiniSat
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Head-to-head CaDiCaL vs cluster-gadget-XOR CryptoMiniSat on two small `cfi-rigid-t2` files
 * specifically flagged as edge cases during GADGET_XOR_SPEC.md's development (small enough that
 * cluster reconstruction's port/clique union step is most likely to over- or under-merge) -- a
 * targeted regression check that the recovered orbit partitions still agree, not a general
 * soundness sweep (see [dialysis.RealFileBypassedGadgetXorSoundnessTest] for that).
 */
class T2SoundnessOnProblematicFiles {
    private fun canonical(orbits: List<List<Int>>) = orbits.map { it.sorted() }.sortedBy { it.first() }

    private fun compare(path: String) {
        val g = GraphIO.loadDimacs(File(path).toPath())
        val recon = RealFileBypassedGadgetReconstruction.loadAndReconstruct(path)
        val sided = RealFileBypassedGadgetXor.prepare(recon)
        val dispatch = dispatchColouring(g)
        val colorOf = { v: Int -> dispatch.colouring[v] }

        val (cadicalSolver, cadicalEncoding) = buildCadicalEncoding(g, colorOf)
        val cadicalResult = try { driveToOrbitsCadical(g, cadicalSolver, cadicalEncoding, swapPair = null, timeoutMs = 30_000, shortMs = 2_000) } finally { cadicalSolver.close() }

        val (xorSolver, xorEncoding) = buildCryptoMiniSatEncoding(g, colorOf)
        val clustersConstrained = RealFileBypassedGadgetXor.addClusterParityXors(xorSolver, xorEncoding, sided)
        val xorResult = try { driveToOrbitsCryptoMiniSat(g, xorSolver, xorEncoding, timeoutMs = 30_000, shortMs = 2_000) } finally { xorSolver.close() }

        println("$path: clusters=${clustersConstrained}/${sided.clusters.size} cadical_orbits=${cadicalResult.orbits.size} xor_orbits=${xorResult.orbits.size} cadical_unknown=${cadicalResult.queriesUnknown} xor_unknown=${xorResult.queriesUnknown}")
        val match = canonical(cadicalResult.orbits) == canonical(xorResult.orbits)
        println("$path: orbits MATCH=$match")
        check(match) { "$path: CaDiCaL and gadget-XOR CryptoMiniSat disagree on orbits -- cadical=${canonical(cadicalResult.orbits)} xor=${canonical(xorResult.orbits)}" }
    }

    @Test
    fun checkProblematicFiles() {
        for (path in listOf(
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0016-04-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0020-01-1",
        )) compare(path)
    }
}
