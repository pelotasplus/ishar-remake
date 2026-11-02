package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Machine.Companion.s16

/**
 * The view passes and the VGA driver's drawing: `view_pass` (`3e55`),
 * `chain_insert` (`4278`), `draw_dirty_rect` (`43fb`), `dirty_rect_build` (`4723`),
 * `view_clip_dirty_rect` (`4535`), `rect_merge_moved` (`45d3`); seg_0e97 (run at 1014):
 * `vdrv_draw_object` (`01a8`), the blitters (`0482..06de`), `vdrv_copy_dirty_rect` (`0148`),
 * `vdrv_screen_to_page` (`0c9b`).
 *
 * Object fields are read in the object segment `[0bea]`, view records in the frame segment `[0bd6]`.
 */
class Renderer(private val vm: Scheduler) {
    private val m = vm.machine
    val projection = Projection(m)

    /** `present_dirty_rect` (`431f`): the pointer and the copy to the screen; [Present] fills it. */
    var present: () -> Unit = { copyDirtyRect() }

    /** `pointer_restore`: the pixels under the pointer put back into the page after [screenToPage]. */
    var pointerRestore: (dofs: Int, dseg: Int) -> Unit = { _, _ -> }

    fun install() {
        vm.viewPass = ::viewPass
        vm.pointerObjectMoved = ::pointerObjectMoved
        vm.pointerObjectNew = ::pointerObjectNew
        vm.drawDirtyRect = ::drawDirtyRect
        vm.drawObject = ::drawObject
        vm.copyDirtyRect = ::copyDirtyRect
        vm.screenToPage = ::screenToPage
    }

    private val es get() = m.g(0x0BD6)
    private val o get() = m.g(0x0BEA)

    /** Driver slot 28h: the screen's first 8000 bytes (the LZ input buffer) into the page. */
    fun screenToPage() {
        val a = (m.g(0x0B86) shl 4) + m.g(0x0B84)
        val b = (m.g(0x0B8A) shl 4) + m.g(0x0B88)
        m.m.copyInto(m.m, b, a, a + 8000)
        pointerRestore(m.g(0x0B88), m.g(0x0B8A))
    }

    // --- view_pass 3e55 -----------------------------------------------------

    private fun viewApplyMotion(bx: Int) {
        val es = es
        val o = o
        for (k in intArrayOf(0x16, 0x18, 0x1A)) {
            m.ww(es, bx + k, m.rw(es, bx + k) + m.rw(es, bx + k + 0x14))
            m.ww(es, bx + k + 0x14, 0)
        }
        var di = m.g(0x0B18)
        while (true) {
            di = m.rw(o, di + 6)
            if (di == 0) break
            if (m.rb(o, di) == 0) m.wb(o, di, 2)
        }
        m.wb(es, bx, m.rb(es, bx) and 0x7F)
    }

    fun viewPass(bx: Int, cx: Int) {
        val es = es
        val o = o
        m.sgb(0x0C48, 0)
        m.sg(0x0B26, m.g(0x0BE8))
        if (m.gb(0x0C49) != 0 || (m.rb(es, bx) and 0x80) != 0) viewApplyMotion(bx)
        m.sgb(0x1DA6, if ((m.rb(es, bx + 1) and 4) != 0) 1 else 0)
        if ((m.rb(es, bx + 1) and 0x10) != 0) return viewFullRedraw(bx, cx)
        var di = cx
        while (true) {
            val pb = di
            di = m.rw(o, di + m.g(0x0B26) + 6)
            if (di == 0) return
            m.sgb(0x0C18, 0)
            val al = m.rb(o, di)
            if (al == 0) continue
            m.sgb(0x0B56, 0)
            m.sgb(0x0C48, 1)
            di = when (al) {
                2 -> objMoved(di, pb, cx)
                0xFF -> objNew(di, pb, cx)
                else -> objErase(di, pb, cx)
            }
        }
    }

    private fun storeProjection(di: Int) {
        val o = o
        m.ww(o, di + 8, m.g(0x0C5E))
        m.ww(o, di + 0x0A, m.g(0x0C60))
        m.ww(o, di + 0x0C, m.g(0x0C62))
        m.ww(o, di + 0x0E, m.g(0x0C64))
        m.ww(o, di + 0x10, m.g(0x0C66))
        m.wb(o, di + 0x22, m.gb(0x0C68))
    }

    private fun unlink(di: Int, pb: Int) {
        m.ww(o, pb + 6, m.rw(o, di + 6))
    }

    /** `409b`. */
    private fun objMoved(di: Int, pb: Int, cx: Int): Int {
        groupStart(di)
        if ((m.g(0x0B22) and 0x8000) == 0) {
            dirtyRectGrow(di)
            return groupLoop(reproject(di, pb, cx), di, pb, cx)
        }
        // 4057
        projection.objectProject(di)
        if (rectMergeMoved(di)) {
            unlink(di, pb) // 401c
        } else {
            unlink(di, pb) // 4061
            drawDirtyRect(di, cx)
        }
        storeProjection(di) // 4022
        val w = chainInsert(di, pb, cx)
        drawDirtyRect(di, cx)
        return w
    }

    /** `4084`. */
    private fun objNew(di: Int, pb: Int, cx: Int): Int {
        groupStart(di)
        val w = reproject(di, pb, cx)
        if ((m.g(0x0B22) and 0x8000) != 0) {
            m.sgb(0x0C18, 1)
            drawDirtyRect(di, cx)
            return pb
        }
        return groupLoop(w, di, pb, cx)
    }

    /** `40b4`. */
    private fun objErase(di: Int, pb: Int, cx: Int): Int {
        groupStart(di)
        val w = objectEraseFree(di, pb)
        if ((m.g(0x0B22) and 0x8000) != 0) {
            m.sgb(0x0C18, 1)
            drawDirtyRect(di, cx)
            return pb
        }
        return groupLoop(w, di, pb, cx)
    }

    /** `4132`: the rest of the chain's objects of group `[0b22]`. */
    private fun groupLoop(w: Int, di0: Int, pb0: Int, cx: Int): Int {
        val o = o
        var di = w
        while (true) {
            val bx = di
            di = m.rw(o, di + 6)
            if (di == 0) break
            if (m.g(0x0B22) != m.rw(o, di + 0x1E)) continue
            val al = m.rb(o, di)
            if (al == 0) continue
            di = when {
                (al and 0x80) != 0 -> reproject(di, bx, cx)
                al == 2 -> {
                    dirtyRectGrow(di)
                    reproject(di, bx, cx)
                }
                else -> objectEraseFree(di, bx)
            }
        }
        m.sgb(0x0C18, 1)
        drawDirtyRect(di0, cx)
        return pb0
    }

    /** `40d9` -> the walk position (chain_insert's result). */
    private fun reproject(di: Int, pb: Int, cx: Int): Int {
        projection.objectProject(di)
        unlink(di, pb)
        storeProjection(di)
        dirtyRectGrow(di)
        return chainInsert(di, pb, cx)
    }

    private fun objectEraseFree(di: Int, pb: Int): Int {
        dirtyRectGrow(di)
        unlink(di, pb)
        m.ww(o, di + 4, m.g(0x0BEC))
        m.sg(0x0BEC, di)
        return pb
    }

    private fun groupStart(di: Int) {
        m.sg(0x0B22, m.rw(o, di + 0x1E))
        m.sg(0x0C2C, 0x7FFF)
        m.sg(0x0C2E, 0x7FFF)
        m.sg(0x0C30, 0x8000)
        m.sg(0x0C32, 0x8000)
    }

    private fun dirtyRectGrow(di: Int) {
        val o = o
        if ((m.rw(o, di + 0x10) and 0x8000) != 0) return
        val (es, si) = m.entry(m.rw(o, di + 0x0A), m.rw(o, di + 8))
        var ax = m.rw(o, di + 0x0C)
        if (s16(ax) <= m.gs(0x0C2C)) m.sg(0x0C2C, ax)
        ax = (ax + m.rw(es, si + 2)) and 0xFFFF
        if (s16(ax) >= m.gs(0x0C30)) m.sg(0x0C30, ax)
        ax = m.rw(o, di + 0x0E)
        if (s16(ax) <= m.gs(0x0C2E)) m.sg(0x0C2E, ax)
        ax = (ax + m.rw(es, si + 4)) and 0xFFFF
        if (s16(ax) >= m.gs(0x0C32)) m.sg(0x0C32, ax)
    }

    /**
     * `4278`: insert [di] into the chain from record [cx], sorted by depth (desc), sort key
     * `+20`, index `+1`; -> the walk position.
     */
    /**
     * EXPERIMENT (Ishar 3): fville.io puts its sky and ground, opaque panels (sprite type 16h), in the walls' view at
     * depth 0, the nearest; drawn last they would hide the street. They go behind. Other depth-0 sprites (the logo's
     * dragon) keep their order. The original's sort (5d41) and 2D transform (4d5b) are the same as here: why it
     * draws them behind is not found yet.
     */
    private fun depthKey(di: Int): Int {
        val d = m.rsw(o, di + 0x10)
        if (m.build != GameBuild.ISHAR3 || d != 0) return d
        val (seg, ofs) = m.entry(m.rw(o, di + 0x0A), m.rw(o, di + 8))
        return if (m.rb(seg, ofs) == 0x16) 0x7FFF else d
    }

    private fun chainInsert(di: Int, si: Int, cx: Int): Int {
        val o = o
        val ax = depthKey(di)
        val dl = m.rsb(o, di + 0x20)
        val dh = m.rsb(o, di + 1)
        val new = m.rb(o, di) == 0xFF
        var bx = cx
        var bp: Int
        while (true) {
            bp = bx
            bx = m.rw(o, bx + 6)
            if (bx == 0) break
            if ((m.rb(o, bx) and 0x80) != 0) continue
            val d = depthKey(bx)
            if (ax < d) continue
            if (ax > d) break
            val k = m.rsb(o, bx + 0x20)
            if (dl < k) continue
            if (dl > k) break
            val j = m.rsb(o, bx + 1)
            if (dh < j) continue
            if (dh > j) break
            if (!new) continue
            break
        }
        m.ww(o, di + 6, bx)
        m.ww(o, bp + 6, di)
        m.wb(o, di, 0)
        return if (si == bp) di else si
    }

    /** `3eea`: view flag 10h: project the whole chain, redraw the view. */
    private fun viewFullRedraw(bx: Int, cx: Int) {
        val es = es
        val o = o
        var ax = m.rw(es, bx + 0x0E)
        var dx = m.rw(es, bx + 0x10)
        m.sg(0x0C40, ax); m.sg(0x0C42, dx)
        m.sg(0x0C1A, ax); m.sg(0x0C1C, dx)
        ax = (ax + m.rw(es, bx + 0x12)) and 0xFFFF
        dx = (dx + m.rw(es, bx + 0x14)) and 0xFFFF
        m.sg(0x0C44, ax); m.sg(0x0C46, dx)
        m.sg(0x0C1E, ax); m.sg(0x0C20, dx)
        m.sg(0x0C22, ((ax - m.g(0x0C1A) + 1) and 0xFFFF) ushr 3)
        var di = cx
        while (true) {
            val pb = di
            di = m.rw(o, di + 6)
            if (di == 0) break
            val al = m.rb(o, di)
            if (al == 0) continue
            if (al == 1) {
                unlink(di, pb)
                m.ww(o, di + 4, m.g(0x0BEC))
                m.sg(0x0BEC, di)
                di = pb
                continue
            }
            projection.objectProject(di)
            unlink(di, pb)
            storeProjection(di)
            di = chainInsert(di, pb, cx)
        }
        drawViews()
        present()
    }

    /** Every visible view whose record meets the rectangle: its chain. */
    private fun drawViews() {
        val es = es
        val o = o
        var cx = m.g(0x0C16)
        while (cx != 0) {
            val bx = (m.g(0x0BF6) + cx) and 0xFFFF
            val nxt = m.rw(es, bx + 4)
            if ((m.rb(es, bx) and 0x40) == 0) {
                var di = m.rw(es, bx + 2)
                if (di != 0) {
                    viewClipDirtyRect(di)
                    if (m.gb(0x0C3E) != 0) {
                        while (true) {
                            di = m.rw(o, di + 6)
                            if (di == 0) break
                            if ((m.rb(o, di) and 0x80) != 0 || (m.rb(o, di + 0x22) and 0x80) != 0 ||
                                (m.rw(o, di + 0x10) and 0x8000) != 0
                            ) continue
                            drawObject(di)
                        }
                    }
                }
            }
            cx = nxt
        }
    }

    // --- dirty rectangles ---------------------------------------------------

    /** `45d3`: true (ZF clear) when the old and new boxes overlap; the union goes to `0c2c..0c32`. */
    private fun rectMergeMoved(di: Int): Boolean {
        val o = o
        val (os, oo) = m.entry(m.rw(o, di + 0x0A), m.rw(o, di + 8))
        m.sg(0x0B42, oo); m.sg(0x0B44, os)
        val (ns, no) = m.entry(m.g(0x0C60), m.g(0x0C5E))
        m.sg(0x0B3E, no); m.sg(0x0B40, ns)
        if ((m.rw(o, di + 0x10) and 0x8000) != 0) {
            m.sgb(0x0C18, 0)
            return false
        }
        var ax = m.rsw(o, di + 0x0C)
        val nw = m.rw(ns, no + 2)
        val nh = m.rw(ns, no + 4)
        val ow = m.rw(os, oo + 2)
        val oh = m.rw(os, oo + 4)
        val c62 = m.gs(0x0C62)
        val c64 = m.gs(0x0C64)
        var dx = m.rsw(o, di + 0x0E)
        if (ax > s16(c62 + nw) || dx > s16(c64 + nh)) {
            m.sgb(0x0C18, 0)
            return false
        }
        ax = s16(ax + ow)
        if (ax < c62) {
            m.sgb(0x0C18, 0)
            return false
        }
        dx = s16(dx + oh)
        if (dx < c64) {
            m.sgb(0x0C18, 0)
            return false
        }
        m.sg(0x0C2C, minOf(m.rsw(o, di + 0x0C), c62))
        m.sg(0x0C2E, minOf(m.rsw(o, di + 0x0E), c64))
        m.sg(0x0C30, maxOf(ax, s16(c62 + nw)))
        m.sg(0x0C32, maxOf(dx, s16(c64 + nh)))
        m.sgb(0x0C18, 1)
        return true
    }

    /** `4723` -> true (ZF) when the rectangle meets the view record [cx]; widened to 16-pixel columns. */
    private fun dirtyRectBuild(di: Int, cx: Int): Boolean {
        val o = o
        if (m.gb(0x0C18) == 0) {
            if ((m.rw(o, di + 0x10) and 0x8000) != 0) return false
            val (es, si) = m.entry(m.rw(o, di + 0x0A), m.rw(o, di + 8))
            val ax = m.rw(o, di + 0x0C)
            val bx = m.rw(o, di + 0x0E)
            m.sg(0x0C2C, ax); m.sg(0x0C2E, bx)
            m.sg(0x0C30, ax + m.rw(es, si + 2))
            m.sg(0x0C32, bx + m.rw(es, si + 4))
        }
        fun r(k: Int) = m.rsw(o, cx + k)
        val c2c = m.gs(0x0C2C)
        val c2e = m.gs(0x0C2E)
        val c30 = m.gs(0x0C30)
        val c32 = m.gs(0x0C32)
        if (c2c > r(0x16) || c2e > r(0x18) || c30 < r(0x0C) || c32 < r(0x0E)) return false
        val x0 = maxOf(s16(c2c and 0xFFF0), r(0x0C))
        val y0 = maxOf(c2e, r(0x0E))
        val x1 = minOf(s16(c30 or 0x0F), r(0x16))
        val y1 = minOf(c32, r(0x18))
        m.sg(0x0C1A, x0); m.sg(0x0C1C, y0); m.sg(0x0C1E, x1); m.sg(0x0C20, y1)
        m.sg(0x0C22, ((x1 - x0 + 1) and 0xFFFF) ushr 3)
        m.sg(0x0C24, 0)
        m.sg(0x0C26, m.g(0x1DFB))
        return true
    }

    private fun viewClipDirtyRect(di: Int) {
        val o = o
        m.sgb(0x0C3E, 0)
        val x0 = m.rw(o, di + 0x0C); m.sg(0x0B12, x0)
        if (s16(x0) > m.gs(0x0C1E)) return
        val y0 = m.rw(o, di + 0x0E); m.sg(0x0B14, y0)
        if (s16(y0) > m.gs(0x0C20)) return
        val x1 = m.rw(o, di + 0x16); m.sg(0x0B16, x1)
        if (s16(x1) < m.gs(0x0C1A)) return
        val y1 = m.rw(o, di + 0x18); m.sg(0x0B18, y1)
        if (s16(y1) < m.gs(0x0C1C)) return
        m.sg(0x0C40, maxOf(s16(x0), m.gs(0x0C1A)))
        m.sg(0x0C42, maxOf(s16(y0), m.gs(0x0C1C)))
        m.sg(0x0C44, minOf(s16(x1), m.gs(0x0C1E)))
        m.sg(0x0C46, minOf(s16(y1), m.gs(0x0C20)))
        m.sgb(0x0C3E, 1)
    }

    /** `43fb`. */
    fun drawDirtyRect(di: Int, cx: Int) {
        val vs = m.g(0x0B34)
        val vb = m.g(0x0B32)
        if ((m.rb(vs, vb) and 0x40) != 0) return
        m.sgb(0x0C3F, 0)
        if (!dirtyRectBuild(di, cx)) return
        drawViews()
        if (m.gb(0x0CB0) != 0) {
            val p = m.g(0x0CAE)
            if ((m.rb(o, p) and 0x80) == 0) {
                m.sg(0x0C40, m.g(0x0C1A))
                m.sg(0x0C42, m.g(0x0C1C))
                m.sg(0x0C44, m.g(0x0C1E))
                drawObject(p)
            }
        }
        if ((m.rb(vs, vb + 1) and 0x20) == 0) present()
    }

    // --- the pointer object in frame_wait -----------------------------------

    fun pointerObjectNew(di: Int, cx: Int) {
        val o = o
        m.wb(o, di + 0x22, m.rb(o, di + 0x23))
        for ((a, b) in POINTER_COPY) m.ww(o, di + a, m.rw(o, di + b))
        m.wb(o, di, 0)
        drawDirtyRect(di, cx)
    }

    fun pointerObjectMoved(di: Int, cx: Int) {
        val o = o
        m.sg(0x0C5E, m.rw(o, di + 0x12) and 0xFF00)
        m.sg(0x0C60, m.rw(o, di + 0x14))
        m.sg(0x0C62, m.rw(o, di + 0x16))
        m.sg(0x0C64, m.rw(o, di + 0x18))
        m.sg(0x0C66, 0)
        if (!rectMergeMoved(di)) {
            m.wb(o, di, 0xFF)
            drawDirtyRect(di, cx)
        }
        pointerObjectNew(di, cx)
    }

    // --- the VGA driver -----------------------------------------------------

    /** `0148`: the rectangle from the page `[0b88]` to the screen `[0b84]`, in whole words; SI/DI wrap at 64 KB. */
    fun copyDirtyRect() {
        val so = m.g(0x0B88)
        val ss = m.g(0x0B8A)
        val dof = m.g(0x0B84)
        val ds = m.g(0x0B86)
        val stride = m.g(0x1DD5)
        val off = (m.g(0x0C1C) * stride + m.g(0x0C1A)) and 0xFFFF
        val n = (((m.g(0x0C1E) - m.g(0x0C1A) + 1) and 0xFFFF) ushr 1) * 2
        val rows = (m.g(0x0C20) - m.g(0x0C1C) + 1) and 0xFFFF
        var a = (so + off) and 0xFFFF
        var b = (dof + off) and 0xFFFF
        repeat(rows) {
            copy(ss, a, ds, b, n)
            a = (a + stride) and 0xFFFF
            b = (b + stride) and 0xFFFF
        }
    }

    /** [n] bytes, both offsets wrapping at 64 KB like SI/DI. */
    private fun copy(sseg: Int, sofs: Int, dseg: Int, dofs: Int, n: Int) {
        val sa = sseg shl 4
        val da = dseg shl 4
        if (sofs + n <= 0x10000 && dofs + n <= 0x10000) {
            m.m.copyInto(m.m, da + dofs, sa + sofs, sa + sofs + n)
        } else {
            val tmp = ByteArray(n) { m.m[sa + ((sofs + it) and 0xFFFF)] }
            for (i in 0 until n) m.m[da + ((dofs + i) and 0xFFFF)] = tmp[i]
        }
    }

    /** `vdrv_draw_object` `01a8`, clipped to `0c40..0c46`. */
    fun drawObject(di: Int) {
        val o = o
        val (ds, si) = m.entry(m.rw(o, di + 0x0A), m.rw(o, di + 8))
        val ax = m.rsw(o, di + 0x0C)
        if (ax > m.gs(0x0C44)) return
        val bx = m.rsw(o, di + 0x0E)
        if (bx > m.gs(0x0C46)) return
        val cx = s16(ax + m.rw(ds, si + 2))
        if (cx < m.gs(0x0C40)) return
        val dx = s16(bx + m.rw(ds, si + 4))
        if (dx < m.gs(0x0C42)) return
        val x0 = maxOf(ax, m.gs(0x0C40))
        val y0 = maxOf(bx, m.gs(0x0C42))
        val x1 = minOf(cx, m.gs(0x0C44))
        val y1 = minOf(dx, m.gs(0x0C46))
        m.sg(0x0C2C, x0); m.sg(0x0C2E, y0); m.sg(0x0C30, x1); m.sg(0x0C32, y1)
        val t = m.rb(ds, si)
        if (t == 0x7F) throw UnsupportedEngineOperationException("draw type 7fh")
        val mirror = (m.rb(o, di + 0x22) and 1) != 0
        when (t) {
            0x14, 0x16, 0x01 -> blit8(ds, si, t, mirror, ax, bx, x0, y0, x1, y1)
            0x00, 0x02, 0x10, 0x12 -> blit4(ds, si, t, mirror, ax, bx, x0, y0, x1, y1)
        }
    }

    /** Types 14h (0 transparent), 16h (opaque), 01h (solid box). */
    private fun blit8(ds: Int, si: Int, t: Int, mirror: Boolean, ox: Int, oy: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val w = m.rw(ds, si + 2) + 1
        val pseg = m.g(0x1DC1)
        val pofs = m.g(0x1DBF)
        val stride = m.g(0x1DD5)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        val pa = pseg shl 4
        val sa = ds shl 4
        var d = (pofs + y0 * stride + x0) and 0xFFFF
        if (t == 0x01) {
            val c = m.m[sa + ((si + 1) and 0xFFFF)]
            repeat(ch) {
                for (i in 0 until cw) m.m[pa + ((d + i) and 0xFFFF)] = c
                d = (d + stride) and 0xFFFF
            }
            return
        }
        // blit_setup_8bpp 02dd: the source row offset, then the start column
        val sy = if (y0 > oy) y0 - oy else 0
        val sx = if (x0 > ox) x0 - ox else 0
        var src = (sy * w) and 0xFFFF
        src = (if (mirror) src - sx else src + sx) and 0xFFFF
        if (mirror) src = (src + w - cw) and 0xFFFF // the row is written leftwards from x1
        src = (si + 8 + src) and 0xFFFF
        repeat(ch) {
            for (i in 0 until cw) {
                val v = m.m[sa + ((src + (if (mirror) cw - 1 - i else i)) and 0xFFFF)]
                if (t == 0x16 || v.toInt() != 0) m.m[pa + ((d + i) and 0xFFFF)] = v
            }
            src = (src + w) and 0xFFFF
            d = (d + stride) and 0xFFFF
        }
    }

    /**
     * Types 00/02 (base 0, 6-byte header) and 10/12 (base byte 6, 8-byte header); 00/10 skip
     * nibble 0. The high nibble comes first. `blit_setup_4bpp` `038b` and the loops
     * `0568/05c0/063a/0686`: the clip start can fall on a low nibble (`cs:0031`).
     */
    private fun blit4(ds: Int, si: Int, t: Int, mirror: Boolean, ox: Int, oy: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val w1 = m.rw(ds, si + 2)
        val pseg = m.g(0x1DC1)
        val pofs = m.g(0x1DBF)
        val stride = m.g(0x1DD5)
        val cw = x1 - x0 + 1
        val ch = y1 - y0 + 1
        val mem = m.m
        val sb = ds shl 4
        val pb = pseg shl 4
        var skipSrc = ((w1 - x1 + x0) and 0xFFFF) ushr 1 // cs:002c
        var lowFirst = false // cs:0031
        var dx = 0
        if (y0 - oy > 0) dx = (((y0 - oy) * (w1 + 1)) and 0xFFFF) ushr 1
        var ax = x0 - ox
        if (ax > 0) {
            if ((ax and 1) != 0) {
                lowFirst = true
                if ((cw and 1) == 0) skipSrc -= 1
            }
            ax = ax shr 1
            if (mirror) {
                ax = -ax
                lowFirst = false
            }
            dx += ax
        }
        if (mirror) {
            val bx = w1 + ox - x1
            if (bx > 0 && (bx and 1) != 0) {
                lowFirst = true
                if ((cw and 1) == 0) dx -= 1
            }
            dx += ((w1 + 1 - cw) and 0xFFFF) ushr 1
        }
        val colour = if (t == 0x10 || t == 0x12) m.rb(ds, si + 6) else 0
        val hdr = if (t == 0x10 || t == 0x12) 8 else 6
        val transparent = t == 0x00 || t == 0x10
        var src = (si + hdr + dx) and 0xFFFF
        var d = (pofs + y0 * stride + x0 + (if (mirror) cw else 0)) and 0xFFFF
        var v = 0
        repeat(ch) {
            var n = cw
            var hi = !lowFirst
            while (true) {
                val p: Int
                if (hi) {
                    v = mem[sb + src].toInt() and 0xFF
                    src = (src + 1) and 0xFFFF
                    p = v ushr 4
                } else {
                    if (lowFirst && n == cw) {
                        v = mem[sb + src].toInt() and 0xFF
                        src = (src + 1) and 0xFFFF
                    }
                    p = v and 0x0F
                }
                if (mirror) {
                    d = (d - 1) and 0xFFFF
                    if (p != 0 || !transparent) mem[pb + d] = (p + colour).toByte()
                } else {
                    if (p != 0 || !transparent) mem[pb + d] = (p + colour).toByte()
                    d = (d + 1) and 0xFFFF
                }
                n--
                if (n == 0) break
                hi = !hi
            }
            src = (src + skipSrc) and 0xFFFF
            d = (d + (if (mirror) stride + cw else stride - cw)) and 0xFFFF
        }
    }

    private companion object {
        val POINTER_COPY = listOf(8 to 0x12, 0x0A to 0x14, 0x0C to 0x16, 0x0E to 0x18)
    }
}
