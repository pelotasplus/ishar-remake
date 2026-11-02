package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Machine.Companion.CS
import dev.pelotasplus.ishar.engine.Machine.Companion.s16

/**
 * The dirty rectangle onto the screen and the mouse pointer:
 * `present_dirty_rect` (`431f`), the driver's `pointer_show` (slot 20h, `0e97:0f2a`) and
 * `pointer_hide` (slot 1ch, `105d`), and `pointer_tick` (`670d`, on the 57.6 Hz tick).
 *
 * The pointer's sprite is `[0cb4]:[0cb6]`, its position `[0ca6]`/`[0ca8]`, the pixels under it
 * are saved at `[0caa]:[0cac]` (offset, width, height, then the bytes).
 */
class Present(private val vm: Scheduler, private val renderer: Renderer) {
    private val m = vm.machine

    fun install() {
        renderer.present = ::presentDirtyRect
        renderer.pointerRestore = ::pointerHide
        vm.pointerShow = ::pointerShow
        vm.pointerHide = ::pointerHide
        vm.pointerTick = ::pointerTick
    }

    /** `431f`: the pointer drawn into the page over the rectangle, the copy, the page restored. */
    fun presentDirtyRect() {
        if ((m.gb(0x0CB1) and 0x80) != 0) return renderer.copyDirtyRect()
        if (m.gb(0x2896) == 0) throw UnsupportedEngineOperationException("pointer through the mouse driver (2896 = 0)")
        val over = pointerOverRect()
        m.sgb(0x0CA5, if (over) 1 else 0)
        val pofs = m.g(0x0B88)
        val pseg = m.g(0x0B8A)
        if (over) {
            if (m.gb(0x2898) != 1) throw UnsupportedEngineOperationException("pointer_off")
            pointerShow(pofs, pseg)
        }
        renderer.copyDirtyRect()
        if (over) pointerHide(pofs, pseg)
    }

    private fun pointerOverRect(): Boolean {
        val ax = m.gs(0x0CA6)
        val bx = m.gs(0x0CA8)
        if (ax > m.gs(0x0C1E) || bx > m.gs(0x0C20)) return false
        return !(ax + 15 < m.gs(0x0C1A) || bx + 15 < m.gs(0x0C1C))
    }

    /** Slot 20h: save what is under the pointer into `[0caa]`, then draw the sprite at `[0cb4]`. */
    fun pointerShow(dofs: Int, dseg: Int) {
        val x = m.g(0x0CA6)
        val y = m.g(0x0CA8)
        val stride = m.g(0x1DD5)
        val h = (if (s16(y) < 0xB8) 0x10 else 0xC8 - y) and 0xFFFF
        val w = (if (s16(x) < 0x130) 0x10 else 0x140 - x) and 0xFFFF
        val bofs = m.g(0x0CAA)
        val bseg = m.g(0x0CAC)
        val si = (dofs + y * stride + x) and 0xFFFF
        m.ww(bseg, bofs, si)
        m.ww(bseg, bofs + 2, w)
        m.ww(bseg, bofs + 4, h)
        var p = bofs + 6
        for (r in 0 until h) {
            for (c in 0 until w) m.wb(bseg, p++, m.rb(dseg, si + r * stride + c))
        }
        if (vm.touch) return
        // pointer_draw
        val sofs = m.g(0x0CB4)
        val sseg = m.g(0x0CB6)
        val t = m.rb(sseg, sofs)
        var di = si
        val skip = 0x10 - w
        if (t != 0 && t != 0x10) {
            var s = sofs + 8
            for (r in 0 until h) {
                for (c in 0 until w) {
                    val v = m.rb(sseg, s++)
                    if (v != 0) m.wb(dseg, di + c, v)
                }
                s += skip
                di += stride
            }
            return
        }
        val base = if (t == 0x10) m.rb(sseg, sofs + 6) else 0
        var s = sofs + if (t == 0x10) 8 else 6
        for (r in 0 until h) {
            var c = 0
            var n = w and 0xFF
            while (true) {
                val v = m.rb(sseg, s++)
                if ((v ushr 4) != 0) m.wb(dseg, di + c, (v ushr 4) + base)
                c++
                if (--n == 0) break
                if ((v and 0x0F) != 0) m.wb(dseg, di + c, (v and 0x0F) + base)
                c++
                if (--n == 0) break
            }
            s += skip shr 1
            di += stride
        }
    }

    /** Slot 1ch: the saved pixels back to [dseg]. */
    fun pointerHide(dofs: Int, dseg: Int) {
        val bofs = m.g(0x0CAA)
        val bseg = m.g(0x0CAC)
        val di = m.rw(bseg, bofs)
        val w = m.rw(bseg, bofs + 2)
        val h = m.rw(bseg, bofs + 4)
        val stride = m.g(0x1DD5)
        var p = bofs + 6
        for (r in 0 until h) {
            for (c in 0 until w) m.wb(dseg, di + r * stride + c, m.rb(bseg, p++))
        }
    }

    /** `670d`: when the mouse moved, the pointer moves on the screen. */
    fun pointerTick() {
        if (m.gb(0x2896) == 0 || (m.gb(0x0CB1) and 0x80) != 0) return
        val x = m.rw(CS, m.build.mouse)
        val y = m.rw(CS, m.build.mouse + 2)
        if (x == m.g(0x0CA6) && y == m.g(0x0CA8)) return
        val sofs = m.g(0x0B84)
        val sseg = m.g(0x0B86)
        pointerHide(sofs, sseg)
        m.sg(0x0CA6, x)
        m.sg(0x0CA8, y)
        pointerShow(sofs, sseg)
    }
}
