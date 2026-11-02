package dev.pelotasplus.ishar.engine

/**
 * Space in a fight: each member's ATTACK tab in turn, through the game's own click (frise.io 0309,
 * which still checks PHYSICAL, delays and the formation). A swing costs PHYSICAL even at nothing
 * (5f30), so it needs a hostile on the cell ahead. Missile hands are left out: their ATTACK leaves a
 * target cursor (1506) that stops the party moving.
 */
object AttackAll {
    private const val G = 0x126B

    private fun a(glob: Int) = glob + 0x2A0

    /** Inside the tab row, above y 129 where a target picker would take the click as a member (2eb5). */
    const val TAB_Y = 127

    fun tabX(slot: Int) = 64 * slot + 48

    private val MISSILES = setOf(0xCA, 0xD1, 0xD2)

    /** The members to send in now: none outside a fight or while the game wants something else. */
    fun slots(m: Machine): List<Int> {
        val busy = m.rb(G, a(0x399E)) != 0 || m.rb(G, a(0x426E)) != 0 || m.rb(G, a(0x137F)) != 0 ||
            m.rw(G, a(0x1506)) != 0 || m.rb(G, a(0x403A)) != 0
        if (busy || !hostileAhead(Cheats.position(m), Creatures.all(m))) return emptyList()
        return (0 until 5).filter { s ->
            val hands = listOf(9, 10).map { m.rb(G, a(0x1568) + 2 * (13 * s + it)) } // the low byte: codes from 80h are kept as ffxxh
            m.rb(G, a(0x1664) + s) != 0xFF && m.rsw(G, a(0x1542) + 2 * s) > 0 && m.rb(G, a(0x16BC) + s) > 0 &&
                m.rw(G, a(0x15EC) + 2 * s) == 0 && m.rw(G, a(0x1610) + 2 * s) == 0 && hands.none { it in MISSILES }
        }
    }

    fun hostileAhead(p: Cheats.Position, creatures: List<Creatures.Creature>): Boolean {
        val (dx, dy) = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).getOrNull(p.facing - 1) ?: return false
        val x = (WorldMap.worldX(p) ?: return false) + dx
        return creatures.any { it.hostile && !it.inParty && it.life > 0 && WorldMap.creatureX(it) == x && it.y == p.y + dy && WorldMap.areaOf(it.contree) == WorldMap.areaOf(p.contree) }
    }
}
