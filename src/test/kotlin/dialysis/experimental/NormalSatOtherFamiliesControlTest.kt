package dialysis.experimental

import dialysis.cl.TracesJni
import dialysis.refinement.dispatchColouring
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.buildCadicalEncodingSideSwapped
import dialysis.sat.cadical.driveToOrbitsCadical
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

/**
 * CONTROL for [FvsSeededOtherFamiliesTest]: on the SAME 25 instances (15 non-`cfi-rigid` families)
 * where FVS-seeded 1-WL matched Traces on 24/25, does today's PRODUCTION path -- unrestricted
 * `driveToOrbitsCadical`, both the preserve and side-swapped encodings, exactly what
 * `BenchmarkRunner` runs -- already solve them fine on its own? If it does, FVS-seeded 1-WL isn't
 * unlocking anything NEW on these families (the same conclusion Part 4.3 already reached for
 * t2/s2: sound, but not a win over what's already achievable) -- it would only be a genuine result
 * if it solved something production's own unrestricted search does NOT.
 */
class NormalSatOtherFamiliesControlTest {

    private fun check(path: String): Boolean {
        val g = GraphIO.loadDimacs(File(path).toPath())
        val dispatch = dispatchColouring(g, allowSubdivision = true)
        val colorOf = { v: Int -> dispatch.colouring[v] }

        val (solver, encoding) = buildCadicalEncoding(g, colorOf)
        val swapPair = buildCadicalEncodingSideSwapped(g, colorOf)
        val t0 = System.currentTimeMillis()
        val result = try {
            driveToOrbitsCadical(g, solver, encoding, swapPair, timeoutMs = 30_000, shortMs = 1_000)
        } finally {
            solver.close()
            swapPair?.first?.close()
        }
        val ms = System.currentTimeMillis() - t0

        val traces = TracesJni()
        val trueOrbitLabel = traces.orbits(g, listOf(IntArray(g.n) { it }))
        val trueOrbitCount = trueOrbitLabel.toHashSet().size

        val certified = result.queriesUnknown == 0
        val matches = result.orbits.size == trueOrbitCount
        println(
            "$path: n=${g.n} wall_ms=$ms queriesIssued=${result.queriesIssued} unknown=${result.queriesUnknown} " +
                "orbits=${result.orbits.size} trueOrbits=$trueOrbitCount CERTIFIED=$certified MATCH=$matches",
        )
        return certified && matches
    }

    @Test
    fun run() {
        val paths = listOf(
            "graphs/cfi/cfi-20", "graphs/cfi/cfi-22",
            "graphs/mz/mz-2", "graphs/mz/mz-4",
            "graphs/mz-aug/mz-aug-2", "graphs/mz-aug/mz-aug-4",
            "graphs/mz-aug2/mz-aug2-4", "graphs/mz-aug2/mz-aug2-6",
            "graphs/sts/sts-7", "graphs/sts/sts-9", "graphs/sts/sts-13",
            "graphs/sts-sw/sts-sw-19-1", "graphs/sts-sw/sts-sw-21-1",
            "graphs/rnd-3-reg/rnd-3-reg-1000-1",
            "graphs/ag/ag2-2", "graphs/ag/ag2-3",
            "graphs/pg/pg2-2", "graphs/pg/pg2-3",
            "graphs/had/had-1", "graphs/had/had-4",
            "graphs/latin/latin-2", "graphs/latin/latin-4",
            "graphs/paley/paley-5", "graphs/paley/paley-9",
            "graphs/lattice/lattice-4",
            "graphs/triang/triang-4",
            "graphs/grid-w/grid-w-3-2",
        )
        var allOk = true
        for (path in paths) if (!check(path)) allOk = false
        println("ALL CERTIFIED AND MATCH = $allOk")
    }
}
