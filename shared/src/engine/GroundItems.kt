package dev.pelotasplus.ishar.engine

/**
 * The unique item table (1382/1404/1486, byte arrays by item code): what lies
 * in the world. Contree 0 = carried or taken, ffh = not on a map. Ishar 2 keeps X, Y and contree at
 * 2aba/2b84/2c4e (affobj.io 007d..00b4), 202 codes.
 */
object GroundItems {
    private const val G = 0x126B

    private fun a(glob: Int) = glob + 0x2A0

    const val COUNT = 130

    data class Item(val code: Int, val x: Int, val y: Int, val contree: Int, val ishar2: Boolean = false) {
        /** The money pouch and the sacks (20, 29h..47h); Ishar 2's are not known yet. */
        val money get() = !ishar2 && (code == 20 || code in 0x29..0x47)
    }

    private const val ISHAR2_COUNT = 0xCA

    /** Chests, daggers and bread (objint.io), which affobj.io draws only in these cave zones (0475). */
    private val INTERIOR = 51..93
    private val INTERIOR_ZONES = setOf(30, 32, 34, 35, 36, 37, 38, 41, 42, 43, 45, 46)

    /** The last code affobj.io's walks reach (0110, 018e). */
    private const val LAST_DRAWN = 110

    /** Whether the game draws item [code] while the zone (41e6) is [zone]. */
    fun drawn(code: Int, zone: Int) = code <= LAST_DRAWN && (code !in INTERIOR || zone in INTERIOR_ZONES)

    fun zone(m: Machine) = when (m.build) {
        GameBuild.ARBOREA -> 0
        GameBuild.ISHAR1 -> m.rb(G, a(0x41E6))
        GameBuild.ISHAR2 -> m.rb(m.build.globals, a(0x4EF2))
        GameBuild.ISHAR3 -> m.rb(m.build.globals, 0x3B33 + m.build.globBase)
    }

    fun all(m: Machine): List<Item> {
        if (m.build == GameBuild.ISHAR3 || m.build == GameBuild.ARBOREA) return emptyList() // their ground objects are not mapped yet
        if (m.build != GameBuild.ISHAR1) {
            val g = m.build.globals
            return (0 until ISHAR2_COUNT).map { i -> Item(i, m.rb(g, a(0x2ABA) + i), m.rb(g, a(0x2B84) + i), m.rb(g, a(0x2C4E) + i), ishar2 = true) }
        }
        return (0 until COUNT).map { i ->
            Item(i, m.rb(G, a(0x1382) + i), m.rb(G, a(0x1404) + i), m.rb(G, a(0x1486) + i))
        }
    }
}
