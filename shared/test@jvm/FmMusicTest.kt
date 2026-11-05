package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Ishar 2's in-game tunes, pcmusic1..3 on the OPL. */
class FmMusicTest {
    private suspend fun files() = GameFiles.fromResourceRepository(ResourceRepositoryImpl(GameBuild.ISHAR2), saveSize = null)

    /** A start that picks one tune: the seed and main.io's last tune (51c7, which the pick avoids) decide it. */
    private fun session(seed: Int, last: Int): Session {
        val base = Starts.ISHAR2_SKIP_INTRO_MUSIC
        val poke = Session.Event.Poke(GameBuild.ISHAR2.globals, 0x51C7 + 0x2A0, listOf(last))
        return Session("tune", base.cpuSpeed, seed, base.events + (100 to listOf(poke)))
    }

    private suspend fun stream(seed: Int, last: Int): List<String> {
        val s = session(seed, last)
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B', sink = { SoundBlaster(it) })
        val lines = ArrayList<String>()
        var tick0 = -1
        (vm.sound as SoundBlaster).oplWrites = { r, v ->
            if (tick0 < 0) tick0 = vm.ticks
            lines += "${vm.ticks - tick0} %02x %02x".format(r, v)
        }
        for (f in 1 until FRAMES) s.step(vm, f)
        return lines
    }

    private fun crc(bytes: ByteArray) = CRC32().apply { update(bytes) }.value

    /**
     * The register writes of each tune for about 4.5 minutes, tick by tick. These CRCs are of streams that equal the game's
     * own driver (music_drv_init, music_drv_play, music_fm_tick) run under Unicorn on the same memory, write for write.
     */
    @Test
    fun `each tune writes what the game's driver writes`() = runTest {
        assertEquals(CRC_TUNE_A, crc(stream(0, 0).joinToString("\n").encodeToByteArray()))
        assertEquals(CRC_TUNE_B, crc(stream(257, 0).joinToString("\n").encodeToByteArray()))
        assertEquals(CRC_TUNE_C, crc(stream(0, 2).joinToString("\n").encodeToByteArray()))
    }

    /** A minute of a tune through [OplChip]: the CRC is ymfm's C++ on the same writes, sample for sample. */
    @Test
    fun `the chip sounds like ymfm`() = runTest {
        val chip = OplChip()
        val out = ByteArray(OplChip.RATE * 2 * 60)
        var pos = 0
        fun advance(until: Int) {
            while (pos < until && pos < out.size / 2) {
                val v = chip.clock()
                out[2 * pos] = v.toByte()
                out[2 * pos + 1] = (v shr 8).toByte()
                pos++
            }
        }
        for (l in stream(0, 2)) {
            val w = l.split(' ')
            advance((w[0].toInt() * (0x50E7 / SoundBlaster.PIT_HZ) * OplChip.RATE).toInt())
            chip.write(w[1].toInt(16), w[2].toInt(16))
            advance(pos + 2)
        }
        advance(out.size / 2)
        assertEquals(CRC_PCM_C, crc(out))
    }

    @Test
    fun `a tune is heard steadily, and a stop silences the chip`() = runTest {
        val s = session(0, 2)
        val mixer = PcmMixer(22050)
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B', sink = { SoundBlaster(it, out = mixer) })
        val sb = vm.sound as SoundBlaster
        for (f in 1 until 1000) s.step(vm, f)
        mixer.take(sb.time)
        // far past the point where notes that never restart would have died away
        for (f in 1000 until 2500) s.step(vm, f)
        val pcm = mixer.take(sb.time)
        assertTrue(sb.musicPlaying)
        val second = 22050
        for (i in 0 until pcm.size / second) {
            val peak = (0 until second).maxOf { kotlin.math.abs(pcm[i * second + it].toInt()) }
            assertTrue(peak > 1000, "second $i peaks at $peak")
        }
        vm.machine.sg(0x0CE6, 0) // sound_seq_ctl 0: stop at once
        val writes = ArrayList<Pair<Int, Int>>()
        sb.oplWrites = { r, v -> writes += r to v }
        sb.music(SoundSink.Music.FADE)
        assertEquals(0xBD to 0, writes.last())
        assertFalse(sb.musicPlaying)

    }

    private companion object {
        const val FRAMES = 5400
        const val CRC_TUNE_A = 0xA29205CBL
        const val CRC_TUNE_B = 0xB55319D5L
        const val CRC_TUNE_C = 0xD5542F12L
        const val CRC_PCM_C = 0xC77D44FBL
    }
}
