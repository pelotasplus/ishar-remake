package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class BootTest {

    @Test
    fun `Boot initializes memory layout, blancpc samples, and main io frame 0`() = runTest {
        val repo = TestResources()
        val files = GameFiles.fromResourceRepository(repo, includeAllIo = false)
        val machine = Machine()

        Boot.boot(machine, files, cpuSpeed = 332, randomSeed = 0x0057)

        // the reference engine Boot state verification
        assertEquals(332, machine.g(0x0C9F))
        assertEquals(0, machine.gb(0x2892)) // slow_machine = 0 when cpu_speed > 150
        assertEquals(0x0057, machine.rw(0x0040, 0x006C))
        assertEquals(0x1123, machine.g(0x0E22))
        assertEquals(0x11C3, machine.g(0x0E26))
        assertEquals(0x126B, machine.g(0x0BD6)) // frame segment
        assertEquals(0x0100, machine.g(0x0BD2)) // frame table offset
        assertEquals(50, machine.g(0x0BDA)) // 50 frame slots
        assertEquals(0x1848, machine.g(0x0BE2)) // object area segment
        assertEquals(0x1CF3, machine.g(0x0BC6)) // asset heap start (the reference engine: 1cf3:0000)
        assertEquals(0x02A0, machine.g(0x0BF6)) // main.io frame 0 BP = 02a0
        assertEquals(1, machine.g(0x0BDC)) // 1 frame slot used
        assertEquals(1, machine.g(0x0BCE)) // 1 asset loaded (main.io)

        val mainAsset = assertNotNull(Assets.assetFindLoaded(machine, 0))
        assertEquals(0x1CF3 to 0x0000, mainAsset)
    }

    @Test
    fun `keyDown, keyUp, and mouse update CS input locations`() {
        val machine = Machine()
        ExeData.load(machine)

        Boot.keyDown(machine, 0x39) // Space scancode
        assertEquals(1, machine.rb(Machine.CS, 0x031A + 0x38))
        assertEquals(0x20, machine.rb(Machine.CS, 0x0318))

        Boot.keyUp(machine, 0x39)
        assertEquals(0, machine.rb(Machine.CS, 0x031A + 0x38))
        assertEquals(0, machine.rb(Machine.CS, 0x0318))

        Boot.mouse(machine, x = 160, y = 100, buttons = 1)
        assertEquals(160, machine.rw(Machine.CS, 0x11BD))
        assertEquals(100, machine.rw(Machine.CS, 0x11BF))
        assertEquals(1, machine.rw(Machine.CS, 0x11C1))
    }
}
