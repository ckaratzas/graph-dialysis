package dialysis.decomposition

import dialysis.graph.Graph
import dialysis.util.boundedPool
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files

/**
 * Off-heap store of dialysis(g, v) for every v in [0, g.n) — computed once per
 * vertex and serialized into a memory-mapped file rather than kept as live
 * Decomposition objects on the JVM heap. Every record is dominated by the two
 * dense n-length arrays (parent, depth), so holding all n simultaneously as
 * Kotlin objects is O(n^2) heap plus the allocation/GC overhead of n nested
 * Lists and IntArrays; mapping it instead lets the OS carry that footprint,
 * lets callers materialize only the fields one phase actually needs (see
 * [treeAndOrphanCount], [remainderComponents]), and lets a later stage (e.g.
 * Certify picking a root) fetch the ALREADY-computed decomposition via [get]
 * instead of recomputing dialysis(g, r) from scratch.
 *
 * Read-only after [build]. Not safe to use concurrently with mutation of [g]
 * (none of this codebase's pipeline mutates graphs in place, so this is not a
 * new constraint).
 */
class DecompositionStore private constructor(
    private val n: Int,
    private val buffer: MappedByteBuffer,
    private val offsets: IntArray,   // offsets[v] = byte start of v's own record (only meaningful when hasVertex[v])
    private val hasVertex: BooleanArray,
) {
    /** True iff [v]'s decomposition was actually computed and stored (see [build]'s [needed]
     *  mask) — false for a vertex the mask skipped, whose record is zero-length. Tracked
     *  explicitly (not inferred from `offsets[v] != offsets[v+1]`) because [build] now writes
     *  records in COMPLETION order (parallel computation), not strict ascending `v` order — the
     *  old inference relied on offsets being a monotonic running total assigned while iterating
     *  `v` in order, which no longer holds. */
    fun has(v: Int): Boolean = hasVertex[v]

    /** Full decomposition of root [v], deserialized on demand from the mapped bytes. */
    fun get(v: Int): Decomposition {
        val buf = reader(v)
        val treeVerts = buf.readInts()
        val parent = IntArray(n) { buf.buf.int }
        val depth = IntArray(n) { buf.buf.int }
        val orphans = buf.readInts()
        val slices = List(buf.buf.int) {
            val level = buf.buf.int
            val sliceOrphans = buf.readInts()
            val attachments = buf.readInts()
            Slice(level, sliceOrphans, attachments)
        }
        val remainderComps = List(buf.buf.int) { buf.readInts() }
        val connectorEdges = List(buf.buf.int) { buf.buf.int to buf.buf.int }
        return Decomposition(v, treeVerts, parent, depth, orphans, slices, remainderComps, connectorEdges)
    }

    /** Remainder components only — the field Phase 2 needs, without materializing
     *  parent/depth/slices/connectorEdges for every vertex. */
    fun remainderComponents(v: Int): List<IntArray> {
        val buf = reader(v)
        buf.skipInts()                          // treeVerts
        buf.skip(n * 2 * 4)                      // parent, depth
        buf.skipInts()                           // orphans
        val sliceCount = buf.buf.int
        repeat(sliceCount) {
            buf.buf.int                          // level
            buf.skipInts(); buf.skipInts()       // orphans, attachments
        }
        val compCount = buf.buf.int
        return List(compCount) { buf.readInts() }
    }

    /** Tree vertices + orphan count only — what Phase 1 needs. */
    fun treeAndOrphanCount(v: Int): Pair<IntArray, Int> {
        val buf = reader(v)
        val treeVerts = buf.readInts()
        buf.skip(n * 2 * 4)                      // parent, depth
        val orphanCount = buf.buf.int
        return treeVerts to orphanCount
    }

    private fun reader(v: Int): Reader {
        val dup = buffer.duplicate()
        dup.position(offsets[v])
        return Reader(dup)
    }

    private class Reader(val buf: ByteBuffer) {
        fun readInts(): IntArray {
            val size = buf.int
            return IntArray(size) { buf.int }
        }
        fun skipInts() {
            val size = buf.int
            buf.position(buf.position() + size * 4)
        }
        fun skip(bytes: Int) {
            buf.position(buf.position() + bytes)
        }
    }

    companion object {
        /**
         * Computes and stores dialysis(g, v) for every v with [needed]==null or
         * [needed]\[v\]==true; other vertices get a zero-length record (callers must
         * never query them — [initialPhase] only queries vertices it marked needed,
         * since those are exactly the ones whose Phase-0 cell could still be split).
         */
        fun build(g: Graph, needed: BooleanArray? = null): DecompositionStore {
            val n = g.n
            val tmp = Files.createTempFile("dialysis-decomp", ".bin")
            tmp.toFile().deleteOnExit()
            val offsets = IntArray(n + 1)
            val hasVertex = BooleanArray(n) { needed == null || needed[it] }
            val neededVertices = (0 until n).filter { hasVertex[it] }

            // dialysis(g, v) is pure JVM and reads only the shared, immutable graph — safe to
            // compute in parallel across vertices (this is the initial phase's own measured
            // dominant cost). Only the actual disk write is serialized (one lock around "grab the
            // next offset, write these already-computed bytes"), which is cheap next to the BFS-
            // based decomposition itself — this keeps AT MOST the parallel-stream's own in-flight
            // record count in memory at once, not every vertex's O(n)-sized record simultaneously
            // (which would reintroduce exactly the O(n^2) heap blowup this store's mmap design
            // exists to avoid — see the class doc).
            var pos = 0
            val writeLock = Any()
            BufferedOutputStream(FileOutputStream(tmp.toFile())).use { fileOut ->
                boundedPool.submit {
                    neededVertices.parallelStream().forEach { v ->
                        val dec = dialysis(g, v)
                        // One ByteBuffer per record, filled with plain putInt() (no per-int stream
                        // call), then a single bulk write — writeInt() through a DataOutputStream
                        // was measured costing multiple milliseconds per vertex on dense graphs
                        // (thousands of tiny writes for the dense parent/depth arrays alone).
                        val bytes = ByteBuffer.allocate(recordSize(dec, n))
                        writeRecord(bytes, dec, n)
                        val rec = bytes.array()
                        synchronized(writeLock) {
                            offsets[v] = pos
                            fileOut.write(rec)
                            pos += rec.size
                        }
                    }
                }.get()
            }
            offsets[n] = pos
            val channel = FileChannel.open(tmp, java.nio.file.StandardOpenOption.READ)
            val mapped = channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, pos.toLong()) }
            Files.deleteIfExists(tmp)
            return DecompositionStore(n, mapped, offsets, hasVertex)
        }

        private fun intsSize(arr: IntArray): Int = 4 + arr.size * 4

        private fun recordSize(dec: Decomposition, n: Int): Int {
            var size = intsSize(dec.treeVerts) + n * 4 + n * 4 + intsSize(dec.orphans) + 4
            for (s in dec.slices) size += 4 + intsSize(s.orphans) + intsSize(s.attachments)
            size += 4
            for (c in dec.remainderComps) size += intsSize(c)
            size += 4 + dec.connectorEdges.size * 8
            return size
        }

        private fun ByteBuffer.putInts(arr: IntArray) {
            putInt(arr.size)
            for (x in arr) putInt(x)
        }

        private fun writeRecord(buf: ByteBuffer, dec: Decomposition, n: Int) {
            buf.putInts(dec.treeVerts)
            for (x in dec.parent) buf.putInt(x)
            for (x in dec.depth) buf.putInt(x)
            buf.putInts(dec.orphans)
            buf.putInt(dec.slices.size)
            for (s in dec.slices) {
                buf.putInt(s.level)
                buf.putInts(s.orphans)
                buf.putInts(s.attachments)
            }
            buf.putInt(dec.remainderComps.size)
            for (c in dec.remainderComps) buf.putInts(c)
            buf.putInt(dec.connectorEdges.size)
            for ((u, w) in dec.connectorEdges) { buf.putInt(u); buf.putInt(w) }
        }
    }
}