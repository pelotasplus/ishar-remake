package dev.pelotasplus.ishar.engine

/**
 * Asset registration, `sound_bank_prepare` (`0000:16ce`), direct loading (`78f9`),
 * unloading (`803b`), and heap compaction (`mem_move` `7ee3`).
 */
object Assets {

    /**
     * `asset_find_loaded` (`8106`): searches the sorted loaded-asset table at
     * `es:[0bc2]` (`[0bce]` entries of 4-byte far pointers). Returns `(seg, ofs)` or `null`.
     */
    fun assetFindLoaded(machine: Machine, aid: Int): Pair<Int, Int>? {
        val es = machine.es
        var di = machine.g(0x0BC2)
        val end = machine.g(0x0BCE) * 4 + machine.g(0x0BC2)
        while (true) {
            val (ofs, seg) = machine.far(es, di)
            di += 4
            if (di > end) return null
            val v = machine.rw(seg, ofs)
            if (aid > v) continue
            return if (aid == v) (seg to ofs) else null
        }
    }

    /**
     * Places decoded asset bytes at `seg:ofs` (`[0bca]:[0bc8]`) and inserts into the asset table.
     */
    fun assetPlace(machine: Machine, seg: Int, ofs: Int, data: ByteArray) {
        val a = (seg shl 4) + ofs
        data.copyInto(machine.m, destinationOffset = a)
        val end = a + data.size
        machine.sg(0x0B14, end ushr 4)
        machine.sg(0x0B16, end and 0x0F)
        assetTableInsert(machine, seg, ofs, end and 0x0F)
    }

    /**
     * `asset_table_insert` (`8182`): inserts `ds:dx` into the sorted asset table
     * and runs `sound_bank_prepare`.
     */
    fun assetTableInsert(machine: Machine, ds: Int, dx: Int, cx: Int) {
        val es = machine.g(0x0B92)
        val top = (cx ushr 4) + 1 + machine.g(0x0B14)
        machine.sg(0x0BCA, top)
        if (top > machine.g(0x0B96)) {
            throw UnsupportedEngineOperationException("script error 0bh: out of memory")
        }
        val aid = machine.rw(ds, dx)
        var di = machine.g(0x0BC2)
        while (true) {
            val (so, sg) = machine.far(es, di)
            di += 4
            if (di > machine.g(0x0BD0)) break
            if (aid > machine.rw(sg, so)) continue
            var si = machine.g(0x0BD0)
            while (si >= di) {
                machine.ww(es, si, machine.rw(es, si - 4))
                machine.ww(es, si + 2, machine.rw(es, si - 2))
                si -= 4
            }
            break
        }
        di -= 4
        machine.ww(es, di, dx)
        machine.ww(es, di + 2, ds)
        machine.sg(0x0BD0, machine.g(0x0BD0) + 4)
        val n = machine.g(0x0BCE) + 1
        machine.sg(0x0BCE, n)
        if (Machine.s16(n) > Machine.s16(machine.g(0x0BCC))) {
            throw UnsupportedEngineOperationException("script error 4: asset table full")
        }
        soundBankPrepare(machine, ds, dx)
    }

    /**
     * `sound_bank_prepare` (`16ce`): unpacks packed samples (byte 6 == 1) through
     * the `E000` draw page using 4-bit Fibonacci deltas (shrinking record length by 2)
     * and converts kind-1 samples to unsigned 6-bit when not Sound Blaster (`[2890] != 3`).
     */
    fun soundBankPrepare(machine: Machine, aseg: Int, aofs: Int) {
        val (sseg, sofs) = Machine.norm(
            aseg,
            aofs,
            machine.rw(aseg, aofs + 0x0E),
            machine.rw(aseg, aofs + 0x10),
        )
        val n = machine.rw(sseg, sofs + 0x10)
        val (tseg, tofs) = Machine.norm(
            sseg,
            sofs,
            machine.rw(sseg, sofs + 0x0C),
            machine.rw(sseg, sofs + 0x0E),
        )
        val delta = intArrayOf(-34, -21, -13, -8, -5, -3, -2, -1, 1, 2, 3, 5, 8, 13, 21, 34)
        val page = (machine.g(0x0B5C) shl 4) + machine.g(0x0B5A)
        val m = machine.m
        for (i in 0 until n) {
            val (g, o) = machine.entry(tseg, tofs + 4 * i)
            val rec = (g shl 4) + o
            val kind = m[rec].toInt() and 0xFF
            val packedFlag = m[rec + 6].toInt() and 0xFF
            if ((kind == 1 || kind == 2) && packedFlag == 1) {
                var cx = (machine.rw(g, o + 2) - 0x10) and 0xFFFF
                cx = cx ushr 1
                val nb = (cx ushr 1) * 2
                m.copyInto(m, destinationOffset = page, startIndex = rec + 0x10, endIndex = rec + 0x10 + nb)
                var src = page
                var dst = rec + 0x10
                var dl = m[src].toInt() and 0xFF
                m[dst] = dl.toByte()
                src += 1
                dst += 1
                val steps = (cx - 1) and 0xFFFF
                repeat(steps) {
                    val v = m[src].toInt() and 0xFF
                    src += 1
                    dl = (dl + delta[v ushr 4]) and 0xFF
                    m[dst] = dl.toByte()
                    dl = (dl + delta[v and 0x0F]) and 0xFF
                    m[dst + 1] = dl.toByte()
                    dst += 2
                }
                m[dst - 1] = 0
                m[dst - 2] = 0
                machine.ww(g, o + 2, machine.rw(g, o + 2) - 2)
            }
            if (machine.gb(0x2890) != 3 && kind == 1) {
                val cx = (machine.rw(g, o + 2) - 0x10) and 0xFFFF
                val p = rec + 0x10
                for (j in 0 until cx) {
                    val v = (((m[p + j].toInt() and 0xFF) + 0x80) and 0xFF) ushr 2
                    m[p + j] = (if (v != 0) v else 1).toByte()
                }
                if (cx > 0) {
                    m[p + cx - 1] = 0
                }
            }
        }
    }

    /**
     * `asset_load_direct` (`78f9`, statement `45h`): loads `name` as asset `aid`
     * unless `aid` is already loaded.
     */
    fun loadAsset(machine: Machine, files: GameFiles, aid: Int, name: String) {
        if (aid == 0) {
            throw UnsupportedEngineOperationException("load_asset 0: new game directory and main.io")
        }
        machine.sg(0x0B04, aid)
        if (assetFindLoaded(machine, aid) != null) return
        val raw = files.read(name)
        val direct = (raw[4].toInt() and 0xFF) or ((raw[5].toInt() and 0xFF) shl 8)
        if (direct == 0) {
            throw UnsupportedEngineOperationException("load_asset of a file with a boot block: $name")
        }
        val page = (machine.g(0x0B5C) shl 4) + machine.g(0x0B5A)
        val buf = machine.m.copyOfRange(page, page + IoDecoder.REFILL_BYTES)
        val decoded = IoDecoder.decode(raw, buf)
        if ((raw[3].toInt() and 0x80) != 0) {
            buf.copyInto(machine.m, destinationOffset = page)
        }
        val seg = machine.g(0x0BCA)
        val ofs = machine.g(0x0BC8)
        val a = (seg shl 4) + ofs
        decoded.bytes.copyInto(machine.m, destinationOffset = a)
        if ((raw[3].toInt() and 0x80) != 0) {
            decoded.overrun.copyInto(machine.m, destinationOffset = a + decoded.bytes.size)
            machine.sgb(0x0C4A, 1) // LZ input buffer was the draw page
        }
        val end = a + decoded.bytes.size
        machine.sg(0x0B14, end ushr 4)
        machine.sg(0x0B16, end and 0x0F)
        val got = machine.rw(seg, ofs)
        if (got != aid && assetFindLoaded(machine, got) != null) return
        assetTableInsert(machine, seg, ofs, end and 0x0F)
    }

    /**
     * `s_unload_asset` (`803b`, statement `3dh`): unloads asset `aid`, closes its
     * heap gap with `mem_move` (`7ee3`), destroys its frames, and updates pointers.
     */
    fun unloadAsset(machine: Machine, aid: Int, onStopSound: () -> Unit = {}) {
        machine.sg(0x0B04, aid)
        if (aid == 0xFFFF || aid == machine.rw(machine.es, machine.bp - 0x10)) return
        val es = machine.g(0x0BD6)
        var di = machine.g(0x0BC2)
        val end = machine.g(0x0BCE) * 4 + di
        var slot: Int? = null
        var seg = 0
        while (true) {
            val (ofs, s) = machine.far(es, di)
            di += 4
            if (di > end || aid < machine.rw(s, ofs)) return
            if (aid == machine.rw(s, ofs)) {
                slot = di - 4
                seg = s
                break
            }
        }
        machine.sg(0x0B14, slot)
        machine.sg(0x0B18, seg)
        var cx = 0xFFFF
        var bx = machine.g(0x0BC2) - 4
        while (true) {
            bx += 4
            if (bx == machine.g(0x0BD0)) break
            val v = machine.rw(es, bx + 2)
            if (v > seg && v < cx) {
                cx = v
            }
        }
        if (cx == 0xFFFF) {
            cx = machine.g(0x0BCA)
        }
        val nxt = cx
        machine.sg(0x0B1A, nxt)
        val shift = (nxt - seg) and 0xFFFF
        machine.sg(0x0B16, shift)
        val size = (machine.g(0x0BCA) - nxt) and 0xFFFF
        onStopSound()
        machine.sgb(0x0BAB, 1)
        memMove(machine, nxt, seg, size)
        if (machine.ds >= seg) {
            machine.ds = (machine.ds - shift) and 0xFFFF
        }
    }

    /**
     * `mem_move` (`7ee3`): moves `paras` paragraphs from `src:ofs` down to `dst:ofs`
     * and fixes every segment at or above `dst`.
     */
    fun memMove(machine: Machine, src: Int, dst: Int, paras: Int, ofs: Int = 0) {
        val m = machine.m
        val nBytes = paras shl 4
        val a = (src shl 4) + ofs
        val b = (dst shl 4) + ofs
        if (nBytes > 0) {
            m.copyInto(m, destinationOffset = b, startIndex = a, endIndex = a + nBytes)
        }
        val cx = (src - dst) and 0xFFFF
        machine.sg(0x0BCA, machine.g(0x0BCA) - cx)
        val es = machine.g(0x0BD6)
        if (machine.gb(0x0BAB) != 0) {
            val di = machine.g(0x0B14)
            val base = es shl 4
            val count = (machine.g(0x0BD0) - di - 4) and 0xFFFF
            if (count > 0) {
                m.copyInto(m, destinationOffset = base + di, startIndex = base + di + 4, endIndex = base + di + 4 + count)
            }
            machine.sg(0x0BD0, machine.g(0x0BD0) - 4)
            machine.sg(0x0BCE, machine.g(0x0BCE) - 1)
        }
        val dx = dst
        var di = machine.g(0x0BC2)
        while (true) {
            di += 4
            if (machine.rw(es, di - 2) >= dx) {
                machine.ww(es, di - 2, machine.rw(es, di - 2) - cx)
            }
            if (di >= machine.g(0x0BD0)) break
        }
        val savedEs = machine.es
        machine.es = es
        val bp = machine.g(0x0B04)
        val tbl = machine.g(0x0BD2)
        var bx = 0
        while (true) {
            bx = machine.rw(es, bx + tbl + 4)
            if (bx == 0) break
            val si = machine.rw(es, bx + tbl)
            if (machine.gb(0x0BAB) != 0 && machine.rw(es, si - 0x10) == bp) {
                machine.sg(0x0B06, bx)
                val keep = machine.bp
                Objects.frameDestroy(machine)
                machine.bp = keep
                continue
            }
            if (machine.rw(es, si - 0x12) >= dx) {
                machine.ww(es, si - 0x12, machine.rw(es, si - 0x12) - cx)
                machine.ww(es, si - 6, machine.rw(es, si - 6) - cx)
            }
        }
        machine.es = savedEs
        val o = machine.g(0x0BEA)
        var v = machine.g(0x0C16)
        val base = machine.g(0x0BF6)
        while (v != 0) {
            var bObj = machine.rw(es, v + base + 2)
            while (true) {
                bObj = machine.rw(o, bObj + 6)
                if (bObj == 0) break
                if (machine.rw(o, bObj + 0x0A) >= dx) {
                    machine.ww(o, bObj + 0x0A, machine.rw(o, bObj + 0x0A) - cx)
                }
                if (machine.rw(o, bObj + 0x14) >= dx) {
                    machine.ww(o, bObj + 0x14, machine.rw(o, bObj + 0x14) - cx)
                }
            }
            v = machine.rw(es, v + base + 4)
        }
    }

    /**
     * `s_sprite_free` (`e2h`, `7d4e`): drops the pixels of sprite `entryIndex` of the
     * running asset, shrinks its header to 16×1, adjusts entry/shape/sound offsets,
     * and compacts the heap above it.
     */
    fun spriteFree(machine: Machine, entryIndex: Int) {
        val saved = intArrayOf(
            machine.ax, machine.bx, machine.cx, machine.dx,
            machine.di, machine.si, machine.ds, machine.es, machine.bp,
        )
        machine.sgb(0x0C15, 0)
        val (ds, bx) = entryPtr(machine, entryIndex)
        val t = machine.rb(ds, bx)
        if (t !in intArrayOf(0, 2, 0x10, 0x12, 0x14, 0x16) || machine.rw(ds, bx + 4) == 0) {
            return
        }
        machine.sg(0x0B14, bx)
        machine.sg(0x0B16, ds)
        var n = (((machine.rw(ds, bx + 2) + 1) ushr 1) * (machine.rw(ds, bx + 4) + 1)) and 0xFFFF
        if (t == 0x14 || t == 0x16) {
            n = (n * 2) and 0xFFFF
        }
        val free = n and 0xFFF0
        val (aofs, aseg) = machine.far(machine.es, machine.bp - 0x14)
        val (sseg, sofs) = Machine.norm(aseg, aofs, machine.rw(aseg, aofs + 0x0E), machine.rw(aseg, aofs + 0x10))
        val count = machine.rw(sseg, sofs + 4)
        val (tseg, tofs) = Machine.norm(sseg, sofs, machine.rw(sseg, sofs), machine.rw(sseg, sofs + 2))
        for (i in 0 until count) {
            val p = tofs + 4 * i
            val (g, o) = Machine.norm(tseg, p, machine.rw(tseg, p), machine.rw(tseg, p + 2))
            if (g > ds || (g == ds && o > bx)) {
                val lo = machine.rw(tseg, p) - free
                machine.ww(tseg, p, lo)
                if (lo < 0) {
                    machine.ww(tseg, p + 2, machine.rw(tseg, p + 2) - 1)
                }
            }
        }
        for ((loK, hiK) in arrayOf(6 to 4, 0x0C to 0x0E)) {
            val lo = machine.rw(sseg, sofs + loK) - free
            machine.ww(sseg, sofs + loK, lo)
            if (lo < 0) {
                machine.ww(sseg, sofs + hiK, machine.rw(sseg, sofs + hiK) - 1)
            }
        }
        machine.ww(ds, bx + 2, 0x0F)
        machine.ww(ds, bx + 4, 0)
        val src = (ds + (free ushr 4)) and 0xFFFF
        val paras = (machine.g(0x0BCA) - src) and 0xFFFF
        machine.sgb(0x0BAB, 0)
        memMove(machine, src, ds, paras, bx + 6)
        machine.ax = saved[0]; machine.bx = saved[1]; machine.cx = saved[2]; machine.dx = saved[3]
        machine.di = saved[4]; machine.si = saved[5]; machine.ds = saved[6]; machine.es = saved[7]; machine.bp = saved[8]
    }

    /**
     * `entry_slot` (`39f5`): returns `(seg, ofs)` of entry `dx`'s u32 slot in the
     * running asset (or `main.io` when `[0c15] == 1`).
     */
    fun entrySlot(machine: Machine, dx: Int): Pair<Int, Int> {
        val bp = if (machine.gb(0x0C15) != 0) machine.g(0x0BF6) else machine.bp
        val (aofs, aseg) = machine.far(machine.es, bp - 0x14)
        var bx = (aofs + machine.rw(aseg, aofs + 0x0E)) and 0xFFFF
        bx = (bx + machine.rw(aseg, bx)) and 0xFFFF
        return aseg to ((bx + 4 * dx) and 0xFFFF)
    }

    /**
     * `entry_ptr` (`3a4a`): returns the normalised far address `(seg, ofs)` of entry `dx`'s data.
     */
    fun entryPtr(machine: Machine, dx: Int): Pair<Int, Int> {
        val (seg, slot) = entrySlot(machine, dx)
        return machine.entry(seg, slot)
    }

    /**
     * `sound_record_ptr` (`60f6`): returns normalised `(seg, ofs)` of sound record `n`.
     */
    fun soundRecordPtr(machine: Machine, n: Int): Pair<Int, Int> {
        val bp = if (machine.gb(0x0C15) != 0) machine.g(0x0BF6) else machine.bp
        val (aofs, aseg) = machine.far(machine.es, bp - 0x14)
        val (sseg, sofs) = Machine.norm(aseg, aofs, machine.rw(aseg, aofs + 0x0E), machine.rw(aseg, aofs + 0x10))
        val (tseg, tofs) = Machine.norm(sseg, sofs, machine.rw(sseg, sofs + 0x0C), machine.rw(sseg, sofs + 0x0E))
        return machine.entry(tseg, (tofs + 4 * n) and 0xFFFF)
    }
}
