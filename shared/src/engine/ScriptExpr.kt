package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Machine.Companion.CS
import dev.pelotasplus.ishar.engine.Machine.Companion.s16
import dev.pelotasplus.ishar.engine.Machine.Companion.s8

/** What expressions need from the rest of the engine. */
interface ScriptHost {
    /** `key_wait` (`143c`): a key down, then up. */
    fun keyWait(): Int

    /** `cpu_speed`: the boot benchmark. */
    fun cpuSpeed(): Int

    fun fileExists(name: String): Boolean

    /** Expression 6ah read a non-zero held key (prompts spin on it until released). */
    fun heldKeyRead() {}
}

/**
 * Script expressions and store targets:
 * `script_vm_step` (`69a6`), the expression table `cs:01f2`, and the store
 * tables `cs:029c` (`script_vm_store` `291b`) / `cs:02d8` (`script_vm_add_store` `2943`).
 *
 * Terms evaluate strictly left to right: `CX` is the left operand, `DX` the result,
 * `BX` the push stack (`ss:277c` downward). The running frame is `ES:BP`.
 */
class ScriptExpr(
    private val machine: Machine,
    var host: ScriptHost,
) {
    private val SS = machine.ss
    private val handlers = arrayOfNulls<() -> Unit>(256)

    // --- Stepping (69a6) ----------------------------------------------------

    /** `69a6`: `BX = 277ch`, `CX = DX`, one term into `DX`. */
    fun step() {
        machine.bx = PUSH_TOP
        machine.cx = machine.dx
        dispatch()
    }

    /** `69a9`. */
    fun stepKeepBx() {
        machine.cx = machine.dx
        dispatch()
    }

    /** `69ab`: `sub ax,ax / lodsb / jmp [cs:01f2 + ax]` (raw byte index). */
    fun dispatch() {
        machine.ax = 0
        val op = machine.lodsb()
        machine.di = op
        val h = handlers[op] ?: throw UnsupportedEngineOperationException(
            "expression ${hex2(op)} at ${hex4(machine.ds)}:${hex4(machine.si - 1)}",
        )
        h()
    }

    /** `699e`: next term into `CX`, `DX` kept. */
    fun evalCx() {
        val dx = machine.dx
        step()
        machine.cx = machine.dx
        machine.dx = dx
    }

    /** `69b8`: swap `[0c58]`/`[0c5a]`, then dispatch. */
    fun swapStrings() {
        val a = machine.g(0x0C58)
        machine.sg(0x0C58, machine.g(0x0C5A))
        machine.sg(0x0C5A, a)
        dispatch()
    }

    /** `69b5`. */
    fun stepSwapped() {
        machine.bx = PUSH_TOP
        swapStrings()
    }

    // --- Push stack ---------------------------------------------------------

    fun push(v: Int) {
        machine.bx = (machine.bx - 2) and 0xFFFF
        machine.ww(SS, machine.bx, v)
    }

    fun pop(): Int {
        val v = machine.rw(SS, machine.bx)
        machine.bx = (machine.bx + 2) and 0xFFFF
        return v
    }

    // --- Array element offsets (6c2e / 6c5b / 6c8a); BP is the array's base ---

    private fun arrayIndex(kind: Char): Int {
        val es = machine.es
        val bp = machine.bp
        var ax = machine.ax
        var dx = machine.dx
        var di = ax
        val cl = machine.rb(es, bp + di - 1)
        di = (bp + di - 2) and 0xFFFF
        // An Ishar 3 pointer array: the header stays at the variable, the elements are elsewhere (e9h)
        machine.pointerArrays[(es shl 4) + ((bp + ax) and 0xFFFF)]?.let { ax = (it - bp) and 0xFFFF }
        when (kind) {
            'b' -> ax = (ax + dx) and 0xFFFF
            'w' -> {
                dx = (dx + dx) and 0xFFFF
                ax = (ax + dx) and 0xFFFF
            }
            else -> {
                machine.sg(0x0B12, ax)
                val p = machine.rb(es, di) * dx
                ax = p and 0xFFFF
                dx = p ushr 16
                ax = (ax + machine.g(0x0B12)) and 0xFFFF
            }
        }
        if (cl != 0) {
            machine.sg(0x0B0A, ax)
            repeat(cl) {
                val v = pop()
                di = (di - 2) and 0xFFFF
                val p = v.toLong() * machine.rw(es, di)
                ax = (p and 0xFFFF).toInt()
                dx = (p ushr 16).toInt()
                machine.sg(0x0B0A, machine.g(0x0B0A) + ax)
            }
            ax = machine.g(0x0B0A)
        }
        machine.ax = ax
        machine.dx = dx
        machine.di = di
        return ax
    }

    /** `push bp / mov bp, X / call script_array_index* / pop bp`. */
    private fun withBp(bp: Int, kind: Char): Int {
        val old = machine.bp
        machine.bp = bp
        try {
            return arrayIndex(kind)
        } finally {
            machine.bp = old
        }
    }

    /** `es:[bp+v]` is a frame handle (a byte offset in the frame table); returns that frame's BP. */
    fun frameBase(v: Int): Int {
        val es = machine.es
        val h = machine.rw(es, machine.bp + v)
        return machine.rw(es, (h + machine.g(0x0BD2)) and 0xFFFF)
    }

    /** 30/32/34: the array offset first, then the frame-handle variable. */
    private fun frameArr(): Int {
        val arr = machine.lodsw()
        val v = machine.lodsw()
        val fb = frameBase(v)
        machine.ax = arr
        return fb
    }

    private fun copyToC58(seg: Int, ofs: Int) {
        machine.di = machine.strcpy(SS, machine.g(0x0C58), seg, ofs).first
    }

    private fun cbwDx(v: Int) {
        machine.ax = if ((v and 0x80) != 0) v or 0xFF00 else v
        machine.dx = machine.ax
    }

    // --- Terms --------------------------------------------------------------

    private fun lit8() = cbwDx(machine.lodsb())

    private fun lit16() {
        machine.dx = machine.lodsw()
    }

    private fun str() {
        val (d, s) = machine.strcpy(SS, machine.g(0x0C58), machine.ds, machine.si)
        machine.di = d
        machine.si = s
    }

    private fun locB() {
        machine.di = machine.lodsw()
        cbwDx(machine.rb(machine.es, machine.bp + machine.di))
    }

    private fun locW() {
        machine.di = machine.lodsw()
        machine.dx = machine.rw(machine.es, machine.bp + machine.di)
    }

    private fun locS() {
        val a = machine.lodsw()
        copyToC58(machine.es, machine.bp + a)
    }

    private fun locBArr() {
        machine.lodsw()
        machine.di = arrayIndex('b')
        cbwDx(machine.rb(machine.es, machine.bp + machine.di))
    }

    private fun locWArr() {
        machine.lodsw()
        machine.di = arrayIndex('w')
        machine.dx = machine.rw(machine.es, machine.bp + machine.di)
    }

    private fun locSArr() {
        machine.lodsw()
        val a = arrayIndex('s')
        copyToC58(machine.es, machine.bp + a)
    }

    private fun locbB() {
        machine.di = machine.lodsb()
        cbwDx(machine.rb(machine.es, machine.bp + machine.di))
    }

    private fun locbW() {
        machine.di = machine.lodsb()
        machine.dx = machine.rw(machine.es, machine.bp + machine.di)
    }

    private fun locbS() {
        machine.ax = 0
        val a = machine.lodsb()
        copyToC58(machine.es, machine.bp + a)
    }

    private fun locbBArr() {
        machine.ax = machine.lodsb()
        machine.di = arrayIndex('b')
        cbwDx(machine.rb(machine.es, machine.bp + machine.di))
    }

    private fun locbWArr() {
        machine.ax = machine.lodsb()
        machine.di = arrayIndex('w')
        machine.dx = machine.rw(machine.es, machine.bp + machine.di)
    }

    private fun locbSArr() {
        machine.ax = 0
        machine.lodsb()
        val a = arrayIndex('s')
        copyToC58(machine.es, machine.bp + a)
    }

    private fun globB() {
        machine.di = (machine.g(0x0BF6) + machine.lodsw()) and 0xFFFF
        cbwDx(machine.rb(machine.es, machine.di))
    }

    private fun globW() {
        machine.di = (machine.g(0x0BF6) + machine.lodsw()) and 0xFFFF
        machine.dx = machine.rw(machine.es, machine.di)
    }

    private fun globS() {
        val a = machine.lodsw()
        copyToC58(machine.es, machine.g(0x0BF6) + a)
    }

    private fun globBArr() {
        machine.lodsw()
        val base = machine.g(0x0BF6)
        val a = withBp(base, 'b')
        cbwDx(machine.rb(machine.es, base + a))
    }

    private fun globWArr() {
        machine.lodsw()
        val base = machine.g(0x0BF6)
        val a = withBp(base, 'w')
        machine.dx = machine.rw(machine.es, base + a)
    }

    private fun globSArr() {
        machine.lodsw()
        val base = machine.g(0x0BF6)
        val a = withBp(base, 's')
        copyToC58(machine.es, base + a)
    }

    private fun frameB() {
        val fb = frameBase(machine.lodsw())
        machine.di = (fb + machine.lodsw()) and 0xFFFF
        cbwDx(machine.rb(machine.es, machine.di))
    }

    private fun frameW() {
        val fb = frameBase(machine.lodsw())
        machine.di = (fb + machine.lodsw()) and 0xFFFF
        machine.dx = machine.rw(machine.es, machine.di)
    }

    private fun frameS() {
        val fb = frameBase(machine.lodsw())
        val a = (fb + machine.lodsw()) and 0xFFFF
        copyToC58(machine.es, a)
    }

    private fun frameBArr() {
        val fb = frameArr()
        val a = withBp(fb, 'b')
        cbwDx(machine.rb(machine.es, fb + a))
    }

    private fun frameWArr() {
        val fb = frameArr()
        val a = withBp(fb, 'w')
        machine.dx = machine.rw(machine.es, fb + a)
    }

    private fun frameSArr() {
        val fb = frameArr()
        val a = withBp(fb, 's')
        copyToC58(machine.es, fb + a)
    }

    private fun popTerm() {
        machine.dx = machine.cx
        machine.cx = pop()
    }

    /** 38h: terms until 3ah. */
    fun group() {
        try {
            while (true) dispatch()
        } catch (e: GroupEndException) {
            // 3ah ends the group
        }
    }

    private fun binop(f: (Int, Int) -> Int) {
        stepKeepBx()
        machine.dx = f(machine.cx, machine.dx) and 0xFFFF
    }

    private fun flag(b: Boolean): Int = if (b) 0xFFFF else 0

    private fun sub() {
        stepKeepBx()
        machine.cx = (machine.cx - machine.dx) and 0xFFFF
        machine.dx = machine.cx
    }

    /** mod/div: `idiv cx` (f7 f9: ModRM bit 2 clear, so the INT 0 retry shifts `DX:AX` logically). */
    private fun idiv(): Pair<Int, Int> {
        stepKeepBx()
        val a = machine.cx
        val d = if (machine.dx != 0) machine.dx else 1
        machine.cx = d
        val num = if ((a and 0x8000) != 0) 0xFFFF0000L or a.toLong() else a.toLong()
        val qr = Machine.idiv32(num, d, sar = false)
        machine.ax = qr.first
        return qr
    }

    private fun mul() {
        stepKeepBx()
        val p = s16(machine.dx) * s16(machine.cx)
        machine.ax = p and 0xFFFF
        machine.dx = machine.ax
    }

    private fun mulDx(ax: Int) {
        val p = ax.toLong() * machine.dx
        machine.ax = (p and 0xFFFF).toInt()
        machine.dx = (p ushr 16).toInt()
    }

    private fun random() {
        val ax = lcg(machine.g(0x0C4E))
        machine.sg(0x0C4E, ax)
        mulDx(ax)
    }

    private fun rand2() {
        var ax = pop()
        ax = ((ax ushr 6) or (ax shl 10)) and 0xFFFF
        mulDx(lcg(ax))
    }

    private fun heldKey() {
        machine.dx = machine.rb(CS, 0x0318)
        machine.ax = machine.dx
        if (machine.dx != 0) host.heldKeyRead()
    }

    /**
     * 6eh: `joystick_read` (`1334`). Without a joystick (`288a/288b = 0`) it is
     * `keyboard_stick_bits` (`1137`), which builds bits from key_down only when
     * `DX = 0` and otherwise returns `AX` untouched (the term byte 6eh).
     */
    private fun joystick() {
        var ch = 0
        if (machine.gb(0x0CA1) == 0) {
            val stick = if ((machine.dx and 0xFF) != 0) machine.gb(0x288B) else machine.gb(0x288A)
            if (stick != 0) throw UnsupportedEngineOperationException("joystick on port 201h")
            if (machine.dx != 0) {
                ch = machine.ax and 0xFF
            } else {
                fun kd(k: Int) = (machine.rb(CS, 0x031A + k) and 1) != 0
                if (kd(123)) ch = ch or 1
                if (kd(122)) ch = ch or 2
                if (kd(121)) ch = ch or 4
                if (kd(120)) ch = ch or 8
                if (kd(119) || kd(118)) ch = ch or 0x80
            }
        }
        machine.ax = ch
        machine.dx = ch
    }

    private fun shiftFlags() {
        machine.ax = machine.rb(0x40, 0x17) and 0x1F
        machine.dx = machine.ax
    }

    /** 76h: `system_query` (`1451`). */
    private fun systemQuery() {
        val m = machine
        machine.dx = when (machine.dx) {
            0 -> { // free heap, KB
                val p = (m.g(0x0B96) + (m.g(0x0B94) ushr 4) - m.g(0x0BCA) - (m.g(0x0BC8) ushr 4)) and 0xFFFF
                (p * 16 / 1000) and 0xFFFF
            }
            1 -> { // free frame memory, KB
                val p = (m.g(0x0BE2) + (m.g(0x0BE0) ushr 4) - m.g(0x0BD6) - (m.g(0x0BD8) ushr 4)) and 0xFFFF
                (p * 16 / 1000) and 0xFFFF
            }
            2 -> (m.g(0x0BCC) - m.g(0x0BCE)) and 0xFFFF
            3 -> (m.g(0x0BDA) - m.g(0x0BDC)) and 0xFFFF
            4 -> { // free object records
                var n = 0
                var bx = m.g(0x0BEC)
                while (bx != 0) {
                    n++
                    bx = m.rw(m.g(0x0BEA), bx + m.g(0x0BE8) + 4)
                }
                n
            }
            0x41, 0x42, 0x43, 0x44, 0x61, 0x62, 0x63, 0x64 -> 0x7D00 // free disk KB, capped like the game
            else -> 0xFFFF
        }
    }

    private fun eventPop() {
        val es = machine.es
        val bp = machine.bp
        var di = machine.rw(es, bp - 0x1E)
        if (di == machine.rw(es, bp - 0x1C)) {
            machine.dx = 0xFFFF
            return
        }
        val (aofs, aseg) = machine.far(es, bp - 0x14)
        val qsize = machine.rw(aseg, aofs + 0x16)
        machine.dx = machine.rw(es, bp + di)
        di = (di + 2) and 0xFFFF
        if (s16(di) >= -0x34) di = (di - qsize) and 0xFFFF
        machine.ww(es, bp - 0x1E, di)
        if (di == machine.rw(es, bp - 0x1C)) {
            machine.wb(es, bp - 0x24, machine.rb(es, bp - 0x24) and 0x7F)
        }
    }

    /**
     * a0h: `drive_select` (`15a1`) for 'a'/'b'/'c', '?' = the current drive.
     * The game drive is C: with no floppies, so A and B fail (0) and C and '?' answer 'C'.
     */
    private fun selectDrive() {
        when (machine.dx) {
            0x61, 0x41, 0x62, 0x42 -> machine.dx = 0
            0x63, 0x43, 0x3F -> {
                machine.ax = 2
                machine.dx = 0x43
            }
            else -> machine.dx = 0
        }
    }

    private fun cardCode() {
        val c = when (machine.gb(0x2894)) {
            7 -> 1
            3 -> 2
            5 -> 4
            else -> 0
        }
        machine.ax = 2000 + c
        machine.dx = machine.ax
    }

    // --- Strings ------------------------------------------------------------

    private fun stringPop(buf: Int = 0x0C58) {
        var si = machine.g(buf)
        while (true) {
            val c = machine.rb(SS, machine.bx)
            machine.wb(SS, si, c)
            machine.bx = (machine.bx + 1) and 0xFFFF
            si++
            if (c == 0) return
        }
    }

    private fun strEnd(b: Int): Int {
        var si = b
        while (machine.rb(SS, si) != 0) si++
        return si
    }

    private fun strLeft() {
        stringPop()
        machine.dx = machine.dx and 0xFF
        machine.wb(SS, machine.g(0x0C58) + machine.dx, 0)
    }

    private fun strRight() {
        stringPop()
        val b = machine.g(0x0C58)
        var si = (strEnd(b) - machine.dx) and 0xFFFF
        if (si <= b) si = b
        machine.strcpy(SS, b, SS, si)
    }

    private fun strMid() {
        val si = (machine.g(0x0C58) + pop()) and 0xFFFF
        stringPop()
        val di = machine.g(0x0C58)
        val c = machine.rb(SS, si)
        machine.wb(SS, di, c)
        val dx = (machine.dx - 2) and 0xFFFF
        machine.dx = dx
        if (c != 0 && (dx and 0x8000) != 0) machine.wb(SS, di + 1, 0)
    }

    private fun strlen() {
        val b = machine.g(0x0C58)
        machine.dx = (strEnd(b) - b) and 0xFFFF
    }

    fun strToInt() {
        machine.sg(0x0B12, 10)
        var si = machine.g(0x0C58)
        var neg = false
        var ax = 0
        if (machine.rb(SS, si) != 0) {
            if (machine.rb(SS, si) == 0x2D) {
                neg = true
                si++
            }
            if (machine.rb(SS, machine.di) == 0x24) {
                throw UnsupportedEngineOperationException("str_to_int hex path")
            }
            while (true) {
                val c = machine.rb(SS, si)
                si++
                if (c !in 0x30..0x39) break
                ax = (ax * 10 + c - 0x30) and 0xFFFF
            }
        }
        machine.dx = if (neg) (-ax) and 0xFFFF else ax
    }

    private fun strFirst() {
        machine.dx = machine.rb(SS, machine.g(0x0C58))
    }

    fun formatDecimal(value: Int, dst: Int) {
        var v = value and 0xFFFF
        var di = dst
        machine.sg(0x0B10, 0)
        if ((v and 0x8000) != 0) {
            machine.wb(SS, di, 0x2D)
            di++
            v = (-v) and 0xFFFF
        }
        DECIMAL_PLACES.forEachIndexed { n, p ->
            var d = 0
            while (s16(v - p) >= 0) {
                v = (v - p) and 0xFFFF
                d++
            }
            if (d != 0) {
                machine.sg(0x0B10, 1)
            } else if (machine.g(0x0B10) == 0 && n != 4) {
                return@forEachIndexed
            }
            machine.wb(SS, di, 0x30 + d)
            di++
        }
        machine.wb(SS, di, 0)
    }

    private fun intToStr() {
        machine.ax = machine.dx
        formatDecimal(machine.dx, machine.g(0x0C58))
    }

    private fun chr() {
        val si = machine.g(0x0C58)
        machine.wb(SS, si, machine.dx)
        machine.wb(SS, si + 1, 0)
    }

    /** `6ff2`: `[0c5a]` (left) against `[0c58]`; -> <0, 0, >0 like the flags. */
    private fun stringCompare(): Int {
        var si = machine.g(0x0C58)
        var di = machine.g(0x0C5A)
        while (true) {
            val a = machine.rb(SS, si)
            val b = machine.rb(SS, di)
            si++
            di++
            if (a != b) {
                if (b == 0) return 0
                return s8(b) - s8(a) // the flags of `sub al` as a signed compare
            }
            if (b == 0) return 0
        }
    }

    private fun strcmpOp(f: (Int) -> Boolean) {
        swapStrings()
        machine.dx = flag(f(stringCompare()))
    }

    private fun strcat() {
        swapStrings()
        val di = strEnd(machine.g(0x0C5A))
        machine.strcpy(SS, di, SS, machine.g(0x0C58))
        val a = machine.g(0x0C58)
        machine.sg(0x0C58, machine.g(0x0C5A))
        machine.sg(0x0C5A, a)
    }

    private fun strPush() {
        val b = machine.g(0x0C58)
        var si = strEnd(b)
        while (true) {
            machine.bx = (machine.bx - 1) and 0xFFFF
            machine.wb(SS, machine.bx, machine.rb(SS, si))
            if (si == b) break
            si--
        }
    }

    private fun strPop() {
        val a = machine.g(0x0C5A)
        machine.sg(0x0C5A, machine.g(0x0C58))
        machine.sg(0x0C58, a)
        stringPop(0x0C5A)
    }

    private fun fileExists() {
        machine.dx = flag(host.fileExists(cstr(SS, machine.g(0x0C58))))
    }

    fun cstr(seg: Int, ofs: Int): String {
        val sb = StringBuilder()
        while (true) {
            val c = machine.rb(seg, ofs + sb.length)
            if (c == 0) return sb.toString()
            sb.append(c.toChar())
        }
    }

    // --- Store targets (script_vm_store 291b, script_vm_add_store 2943) -----

    /** `291b`: stores `DX` (or the string in `[0c58]`) into the target that follows. */
    fun store() {
        swapStoreBuffer()
        machine.di = machine.lodsb()
        storeOp(machine.di, add = false)
    }

    /** `2943`: adds `DX` into the target; returns the result for djnz's ZF test (0 = zero). */
    fun addStore(): Int {
        swapStoreBuffer()
        machine.di = machine.lodsb()
        return storeOp(machine.di, add = true)
    }

    private fun swapStoreBuffer() {
        val bx = machine.g(0x0C58)
        machine.sg(0x0C58, machine.g(0x0C5C))
        machine.sg(0x0C5C, bx)
        machine.bx = PUSH_TOP
        machine.ax = 0
    }

    private fun storeOp(op0: Int, add: Boolean): Int {
        val es = machine.es
        when (op0) {
            0x36 -> {
                push(machine.dx)
                return 1
            }
            0x38 -> { // indexed: push DX, the group, then the target
                machine.bx = PUSH_TOP
                push(machine.dx)
                group()
                val op = machine.lodsb()
                machine.di = if ((op and 0x80) != 0) op or 0xFF00 else op
                return storeOp(op, add)
            }
            0x3A -> throw GroupEndException
        }
        val target = STORE_TARGETS[op0] ?: throw UnsupportedEngineOperationException("store ${hex2(op0)}")
        val t = target.type
        val addr: Int
        when (target.where) {
            Where.LOC, Where.LOC_ARR -> {
                var a = if (target.wide) {
                    machine.lodsw()
                } else {
                    machine.ax = 0
                    machine.lodsb()
                }
                if (target.where == Where.LOC_ARR) {
                    machine.ax = a
                    a = arrayIndex(t)
                    if (t != 's') machine.dx = pop()
                }
                addr = (machine.bp + a) and 0xFFFF
            }
            Where.GLOB, Where.GLOB_ARR -> {
                var a = machine.lodsw()
                val base = machine.g(0x0BF6)
                if (target.where == Where.GLOB_ARR) {
                    machine.ax = a
                    a = withBp(base, t)
                    if (t != 's') machine.dx = pop()
                }
                addr = (base + a) and 0xFFFF
            }
            Where.FRAME -> {
                val fb = frameBase(machine.lodsw())
                addr = (fb + machine.lodsw()) and 0xFFFF
            }
            Where.FRAME_ARR -> {
                val fb = frameArr()
                val a = withBp(fb, t)
                if (t != 's') machine.dx = pop()
                addr = (fb + a) and 0xFFFF
            }
        }
        return when (t) {
            'b' -> {
                val v = (if (add) machine.rb(es, addr) + machine.dx else machine.dx) and 0xFF
                machine.wb(es, addr, v)
                v
            }
            'w' -> {
                val v = (if (add) machine.rw(es, addr) + machine.dx else machine.dx) and 0xFFFF
                machine.ww(es, addr, v)
                v
            }
            else -> {
                var a = addr
                if (add) { // string append
                    while (machine.rb(es, a) != 0) a = (a + 1) and 0xFFFF
                }
                machine.strcpy(es, a, SS, machine.g(0x0C5C))
                0
            }
        }
    }

    private enum class Where { LOC, LOC_ARR, GLOB, GLOB_ARR, FRAME, FRAME_ARR }

    private class Target(val where: Where, val type: Char, val wide: Boolean = true)

    // --- Expression table cs:01f2 -------------------------------------------

    init {
        val t = handlers
        t[0x00] = ::lit8; t[0x02] = ::lit16; t[0x04] = ::str
        t[0x06] = ::locB; t[0x08] = ::locW; t[0x0A] = ::locS
        t[0x0C] = ::locSArr; t[0x0E] = ::locBArr; t[0x10] = ::locWArr
        t[0x12] = ::locbB; t[0x14] = ::locbW; t[0x16] = ::locbS
        t[0x18] = ::locbSArr; t[0x1A] = ::locbBArr; t[0x1C] = ::locbWArr
        t[0x1E] = ::globB; t[0x20] = ::globW; t[0x22] = ::globS
        t[0x24] = ::globSArr; t[0x26] = ::globBArr; t[0x28] = ::globWArr
        t[0x2A] = ::frameB; t[0x2C] = ::frameW; t[0x2E] = ::frameS
        t[0x30] = ::frameSArr; t[0x32] = ::frameBArr; t[0x34] = ::frameWArr
        t[0x36] = ::popTerm; t[0x38] = ::group; t[0x3A] = { throw GroupEndException }
        t[0x40] = { push(machine.dx) }
        t[0x42] = { binop { c, d -> c and d } }
        t[0x44] = { binop { c, d -> c or d } }
        t[0x46] = { binop { c, d -> c xor d } }
        t[0x48] = { binop { c, d -> (c xor d).inv() } }
        t[0x4A] = { binop { c, d -> flag(c == d) } }
        t[0x4C] = { binop { c, d -> flag(c != d) } }
        t[0x4E] = { binop { c, d -> flag(s16(c) <= s16(d)) } }
        t[0x50] = { binop { c, d -> flag(s16(c) >= s16(d)) } }
        t[0x52] = { binop { c, d -> flag(s16(c) < s16(d)) } }
        t[0x54] = { binop { c, d -> flag(s16(c) > s16(d)) } }
        t[0x56] = { binop { c, d -> c + d } }
        t[0x58] = ::sub
        t[0x5A] = { machine.dx = idiv().second }
        t[0x5C] = { machine.dx = idiv().first }
        t[0x5E] = ::mul
        t[0x60] = { machine.dx = (-machine.dx) and 0xFFFF }
        t[0x62] = { if ((machine.dx and 0x8000) != 0) machine.dx = (-machine.dx) and 0xFFFF }
        t[0x64] = ::random
        t[0x66] = { if (machine.dx != 0) machine.dx = if ((machine.dx and 0x8000) != 0) 0xFFFF else 1 }
        t[0x68] = { machine.dx = machine.dx.inv() and 0xFFFF }
        t[0x6A] = ::heldKey
        t[0x6C] = {}
        t[0x6E] = ::joystick
        t[0x70] = ::rand2
        t[0x72] = ::eventPop
        t[0x74] = ::shiftFlags
        t[0x76] = ::systemQuery
        t[0x78] = ::cardCode
        t[0x7A] = { machine.dx = host.keyWait() }
        t[0x7C] = ::strLeft; t[0x7E] = ::strRight; t[0x80] = ::strMid
        t[0x82] = ::strlen; t[0x84] = ::strFirst; t[0x86] = ::intToStr
        t[0x88] = ::strcat
        t[0x8A] = { strcmpOp { it == 0 } }
        t[0x8C] = { strcmpOp { it != 0 } }
        t[0x8E] = { strcmpOp { it <= 0 } }
        t[0x90] = { strcmpOp { it >= 0 } }
        t[0x92] = { strcmpOp { it < 0 } }
        t[0x94] = { strcmpOp { it > 0 } }
        t[0x96] = ::strPush; t[0x98] = ::strPop; t[0x9A] = ::strToInt
        t[0x9C] = ::fileExists; t[0x9E] = ::chr
        t[0xA0] = ::selectDrive
        t[0xA2] = { machine.dx = machine.gb(0x0CC6) }
        t[0xA4] = {
            machine.ax = host.cpuSpeed()
            machine.dx = machine.ax
        }
        t[0xA6] = { machine.dx = 0 }
        t[0xA8] = {
            machine.ax = machine.g(0x0C9D)
            machine.dx = machine.ax
        }
    }

    /** The opcodes with a handler (83 of them). */
    fun opcodes(): List<Int> = (0 until 256).filter { handlers[it] != null }

    companion object {
        /** The expression push stack, `ss:277c` downward. */
        const val PUSH_TOP = 0x277C

        private val DECIMAL_PLACES = intArrayOf(10000, 1000, 100, 10, 1)

        private val STORE_TARGETS: Map<Int, Target> = mapOf(
            0x06 to Target(Where.LOC, 'b'), 0x08 to Target(Where.LOC, 'w'), 0x0A to Target(Where.LOC, 's'),
            0x0E to Target(Where.LOC_ARR, 'b'), 0x10 to Target(Where.LOC_ARR, 'w'), 0x0C to Target(Where.LOC_ARR, 's'),
            0x12 to Target(Where.LOC, 'b', false), 0x14 to Target(Where.LOC, 'w', false),
            0x16 to Target(Where.LOC, 's', false),
            0x1A to Target(Where.LOC_ARR, 'b', false), 0x1C to Target(Where.LOC_ARR, 'w', false),
            0x18 to Target(Where.LOC_ARR, 's', false),
            0x1E to Target(Where.GLOB, 'b'), 0x20 to Target(Where.GLOB, 'w'), 0x22 to Target(Where.GLOB, 's'),
            0x26 to Target(Where.GLOB_ARR, 'b'), 0x28 to Target(Where.GLOB_ARR, 'w'),
            0x24 to Target(Where.GLOB_ARR, 's'),
            0x2A to Target(Where.FRAME, 'b'), 0x2C to Target(Where.FRAME, 'w'), 0x2E to Target(Where.FRAME, 's'),
            0x32 to Target(Where.FRAME_ARR, 'b'), 0x34 to Target(Where.FRAME_ARR, 'w'),
            0x30 to Target(Where.FRAME_ARR, 's'),
        )

        fun lcg(ax: Int): Int = (ax * 0x7AB7 + 0xF881) and 0xFFFF

        internal fun hex2(v: Int) = (v and 0xFF).toString(16).padStart(2, '0')

        internal fun hex4(v: Int) = (v and 0xFFFF).toString(16).padStart(4, '0')
    }
}
