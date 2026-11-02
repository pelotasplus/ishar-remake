package dev.pelotasplus.ishar.engine

/**
 * The live EN1 table (the game's copy of EN1.FIC, script globals at `126b:G+2a0`): every person and
 * creature and where it stands now. Contree 0 = not placed in the world.
 */
object Creatures {
    private const val G = 0x126B

    private fun a(glob: Int) = glob + 0x2A0

    const val COUNT = 70

    data class Creature(
        val index: Int,
        val name: String,
        val x: Int,
        val y: Int,
        val contree: Int,
        val life: Int,
        /** Movement mode 22f2, signed: below 0 hostile, chasing within |mode| cells; 0 or more peaceful. */
        val mode: Int,
        val inParty: Boolean,
        /** Ishar 2: the zone the record belongs to (449a); 0 in Ishar 1. */
        val zone: Int = 0,
    ) {
        val hostile get() = mode < 0
    }

    /**
     * Ishar 3 (gerdep.io 0f18..0f44, the step check): zone 2f0ah, X 1e22h and Y 1f04h words, mode 2e22h (below 0
     * or 63h: in the way), life 20c8h, names 19d0h (10 bytes), up to [3519] records. Placed where its zone is the
     * current zone 3b33h, on the party's island 14b9h. Main.io's frame is at BP 3f4h ([GameBuild.globBase]).
     */
    private fun ishar3(m: Machine): List<Creature> {
        val g = m.build.globals
        fun b(glob: Int) = glob + m.build.globBase
        val zone = m.rb(g, b(0x3B33))
        val contree = m.rb(g, b(0x14B9))
        return (0 until m.rb(g, b(0x3519)).coerceAtMost(110)).map { i ->
            val z = m.rb(g, b(0x2F0A) + i)
            Creature(
                index = i,
                name = (0 until 10).map { m.rb(g, b(0x19D0) + 10 * i + it) }.takeWhile { it != 0 }.map { it.toChar() }
                    .joinToString("").let { if (it.length > 2 && it[1] == ' ') it.removeRange(1, 2) else it }.trim(),
                x = m.rw(g, b(0x1E22) + 2 * i),
                y = m.rw(g, b(0x1F04) + 2 * i),
                contree = if (zone != 0 && z == zone) contree else 0,
                zone = z,
                life = m.rsw(g, b(0x20C8) + 2 * i),
                mode = m.rb(g, b(0x2E22) + i).toByte().toInt(),
                inParty = false,
            )
        }
    }

    /**
     * Ishar 2 (the step check, gerdep.io 0c26..0c50): X 3412 and Y 34f0 words, mode 43ba (below 0
     * hostile), life 36ac, the party's records 2fa2. No contree: a creature is placed where its zone
     * 449a is the current zone 4ef2, and then stands on the party's island; up to [4a81] records.
     */
    private fun ishar2(m: Machine): List<Creature> {
        val g = m.build.globals
        val party = (0 until 5).map { m.rb(g, a(0x2FA2) + it) }.filter { it != 0xFF }.toSet()
        val zone = m.rb(g, a(0x4EF2))
        val contree = m.rb(g, a(0x2D18))
        return (0 until m.rb(g, a(0x4A81)).coerceAtMost(ISHAR2_COUNT)).map { i ->
            val here = zone != 0 && m.rb(g, a(0x449A) + i) == zone
            Creature(
                index = i,
                name = "",
                x = m.rw(g, a(0x3412) + 2 * i),
                y = m.rw(g, a(0x34F0) + 2 * i),
                contree = if (here) contree else 0,
                zone = m.rb(g, a(0x449A) + i),
                life = m.rsw(g, a(0x36AC) + 2 * i),
                mode = m.rb(g, a(0x43BA) + i).toByte().toInt(),
                inParty = i in party,
            )
        }
    }

    /** Words from 3412 to 34f0. */
    const val ISHAR2_COUNT = 111

    fun all(m: Machine): List<Creature> {
        if (m.build == GameBuild.ISHAR3) return ishar3(m)
        if (m.build != GameBuild.ISHAR1) return ishar2(m)
        val party = (0 until 5).map { m.rb(G, a(0x3908) + it) }.filter { it != 0xFF }.toSet()
        return (0 until COUNT).map { i ->
            Creature(
                index = i,
                name = (0 until 8).map { m.rb(G, a(0x1746) + 8 * i + it) }.takeWhile { it != 0 }
                    .map { it.toChar() }.joinToString("").trim(),
                x = m.rw(G, a(0x1978) + 2 * i),
                y = m.rw(G, a(0x1A06) + 2 * i),
                contree = m.rb(G, a(0x3910) + i),
                life = m.rsw(G, a(0x1B22) + 2 * i),
                mode = m.rb(G, a(0x22F2) + i).toByte().toInt(),
                inParty = i in party,
            )
        }
    }
}
