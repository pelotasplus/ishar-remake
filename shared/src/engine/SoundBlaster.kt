package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Machine.Companion.s16
import dev.pelotasplus.ishar.engine.Machine.Companion.s8

/**
 * The game's voices, the Sound Blaster sample driver and the MOD player,
 * as the original runs them on an SB (`[2890]` = 3, `[288e]` = 4), state at the original
 * addresses: game voices `ss:1dfd + 16n`, driver voices `0bac:008f + 11h n` (run at `0d29`),
 * the MOD player in seg_0941 (run at `0abe`) with its tables in the block at `[28b0]`.
 *
 * Output goes to [out] on the emulated clock [time] (seconds): the sample mixer's 256-byte DMA
 * blocks at 12,048 Hz and the MOD player's direct-DAC bytes at 13,257.6 Hz.
 */
class SoundBlaster(
    private val m: Machine,
    pitDivisor: Int = 0x50E7,
    var out: Output = Output.NONE,
) : SoundSink {
    private val DRV = m.build.snd
    private val SBD = m.build.sbd
    private val mod = m.build.mod

    interface Output {
        /** A DMA block of unsigned 8-bit samples (80h silence) starting at [t]. */
        fun block(t: Double, data: ByteArray)

        /** One direct-DAC byte (23h silence, the speaker curve's middle), held for 1/13,257.6 s. */
        fun dac(t: Double, v: Int)

        /** One sample of the FM chip (signed 16 bits), at [OplChip.RATE] Hz, from [t] on. */
        fun opl(t: Double, v: Int) {}

        companion object {
            val NONE = object : Output {
                override fun block(t: Double, data: ByteArray) {}
                override fun dac(t: Double, v: Int) {}
            }
        }
    }

    /** Emulated time in seconds: game ticks, or music interrupts while a tune owns INT 8. */
    var time = 0.0
        private set

    /** A tune owns INT 8 (until its fade-out ends or the speed hits 0). */
    val musicPlaying: Boolean get() = musicOn

    /** A driver voice still has sample data to mix. */
    val samplesPlaying: Boolean get() = (0 until 4).any { m.rw(SBD, 0x8F + 0x11 * it + 4) != 0 }

    /** Samples started (`sound_drv_sb_start`), tunes started. */
    var samplesStarted = 0
        private set
    var tunesStarted = 0
        private set

    private val tickS = pitDivisor / PIT_HZ
    private var sbNext = Double.NaN // the next SB interrupt while the chain runs
    private var musicOn = false
    private var d = 0 // the MOD player's block segment

    /** The FM chip; [oplWrites] sees each register write of a tune in FM mode. */
    private val chip = OplChip()
    private var chipSamples = 0L // what the chip has put out so far
    var oplWrites: ((reg: Int, value: Int) -> Unit)? = null

    // --- time ---------------------------------------------------------------

    /** Before the game's int8 body: with music, PIT interrupts until `cs:13fa` carries; else one tick. */
    override fun irq() {
        if (drb(0x0004) != 0) { // int8_handler 0dc9: in FM mode the tune runs on the game's own tick
            time += tickS
            sbUntil(time)
            renderChip()
            d = drw(0x1406)
            musicTick()
            return
        }
        if (!musicOn) {
            time += tickS
            sbUntil(time)
            return
        }
        while (musicOn) {
            time += 1 / MUSIC_HZ
            sbUntil(time)
            if (musicIrq()) break
        }
    }

    private fun sbUntil(t: Double) {
        while (!sbNext.isNaN() && sbNext <= t) {
            val at = sbNext
            sbNext = at + BLOCK / SB_HZ
            sbIrq(at)
        }
    }

    // --- statements ---------------------------------------------------------

    /** `9dh` (6302..6356, the SB path): `[0cc7]` owner, kind 80h, voice_alloc. */
    /** Sound effects started (`play`), for the MCP `sound` tool. */
    var plays = 0
        private set

    override fun play(seg: Int, ofs: Int, owner: Int) {
        plays++
        m.sg(0x0CC7, owner)
        m.sgb(0x0CCA, 0x80)
        voiceAlloc()
    }

    /** `6bh`/`6dh`: voice_alloc with kind 1 (a tone, no driver voice). */
    override fun voice() = voiceAlloc()

    /** `voice_alloc` `5f01`. */
    private fun voiceAlloc() {
        val bx = m.g(0x0CC7)
        val al = m.gsb(0x0CC9)
        var di = -1
        for ((n, v) in VOICES.withIndex()) {
            m.sgb(0x0CD5, n)
            if (m.gsb(v + 1) == al && (al < 0 || m.g(v + 0x0E) == bx)) {
                di = v
                break
            }
        }
        if (di < 0) { // 5fd3: the lowest priority
            m.sgb(0x0CD5, 0)
            di = VOICES[0]
            var low = m.gsb(VOICES[0] + 1)
            for (n in 1 until 4) {
                val v = VOICES[n]
                if (low > m.gsb(v + 1)) {
                    m.sgb(0x0CD5, n)
                    di = v
                    low = m.gsb(v + 1)
                }
            }
            if (al >= 0 && low < 0 && low != -128) return
            if (low > al) return
        }
        m.sgb(di, 0x80)
        m.sgb(di + 1, al)
        m.sgb(di + 2, m.gb(0x0CCA))
        m.sg(di + 0x0A, m.g(0x0CCD))
        m.sg(di + 0x0C, m.g(0x0CCF))
        m.sgb(di + 7, m.gb(0x0CD1))
        m.sgb(di + 6, 0)
        m.sg(di + 8, m.g(0x0CD3))
        m.sg(di + 4, m.g(0x0CCB))
        m.sg(di + 0x0E, m.g(0x0CC7))
        m.sgb(di, 2)
        if ((m.gb(0x0CCA) and 0x80) != 0) {
            m.sgb(0x0E1A, m.gb(0x0CD5))
            glueSample()
        }
    }

    /** `sound_glue_sample` `87f3`, card 3 (`[288e]` > 1: no priority test). */
    private fun glueSample() {
        m.sgb(0x0E0B, m.gb(0x0CC9))
        m.ww(Machine.CS, 0x0646, 0)
        m.sg(0x0E2C, m.g(0x0E14))
        m.sg(0x0E2E, m.g(0x0E0E))
        m.sgb(0x0E30, m.gb(0x0E0D))
        m.sg(0x0E28, m.g(0x0E10))
        m.sg(0x0E2A, m.g(0x0E12))
        var r = m.gsb(0x0E0D)
        if (r !in 1..0x14) r = 0x0A
        m.sgb(0x0E0D, r)
        m.sgb(0x0E0A, 1)
        sbStart()
    }

    /** `sound_drv_sb_start` `0941:2177`: driver voice `[0e1a]`, step = rate `[0e18]` / the mixer's 12000. */
    private fun sbStart() {
        val n = m.gb(0x0E1A)
        val si = 0x8F + 0x11 * n
        val mix = m.rw(SBD, 0xD3)
        m.ww(SBD, si, m.g(0x0E10))
        m.ww(SBD, si + 2, m.g(0x0E12))
        m.ww(SBD, si + 4, m.g(0x0E14))
        val rate = m.g(0x0E18)
        val q = rate / mix
        m.ww(SBD, si + 0x0C, q)
        val cx = (rate - q * mix) and 0xFFFF
        m.ww(SBD, si + 0x0E, ((0xFFFFL * cx / mix + 1) and 0xFFFF).toInt())
        m.ww(SBD, si + 8, 0)
        m.ww(SBD, si + 0x0A, 0)
        m.ww(SBD, si + 6, m.g(0x0E0E) - 1)
        sbUntil(time)
        samplesStarted++
        if (s8(m.rb(DRV, 0x2175)) <= 0) { // the chain is not running: kick it
            m.wb(DRV, 0x2175, 1)
            sbNext = time + BLOCK / SB_HZ
            sbIrq(time)
        }
    }

    /** `sound_stop_far` `8adb` (`[288e]` != 1): the glue queue and the four voices (`0941:2209`). */
    override fun stop() {
        m.ww(Machine.CS, mod, 0) // 8909/890b/8947 in Ishar 1
        m.ww(Machine.CS, mod + 2, mod + 0x34)
        m.wb(Machine.CS, mod + 0x3E, 0)
        for (n in 0 until 4) {
            m.sgb(0x0E1A, n)
            m.sgb(VOICES[n] + 1, 0x80)
            m.sgb(VOICES[n], 0)
            val si = 0x8F + 0x11 * n
            m.ww(SBD, si + 4, 0)
            m.ww(SBD, si + 8, 0)
            m.ww(SBD, si + 6, 0)
        }
    }

    // --- the sample mixer -----------------------------------------------------

    /** `sb_mix_irq` `0941:1f82`: start the block at `[5de]`, mix the next into `[5e0]`, swap. */
    private fun sbIrq(at: Double) {
        if (s8(m.rb(DRV, 0x2175)) <= 0) {
            m.wb(DRV, 0x2175, 0)
            sbNext = Double.NaN
            return
        }
        m.wb(DRV, 0x1AA9, m.rb(DRV, 0x1AA9) + 1)
        val mem = m.m
        val play = (m.rb(SBD, 0x5DC) shl 16) or m.rw(SBD, 0x5DE)
        out.block(at, mem.copyOfRange(play, play + BLOCK))
        var dx = 0
        for (n in 0 until 4) {
            val si = 0x8F + 0x11 * n
            dx = (dx + m.rw(SBD, si + 6) + m.rw(SBD, si + 4) - m.rw(SBD, si + 8)) and 0xFFFF
            if (dx != 0) break
        }
        val o = (m.rb(SBD, 0x5DD) shl 16) or m.rw(SBD, 0x5E0)
        if (dx == 0) {
            if (m.rb(DRV, 0x1F7E) == 0) return
            m.wb(DRV, 0x1F7E, 0)
            mem.fill(0x80.toByte(), o, o + BLOCK)
        } else {
            m.wb(DRV, 0x1F7E, 1)
            val acc = IntArray(BLOCK)
            for (n in 0 until 4) sbVoice(n, acc)
            val wbuf = (SBD shl 4) + 0x3DC
            for (i in 0 until BLOCK) {
                val v = acc[i] and 0xFFFF
                mem[wbuf + 2 * i] = v.toByte()
                mem[wbuf + 2 * i + 1] = (v ushr 8).toByte()
                mem[o + i] = ((s16(v) shr 2) + 0x80).toByte()
            }
        }
        val a = m.rw(SBD, 0x5DE)
        m.ww(SBD, 0x5DE, m.rw(SBD, 0x5E0))
        m.ww(SBD, 0x5E0, a)
        val p = m.rb(SBD, 0x5DC)
        m.wb(SBD, 0x5DC, m.rb(SBD, 0x5DD))
        m.wb(SBD, 0x5DD, p)
    }

    /** `2067..20d7` for one voice: 256 signed bytes at the step; a repeat restarts, the last end frees it. */
    private fun sbVoice(n: Int, acc: IntArray) {
        val mem = m.m
        val v = 0x8F + 0x11 * n
        fun r(o: Int) = m.rw(SBD, v + o)
        val base = r(0)
        val seg = r(2)
        var si = (base + r(8)) and 0xFFFF
        var start = si
        var frac = r(0x0A)
        val step = r(0x0C)
        val fstep = r(0x0E)
        var i = 0
        while (i < BLOCK) {
            if (((si - base) and 0xFFFF) >= r(4)) {
                si = base
                m.ww(SBD, v + 8, 0)
                start = si
                if (r(6) == 0) {
                    m.ww(SBD, v + 4, 0)
                    m.ww(SBD, v + 8, 0)
                    m.sgb(VOICES[n] + 1, 0x80)
                    m.sgb(VOICES[n], 0)
                    m.ww(SBD, v + 0x0A, frac)
                    return
                }
                m.ww(SBD, v + 6, r(6) - 1)
                i++
                continue
            }
            acc[i] += mem[(seg shl 4) + si].toInt()
            frac += fstep
            si = (si + step + (frac ushr 16)) and 0xFFFF
            frac = frac and 0xFFFF
            i++
        }
        m.ww(SBD, v + 0x0A, frac)
        m.ww(SBD, v + 8, r(8) + ((si - start) and 0xFFFF))
    }

    // --- the MOD player ---------------------------------------------------------

    override fun music(kind: SoundSink.Music, vararg args: Int) {
        when (kind) {
            SoundSink.Music.PLAY -> musicStart()
            SoundSink.Music.FADE -> musicFade()
            SoundSink.Music.PARAM -> {}
        }
    }

    /** `music_start` `86ec` (card 3; the caller tests slow_machine `[2892]`). */
    private fun musicStart() {
        m.wb(Machine.CS, m.build.music, 0)
        var fm = 0
        for (s in 0 until 0x20) {
            val ofs = m.g(0x0CF6 + 8 * s)
            val seg = m.g(0x0CF8 + 8 * s)
            if (m.rb(seg, ofs - 0x10) == 6) fm++
        }
        val fmMode = fm >= 3 // Ishar 2's pcmusic*.io: kind-6 patch records, played on the OPL
        if (fmMode) m.wb(Machine.CS, m.build.music, 1)
        if (m.g(0x28B0) == 0) {
            val dx = memAllocTop(if (fmMode) 0x1000 else 0x6000)
            m.sg(0x28B0, dx)
            if (dx == 0) return
            if (fmMode) drvInitFm(dx - 0x510) else drvInit(dx)
        }
        drvPlay()
        m.sgb(0x0CD8, 1)
    }

    /** `mem_alloc_top` `0a14`: paragraphs from `[0b96]` down; -> segment or 0. */
    private fun memAllocTop(size: Int): Int {
        val p = ((size + 0x10) ushr 4) + 1
        val bx = m.g(0x0B96) - p
        if (bx < m.g(0x0BCA)) return 0
        m.sg(0x0B96, bx)
        m.ww(bx, 0, p)
        return bx + 1
    }

    /** `music_drv_init` `0941:0131` for the SB (`snd_init_sb` `11ed`): volume pages, step table, channels. */
    private fun drvInit(seg: Int) {
        m.wb(DRV, 0x0013, 0)
        m.wb(DRV, 0x0005, 0)
        m.ww(DRV, 0x0002, 0x5A)
        m.ww(DRV, 0x1406, seg)
        m.ww(DRV, 0x0000, m.gb(0x2890))
        // snd_init_sb 1226: the DSP port into the output stub, the stub over the mixer's head
        m.wb(DRV, 0x2175, 0xFF)
        val port = m.rw(SBD, 0x8A) + 0x0C
        m.ww(DRV, 0x1274, port)
        m.ww(DRV, 0x1284, port)
        m.ww(DRV, 0x059F, 0x066F)
        for (i in 0 until 0x18) m.wb(DRV, 0x066F + i, m.rb(DRV, 0x1280 + i))
        m.wb(DRV, 0x11EB, 1)
        val mem = m.m
        val base = seg shl 4
        for (b in 0 until 256) mem[base + 0x4000 + b] = (b + 0x80).toByte()
        for (vol in 0 until 0x40) {
            for (b in 0 until 256) {
                mem[base + vol * 256 + b] = (((((s8(b) * vol) and 0xFFFF) ushr 6) + 0x80) and 0xFF).toByte()
            }
        }
        m.ww(seg, 0x4100, 0)
        m.ww(seg, 0x4102, 0)
        val n = (3L * 0x5A shl 16) + 0xD2L * 0x5A
        for (p in 1 until 0x400) {
            val q = n / p
            m.ww(seg, 0x4100 + 4 * p, (q and 0xFFFF).toInt())
            m.ww(seg, 0x4102 + 4 * p, ((q ushr 16) and 0xFFFF).toInt())
        }
        channelsReset()
        musicOn = true
    }

    /** `music_drv_init` in FM mode: no mixer, no timer, no tables; the channel state at [seg]`:5100` is the block's start. */
    private fun drvInitFm(seg: Int) {
        m.wb(DRV, 0x0013, 0)
        m.wb(DRV, 0x0005, 1)
        m.ww(DRV, 0x0002, 0x5A)
        m.ww(DRV, 0x1406, seg)
        m.ww(DRV, 0x0000, m.gb(0x2890))
        channelsReset()
        chipSamples = (time * OplChip.RATE).toLong() // the chip was silent since the last tune: no need to render that
        oplWrite(0x01, 0) // opl_reset 2647
        oplWrite(0x08, 0)
        m.wb(DRV, 0x0004, 1)
        musicOn = true
    }

    /** `0617`. */
    private fun channelsReset() {
        for (si in intArrayOf(0, 0x20, 0x40, 0x60)) {
            m.ww(DRV, 0x698 + si, 0)
            m.wb(DRV, 0x69C + si, 0)
            m.ww(DRV, 0x6A2 + si, 0)
            m.ww(DRV, 0x6A6 + si, 1)
        }
        m.ww(DRV, 0x072E, 0)
        m.wb(DRV, 0x079A, 0x20)
    }

    /** `music_drv_play` `0941:0297`: the song `[0ce2]:[0ce0]`, fade-in `[0ce4]`, hold `[0ce8]`, fade-out `[0ce6]`. */
    private fun drvPlay() {
        val song = m.g(0x0CE2)
        val di = m.g(0x0CE0)
        m.ww(DRV, 0x0016, song)
        m.ww(DRV, 0x000E, m.g(0x0CE4)); m.ww(DRV, 0x000C, m.g(0x0CE4))
        m.ww(DRV, 0x0010, m.g(0x0CE8))
        m.ww(DRV, 0x000A, m.g(0x0CE6)); m.ww(DRV, 0x0008, m.g(0x0CE6))
        m.ww(DRV, 0x0018, di)
        val vol = m.gb(0x0CDB) ushr 1
        m.wb(DRV, 0x0006, vol)
        m.wb(DRV, 0x0007, vol)
        m.wb(DRV, 0x0012, 0)
        m.ww(DRV, 0x1402, song)
        // music_song_start 05af
        m.wb(DRV, 0x13F2, m.rb(song, di))
        m.wb(DRV, 0x13F1, 0)
        m.wb(DRV, 0x13F0, 0)
        m.wb(DRV, 0x13EF, 6)
        val dseg = m.rw(DRV, 0x1406)
        for (i in 0 until 0x80) m.wb(dseg, 0x51F7 + i, m.rb(song, di + 2 + i))
        m.ww(DRV, 0x1400, (song + m.rb(song, di + 2) * 0x40) and 0xFFFF)
        m.ww(DRV, 0x13FE, (di + 0x86) and 0xFFFF)
        channelsReset()
        // music_load_instruments 03a5
        for (s in 0 until 0x1F) {
            val ofs = m.g(0x0CF6 + 8 * s)
            val seg = m.g(0x0CF8 + 8 * s)
            val o = 2 + 2 * s
            m.ww(dseg, 0x5277 + o, seg)
            m.ww(dseg, 0x53B7 + o, ofs)
            val loop = m.rw(seg, ofs - 8)
            m.ww(dseg, 0x52F7 + o, if (loop != 0) (loop + ofs) and 0xFFFF else 0xFFFF)
            var end = m.rw(seg, ofs - 4)
            if (end == 0) end = m.rw(seg, ofs - 0x0E) - 0x10
            m.ww(dseg, 0x5337 + o, (end + ofs) and 0xFFFF)
            m.wb(dseg, 0x52B7 + o, 0x40)
            m.wb(dseg, 0x5377 + o, 0)
            m.wb(dseg, 0x52B8 + o, 0x40)
            m.wb(dseg, 0x5378 + o, 0)
        }
        m.ww(DRV, 0x072E, 0xF7)
        m.wb(DRV, 0x079A, 0x20)
        m.wb(DRV, 0x071B, 0)
        m.wb(DRV, 0x0013, 1)
        musicOn = true // again after an FM tune silenced it
        tunesStarted++
    }

    /** `music_fade` `8a4d`: `[0ce6]` ticks of fade (`music_drv_fade` `0309`), or stop. */
    private fun musicFade() {
        if (m.gb(0x0CD8) == 0) return
        val e = m.g(0x0CE6)
        if (e != 0) {
            m.ww(DRV, 0x0008, e)
            m.ww(DRV, 0x000A, e)
            m.wb(DRV, 0x0007, m.rb(DRV, 0x0006))
            m.wb(DRV, 0x0012, 1)
        } else {
            musicStop()
        }
    }

    /** `music_stop` `8a97`: the PIT back (`music_drv_stop` `019d`), the block freed. */
    private fun musicStop() {
        musicOn = false
        if (drb(0x0005) != 0) { // music_drv_stop 019d
            dwb(0x0004, 0)
            oplSilence()
        }
        val dseg = m.g(0x28B0)
        if (dseg != 0) {
            if (dseg - 1 == m.g(0x0B96)) m.sg(0x0B96, dseg - 1 + m.rw(dseg - 1, 0))
            m.sg(0x28B0, 0)
        }
        m.sgb(0x0E0A, 0)
        m.sgb(0x0CD8, 0)
        m.wb(Machine.CS, m.build.music, 0)
    }

    /** `music_mix_irq` `066f`: send the previous byte (`cs:1408`), mix the four channels into the next. -> `cs:13fa` carried. */
    private fun musicIrq(): Boolean {
        val mem = m.m
        val dr = DRV shl 4
        out.dac(time, mem[dr + 0x1408].toInt() and 0xFF)
        val db = m.rw(DRV, 0x1406) shl 4
        var cx = 0
        for (si in intArrayOf(0, 0x20, 0x40, 0x60)) {
            val ch = db + 0x5185 + si
            var di = (mem[ch].toInt() and 0xFF) or ((mem[ch + 1].toInt() and 0xFF) shl 8)
            val es = (mem[ch + 2].toInt() and 0xFF) or ((mem[ch + 3].toInt() and 0xFF) shl 8)
            val f = ((mem[ch + 4].toInt() and 0xFF) or ((mem[ch + 5].toInt() and 0xFF) shl 8)) + m.rw(DRV, 0x698 + si)
            mem[ch + 4] = f.toByte()
            mem[ch + 5] = (f ushr 8).toByte()
            di = (di + s8(m.rb(DRV, 0x69C + si)) + (f ushr 16)) and 0xFFFF
            val bl = mem[(es shl 4) + di].toInt() and 0xFF
            cx += mem[db + ((bl + m.rw(DRV, 0x6A2 + si)) and 0xFFFF)].toInt() and 0xFF
            if (di >= m.rw(DRV, 0x6A6 + si)) {
                di = m.rw(m.rw(DRV, 0x1406), 0x5108 + si)
                if (di == 0xFFFF) m.wb(DRV, 0x6A3 + si, 0)
            }
            mem[ch] = di.toByte()
            mem[ch + 1] = (di ushr 8).toByte()
        }
        var bl = ((cx ushr 2) xor 0x80) and 0xFFFF
        bl = mem[db + ((bl + m.rw(DRV, 0x071A)) and 0xFFFF)].toInt() and 0xFF
        mem[dr + 0x1408] = m.rb(DRV, CURVE + bl).toByte()
        val a = m.rw(DRV, 0x13FA) + m.rw(DRV, 0x072E)
        m.ww(DRV, 0x13FA, a)
        if ((a ushr 16) != 0) {
            musicTick()
            return true
        }
        return false
    }

    // channel state at the block's 5100 + 20h n
    private fun b(o: Int) = m.rb(d, o)
    private fun sb(o: Int, v: Int) = m.wb(d, o, v)
    private fun w(o: Int) = m.rw(d, o)
    private fun sw(o: Int, v: Int) = m.ww(d, o, v)
    private fun drb(o: Int) = m.rb(DRV, o)
    private fun dwb(o: Int, v: Int) = m.wb(DRV, o, v)
    private fun drw(o: Int) = m.rw(DRV, o)
    private fun dww(o: Int, v: Int) = m.ww(DRV, o, v)

    /** `music_tick` `0767`. */
    private fun musicTick() {
        if (drb(0x0013) == 0 || drb(0x0014) != 0) return
        d = drw(0x1406)
        if (!envelope()) return
        val speed = drb(0x13EF)
        if (speed == 0) {
            musicStop()
            return
        }
        val c = (drb(0x13F0) + 1) and 0xFF
        dwb(0x13F0, c)
        if ((((drb(0x079A) * speed) ushr 5) and 0xFF) > c) {
            for (n in 0 until 4) channelEffects(0x20 * n)
        } else {
            dwb(0x13F0, 0)
            musicRow()
        }
        while (drb(0x13F4) != 0) nextOrder()
    }

    /** `music_envelope` `0322`: fade-in, hold (negative: forever), fade-out; the end of the fade-out stops the music. */
    private fun envelope(): Boolean {
        val vol = drb(0x0007)
        if (drb(0x0012) == 0) {
            var c = drw(0x000C)
            if (c != 0) {
                c--
                dww(0x000C, c)
                return setVolume((vol - (c * vol / drw(0x000E))) and 0xFF)
            }
            val h = drw(0x0010)
            if (h != 0) {
                if ((h and 0x8000) != 0) return true
                dww(0x0010, h - 1)
                return setVolume(vol)
            }
        }
        var f = drw(0x0008)
        if (f == 0) {
            musicStop() // music_tick goes on
            return true
        }
        f--
        dww(0x0008, f)
        return setVolume((f * vol / drw(0x000A)) and 0xFF)
    }

    private fun setVolume(al: Int): Boolean {
        dwb(0x0006, al)
        dwb(0x071B, al + 1)
        return true
    }

    /** `music_row` `07e8`, then `0a11`: the row pointer, pattern delay, the end of the pattern. */
    private fun musicRow() {
        val es = drw(0x1400)
        val di = drw(0x13FE)
        for (n in 0 until 4) noteRead(0x20 * n, es, (di + 4 * n) and 0xFFFF)
        dww(0x13FE, drw(0x13FE) + 0x10)
        if (drb(0x13F7) != 0) {
            dwb(0x13F8, drb(0x13F7))
            dwb(0x13F7, 0)
        }
        if (drb(0x13F8) != 0) {
            dwb(0x13F8, drb(0x13F8) - 1)
            if (drb(0x13F8) != 0) dww(0x13FE, drw(0x13FE) - 0x10)
        }
        val song = drw(0x0018)
        if (drb(0x13F5) != 0) {
            dwb(0x13F5, 1)
            dww(0x13FE, drb(0x13F3) * 16 + song + 0x86)
        }
        if (((drw(0x13FE) - song - 0x86) and 0xFFFF) >= 0x400) nextOrder()
    }

    /** `0a7a`: row `cs:13f3`, the next order (wrapping at the song length). */
    private fun nextOrder() {
        val song = drw(0x0018)
        dww(0x13FE, (drb(0x13F3) * 16 + song + 0x86) and 0xFFFF)
        dwb(0x13F3, 0)
        dwb(0x13F4, 0)
        var o = (drb(0x13F1) + 1) and 0x7F
        if (o == drb(0x13F2)) o = 0
        dwb(0x13F1, o)
        setPattern(o)
    }

    private fun setPattern(o: Int) = dww(0x1400, (b(0x51F7 + o) * 0x40 + drw(0x1402)) and 0xFFFF)

    private fun period(ft: Int, i: Int) = drw((PERIODS + ft * 72 + i) and 0xFFFF)

    /** `music_set_step` `0b55`: the step of period [p] (default the channel's). */
    private fun setStep(si: Int, p: Int = w(0x510C + si)) {
        val bx = (p * 4) and 0xFFFF
        dww(0x698 + si, w((0x4100 + bx) and 0xFFFF))
        dwb(0x69C + si, b((0x4102 + bx) and 0xFFFF))
    }

    /** `09ce` / `10fb`: the sample from the start, the step, the volume page. */
    private fun trigger(si: Int) {
        sw(0x5187 + si, w(0x5104 + si))
        sw(0x5185 + si, w(0x5110 + si))
        dww(0x6A6 + si, w(0x5106 + si))
        setStep(si)
        dwb(0x6A3 + si, b(0x510F + si))
    }

    private fun cellPeriod(si: Int): Int {
        val cell = w(0x5100 + si)
        return (((cell and 0xFF) shl 8) or (cell ushr 8)) and 0xFFF
    }

    /** `music_note_read` `0899`. */
    private fun noteRead(si: Int, es: Int, di: Int) {
        if (drb(0x0005) != 0) return noteReadFm(si, es, di)
        if (w(0x5100 + si) == 0 && b(0x5102 + si) == 0) setStep(si)
        sw(0x5100 + si, m.rw(es, di))
        sw(0x5102 + si, m.rw(es, di + 2))
        val s = (b(0x5102 + si) ushr 4) or (b(0x5100 + si) and 0xF0)
        if (s != 0) {
            val bx = 2 * s
            sw(0x5104 + si, w(0x5277 + bx))
            sw(0x5110 + si, w(0x53B7 + bx))
            val end = w(0x5337 + bx)
            sw(0x5106 + si, end)
            sw(0x5183 + si, end)
            sb(0x510E + si, b(0x5377 + bx))
            val v = b(0x52B7 + bx)
            sb(0x510F + si, v)
            dwb(0x6A3 + si, v)
            sw(0x5108 + si, w(0x52F7 + bx))
            sw(0x5181 + si, 0)
        }
        noteTail(si)
    }

    /** `0820`: the FM head of `music_note_read`, a note-on for the cell's patch. The tail keeps the (unheard) channel state. */
    private fun noteReadFm(si: Int, es: Int, di: Int) {
        if (w(0x5100 + si) == 0 && b(0x5102 + si) == 0) setStep(si)
        sw(0x5100 + si, m.rw(es, di))
        sw(0x5102 + si, m.rw(es, di + 2))
        val s = (b(0x5102 + si) ushr 4) or (b(0x5100 + si) and 0xF0)
        if (s != 0) {
            val bx = 2 * s
            val seg = w(0x5277 + bx)
            val ofs = w(0x53B7 + bx)
            sw(0x5104 + si, seg)
            sw(0x5110 + si, ofs)
            fmNoteOn(cellPeriod(si), ((di - drw(0x13FE)) and 0xFFFF) ushr 2, seg, ofs)
        }
        noteTail(si)
    }

    /** `091e`: the cell's period, its effects. */
    private fun noteTail(si: Int) {
        val per = cellPeriod(si)
        if (per == 0) return rowEffects(si)
        var e = w(0x5102 + si)
        e = ((e and 0xFF) shl 8) or (e ushr 8)
        if ((e and 0xFF0) == 0xE50) {
            sb(0x510E + si, b(0x5103 + si) and 0x0F)
        } else if (((e ushr 8) and 0x0F) == 3 || ((e ushr 8) and 0x0F) == 5) {
            periodFinetune(si)
            return rowEffects(si)
        } else if (((e ushr 8) and 0x0F) == 9) {
            rowEffects(si)
        }
        var bp = 0
        var found = false
        for (k in 0 until 36) {
            if (per >= period(0, bp)) {
                found = true
                break
            }
            bp += 2
        }
        if (!found) bp -= 2
        sw(0x510C + si, period(b(0x510E + si), bp))
        if ((e and 0xFF0) == 0xED0) return rowEffects(si)
        if ((b(0x511A + si) and 2) == 0) sb(0x5117 + si, 0)
        if ((b(0x511A + si) and 0x20) == 0) sb(0x5119 + si, 0)
        sb(0x518B + si, 1)
        trigger(si)
        rowEffects(si)
    }

    // --- the FM tunes (seg_0941 2454..269e) ---------------------------------------

    /** `music_fm_note_on` `2454`: voice = the cell's channel, or the one the patch names. */
    private fun fmNoteOn(period: Int, channel: Int, seg: Int, ofs: Int) {
        val pinned = m.rb(seg, ofs) != 0
        val voice = if (pinned) m.rb(seg, ofs + 1) else channel
        val p = ofs + 2
        fmKeyOff(voice)
        val first = FM_OPS[voice * 2]
        val second = FM_OPS[voice * 2 + 1]
        val connection = m.rb(seg, p + 0x0C)
        m.wb(seg, p + 0x19, connection)
        fmOperator(seg, p, first, second)
        fmOperator(seg, p + 0x0D, second, first)
        // a drum's single operator leaves its record's second half unread, and its wave comes from there
        val w = if (second == 0xFF) p + 0x0D else p + 0x1A
        oplWrite((0xE0 + first) and 0xFF, m.rb(seg, w))
        oplWrite((0xE0 + second) and 0xFF, m.rb(seg, w + 1))
        if (voice < 7) {
            val fb = (m.rb(seg, p + 2) shl 1) and 0xFF
            oplWrite(0xC0 + voice, ((connection xor 1) + fb) and 0xFF)
        }
        var note = 0
        while (note < 36 && period != drw(PERIODS + 2 * note)) note++
        if (voice < 6) {
            val v = drw(0x22C2 + 2 * note)
            oplWrite(0xA0 + voice, v and 0xFF)
            oplWrite(0xB0 + voice, (v ushr 8) or 0x20)
        } else { // 25de: a drum
            oplWrite(0xB0 + voice, 0)
            oplWrite(0xBD, drb(OPL_SHADOW + 0xBD) or (0x10 ushr (voice - 6)) or 0x20)
        }
    }

    /** `24e6`: one operator's 13 bytes; the volume scales the ones heard, not a modulator. */
    private fun fmOperator(seg: Int, si: Int, op: Int, other: Int) {
        if (op == 0xFF) return
        fun r(i: Int) = m.rb(seg, si + i)
        var level = r(8)
        if (r(0x0C) != 1 || s8(op) >= s8(other)) {
            val scaled = (drb(0x0006) * ((0x3F - level) and 0xFF)) ushr 6
            level = (0x3F - scaled) and 0xFF
        }
        oplWrite(0x40 + op, (((r(0) shl 6) and 0xFF) + level) and 0xFF)
        oplWrite(0x20 + op, (r(1) + ((r(5) shl 5) and 0xFF) + ((r(9) shl 7) and 0xFF) + ((r(0x0A) shl 6) and 0xFF) + ((r(0x0B) shl 4) and 0xFF)) and 0xFF)
        oplWrite(0x60 + op, (((r(3) shl 4) and 0xFF) + r(6)) and 0xFF)
        oplWrite(0x80 + op, (((r(4) shl 4) and 0xFF) + r(7)) and 0xFF)
    }

    /** `opl_key_off` `2600`. */
    private fun fmKeyOff(voice: Int) {
        if (voice < 6) {
            oplWrite(0xB0 + voice, 0)
        } else {
            val bit = 0x10 ushr (voice - 6)
            oplWrite(0xBD, (drb(OPL_SHADOW + 0xBD) and bit.inv()) or 0x20)
        }
    }

    /** `opl_silence` `2666`. */
    private fun oplSilence() {
        for (c in 0x16 downTo 1) oplWrite(0x40 + c - 1, 0x3F)
        for (c in 9 downTo 1) oplWrite(0xB0 + c - 1, 0)
        oplWrite(0xBD, 0)
    }

    /**
     * `opl_write` `240a`: the chip and the shadow `cs:230a`. A write takes the chip time, as the driver's delays gave it
     * (`opl_delay_short`/`_long`, about 27 us): a note's key-off and key-on must be samples apart or the note never restarts.
     */
    private fun oplWrite(reg: Int, value: Int) {
        dwb(OPL_SHADOW + reg, value)
        oplWrites?.invoke(reg, value)
        if (out === Output.NONE) return
        renderChip()
        chip.write(reg, value)
        repeat(SAMPLES_PER_WRITE) {
            out.opl(chipSamples.toDouble() / OplChip.RATE, chip.clock())
            chipSamples++
        }
    }

    /** The chip's samples up to [time]. Before a tick's writes: they take effect from the tick's time on. */
    private fun renderChip() {
        if (out === Output.NONE) return
        val until = (time * OplChip.RATE).toLong()
        while (chipSamples < until) {
            out.opl(chipSamples.toDouble() / OplChip.RATE, chip.clock())
            chipSamples++
        }
    }

    /** `music_period_finetune` `0c8d`: the tone-portamento target. */
    private fun periodFinetune(si: Int) {
        val per = cellPeriod(si)
        val ft = b(0x510E + si)
        var bp = 0
        while (per < period(ft, bp)) {
            bp += 2
            if (bp == 0x48) {
                bp = 0x46
                break
            }
        }
        if ((ft and 8) != 0 && bp != 0) bp -= 2
        val ax = period(ft, bp)
        sw(0x5114 + si, ax)
        val cur = w(0x510C + si)
        sb(0x5112 + si, 0)
        if (cur == ax) {
            sw(0x5114 + si, 0)
        } else if (cur >= ax) {
            sb(0x5112 + si, 1)
        }
    }

    /** `music_row_effects` `0fc6`. */
    private fun rowEffects(si: Int) {
        invertLoop(si)
        val t = b(0x5102 + si) and 0x0F
        val p = b(0x5103 + si)
        when (t) {
            9 -> return
            0x0B -> {
                val o = (p - 1) and 0xFF
                dwb(0x13F1, o)
                setPattern(o)
                dwb(0x13F3, 0)
                dwb(0x13F4, 1)
            }
            0x0D -> {
                val al = ((p ushr 4) * 10 + (p and 0x0F)) and 0xFF
                dwb(0x13F3, if (s8(al) >= 0x3F) 0 else al)
                dwb(0x13F4, 1)
            }
            0x0E -> fxE(si)
            0x0F -> {
                dwb(0x13F0, 0)
                dwb(0x13EF, p)
            }
            0x0C -> {
                val v = minOf(p, 0x40)
                sb(0x510F + si, v)
                dwb(0x6A3 + si, v)
            }
            else -> setStep(si)
        }
    }

    /** `music_fx_invert_loop` `11b2`: EFx moves a counter only (`5181`). */
    private fun invertLoop(si: Int) {
        val x = b(0x511B + si) ushr 4
        if (x == 0) return
        val c = (b(0x5180 + si) + drb(FUNK + x)) and 0xFF
        sb(0x5180 + si, c)
        if ((c and 0x80) != 0) {
            sb(0x5180 + si, 0)
            var ax = (w(0x5181 + si) + 1) and 0xFFFF
            if (ax >= w(0x5106 + si)) ax = w(0x5108 + si)
            sw(0x5181 + si, ax)
        }
    }

    /** `1001`: the extended effects. */
    private fun fxE(si: Int) {
        val p = b(0x5103 + si)
        val x = p ushr 4
        val y = p and 0x0F
        val tick = drb(0x13F0)
        when (x) {
            1 -> if (tick == 0) {
                dwb(0x13F6, 0x0F)
                slide(si, -1)
            }
            2 -> if (tick == 0) {
                dwb(0x13F6, 0x0F)
                slide(si, 1)
            }
            3 -> sb(0x511B + si, (b(0x511B + si) and 0xF0) or y)
            4 -> sb(0x511A + si, (b(0x511A + si) and 0xF0) or y)
            5 -> sb(0x510E + si, y)
            7 -> sb(0x511A + si, (b(0x511A + si) and 0x0F) or (y shl 4))
            9 -> if (y != 0) {
                if (tick == 0 && cellPeriod(si) != 0) return
                if (tick % y == 0) trigger(si)
            }
            0x0A -> if (tick == 0) volumeUp(si, y)
            0x0B -> if (tick == 0) volumeDown(si, y)
            0x0C -> if (tick == y) {
                sb(0x510F + si, 0)
                dwb(0x6A3 + si, 0)
            }
            0x0D -> if (tick == y && w(0x5100 + si) != 0) trigger(si)
            0x0F -> if (tick == 0) {
                sb(0x511B + si, (b(0x511B + si) and 0x0F) or (y shl 4))
                if (y != 0) invertLoop(si)
            }
        }
    }

    /** `0bf7` (down, floor 71h) / `0c49` (up, ceiling 358h): by the parameter masked with `cs:13f6`. */
    private fun slide(si: Int, sign: Int) {
        val bl = b(0x5103 + si) and drb(0x13F6)
        dwb(0x13F6, 0xFF)
        val p = (w(0x510C + si) + sign * bl) and 0xFFFF
        sw(0x510C + si, p)
        if (sign < 0 && (p and 0xFFF) <= 0x71) sw(0x510C + si, 0x71)
        if (sign > 0 && (p and 0xFFF) >= 0x358) sw(0x510C + si, 0x358)
        setStep(si)
    }

    private fun volumeUp(si: Int, n: Int) {
        var v = (b(0x510F + si) + n) and 0xFF
        if (s8(v) > 0x40) v = 0x40
        sb(0x510F + si, v)
        dwb(0x6A3 + si, v)
    }

    private fun volumeDown(si: Int, n: Int) {
        var v = (b(0x510F + si) - n) and 0xFF
        if (s8(v) <= 0) v = 0
        sb(0x510F + si, v)
        dwb(0x6A3 + si, v)
    }

    /** `0f05`: up by the high nibble, else down by the low one. */
    private fun volumeSlide(si: Int) {
        val p = b(0x5103 + si)
        if ((p ushr 4) != 0) volumeUp(si, p ushr 4) else volumeDown(si, p and 0x0F)
    }

    /** `music_channel_effects` `0ada`: the ticks between rows. */
    private fun channelEffects(si: Int) {
        invertLoop(si)
        var e = w(0x5102 + si)
        e = (((e and 0xFF) shl 8) or (e ushr 8)) and 0xFFF
        if (e == 0) return setStep(si)
        when (val t = e ushr 8) {
            0x0E -> fxE(si)
            0 -> arpeggio(si)
            1 -> slide(si, -1)
            2 -> slide(si, 1)
            3 -> {
                val p = b(0x5103 + si)
                if (p != 0) {
                    sb(0x5113 + si, p)
                    sb(0x5103 + si, 0)
                }
                tonePorta(si)
            }
            4 -> {
                vibratoParam(si, 0x5116)
                vibrato(si)
            }
            5 -> {
                tonePorta(si)
                volumeSlide(si)
            }
            6 -> {
                vibrato(si)
                volumeSlide(si)
            }
            else -> {
                setStep(si)
                if (t == 7) {
                    vibratoParam(si, 0x5118)
                    tremolo(si)
                } else if (t == 0x0A) {
                    volumeSlide(si)
                }
            }
        }
    }

    /** `0b6f`: the base note, +x, +y semitones on ticks 0, 1, 2 mod 3. */
    private fun arpeggio(si: Int) {
        val r = drb(0x13F0) % 3
        if (r == 0) return setStep(si)
        val p = b(0x5103 + si)
        val k = if (r == 1) p ushr 4 else p and 0x0F
        var di = PERIODS + b(0x510E + si) * 72
        val cur = w(0x510C + si)
        for (i in 0 until 36) {
            val ax = drw((di + 2 * k) and 0xFFFF)
            if (cur >= drw(di and 0xFFFF)) return setStep(si, ax)
            di += 2
        }
    }

    /** `music_fx_tone_porta` `0d08`, glissando (`511b` low nibble) to the table. */
    private fun tonePorta(si: Int) {
        val target = w(0x5114 + si)
        if (target == 0) return
        val bx = b(0x5113 + si)
        if (b(0x5112 + si) == 0) {
            val p = (w(0x510C + si) + bx) and 0xFFFF
            sw(0x510C + si, p)
            if (s16(target) <= s16(p)) {
                sw(0x510C + si, target)
                sw(0x5114 + si, 0)
            }
        } else {
            val p = (w(0x510C + si) - bx) and 0xFFFF
            sw(0x510C + si, p)
            if (s16(target) >= s16(p)) {
                sw(0x510C + si, target)
                sw(0x5114 + si, 0)
            }
        }
        var p = w(0x510C + si)
        if ((b(0x511B + si) and 0x0F) != 0) {
            val ft = b(0x510E + si)
            var bp = 0
            while (s16(p) < s16(period(ft, bp))) {
                bp += 2
                if (bp == 0x48) {
                    bp = 0x46
                    break
                }
            }
            p = period(ft, bp)
        }
        setStep(si, p)
    }

    /** `0da4` / `0e50`: non-zero nibbles of the parameter replace the stored ones. */
    private fun vibratoParam(si: Int, at: Int) {
        val p = b(0x5103 + si)
        if (p == 0) return
        var al = b(at + si)
        if ((p and 0x0F) != 0) al = (al and 0xF0) or (p and 0x0F)
        if ((p and 0xF0) != 0) al = (al and 0x0F) or (p and 0xF0)
        sb(at + si, al)
    }

    /** The vibrato/tremolo table value: sine, ramp (sign of `5117` for both, as the original), square. */
    private fun wave(si: Int, pos: Int, wf: Int): Int {
        val idx = (pos ushr 2) and 0x1F
        if (wf == 0) return drb(SINE + idx)
        val bl = (idx shl 3) and 0xFF
        if (wf == 1) return if ((b(0x5117 + si) and 0x80) != 0) 0xFF - bl else bl
        return 0xFF
    }

    /** `music_fx_vibrato` `0dcb`. */
    private fun vibrato(si: Int) {
        val pos = b(0x5117 + si)
        val cmd = b(0x5116 + si)
        val amt = (wave(si, pos, b(0x511A + si) and 3) * (cmd and 0x0F)) ushr 7
        var p = w(0x510C + si)
        p = (if ((pos and 0x80) != 0) p - amt else p + amt) and 0xFFFF
        setStep(si, p)
        sb(0x5117 + si, pos + ((cmd ushr 2) and 0x3C))
    }

    /** `0e77`. */
    private fun tremolo(si: Int) {
        val pos = b(0x5119 + si)
        val cmd = b(0x5118 + si)
        val amt = (wave(si, pos, (b(0x511A + si) ushr 4) and 3) * (cmd and 0x0F)) ushr 7
        var v = b(0x510F + si)
        v = (if ((pos and 0x80) != 0) v - amt else v + amt) and 0xFFFF
        if ((v and 0x8000) != 0) v = 0
        if ((v and 0xFF) > 0x40) v = 0x40
        dwb(0x6A3 + si, v)
        sb(0x5119 + si, pos + ((cmd ushr 2) and 0x3C))
    }

    companion object {
        const val PIT_HZ = 1193182.0
        const val MUSIC_HZ = PIT_HZ / 0x5A
        const val SB_HZ = 1000000.0 / 83 // sb_set_rate 1a75: 256 - 1000000 / 12000
        const val BLOCK = 256
        private val VOICES = intArrayOf(0x1DFD, 0x1E0D, 0x1E1D, 0x1E2D)
        private const val OPL_SHADOW = 0x230A
        private const val SAMPLES_PER_WRITE = 2
        // `cs:24d0`: each voice's operators (register offsets); the drums (7..10) have one
        private val FM_OPS = intArrayOf(
            0, 3, 1, 4, 2, 5, 8, 0x0B, 9, 0x0C, 0x0A, 0x0D, 0x10, 0x13, 0x14, 0xFF, 0x12, 0xFF, 0x15, 0xFF, 0x11, 0xFF,
        )
        private const val PERIODS = 0x1439 // cs: 16 finetunes x 36 periods
        private const val FUNK = 0x1409
        private const val SINE = 0x1419
        private const val CURVE = 0x001A

        /**
         * `int8_handler` `0dd6` on every card: `voice_lowest_priority` `222a`, then
         * `voice_level_ramp` `224e` for voices 0..2 whose state byte is positive.
         */
        fun voicesTick(m: Machine) {
            var lo = m.gsb(0x1DFE)
            m.sgb(0x0CD7, 0)
            if (lo < m.gsb(0x1E0E)) {
                lo = m.gsb(0x1E0E)
                m.sgb(0x0CD7, 1)
            }
            if (lo < m.gsb(0x1E1E)) m.sgb(0x0CD7, 2)
            for (k in 0 until 3) {
                val di = VOICES[k]
                if (m.gsb(di) > 0) levelRamp(m, di)
            }
        }

        /** `voice_level_ramp` `224e`: duration, level and pitch steps; the end or a negative value frees the voice. */
        private fun levelRamp(m: Machine, di: Int) {
            if (m.gsb(di + 2) < 0) return
            val d = (m.g(di + 4) - 1) and 0xFFFF
            m.sg(di + 4, d)
            if (d != 0) {
                var a = (m.g(di + 6) + m.g(di + 8)) and 0xFFFF
                if ((a and 0x8000) == 0) {
                    m.sg(di + 6, minOf(a, 0x7FFF))
                    a = (m.g(di + 0x0A) + m.g(di + 0x0C)) and 0xFFFF
                    if ((a and 0x8000) == 0) {
                        m.sg(di + 0x0A, a)
                        return
                    }
                }
            }
            m.sg(di + 6, 0)
            m.sg(di + 0x0A, 0)
            m.sgb(di + 1, 0x80)
            m.sgb(di + 2, 0)
            m.sgb(di, 0)
        }
    }
}
