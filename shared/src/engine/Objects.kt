package dev.pelotasplus.ishar.engine

/**
 * Script frames (`frame_create` `8268`, `frame_destroy` `837d`), object placement
 * (`place_entry` `341b`, `object_find` `35cf`, `object_alloc` `3626`, `object_mark_erase` `3afa`),
 * and view management (`2ded`).
 */
object Objects {

    /**
     * `frame_slot_free` (`8224`): takes a slot from the free chain `[0bde]`, links
     * it after the running slot `[0b02]`, and calls [frameCreate].
     */
    fun frameSlotTake(machine: Machine, aseg: Int, aofs: Int): Int {
        val es = machine.es
        val tbl = machine.g(0x0BD2)
        val cur = machine.g(0x0B02)
        val cx = machine.rw(es, cur + tbl + 4)
        val bx = machine.g(0x0BDE)
        machine.ww(es, cur + tbl + 4, bx)
        machine.sg(0x0BDE, machine.rw(es, bx + tbl + 4))
        machine.ww(es, bx + tbl + 4, cx)
        machine.sg(0x0BDC, machine.g(0x0BDC) + 1)
        if (Machine.s16(machine.g(0x0BDC)) > Machine.s16(machine.g(0x0BDA))) {
            throw UnsupportedEngineOperationException("script error 5: no frame slot")
        }
        machine.sg(0x0B06, bx)
        return frameCreate(machine, aseg, aofs)
    }

    /**
     * `frame_create` (`8268`): creates a frame for the asset at `aseg:aofs` in slot `[0b06]`.
     * Leaves `BP-23h`, `BP-27h`, `BP-29h`, and `BP-2bh` untouched as in the original game.
     */
    fun frameCreate(machine: Machine, aseg: Int, aofs: Int): Int {
        val es = machine.es
        val tbl = machine.g(0x0BD2)
        var ax = (machine.g(0x0BD8) + machine.rw(aseg, aofs + 0x12)) and 0xFFFF
        machine.sg(0x0B0C, ax)
        ax = (ax + machine.rw(aseg, aofs + 0x16) + 0x34) and 0xFFFF
        machine.ww(es, tbl + machine.g(0x0B06), ax)
        machine.sg(0x0B0E, ax)
        var bx = machine.rw(aseg, aofs + 0x14)
        ax = (ax + bx) and 0xFFFF
        machine.sg(0x0BD8, ax)
        if ((ax ushr 4) + 1 + machine.g(0x0BD6) >= machine.g(0x0BE2)) {
            throw UnsupportedEngineOperationException("script error 0ch: frame memory full")
        }
        val di = machine.g(0x0B0E)
        while (bx != 0) {
            machine.ww(es, bx + di - 2, 0)
            bx -= 2
        }
        val b = (machine.g(0x0B0C) - di) and 0xFFFF
        machine.ww(es, di - 0x0A, b)
        machine.ww(es, di - 0x1C, b)
        machine.ww(es, di - 0x1E, b)
        machine.ww(es, di - 8, aofs + 2 + machine.rw(aseg, aofs + 2))
        machine.ww(es, di - 6, aseg)
        machine.ww(es, di - 0x10, machine.rw(aseg, aofs))
        machine.ww(es, di - 0x14, 0)
        machine.ww(es, di - 0x12, aseg)
        machine.wb(es, di - 0x2E, machine.rb(aseg, aofs + 5))
        machine.wb(es, di - 2, 1)
        machine.wb(es, di - 1, 1)
        machine.wb(es, di - 4, 0xFF)
        machine.ww(es, di - 0x1A, 0xFFFF)
        machine.ww(es, di - 0x0E, machine.g(0x0B06))
        machine.wb(es, di - 0x24, 2)
        for (o in intArrayOf(0x18, 0x0C, 0x22, 0x20, 0x32, 0x34)) {
            machine.ww(es, di - o, 0)
        }
        for (o in intArrayOf(3, 0x25, 0x2A, 0x28, 0x2C, 0x2D, 0x2F, 0x30)) {
            machine.wb(es, di - o, 0)
        }
        machine.wb(es, di - 0x26, 0xFF)
        machine.bx = di
        return di
    }

    /**
     * `frame_destroy` (`837d`): frees the objects of frame `[0b06]`, compacts frame memory,
     * and returns the slot to `[0bde]`. Throws [RescheduleException] if destroying the running frame.
     */
    fun frameDestroy(machine: Machine) {
        val bx = machine.g(0x0B06)
        val es = machine.es
        val di = machine.g(0x0BD2)
        if ((bx and 0x8000) != 0 || machine.rw(es, bx + di) == 0) {
            machine.sgb(0x0BF3, 0)
            return
        }
        val savedAx = machine.ax
        val savedBx = machine.bx
        val savedCx = machine.cx
        val savedDx = machine.dx
        val savedDi = machine.di
        val savedSi = machine.si
        val savedDs = machine.ds
        val savedEs = machine.es
        val bp = machine.rw(es, bx + di)
        machine.bp = bp
        deleteAllObjects(machine)
        val (aofs, aseg) = machine.far(es, bp - 0x14)
        val cx = (machine.rw(aseg, aofs + 0x12) + machine.rw(aseg, aofs + 0x16) + 0x34) and 0xFFFF
        val lo = (bp - cx) and 0xFFFF
        val size = (cx + machine.rw(aseg, aofs + 0x14)) and 0xFFFF
        val src = (lo + size) and 0xFFFF
        val end = machine.g(0x0BD8)
        val base = es shl 4
        val nWords = ((end - src) and 0xFFFF) ushr 1
        if (nWords > 0) {
            machine.m.copyInto(
                machine.m,
                destinationOffset = base + lo,
                startIndex = base + src,
                endIndex = base + src + 2 * nWords,
            )
        }
        machine.sg(0x0BD8, end - size)
        val tbl = machine.g(0x0BD2)
        val target = machine.g(0x0B06)
        var prev = 0
        var b = 0
        while (true) {
            val ax = machine.rw(es, b + tbl + 4)
            if (ax == target) {
                prev = b
            }
            b = ax
            if (b == 0) break
            if (bp > machine.rw(es, b + tbl)) continue
            machine.ww(es, b + tbl, machine.rw(es, b + tbl) - size)
        }
        b = target
        machine.ww(es, prev + tbl + 4, machine.rw(es, b + tbl + 4))
        machine.ww(es, b + tbl, 0)
        machine.ww(es, b + tbl + 4, machine.g(0x0BDE))
        machine.sg(0x0BDE, b)
        machine.sg(0x0BDC, machine.g(0x0BDC) - 1)
        if (machine.g(0x0B02) == b) {
            machine.sg(0x0B02, prev)
            throw RescheduleException
        }
        machine.ax = savedAx
        machine.bx = savedBx
        machine.cx = savedCx
        machine.dx = savedDx
        machine.di = savedDi
        machine.si = savedSi
        machine.ds = savedDs
        machine.es = savedEs
        machine.bp = machine.rw(es, machine.g(0x0B02) + tbl)
        machine.sg(0x0B12, machine.bp)
    }

    /**
     * `place_entry` (`341b`): places entry `machine.dx` (single sprite or recursive compound).
     */
    fun placeEntry(machine: Machine) {
        val savedDs = machine.ds
        val savedDi = machine.di
        val savedBx = machine.bx
        val savedCx = machine.cx
        val savedSi = machine.si

        val (seg, ofs) = Assets.entryPtr(machine, machine.dx)
        val t = machine.rb(seg, ofs)
        val ods = machine.g(0x0BEA)
        if ((t and 0x80) != 0) {
            if (t == 0xFE) {
                // 342f jumps into palette_load_record with five words still pushed, so its ret goes astray
                // (the EGA/CGA presentation stops on it). Ishar 2's travel map places one as a compound's
                // last part (pptravel.io entry 2) and loads the same palette at once (palette_load 0, 001f):
                // nothing is placed, and the script's own load does the palette.
                machine.ds = savedDs
                machine.di = savedDi
                machine.bx = savedBx
                machine.cx = savedCx
                machine.si = savedSi
                return
            }
            val n = machine.rb(seg, ofs + 1)
            var p = ofs + 2
            repeat(n) {
                val keep0 = machine.g(0x0C12)
                val keep1 = machine.g(0x0C02)
                val keep2 = machine.g(0x0C04)
                val keep3 = machine.g(0x0C06)
                machine.sgb(0x0C13, 1)
                var dx = machine.rw(seg, p)
                var ax = machine.rw(seg, p + 2)
                if (machine.gb(0x0C12) != 0) {
                    ax = (-ax) and 0xFFFF
                }
                machine.sg(0x0C02, machine.g(0x0C02) + ax)
                machine.sg(0x0C04, machine.g(0x0C04) + machine.rw(seg, p + 4))
                machine.sg(0x0C06, machine.g(0x0C06) + machine.rw(seg, p + 6))
                p += 8
                if ((dx and 0x8000) != 0) {
                    dx = dx and 0x7FFF
                    machine.sgb(0x0C12, machine.gb(0x0C12) xor 1)
                }
                machine.dx = dx
                placeEntry(machine)
                machine.sg(0x0C06, keep3)
                machine.sg(0x0C04, keep2)
                machine.sg(0x0C02, keep1)
                machine.sg(0x0C12, keep0)
            }
            machine.di = 0
        } else {
            machine.ds = ods
            var di: Int?
            if (machine.gb(0x0C14) != 0 || machine.gb(0x0C13) != 0) {
                di = objectFind(machine)
                while (true) {
                    if (di == null) {
                        di = objectAlloc(machine)
                        break
                    }
                    if (machine.rb(ods, di) == 0) {
                        machine.wb(ods, di, 2)
                        break
                    }
                    di = objectFindNext(machine, di)
                }
            } else {
                di = objectFind(machine)
                if (di == null) {
                    di = objectAlloc(machine)
                }
                if ((machine.rb(ods, di) and 0x80) == 0) {
                    machine.wb(ods, di, 2)
                }
            }
            machine.di = di
            val (aseg, slot) = Assets.entrySlot(machine, machine.dx)
            val es = machine.es
            val bp = machine.bp
            machine.ww(ods, di + 0x12, slot)
            machine.ww(ods, di + 0x14, aseg)
            machine.wb(ods, di + 0x23, machine.gb(0x0C12))
            machine.ww(ods, di + 0x1E, machine.rw(es, bp - 0x2A))
            machine.wb(ods, di + 0x20, machine.rb(es, bp - 0x2B))
            machine.wb(ods, di + 0x24, machine.rb(es, bp - 0x2C))
            machine.wb(ods, di + 0x25, machine.rb(es, bp - 0x2D))
            var cl = machine.rb(es, bp - 0x25)
            machine.wb(ods, di + 0x1C, cl)
            if ((cl and 0x80) == 0) {
                machine.wb(ods, di + 0x1D, machine.rb(es, bp - 0x27))
                cl = machine.rb(es, bp - 0x26)
                machine.wb(ods, di + 0x1C, cl)
                if ((cl and 0x80) != 0) {
                    val vb = (machine.g(0x0BF6) + machine.rw(es, bp - 0x16)) and 0xFFFF
                    machine.wb(ods, di + 0x1D, 0)
                    machine.wb(ods, di + 0x1C, machine.rb(es, vb + 0x1F))
                }
            }
            machine.ww(ods, di + 0x16, machine.g(0x0BFA) + machine.g(0x0C02))
            machine.ww(ods, di + 0x18, machine.g(0x0BFC) + machine.g(0x0C04))
            machine.ww(ods, di + 0x1A, machine.g(0x0BFE) + machine.g(0x0C06))
        }

        if (machine.gb(0x0C13) == 0) {
            var di = objectFind(machine)
            while (di != null) {
                if (machine.rb(ods, di) != 0) {
                    di = objectFindNext(machine, di)
                    continue
                }
                val next = objectMarkErase(machine, di)
                if (next == 0 || !objectMatch(machine, next)) break
                di = next
            }
        }
        machine.sgb(0x0C14, 0)
        machine.ds = savedDs
        machine.di = savedDi
        machine.bx = savedBx
        machine.cx = savedCx
        machine.si = savedSi
    }

    fun objectFind(machine: Machine): Int? {
        val ods = machine.g(0x0BEA)
        val es = machine.es
        val bp = machine.bp
        var bx = 0
        var di = machine.rw(es, bp - 0x18)
        val cx = Machine.s16(machine.rw(es, bp - 0x16))
        val al = machine.gb(0x0C0E)
        val alSigned = Machine.s8(al)
        while (di != 0) {
            val v = Machine.s16(machine.rw(ods, di + 2))
            if (cx < v) {
                bx = di
                di = machine.rw(ods, di + 4)
                continue
            }
            if (cx != v) break
            val i = machine.rb(ods, di + 1)
            if (alSigned < Machine.s8(i)) {
                bx = di
                di = machine.rw(ods, di + 4)
                continue
            }
            if (al != i) break
            machine.bx = bx
            return di
        }
        machine.bx = bx
        machine.di = di
        return null
    }

    fun objectMatch(machine: Machine, di: Int): Boolean {
        val ods = machine.g(0x0BEA)
        return machine.rw(ods, di + 2) == machine.rw(machine.es, machine.bp - 0x16) &&
            machine.rb(ods, di + 1) == machine.gb(0x0C0E)
    }

    fun objectFindNext(machine: Machine, di: Int): Int? {
        val ods = machine.g(0x0BEA)
        machine.bx = di
        val next = machine.rw(ods, di + 4)
        if (next != 0 && objectMatch(machine, next)) {
            return next
        }
        machine.di = next
        return null
    }

    fun objectAlloc(machine: Machine): Int {
        val ods = machine.g(0x0BEA)
        val es = machine.es
        val bp = machine.bp
        val si = machine.g(0x0BEC)
        if (si == 0) {
            throw UnsupportedEngineOperationException("script error 0ah: too many sprites")
        }
        machine.sg(0x0BEC, machine.rw(ods, si + 4))
        if (machine.bx == 0) {
            machine.ww(es, bp - 0x18, si)
        } else {
            machine.ww(ods, machine.bx + 4, si)
        }
        machine.ww(ods, si + 4, machine.di)
        val di = si
        machine.ww(ods, di + 2, machine.rw(es, bp - 0x16))
        machine.wb(ods, di + 1, machine.gb(0x0C0E))
        machine.wb(ods, di, 0xFF)
        val rec = machine.rw(es, (machine.rw(es, bp - 0x16) + machine.g(0x0BF6) + 2) and 0xFFFF)
        machine.ww(ods, di + 6, machine.rw(ods, rec + 6))
        machine.ww(ods, rec + 6, di)
        return di
    }

    fun objectMarkErase(machine: Machine, di: Int): Int {
        val ods = machine.g(0x0BEA)
        val es = machine.es
        val bp = machine.bp
        val bx = machine.bx
        val nxt = machine.rw(ods, di + 4)
        if (machine.gb(0x0BF3) == 0 && (machine.rb(ods, di) and 0x80) == 0) {
            machine.wb(ods, di, 1)
            if (bx == 0) {
                machine.ww(es, bp - 0x18, nxt)
            } else {
                machine.ww(ods, bx + 4, nxt)
            }
        } else {
            if (bx == 0) {
                machine.ww(es, bp - 0x18, nxt)
            } else {
                machine.ww(ods, bx + 4, nxt)
            }
            machine.ww(ods, di + 4, machine.g(0x0BEC))
            machine.sg(0x0BEC, di)
            viewRecordUnlink(machine, di)
        }
        return if (bx == 0) machine.rw(es, bp - 0x18) else machine.rw(ods, bx + 4)
    }

    fun viewRecordUnlink(machine: Machine, di: Int) {
        val ods = machine.g(0x0BEA)
        val es = machine.es
        val vb = (machine.g(0x0BF6) + machine.rw(ods, di + 2)) and 0xFFFF
        if (di == machine.rw(es, vb + 2)) {
            machine.ww(es, vb + 2, machine.rw(ods, di + 6))
            return
        }
        var si = machine.rw(es, vb + 2)
        while (di != machine.rw(ods, si + 6)) {
            si = machine.rw(ods, si + 6)
        }
        machine.ww(ods, si + 6, machine.rw(ods, di + 6))
    }

    fun deleteAllObjects(machine: Machine) {
        machine.bx = 0
        var di = machine.rw(machine.es, machine.bp - 0x18)
        while (di != 0) {
            di = objectMarkErase(machine, di)
        }
        machine.sgb(0x0BF3, 0)
    }

    fun deleteObject(machine: Machine, index: Int, now: Boolean = false) {
        if (now) machine.sgb(0x0BF3, 1)
        machine.sgb(0x0C0E, index)
        while (true) {
            val di = objectFind(machine) ?: break
            objectMarkErase(machine, di)
        }
        machine.sgb(0x0BF3, 0)
    }

    fun deleteViewObjects(machine: Machine, now: Boolean = false) {
        if (now) machine.sgb(0x0BF3, 1)
        val ods = machine.g(0x0BEA)
        val es = machine.es
        val bp = machine.bp
        var bx = 0
        var di = machine.rw(es, bp - 0x18)
        val cx = Machine.s16(machine.rw(es, bp - 0x16))
        var found = 0
        while (di != 0) {
            val v = Machine.s16(machine.rw(ods, di + 2))
            if (cx < v) {
                bx = di
                di = machine.rw(ods, di + 4)
                continue
            }
            if (cx == v) {
                found = di
            }
            break
        }
        machine.bx = bx
        di = found
        while (di != 0) {
            di = objectMarkErase(machine, di)
            if (di == 0 || machine.rw(es, bp - 0x16) != machine.rw(ods, di + 2)) break
        }
        machine.sgb(0x0BF3, 0)
    }

    fun viewListAdd(machine: Machine, viewVar: Int) {
        val es = machine.es
        val di = machine.g(0x0BF6)
        machine.ww(es, viewVar + di + 4, 0)
        var bp = machine.g(0x0C16)
        if (bp == 0) {
            machine.sg(0x0C16, viewVar)
            return
        }
        while (machine.rw(es, bp + di + 4) != 0) {
            bp = machine.rw(es, bp + di + 4)
        }
        machine.ww(es, bp + di + 4, viewVar)
    }

    fun viewListRemove(machine: Machine, viewVar: Int) {
        val es = machine.es
        val di = machine.g(0x0BF6)
        var bp = machine.g(0x0C16)
        if (bp == 0) return
        if (viewVar == bp) {
            machine.sg(0x0C16, machine.rw(es, viewVar + di + 4))
            return
        }
        while (true) {
            val ax = machine.rw(es, bp + di + 4)
            if (viewVar == ax) {
                machine.ww(es, bp + di + 4, machine.rw(es, viewVar + di + 4))
                return
            }
            bp = ax
            if (ax == 0) return
        }
    }

    fun viewMatrixCross(machine: Machine, bx: Int) {
        val es = machine.es
        fun m(k: Int): Int = machine.rsb(es, bx + k)
        machine.ww(es, bx + 0x26, m(0x21) * m(0x25) - m(0x24) * m(0x22))
        machine.ww(es, bx + 0x27, m(0x22) * m(0x23) - m(0x25) * m(0x20))
        machine.ww(es, bx + 0x28, m(0x20) * m(0x24) - m(0x23) * m(0x21))
    }
}
