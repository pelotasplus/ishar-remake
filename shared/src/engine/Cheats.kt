package dev.pelotasplus.ishar.engine

/**
 * Edits of the live game state (script globals G at `126b:G+2a0`) and the
 * developers' debug start (41e7): a new game with their party at any X/Y/contree/region/zone.
 */
object Cheats {
    private const val G = 0x126B

    private fun a(glob: Int) = glob + 0x2A0

    /** Script global [glob] of the running game ([GameBuild.globBase]). */
    private fun a(m: Machine, glob: Int) = glob + m.build.globBase

    val STATS = listOf("STR", "CON", "WIS", "INT", "AGI", "MENTAL", "PHYSICAL")

    /**
     * Where a game keeps the party (script globals). Ishar 2's are the globals its I -> II import
     * fills from Ishar 1's, read back live.
     */
    private class PartyGlobs(
        val names: Int, val life: Int, val maxLife: Int, val thousands: Int, val units: Int, val cls: Int,
        /** STR, CON, WIS, INT, AGI, MENTAL, PHYSICAL. */
        val stats: List<Int>,
        /** Bytes per name (Ishar 2: 10, io.io reads 50 for five). */
        val nameLen: Int = 8,
    )

    private val ISHAR1 = PartyGlobs(0x150C, 0x1542, 0x155A, 0x154E, 0x3770, 0x1664, listOf(0x1684, 0x168C, 0x1694, 0x169C, 0x16A4, 0x16B4, 0x16BC))
    private val ISHAR2 = PartyGlobs(0x2D1C, 0x2D5C, 0x2D74, 0x2D68, 0x2D80, 0x2E9E, listOf(0x2EBE, 0x2EC6, 0x2ECE, 0x2ED6, 0x2EDE, 0x2EEE, 0x2EF6), nameLen = 10)

    /**
     * Ishar 3: frise.io 2192 copies a joining character's record into these slot arrays (5 entries each): names
     * 1698h (10 bytes), life 16e0h, max life 1700h, gold in thousands 16f0h and units 1710h (marchand.io 049c),
     * class 185eh (ffh empty), STR/CON/WIS/INT/AGI 188eh..18beh, MENTAL 18e2h, PHYSICAL 18d6h.
     */
    private val ISHAR3 = PartyGlobs(0x1698, 0x16E0, 0x1700, 0x16F0, 0x1710, 0x185E, listOf(0x188E, 0x189A, 0x18A6, 0x18B2, 0x18BE, 0x18E2, 0x18D6), nameLen = 10)

    private fun globs(m: Machine) = when (m.build) {
        GameBuild.ARBOREA -> null // its party globals are not found yet
        GameBuild.ISHAR1 -> ISHAR1
        GameBuild.ISHAR2 -> ISHAR2
        GameBuild.ISHAR3 -> ISHAR3
    }

    data class Member(val slot: Int, val name: String, val life: Int, val maxLife: Int, val gold: Int, val stats: List<Int>)

    /** The members in their slots (class = ffh is an empty slot). */
    fun party(m: Machine): List<Member> {
        val p = globs(m) ?: return emptyList()
        val g = m.build.globals
        return (0 until 5).filter { m.rb(g, a(m, p.cls) + it) != 0xFF }.map { s ->
            var name = (0 until p.nameLen).map { m.rb(g, a(m, p.names) + p.nameLen * s + it) }.takeWhile { it != 0 }
                .map { it.toChar() }.joinToString("").trim()
            if (m.build != GameBuild.ISHAR1 && name.length > 2 && name[1] == ' ') name = name.removeRange(1, 2) // the drop cap's space: "Z ubaran"
            Member(
                slot = s,
                name = name,
                life = m.rsw(g, a(m, p.life) + 2 * s),
                maxLife = m.rw(g, a(m, p.maxLife) + 2 * s),
                gold = m.rw(g, a(m, p.thousands) + 2 * s) * 1000 + m.rw(g, a(m, p.units) + 2 * s),
                stats = p.stats.map { m.rb(g, a(m, it) + s) },
            )
        }
    }

    /** [heal], and in Ishar 2 the revived go to the front once frise.io has placed them ([formation2]). */
    fun heal(vm: Scheduler) {
        val revived = heal(vm.machine)
        if (revived.isNotEmpty() && vm.machine.build == GameBuild.ISHAR2) settle(vm, revived, tries = 60)
    }

    private fun settle(vm: Scheduler, slots: List<Int>, tries: Int) {
        vm.at(1) { s -> if (!formation2(s.machine, slots) && tries > 0) settle(s, slots, tries - 1) }
    }

    /**
     * Life to the maximum, PHYSICAL and MENTAL to 100, poison and blindness cleared; a dead member still in a
     * slot comes back too. Ishar 2: the slots whose revive was posted to frise.io, still to be placed.
     */
    fun heal(m: Machine): List<Int> {
        if (m.build != GameBuild.ISHAR1) return heal2(m)
        for (p in party(m)) {
            m.ww(G, a(0x1542) + 2 * p.slot, p.maxLife)
            m.wb(G, a(0x16BC) + p.slot, 100)
            m.wb(G, a(0x16B4) + p.slot, 100)
            m.ww(G, a(0x1658) + 2 * p.slot, 0)
            if (m.rw(G, a(0x164C) + 2 * p.slot) != 0) m.ww(G, a(0x164C) + 2 * p.slot, 1) // the tick takes its icon off
        }
        return emptyList()
    }

    /**
     * Ishar 2: life, PHYSICAL and MENTAL (its status timers are not mapped yet). A dead member stays in his
     * slot at life 0 under a skull (frise.io 5e8d, object 36h + slot) and leaves the formation grid 49e6
     * (5x5, cell a*5+b holds slot+1; b = 4 is the front). frise.io's event 32 (slot) brings him back
     * (0d89): the skull goes, name and portrait are drawn again, and he takes the first empty cell from
     * the back corner. So the dead (life 0, or a [skull] up) get that event once (none if one is still
     * queued), and [formation2] moves them to the front afterwards.
     */
    private fun heal2(m: Machine): List<Int> {
        val p = globs(m) ?: return emptyList()
        val g = m.build.globals
        val party = party(m)
        for (mem in party) {
            m.ww(g, a(m, p.life) + 2 * mem.slot, mem.maxLife)
            m.wb(g, a(m, p.stats[5]) + mem.slot, 100)
            m.wb(g, a(m, p.stats[6]) + mem.slot, 100)
        }
        if (m.build == GameBuild.ISHAR3) return revive3(m, party)
        val slots = party.map { it.slot }
        val queued = Frames.queued(m, FRISE).windowed(2).filter { it[0] == REVIVE && it[1] in slots }.map { it[1] }
        val dead = party.filter { it.life <= 0 || skull(m, it.slot) }.map { it.slot } - queued.toSet()
        val revived = dead.filter { slot ->
            Frames.post(m, FRISE, listOf(REVIVE, slot)).also { posted ->
                if (posted) for (i in 0 until 25) if (m.rb(g, a(0x49E6) + i) == slot + 1) m.wb(g, a(0x49E6) + i, 0) // frise places him
            }
        }
        formation2(m, revived + queued)
        return (revived + queued).distinct()
    }

    /**
     * frise.io's skull (object 36h + slot) is up: he is dead even with life above 0, as after a heal whose
     * revive frise.io's queue did not take (seen in play: full life under a skull).
     */
    private fun skull(m: Machine, slot: Int): Boolean {
        val bp = Frames.of(m, FRISE) ?: return false
        val es = m.g(0x0BD6)
        val ods = m.g(0x0BEA)
        val owner = m.rw(es, bp - 0x16)
        var di = m.rw(es, bp - 0x18)
        repeat(0x400) {
            if (di == 0) return false
            if (m.rw(ods, di + 2) == owner && m.rb(ods, di + 1) == 0x36 + slot) return true
            di = m.rw(ods, di + 4)
        }
        return false
    }

    /**
     * Ishar 3: frise.io (asset 0dh) takes event 32 (slot) to bring a member back (0e13): the skull (object 36h + slot)
     * goes, the name is drawn again and he takes the first empty cell of the formation grid 347ah (5x5, slot + 1).
     */
    private fun revive3(m: Machine, party: List<Member>): List<Int> {
        val g = m.build.globals
        return party.filter { it.life <= 0 }.map { it.slot }.filter { slot ->
            Frames.post(m, ISHAR3_FRISE, listOf(REVIVE, slot)).also { posted ->
                if (posted) for (i in 0 until 25) if (m.rb(g, a(m, 0x347A) + i) == slot + 1) m.wb(g, a(m, 0x347A) + i, 0)
            }
        }
    }

    private const val ISHAR3_FRISE = 0x0D

    /**
     * Every member in the grid once (one with no cell goes to the first empty front cell), [waiting] moved to
     * the front, and frise.io's local 9ah (who may strike: the front-most occupied column, 5be5..5c49) worked
     * out again. False while a [waiting] member has no cell yet.
     */
    private fun formation2(m: Machine, waiting: List<Int>): Boolean {
        val g = m.build.globals
        val grid = a(0x49E6)
        fun cell(i: Int) = m.rb(g, grid + i)
        fun frontFree() = (0 until 5).map { it * 5 + 4 }.firstOrNull { cell(it) == 0 }
        for (mem in party(m)) {
            val n = mem.slot + 1
            val cells = (0 until 25).filter { cell(it) == n }
            if (cells.isEmpty()) {
                if (mem.slot in waiting) return false
                val free = frontFree() ?: (0 until 25).firstOrNull { cell(it) == 0 } ?: continue
                m.wb(g, grid + free, n)
                continue
            }
            val keep = cells.maxBy { it % 5 }
            for (c in cells) if (c != keep) m.wb(g, grid + c, 0)
            if (mem.slot in waiting && keep % 5 != 4) frontFree()?.let { m.wb(g, grid + keep, 0); m.wb(g, grid + it, n) }
        }
        val bp = Frames.of(m, FRISE) ?: return true
        val es = m.g(0x0BD6)
        for (i in 0 until 5) m.wb(es, bp + REACH + i, 0)
        for (b in 4 downTo 0) {
            val front = (0 until 5).filter { a -> cell(a * 5 + b) != 0 }
            if (front.isEmpty()) continue
            for (a in front) m.wb(es, bp + REACH + cell(a * 5 + b) - 1, a + 1)
            break
        }
        return true
    }

    /** Ishar 2's frise.io and its per-member reach array (local 9ah). */
    private const val FRISE = 6
    private const val REACH = 0x9A
    private const val REVIVE = 32

    /**
     * What creatures and traps put on the party: binding, blindness, inversion, poison, and the icons of
     * their spells (3dec: 16, 22, 26, 34, 99). A DETECT INVISIBILITY cast leaves its icon for good: it
     * has no timer to end it. The panel draws its icons again when the tick takes one off (L7d34), so
     * the list gets a binding icon with a timer of 1 for frise.io's tick to end (7ed4).
     */
    fun removeCurses(m: Machine) {
        if (m.build == GameBuild.ISHAR3) return removeCurses3(m)
        if (m.build != GameBuild.ISHAR1) return removeCurses2(m)
        for (p in party(m)) {
            m.ww(G, a(0x164C) + 2 * p.slot, 0)
            m.ww(G, a(0x1610) + 2 * p.slot, 0)
            m.ww(G, a(0x1658) + 2 * p.slot, 0)
            val at = a(0x3DEC) + 10 * p.slot
            val icons = (0 until 10).map { m.rb(G, at + it) }.filter { it != 0 && it !in CURSE_ICONS }
            for (i in 0 until 10) m.wb(G, at + i, (icons + BINDING).getOrElse(i) { 0 })
            m.ww(G, a(0x15EC) + 2 * p.slot, 1)
        }
    }

    private const val BINDING = 26
    private val CURSE_ICONS = setOf(16, 22, BINDING, 34, 99)

    /**
     * Ishar 2: what creatures put on the party (eatt.io 102a, the effect number being its icon): 20 (glob 2e4a,
     * a flag: no attacking, frise.io 4b01), 23 (timer 2e86) and 26 (timer 2e26: no attacking, 0933). They
     * sit where Ishar 1's do, 183ah higher. As there, the icons go and a one-tick binding (26) is left for the
     * game's tick to end, so the panel draws its icons again.
     */
    private fun removeCurses2(m: Machine) {
        val g = m.build.globals
        for (p in party(m)) {
            m.ww(g, a(0x2E4A) + 2 * p.slot, 0)
            m.ww(g, a(0x2E86) + 2 * p.slot, 0)
            val at = a(0x4B88) + 10 * p.slot
            val icons = (0 until 10).map { m.rb(g, at + it) }.filter { it != 0 && it !in ISHAR2_CURSE_ICONS }
            for (i in 0 until 10) m.wb(g, at + i, (icons + BINDING).getOrElse(i) { 0 })
            m.ww(g, a(0x2E26) + 2 * p.slot, 1)
        }
    }

    private val ISHAR2_CURSE_ICONS = setOf(20, 23, BINDING)

    /**
     * Ishar 3 (eatt.io 1166): 20 the flag 17eeh, 23 the timer 183eh, 26 the timer 17beh, 99 the timer 184eh; their
     * icons in 36b4h (10 a member). No binding is left as in Ishar 2 (its timer 17beh counts down only on frise.io's
     * slow tick, 70f2, and a member under it cannot strike): frise.io's event 27 draws the icons again (00f2 -> 65f2).
     */
    private fun removeCurses3(m: Machine) {
        val g = m.build.globals
        for (p in party(m)) {
            m.ww(g, a(m, 0x17EE) + 2 * p.slot, 0)
            m.ww(g, a(m, 0x183E) + 2 * p.slot, 0)
            m.ww(g, a(m, 0x184E) + 2 * p.slot, 0)
            val at = a(m, 0x36B4) + 10 * p.slot
            val icons = (0 until 10).map { m.rb(g, at + it) }.filter { it != 0 && it !in ISHAR3_CURSE_ICONS }
            for (i in 0 until 10) m.wb(g, at + i, icons.getOrElse(i) { 0 })
            m.ww(g, a(m, 0x17BE) + 2 * p.slot, 0)
        }
        Frames.post(m, ISHAR3_FRISE, listOf(ICONS_REDRAW))
    }

    private const val ICONS_REDRAW = 27

    private val ISHAR3_CURSE_ICONS = setOf(20, 23, BINDING, 99)

    /** Gold as the game keeps it: thousands (154e) and units (3770). */
    fun setGold(m: Machine, slot: Int, gold: Int) {
        val g = gold.coerceIn(0, 65535 * 1000 + 999)
        val p = globs(m) ?: return
        m.ww(m.build.globals, a(m, p.thousands) + 2 * slot, g / 1000)
        m.ww(m.build.globals, a(m, p.units) + 2 * slot, g % 1000)
    }

    /** [amount] more gold for the first member in the party. */
    fun addGold(m: Machine, amount: Int) {
        val first = party(m).firstOrNull() ?: return
        setGold(m, first.slot, first.gold + amount)
    }

    fun setStat(m: Machine, slot: Int, stat: Int, value: Int) {
        val p = globs(m) ?: return
        m.wb(m.build.globals, a(m, p.stats[stat]) + slot, value.coerceIn(0, 255))
    }

    /** Party X/Y/facing (137c..137e; facing 1 N, 2 E, 3 S, 4 W) in the loaded contree; the view follows on the next step. */
    fun moveParty(m: Machine, x: Int, y: Int, facing: Int) {
        if (m.build == GameBuild.ISHAR3) {
            m.wb(m.build.globals, a(m, 0x257), x)
            m.wb(m.build.globals, a(m, 0x14B6), y)
            m.wb(m.build.globals, a(m, 0x14B7), ISHAR3_FACING[facing.coerceIn(1, 4) - 1])
            return
        }
        val p = a(m, m.build.partyPos)
        m.wb(m.build.globals, p, x)
        m.wb(m.build.globals, p + 1, y)
        m.wb(m.build.globals, p + 2, facing.coerceIn(1, 4))
    }

    /**
     * [moveParty] and, in Ishar 1, 3646 (main.io 09ee), the place along the country: it, not X, decides the contree seams.
     * [ownX] is X in the party's contree.
     */
    fun moveWithinContree(m: Machine, ownX: Int, y: Int, facing: Int) {
        moveParty(m, ownX, y, facing)
        if (m.build == GameBuild.ISHAR1) m.ww(m.build.globals, a(0x3646), (position(m).contree % 10 - 1) * 54 + ownX)
    }

    /**
     * Saving costs nothing: sos.io sets the fee to 3f12 = 1 thousand on each SAVE (0b64,
     * `1e 00 01 20 12 3f` = set lit8(1) -> glob.w(3f12)), refuses when the party has less (0bd8)
     * and takes it after OK (0cf8). The literal becomes 0 (or 1 again). sos.io stays loaded;
     * call it after loads (each frame). The screen still says 1000.
     */
    fun freeSaves(m: Machine, free: Boolean) {
        if (m.build != GameBuild.ISHAR1) return
        val es = m.es
        m.es = m.g(0x0BD6)
        val at = Assets.assetFindLoaded(m, 0x56)
        m.es = es
        val (seg, ofs) = at ?: return
        val from = if (free) 1 else 0
        for (i in 0 until 0x1000) {
            if (FEE.indices.all { k -> m.rb(seg, ofs + i + k) == (if (k == 2) from else FEE[k]) }) {
                m.wb(seg, ofs + i + 2, 1 - from)
                return
            }
        }
    }

    private val FEE = intArrayOf(0x1E, 0x00, 0x01, 0x20, 0x12, 0x3F)

    data class Position(val x: Int, val y: Int, val facing: Int, val contree: Int)

    /** Ishar 3's facing bytes for N, E, S, W: 1, 2, -1, -2 (vwall.io 003c: N steps Y down, E steps X up). */
    private val ISHAR3_FACING = listOf(1, 2, 0xFF, 0xFE)

    /** Party X/Y/facing ([GameBuild.partyPos]) and contree (1508, Ishar 2 2d18). */
    fun position(m: Machine): Position {
        val g = m.build.globals
        // Ishar 3: X 257h, Y 14b6h, facing 14b7h, island 14b9h (main.io 0cb0, a new game)
        if (m.build == GameBuild.ISHAR3) {
            val f = ISHAR3_FACING.indexOf(m.rb(g, a(m, 0x14B7))) + 1
            return Position(m.rb(g, a(m, 0x257)), m.rb(g, a(m, 0x14B6)), f, m.rb(g, a(m, 0x14B9)))
        }
        if (m.build == GameBuild.ARBOREA) {
            val p = a(m, m.build.partyPos)
            return Position(m.rb(g, p), m.rb(g, p + 1), m.rb(g, p + 2), 0)
        }
        val p = a(m, m.build.partyPos)
        val contree = a(if (m.build == GameBuild.ISHAR1) 0x1508 else 0x2D18)
        return Position(m.rb(g, p), m.rb(g, p + 1), m.rb(g, p + 2), m.rb(g, contree))
    }

    data class Place(val name: String, val x: Int, val y: Int, val contree: Int, val region: Int, val zone: Int)

    /** Debug start presets. */
    val PLACES = listOf(
        Place("c2 Rhudgast", 46, 16, 2, 4, 0),
        Place("c4", 48, 50, 4, 0, 0),
        Place("c6 krog", 20, 33, 6, 19, 0),
    )

    /**
     * [Starts.DEBUG_START] (the logo, Ctrl+Backspace, the test party "1", English) with [place] typed
     * into the position prompts at frame 593; the party is in play by [DEBUG_START_FRAMES].
     */
    fun debugStart(place: Place, start: Session = Starts.DEBUG_START): Session {
        val events = start.events.filterKeys { it < PROMPTS }.toMutableMap()
        val typed = with(place) { listOf(x, y, contree, region, zone) }.joinToString("") { "$it\r" }
        events[PROMPTS] = listOf(Session.Event.Type(typed.map { it.code }))
        return Session("debug start", start.cpuSpeed, start.randomSeed, events)
    }

    private const val PROMPTS = 593
    const val DEBUG_START_FRAMES = 820
}
