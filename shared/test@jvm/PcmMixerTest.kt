package dev.pelotasplus.ishar.engine

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PcmMixerTest {
    @Test
    fun `silence of both streams is zero`() {
        val m = PcmMixer(1000)
        m.block(0.0, ByteArray(256) { 0x80.toByte() })
        m.dac(0.0, 0x23)
        assertTrue(m.take(0.02).all { it.toInt() == 0 })
    }

    @Test
    fun `bytes are held, summed around their silences, clipped and handed out once`() {
        val m = PcmMixer(1000)
        m.block(0.0, ByteArray(256) { 0x90.toByte() }) // +16 for 256 / 12,048 s
        m.dac(0.0, 0x23 + 100) // +100 for 1 / 13,257.6 s: shorter than one 1 kHz sample
        m.dac(0.00996, 0xFF) // +220 over sample 9, clipped with the block's +16
        val a = m.take(0.005)
        assertEquals(5, a.size)
        assertTrue(a.all { it.toInt() == 16 shl 8 })
        val b = m.take(0.030)
        assertEquals(25, b.size)
        assertEquals(127 shl 8, b[4].toInt()) // sample 9
        assertEquals(16 shl 8, b[5].toInt())
        assertEquals(0, b.last().toInt()) // after the block's 21 ms
    }

    /** The mixer before it kept its high-water mark: moved its whole buffer on every take. The reference for the output. */
    private class WholeBufferMixer(val rate: Int) {
        var acc = IntArray(rate)
        var base = 0L

        fun block(t: Double, data: ByteArray) {
            for (i in data.indices) hold(t + i / SoundBlaster.SB_HZ, 1 / SoundBlaster.SB_HZ, (data[i].toInt() and 0xFF) - 0x80)
        }

        fun dac(t: Double, v: Int) = hold(t, 1 / SoundBlaster.MUSIC_HZ, v - 0x23)

        private fun hold(t: Double, len: Double, v: Int) {
            if (v == 0) return
            val j = (t * rate).toLong()
            val k = ((t + len) * rate).toLong()
            for (x in maxOf(j, base) until k) {
                val i = (x - base).toInt()
                if (i >= acc.size) acc = acc.copyOf(maxOf(acc.size * 2, i + 1))
                acc[i] += v
            }
        }

        fun take(t: Double): ShortArray {
            val n = ((t * rate).toLong() - base).toInt().coerceAtLeast(0)
            if (n > acc.size) acc = acc.copyOf(n)
            val out = ShortArray(n) { (acc[it].coerceIn(-128, 127) shl 8).toShort() }
            acc.copyInto(acc, 0, n, acc.size)
            acc.fill(0, acc.size - n, acc.size)
            base += n
            return out
        }
    }

    /** Random blocks, DAC bytes and takes of every size, including a long stretch with no take (an unpaced start). */
    @Test
    fun `the output is sample for sample the old mixer's`() {
        for (seed in 1..20) {
            val r = kotlin.random.Random(seed)
            val new = PcmMixer(22050)
            val old = WholeBufferMixer(22050)
            var t = 0.0
            repeat(3000) { step ->
                t += if (step == 500) 40.0 else r.nextDouble() * 0.05
                when (r.nextInt(4)) {
                    0 -> {
                        val at = t + r.nextDouble() * 0.02
                        val data = r.nextBytes(r.nextInt(1, 400))
                        new.block(at, data)
                        old.block(at, data)
                    }
                    1 -> {
                        val at = t + r.nextDouble() * 0.02
                        val v = r.nextInt(256)
                        new.dac(at, v)
                        old.dac(at, v)
                    }
                    2 -> assertContentEquals(old.take(t), new.take(t), "seed $seed step $step")
                    else -> {}
                }
            }
        }
    }

    /** An unpaced start asks for half a minute at once; afterwards the buffer is back to size, not kept for the session. */
    @Test
    fun `a big take does not leave a big buffer`() {
        val m = PcmMixer(22050)
        m.dac(32.0, 0x40)
        assertTrue(m.capacity > 22050 * 30, "it grew: ${m.capacity}")
        m.take(40.0)
        assertTrue(m.capacity <= 22050, "and shrank: ${m.capacity}")
    }

    @Test
    fun `music off drops the tunes and keeps the effects`() {
        val m = PcmMixer(1000)
        m.musicOn = false
        m.block(0.0, ByteArray(256) { 0x90.toByte() })
        m.dac(0.0, 0x23 + 100)
        m.opl(0.0, 20000)
        assertTrue(m.take(0.005).all { it.toInt() == 16 shl 8 })
        m.musicOn = true
        m.opl(0.1, 20000)
        assertTrue(m.take(0.2).any { it.toInt() != 0 })
    }
}
