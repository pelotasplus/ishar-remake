package dev.pelotasplus.ishar.engine


/**
 * Palette statements and fades: 68h load, beh fade
 * to, bch fade to black, d7h hold, e6h save, 69h (an Atari leftover), `palette_load_record`
 * (`59a8`), the driver's `fade_tick` (`0e97:0ea8`), `fade_step` (`0ec9`) and `palette_upload` (`0bdb`).
 *
 * Buffers in SS: `1443` target, `0e43` wanted (what fade_tick uploads), `1143` the DAC shadow,
 * `1763`/`1a63` save slots. Records: feh, count - 1, first index, -, then 8-bit RGB triples.
 */
class Palette(private val vm: Scheduler) {
    private val m = vm.machine
    private val x get() = vm.expr

    fun install() {
        val t = vm.stmts
        t[0x68] = ::load; t[0x69] = ::setEntry16; t[0xBC] = ::fadeToBlack
        t[0xBE] = ::fadeTo; t[0xD7] = ::hold; t[0xE6] = ::save
        vm.fadeTick = ::fadeTick
    }

    private fun copy(src: Int, dst: Int) {
        val a = m.ss shl 4
        m.m.copyInto(m.m, a + dst, a + src, a + src + 0x300)
    }

    private fun fill0(dst: Int) {
        val a = m.ss shl 4
        m.m.fill(0, a + dst, a + dst + 0x300)
    }

    private fun clearFade(vararg ks: Int) {
        for (k in ks) m.sgb(k, 0)
    }

    private fun fadeParams(cx: Int, inc: Int) {
        m.sgb(0x0CBE, 1)
        if (cx == 0) return
        if (cx < 0x3F) {
            m.sgb(0x0CBE, 0x3F / (cx and 0xFF) + 1)
            m.sgb(0x0CBB, cx)
            m.sgb(0x0CBC, 1)
            m.sgb(0x0CBD, 1)
        } else {
            val q = cx / 0x3F
            if (q > 0xFF) throw UnsupportedEngineOperationException("divide overflow in a fade length")
            m.sgb(0x0CBB, 0x3F)
            m.sgb(0x0CBC, q + inc)
            m.sgb(0x0CBD, q + inc)
        }
    }

    /** `59a8` (VGA path `59f2`). `bx = ffffh`: the target is already set. */
    private fun loadRecord(seg: Int, bx: Int, cx: Int, dxIn: Int = cx) {
        var dx = dxIn
        m.sgb(0x0CB9, 1)
        fadeParams(cx, 1)
        if (bx != 0xFFFF) {
            dx = cx
            val n = m.rb(seg, bx + 1) + 1
            val first = m.rb(seg, bx + 2)
            var si = bx + 4
            var di = 0x1443 + 3 * first
            if (n == 1) {
                // 5b1a: an Atari ST record, 16 words 0RGB of 3-bit levels, always faded (5af3)
                si -= 2
                repeat(16) {
                    val lo = m.rb(seg, si)
                    val hi = m.rb(seg, si + 1)
                    si += 2
                    for (v in intArrayOf((lo shl 3) and 0xFF, (hi and 0xF0) ushr 1, ((hi and 0x0F) shl 3) and 0xFF)) {
                        m.sgb(di++, if (v != 0) v or 7 else 0)
                    }
                }
                m.sgb(0x0CB9, 1)
                fadeStep()
                m.sgb(0x0CB8, 1)
                m.sgb(0x0CB9, 0)
                m.sgb(0x0BF4, 0)
                return
            }
            for (i in 0 until 3 * n) m.sgb(di + i, m.rb(seg, si + i) ushr 2)
        }
        if (vm.cpuSpeed() <= 200) {
            dx = 0
            clearFade(0x0CBD, 0x0CBC, 0x0CBB)
        }
        if (dx == 0) {
            clearFade(0x0CBD, 0x0CBB, 0x0CBC)
            copy(0x1443, 0x0E43)
            m.sgb(0x0CB8, 0xFF)
            m.sgb(0x0CB9, 0)
            upload()
        } else {
            m.sgb(0x0CB9, 1)
            fadeStep()
            m.sgb(0x0CB8, 1)
            m.sgb(0x0CB9, 0)
        }
        m.sgb(0x0BF4, 0)
    }

    private fun restoreSlot(dx: Int, cx: Int) {
        copy(if (dx == 0xFFFF) 0x1763 else 0x1A63, 0x1443)
        loadRecord(0, 0xFFFF, cx, dx)
    }

    /** 68h. */
    private fun load() {
        val es = m.es
        val bx = m.bx
        m.sgb(0x0C15, 0)
        x.step()
        val d = m.dx
        if ((d and 0x8000) == 0) {
            if (m.gb(0x2896) != 0 && m.gb(0x0BF4) == 0) {
                val (seg, ofs) = Assets.entryPtr(m, d)
                if (m.rb(seg, ofs) == 0xFE) {
                    clearFade(0x0CBD, 0x0CBC, 0x0CBB)
                    loadRecord(seg, ofs, 0)
                    m.sgb(0x0CB8, 1)
                }
            }
        } else if (m.gb(0x2898) != 0 && d >= 0xFFFE) {
            clearFade(0x0CBD, 0x0CBC, 0x0CBB)
            restoreSlot(d, 0)
        }
        m.sgb(0x0BF4, 0)
        m.es = es
        m.bx = bx
    }

    /** beh. */
    private fun fadeTo() {
        val es = m.es
        val bx = m.bx
        m.sgb(0x0C15, 0)
        x.step()
        x.evalCx()
        if (m.gb(0x2896) != 0 && m.gb(0x0BF4) == 0) {
            val d = m.dx
            if ((d and 0x8000) != 0) {
                if (d >= 0xFFFE) throw UnsupportedEngineOperationException("beh with a save slot: jumps without popping (crash)")
            } else {
                val (seg, ofs) = Assets.entryPtr(m, d)
                if (m.rb(seg, ofs) == 0xFE) loadRecord(seg, ofs, m.cx)
            }
        }
        m.sgb(0x0BF4, 0)
        m.es = es
        m.bx = bx
    }

    /** bch. */
    private fun fadeToBlack() {
        x.evalCx()
        val cx = m.cx
        fadeParams(cx, 0)
        var dx = cx
        fill0(0x1443)
        if (vm.cpuSpeed() <= 200) dx = 0
        if (dx == 0) {
            clearFade(0x0CBC, 0x0CBD, 0x0CBB)
            fill0(0x0E43)
        }
        m.sgb(0x0CB9, 1)
        fadeStep()
        m.sgb(0x0CB8, 1)
        m.sgb(0x0CB9, 0)
        m.sgb(0x0BF4, 0)
    }

    /** d7h. */
    private fun hold() {
        x.step()
        m.sgb(0x0BF4, m.dx)
        m.sgb(0x0BF5, 1)
    }

    /** e6h. */
    private fun save() {
        x.step()
        val d = m.dx
        if ((d and 0x8000) != 0 && d >= 0xFFFE) copy(0x1443, if (d == 0xFFFF) 0x1763 else 0x1A63)
    }

    /** 69h. */
    private fun setEntry16() {
        x.step()
        x.evalCx()
        if (m.gb(0x2897) == 1) {
            m.sg(0x0E43 + 2 * m.dx, ((m.cx and 0xFF) shl 8) or ((m.cx ushr 8) and 0xFF))
            upload()
        }
    }

    // --- the driver ---------------------------------------------------------

    /** `0e97:0ea8`, on the tick while `[0cb8]` is set. */
    fun fadeTick() {
        if (m.gb(0x0CB9) == 1) return
        upload()
        val c = (m.gb(0x0CBD) - 1) and 0xFF
        m.sgb(0x0CBD, c)
        if ((c and 0x80) != 0) {
            m.sgb(0x0CBD, m.gb(0x0CBC))
            m.sgb(0x0CB8, 0)
            fadeStep()
        }
    }

    /** `0e97:0bdb`: the wanted palette `0e43` to the DAC; `1143` shadows it. */
    private fun upload() = copy(0x0E43, 0x1143)

    /** `0e97:0ec9`: each component of `0e43` moves by up to `[0cbe]` towards `1443`. */
    private fun fadeStep() {
        val a = m.ss shl 4
        val mem = m.m
        val step = m.gb(0x0CBE)
        m.sgb(0x0CB8, 0)
        for (i in 0 until 0x300) {
            val t = mem[a + 0x1443 + i].toInt()
            val w = mem[a + 0x0E43 + i].toInt()
            if (t == w) continue
            m.sgb(0x0CB8, 1)
            var v: Int
            if (t < w) {
                v = (w - step).toByte().toInt()
                if (v <= t) v = t
            } else {
                v = (w + step).toByte().toInt()
                if (v >= t) v = t
            }
            mem[a + 0x0E43 + i] = v.toByte()
        }
    }

    companion object {
        /** The 256 colours on screen as packed ARGB: the DAC shadow `1143`, 6-bit `(v shl 2) or (v shr 4)`. */
        fun dacArgb(m: Machine): IntArray {
            val a = (m.ss shl 4) + 0x1143
            return IntArray(256) { i ->
                var c = 0xFF
                for (k in 0 until 3) {
                    val v = m.m[a + 3 * i + k].toInt() and 0x3F
                    c = (c shl 8) or ((v shl 2) or (v ushr 4))
                }
                c
            }
        }

        /** 320x200 packed ARGB of the page at [seg] (`A000` screen or `E000` page) through the DAC. */
        fun frameArgb(m: Machine, seg: Int = m.screen): IntArray {
            val dac = dacArgb(m)
            val base = seg shl 4
            return IntArray(64000) { dac[m.m[base + it].toInt() and 0xFF] }
        }
    }
}
