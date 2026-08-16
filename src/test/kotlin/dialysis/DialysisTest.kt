package dialysis

import dialysis.ahu.ColoredAHU
import dialysis.decomposition.dialysis
import dialysis.util.GraphIO
import org.junit.jupiter.api.Test
import java.io.File

class DialysisTest {

    @Test
    fun dialysisAndAHUOnMiyazakiGraph() {
        val g = Utils.subdivision(Fixtures.MIYAZAKI_GRAPH)
        val rootIdByOriginalRoot = (0 until g.n).associateWith { root ->
            val dec = dialysis(g, root)
            val (tree, oldToNew) = g.induced(dec.treeVerts)
            ColoredAHU.compute(tree, emptyMap(), oldToNew[root]).rootId
        }
        val grouped = rootIdByOriginalRoot.entries.groupBy({ it.value }, { it.key })
        println(grouped.size)
        println(grouped.values)
    }

    @Test
    fun dialysisAndAHUOnHad20Graph() {
        val file = File("graphs/had/had-20")
        val original = GraphIO.loadDimacs(file.toPath())
        val g = Utils.subdivision(original)

        val rootIdByOriginalRoot = (0 until g.n).associateWith { root ->
            val dec = dialysis(g, root)
            val (tree, oldToNew) = g.induced(dec.treeVerts)
            ColoredAHU.compute(tree, emptyMap(), oldToNew[root]).rootId
        }
        val grouped = rootIdByOriginalRoot.entries.groupBy({ it.value }, { it.key })
        println(grouped.values.map { it.sorted() })

        val dec = dialysis(g, 320)
        println(dec)
        println("slices: ${dec.slices.size}, stars: ${dec.slices.count { it.isStar }}")

        // Every orphan belongs to exactly one slice, and slices partition dec.orphans.
        val orphansInSlices = dec.slices.flatMap { it.orphans.toList() }
        kotlin.test.assertEquals(dec.orphans.toSet(), orphansInSlices.toSet())
        kotlin.test.assertEquals(dec.orphans.size, orphansInSlices.size)
    }
}