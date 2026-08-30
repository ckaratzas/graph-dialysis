package dialysis.fvs

import dialysis.content.Content
import dialysis.refinement.colorRefine1WL
import dialysis.refinement.dispatchColouring
import dialysis.sat.SatQueryResult
import dialysis.sat.SeparatingUnionFind
import dialysis.sat.cadical.buildCadicalEncoding
import dialysis.sat.cadical.queryOrbitMateCadical
import dialysis.util.GraphIO
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter

/**
 * The FVS-seeded 1-WL scale campaign (FVS_SEEDED_1WL_SPEC.md): FVS -> colour-class closure ->
 * restricted SAT orbit queries -> seeded 1-WL, swept across the real size range of a family.
 * Extracted out of `dialysis.experimental.FvsSeeded1WLScaleTest` (still the entry point for
 * `./gradlew test`) so the exact same logic is also reachable as a standalone CLI
 * ([FvsCampaignRunner]) that only needs a jar, a JDK, and the one family's graph files + ground
 * truth CSVs -- not a full repo checkout (see `scripts/package_fvs_campaign.sh`).
 *
 * Ground truth is always this repo's OWN already-computed CSVs (CaDiCaL's `recovered_orbits`
 * where `status=CERTIFIED`, or a dedicated `true_orbits` column) -- never a fresh Traces/nauty
 * computation, per `BENCHMARK_SPEC.md`'s own discipline on what CERTIFIED already means.
 */
object FvsSeededCampaign {

    val HEADER = listOf(
        "family", "instance", "n", "m", "fvs_size", "seeded_size", "fvs_queries", "full_admissible_pairs",
        "wl_cells", "plain_wl_cells", "true_orbits", "gt_source", "match", "fvs_ms", "sat_ms", "wl_ms", "total_ms",
    )

    /** instance path -> (orbit count, which file it came from) -- built once per family from
     *  the given results CSVs, never from a fresh Traces call. */
    fun groundTruthFrom(files: List<Pair<File, String>>): Map<String, Pair<Int, String>> {
        val map = LinkedHashMap<String, Pair<Int, String>>()
        for ((file, sourceLabel) in files) {
            if (!file.exists()) continue
            val lines = file.readLines()
            if (lines.isEmpty()) continue
            val cols = lines[0].split(",")
            val instanceIdx = cols.indexOf("instance")
            val trueOrbitsIdx = cols.indexOf("true_orbits")
            val recoveredIdx = cols.indexOf("recovered_orbits")
            val statusIdx = cols.indexOf("status")
            for (line in lines.drop(1)) {
                val parts = line.split(",")
                if (parts.size <= instanceIdx) continue
                val instance = parts[instanceIdx]
                if (instance.isBlank() || instance in map) continue
                val trueOrbits = trueOrbitsIdx.takeIf { it >= 0 }?.let { parts.getOrNull(it) }?.takeIf { it.isNotBlank() }?.toIntOrNull()
                if (trueOrbits != null) { map[instance] = trueOrbits to sourceLabel; continue }
                val status = statusIdx.takeIf { it >= 0 }?.let { parts.getOrNull(it) }
                if (status == "CERTIFIED") {
                    val recovered = recoveredIdx.takeIf { it >= 0 }?.let { parts.getOrNull(it) }?.takeIf { it.isNotBlank() }?.toIntOrNull()
                    if (recovered != null) map[instance] = recovered to sourceLabel
                }
            }
        }
        return map
    }

    /** Per-family default ground-truth CSVs, relative to [resultsDir] -- the same files
     *  `dialysis.experimental.FvsSeeded1WLScaleTest` has always used for each family. */
    fun defaultGroundTruthFiles(family: String, resultsDir: File): List<Pair<File, String>> {
        fun f(name: String) = File(resultsDir, name) to name
        val shortName = family.removePrefix("cfi-rigid-")
        return when (shortName) {
            "r2" -> listOf(f("r2-sat.csv"))
            "t2" -> listOf(f("t2-sat.csv"), f("gt-t2.csv"))
            "s2" -> listOf(f("s2-sat.csv"))
            "z2" -> listOf(f("z2-sat.csv"))
            "z3" -> listOf(f("z3-sat.csv"))
            "d3" -> listOf(f("gt-d3.csv"), f("d3-sat.csv"), f("d3-sat-3600-plus.csv"), f("d3-cadical.csv"))
            else -> error("no default ground-truth source configured for $family -- pass --groundTruth explicitly")
        }
    }

    private fun alreadyDone(out: File): Set<String> {
        if (!out.exists()) return emptySet()
        return out.readLines().drop(1).mapNotNull { it.split(",").getOrNull(1)?.takeIf(String::isNotBlank) }.toSet()
    }

    fun peekN(path: String): Int {
        File(path).bufferedReader().useLines { lines ->
            for (line in lines) {
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.isNotEmpty() && parts[0] == "p") return parts[2].toInt()
            }
        }
        error("$path: no DIMACS 'p' line found")
    }

    /** One instance per distinct size bucket, restricted to instances that HAVE a ground-truth
     *  answer on file already -- covers the whole range the existing results already trust. */
    fun sampledInstances(graphsDir: File, groundTruth: Map<String, Pair<Int, String>>): List<String> {
        val files = (graphsDir.listFiles()?.filter { it.isFile }?.map { it.path } ?: emptyList()).sorted()
            .filter { it in groundTruth }
        val bySize = files.groupBy { peekN(it) }.toSortedMap()
        return bySize.values.map { it.first() }
    }

    private fun runOne(family: String, path: String, trueOrbits: Int, gtSource: String, writer: PrintWriter) {
        val t0 = System.currentTimeMillis()
        val g = GraphIO.loadDimacs(File(path).toPath())

        val fvsT0 = System.currentTimeMillis()
        val fvs = FeedbackVertexSet.compute(g)
        val fvsMs = System.currentTimeMillis() - fvsT0

        val satT0 = System.currentTimeMillis()
        val dispatch = dispatchColouring(g, allowSubdivision = false)
        val colorOf = { v: Int -> dispatch.colouring[v] }
        val (solver, encoding) = buildCadicalEncoding(g, colorOf)
        val uf = SeparatingUnionFind(g.n)
        val seeded = HashSet<Int>()
        for (members in encoding.groups) if (members.any { it in fvs }) seeded.addAll(members)
        var queriesIssued = 0
        try {
            for (members in encoding.groups) {
                val inSeeded = members.filter { it in seeded }
                if (inSeeded.size <= 1) continue
                for (u in inSeeded) for (v in inSeeded) {
                    if (u == v) continue
                    if (uf.find(u) == uf.find(v) || uf.separated(u, v)) continue
                    queriesIssued++
                    when (val r = queryOrbitMateCadical(solver, encoding, u, v, 60_000)) {
                        is SatQueryResult.Sat -> for (w in 0 until g.n) uf.union(w, r.alpha[w])
                        SatQueryResult.Unsat -> uf.markSeparated(u, v)
                        SatQueryResult.Unknown -> {}
                    }
                }
            }
        } finally {
            solver.close()
        }
        val satMs = System.currentTimeMillis() - satT0

        val wlT0 = System.currentTimeMillis()
        val initial = Array<Content>(g.n) { v -> if (v in seeded) Content.Str("fvs-orbit-${uf.find(v)}") else Content.Str("non-fvs") }
        val refined = colorRefine1WL(g, initial)
        val plainRefined = colorRefine1WL(g, Array(g.n) { Content.Str("u") })
        val wlMs = System.currentTimeMillis() - wlT0

        // Match against the CACHED ground truth's ORBIT COUNT only (not a full per-vertex partition
        // check -- the cached files don't carry per-vertex orbit labels, only the count). A count
        // match is weaker evidence than the small-scale experiment's full partition check, but a
        // count MISMATCH is still fully conclusive evidence of failure either way.
        val match = refined.cells.size == trueOrbits
        val fullAdmissiblePairs = encoding.groups.sumOf { it.size.toLong() * (it.size - 1) }
        val totalMs = System.currentTimeMillis() - t0

        val row = listOf(
            family, path, g.n, g.m, fvs.size, seeded.size, queriesIssued, fullAdmissiblePairs,
            refined.cells.size, plainRefined.cells.size, trueOrbits, gtSource, match, fvsMs, satMs, wlMs, totalMs,
        ).joinToString(",")
        writer.println(row)
        writer.flush()
        println("  -> $row")
    }

    /** Runs the campaign for one family, resuming from [out] if it already has rows (same
     *  resume-by-already-written-instance convention as `BenchmarkRunner`). */
    fun sweepFamily(family: String, graphsDir: File, groundTruth: Map<String, Pair<Int, String>>, out: File) {
        out.parentFile?.mkdirs()
        val resuming = out.exists()
        if (!resuming) out.writeText(HEADER.joinToString(",") + "\n")
        val done = alreadyDone(out)
        val instances = sampledInstances(graphsDir, groundTruth)
        println("=== $family: ${instances.size} size buckets with cached ground truth (max n=${instances.maxOfOrNull { peekN(it) }}) ===")
        PrintWriter(FileWriter(out, true)).use { writer ->
            for (path in instances) {
                if (path in done) { println("  SKIP (already done): $path"); continue }
                val (trueOrbits, gtSource) = groundTruth.getValue(path)
                try {
                    runOne(family, path, trueOrbits, gtSource, writer)
                } catch (e: Throwable) {
                    println("  FAILED: $path -- ${e::class.simpleName}: ${e.message}")
                    writer.println(listOf(family, path, "", "", "", "", "", "", "", "", "", "", "ERROR:${e::class.simpleName}", "", "", "", "").joinToString(","))
                    writer.flush()
                }
            }
        }
    }
}
