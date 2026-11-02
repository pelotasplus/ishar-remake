package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Machine.Companion.div8
import dev.pelotasplus.ishar.engine.Machine.Companion.idiv32
import dev.pelotasplus.ishar.engine.Machine.Companion.s16

/**
 * `object_project` (`36e1`) and `view_rotate` (`3955`): an object's
 * position relative to its view, rotated (3D views) or sheared (2D), divided by z,
 * shifted and offset to the screen; the depth index picks the sprite slot.
 *
 * Results: `[0c5e]:[0c60]` the sprite slot, `[0c62]`/`[0c64]` x/y, `[0c66]` z, `[0c68]` mirror.
 */
class Projection(private val m: Machine) {

    /** Projects object record [di] (object segment `[0bea]`) through its view (frame segment `[0bd6]`). */
    fun objectProject(di: Int) {
        val es = m.g(0x0BD6)
        val o = m.g(0x0BEA)
        val bx = (m.g(0x0BF6) + m.rw(o, di + 2)) and 0xFFFF
        m.sgb(0x0C68, m.rb(o, di + 0x23))
        var ax = (m.rw(o, di + 0x16) - m.rw(es, bx + 0x16)) and 0xFFFF
        var cx = (m.rw(o, di + 0x18) - m.rw(es, bx + 0x18)) and 0xFFFF
        var dx = (m.rw(o, di + 0x1A) - m.rw(es, bx + 0x1A)) and 0xFFFF
        m.sg(0x0B3A, ax); m.sg(0x0B1A, ax)
        m.sg(0x0B3E, cx); m.sg(0x0B1C, cx)
        m.sg(0x0B1E, dx)
        viewRotate(es, bx, ax, cx, dx).let { (a, c, d) -> ax = a; cx = c; dx = d }
        m.sg(0x0B1A, ax); m.sg(0x0B1C, cx); m.sg(0x0B1E, dx)
        m.sg(0x0B18, 0)
        val sh = m.rsb(es, bx + 0x1D)
        m.sg(0x0B12, sh)
        if (sh >= 0 && (m.rb(o, di + 0x1C) and 0x80) == 0) {
            val x = m.gs(0x0B1A).toLong() shl sh
            val y = m.gs(0x0B1C).toLong() shl sh
            m.sg(0x0B3A, x.toInt()); m.sg(0x0B3C, (x shr 16).toInt())
            m.sg(0x0B3E, y.toInt()); m.sg(0x0B40, (y shr 16).toInt())
            if (m.g(0x0B1E) == 0) m.sg(0x0B1E, 1)
            val z = m.g(0x0B1E)
            // `idiv word ptr ss:[0b1e]`: the INT 0 retry shifts arithmetically
            m.sg(0x0B1C, idiv32(y, z, true).first)
            m.sg(0x0B1A, idiv32(x, z, true).first)
            var d = (m.gs(0x0B1E) shr (m.rb(o, di + 0x1C) and 0x1F)) and 0xFFFF
            d = (d + m.rsb(o, di + 0x1D)) and 0xFFFF
            m.sg(0x0B18, d)
            if ((d and 0x8000) != 0) {
                m.sg(0x0B18, 0)
            } else {
                val cap = m.rsb(es, bx + 0x1E) and 0xFFFF
                m.sg(0x0B20, cap)
                if (d > cap) m.sg(0x0B18, cap)
                m.sg(0x0B18, m.g(0x0B18) shl 2)
            }
        }
        val cl = m.rb(es, bx + 0x1C) and 0x1F
        m.sg(0x0B1A, (m.gs(0x0B1A) shr cl) + m.rw(es, bx + 0x0A))
        m.sg(0x0B1C, (m.gs(0x0B1C) shr cl) + m.rw(es, bx + 0x0C))

        // the sprite: placed slot + 4 x depth index
        val seg = m.rw(o, di + 0x14)
        m.sg(0x0B12, seg)
        var slot = (m.rw(o, di + 0x12) + m.g(0x0B18)) and 0xFFFF
        var (sseg, sofs) = m.entry(seg, slot)
        if (m.rb(sseg, sofs) == 3) { // a reference entry: offset + another entry
            val ox = m.rw(sseg, sofs + 4)
            m.sg(0x0B1A, if (m.rb(o, di + 0x23) != 0) m.g(0x0B1A) - ox else m.g(0x0B1A) + ox)
            m.sg(0x0B1C, m.g(0x0B1C) + m.rw(sseg, sofs + 6))
            val n = m.rw(sseg, sofs + 2)
            if (m.rb(sseg, sofs + 1) != 0) m.sgb(0x0C68, m.gb(0x0C68) xor 1)
            slot = (slot + 4 * n) and 0xFFFF
            m.entry(seg, slot).let { (s, f) -> sseg = s; sofs = f }
        }
        var snap = m.rb(o, di + 0x24)
        if (snap != 0) {
            if ((m.rb(o, di + 0x25) and 0x80) == 0) {
                slot = m.g(0x0B18) ushr 2 // AX is the depth from here on, divided or not
                val depth = slot and 0xFF
                val a25 = m.rb(o, di + 0x25)
                if (depth > a25) {
                    val dl = (m.g(0x0B20) - a25) and 0xFF
                    if (dl != 0 && (dl and 0x80) == 0) {
                        val (q, r) = div8(((depth - a25) and 0xFF) * snap, dl, false) // `div dl`
                        slot = (r shl 8) or q
                        snap = r
                    }
                }
            }
            snap = snap and 0x1F
            m.sg(0x0B1A, ((m.g(0x0B1A) ushr snap) shl snap))
        }
        m.sg(0x0C5E, slot)
        m.sg(0x0C60, m.g(0x0B12))
        m.sg(0x0B1A, m.g(0x0B1A) - (m.rw(sseg, sofs + 2) ushr 1))
        m.sg(0x0B1C, m.g(0x0B1C) - (m.rw(sseg, sofs + 4) ushr 1))
        m.sg(0x0C62, m.g(0x0B1A))
        m.sg(0x0C64, m.g(0x0B1C))
        m.sg(0x0C66, m.g(0x0B1E))
    }

    /** `3955`: the 3D view's signed 3x3 matrix at `+20h`; `3943`: 2D, y sheared by z. */
    private fun viewRotate(es: Int, bx: Int, ax: Int, cx: Int, dx: Int): Triple<Int, Int, Int> {
        if ((m.rb(es, bx + 1) and 0x80) == 0) {
            return Triple(ax, (m.rsb(es, bx + 0x24) * s16(cx) - s16(dx)) and 0xFFFF, cx)
        }
        m.sg(0x0B14, ax); m.sg(0x0B1A, ax); m.sg(0x0B16, cx); m.sg(0x0B18, dx)
        val r = IntArray(9) { m.rsb(es, bx + 0x20 + it) }
        val x = s16(ax)
        val y = s16(cx)
        val z = s16(dx)
        val a = (r[0] * x + r[1] * y + r[2] * z) and 0xFFFF
        val c = (r[3] * x + r[4] * y + r[5] * z) and 0xFFFF
        val d = (r[6] * x + r[7] * y + r[8] * z) and 0xFFFF
        m.sg(0x0B1A, a)
        m.sg(0x0B12, d)
        return Triple(a, c, d)
    }
}
