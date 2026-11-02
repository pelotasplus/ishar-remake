package dev.pelotasplus.ishar.engine

import kotlin.test.Test
import kotlin.test.assertEquals

class MachineTest {

    @Test
    fun `memory read write and globals operate on flat DOS addresses`() {
        val m = Machine()
        m.wb(0x126B, 0x02A0, 0xFE)
        assertEquals(0xFE, m.rb(0x126B, 0x02A0))
        assertEquals(-2, m.rsb(0x126B, 0x02A0))

        m.ww(0x126B, 0x02A2, 0x8001)
        assertEquals(0x8001, m.rw(0x126B, 0x02A2))
        assertEquals(-32767, m.rsw(0x126B, 0x02A2))

        // 16-bit offset wrapping
        m.wb(0x126B, 0x10005, 0x42)
        assertEquals(0x42, m.rb(0x126B, 0x0005))

        // Engine globals in SS = 0x0D88
        m.sg(0x0C9F, 332)
        assertEquals(332, m.g(0x0C9F))
        m.sgb(0x2892, 1)
        assertEquals(1, m.gb(0x2892))
    }

    @Test
    fun `lodsb and lodsw advance SI and update AX`() {
        val m = Machine()
        m.ds = 0x2000
        m.si = 0xFFFE
        m.wb(0x2000, 0xFFFE, 0x34)
        m.wb(0x2000, 0xFFFF, 0x12)
        m.wb(0x2000, 0x0000, 0x78)

        assertEquals(0x1234, m.lodsw())
        assertEquals(0x1234, m.ax)
        assertEquals(0x0000, m.si)

        assertEquals(0x78, m.lodsb())
        assertEquals(0x1278, m.ax)
        assertEquals(0x0001, m.si)
    }

    @Test
    fun `huge-pointer norm matches game xchg and shl 4 logic`() {
        // Basic offset normalisation without carry
        assertEquals(0x1CF6 to 0x0008, Machine.norm(0x1CF4, 0x0010, 0x0018, 0x0000))
        // Carry into high word (hi = 1 -> hx = 0x1000)
        assertEquals(0x2CF4 to 0x0004, Machine.norm(0x1CF4, 0xFFF0, 0x0014, 0x0000))
    }

    @Test
    fun `idiv32 retries on overflow with SAR or SHR`() {
        // Fits without overflow: -100 / 7 -> q = -14 (0xFFF2), r = -2 (0xFFFE)
        val (q1, r1) = Machine.idiv32((-100L) and 0xFFFFFFFFL, 7, sar = true)
        assertEquals(-14, Machine.s16(q1))
        assertEquals(-2, Machine.s16(r1))

        // Overflow with SAR (negative dividend stays negative)
        val bigNeg = (-0x40000L) and 0xFFFFFFFFL
        val (qSar, _) = Machine.idiv32(bigNeg, 2, sar = true)
        assertEquals(-0x8000, Machine.s16(qSar))

        // Overflow with SHR (logical shift clears high bit -> shifts until quotient fits 0x7FFE)
        val (qShr, _) = Machine.idiv32(bigNeg, 2, sar = false)
        assertEquals(0x7FFE, Machine.s16(qShr))
    }
}
