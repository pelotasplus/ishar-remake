package dev.pelotasplus.ishar.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class ExeDataTest {

    @Test
    fun `ExeData populates hardcoded tables and initial SS globals`() {
        val machine = Machine()
        ExeData.load(machine)

        // Keyboard table at cs:04be (Esc = 0x1b at index 0, 'q' = 0x71 at index 15)
        assertEquals(0x1B, machine.rb(Machine.CS, 0x04BE))
        assertEquals('q'.code, machine.rb(Machine.CS, 0x04BE + 15))

        // Fibonacci deltas at cs:16bc (-34 .. 34)
        assertEquals(-34, machine.rsb(Machine.CS, 0x16BC))
        assertEquals(34, machine.rsb(Machine.CS, 0x16BC + 15))

        // Initial SS globals
        assertEquals(0x04, machine.gb(0x0B60))
        assertEquals(0x01, machine.gb(0x0B64))
        assertEquals(0xFFFF, machine.g(0x0BA0))
        assertEquals(319, machine.g(0x0BBA))
        assertEquals(199, machine.g(0x0BBC))
        assertEquals(0x80, machine.gb(0x0CC9))
        assertEquals(0x80, machine.gb(0x0E0B))
        assertEquals(0xFF, machine.gb(0x1143))
        assertEquals(0xFF, machine.gb(0x1442))
        assertEquals('\\'.code, machine.gb(0x1D63))
        assertEquals(0x90, machine.gb(0x1E3D))

        // Sound driver tables
        assertEquals(12_000, machine.rw(machine.build.sbd, 0x00D3))
        assertEquals(0x01, machine.rb(machine.build.snd, 0x001A))
        assertEquals(0x4B, machine.rb(machine.build.snd, 0x001A + 255))
    }
}
