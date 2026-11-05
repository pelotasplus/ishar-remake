package dev.pelotasplus.ishar.engine

/**
 * A YM3812 (OPL2), for Ishar 2's FM tunes: Kotlin port of the OPL2 parts of ymfm (https://github.com/aaronsgiles/ymfm).
 *
 * BSD 3-Clause License. Copyright (c) 2021, Aaron Giles. All rights reserved.
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided that the
 * following conditions are met:
 * 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following
 *    disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the
 *    following disclaimer in the documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote
 *    products derived from this software without specific prior written permission.
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
class OplChip {
    private val regs = IntArray(256)
    private val ops = Array(18) { Op(it + 2 * (it / 6)) }
    private val channels = Array(9) { Channel(it, ops[CH_OPS[it * 2]], ops[CH_OPS[it * 2 + 1]]) }

    private var envCounter = 0
    private var noise = 1
    private var amCounter = 0
    private var pmCounter = 0
    private var lfoAm = 0
    private var modified = true
    private var prepareCount = 0

    /** One register write. */
    fun write(reg: Int, value: Int) {
        modified = true
        val data = value and 0xFF
        regs[reg] = data
        if (reg == 0xBD) {
            val mask = if ((data and 0x20) != 0) data and 0x1F else 0
            channels[6].key(if ((mask and 0x10) != 0) 3 else 0, KEYON_RHYTHM)
            channels[7].key((mask and 1) or (((mask ushr 3) and 1) shl 1), KEYON_RHYTHM)
            channels[8].key(((mask ushr 2) and 1) or (((mask ushr 1) and 1) shl 1), KEYON_RHYTHM)
        } else if ((reg and 0xF0) == 0xB0 && (reg and 0x0F) < 9) {
            channels[reg and 0x0F].key(if ((data and 0x20) != 0) 3 else 0, KEYON_NORMAL)
        }
    }

    /** The next sample, at [RATE] Hz: signed 16 bits through the YM3014's 10.3 float. */
    fun clock(): Int {
        if (modified || prepareCount++ >= 4096) {
            for (c in channels) c.prepare()
            modified = false
            prepareCount = 0
        }
        envCounter += 4
        val rawPm = clockNoiseAndLfo()
        for (c in channels) c.clock(rawPm)
        var out = 0
        if ((regs[0xBD] and 0x20) != 0) {
            val p13 = ops[13].phase
            val p17 = ops[17].phase
            val select = (((p13 ushr 2) xor (p13 ushr 7)) or (p13 ushr 3) or ((p17 ushr 5) xor (p17 ushr 3))) and 1
            for (i in 0 until 6) out += channels[i].output2Op()
            out += channels[6].bassDrum()
            out += channels[7].hatSnare(select)
            out += channels[8].tomCymbal(select)
        } else {
            for (c in channels) out += c.output2Op()
        }
        return roundtripFp(out)
    }

    private fun clockNoiseAndLfo(): Int {
        val n = noise shl 1
        noise = n or (((n ushr 23) xor (n ushr 9) xor (n ushr 8) xor (n ushr 1)) and 1)
        val am = amCounter++
        if (am >= 210 * 64 - 1) amCounter = 0
        val depthAm = (regs[0xBD] ushr 7) and 1
        lfoAm = (if (am < 105 * 64) am else 210 * 64 + 63 - am) ushr (9 - 2 * depthAm)
        val pm = pmCounter
        pmCounter = (pmCounter + 1) and 0xFFFF
        val depthPm = (regs[0xBD] ushr 6) and 1
        return PM_SCALE[(pm ushr 10) and 7] shr (depthPm xor 1)
    }

    private inner class Op(val offs: Int) {
        private var phase10 = 0
        var env = 0x3FF
        private var state = EG_RELEASE
        private var keyState = 0
        private var keyonLive = 0

        private var wave = WAVES[0]
        private var blockFreq = 0
        private var multiple = 1
        private var phaseStep = 0
        private var pmEnable = false
        private var amEnable = false
        private var totalLevel = 0
        private var sustain = 0
        private val rates = IntArray(5)

        val phase: Int get() = phase10 ushr 10

        fun key(on: Int, type: Int) {
            keyonLive = (keyonLive and (1 shl type).inv()) or ((on and 1) shl type)
        }

        fun prepare(ch: Int) {
            val r20 = regs[0x20 + offs]
            val r40 = regs[0x40 + offs]
            val r60 = regs[0x60 + offs]
            val r80 = regs[0x80 + offs]
            wave = WAVES[if ((regs[1] and 0x20) != 0) regs[0xE0 + offs] and 3 else 0]
            blockFreq = ((regs[0xB0 + ch] and 0x1F) shl 8) or regs[0xA0 + ch]
            val noteSelect = (regs[8] ushr 6) and 1
            val keycode = (((blockFreq ushr 10) and 7) shl 1) or ((blockFreq ushr (9 - noteSelect)) and 1)
            val mul = r20 and 15
            multiple = ((mul and 0xE) or ((0xC2AA ushr mul) and 1)) * 2
            if (multiple == 0) multiple = 1
            pmEnable = (r20 and 0x40) != 0
            amEnable = (r20 and 0x80) != 0
            phaseStep = computePhaseStep(0)
            totalLevel = (r40 and 0x3F) shl 3
            val t = (r40 ushr 6) and 3
            val ksl = ((t ushr 1) and 1) or ((t and 1) shl 1)
            if (ksl != 0) {
                val block = (blockFreq ushr 10) and 7
                val a = maxOf(0, FNUM_TO_ATTEN[(blockFreq ushr 6) and 15] - 8 * (block xor 7))
                totalLevel += a shl ksl
            }
            var sl = (r80 ushr 4) and 15
            sl = sl or ((sl + 1) and 0x10)
            sustain = sl shl 5
            val ksr = keycode ushr (2 * (((r20 ushr 4) and 1) xor 1))
            rates[EG_ATTACK] = rate(((r60 ushr 4) and 15) * 4, ksr)
            rates[EG_DECAY] = rate((r60 and 15) * 4, ksr)
            val release = rate((r80 and 15) * 4, ksr)
            rates[EG_SUSTAIN] = if ((r20 and 0x20) != 0) 0 else release
            rates[EG_RELEASE] = release
            val on = if (keyonLive != 0) 1 else 0
            if (on != keyState) {
                keyState = on
                if (on != 0) startAttack() else if (state < EG_RELEASE) state = EG_RELEASE
            }
        }

        private fun rate(raw: Int, ksr: Int) = if (raw == 0) 0 else minOf(raw + ksr, 63)

        private fun startAttack() {
            if (state == EG_ATTACK) return
            state = EG_ATTACK
            phase10 = 0
            if (rates[EG_ATTACK] >= 62) env = 0
        }

        private fun computePhaseStep(rawPm: Int): Int {
            var fnum = (blockFreq and 0x3FF) shl 2
            fnum += (rawPm * ((blockFreq ushr 7) and 7)) shr 1
            fnum = fnum and 0xFFF
            val step = (fnum shl ((blockFreq ushr 10) and 7)) ushr 2
            return (step * multiple) ushr 1
        }

        fun clock(counter: Int, rawPm: Int) {
            clockEnvelope(counter ushr 2)
            phase10 += if (pmEnable) computePhaseStep(rawPm) else phaseStep
        }

        private fun clockEnvelope(counter: Int) {
            if (state == EG_ATTACK && env == 0) state = EG_DECAY
            if (state == EG_DECAY && env >= sustain) state = EG_SUSTAIN
            val rate = rates[state]
            val shift = rate ushr 2
            val c = counter shl shift
            if ((c and 0x7FF) != 0) return
            val bits = (c ushr (if (shift <= 11) 11 else shift)) and 7
            val inc = (INCREMENTS[rate] ushr (4 * bits)) and 15
            if (state == EG_ATTACK) {
                if (rate < 62) env = (env + ((env.inv() * inc) ushr 4)) and 0xFFFF
            } else {
                env += inc
                if (env >= 0x400) env = 0x3FF
            }
        }

        fun volume(phase: Int): Int {
            if (env > EG_QUIET) return 0
            val sin = wave[phase and 1023]
            var a = env
            if (amEnable) a += lfoAm
            a += totalLevel
            if (a > 0x3FF) a = 0x3FF
            val x = (sin and 0x7FFF) + (a shl 2)
            val v = POWER[x and 0xFF] ushr (x ushr 8)
            return if ((sin and 0x8000) != 0) -v else v
        }
    }

    private inner class Channel(val ch: Int, val a: Op, val b: Op) {
        private var fb0 = 0
        private var fb1 = 0
        private var fbIn = 0

        fun key(states: Int, type: Int) {
            a.key(states and 1, type)
            b.key((states ushr 1) and 1, type)
        }

        fun prepare() {
            a.prepare(ch)
            b.prepare(ch)
        }

        fun clock(rawPm: Int) {
            fb0 = fb1
            fb1 = fbIn
            a.clock(envCounter, rawPm)
            b.clock(envCounter, rawPm)
        }

        private fun modulator(): Int {
            val feedback = (regs[0xC0 + ch] ushr 1) and 7
            val mod = if (feedback != 0) (fb0 + fb1) shr (10 - feedback) else 0
            fbIn = a.volume(a.phase + mod)
            return fbIn
        }

        fun output2Op(): Int {
            modulator()
            // OPL2's modulator comes from the sample before
            if ((regs[0xC0 + ch] and 1) == 0) return b.volume(b.phase + (fb1 shr 1)) shr 1
            return ((fb1 shr 1) + (b.volume(b.phase) shr 1)).coerceIn(-32768, 32767)
        }

        fun bassDrum(): Int {
            val m = modulator()
            val mod = if ((regs[0xC0 + ch] and 1) != 0) 0 else m shr 1
            return (b.volume(b.phase + mod) shr 1) * 2
        }

        fun hatSnare(select: Int): Int {
            val n = (noise ushr 23) and 1
            val hat = a.volume((select shl 9) or (0xD0 ushr (2 * (n xor select)))) shr 1
            val snare = b.volume((0x100 shl ((a.phase ushr 8) and 1)) xor (n shl 8)) shr 1
            return (hat + snare).coerceIn(-32768, 32767) * 2
        }

        fun tomCymbal(select: Int): Int {
            val tom = a.volume(a.phase) shr 1
            val cymbal = b.volume(0x100 or (select shl 9)) shr 1
            return (tom + cymbal).coerceIn(-32768, 32767) * 2
        }
    }

    companion object {
        /** 3.579545 MHz / 72. */
        const val RATE = 49716

        private const val KEYON_NORMAL = 0
        private const val KEYON_RHYTHM = 1
        private const val EG_ATTACK = 1
        private const val EG_DECAY = 2
        private const val EG_SUSTAIN = 3
        private const val EG_RELEASE = 4
        private const val EG_QUIET = 0x380

        private val CH_OPS = intArrayOf(0, 3, 1, 4, 2, 5, 6, 9, 7, 10, 8, 11, 12, 15, 13, 16, 14, 17)
        private val PM_SCALE = intArrayOf(8, 4, 0, -4, -8, -4, 0, 4)
        private val FNUM_TO_ATTEN = intArrayOf(0, 24, 32, 37, 40, 43, 45, 47, 48, 50, 51, 52, 53, 54, 55, 56)

        private fun roundtripFp(value: Int): Int {
            if (value < -32768) return -32768
            if (value > 32767) return 32767
            val scan = value xor (value shr 31)
            val exponent = maxOf(7 - (scan shl 17).countLeadingZeroBits(), 1) - 1
            return value and ((1 shl exponent) - 1).inv()
        }

        private val SIN = intArrayOf(
        0x859, 0x6c3, 0x607, 0x58b, 0x52e, 0x4e4, 0x4a6, 0x471, 0x443, 0x41a, 0x3f5, 0x3d3, 0x3b5, 0x398, 0x37e, 0x365,
        0x34e, 0x339, 0x324, 0x311, 0x2ff, 0x2ed, 0x2dc, 0x2cd, 0x2bd, 0x2af, 0x2a0, 0x293, 0x286, 0x279, 0x26d, 0x261,
        0x256, 0x24b, 0x240, 0x236, 0x22c, 0x222, 0x218, 0x20f, 0x206, 0x1fd, 0x1f5, 0x1ec, 0x1e4, 0x1dc, 0x1d4, 0x1cd,
        0x1c5, 0x1be, 0x1b7, 0x1b0, 0x1a9, 0x1a2, 0x19b, 0x195, 0x18f, 0x188, 0x182, 0x17c, 0x177, 0x171, 0x16b, 0x166,
        0x160, 0x15b, 0x155, 0x150, 0x14b, 0x146, 0x141, 0x13c, 0x137, 0x133, 0x12e, 0x129, 0x125, 0x121, 0x11c, 0x118,
        0x114, 0x10f, 0x10b, 0x107, 0x103, 0x0ff, 0x0fb, 0x0f8, 0x0f4, 0x0f0, 0x0ec, 0x0e9, 0x0e5, 0x0e2, 0x0de, 0x0db,
        0x0d7, 0x0d4, 0x0d1, 0x0cd, 0x0ca, 0x0c7, 0x0c4, 0x0c1, 0x0be, 0x0bb, 0x0b8, 0x0b5, 0x0b2, 0x0af, 0x0ac, 0x0a9,
        0x0a7, 0x0a4, 0x0a1, 0x09f, 0x09c, 0x099, 0x097, 0x094, 0x092, 0x08f, 0x08d, 0x08a, 0x088, 0x086, 0x083, 0x081,
        0x07f, 0x07d, 0x07a, 0x078, 0x076, 0x074, 0x072, 0x070, 0x06e, 0x06c, 0x06a, 0x068, 0x066, 0x064, 0x062, 0x060,
        0x05e, 0x05c, 0x05b, 0x059, 0x057, 0x055, 0x053, 0x052, 0x050, 0x04e, 0x04d, 0x04b, 0x04a, 0x048, 0x046, 0x045,
        0x043, 0x042, 0x040, 0x03f, 0x03e, 0x03c, 0x03b, 0x039, 0x038, 0x037, 0x035, 0x034, 0x033, 0x031, 0x030, 0x02f,
        0x02e, 0x02d, 0x02b, 0x02a, 0x029, 0x028, 0x027, 0x026, 0x025, 0x024, 0x023, 0x022, 0x021, 0x020, 0x01f, 0x01e,
        0x01d, 0x01c, 0x01b, 0x01a, 0x019, 0x018, 0x017, 0x017, 0x016, 0x015, 0x014, 0x014, 0x013, 0x012, 0x011, 0x011,
        0x010, 0x00f, 0x00f, 0x00e, 0x00d, 0x00d, 0x00c, 0x00c, 0x00b, 0x00a, 0x00a, 0x009, 0x009, 0x008, 0x008, 0x007,
        0x007, 0x007, 0x006, 0x006, 0x005, 0x005, 0x005, 0x004, 0x004, 0x004, 0x003, 0x003, 0x003, 0x002, 0x002, 0x002,
        0x002, 0x001, 0x001, 0x001, 0x001, 0x001, 0x001, 0x001, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000, 0x000,
        )

        /** The mantissas of `attenuation_to_volume`. */
        private val POWER = intArrayOf(
        0x3fa, 0x3f5, 0x3ef, 0x3ea, 0x3e4, 0x3df, 0x3da, 0x3d4, 0x3cf, 0x3c9, 0x3c4, 0x3bf, 0x3b9, 0x3b4, 0x3ae, 0x3a9,
        0x3a4, 0x39f, 0x399, 0x394, 0x38f, 0x38a, 0x384, 0x37f, 0x37a, 0x375, 0x370, 0x36a, 0x365, 0x360, 0x35b, 0x356,
        0x351, 0x34c, 0x347, 0x342, 0x33d, 0x338, 0x333, 0x32e, 0x329, 0x324, 0x31f, 0x31a, 0x315, 0x310, 0x30b, 0x306,
        0x302, 0x2fd, 0x2f8, 0x2f3, 0x2ee, 0x2e9, 0x2e5, 0x2e0, 0x2db, 0x2d6, 0x2d2, 0x2cd, 0x2c8, 0x2c4, 0x2bf, 0x2ba,
        0x2b5, 0x2b1, 0x2ac, 0x2a8, 0x2a3, 0x29e, 0x29a, 0x295, 0x291, 0x28c, 0x288, 0x283, 0x27f, 0x27a, 0x276, 0x271,
        0x26d, 0x268, 0x264, 0x25f, 0x25b, 0x257, 0x252, 0x24e, 0x249, 0x245, 0x241, 0x23c, 0x238, 0x234, 0x230, 0x22b,
        0x227, 0x223, 0x21e, 0x21a, 0x216, 0x212, 0x20e, 0x209, 0x205, 0x201, 0x1fd, 0x1f9, 0x1f5, 0x1f0, 0x1ec, 0x1e8,
        0x1e4, 0x1e0, 0x1dc, 0x1d8, 0x1d4, 0x1d0, 0x1cc, 0x1c8, 0x1c4, 0x1c0, 0x1bc, 0x1b8, 0x1b4, 0x1b0, 0x1ac, 0x1a8,
        0x1a4, 0x1a0, 0x19c, 0x199, 0x195, 0x191, 0x18d, 0x189, 0x185, 0x181, 0x17e, 0x17a, 0x176, 0x172, 0x16f, 0x16b,
        0x167, 0x163, 0x160, 0x15c, 0x158, 0x154, 0x151, 0x14d, 0x149, 0x146, 0x142, 0x13e, 0x13b, 0x137, 0x134, 0x130,
        0x12c, 0x129, 0x125, 0x122, 0x11e, 0x11b, 0x117, 0x114, 0x110, 0x10c, 0x109, 0x106, 0x102, 0x0ff, 0x0fb, 0x0f8,
        0x0f4, 0x0f1, 0x0ed, 0x0ea, 0x0e7, 0x0e3, 0x0e0, 0x0dc, 0x0d9, 0x0d6, 0x0d2, 0x0cf, 0x0cc, 0x0c8, 0x0c5, 0x0c2,
        0x0be, 0x0bb, 0x0b8, 0x0b5, 0x0b1, 0x0ae, 0x0ab, 0x0a8, 0x0a4, 0x0a1, 0x09e, 0x09b, 0x098, 0x094, 0x091, 0x08e,
        0x08b, 0x088, 0x085, 0x082, 0x07e, 0x07b, 0x078, 0x075, 0x072, 0x06f, 0x06c, 0x069, 0x066, 0x063, 0x060, 0x05d,
        0x05a, 0x057, 0x054, 0x051, 0x04e, 0x04b, 0x048, 0x045, 0x042, 0x03f, 0x03c, 0x039, 0x036, 0x033, 0x030, 0x02d,
        0x02a, 0x028, 0x025, 0x022, 0x01f, 0x01c, 0x019, 0x016, 0x014, 0x011, 0x00e, 0x00b, 0x008, 0x006, 0x003, 0x000,
        ).also { t -> for (i in t.indices) t[i] = (t[i] or 0x400) shl 2 }

        private val INCREMENTS = intArrayOf(
        0x00000000.toInt(), 0x00000000.toInt(), 0x10101010.toInt(), 0x10101010.toInt(),
        0x10101010.toInt(), 0x10101010.toInt(), 0x11101110.toInt(), 0x11101110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x10101010.toInt(), 0x10111010.toInt(), 0x11101110.toInt(), 0x11111110.toInt(),
        0x11111111.toInt(), 0x21112111.toInt(), 0x21212121.toInt(), 0x22212221.toInt(),
        0x22222222.toInt(), 0x42224222.toInt(), 0x42424242.toInt(), 0x44424442.toInt(),
        0x44444444.toInt(), 0x84448444.toInt(), 0x84848484.toInt(), 0x88848884.toInt(),
        0x88888888.toInt(), 0x88888888.toInt(), 0x88888888.toInt(), 0x88888888.toInt(),
        )

        private fun absSin(input: Int) = SIN[(if ((input and 0x100) != 0) input.inv() else input) and 0xFF]

        private val WAVES: Array<IntArray> = run {
            val w0 = IntArray(1024) { absSin(it) or (((it ushr 9) and 1) shl 15) }
            val zero = w0[0]
            arrayOf(
                w0,
                IntArray(1024) { if ((it and 0x200) != 0) zero else w0[it] },
                IntArray(1024) { w0[it] and 0x7FFF },
                IntArray(1024) { if ((it and 0x100) != 0) zero else w0[it] and 0x7FFF },
            )
        }
    }
}
