package dialysis

import dialysis.graph.Graph
import dialysis.refinement.NativeWL1
import dialysis.util.GraphIO
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class `1WLTest` {

    @Test
    fun testSimple1WL() {
        val file = File("graphs/cfi-rigid-t2/cfi-rigid-t2-0480-04-2")
        val g = Utils.subdivision(GraphIO.loadDimacs(file.toPath()))
        val colors = NativeWL1.compute1WLColors(g)

        val colorClasses = HashMap<Int, MutableList<Int>>()
        colors.forEachIndexed { v, c -> colorClasses.getOrPut(c) { mutableListOf() }.add(v) }
        val classes = colorClasses.values.sortedBy { it.min() }
        println("cfi-rigid-t2-0480-04-2: ${classes.size} classes over ${g.n} vertices")
        println(classes)
    }

    @Test
    fun seedingNeverCoarsensThePartition() {
        val file = File("graphs/ag/ag2-2")
        val g = Utils.subdivision(GraphIO.loadDimacs(file.toPath()))

        val uniform = NativeWL1.compute1WLColors(g)
        val uniformClasses = uniform.toSet().size

        val seeded = IntArray(g.n) { if (it == 0) 1 else 0 }
        val seededColors = NativeWL1.compute1WLColorsFrom(g, seeded)
        val seededClasses = seededColors.toSet().size

        assertTrue(seededClasses >= uniformClasses)
        assertEquals(1, seededColors.count { it == seededColors[0] })
    }
}