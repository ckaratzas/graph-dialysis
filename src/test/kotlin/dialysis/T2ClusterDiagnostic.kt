package dialysis

import dialysis.gadgetxor.RealFileBypassedGadgetReconstruction
import dialysis.gadgetxor.RealFileBypassedGadgetXor
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Read-only inspection of what [RealFileBypassedGadgetReconstruction]/[RealFileBypassedGadgetXor]
 * actually recover on real `cfi-rigid-t2` files, at three sizes -- no assertions, prints the
 * cluster/odd-port-count histograms and the per-port multiplicity distribution that
 * GADGET_XOR_SPEC.md's Part 2 cluster-merge discussion is based on. A diagnostic, not a
 * regression test: run it directly (or via -i) when re-deriving or sanity-checking those numbers,
 * not as part of routine `./gradlew test`.
 */
@Disabled("edit-and-rerun manual diagnostic, no assertions -- see class doc; run explicitly via --tests when re-deriving GADGET_XOR_SPEC.md Part 2's numbers")
class T2ClusterDiagnostic {
    @Test
    fun inspect() {
        for (path in listOf(
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0048-01-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0192-01-1",
            "graphs/cfi-rigid-t2/cfi-rigid-t2-0864-01-1",
        )) {
            val recon = RealFileBypassedGadgetReconstruction.loadAndReconstruct(path)
            val sided = RealFileBypassedGadgetXor.prepare(recon)
            println("=== $path ===")
            println("gadgets=${recon.gadgets.size} clusters=${sided.clusters.size}")
            val sizes = sided.clusters.values.map { it.size }
            println("cluster size histogram: ${sizes.groupingBy { it }.eachCount()}")
            val oddCounts = sided.clusterOddPorts.values.map { it.size }
            println("odd-port-count per cluster: ${oddCounts.groupingBy { it }.eachCount()}")
            val totalPortRefs = recon.gadgets.size * 3
            val totalOdd = oddCounts.sum()
            println("total port-references=$totalPortRefs, total surviving (odd) after cancellation=$totalOdd")

            val portMultiplicity = HashMap<Pair<Int, Int>, Int>()
            for (gadget in recon.gadgets) {
                val ports = RealFileBypassedGadgetReconstruction.ports(gadget.members)
                val opposites = RealFileBypassedGadgetReconstruction.oppositePairs(gadget.members)
                for (j in 0 until 3) {
                    val (p, _) = ports[j]; val (op, _) = opposites[j]
                    val a = recon.cliqueOf.getValue(p to j); val b = recon.cliqueOf.getValue(op to j)
                    val key = minOf(a, b) to maxOf(a, b)
                    portMultiplicity[key] = (portMultiplicity[key] ?: 0) + 1
                }
            }
            println("distinct ports=${portMultiplicity.size}, multiplicity histogram=${portMultiplicity.values.groupingBy { it }.eachCount()}")
        }
    }
}
