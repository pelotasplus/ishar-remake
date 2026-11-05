package dev.pelotasplus.ishar.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CopyProtectionTest {
    /** M stands at c2 (46,16) facing east: walking across into c3 asks nothing, and the party walks on. */
    @Test
    fun `crossing into contree 3 asks nothing`() = runTest {
        TestResources.needSaves()
        val (vm, _) = Play.loaded(Play.files(), "M")
        val input = Input(vm).also { it.install() }
        val m = vm.machine
        fun step() {
            input.keyDown(Input.UP)
            repeat(4) { vm.frame(); CopyProtection.remove(m) }
            input.keyUp(Input.UP)
            repeat(30) { vm.frame(); CopyProtection.remove(m) }
            assertEquals(null, Frames.of(m, MONSTRE_IO), "no question at ${Cheats.position(m)}")
        }
        while (Cheats.position(m).contree == 2) step()
        val x = Cheats.position(m).x
        repeat(2) { step() }
        assertEquals(listOf(3, x + 2), Cheats.position(m).let { listOf(it.contree, it.x) })
    }

    private companion object {
        const val MONSTRE_IO = 0x54
    }
}
