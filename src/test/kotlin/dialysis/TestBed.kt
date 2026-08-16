package dialysis

import dialysis.content.Content
import dialysis.graph.Graph
import dialysis.refinement.initialPhase
import dialysis.util.GraphIO
import java.io.File

object TestBed {
    private fun load(path: String): Graph = GraphIO.loadDimacs(File(path).toPath())
    private fun uniform(g: Graph): Array<Content> = Array(g.n) { Content.Str("X") }

    @JvmStatic
    fun main(args: Array<String>) {
        for (path in listOf("graphs/cfi/cfi-200")) {
            val g = (load(path)).subdivided()
            println(g.bipartition() != null)
            val pInit = initialPhase(g, uniform(g))
            for (element in pInit.cells) {
                println(element.toList())
            }
            println(pInit.cells.size)
        }
    }
}