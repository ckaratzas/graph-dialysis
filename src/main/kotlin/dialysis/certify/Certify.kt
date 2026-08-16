package dialysis.certify

import dialysis.content.Content

/** Groups vertices `[0, n)` by [color] into cells sorted in canonical ([Content]) order. */
internal fun cellsFromColoring(n: Int, color: (Int) -> Content): List<IntArray> =
    (0 until n).groupBy(color)
        .entries
        .sortedBy { (c, _) -> c }
        .map { (_, verts) -> verts.toIntArray() }