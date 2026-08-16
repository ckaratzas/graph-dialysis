package dialysis.util

import dialysis.graph.Graph
import org.jgrapht.graph.DefaultEdge
import java.nio.file.Files
import java.nio.file.Path
import java.util.HashSet

object GraphIO {

    /** Reads a plain edge-list file: one "u v" per line, blank lines and # comments ignored. */
    fun loadEdgeList(path: Path): Graph {
        val edges = mutableListOf<Pair<Int, Int>>()
        var maxVertex = -1
        Files.readAllLines(path).forEach { raw ->
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) return@forEach
            val parts = line.split("\\s+".toRegex())
            require(parts.size >= 2) { "Expected 'u v' on line: $line" }
            val u = parts[0].toInt()
            val v = parts[1].toInt()
            edges.add(u to v)
            maxVertex = maxOf(maxVertex, u, v)
        }
        return buildFromEdges(maxVertex + 1, edges)
    }

    /** Reads a DIMACS edge file (1-indexed vertices, converted to 0-indexed). */
    fun loadDimacs(path: Path): Graph {
        var n = 0
        val edges = mutableListOf<Pair<Int, Int>>()
        Files.readAllLines(path).forEach { raw ->
            val parts = raw.trim().split("\\s+".toRegex())
            when (parts.firstOrNull()) {
                "p" -> n = parts[2].toInt()
                "e" -> edges.add(parts[1].toInt() - 1 to parts[2].toInt() - 1)
            }
        }
        return buildFromEdges(n, edges)
    }

    /** Writes edges sorted canonically (smaller endpoint first), one per line. */
    fun save(graph: Graph, path: Path) {
        val lines = mutableListOf<String>()
        for (u in 0 until graph.n) for (v in graph.adj[u]) if (u <= v) lines.add("$u $v")
        Files.writeString(path, lines.sorted().joinToString("\n", postfix = "\n"))
    }

    private fun buildFromEdges(n: Int, edges: List<Pair<Int, Int>>): Graph {
        val bucket = Array(n) { mutableListOf<Int>() }
        for ((u, v) in edges) {
            bucket[u].add(v); bucket[v].add(u)
        }
        val adj = Array(n) { bucket[it].distinct().sorted().toIntArray() }
        return Graph(n, adj, Array(n) { it.toString() })
    }
}