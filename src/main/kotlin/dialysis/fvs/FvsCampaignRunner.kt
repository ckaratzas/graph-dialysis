package dialysis.fvs

import java.io.File

/**
 * Standalone CLI for [FvsSeededCampaign] -- lets the FVS-seeded 1-WL scale campaign run on a
 * machine that only has the shadow jar plus one family's graph files and ground-truth CSVs, not a
 * full repo checkout (see `scripts/package_fvs_campaign.sh`, which builds exactly that bundle).
 *
 * No `--enable-native-access`/other JVM flags beyond what `scripts/run_fvs_campaign.sh`'s packaged
 * `run.sh` already sets are required -- this only touches CaDiCaL, never the CryptoMiniSat stack
 * (no `LD_PRELOAD=libjsig.so` needed here, unlike `./gradlew test`/`run`; see build.gradle.kts's
 * own comment on why that preload exists at all -- it is specific to `backboneSimplify()`).
 *
 * Usage:
 *   java -cp graph-dialysis-1.0-all.jar dialysis.fvs.FvsCampaignRunnerKt \
 *     --family=cfi-rigid-r2 --graphsDir=graphs/cfi-rigid-r2 --groundTruth=results/r2-sat.csv \
 *     --out=results/fvs-seeded-1wl.csv
 *
 * `--graphsDir` defaults to `graphs/<family>`; `--groundTruth` defaults to this family's usual
 * ground-truth CSVs under `results/` (see [FvsSeededCampaign.defaultGroundTruthFiles]) if omitted;
 * `--out` defaults to `results/fvs-seeded-1wl.csv` and RESUMES if that file already has rows for
 * this family (copy an existing one over before running to continue a partial campaign).
 */
fun main(args: Array<String>) {
    val flags = args.associate { arg ->
        val body = arg.removePrefix("--")
        val eq = body.indexOf('=')
        if (eq < 0) body to "true" else body.substring(0, eq) to body.substring(eq + 1)
    }

    val family = flags["family"] ?: error("--family=cfi-rigid-r2 required (e.g. cfi-rigid-r2, cfi-rigid-z2, ...)")
    val graphsDir = File(flags["graphsDir"] ?: "graphs/$family")
    require(graphsDir.isDirectory) { "--graphsDir=$graphsDir does not exist or is not a directory" }
    val out = File(flags["out"] ?: "results/fvs-seeded-1wl.csv")

    val groundTruthFiles = flags["groundTruth"]
        ?.split(",")
        ?.map { File(it) to it }
        ?: FvsSeededCampaign.defaultGroundTruthFiles(family, File("results"))
    val groundTruth = FvsSeededCampaign.groundTruthFrom(groundTruthFiles)
    require(groundTruth.isNotEmpty()) {
        "no ground-truth entries loaded from ${groundTruthFiles.map { it.second }} -- check --groundTruth points at the right file(s)"
    }
    println("Loaded ${groundTruth.size} ground-truth entries from ${groundTruthFiles.map { it.second }}")

    FvsSeededCampaign.sweepFamily(family, graphsDir, groundTruth, out)
}
