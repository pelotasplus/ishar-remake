package dev.pelotasplus.ishar.engine

/**
 * The cave doors: X 3c00, Y 3c34 and state 3b64 (0 closed) by door index, bytes.
 * Doors 12..15 and 21..23 are cave 12's, the others cave 15's.
 */
object Doors {
    private const val G = 0x126B

    private fun a(glob: Int) = glob + 0x2A0

    private const val COUNT = 41
    private val CAVE_12 = setOf(12, 13, 14, 15, 21, 22, 23)

    /** For [contree]'s doors, each cell and whether it is closed. */
    fun of(m: Machine, contree: Int): Map<Pair<Int, Int>, Boolean> {
        if (contree != 12 && contree != 15) return emptyMap()
        return (0 until COUNT).filter { it != 8 && (it in CAVE_12) == (contree == 12) }.associate { d ->
            (m.rb(G, a(0x3C00) + d) to m.rb(G, a(0x3C34) + d)) to (m.rb(G, a(0x3B64) + d) == 0)
        }
    }
}
