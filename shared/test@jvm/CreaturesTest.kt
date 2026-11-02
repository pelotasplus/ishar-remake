package dev.pelotasplus.ishar.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CreaturesTest {
    /** Fragonir after the intro (frame 900) as the notes' WORLD section has it. */
    @Test
    fun `the live table shows Fragonir`() = runTest {
        val vm = Starts.skipIntro(Play.files())
        val all = Creatures.all(vm.machine)
        assertEquals(Creatures.COUNT, all.size)
        val here = all.filter { it.contree == 1 }.associateBy { it.index }
        assertEquals(listOf("BORMINH", 15, 29), here.getValue(28).let { listOf(it.name, it.x, it.y) })
        assertEquals(listOf("KIRIELA", 28, 35, false), here.getValue(30).let { listOf(it.name, it.x, it.y, it.hostile) })
        assertEquals("azal", here.getValue(42).name)
        assertTrue(all[4].inParty, "ARAMIR is the party")
        assertEquals(listOf("dragon", 0), all[50].let { listOf(it.name, it.contree) })
    }
}
