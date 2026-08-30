package dialysis.util

import java.nio.file.Files
import java.nio.file.Path

/**
 * Every native-library extraction and [dialysis.decomposition.DecompositionStore]'s scratch file
 * goes through here so one property redirects all of them off the OS default temp directory. On
 * some VMs `java.io.tmpdir` is a small tmpfs/partition distinct from the machine's main disk, and
 * DecompositionStore's scratch file is O(n^2) in the (possibly subdivided) graph's vertex count --
 * confirmed to exceed such a partition well before the actual disk fills, on a subdivided instance
 * upward of ~8000 vertices. Configurable via `-Ddialysis.tmpDir=/path`; unset keeps the JVM's own
 * temp directory, so this is a no-op change for every existing invocation.
 */
fun dialysisTempFile(prefix: String, suffix: String): Path {
    val override = System.getProperty("dialysis.tmpDir")
    return if (override != null) Files.createTempFile(Path.of(override), prefix, suffix) else Files.createTempFile(prefix, suffix)
}
