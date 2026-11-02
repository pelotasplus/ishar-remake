package dev.pelotasplus.ishar.engine

import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `vdrv_draw_object` and the seven blitters against the reference engine: fuzzed positions, clip rectangles, mirror bits and
 * draw types on the objects of five pairs' snapshots A; CRC of the page after each draw.
 * Reaches the mirrored 8bpp and odd-column mirrored 4bpp paths no pair takes.
 */
class RendererBlitTest {

    private val expected = mapOf(
        "a" to 0x8749D0D9L,
        "is" to 0x7D6AA1B4L,
        "ro" to 0x50616868L,
        "hc" to 0xC49374ACL,
        "ld" to 0x73CB608AL,
    )

    @Test
    fun fuzzedDrawsMatchRebuild() {
        val got = VmPairs.scenarios().filter { it.a in expected }.associate { s ->
            val m = Machine(VmPairs.load(s).first.memory)
            val r = Renderer(Scheduler(m, GameFiles()))
            val es = m.g(0x0BD6)
            val o = m.g(0x0BEA)
            val objs = mutableListOf<Int>()
            var cx = m.g(0x0C16)
            while (cx != 0) {
                val bx = (m.g(0x0BF6) + cx) and 0xFFFF
                var di = m.rw(es, bx + 2)
                while (true) {
                    di = m.rw(o, di + 6)
                    if (di == 0) break
                    objs += di
                }
                cx = m.rw(es, bx + 4)
            }
            val rng = XorShift32(0x2468ACEL)
            val crc = CRC32()
            repeat(1500) {
                val di = objs[rng.below(objs.size)]
                val (ds, si) = m.entry(m.rw(o, di + 0x0A), m.rw(o, di + 8))
                val t0 = m.rb(ds, si)
                m.wb(ds, si, TYPES[rng.below(TYPES.size)])
                m.ww(o, di + 0x0C, rng.below(360) - 20)
                m.ww(o, di + 0x0E, rng.below(220) - 10)
                m.wb(o, di + 0x22, rng.bits() and 1)
                val x0 = rng.below(340) - 10
                val y0 = rng.below(210) - 5
                m.sg(0x0C40, x0)
                m.sg(0x0C42, y0)
                m.sg(0x0C44, x0 + rng.below(320))
                m.sg(0x0C46, y0 + rng.below(200))
                r.drawObject(di)
                m.wb(ds, si, t0)
                crc.update(m.m, 0xE0000, 64000)
            }
            s.a to crc.value
        }
        assertEquals(expected, got)
    }

    private companion object {
        val TYPES = intArrayOf(0x00, 0x02, 0x10, 0x12, 0x14, 0x16, 0x01)
    }
}
