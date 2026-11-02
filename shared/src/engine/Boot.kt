package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.ByteReader

/**
 * Builds the initial DOS boot state (`entry 0000:25e5 .. 2607`):
 * - Loads hardcoded executable tables (`ExeData.load`)
 * - Parses `start.stp`
 * - Loads `blancpc.io` at `1123:0000` and scaled copy at `11c3:0036` (`builtin_samples_load`); Ishar 2
 *   ships none, so its heap starts at the free paragraph ([GameBuild.heap])
 * - Decodes `main.io` via `asset_load_catalogue` (`76bc`), lays out the asset table,
 *   frame table, frame memory, object area, and asset heap, and creates frame 0 (`BP = 02a0`).
 */
object Boot {
    const val PAGE_SEG = 0xE000

    fun boot(
        machine: Machine,
        files: GameFiles,
        cpuSpeed: Int = 379,
        randomSeed: Int = 0,
        soundOverride: Char? = null,
    ) {
        ExeData.load(machine)

        val stpBytes = files.read("start.stp")
        val cfg = mutableMapOf<Char, Char>()
        var i = 0
        while (i < stpBytes.size - 1) {
            val k = (stpBytes[i].toInt() and 0xFF).toChar()
            val v = (stpBytes[i + 1].toInt() and 0xFF).toChar()
            cfg[k] = v
            i += 2
        }
        if (soundOverride != null) {
            cfg['S'] = soundOverride
        }
        val videoChar = cfg['V']
        if (videoChar != 'V') {
            throw UnsupportedEngineOperationException("only the VGA driver is rebuilt (start.stp V$videoChar)")
        }

        machine.sgb(0x2898, 1) // has_vga
        machine.sgb(0x2896, 1) // has_ega (set on VGA too)
        machine.sgb(0x2897, 1)
        machine.sg(0x2894, 5) // card code: VGA
        val soundCode = when (cfg['S']) {
            'I' -> 1
            'A' -> 2
            'B' -> 3
            'N' -> 0
            else -> 3
        }
        machine.sgb(0x2890, soundCode)
        machine.sgb(0x2893, (cfg['K'] ?: 'Q').code)
        machine.sgb(0x0CC6, 1) // keyboard_layout_check forces 1
        machine.sgb(0x289A, 3) // BIOS video mode at boot
        machine.sg(0x0C9F, cpuSpeed) // boot benchmark
        machine.sgb(0x2892, if (cpuSpeed <= 150) 1 else 0) // slow_machine: music off
        machine.ww(0x0040, 0x006C, randomSeed) // BIOS clock word main.io 0596 seeds from
        machine.sgb(0x288E, 1) // sound glue: not a working SB
        if (cfg['S'] == 'B') {
            sbDetect(machine)
        }

        // mouse_init: INT 33h present, pointer save buffer, pointer hidden
        machine.sgb(0x0CA3, 0)
        val soundDeviceBits = when (cfg['S']) {
            'A' -> 0x20
            'B' -> 0x80
            else -> 0
        }
        machine.sg(0x0C9D, 4 or soundDeviceBits)
        machine.sg(0x0CAA, 0x2780)
        machine.sg(0x0CAC, machine.ss)
        machine.sgb(0x0CB1, 0xFF)
        machine.ww(Machine.CS, machine.build.mouse, 0)
        machine.ww(Machine.CS, machine.build.mouse + 2, 0)

        // timer_setup: 57.6 Hz clock; [0c97]/[0c98] chain BIOS clock
        machine.sgb(0x0C97, 1)
        machine.sgb(0x0C98, 1)
        machine.sgb(0x0C69, 1) // retrace IRQ installed
        for (v in 0 until 4) {
            machine.sgb(0x1DFE + 16 * v, 0x80) // sound_voices_reset
        }

        // video: mode 13h, page at E000 (EMS frame), screen A000, stride 320
        machine.sg(0x0B84, 0); machine.sg(0x0B86, machine.screen)
        machine.sg(0x0B88, 0); machine.sg(0x0B8A, PAGE_SEG)
        machine.sg(0x1DF9, 0); machine.sg(0x1DFB, PAGE_SEG)
        machine.sg(0x0C24, 0); machine.sg(0x0C26, PAGE_SEG)
        machine.sg(0x0B5A, 0); machine.sg(0x0B5C, PAGE_SEG)
        machine.sg(0x1DD5, 320)
        machine.sg(0x1DCD, 0); machine.sg(0x1DCF, 0); machine.sg(0x1DD1, 319); machine.sg(0x1DD3, 199)
        machine.sg(0x0C1A, 0); machine.sg(0x0C1C, 0); machine.sg(0x0C1E, 319); machine.sg(0x0C20, 199)
        machine.sg(0x0C22, 0x50)

        // memory
        val b = machine.build
        machine.sg(0x28A2, b.freePara)
        machine.sg(0x0E1E, b.freePara); machine.sg(0x0E1C, 0)
        if (b.builtinSamples) builtinSamplesLoad(machine, files)
        machine.sg(0x0B92, b.heap); machine.sg(0x0B90, 0)
        machine.sg(0x0B94, 0); machine.sg(0x0B96, machine.build.heapEnd) // heap end: 640 KB (Ishar 3: up to E000)
        loadMain(machine, files)

        // 2607
        machine.sg(0x0C58, 0x0802); machine.sg(0x0C5A, 0x0902); machine.sg(0x0C5C, 0x0A02)
        machine.sg(0x0C80, 0x1E7E)
        machine.sg(0x0B02, 0)
    }

    fun sbDetect(machine: Machine) {
        machine.sgb(0x288E, 4)
        val sbd = machine.build.sbd
        val drv = machine.build.snd
        machine.ww(sbd, 0x008A, 0x0220)
        machine.wb(sbd, 0x008C, 0x7F)
        machine.wb(sbd, 0x008D, 0x0F)
        val a = (sbd shl 4) + 0x00DC
        machine.wb(sbd, 0x05DC, a ushr 16)
        machine.ww(sbd, 0x05DE, a and 0xFFFF)
        machine.wb(sbd, 0x05DD, (a + 0x100) ushr 16)
        machine.ww(sbd, 0x05E0, (a + 0x100) and 0xFFFF)
        machine.wb(drv, 0x1F7E, 0)
        machine.wb(drv, 0x2175, 0)
    }

    fun builtinSamplesLoad(machine: Machine, files: GameFiles) {
        val raw = files.read("blancpc.io")
        val len = raw.size.coerceAtMost(0x09C4)
        val freePara = machine.build.freePara
        val a = freePara shl 4
        raw.copyInto(machine.m, destinationOffset = a, startIndex = 0, endIndex = len)
        machine.sg(0x0E20, 0x0036); machine.sg(0x0E24, 0x0036)
        machine.sg(0x0E22, freePara); machine.sg(0x0E26, freePara + 0x00A0)
        val src = a + 0x0036
        val dst = a + 0x0A00 + 0x0036
        machine.m.copyInto(machine.m, destinationOffset = dst, startIndex = src, endIndex = src + 0x0074)
        for (i in 0x0074 until (0x0074 + 0x098C)) {
            var v = machine.m[src + i].toInt() and 0xFF
            if (v != 0) {
                val signedDiff = (((v - 0x20) and 0xFF) xor 0x80) - 0x80
                v = ((signedDiff shr 2) + 0x20) and 0xFF
            }
            machine.m[dst + i] = v.toByte()
        }
    }

    fun loadMain(machine: Machine, files: GameFiles) {
        val raw = files.read("main.io")
        val page = (machine.g(0x0B5C) shl 4) + machine.g(0x0B5A)
        val pageBuf = machine.m.copyOfRange(page, page + IoDecoder.REFILL_BYTES)
        val decoded = IoDecoder.decode(raw, pageBuf)
        pageBuf.copyInto(machine.m, destinationOffset = page)
        val block = requireNotNull(decoded.header.bootBlock) { "main.io must have a 16-byte boot block" }.raw
        fun w(ofs: Int): Int = ByteReader.u16LE(block, ofs)

        val es = machine.g(0x0B92)
        val ax = w(0) // asset table entries
        machine.sg(0x0BCC, ax)
        val dx = machine.g(0x0B90)
        machine.sg(0x0BC2, dx)
        machine.sg(0x0BD0, dx)
        val tbl = ((((ax ushr 2) + 1) shl 4) + dx) and 0xFFFF
        machine.sg(0x0BD2, tbl)
        machine.sg(0x0BDA, w(2)) // frame slots
        val bx = (w(2) * 6 + tbl) and 0xFFFF
        machine.sg(0x0BD4, bx)
        machine.sg(0x0BD6, es)
        machine.sg(0x0BD8, bx)
        var seg = es + (w(8) ushr 4) + 1 // frame memory bytes
        machine.pointerArrays.clear()
        machine.pointerArrayNext = (seg - es) shl 4
        machine.pointerArrayEnd = machine.pointerArrayNext + machine.build.pointerArrayBytes
        seg += machine.build.pointerArrayBytes ushr 4
        machine.sg(0x0BE0, 0)
        machine.sg(0x0BE2, seg)
        seg += (((w(12) + 3) * 0x26) ushr 4) + 1 // object records
        machine.sg(0x0BE4, 0)
        machine.sg(0x0BE6, seg)
        machine.sg(0x0BC4, 0)
        machine.sg(0x0BC6, seg)
        machine.sg(0x0BC8, 0)
        machine.sg(0x0BCA, seg)
        val lim = seg + ((((w(6) and 0x0F) shl 12) + (w(4) ushr 4)) and 0xFFFF)
        if (lim <= 0xFFFF && lim <= machine.g(0x0B96)) {
            machine.sg(0x0B96, lim)
            machine.sg(0x0B94, 0)
        }
        machine.sg(0x0BCE, 0)
        machine.sgb(0x0C37, 0)
        machine.sgb(0x0C99, 0)
        machine.sg(0x0C16, 0)
        machine.sg(0x0C10, 0)
        machine.sgb(0x0CB0, 0)
        machine.sgb(0x0CB1, 0xFF)
        machine.sg(0x0BA0, 0xFFFF)
        machine.es = es
        frameTableInit(machine)
        objectAreaInit(machine)
        val aseg = machine.g(0x0BCA)
        val aofs = machine.g(0x0BC8)
        val a = (aseg shl 4) + aofs + decoded.bytes.size
        decoded.overrun.copyInto(machine.m, destinationOffset = a)
        Assets.assetPlace(machine, aseg, aofs, decoded.bytes)
        machine.sg(0x0B06, 0)
        val bp = Objects.frameCreate(machine, aseg, aofs)
        machine.ww(es, bp - 0x16, 0)
        machine.sg(0x0BF6, bp)
        machine.sg(0x0BF8, es)
    }

    fun frameTableInit(machine: Machine) {
        val es = machine.es
        val di = machine.g(0x0BD2)
        var bx = 0
        var cx = 1
        while (true) {
            bx += 6
            cx += 1
            val si = bx + di
            machine.ww(es, si - 2, bx)
            machine.ww(es, si - 6, 0)
            machine.ww(es, si - 4, 0)
            if (cx >= machine.g(0x0BDA)) break
        }
        machine.ww(es, di + 4, 0)
        machine.sg(0x0BDC, 1)
        machine.sg(0x0BDE, 6)
    }

    fun objectAreaInit(machine: Machine) {
        var si = machine.g(0x0BE0)
        val ds = machine.g(0x0BE2)
        machine.sg(0x0BE8, si)
        machine.sg(0x0BEA, ds)
        si += 2
        machine.sg(0x0BEE, si)
        machine.ww(ds, si + 0x0C, 0); machine.ww(ds, si + 0x0E, 0)
        machine.ww(ds, si + 0x16, 0x013F); machine.ww(ds, si + 0x18, 0x00C7)
        si += 0x26
        machine.wb(ds, si + 1, 0)
        machine.sg(0x1DA9, si)
        si += 0x26
        machine.sg(0x0CAE, si)
        machine.ww(ds, si + 0x0C, 0); machine.ww(ds, si + 0x0E, 0)
        machine.wb(ds, si, 0xFE)
        machine.sgb(0x0CB0, 0)
        si += 0x26
        machine.sg(0x0BEC, si)
        var di = si
        while (true) {
            si += 0x26
            if (si > 0xFFD9) break
            machine.ww(ds, di + 4, si)
            di = si
            if ((di ushr 4) + ds >= machine.g(0x0BE6)) break
        }
        di -= 0x26
        machine.ww(ds, di + 4, 0)
    }

    // --- Input helpers (int9_handler & INT 33h callback) --------------------

    fun keyDown(machine: Machine, scancode: Int) {
        val bx = scancode - 1
        if (bx < 0) return
        machine.wb(Machine.CS, 0x031A + bx, 1)
        val kbdChar = if (bx < ExeData.KBD_TABLE.size) ExeData.KBD_TABLE[bx].toInt() and 0xFF else 0xFE
        if (bx < 0x70 && kbdChar != 0xFE) {
            machine.wb(Machine.CS, 0x0318, kbdChar)
        }
    }

    fun keyUp(machine: Machine, scancode: Int) {
        val bx = scancode - 1
        if (bx < 0) return
        machine.wb(Machine.CS, 0x031A + bx, 0)
        val kbdChar = if (bx < ExeData.KBD_TABLE.size) ExeData.KBD_TABLE[bx].toInt() and 0xFF else 0xFE
        if (bx < ExeData.KBD_TABLE.size && kbdChar != 0xFE) {
            machine.wb(Machine.CS, 0x0318, 0)
        }
    }

    fun mouse(machine: Machine, x: Int? = null, y: Int? = null, buttons: Int? = null) {
        val at = machine.build.mouse
        if (x != null) machine.ww(Machine.CS, at, x)
        if (y != null) machine.ww(Machine.CS, at + 2, y)
        if (buttons != null) machine.ww(Machine.CS, at + 4, buttons)
    }
}
