package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CheatsTest {
    @Test
    fun `party edits read back and survive frames`() = runTest {
        val vm = Starts.skipIntro(Play.files())
        val m = vm.machine
        val before = Cheats.party(m)
        assertEquals("ARAMIR", before.first().name)
        m.ww(0x126B, 0x1542 + 0x2A0, 1)
        for (slot in Cheats.party(m).map { it.slot }) {
            m.wb(0x126B, 0x16BC + 0x2A0 + slot, 40)
            m.wb(0x126B, 0x16B4 + 0x2A0 + slot, 30)
        }
        Cheats.heal(m)
        for (p in Cheats.party(m)) {
            assertEquals(listOf(100, 100), listOf(p.stats[Cheats.STATS.indexOf("PHYSICAL")], p.stats[Cheats.STATS.indexOf("MENTAL")]), p.name)
        }
        Cheats.setGold(m, 0, 77123)
        Cheats.setStat(m, 0, 0, 99)
        for (f in 900 until 960) vm.frame()
        val p = Cheats.party(m).first()
        assertEquals(listOf(p.maxLife, 77123, 99), listOf(p.life, p.gold, p.stats[0]))
    }

    /** Every debug start ends in play at its place with the developers' five members. */
    @Test
    fun `debug starts land where asked`() = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        val bad = Cheats.PLACES.mapNotNull { place ->
            val s = Cheats.debugStart(place)
            val vm = s.boot(files.fresh())
            for (f in 1 until Cheats.DEBUG_START_FRAMES) s.step(vm, f)
            val got = Cheats.position(vm.machine)
            val party = Cheats.party(vm.machine).map { it.name }
            val want = Cheats.Position(place.x, place.y, got.facing, place.contree)
            if (got != want || party.size != 5) "${place.name}: $got $party" else null
        }
        assertEquals(emptyList(), bad)
    }

    @Test
    fun `add gold goes to the first member`() = runTest {
        val m = Starts.skipIntro(Play.files()).machine
        val before = Cheats.party(m).first().gold
        Cheats.addGold(m, 10000)
        assertEquals(before + 10000, Cheats.party(m).first().gold)
        // a slot left empty at the front: the first member is the next one
        m.wb(0x126B, 0x1664 + 0x2A0, 0xFF)
        val next = Cheats.party(m).firstOrNull()
        Cheats.addGold(m, 10000)
        assertEquals(next?.let { it.gold + 10000 }, Cheats.party(m).firstOrNull()?.gold)
    }

    @Test
    fun `remove curses ends the effects and takes their icons off the panel`() = runTest {
        val vm = Starts.skipIntro(Play.files())
        val m = vm.machine
        val g = 0x126B
        fun at(glob: Int) = glob + 0x2A0
        fun panel() = Palette.frameArgb(m).let { f -> (0 until 64).flatMap { x -> (138 until 186).map { y -> f[y * 320 + x] } } }
        fun icons() = (0 until 10).map { m.rb(g, at(0x3DEC) + it) }
        Boot.mouse(m, 30, 160, 1)
        for (f in 0 until 4) vm.frame()
        Boot.mouse(m, buttons = 0)
        for (f in 0 until 20) vm.frame()
        val clean = panel()
        for (timer in listOf(0x15EC, 0x164C)) m.ww(g, at(timer), 2000)
        m.ww(g, at(0x1610), 1)
        m.ww(g, at(0x1658), 5)
        for ((i, icon) in listOf(34, 22, 16, 99, 0x1B).withIndex()) m.wb(g, at(0x3DEC) + i, icon)
        Cheats.removeCurses(m)
        for (f in 0 until 10) vm.frame()
        assertEquals(listOf(0, 0, 0, 0), listOf(0x15EC, 0x164C, 0x1610, 0x1658).map { m.rw(g, at(it)) })
        assertEquals(listOf(0x1B) + List(9) { 0 }, icons(), "only the curses' icons go")
        // a detect invisibility icon, which has no timer of its own, leaves the screen too
        m.wb(g, at(0x3DEC), 34); m.wb(g, at(0x3DEC) + 1, 0)
        m.wb(g, at(0x3DEC) + 1, 26); m.ww(g, at(0x15EC), 1)
        for (f in 0 until 10) vm.frame()
        val shown = panel()
        Cheats.removeCurses(m)
        for (f in 0 until 10) vm.frame()
        assertEquals(List(10) { 0 }, icons())
        assertEquals(true, shown != panel(), "the panel was drawn again")
        assertEquals(clean, panel(), "as it was without the icons")
    }

    /** Saving costs 1000 (live: 37 -> 36 thousand); with free saves the gold stays. */
    @Test
    fun `free saves keep the gold`() = runTest {
        val files = Play.files()
        fun goldAfterSave(free: Boolean): Pair<Int, Int> {
            val (vm, next) = Play.loaded(files.fresh(), "LR")
            Cheats.setGold(vm.machine, 0, 5000)
            val before = Cheats.party(vm.machine).sumOf { it.gold }
            Play.run(vm, SaveGames.saveEvents("IO", next), next, SaveGames.saveFrames("IO")) {
                if (free) Cheats.freeSaves(vm.machine, true)
            }
            return before to Cheats.party(vm.machine).sumOf { it.gold }
        }
        val (paidBefore, paidAfter) = goldAfterSave(false)
        assertEquals(1000, paidBefore - paidAfter)
        val (freeBefore, freeAfter) = goldAfterSave(true)
        assertEquals(freeBefore, freeAfter)
    }

    /**
     * A poke alone keeps the old view (the notes' trap); landing one turn left and turning right
     * (GameViewModel.teleport) redraws it at the new place, facing the way asked.
     */
    @Test
    fun `a teleport redraws the view`() = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        fun screenAfter(turn: Boolean): Pair<Cheats.Position, IntArray> {
            val vm = Starts.skipIntro(files.fresh())
            val input = Input(vm).also { it.install() }
            input.poke { Cheats.moveParty(it, 15, 31, if (turn) 4 else 1) }
            repeat(3) { vm.frame() }
            if (turn) {
                input.keyDown(0x51)
                repeat(4) { vm.frame() }
                input.keyUp(0x51)
            }
            repeat(20) { vm.frame() }
            return Cheats.position(vm.machine) to Palette.frameArgb(vm.machine)
        }
        val (pokedAt, poked) = screenAfter(turn = false)
        val (at, turned) = screenAfter(turn = true)
        assertEquals(Cheats.Position(15, 31, 1, 1), pokedAt)
        assertEquals(Cheats.Position(15, 31, 1, 1), at)
        val view = (0 until 140).flatMap { y -> (0 until 240).map { x -> y * 320 + x } }
        assertTrue(view.count { poked[it] != turned[it] } > 5000, "the turn redraws the 3D view")
    }
}
