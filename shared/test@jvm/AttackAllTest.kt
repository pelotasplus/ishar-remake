package dev.pelotasplus.ishar.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AttackAllTest {
    private val g = 0x126B
    private fun a(glob: Int) = glob + 0x2A0

    private fun life(m: Machine, record: Int) = m.rw(g, a(0x1B22) + 2 * record)
    private fun physical(m: Machine, slot: Int) = m.rb(g, a(0x16BC) + slot)

    /** The lake barbarian (EN1 59, c1 27,17, hostile) from the cell east of him. */
    private suspend fun beside(facing: Int): Scheduler {
        val vm = Starts.skipIntro(Play.files())
        assertTrue(Creatures.all(vm.machine)[59].hostile)
        Cheats.moveParty(vm.machine, 28, 17, facing)
        repeat(10) { vm.frame() }
        return vm
    }

    private fun space(vm: Scheduler, frames: Int): Input {
        val input = Input(vm).also { it.install() }
        input.keyDown(Input.SPACE)
        input.keyUp(Input.SPACE)
        repeat(frames) { vm.frame() }
        return input
    }

    /** Life is a signed word: below 0 is dead until the next move (frise.io 033d). As an unsigned read it looks like 65,531. */
    @Test
    fun `negative life is dead, for members and for creatures`() = runTest {
        val vm = beside(facing = 4)
        val m = vm.machine
        assertEquals(listOf(0), AttackAll.slots(m))

        m.ww(g, a(0x1542), 0xFFFB)
        assertEquals(-5, Cheats.party(m).single().life)
        assertEquals(emptyList(), AttackAll.slots(m), "a dead member is not sent in")

        m.ww(g, a(0x1542), 12)
        m.ww(g, a(0x1B22) + 2 * 59, 0xFFFB)
        assertEquals(-5, Creatures.all(m)[59].life)
        assertEquals(emptyList(), AttackAll.slots(m), "no swing at a dead creature")
    }

    @Test
    fun `Space strikes the hostile ahead, once for the member`() = runTest {
        val vm = beside(facing = 4)
        val m = vm.machine
        val before = physical(m, 0)
        assertEquals(listOf(0), AttackAll.slots(m))
        space(vm, 20)
        assertEquals(before - 1, physical(m, 0), "one swing costs one PHYSICAL")
        assertEquals(0, m.rw(g, a(0x1506)), "no target cursor left")
    }

    /** The developers' five: everyone the game lets strike does, one after the other. */
    @Test
    fun `a round sends in the whole front row`() = runTest {
        val place = Cheats.Place("barbarian", 28, 17, 1, 0, 0)
        val start = Cheats.debugStart(place)
        val vm = start.boot(Play.files())
        for (frame in 1 until Cheats.DEBUG_START_FRAMES) start.step(vm, frame)
        repeat(10) { vm.frame() }
        val m = vm.machine
        Cheats.moveParty(m, 28, 17, 4)
        repeat(5) { vm.frame() }
        val slots = AttackAll.slots(m)
        val before = slots.map { physical(m, it) }
        space(vm, 60)
        val spent = slots.zip(before).map { (s, b) -> b - physical(m, s) }
        // SHEELD's crossbow (slot 4) stays out; slot 2 holds a flask, no weapon, so the game does not swing it
        assertEquals(listOf(0, 1, 2, 3), slots)
        assertEquals(listOf(1, 1, 0, 1), spent)
        assertEquals(0, m.rw(g, a(0x1506)), "no target cursor left")
    }

    @Test
    fun `Space does not swing at nothing`() = runTest {
        val vm = beside(facing = 2)
        val before = physical(vm.machine, 0)
        assertEquals(emptyList(), AttackAll.slots(vm.machine))
        space(vm, 20)
        assertEquals(before, physical(vm.machine, 0))
    }

    @Test
    fun `a bow in hand keeps the member out`() = runTest {
        val vm = beside(facing = 4)
        vm.machine.ww(g, a(0x1568) + 2 * 9, 0xFFD2)
        assertEquals(emptyList(), AttackAll.slots(vm.machine))
    }

    @Test
    fun `only a hostile on the cell ahead counts`() {
        val p = Cheats.Position(28, 17, 4, 1)
        fun orc(x: Int, y: Int, mode: Int = -3, life: Int = 5) = Creatures.Creature(0, "ORC", x, y, 1, life, mode, false)
        assertTrue(AttackAll.hostileAhead(p, listOf(orc(27, 17))))
        assertTrue(!AttackAll.hostileAhead(p, listOf(orc(26, 17))), "two cells off")
        assertTrue(!AttackAll.hostileAhead(p, listOf(orc(29, 17))), "behind")
        assertTrue(!AttackAll.hostileAhead(p, listOf(orc(27, 17, mode = 0))), "peaceful")
        assertTrue(!AttackAll.hostileAhead(p, listOf(orc(27, 17, life = 0))), "dead")
    }
}
