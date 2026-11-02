package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Machine.Companion.s16
import dev.pelotasplus.ishar.engine.ScriptStmts.Companion.RESULTS

/**
 * Collision and distance searches: `collide_search` (`4bdd`,
 * statements 53h..56h, 8eh, 8fh, a3h..a5h), `frames_by_mask` 93h (`4fd0`), the distance
 * searches 59h..5eh, cbh, cch (`5600`, the sort `51e0`), the shape centre `48cc` (a2h, 3ch).
 *
 * Shapes (asset section +06, a u32 to a table of word offsets): word 0 = kind (byte 0:
 * 0 byte box, 1 word box, bit 7 compound with byte 1 parts), word 1 = class bits, then
 * corner x/y/z and size x/y/z (bytes or words), x negated when the frame is mirrored.
 * Hits go to `ss:1e7e` (ffffh ends), the hit's class word 100h above it.
 */
class Collide(private val vm: Scheduler, private val stmts: ScriptStmts) {
    private val m = vm.machine
    private val SS = m.ss
    private val x = vm.expr

    fun install() {
        val t = vm.stmts
        t[0x53] = { collideRel(box = false) }; t[0x54] = { collideAt(box = false) }
        t[0x55] = { collideRel(box = true) }; t[0x56] = { collideAt(box = true) }
        t[0x8E] = { collideStep(shape = false) }; t[0x8F] = { collideStep(shape = true) }
        t[0xA3] = { collideAtShape(box = false) }; t[0xA4] = { collideAtShape(box = true) }
        t[0xA5] = ::collideHere; t[0xA2] = ::moveByShape; t[0x3C] = ::frameNewAtBox
        t[0x93] = ::framesByMask
        t[0x59] = { nearFrame(false) }; t[0x5C] = { nearFrame(true) }
        t[0x5A] = { nearAsset(false) }; t[0x5D] = { nearAsset(true) }
        t[0x5B] = { nearHitbox(false) }; t[0x5E] = { nearHitbox(true) }
        t[0xCB] = { framesFind(false) }; t[0xCC] = { framesFind(true) }
    }

    /** The running (or another) frame's shape table, normalised: `(seg, ofs)`. */
    private fun shapeTable(es: Int, bp: Int): Pair<Int, Int> {
        val (aofs, aseg) = m.far(es, bp - 0x14)
        val si = (aofs + m.rw(aseg, aofs + 0x0E)) and 0xFFFF
        return Machine.norm(aseg, si, m.rw(aseg, si + 6), m.rw(aseg, si + 8))
    }

    private fun shapeAt(tseg: Int, tofs: Int, n: Int): Pair<Int, Int> =
        tseg to ((tofs + m.rw(tseg, tofs + 2 * n)) and 0xFFFF)

    private class Box(val lo: IntArray, val hi: IntArray)

    /**
     * `(min, max)` per axis as the code orders them (`4e0d` / `4f6e`: the first corner
     * is swapped with the second when it is not smaller).
     */
    private fun readBox(seg: Int, si: Int, kind: Int, mirror: Boolean, base: IntArray): Box {
        val v = IntArray(6) { if (kind == 0) m.rsb(seg, si + it) else m.rsw(seg, si + 2 * it) }
        val a = IntArray(3) { v[it] }
        if (mirror) a[0] = -a[0]
        for (i in 0 until 3) a[i] = (a[i] + base[i]) and 0xFFFF
        val b = IntArray(3) { v[3 + it] }
        if (mirror) b[0] = -b[0]
        for (i in 0 until 3) b[i] = (b[i] + a[i]) and 0xFFFF
        val lo = IntArray(3)
        val hi = IntArray(3)
        for (i in 0 until 3) {
            val p = s16(a[i])
            val q = s16(b[i])
            if (q > p) {
                lo[i] = p; hi[i] = q
            } else {
                lo[i] = q; hi[i] = p
            }
        }
        return Box(lo, hi)
    }

    // --- collide_search 4bdd ------------------------------------------------

    private fun collideSearch() {
        val es = m.es
        val bp = m.bp
        m.sg(0x0C80, RESULTS)
        val own = m.g(0x0C72)
        if ((own and 0x8000) == 0) {
            val (tseg, tofs) = shapeTable(es, bp)
            m.sg(0x0C82, tofs); m.sg(0x0C84, tseg)
            val (sseg, sofs) = shapeAt(tseg, tofs, own)
            m.sg(0x0B3E, sofs); m.sg(0x0B40, sseg)
            val tbl = m.g(0x0BD2)
            var bx = 0
            while (true) {
                val si = m.rw(es, bx + tbl)
                val other = m.rw(es, si - 0x1A)
                if (m.rw(es, si + 6) == m.rw(es, bp + 6) && (other and 0x8000) == 0 && si != bp) {
                    m.sg(0x0B2A, si); m.sg(0x0B2C, es)
                    val (oseg, oofs) = shapeTable(es, si)
                    m.sg(0x0C86, oofs); m.sg(0x0C88, oseg)
                    if (ownTest(sseg, sofs, tseg, tofs, oseg, oofs, other)) {
                        val p = m.g(0x0C80)
                        m.ww(SS, p + 0x100, m.g(0x0C7E))
                        m.ww(SS, p, bx)
                        m.sg(0x0C80, p + 2)
                        if (m.gb(0x0C96) == 0) break
                    }
                }
                bx = m.rw(es, bx + tbl + 4)
                if (bx == 0) break
            }
        }
        val p = m.g(0x0C80)
        m.ww(SS, p, 0xFFFF)
        m.sg(0x0C80, p + 2)
    }

    private fun ownTest(sseg: Int, sofs: Int, tseg: Int, tofs: Int, oseg: Int, oofs: Int, other: Int): Boolean {
        val kind = m.rw(sseg, sofs)
        if ((kind and 0x80) != 0) { // shape_test_compound 4d26
            var p = sofs + 2
            repeat(kind ushr 8) {
                val part = m.rw(sseg, p)
                p += 2
                if ((part and 0x8000) == 0) {
                    val (ps, po) = shapeAt(tseg, tofs, part)
                    if (ownTest(ps, po, tseg, tofs, oseg, oofs, other)) return true
                }
            }
            return false
        }
        val k = kind and 0xFF
        if (k != 0 && k != 1) return false
        if (k == 0) m.sg(0x0B20, kind)
        val base = intArrayOf(m.g(0x0C6A), m.g(0x0C6C), m.g(0x0C6E))
        val box = readBox(sseg, sofs + 4, k, m.gb(0x0C70) != 0, base)
        for (i in 0 until 3) {
            m.sg(0x0C8A + 2 * i, box.lo[i])
            m.sg(0x0C90 + 2 * i, box.hi[i])
        }
        val (os, oo) = shapeAt(oseg, oofs, other)
        return otherTest(os, oo, oseg, oofs)
    }

    private fun otherTest(seg: Int, si: Int, oseg: Int, oofs: Int): Boolean {
        val kind = m.rw(seg, si)
        if ((kind and 0x80) != 0) { // 4e6d
            var p = si + 2
            repeat(kind ushr 8) {
                val part = m.rw(seg, p)
                p += 2
                if ((part and 0x8000) == 0) {
                    val (ps, po) = shapeAt(oseg, oofs, part)
                    if (otherTest(ps, po, oseg, oofs)) return true
                }
            }
            return false
        }
        val k = kind and 0xFF
        if (k != 0 && k != 1) return false
        val cls = m.rw(seg, si + 2)
        val hit = cls and m.g(0x0C74)
        if (hit == 0) return false
        m.sg(0x0B20, hit)
        m.sg(0x0C7E, cls)
        val es = m.g(0x0B2C)
        val di = m.g(0x0B2A)
        val base = intArrayOf(m.rw(es, di), m.rw(es, di + 2), m.rw(es, di + 4))
        val box = readBox(seg, si + 4, k, m.rb(es, di - 3) != 0, base)
        for (i in intArrayOf(1, 2, 0)) { // 4f6e: y, then z, then x
            if (box.hi[i] < s16(m.g(0x0C8A + 2 * i)) || box.lo[i] > s16(m.g(0x0C90 + 2 * i))) return false
        }
        return true
    }

    private fun searchCommon(p: IntArray, mask: Int, shape: Int) {
        m.sgb(0x0C70, m.rb(m.es, m.bp - 3))
        m.sg(0x0C6A, p[0]); m.sg(0x0C6C, p[1]); m.sg(0x0C6E, p[2])
        m.sg(0x0C74, mask)
        m.sg(0x0C72, shape)
        collideSearch()
        stmts.resultFirst()
    }

    private fun ownPos(): IntArray = IntArray(3) { m.rw(m.es, m.bp + 2 * it) }

    private fun stepPos(): IntArray =
        IntArray(3) { (m.rw(m.es, m.bp + 2 * it) + m.rsb(m.es, m.bp + 9 + it)) and 0xFFFF }

    private fun three(rel: Boolean): IntArray {
        val p = ownPos()
        return IntArray(3) {
            x.step()
            if (rel) (m.dx + p[it]) and 0xFFFF else m.dx
        }
    }

    /** The mask, then (with [box]) the shape; otherwise the frame's own shape `BP-1ah`. */
    private fun maskAndShape(box: Boolean, p: IntArray) {
        x.step()
        val mask = m.dx
        val shape = if (box) {
            x.step()
            m.dx
        } else {
            m.rw(m.es, m.bp - 0x1A)
        }
        searchCommon(p, mask, shape)
    }

    private fun collideRel(box: Boolean) {
        m.sgb(0x0C70, m.rb(m.es, m.bp - 3))
        maskAndShape(box, three(rel = true))
    }

    private fun collideAt(box: Boolean) {
        m.sgb(0x0C70, m.rb(m.es, m.bp - 3))
        maskAndShape(box, three(rel = false))
    }

    private fun collideStep(shape: Boolean) = maskAndShape(shape, stepPos())

    private fun collideHere() = maskAndShape(true, ownPos())

    private fun collideAtShape(box: Boolean) {
        m.sgb(0x0C70, m.rb(m.es, m.bp - 3))
        val c = shapeCentre()
        val p = ownPos()
        maskAndShape(box, IntArray(3) { (c[it] + p[it]) and 0xFFFF })
    }

    // --- The shape centre 48cc ----------------------------------------------

    /**
     * Statement operand E (a shape of the running asset) -> its centre relative to the
     * frame (x negated when mirrored and version >= 15h).
     */
    private fun shapeCentre(): IntArray {
        x.step()
        val es = m.es
        val bp = m.bp
        val (tseg, tofs) = shapeTable(es, bp)
        val (seg, si) = shapeAt(tseg, tofs, m.dx)
        val k = m.rb(seg, si)
        if ((k and 0x80) != 0) throw UnsupportedEngineOperationException("script error 14h: the centre of a compound shape")
        val c = when (k) {
            0 -> {
                val v = IntArray(6) { m.rsb(seg, si + 4 + it) }
                IntArray(3) { v[it] + (v[it + 3] shr 1) }
            }
            1 -> {
                val v = IntArray(6) { m.rsw(seg, si + 4 + 2 * it) }
                IntArray(3) { v[it] + (v[it + 3] shr 1) }
            }
            else -> IntArray(3) { m.rsw(seg, si + 2 + 2 * it) }
        }
        if (m.rb(es, bp - 0x2E) >= 0x15 && m.rb(es, bp - 3) != 0) c[0] = -c[0]
        return IntArray(3) { c[it] and 0xFFFF }
    }

    private fun moveByShape() {
        val c = shapeCentre()
        for (i in 0 until 3) m.ww(m.es, m.bp + 2 * i, m.rw(m.es, m.bp + 2 * i) + c[i])
    }

    private fun frameNewAtBox() {
        val c = shapeCentre()
        m.sg(0x0C6A, c[0]); m.sg(0x0C6C, c[1]); m.sg(0x0C6E, c[2])
        stmts.frameNewCommon()
    }

    // --- frames_by_mask 93h (4fd0) ------------------------------------------

    /**
     * `4fd0`: the other frame's shape has a class bit of `[0c74]`. Parts of a compound
     * shape are looked up and then always walked as compounds again (`test ax,ax / jns`
     * tests the word, not the kind byte), so a simple part with byte 1 = 0 never hits.
     */
    private fun classHit(di: Int): Boolean {
        val es = m.es
        val bx = m.rw(es, di - 0x1A)
        if ((bx and 0x8000) != 0 || di == m.bp) return false
        val (tseg, tofs) = shapeTable(es, di)
        val (seg, p) = shapeAt(tseg, tofs, bx)
        val kind = m.rw(seg, p)
        if ((kind and 0x80) == 0) return classWord(seg, p + 2)
        return classParts(seg, p + 2, kind ushr 8, tseg, tofs)
    }

    private fun classWord(seg: Int, p: Int): Boolean {
        val ax = m.rw(seg, p)
        m.sg(0x0C7E, ax)
        return (ax and m.g(0x0C74)) != 0
    }

    private fun classParts(seg: Int, start: Int, n: Int, tseg: Int, tofs: Int): Boolean {
        var p = start
        repeat(n) {
            val part = m.rw(seg, p)
            p += 2
            if ((part and 0x8000) == 0) {
                val (ps, pp) = shapeAt(tseg, tofs, part)
                val ax = m.rw(ps, pp)
                if ((ax and 0x8000) == 0) {
                    if (classParts(ps, pp + 2, ax ushr 8, tseg, tofs)) return true
                } else if (classWord(ps, pp + 2)) {
                    return true
                }
            }
        }
        return false
    }

    private fun framesByMask() {
        x.step()
        m.sgb(0x0C74, m.dx)
        val es = m.es
        val tbl = m.g(0x0BD2)
        var cx = RESULTS
        var bx = 0
        while (true) {
            val di = m.rw(es, bx + tbl)
            if (classHit(di)) {
                m.sg(0x0C7E, m.rw(SS, cx + 0x100))
                m.ww(SS, cx, bx)
                cx += 2
                if (m.gb(0x0C96) == 0) break
            }
            bx = m.rw(es, bx + tbl + 4)
            if (bx == 0) break
        }
        m.ww(SS, cx, 0xFFFF)
        m.sgb(0x0C96, 0)
        stmts.resultFirst()
    }

    // --- Distance searches (5600, 51e0) -------------------------------------

    private fun radius() {
        x.step()
        val r = m.dx.toLong() * m.dx
        m.sg(0x0C78, (r and 0xFFFF).toInt())
        m.sg(0x0C7A, (r ushr 16).toInt())
    }

    /** `5600`: distance squared within `[0c78]`; with `[0c76]` also ahead: within velocity . delta times `[0c7c]`. */
    private fun nearTest(di: Int): Boolean {
        val es = m.es
        val bp = m.bp
        val d = IntArray(3) { s16(m.rw(es, di + 2 * it) - m.rw(es, bp + 2 * it)) }
        m.sg(0x0B14, d[0]); m.sg(0x0B16, d[1]); m.sg(0x0B18, d[2])
        val d2 = d.fold(0L) { acc, v -> acc + v.toLong() * v } and 0xFFFFFFFFL
        m.sg(0x0B2A, (d2 and 0xFFFF).toInt()); m.sg(0x0B2C, (d2 ushr 16).toInt())
        if (cmp32(d2, (m.g(0x0C7A).toLong() shl 16) or m.g(0x0C78).toLong()) > 0) return false
        if (m.gb(0x0C76) == 0) return true
        var dot = 0L
        for (i in 0 until 3) dot += m.rsb(es, bp + 9 + i).toLong() * d[i]
        dot = dot and 0xFFFFFFFFL
        m.sg(0x0B1A, (dot and 0xFFFF).toInt()); m.sg(0x0B1C, (dot ushr 16).toInt())
        val k = m.g(0x0C7C)
        val lo = (dot and 0xFFFF) * k
        val hi = (lo ushr 16) + ((s16((dot ushr 16).toInt()).toLong() * s16(k)) and 0xFFFF)
        val lim = ((hi and 0xFFFF) shl 16) or (lo and 0xFFFF)
        m.sg(0x0B1E, (lim and 0xFFFF).toInt()); m.sg(0x0B20, (lim ushr 16).toInt())
        return cmp32(d2, lim) <= 0
    }

    private fun nearCollect(want: (Int) -> Boolean) {
        val es = m.es
        val tbl = m.g(0x0BD2)
        var hits = mutableListOf<Pair<Long, Int>>() // (distance, slot)
        val all = m.gb(0x0C96) != 0
        var best: Pair<Long, Int>? = null
        var bx = 0
        while (true) {
            val di = m.rw(es, bx + tbl)
            if (want(di) && nearTest(di)) {
                val d = (m.g(0x0B2C).toLong() shl 16) or m.g(0x0B2A).toLong()
                if (all) {
                    hits.add(d to bx)
                } else if (best == null || cmp32(d, best.first) < 0) {
                    best = d to bx
                }
            }
            bx = m.rw(es, bx + tbl + 4)
            if (bx == 0) break
        }
        if (all) {
            hits.sortWith { a, b -> cmp32(a.first, b.first) }
        } else {
            // one entry: the nearest, written over the sentinel ffffh:7fffh
            m.sg(0x2480, 0xFFFF)
            m.sg(0x2482, 0x7FFF)
            m.sg(RESULTS, 0xFFFF)
            hits = best?.let { mutableListOf(it) } ?: mutableListOf()
        }
        var p = RESULTS
        hits.forEachIndexed { i, (d, slot) ->
            m.sg(0x2480 + 4 * i, (d and 0xFFFF).toInt())
            m.sg(0x2480 + 4 * i + 2, (d ushr 16).toInt())
            m.ww(SS, p, slot)
            p += 2
        }
        if (!all && hits.isEmpty()) p += 2
        m.ww(SS, p, 0xFFFF)
        m.sgb(0x0C96, 0)
        stmts.resultFirst()
    }

    private fun sameZone(di: Int): Boolean = m.rw(m.es, di + 6) == m.rw(m.es, m.bp + 6)

    private fun nearSetup(ahead: Boolean) {
        m.sgb(0x0C76, if (ahead) 1 else 0)
        radius()
        if (ahead) {
            x.step()
            m.sg(0x0C7C, m.dx)
        }
    }

    private fun nearHitbox(ahead: Boolean) {
        nearSetup(ahead)
        x.step()
        m.sgb(0x0C74, m.dx)
        nearCollect { sameZone(it) && classHit(it) }
    }

    private fun nearAsset(ahead: Boolean) {
        nearSetup(ahead)
        val aid = m.lodsw()
        m.sg(0x0B22, aid)
        nearCollect { m.rw(m.es, it - 0x10) == aid && sameZone(it) && it != m.bp }
    }

    private fun framesFind(ahead: Boolean) {
        nearSetup(ahead)
        val k = m.lodsw() and 0xFF
        nearCollect { m.rb(m.es, it + 0x0C) == k && sameZone(it) && it != m.bp }
    }

    private fun nearFrame(ahead: Boolean) {
        nearSetup(ahead)
        x.step()
        val es = m.es
        val bx = m.dx
        val di = m.rw(es, m.g(0x0BD2) + bx)
        var p = RESULTS
        if (sameZone(di) && nearTest(di)) {
            m.ww(SS, p, bx)
            p += 2
        }
        m.ww(SS, p, 0xFFFF)
        m.sgb(0x0C96, 0)
        stmts.resultFirst()
    }

    companion object {
        /** Signed high words, then unsigned low words, like the code. */
        fun cmp32(a: Long, b: Long): Int {
            val ah = s16((a ushr 16).toInt())
            val bh = s16((b ushr 16).toInt())
            if (ah != bh) return if (ah > bh) 1 else -1
            val al = (a and 0xFFFF).toInt()
            val bl = (b and 0xFFFF).toInt()
            return al.compareTo(bl).coerceIn(-1, 1)
        }
    }
}
