package dialysis.refinement

import dialysis.graph.Graph
import dialysis.util.dialysisTempFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JNI bridge to a hand-rolled Paige-Tarjan style partition refinement (see
 * `src/main/cpp/paige_tarjan_wl1.cpp`): the coarsest equitable partition
 * refining a given initial partition, which is exactly the 1-WL /
 * color-refinement fixed point. One native call runs a splitting-queue loop
 * to the fixed point rather than a round-by-round JVM implementation.
 *
 * All colors are compact 0..k-1 class ids, canonical up to CSR-order:
 * isomorphic instances get matching id assignments for corresponding vertices.
 */
object NativeWL1 {

    init {
        val stream = NativeWL1::class.java.getResourceAsStream("/libwl1jni.so")
            ?: error("libwl1jni.so not found in resources — run src/main/cpp/build_wl1.sh first")
        val tmp = dialysisTempFile("libwl1jni", ".so")
        tmp.toFile().deleteOnExit()
        stream.use { Files.copy(it, tmp, StandardCopyOption.REPLACE_EXISTING) }
        System.load(tmp.toAbsolutePath().toString())
    }

    /** 1-WL stable coloring from a uniform initial coloring. */
    @JvmStatic
    external fun compute1WLColors(
        csrOffsets: IntArray,
        csrNeighbors: IntArray,
        maxVertex: Int,
    ): IntArray

    /**
     * 1-WL stable coloring starting from [colors] (size maxVertex+1;
     * colors[v] = initial color class of v).
     */
    @JvmStatic
    external fun compute1WLColorsFrom(
        csrOffsets: IntArray,
        csrNeighbors: IntArray,
        maxVertex: Int,
        colors: IntArray,
    ): IntArray

    // ---- Convenience overloads (accept the library Graph directly) ----

    fun compute1WLColors(g: Graph): IntArray {
        val (offsets, neighbors) = g.toCsr()
        return compute1WLColors(offsets, neighbors, g.n - 1)
    }

    fun compute1WLColorsFrom(g: Graph, coloring: IntArray): IntArray {
        val (offsets, neighbors) = g.toCsr()
        return compute1WLColorsFrom(offsets, neighbors, g.n - 1, coloring)
    }
}