package dev.pelotasplus.ishar.engine

/**
 * The Sound Blaster's streams as one 16-bit mono PCM stream: the DMA blocks (12,048 Hz, silence 80h) and the music's DAC bytes
 * (13,257.6 Hz, silence 23h), each byte held until the next as the DAC holds it, summed
 * around their silences and clipped, plus the FM chip's samples. The SB has one DAC that both write in turn; the sum
 * is how it sounds, not the card's analogue output.
 *
 * Events arrive in time order per stream; a block reaches up to 21 ms past its start, so
 * [take] only hands out samples before the given emulated time.
 */
class PcmMixer(val rate: Int = 22050) : SoundBlaster.Output {
    private var acc = IntArray(rate)
    private var base = 0L // the output sample at acc[0]
    private var used = 0 // acc[used..] and fm[used..] are all 0
    private var fm = IntArray(rate) // the FM chip's samples, 16 bits x 256, averaged into ours
    /** Off, the tunes' streams are dropped here: the game and the player run on as they do with it on, so a tune comes back in its place. */
    @kotlin.concurrent.Volatile
    var musicOn = true

    private val fmWeight = FM_GAIN * rate * 256 / OplChip.RATE

    /** The buffer's size in samples; an unpaced start grows it, its [take] shrinks it. */
    internal val capacity get() = acc.size

    override fun block(t: Double, data: ByteArray) {
        for (i in data.indices) hold(t + i / SoundBlaster.SB_HZ, 1 / SoundBlaster.SB_HZ, (data[i].toInt() and 0xFF) - 0x80)
    }

    override fun dac(t: Double, v: Int) {
        if (musicOn) hold(t, 1 / SoundBlaster.MUSIC_HZ, v - 0x23)
    }

    override fun opl(t: Double, v: Int) {
        if (v == 0 || !musicOn) return
        val x = (t * rate).toLong() - base
        if (x < 0) return
        val i = x.toInt()
        grow(i)
        fm[i] += v * fmWeight
        if (i >= used) used = i + 1
    }

    private fun grow(i: Int) {
        if (i < acc.size) return
        val n = maxOf(acc.size * 2, i + 1)
        acc = acc.copyOf(n)
        fm = fm.copyOf(n)
    }

    private fun hold(t: Double, len: Double, v: Int) {
        if (v == 0) return
        val j = (t * rate).toLong()
        val k = ((t + len) * rate).toLong()
        for (x in maxOf(j, base) until k) {
            val i = (x - base).toInt()
            grow(i)
            acc[i] += v
            if (i >= used) used = i + 1
        }
    }

    /** The samples before emulated time [t] (seconds), 8-bit levels scaled to 16 bits. */
    fun take(t: Double): ShortArray {
        val n = ((t * rate).toLong() - base).toInt().coerceAtLeast(0)
        val out = ShortArray(n) {
            if (it < used) ((acc[it].coerceIn(-128, 127) shl 8) + (fm[it] shr 8)).coerceIn(-32768, 32767).toShort() else 0
        }
        val left = maxOf(used - n, 0)
        if (left > 0) {
            acc.copyInto(acc, 0, n, used)
            fm.copyInto(fm, 0, n, used)
        }
        acc.fill(0, left, used)
        fm.fill(0, left, used)
        used = left
        if (acc.size > rate && used <= rate) {
            acc = acc.copyOf(rate)
            fm = fm.copyOf(rate)
        }
        base += n
        return out
    }

    private companion object {
        /** The chip's voice peaks near 2,000 of 32,767; this sits pcmusic1 under the sound effects, as on a Sound Blaster's mixer. */
        const val FM_GAIN = 4
    }
}
