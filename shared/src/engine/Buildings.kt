package dev.pelotasplus.ishar.engine

/**
 * What the party can walk into: the place tables (X, Y, contree, kind; 4 bytes each). The positions are the door cells, which block, so the party
 * stands next to one when 399e says it went in.
 */
object Buildings {
    private const val G = 0x126B

    private fun a(glob: Int) = glob + 0x2A0

    /** [letter] is the mark on the map; other houses ([HOUSE]) get none. Saved by ordinal: add at the end. */
    enum class Kind(val letter: String?) {
        SHOP("S"), TAVERN("T"), SPELL_HALL("M"), COURSE("C"), ERWAN("E"), DELORIA("D"), PSYCHOANALYST("P"), JON("J"), HOUSE(null), THORM("T"), FORBIDDEN("F"), CHEST("2"),

        // Ishar 2's scene doors (IslandMap); new ones go last, the saved maps keep the ordinals
        GORDBNOEUF("G"), OLBAR("O"), ZELDY("Z"), SCENE("?"), BOAT("B")
    }

    data class Building(val kind: Kind, val x: Int, val y: Int, val contree: Int)

    fun all(m: Machine): List<Building> {
        fun table(glob: Int, count: Int, kind: (Int) -> Kind) = (0 until count).map { i ->
            val at = a(glob) + 4 * i
            Building(kind(m.rb(G, at + 3)), m.rb(G, at), m.rb(G, at + 1), m.rb(G, at + 2))
        }.filter { it.contree != 0 }
        return table(0x3FD6, 9) { Kind.SHOP } + table(0x3F94, 13) { Kind.TAVERN } +
            table(0x4178, 14) { if (it < 10) Kind.SPELL_HALL else Kind.COURSE } + table(0x41B8, 11) { house(it) }
    }

    /** By house number. */
    private fun house(number: Int) = when (number) {
        2 -> Kind.CHEST
        3, 4 -> Kind.PSYCHOANALYST
        7 -> Kind.JON
        8 -> Kind.ERWAN
        9 -> Kind.DELORIA
        10 -> Kind.THORM
        11 -> Kind.FORBIDDEN
        else -> Kind.HOUSE
    }

    /** The party is inside a building (399e). */
    fun inside(m: Machine) = m.rb(G, a(0x399E)) != 0

    /** The door the party at [p] went through: the cell it faces, or else one beside it. */
    fun at(p: Cheats.Position, all: List<Building>): Building? {
        val (dx, dy) = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).getOrNull(p.facing - 1) ?: return null
        val here = all.filter { it.contree == p.contree }
        return here.firstOrNull { it.x == p.x + dx && it.y == p.y + dy }
            ?: here.firstOrNull { kotlin.math.abs(it.x - p.x) + kotlin.math.abs(it.y - p.y) == 1 }
    }
}
