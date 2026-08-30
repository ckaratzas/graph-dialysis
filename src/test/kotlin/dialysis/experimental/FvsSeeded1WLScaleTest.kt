package dialysis.experimental

import dialysis.fvs.FvsSeededCampaign
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Scaled version of [FvsSeeded1WLExperimentTest] -- same pipeline (FVS -> colour-class closure ->
 * restricted SAT orbit queries -> seeded 1-WL), swept across the real size range per family. Ground
 * truth comes from this repo's OWN already-computed `results` CSV files (CaDiCaL's
 * `recovered_orbits` where `status=CERTIFIED` -- already independently verified sound at CERTIFIED
 * time, per BENCHMARK_SPEC.md's own discipline -- or, for d3, `gt-d3.csv`'s dedicated
 * `true_orbits`), NOT a fresh Traces/nauty computation -- avoids re-paying for a slow, memory-heavy
 * ground-truth computation this repo already has cached, and naturally bounds the sweep to sizes
 * that already have a trustworthy answer on file.
 *
 * The actual pipeline lives in [dialysis.fvs.FvsSeededCampaign] (main source set) so it's also
 * reachable as a standalone CLI ([dialysis.fvs.FvsCampaignRunner]) for running on a machine that
 * only has the shadow jar plus one family's files, not a full repo checkout -- see
 * `scripts/package_fvs_campaign.sh`. This class just supplies each family's fixed, repo-relative
 * defaults (`graphs/<family>`, `results/fvs-seeded-1wl.csv`) as `./gradlew test` entry points.
 *
 * Writes a resumable CSV to `results/` (same resume-by-already-written-instance convention as
 * BenchmarkRunner) -- committed as a campaign artifact, not scratch output.
 */
class FvsSeeded1WLScaleTest {

    private val out = File("results/fvs-seeded-1wl.csv")
    private val resultsDir = File("results")

    private fun sweepFamily(family: String) {
        val graphsDir = File("graphs/$family")
        val groundTruth = FvsSeededCampaign.groundTruthFrom(FvsSeededCampaign.defaultGroundTruthFiles(family, resultsDir))
        FvsSeededCampaign.sweepFamily(family, graphsDir, groundTruth, out)
    }

    @Test fun r2() = sweepFamily("cfi-rigid-r2")
    @Test fun t2() = sweepFamily("cfi-rigid-t2")
    @Test fun s2() = sweepFamily("cfi-rigid-s2")
    @Test fun z2() = sweepFamily("cfi-rigid-z2")
    @Test fun d3() = sweepFamily("cfi-rigid-d3")
    @Test fun z3() = sweepFamily("cfi-rigid-z3")
}
