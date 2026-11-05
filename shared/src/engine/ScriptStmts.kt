package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Machine.Companion.CS
import dev.pelotasplus.ishar.engine.Machine.Companion.s16
import dev.pelotasplus.ishar.engine.ScriptExpr.Companion.PUSH_TOP

/**
 * Statement handlers (`script_stmt_table` `cs:0024` and the
 * object/view statements of the reference engine): flow, frames, events, locals,
 * text, input, steering, the sound statements' state, assets, objects and views.
 *
 * Collision, file and palette statements are installed by their own components.
 */
class ScriptStmts(private val vm: Scheduler) {
    private val m = vm.machine
    private val SS = m.ss
    private val build = m.build
    private val x = vm.expr

    fun install() {
        val t = vm.stmts
        for (op in RET_OPS) t[op] = {}
        // cs:0022, a ret in every build; only Arborea's combat.io (065e) runs it
        if (build == GameBuild.ARBOREA) t[0x04] = {}
        t[0x05] = ::call8; t[0x06] = ::call16; t[0x07] = ::call16p
        t[0x08] = ::jump8; t[0x09] = ::jump16; t[0x0A] = ::jump16p
        t[0x11] = ::ret
        t[0x12] = { cond(m.dx == 0, 1) }; t[0x13] = { cond(m.dx == 0, 2) }; t[0x14] = { cond(m.dx == 0, 3) }
        t[0x15] = { cond(m.dx != 0, 1) }; t[0x16] = { cond(m.dx != 0, 2) }; t[0x17] = { cond(m.dx != 0, 3) }
        t[0x18] = { cond(m.dx == m.cx, 1) }; t[0x19] = { cond(m.dx == m.cx, 2) }
        t[0x1A] = { cond(m.dx == m.cx, 3) }
        t[0x1B] = { cond(m.dx != m.cx, 1) }; t[0x1C] = { cond(m.dx != m.cx, 2) }
        t[0x1D] = { cond(m.dx != m.cx, 3) }
        t[0x1E] = ::set; t[0x1F] = ::eval; t[0x20] = ::add; t[0x21] = ::sub
        t[0x24] = ::printNumberText; t[0x25] = ::printInline; t[0x26] = ::printString
        t[0x27] = ::textCursorXy; t[0x28] = ::textCursorColumn
        t[0x29] = ::declareArray; t[0x2A] = ::randomSeed
        t[0x2B] = { djnz(1) }; t[0x2C] = { djnz(2) }; t[0x2D] = { djnz(3) }
        t[0x2E] = ::select; t[0x2F] = ::switch
        t[0x30] = { setResume(rel8(m.lodsb()) and 0xFFFF) }
        t[0x31] = { setResume(m.lodsw()) }
        t[0x32] = ::resume16p; t[0x33] = ::returnResume
        t[0x34] = ::diskCheckArm; t[0x35] = ::frameStop
        t[0x38] = ::objectMoveBy; t[0x39] = ::objectMoveTo; t[0x3A] = { m.sgb(0x0C37, 0) }
        t[0x3D] = ::unloadAsset; t[0x3E] = ::frameActivate; t[0x3F] = ::stop
        t[0x40] = ::frameNew; t[0x41] = ::frameDelete; t[0x42] = { throw LeaveException }
        t[0x43] = ::yieldOrReturn; t[0x44] = ::frameDeleteSelf; t[0x45] = ::loadAsset
        t[0x46] = ::declareView; t[0x47] = ::selectView
        t[0x48] = { placeCommon(main = false, mirrorFlip = false, atOrigin = true) }
        t[0x49] = { placeCommon(main = false, mirrorFlip = false, atOrigin = false) }
        t[0x4A] = { Objects.deleteViewObjects(m) }
        t[0x4B] = { x.step(); deleteObject(m.dx) }
        t[0x4C] = ::setPosition; t[0x4D] = ::movePosition
        t[0x4E] = ::showView; t[0x4F] = ::hideView
        t[0x50] = { Objects.deleteAllObjects(m) }
        t[0x51] = ::setShape; t[0x52] = { m.ww(m.es, m.bp - 0x1A, 0xFFFF) }
        t[0x57] = ::resultNext; t[0x58] = ::resultPrev; t[0x5F] = ::setDirection
        t[0x60] = ::resultFirst; t[0x61] = ::postEvent
        t[0x62] = { m.wb(m.es, m.bp - 0x24, m.rb(m.es, m.bp - 0x24) and 0xFE) }
        t[0x63] = {
            m.wb(m.es, m.bp - 0x24, m.rb(m.es, m.bp - 0x24) or 1)
            eventsFlush()
        }
        t[0x64] = { m.wb(m.es, m.bp - 0x24, m.rb(m.es, m.bp - 0x24) and 0xFD) }
        t[0x65] = { m.wb(m.es, m.bp - 0x24, m.rb(m.es, m.bp - 0x24) or 2) }
        t[0x66] = ::eventsFlush; t[0x67] = { m.sgb(0x0C96, 1) }
        t[0x6A] = {
            x.step()
            // Arborea's 599c: cmp dx,2 / jg / mov dx,2 (signed): a frame waits at least 2 ticks
            if (build == GameBuild.ARBOREA && m.dx.toShort() <= 2) m.dx = 2
            m.sgb(0x0C99, m.dx)
        }
        t[0x6B] = ::soundTone; t[0x6C] = ::soundSampleHit; t[0x6D] = ::soundDecay
        t[0x6E] = { repeat(4) { x.step() } }
        t[0x6F] = ::setLocalBytes
        t[0x79] = ::plotPixel; t[0x7A] = ::nopEval2; t[0x7B] = ::nopEval2
        t[0x7C] = ::fillBoxRel
        t[0x7D] = { x.step(); m.sgb(0x0B9C, m.dx) }
        t[0x7E] = ::cursorSet; t[0x7F] = ::cursorMove
        t[0x80] = { x.step(); m.sgb(0x0B9D, m.dx) }
        t[0x81] = { m.sgb(0x0C15, 0); x.step() }
        t[0x82] = { x.step(); x.step() }
        t[0x83] = { x.step(); m.ww(m.es, m.bp - 0x2A, m.dx) }
        t[0x84] = ::pointerShow; t[0x85] = ::pointerHide; t[0x86] = ::mouseRead
        t[0x87] = ::pointerSprite; t[0x88] = ::mouseSetPosition
        t[0x89] = ::dirTableData; t[0x8A] = { m.ww(m.es, m.bp - 0x20, m.lodsw()) }
        t[0x8C] = { steer(toward = true) }; t[0x8D] = { steer(toward = false) }
        t[0x90] = ::stepPosition; t[0x91] = ::setLocal5
        t[0x92] = { m.ww(m.es, m.bp - 0x22, m.lodsw()) }
        t[0x94] = ::frameFirstOfAsset; t[0x95] = { soundSeqPlay(false) }
        t[0x96] = ::soundSeqCtl; t[0x97] = ::soundSet86c8; t[0x98] = ::soundParam
        t[0x99] = { m.wb(m.es, m.bp - 3, m.rb(m.es, m.bp - 3) xor 1) }
        t[0x9A] = { m.wb(m.es, m.bp - 3, 1) }; t[0x9B] = { m.wb(m.es, m.bp - 3, 0) }
        t[0x9C] = ::frameFirst; t[0x9D] = { samplePlay(false) }; t[0x9E] = { samplePlay(true) }
        t[0x9F] = { m.wb(m.es, m.bp - 0x25, 0) }; t[0xA0] = { m.wb(m.es, m.bp - 0x25, 0xFF) }
        t[0xA1] = { vm.sound.stop() }
        t[0xA6] = { placeCommon(main = false, mirrorFlip = true, atOrigin = true) }
        t[0xA7] = { placeCommon(main = false, mirrorFlip = true, atOrigin = false) }
        t[0xA8] = { placeCommon(main = true, mirrorFlip = false, atOrigin = true) }
        t[0xA9] = { placeCommon(main = true, mirrorFlip = false, atOrigin = false) }
        // aah: `xor bx,1` on the wrong register, so no mirror flip
        t[0xAA] = { placeCommon(main = true, mirrorFlip = false, atOrigin = true) }
        t[0xAB] = { placeCommon(main = true, mirrorFlip = true, atOrigin = false) }
        t[0xAC] = { soundSeqPlay(true) }
        t[0xAD] = ::setShape; t[0xAE] = ::nopEval3; t[0xAF] = ::storeRegs3
        t[0xB0] = ::inputNumber; t[0xB1] = ::inputLine
        t[0xB6] = { textAdvance(); x.step(); printDecimal(m.dx) }
        t[0xB7] = { textAdvance(); x.stepSwapped(); printBuffer(m.g(0x0C58)) }
        t[0xB8] = { m.sgb(0x0BAC, 2); x.step(); printDecimal(m.dx) }
        t[0xB9] = { m.sgb(0x0BAC, 2); x.stepSwapped(); printBuffer(m.g(0x0C58)) }
        t[0xBA] = ::fontSet; t[0xBB] = { x.step(); m.sgb(0x0B9F, m.dx) }
        t[0xBD] = ::nopEval2; t[0xBF] = ::textAt
        t[0xC0] = ::viewportOrigin; t[0xC1] = { viewportClip(0x0E, 0x10) }; t[0xC2] = { viewportClip(0x12, 0x14) }
        t[0xC3] = { matrixRow(0x20, cross = false) }; t[0xC4] = { matrixRow(0x23, cross = true) }
        t[0xC5] = ::viewportScale; t[0xC6] = ::viewportShift
        t[0xC7] = ::objectScaleSet; t[0xCA] = ::framesFindKind
        t[0xCD] = { soundSlotSet(false) }; t[0xCE] = { soundSlotSet(true) }
        t[0xCF] = { x.step(); m.wb(m.es, m.bp - 0x2B, m.dx) }
        t[0xD0] = ::objectAnimSet; t[0xD1] = ::viewportFullRedraw; t[0xD2] = ::setCs5d0c
        t[0xD5] = { x.step() }; t[0xD6] = ::setFrame2f; t[0xD8] = ::nopEval2; t[0xD9] = { x.step() }
        t[0xDA] = { x.swapStrings() }
        t[0xDB] = { Objects.deleteViewObjects(m, now = true) }
        t[0xDC] = {
            m.sgb(0x0BF3, 1)
            Objects.deleteAllObjects(m)
        }
        t[0xDD] = { x.step(); Objects.deleteObject(m, m.dx, now = true) }
        t[0xDE] = {
            m.sgb(0x0BF3, 1)
            frameDelete()
        }
        t[0xDF] = ::set1da5Block; t[0xE0] = ::set1dd7Block
        t[0xE1] = {
            m.dx = m.rw(SS, m.g(0x0C80) - 2 + 0x100)
            x.store()
        }
        t[0xE2] = ::spriteFree
        if (build == GameBuild.ISHAR3) installIshar3()
    }

    /**
     * Ishar 3's statements that Ishar 1/2 lack or treat as no-ops, with the operands its 32-bit handlers read
     * (start.exe table at 119aah; file offset = address + 4827h). E evaluates an
     * expression, S stores 0 (the engine's result is not worked out yet), W is a variable reference: a word, and
     * a second one when it is 0 (43fa). Only the operands are right: what each one does is still to come.
     */
    private fun installIshar3() {
        val t = vm.stmts
        for ((op, sig) in ISHAR3_STUBS) t[op] = { operands(sig) }
        t[0xE9] = ::allocPointerArray
        // 36 E: the frame runs every E timer ticks (785a sets -3ah; the scheduler 33e6 waits until [f612] passes
        // -3eh). The timer is ~70 Hz (one tick a retrace, else PIT 428bh); this engine's passes are 19.2 a second,
        // and a frame's period here is the reload at -2 of the countdown at -1.
        t[0x36] = {
            eval()
            val passes = ((m.dx * 192 + 350) / 700).coerceIn(1, 255)
            m.wb(m.es, m.bp - 2, passes)
            m.wb(m.es, m.bp - 1, passes)
        }
        // 01 xx: a second table (11daah); xx 1..4 set or clear the flags at f659/f658, 5 does nothing
        t[0x01] = { m.lodsb() }
        // f6 E E E S: open FLI number E2 of the frame's asset, E3 timer ticks a frame, and store its frame count
        // (start.exe 8075). b5 E S S: E frames on, and store the frame reached and 0 (8004h at the end) (80bc).
        t[0xF6] = {
            eval()
            eval()
            val index = m.dx
            eval()
            val delay = m.dx
            val (aofs, aseg) = m.far(m.es, m.bp - 0x14)
            fli.open((aseg shl 4) + aofs, index, delay)
            m.dx = fli.frames
            x.store()
        }
        t[0xB5] = {
            eval()
            val n = maxOf(m.dx, 1)
            val more = fli.step(n, Machine.PAGE, m.screen)
            // the player waits its delay between frames (d7f3); here the frame sleeps that long instead
            m.wb(m.es, m.bp - 2, ((fli.delay * n * 192 + 350) / 700).coerceIn(1, 255))
            m.dx = fli.frame
            x.store()
            m.dx = if (more) 0 else 0x8004
            x.store()
        }
    }

    /** Ishar 3's cinematic player (f6h, b5h). */
    private val fli = Fli(m)

    private fun operands(sig: String) {
        for (c in sig) when (c) {
            'E' -> eval()
            'S' -> {
                m.dx = 0
                x.store()
            }
            'W' -> if (m.lodsw() == 0) m.lodsw()
            'b' -> m.lodsb()
            'w' -> m.lodsw()
        }
    }

    // --- Calls and jumps (26fe..2914) ---------------------------------------

    private fun framePushSi() {
        val es = m.es
        val bp = m.bp
        val di = (m.rw(es, bp - 0x0A) - 2) and 0xFFFF
        m.ww(es, bp - 0x0A, di)
        m.ww(es, bp + di, m.si)
        m.di = di
    }

    private fun call8() {
        val r = rel8(m.lodsb())
        framePushSi()
        m.si = (m.si + r) and 0xFFFF
    }

    private fun call16() {
        val r = m.lodsw()
        framePushSi()
        m.si = (m.si + r) and 0xFFFF
    }

    private fun call16p() {
        val r = m.lodsw()
        m.si = (m.si + 1) and 0xFFFF
        framePushSi()
        m.si = (m.si + r) and 0xFFFF
    }

    private fun jump8() {
        val r = rel8(m.lodsb())
        m.si = (m.si + r) and 0xFFFF
    }

    private fun jump16() {
        val r = m.lodsw()
        m.si = (m.si + r) and 0xFFFF
    }

    private fun jump16p() {
        val r = m.lodsw()
        m.si = (m.si + 1 + r) and 0xFFFF
    }

    /** `2808`. */
    private fun ret() {
        val es = m.es
        val bp = m.bp
        if (m.gb(0x0C19) == 0 && s16(m.rw(es, bp - 0x0A)) >= s16(m.g(0x0C56))) throw LeaveException
        val di = m.rw(es, bp - 0x0A)
        m.di = di
        m.si = m.rw(es, bp + di)
        if (di == m.rw(es, bp - 0x0C)) m.ww(es, bp - 0x0C, 0)
        m.ww(es, bp - 0x0A, di + 2)
    }

    private fun cond(take: Boolean, size: Int) {
        if (take) {
            val r = if (size == 1) {
                rel8(m.lodsb())
            } else {
                m.lodsw().also { if (size == 3) m.si = (m.si + 1) and 0xFFFF }
            }
            m.si = (m.si + r) and 0xFFFF
        } else {
            m.si = (m.si + size) and 0xFFFF
        }
    }

    private fun set() {
        m.bx = PUSH_TOP
        x.stepKeepBx()
        x.store()
    }

    private fun eval() {
        m.bx = PUSH_TOP
        x.stepKeepBx()
    }

    private fun add() {
        m.bx = PUSH_TOP
        x.stepKeepBx()
        x.addStore()
    }

    private fun sub() {
        m.bx = PUSH_TOP
        x.stepKeepBx()
        m.dx = (-m.dx) and 0xFFFF
        x.addStore()
    }

    private fun djnz(size: Int) {
        m.sg(0x0C54, m.si)
        m.si = (m.si + size) and 0xFFFF
        m.dx = 0xFFFF
        if (x.addStore() != 0) {
            m.si = m.g(0x0C54)
            cond(true, size)
        }
    }

    // --- Resume points (2744..2801) -----------------------------------------

    private fun setResume(ax: Int) {
        val es = m.es
        val bp = m.bp
        if (m.gb(0x0C19) == 0) {
            if (m.rw(es, bp - 0x0C) == 0) {
                val di = (m.g(0x0C56) - 2) and 0xFFFF
                m.ww(es, bp - 0x0C, di)
                m.sg(0x0C56, di)
                m.cx = m.rw(es, bp - 8)
                m.ww(es, bp + di, m.cx)
                m.di = di
            } else {
                m.cx = m.rw(es, bp - 0x0C)
                m.sg(0x0C56, m.cx)
            }
            m.ww(es, bp - 8, ax + m.si)
            m.wb(es, bp - 4, 1)
            m.wb(es, bp - 1, 1)
        } else {
            val di: Int
            if (m.rw(es, bp - 0x0C) == 0) {
                di = (m.rw(es, bp - 0x0A) - 2) and 0xFFFF
                m.ww(es, bp - 0x0A, di)
                m.ww(es, bp - 0x0C, di)
                m.ww(es, bp + di, m.si)
            } else {
                di = m.rw(es, bp - 0x0C)
                m.ww(es, bp - 0x0A, di)
            }
            m.di = di
            m.si = (m.si + ax) and 0xFFFF
        }
    }

    private fun resume16p() {
        val ax = m.lodsw()
        m.si = (m.si + 1) and 0xFFFF
        setResume(ax)
    }

    private fun returnResume() {
        val es = m.es
        val bp = m.bp
        val di = m.rw(es, bp - 0x0C)
        if (di == 0) return ret()
        m.ww(es, bp - 0x0C, 0)
        if (m.gb(0x0C19) == 0) {
            m.cx = m.rw(es, bp + di)
            m.ww(es, bp - 8, m.cx)
            m.sg(0x0C56, di + 2)
        } else {
            m.si = m.rw(es, bp + di)
            m.ww(es, bp - 0x0A, di + 2)
        }
        m.di = (di + 2) and 0xFFFF
    }

    // --- Frames (2ce2.., 5798..) --------------------------------------------

    private fun frameNew() {
        m.sg(0x0C6A, 0)
        m.sg(0x0C6C, 0)
        m.sg(0x0C6E, 0)
        frameNewCommon()
    }

    /** Also used by `3ch` (frame_new_at_box). */
    fun frameNewCommon() {
        if (build == GameBuild.ISHAR3) loadDeferred(m.rw(m.ds, m.si))
        val aid = m.lodsw()
        val found = Assets.assetFindLoaded(m, aid)
        if (found == null) {
            m.dx = 0xFFFF
            x.store()
            return
        }
        val (aseg, aofs) = found
        val es = m.es
        val bp = m.bp
        val bx = Objects.frameSlotTake(m, aseg, aofs)
        m.dx = m.g(0x0B06)
        for (o in intArrayOf(0, 2, 4)) m.ww(es, bx + o, m.rw(es, bp + o))
        for (o in intArrayOf(9, 10, 11)) m.wb(es, bx + o, m.rb(es, bp + o))
        m.ww(es, bx - 0x28, m.rw(es, bp - 0x0E))
        m.ww(es, bx, m.rw(es, bx) + m.g(0x0C6A))
        m.ww(es, bx + 2, m.rw(es, bx + 2) + m.g(0x0C6C))
        m.ww(es, bx + 4, m.rw(es, bx + 4) + m.g(0x0C6E))
        m.ww(es, bx - 0x16, m.rw(es, bp - 0x16))
        m.ww(es, bx - 0x22, m.rw(es, bp - 0x22))
        m.ww(es, bx - 0x2A, m.rw(es, bx - 0x0E))
        x.store()
    }

    private fun frameDelete() {
        x.stepKeepBx()
        if (m.dx != 0 && (m.dx and 0x8000) == 0) {
            m.ax = m.dx
            m.sg(0x0B06, m.dx)
            Objects.frameDestroy(m)
        }
    }

    private fun yieldOrReturn() {
        if (m.gb(0x0C19) == 0) throw LeaveException
        ret()
        throw LeaveException
    }

    private fun frameDeleteSelf() {
        if (m.g(0x0B02) == 0) throw ProgramEndException()
        m.sg(0x0B06, m.g(0x0B02))
        Objects.frameDestroy(m)
    }

    private fun frameByHandle(): Int = m.rw(m.es, m.g(0x0BD2) + m.dx)

    private fun frameStop() {
        x.step()
        if ((m.dx and 0x8000) == 0) {
            m.bx = m.dx
            m.wb(m.es, frameByHandle() - 4, 0)
        }
    }

    private fun stop() {
        m.wb(m.es, m.bp - 4, 0)
        if (m.gb(0x0C19) != 0) throw LeaveException
    }

    private fun frameActivate() {
        x.step()
        if ((m.dx and 0x8000) == 0) {
            m.bx = m.dx
            m.wb(m.es, frameByHandle() - 4, 1)
        }
    }

    private fun eventsFlush() {
        val es = m.es
        val bp = m.bp
        m.ww(es, bp - 0x1E, m.rw(es, bp - 0x1C))
        m.wb(es, bp - 0x24, m.rb(es, bp - 0x24) and 0x7F)
    }

    /** `57d9`: N+1 words into another frame's event queue (below its header). */
    private fun postEvent() {
        var n = m.lodsb() + 1
        x.step()
        val es = m.es
        var di = 0
        if (m.dx != 0xFFFF) di = m.rw(es, m.g(0x0BD2) + m.dx)
        if (di == 0 || (m.rb(es, di - 0x24) and 1) != 0) {
            repeat(n) { x.step() }
            return
        }
        var bx = m.rw(es, di - 0x1C)
        val (aofs, aseg) = m.far(es, di - 0x14)
        val qsize = m.rw(aseg, aofs + 0x16)
        while (n != 0) {
            x.step()
            m.sg(0x0B14, bx)
            bx = (bx + 2) and 0xFFFF
            if (s16(bx) >= -0x34) bx = (bx - qsize) and 0xFFFF
            if (bx == m.rw(es, di - 0x1E)) {
                m.ww(es, di - 0x1C, bx)
                repeat(n - 1) { x.step() }
                return
            }
            m.ww(es, m.g(0x0B14) + di, m.dx)
            n--
        }
        m.ww(es, di - 0x1C, bx)
        m.wb(es, di - 0x24, m.rb(es, di - 0x24) or 0x80)
    }

    private fun setLocalBytes() {
        val di = (m.lodsw() + m.bp) and 0xFFFF
        val n = m.lodsw() - 1
        for (i in 0 until n) m.wb(m.es, di + i, m.lodsb())
    }

    private fun randomSeed() {
        x.dispatch()
        val ax = if (m.dx != 0) m.dx else m.rw(0x40, 0x6C)
        m.sg(0x0C4E, ax)
    }

    // --- Result lists (4b8f.., 510f..) --------------------------------------

    fun resultNext() {
        val bx = m.g(0x0C80)
        m.dx = m.rw(SS, bx)
        if ((m.dx and 0x8000) == 0) m.sg(0x0C80, bx + 2)
        x.store()
    }

    private fun resultPrev() {
        val bx = m.g(0x0C80)
        if (bx == RESULTS) {
            m.dx = 0xFFFF
        } else {
            m.dx = m.rw(SS, bx - 2)
            m.sg(0x0C80, bx - 2)
        }
        x.store()
    }

    fun resultFirst() {
        m.sg(0x0C80, RESULTS)
        resultNext()
    }

    private fun framesWhere(test: (Int) -> Boolean) {
        val es = m.es
        val tbl = m.g(0x0BD2)
        var cx = RESULTS
        var bx = 0
        while (true) {
            val di = m.rw(es, bx + tbl)
            if (test(di) && m.bp != di) {
                m.ww(SS, cx + 0x100, 0)
                m.ww(SS, cx, bx)
                cx += 2
                if (m.gb(0x0C96) == 0) break
            }
            bx = m.rw(es, bx + tbl + 4)
            if (bx == 0) break
        }
        m.ww(SS, cx, 0xFFFF)
        m.sgb(0x0C96, 0)
        resultFirst()
    }

    private fun frameFirstOfAsset() {
        val aid = m.lodsw()
        framesWhere { m.rw(m.es, it - 0x10) == aid }
    }

    private fun framesFindKind() {
        x.step()
        val k = m.dx and 0xFF
        framesWhere { m.rb(m.es, it + 0x0C) == k }
    }

    private fun frameFirst() {
        val es = m.es
        val tbl = m.g(0x0BD2)
        var di = RESULTS
        var bx = 0
        while (true) {
            bx = m.rw(es, bx + tbl + 4)
            if (bx == 0) break
            m.ww(SS, di + 0x100, 0)
            m.ww(SS, di, bx)
            di += 2
        }
        m.ww(SS, di, 0xFFFF)
        m.sgb(0x0C96, 0)
        resultFirst()
    }

    // --- Locals -------------------------------------------------------------

    /**
     * `29h w:local b:count b:type dims`. Ishar 3 puts two flags in the count: 80h the dims are 32-bit,
     * 40h a 32-bit byte size follows them (`29 60 02 41 01 39 00 42 12 00 00`: 57 rows, 4,674 bytes). Ishar 1
     * and 2 never set them (their counts are 0..2). The header keeps the count and 16-bit dims as before.
     */
    private fun declareArray() {
        val es = m.es
        val bp = m.bp
        var di = (m.lodsw() - 1) and 0xFFFF
        val raw = m.lodsb()
        val n = raw and 0x3F
        m.wb(es, bp + di, n)
        di--
        m.wb(es, bp + di, m.lodsb())
        repeat(n) {
            di -= 2
            m.ww(es, bp + di, m.lodsw())
            if ((raw and 0x80) != 0) m.lodsw() // the dim's high word
        }
        if ((raw and 0x40) != 0) { // the byte size, which the 16-bit engine works out itself
            val size = m.lodsw() or (m.lodsw() shl 16)
            // Bit 7 (Ishar 3, start.exe 77b4): a pointer array. Its elements live where e9h puts them.
            if ((raw and 0x80) != 0) pointerArraySizes[(es shl 4) + ((bp + di + n * 2 + 2) and 0xFFFF)] = size
        }
    }

    /** Ishar 3's pointer arrays declared so far: linear address of the variable -> its byte size. */
    private val pointerArraySizes = mutableMapOf<Int, Int>()

    /**
     * `e9h W`: gives a pointer array its elements (start.exe 439e: [43fa] the variable, then 1b48 allocates the
     * size + 800h). Here they go to the room Boot keeps after the frame memory, in the same segment.
     */
    private fun allocPointerArray() {
        var base = m.bp
        var ofs = m.lodsw()
        if (ofs == 0) {
            base = m.g(0x0BF6)
            ofs = m.lodsw()
        }
        val key = (m.es shl 4) + ((base + ofs) and 0xFFFF)
        val size = pointerArraySizes[key] ?: return
        if (key in m.pointerArrays) return
        val at = m.pointerArrayNext
        if (at + size + 0x800 > m.pointerArrayEnd) throw UnsupportedEngineOperationException("e9h: no room for a $size-byte array")
        for (i in 0 until size) m.wb(m.es, at + i, 0)
        m.pointerArrays[key] = at
        m.pointerArrayNext = at + size + 0x800
    }

    /** `2eh`: no default word; the default code starts right after the value pairs (`5738`). */
    private fun select() {
        val cx = m.cx
        x.step()
        var n = m.lodsb() + 1
        if ((m.si and 1) != 0) m.si = (m.si + 1) and 0xFFFF
        val v = s16(m.dx)
        while (n != 0) {
            val k = m.rsw(m.ds, m.si)
            if (v == k) {
                m.si = (m.si + 2) and 0xFFFF
                m.si = (m.si + m.rw(m.ds, m.si) + 2) and 0xFFFF
                break
            }
            if (v < k) {
                m.si = (m.si + 4 * n) and 0xFFFF
                break
            }
            m.si = (m.si + 4) and 0xFFFF
            n--
        }
        m.cx = cx
    }

    /** `2fh`: leaves `DX` (the table index, doubled when taken). */
    private fun switch() {
        val cx = m.cx
        x.step()
        val n = m.lodsb()
        if ((m.si and 1) != 0) m.si = (m.si + 1) and 0xFFFF
        m.dx = (m.dx + m.rw(m.ds, m.si)) and 0xFFFF
        if ((m.dx and 0x8000) == 0 && m.dx <= n) {
            m.si = (m.si + 2) and 0xFFFF
            m.dx = (m.dx * 2) and 0xFFFF
            m.si = (m.si + m.dx) and 0xFFFF
            m.si = (m.si + m.rw(m.ds, m.si) + 2) and 0xFFFF
        } else {
            m.si = (m.si + 4 + 2 * n) and 0xFFFF
        }
        m.cx = cx
    }

    private fun setShape() {
        x.step()
        m.ww(m.es, m.bp - 0x1A, m.dx)
    }

    private fun setPosition() {
        for (o in intArrayOf(0, 2, 4)) {
            x.step()
            m.ww(m.es, m.bp + o, m.dx)
        }
    }

    private fun movePosition() {
        for (o in intArrayOf(0, 2, 4)) {
            x.step()
            m.ww(m.es, m.bp + o, m.rw(m.es, m.bp + o) + m.dx)
        }
    }

    private fun stepPosition() {
        val es = m.es
        val bp = m.bp
        for ((o, v) in arrayOf(0 to 9, 2 to 10, 4 to 11)) {
            m.ww(es, bp + o, m.rw(es, bp + o) + m.rsb(es, bp + v))
        }
    }

    private fun setDirection() {
        x.step()
        val es = m.es
        val bp = m.bp
        val seg: Int
        val si: Int
        if (m.rw(es, bp - 0x20) != 0) {
            val (aofs, aseg) = m.far(es, bp - 0x14)
            seg = aseg
            si = (aofs + m.rw(es, bp - 0x20) + 1) and 0xFFFF
        } else {
            seg = SS
            si = 0x6867
        }
        val bx = (m.dx * 3) and 0xFFFF
        m.bx = bx
        for (i in 0 until 3) m.wb(es, bp + 9 + i, m.rb(seg, si + bx + i))
    }

    private fun objectScaleSet() {
        x.step()
        m.wb(m.es, m.bp - 0x26, m.dx)
        x.step()
        m.wb(m.es, m.bp - 0x27, m.dx)
    }

    private fun objectAnimSet() {
        x.step()
        val d = m.dx and 0xFF
        val al = when {
            (d and 0x10) != 0 -> 4
            (d and 8) != 0 -> 3
            (d and 4) != 0 -> 2
            (d and 2) != 0 -> 1
            else -> 0
        }
        m.wb(m.es, m.bp - 0x2C, al)
        x.step()
        m.wb(m.es, m.bp - 0x2D, m.dx)
    }

    private fun setFrame2f() {
        x.step()
        m.wb(m.es, m.bp - 0x2F, m.dx)
        repeat(3) { x.step() }
    }

    /**
     * A fix to the original: frise.io's tick clears a red ATTACK tab (objects 8..12, view 42h, 7e69),
     * but while a hit shows its number the frame sits in view 0eh (6796..67e0, a yield), so a
     * cooldown ending then would leave the tab red for good.
     */
    private fun deleteObject(index: Int) {
        Objects.deleteObject(m, index)
        val view = m.bp - 0x16
        if (index !in 8..12 || m.rw(m.es, view) != 0x0E) return
        m.ww(m.es, view, 0x42)
        Objects.deleteObject(m, index)
        m.ww(m.es, view, 0x0E)
    }

    private fun selectView() {
        val ax = m.lodsw()
        if (ax != m.rw(m.es, m.bp - 0x16)) m.ww(m.es, m.bp - 0x16, ax)
    }

    // --- Text (296b..2bfb) --------------------------------------------------

    private fun fontSet() {
        x.step(); m.sg(0x0BA8, m.dx)
        x.step(); m.sg(0x0BA0, m.dx)
        x.step(); m.sgb(0x0BA2, m.dx)
        x.step(); m.sgb(0x0BA4, m.dx)
        x.step(); m.sg(0x0BA6, m.dx)
    }

    private fun textAt() {
        x.step(); m.sg(0x0C02, m.dx)
        x.step(); m.sg(0x0C04, m.dx)
        x.step(); m.sg(0x0C06, m.dx)
        x.step(); m.sgb(0x0C0E, m.dx)
    }

    private fun textAdvance() = m.sgb(0x0BAC, 1)

    private fun printDecimal(v: Int) {
        m.ax = v
        var di = m.g(0x0C58)
        x.formatDecimal(v, di)
        while (true) {
            val c = m.rb(SS, di)
            di++
            if (c == 0) return
            printChar(c)
        }
    }

    private fun printBuffer(start: Int) {
        val saved = intArrayOf(m.ax, m.bx, m.cx, m.dx, m.di, m.si, m.ds, m.es, m.bp)
        var bx = start
        while (true) {
            val c = m.rb(SS, bx)
            bx++
            if (c == 0) break
            printChar(c)
        }
        m.ax = saved[0]; m.bx = saved[1]; m.cx = saved[2]; m.dx = saved[3]; m.di = saved[4]
        m.si = saved[5]; m.ds = saved[6]; m.es = saved[7]; m.bp = saved[8]
    }

    /** Mode 1 advances the cursor; mode 2 places glyph objects from `main.io`; mode 0 is DOS text. */
    private fun printChar(al: Int) {
        val dx = m.dx
        when (m.gb(0x0BAC)) {
            1 -> when {
                al == 0x0A -> m.sg(0x0B9A, m.g(0x0B9A) + m.gb(0x0BA4))
                al == 0x0D -> {
                    m.sg(0x0B98, m.g(0x0BA2) ushr 1)
                    m.sg(0x0B9A, m.g(0x0B9A) + m.gb(0x0BA4))
                }
                al == 0x20 || (m.g(0x0BA0) and 0x8000) == 0 -> {
                    if (al != 0x20) {
                        val d = (al - m.g(0x0BA8)) and 0xFFFF
                        if ((d and 0x8000) == 0 && d < m.g(0x0BA6)) {
                            m.sgb(0x0C15, 1)
                            m.sgb(0x0C12, 0)
                        }
                    }
                    m.sg(0x0B98, m.g(0x0B98) + m.gb(0x0BA2))
                }
            }
            2 -> when {
                al == 0x0A -> m.sg(0x0C06, m.g(0x0C06) - m.gb(0x0BA4))
                al == 0x0D -> m.sg(0x0C02, 0)
                al == 0x20 || (m.g(0x0BA0) and 0x8000) == 0 -> {
                    if (al != 0x20) {
                        val d = (al - m.g(0x0BA8)) and 0xFFFF
                        if ((d and 0x8000) == 0 && d < m.g(0x0BA6)) {
                            m.dx = (d + m.g(0x0BA0)) and 0xFFFF
                            m.sgb(0x0C15, 1)
                            m.sgb(0x0C12, 0)
                            m.sgb(0x0C14, 1)
                            Objects.placeEntry(m)
                        }
                    }
                    m.sg(0x0C02, m.g(0x0C02) + m.gb(0x0BA2))
                }
            }
            // 0f92: INT 21h 02h. In mode 13h the characters go to the BIOS teletype; kept as text.
            0 -> vm.console.add(al)
        }
        m.dx = dx
    }

    private fun printNumberText() {
        m.sgb(0x0BAC, 0)
        x.step()
        printDecimal(m.dx)
    }

    private fun printInline() {
        m.sgb(0x0BAC, 0)
        while (true) {
            val al = m.lodsb()
            if (al == 0) return
            printChar(al)
        }
    }

    private fun printString() {
        m.sgb(0x0BAC, 0)
        x.step()
        printBuffer(m.g(0x0C58))
    }

    /** `27h`: `bios_cursor_set` `0f7c` (column in `0bc0`, row in `0bc1`). */
    private fun textCursorXy() {
        m.sgb(0x0BAC, 0)
        x.step()
        x.evalCx()
        m.ax = m.dx
        m.bx = m.cx
        m.sgb(0x0BC0, m.dx)
        m.sgb(0x0BC1, m.cx)
    }

    private fun textCursorColumn() {
        m.sgb(0x0BAC, 0)
        x.step()
        m.sgb(0x0BC0, m.dx)
        m.dx = (m.gb(0x0BC1) shl 8) or (m.dx and 0xFF)
    }

    // --- Input --------------------------------------------------------------

    private fun mouseRead() {
        var ax: Int
        var bx: Int
        val cx: Int
        if (m.gb(0x0CA3) != 0) {
            ax = 0xFFFF
            bx = 0xFFFF
            cx = 0
        } else {
            ax = m.rw(CS, build.mouse)
            bx = m.rw(CS, build.mouse + 2)
            cx = m.rw(CS, build.mouse + 4)
            if (m.gb(0x2896) != 1) ax = ax ushr 1
            if (m.gb(0x2896) == 0) {
                ax++
                bx++
            }
        }
        m.ax = ax
        m.bx = bx
        m.cx = cx
        m.dx = ax
        x.store()
        m.dx = bx
        x.store()
        m.dx = cx
        x.store()
    }

    /** `88h`: INT 33h 4; the driver moves its pointer but sends no event, so `cs:11bd/11bf` change with the next movement. */
    private fun mouseSetPosition() {
        x.step()
        x.evalCx()
        vm.mouseRequest = m.dx to m.cx
    }

    private fun inputText(digits: Boolean) {
        var bx = m.g(0x0C58)
        while (true) {
            var al: Int
            do {
                al = vm.keyWait()
            } while ((al and 0x80) != 0)
            if (al == 0x0D) {
                m.wb(SS, bx, 0)
                break
            }
            if (al == 8) {
                if (bx != m.g(0x0C58)) {
                    bx--
                    m.wb(SS, bx, 0)
                    for (c in intArrayOf(8, 0x20, 8)) printChar(c)
                }
                continue
            }
            if (digits && al != 0x2D && al !in 0x30..0x39) continue
            m.wb(SS, bx, al)
            bx++
            printChar(al)
        }
    }

    /** `b1h`: `input_number_read` `29d3` (the names are crossed over). */
    private fun inputLine() {
        m.sgb(0x0BAC, 0)
        val bx = m.bx
        inputText(false)
        m.bx = bx
        x.store()
    }

    private fun inputNumber() {
        m.sgb(0x0BAC, 0)
        val bx = m.bx
        inputText(true)
        x.strToInt()
        m.bx = bx
        x.store()
    }

    // --- Small setters (65c6.., 6789..) -------------------------------------

    private fun cursorSet() {
        x.step(); m.sg(0x0B98, m.dx)
        x.step(); m.sg(0x0B9A, m.dx)
    }

    private fun cursorMove() {
        x.step(); m.sg(0x0B98, m.g(0x0B98) + m.dx)
        x.step(); m.sg(0x0B9A, m.g(0x0B9A) + m.dx)
    }

    private fun nopEval2() {
        x.step()
        x.evalCx()
    }

    private fun nopEval3() {
        val bx = m.bx
        val cx = m.cx
        repeat(3) { x.step() }
        m.bx = bx
        m.cx = cx
    }

    private fun storeRegs3() {
        val bx = m.bx
        val cx = m.cx
        m.dx = m.ax
        x.store()
        m.dx = bx
        x.store()
        m.dx = cx
        x.store()
    }

    private fun dirTableData() {
        m.ax = 0
        val n = m.lodsb()
        m.si = (m.si + n * 3 + 3) and 0xFFFF
    }

    private fun setLocal5() {
        val di = (m.lodsw() + m.bp) and 0xFFFF
        for (i in 0 until 5) m.wb(m.es, di + i, m.lodsb())
    }

    private fun diskCheckArm() {
        x.step()
        m.bx = 0x0C0E
        m.sg(0x0C10, m.dx)
    }

    private fun viewportFullRedraw() {
        val vals = IntArray(6)
        for (i in 0 until 6) {
            x.step()
            vals[i] = m.dx
        }
        vals.forEachIndexed { i, v -> m.ww(CS, build.cs5d0c + 2 * i, v) }
        m.ww(CS, build.cs5d0c + 0x0C, 0)
        var di = m.rw(m.es, m.bp - 0x16)
        if ((di and 0x8000) == 0) {
            di = (di + m.g(0x0BF6)) and 0xFFFF
            var f = m.rb(m.es, di + 1) and 0xE7
            if (m.rw(CS, build.cs5d0c) != 0) f = f or 0x18
            m.wb(m.es, di + 1, f)
        }
    }

    private fun setCs5d0c() {
        for (i in 0 until 8) {
            x.step()
            m.ww(CS, build.cs5d0c + 2 * i, m.dx)
        }
    }

    private fun objectMoveBy() {
        x.step(); val a = m.dx
        x.step(); val b = m.dx
        x.step()
        val es = m.es
        val bx = (m.g(0x0BF6) + m.rw(es, m.bp - 0x16)) and 0xFFFF
        m.bx = bx
        m.ww(es, bx + 0x2E, m.dx)
        m.ww(es, bx + 0x2C, b)
        m.ww(es, bx + 0x2A, a)
        m.wb(es, bx, m.rb(es, bx) or 0x80)
    }

    private fun objectMoveTo() {
        x.step(); val a = m.dx
        x.step(); val b = m.dx
        x.step()
        val es = m.es
        val bx = (m.g(0x0BF6) + m.rw(es, m.bp - 0x16)) and 0xFFFF
        m.bx = bx
        m.ww(es, bx + 0x2E, m.dx - m.rw(es, bx + 0x1A))
        m.ww(es, bx + 0x2C, b - m.rw(es, bx + 0x18))
        m.ww(es, bx + 0x2A, a - m.rw(es, bx + 0x16))
        m.dx = (a - m.rw(es, bx + 0x16)) and 0xFFFF
        m.wb(es, bx, m.rb(es, bx) or 0x80)
    }

    /** `79h`: BIOS INT 10h 0ch in mode 13h, XOR when draw mode 3. */
    private fun plotPixel() {
        x.step(); m.sg(0x0B98, m.dx)
        x.step(); m.sg(0x0B9A, m.dx)
        val px = m.g(0x0B98)
        val py = m.g(0x0B9A)
        val c = m.gb(0x0B9C)
        if (px < 320 && py < 200) {
            val ofs = py * 320 + px
            m.wb(m.screen, ofs, if (m.gb(0x0B9D) == 3) m.rb(m.screen, ofs) xor c else c)
        }
    }

    /**
     * `7ch E:w E:h`: a solid box (the type 01 sprite at `ss:0b64` through the u32 at
     * `ss:0b60`) at the cursor, drawn and copied to the screen at once with object `[0bf0]`.
     */
    private fun fillBoxRel() {
        val es0 = m.es
        val di0 = m.di
        x.step()
        x.evalCx()
        val o = m.g(0x0BEA)
        val di = (m.g(0x0BE8) + m.g(0x0BF0)) and 0xFFFF
        m.ww(o, di + 0x0A, SS)
        m.ww(o, di + 8, 0x0B60)
        m.ww(o, di + 0x0C, m.g(0x0B98))
        m.ww(o, di + 0x0E, m.g(0x0B9A))
        m.ww(o, di + 0x10, 0)
        m.wb(o, di + 0x22, 0)
        var bl = m.gb(0x0B9C)
        if (m.gb(0x2896) == 0) bl = bl and 3
        m.sgb(0x0B65, bl)
        var dx = m.dx
        var cx = m.cx
        m.sg(0x0B98, m.g(0x0B98) + dx)
        m.sg(0x0B9A, m.g(0x0B9A) + cx)
        if ((dx and 0x8000) != 0) {
            m.ww(o, di + 0x0C, m.rw(o, di + 0x0C) + dx)
            dx = (-dx) and 0xFFFF
        }
        if ((cx and 0x8000) != 0) {
            m.ww(o, di + 0x0E, m.rw(o, di + 0x0E) + cx)
            cx = (-cx) and 0xFFFF
        }
        m.sg(0x0B66, dx)
        m.sg(0x0B68, cx)
        var ax = m.rw(o, di + 0x0C)
        val bx = m.rw(o, di + 0x0E)
        val x1 = ((dx + ax) or 0x0F) and 0xFFFF
        val y1 = (cx + bx) and 0xFFFF
        ax = ax and 0xFFF0
        for ((k, v) in arrayOf(
            0x0C1A to ax, 0x0C1C to bx, 0x0C1E to x1, 0x0C20 to y1,
            0x0C40 to ax, 0x0C42 to bx, 0x0C44 to x1, 0x0C46 to y1,
        )) {
            m.sg(k, v)
        }
        m.sg(0x0C22, (s16(x1 - ax + 1) shr 1) and 0xFFFF)
        vm.drawObject(di)
        vm.copyDirtyRect()
        m.es = es0
        m.di = di0
    }

    // --- The pointer (660d..66ad) -------------------------------------------

    private fun pointerShow() {
        if (m.gb(0x0CA3) != 0) return
        if ((m.gb(0x0CB1) and 0x80) != 0) {
            vm.pointerShow(m.g(0x0B84), m.g(0x0B86))
            m.sgb(0x0CB1, 1)
            m.sgb(0x0CB2, 0xFF)
        }
    }

    private fun pointerHide() {
        if (m.gb(0x0CA3) != 0) return
        if ((m.gb(0x0CB1) and 0x80) == 0) {
            m.sgb(0x0CB1, 0xFF)
            vm.pointerHide(m.g(0x0B84), m.g(0x0B86))
        }
    }

    private fun pointerSprite() {
        m.sgb(0x0C15, 0)
        x.step()
        val saved = intArrayOf(m.ax, m.bx, m.cx, m.dx, m.di, m.si, m.ds, m.es, m.bp)
        val (seg, slot) = Assets.entrySlot(m, m.dx)
        m.sg(0x0B2E, slot)
        m.sg(0x0B30, seg)
        val (sseg, sofs) = Assets.entryPtr(m, m.dx)
        m.sg(0x0CB4, sofs)
        m.sg(0x0CB6, sseg)
        val o = m.g(0x0BEA)
        val di = (m.g(0x0BE8) + m.g(0x0CAE)) and 0xFFFF
        m.ww(o, di + 8, slot)
        m.ww(o, di + 0x0A, seg)
        m.ax = saved[0]; m.bx = saved[1]; m.cx = saved[2]; m.dx = saved[3]; m.di = saved[4]
        m.si = saved[5]; m.ds = saved[6]; m.es = saved[7]; m.bp = saved[8]
    }

    // --- Steering (67c1, 6813, 68b6, 692a) ----------------------------------

    private fun steer(toward: Boolean) {
        x.step()
        if (m.dx == 0xFFFF) return
        val es = m.es
        val bp = m.bp
        val tgt = m.rw(es, m.g(0x0BD2) + m.dx)
        val d = IntArray(3) { (m.rw(es, tgt + 2 * it) - m.rw(es, bp + 2 * it)) and 0xFFFF }
        m.sg(0x0C6A, d[0])
        m.sg(0x0C6C, d[1])
        m.sg(0x0C6E, d[2])
        val seg: Int
        var di: Int
        if (m.rw(es, bp - 0x20) != 0) {
            val (aofs, aseg) = m.far(es, bp - 0x14)
            seg = aseg
            di = (aofs + m.rw(es, bp - 0x20)) and 0xFFFF
        } else {
            seg = CS
            di = build.steer
        }
        val n = m.rb(seg, di) + 1
        di++
        m.wb(es, bp + 8, n)
        var best = if (toward) 0x80000000L else 0x7FFFFFFFL
        m.sg(0x0B2E, (best and 0xFFFF).toInt())
        m.sg(0x0B30, ((best ushr 16) and 0xFFFF).toInt())
        var cx = n
        while (cx != 0) {
            var tot = 0L
            for (i in 0 until 3) {
                val v = m.rsb(seg, di + i)
                if (v != 0) tot += v.toLong() * s16(d[i])
            }
            tot = tot and 0xFFFFFFFFL
            m.sg(0x0B2A, (tot and 0xFFFF).toInt())
            m.sg(0x0B2C, (tot ushr 16).toInt())
            val t = if ((tot and 0x80000000L) != 0L) tot - (1L shl 32) else tot
            val b = if ((best and 0x80000000L) != 0L) best - (1L shl 32) else best
            if (if (toward) t > b else t < b) {
                best = tot
                m.sg(0x0B2E, (tot and 0xFFFF).toInt())
                m.sg(0x0B30, (tot ushr 16).toInt())
                m.sg(0x0B18, cx)
                m.sg(0x0B16, di)
            }
            di += 3
            cx--
        }
        m.wb(es, bp + 8, m.rb(es, bp + 8) - (m.g(0x0B18) and 0xFF))
        di = m.g(0x0B16)
        for (i in 0 until 3) m.wb(es, bp + 9 + i, m.rb(seg, di + i))
    }

    // --- Sound: the state the game keeps, the mixing left to the SoundSink ---

    private fun samplePlay(main: Boolean) {
        m.sgb(0x0C15, if (main) 1 else 0)
        val saved = intArrayOf(m.ax, m.bx, m.cx, m.dx, m.di, m.ds, m.es, m.bp)
        x.step()
        val rec = m.dx
        x.step(); m.sgb(0x0CC9, m.dx)
        x.step(); m.sgb(0x0CD1, m.dx)
        x.step(); m.sg(0x0E0E, m.dx)
        x.step(); m.sgb(0x0E0D, m.dx)
        m.sg(0x0B28, m.si)
        if (m.gb(0x0CA2) == 0) {
            val (seg, di) = Assets.soundRecordPtr(m, rec)
            if (m.gb(0x0E0D) == 0) m.sgb(0x0E0D, m.rb(seg, di + 1))
            m.sg(0x0E14, m.rw(seg, di + 2) - 0x10)
            m.sg(0x0E10, di + 0x10)
            m.sg(0x0E12, seg)
            m.sg(0x0E18, m.gb(0x0E0D) * 1000)
            vm.sound.play(seg, di, m.rw(m.es, m.bp - 0x0E))
        }
        m.ax = saved[0]; m.bx = saved[1]; m.cx = saved[2]; m.dx = saved[3]
        m.di = saved[4]; m.ds = saved[5]; m.es = saved[6]; m.bp = saved[7]
        m.si = m.g(0x0B28)
    }

    private fun soundParam() {
        x.step()
        m.sgb(0x0DFE, m.dx)
        m.sgb(0x0CDB, m.dx)
    }

    private fun soundSet86c8() {
        x.step()
        m.ax = m.dx
        vm.sound.music(SoundSink.Music.PARAM, m.dx)
    }

    private fun soundSlotSet(main: Boolean) {
        m.sgb(0x0C15, if (main) 1 else 0)
        val saved = intArrayOf(m.ax, m.bx, m.cx, m.dx, m.di, m.ds, m.es, m.bp)
        x.step(); val a = m.dx
        x.step(); val b = m.dx
        x.step()
        m.sg(0x0B28, m.si)
        val si = m.dx
        var di = 0
        var ax = 0
        if ((b and 0x8000) == 0) {
            val (s, o) = Assets.soundRecordPtr(m, b)
            ax = s
            di = o + 0x10
        }
        val bx = (a and 0x1F) * 8
        m.sg(bx + 0x0CF6, di)
        m.sg(bx + 0x0CF8, ax)
        m.sg(bx + 0x0CFA, if ((b and 0x8000) != 0) 0 else si)
        m.ax = saved[0]; m.bx = saved[1]; m.cx = saved[2]; m.dx = saved[3]
        m.di = saved[4]; m.ds = saved[5]; m.es = saved[6]; m.bp = saved[7]
        m.si = m.g(0x0B28)
    }

    private fun soundSeqPlay(main: Boolean) {
        m.sgb(0x0C15, if (main) 1 else 0)
        x.step()
        val (seg, di) = Assets.soundRecordPtr(m, m.dx)
        if (m.rb(seg, di) !in intArrayOf(0, 3, 4)) {
            repeat(5) { x.step() }
            return
        }
        m.sg(0x0CE0, di + 6)
        m.sg(0x0CE2, seg)
        x.step(); m.sgb(0x0CDB, m.dx)
        x.step(); m.sgb(0x0CD9, m.dx)
        x.step(); m.sg(0x0CE4, m.dx)
        x.step(); m.sg(0x0CE8, m.dx)
        x.step(); m.sg(0x0CE6, m.dx)
        if (m.gb(0x2892) == 0) vm.sound.music(SoundSink.Music.PLAY, seg, di + 6) // music_start returns on a slow machine
    }

    private fun soundSeqCtl() {
        x.step()
        m.sg(0x0CE6, m.dx)
        m.sg(0x0CE4, 0)
        m.sg(0x0CE8, 1)
        vm.sound.music(SoundSink.Music.FADE, m.dx)
    }

    private fun voiceParams(): Int {
        m.sg(0x0CC7, m.rw(m.es, m.bp - 0x0E))
        x.step(); m.sgb(0x0CC9, m.dx)
        x.step(); m.sgb(0x0CD1, m.dx)
        x.step(); m.sg(0x0CCD, m.dx)
        x.step(); m.sg(0x0CCB, m.dx)
        return m.dx
    }

    private fun soundTone() {
        if (voiceParams() != 0) {
            x.step()
            m.sg(0x0CCF, m.dx)
            m.sg(0x0CD3, 0)
            m.sgb(0x0CCA, 1)
            vm.sound.voice()
        }
    }

    private fun soundDecay() {
        if (voiceParams() != 0) {
            m.sg(0x0CCF, 0)
            // 5ee6: `div` leaves the flags of the earlier `sub dx,dx` (ZF set) on the CPUs this
            // runs on, so the `jnz` never takes the quotient: the step is always -1 and DX keeps
            // the remainder (7.4)
            m.dx = (m.gb(0x0CD1) shl 8) % m.g(0x0CCB)
            m.sg(0x0CD3, 0xFFFF)
            m.sgb(0x0CCA, 1)
            vm.sound.voice()
        }
    }

    private fun soundSampleHit() {
        if (voiceParams() != 0) throw UnsupportedEngineOperationException("6ch sound_sample_hit (no script uses it)")
    }

    // --- Assets (78f9, 803b, 7d4e) ------------------------------------------

    // --- EXPERIMENT (Ishar 3): lazy loads -----------------------------------------------------
    // Its 32-bit engine has megabytes: runner.io loads intro1..4 at once (1.3 MB), past what this 16-bit
    // layout can hold (~1 MB). A load that does not fit waits until a frame is started on it, and then
    // assets the same script loaded that run no frame make room. Not how the original works.

    /** Deferred loads: id -> file. */
    private val deferred = mutableMapOf<Int, String>()

    /** Each asset's file, to load it again after it made room. */
    private val names = mutableMapOf<Int, String>()

    /** Which script's frame loaded each asset (its asset id). */
    private val loadedBy = mutableMapOf<Int, Int>()

    private fun fits(name: String): Boolean {
        val raw = vm.files.read(name)
        val size = (raw[0].toInt() and 0xFF) or ((raw[1].toInt() and 0xFF) shl 8) or ((raw[2].toInt() and 0xFF) shl 16)
        return m.g(0x0BCA) + (size ushr 4) + 2 <= m.g(0x0B96)
    }

    private fun currentAsset() = m.rb(m.es, m.bp - 0x10)

    /** Before frame_new on [aid]: load it if it was deferred, freeing its loader's idle assets. */
    private fun loadDeferred(aid: Int) {
        val name = deferred[aid] ?: return
        val by = loadedBy[aid]
        while (!fits(name)) {
            val idle = loadedBy.entries.firstOrNull { (c, l) ->
                c != aid && l == by && c !in deferred && Assets.assetFindLoaded(m, c) != null && Frames.of(m, c) == null
            }?.key ?: break
            Assets.unloadAsset(m, idle) { vm.sound.stop() }
            names[idle]?.let { deferred[idle] = it } // loaded again if a frame is started on it later
        }
        if (!fits(name)) return
        deferred.remove(aid)
        Assets.loadAsset(m, vm.files, aid, name)
    }

    /** `45h w:id name`: `asset_load_direct` `78f9` unless the id is loaded. */
    private fun loadAsset() {
        val aid = m.lodsw()
        if (aid == 0) throw UnsupportedEngineOperationException("load_asset 0: a new game directory and main.io again")
        val name = x.cstr(m.ds, m.si)
        while (m.lodsb() != 0) { /* skip the name */ }
        vm.onAssetAsked(name)
        if (build == GameBuild.ISHAR3) {
            loadedBy[aid] = currentAsset()
            names[aid] = name
            if (Assets.assetFindLoaded(m, aid) == null && !fits(name)) {
                deferred[aid] = name
                return
            }
            deferred.remove(aid)
        }
        val before = m.g(0x0BCE)
        val at = (m.g(0x0BCA) shl 4) + m.g(0x0BC8)
        Assets.loadAsset(m, vm.files, aid, name)
        if (m.g(0x0BCE) != before) {
            vm.loaded.add(name)
            if (build == GameBuild.ISHAR2) {
                CopyProtection.removeIshar2(m, name, at)
                Ishar2Text.fix(m, name, at, (m.g(0x0B14) shl 4) + m.g(0x0B16))
            }
            if (build == GameBuild.ISHAR3) Ishar3Text.fix(m, name, at, (m.g(0x0B14) shl 4) + m.g(0x0B16))
        }
    }

    /** `3dh w:id` (`803b`). */
    private fun unloadAsset() {
        Assets.unloadAsset(m, m.lodsw()) { vm.sound.stop() }
    }

    /** `e2h`: drops the pixels of a sprite of the running asset; the shape-table borrow goes into section +4. */
    private fun spriteFree() {
        x.step()
        x.evalCx()
        Assets.spriteFree(m, m.dx)
    }

    // --- Placing objects (333f..35ad) ---------------------------------------

    private fun placeCommon(main: Boolean, mirrorFlip: Boolean, atOrigin: Boolean) {
        m.sgb(0x0C15, if (main) 1 else 0)
        val mirror = m.rb(m.es, m.bp - 3)
        m.sgb(0x0C12, if (mirrorFlip) mirror xor 1 else mirror)
        if (atOrigin) {
            x.step()
            for (o in intArrayOf(0x0C02, 0x0C04, 0x0C06, 0x0C0E)) m.sg(o, 0)
        } else {
            x.step(); m.sg(0x0C02, m.dx)
            x.step(); m.sg(0x0C04, m.dx)
            x.step(); m.sg(0x0C06, m.dx)
            x.step()
            x.evalCx()
            m.sg(0x0C0E, m.cx)
        }
        m.sgb(0x0C13, 0)
        Objects.placeEntry(m)
    }

    // --- Views (2ded) -------------------------------------------------------

    private fun declareView() {
        val bx0 = m.bx
        val di0 = m.di
        val es = m.es
        val o = m.g(0x0BEA)
        val v = m.lodsw()
        val bx = (v + m.g(0x0BF6)) and 0xFFFF
        m.wb(es, bx, m.rb(es, bx) or 0x40)
        m.wb(es, bx + 1, m.lodsb())
        for (i in 0 until 0x20) m.wb(es, bx + 6 + i, m.lodsb())
        val di = (m.g(0x0BE8) + m.g(0x0BEC)) and 0xFFFF
        if (di == 0) throw UnsupportedEngineOperationException("script error 0ah: no object record for a view")
        m.sg(0x0BEC, m.rw(o, di + 4))
        m.ww(es, bx + 2, di)
        m.ww(o, di + 6, 0)
        m.ww(es, bx + 4, 0)
        m.wb(o, di + 1, m.rb(es, bx + 1))
        val vx = m.rw(es, bx + 0x0E)
        val vy = m.rw(es, bx + 0x10)
        m.ww(o, di + 0x0C, vx and 0xFFF0)
        m.ww(o, di + 0x0E, vy)
        m.ww(o, di + 0x10, 0x7FFF)
        m.ww(o, di + 0x16, (vx + m.rw(es, bx + 0x12)) or 0x0F)
        m.ww(o, di + 0x18, vy + m.rw(es, bx + 0x14))
        for (k in intArrayOf(0x2A, 0x2C, 0x2E)) m.ww(es, bx + k, 0)
        Objects.viewListAdd(m, v)
        Objects.viewMatrixCross(m, bx)
        m.bx = bx0
        m.di = di0
    }

    private fun showView() {
        val v = m.lodsw()
        val es = m.es
        val di = m.g(0x0BF6)
        m.wb(es, v + di, (m.rb(es, v + di) and 0xBF) or 0x80)
        Objects.viewListRemove(m, v)
        Objects.viewListAdd(m, v)
    }

    private fun hideView() {
        val v = m.lodsw()
        val es = m.es
        val di = m.g(0x0BF6)
        m.wb(es, v + di, m.rb(es, v + di) or 0x40)
    }

    private fun frameView(): Int = m.rw(m.es, m.bp - 0x16)

    private fun viewportOrigin() {
        x.step()
        x.evalCx()
        val v = frameView()
        if (v != 0) {
            val bx = (m.g(0x0BF6) + v) and 0xFFFF
            m.ww(m.es, bx + 0x0A, m.dx)
            m.ww(m.es, bx + 0x0C, m.cx)
            m.wb(m.es, bx, m.rb(m.es, bx) or 0x80)
        }
    }

    /** `c1h`/`c2h`: test the frame's view but write at `[0bf6]` without adding it. */
    private fun viewportClip(a: Int, b: Int) {
        x.step()
        x.evalCx()
        if (frameView() != 0) {
            val bx = m.g(0x0BF6)
            m.ww(m.es, bx + a, m.dx)
            m.ww(m.es, bx + b, m.cx)
            m.wb(m.es, bx, m.rb(m.es, bx) or 0x80)
        }
    }

    private fun matrixRow(k: Int, cross: Boolean) {
        if (m.rb(m.es, m.bp - 0x16) == 0) return
        x.step(); val a = m.dx
        x.step(); val b = m.dx
        x.step()
        val bx = (m.g(0x0BF6) + frameView()) and 0xFFFF
        m.wb(m.es, bx + k + 2, m.dx)
        m.wb(m.es, bx + k + 1, b)
        m.wb(m.es, bx + k, a)
        if (cross) Objects.viewMatrixCross(m, bx)
        m.wb(m.es, bx, m.rb(m.es, bx) or 0x80)
    }

    private fun viewportScale() {
        x.step()
        x.evalCx()
        val v = frameView()
        if (v != 0) {
            val bx = (m.g(0x0BF6) + v) and 0xFFFF
            m.wb(m.es, bx + 0x1D, m.dx)
            m.wb(m.es, bx + 0x1E, (m.cx and 0xFF) - 1)
            x.step()
            m.wb(m.es, bx + 0x1F, m.dx)
            m.wb(m.es, bx, m.rb(m.es, bx) or 0x80)
        }
    }

    /** `c6h`: writes at `[0bf6]` like c1h/c2h, and the flag byte from `CX`. */
    private fun viewportShift() {
        x.step()
        if (frameView() != 0) {
            val bx = m.g(0x0BF6)
            m.wb(m.es, bx + 0x1C, m.dx)
            m.wb(m.es, bx, m.cx or 0x80)
        }
    }

    private fun set1dd7Block() {
        x.step(); m.sgb(0x1DD7, m.dx)
        x.step(); m.sg(0x1DD9, m.dx)
        x.step(); m.sg(0x1DDB, m.dx)
        x.step(); m.sg(0x1DDD, m.dx)
        if (m.gb(0x1DD7) != 0) {
            val bx = (m.g(0x0BF6) + frameView()) and 0xFFFF
            m.wb(m.es, bx, m.rb(m.es, bx) or 0x20)
        }
    }

    private fun set1da5Block() {
        val dx = m.gb(0x1DA5)
        x.step(); m.sgb(0x1DA5, m.dx)
        for (o in intArrayOf(0x1DB3, 0x1DB5, 0x1DB7, 0x1DB9, 0x1DBB)) {
            x.step()
            m.sg(o, m.dx)
        }
        m.dx = dx
    }

    companion object {
        /** The result list (`ss:1e7e`, extra words at +100h). */
        const val RESULTS = 0x1E7E

        /** Plain `ret` handlers. */
        /** start.exe: 36 785a (sets the frame's words -3ah/-3eh), b5 80bc (anim step), f6 8075 (anim start)... */
        private val ISHAR3_STUBS = mapOf(
            0x36 to "E", 0xB2 to "E", 0xB3 to "", 0xB4 to "", 0xB5 to "ESS", 0xC8 to "EW", 0xE7 to "EEE",
            0xE9 to "W", 0xEA to "W", 0xEB to "", 0xEC to "", 0xED to "EEEE", 0xEE to "EEEEE", 0xEF to "EEE",
            0xF0 to "EEE", 0xF1 to "EEE", 0xF2 to "EEE", 0xF3 to "EEE", 0xF4 to "EEEEEEEE", 0xF5 to "EEEEEE",
            0xF6 to "EEES", 0xF9 to "ESSS", 0xFA to "", 0xFB to "EEEESSS", 0xFC to "EESSSSSS", 0xFE to "EEE",
            0xFF to "EE",
        )

        private val RET_OPS = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x22, 0x23,
            0x36, 0x37, 0x3B, 0x8B, 0xB2, 0xB3, 0xB4, 0xB5, 0xC8, 0xC9, 0xD3, 0xD4,
        )

        fun rel8(v: Int): Int = if ((v and 0x80) != 0) v - 256 else v
    }
}
