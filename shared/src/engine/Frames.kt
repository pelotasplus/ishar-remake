package dev.pelotasplus.ishar.engine

/** The script frames (the table at `[0bd2]` in segment `[0bd6]`): each frame's `bp`, its asset, where it resumes. */
object Frames {
    /** The first frame of [asset], as its `bp` in segment `[0bd6]`, or null. */
    fun of(m: Machine, asset: Int): Int? {
        val es = m.g(0x0BD6)
        val tbl = m.g(0x0BD2)
        var bx = 0
        repeat(64) {
            val bp = m.rw(es, tbl + bx)
            if (m.rb(es, bp - 0x10) == asset) return bp
            bx = m.rw(es, tbl + bx + 4)
            if (bx == 0) return null
        }
        return null
    }

    /** Where [asset]'s frame resumes, relative to the asset, or null. */
    fun resume(m: Machine, asset: Int): Int? = of(m, asset)?.let { bp ->
        val es = m.g(0x0BD6)
        (m.rw(es, bp - 8) - m.rw(es, bp - 0x14)) and 0xFFFF
    }

    /** The words waiting in [asset]'s event queue, oldest first, as `event_pop` (ScriptExpr.eventPop) reads them. */
    fun queued(m: Machine, asset: Int): List<Int> {
        val bp = of(m, asset) ?: return emptyList()
        val es = m.g(0x0BD6)
        val (aofs, aseg) = m.far(es, bp - 0x14)
        val qsize = m.rw(aseg, aofs + 0x16)
        val end = m.rw(es, bp - 0x1C)
        var di = m.rw(es, bp - 0x1E)
        val words = mutableListOf<Int>()
        while (di != end && words.size < 0x100) {
            words += m.rw(es, (bp + di) and 0xFFFF)
            di = (di + 2) and 0xFFFF
            if (di.toShort() >= -0x34) di = (di - qsize) and 0xFFFF
        }
        return words
    }

    /** [words] into [asset]'s event queue, as `post_event` does (ScriptStmts.postEvent); false when it takes none. */
    fun post(m: Machine, asset: Int, words: List<Int>): Boolean {
        val di = of(m, asset) ?: return false
        val es = m.g(0x0BD6)
        if ((m.rb(es, di - 0x24) and 1) != 0) return false
        val (aofs, aseg) = m.far(es, di - 0x14)
        val qsize = m.rw(aseg, aofs + 0x16)
        var bx = m.rw(es, di - 0x1C)
        for (w in words) {
            val at = bx
            bx = (bx + 2) and 0xFFFF
            if (bx.toShort() >= -0x34) bx = (bx - qsize) and 0xFFFF
            if (bx == m.rw(es, di - 0x1E)) return false
            m.ww(es, (at + di) and 0xFFFF, w)
        }
        m.ww(es, di - 0x1C, bx)
        m.wb(es, di - 0x24, m.rb(es, di - 0x24) or 0x80)
        return true
    }

    /** Make [asset]'s frame resume at [ofs] in its asset. */
    fun resumeAt(m: Machine, asset: Int, ofs: Int) {
        val bp = of(m, asset) ?: return
        val es = m.g(0x0BD6)
        m.ww(es, bp - 8, (m.rw(es, bp - 0x14) + ofs) and 0xFFFF)
    }

    /** The linear address of byte [ofs] of [asset], through its frame, or null. */
    fun assetByte(m: Machine, asset: Int, ofs: Int): Int? = of(m, asset)?.let { bp ->
        val es = m.g(0x0BD6)
        val (o, seg) = m.far(es, bp - 0x14)
        seg * 16 + o + ofs
    }
}
