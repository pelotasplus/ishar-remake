package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The sound against the reference engine: a boot with the
 * Sound Blaster at full machine speed (the title tune plays), Space on the logo, keypad 1
 * twice, then walking, 1500 frames: the SB DMA blocks and the MOD player's DAC bytes.
 */
class SoundBlasterTest {

    @Test
    fun `samples and music match the rebuild`() = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        val m = Machine()
        Boot.boot(m, files, soundOverride = 'B')
        val vm = Scheduler.withStatements(m, files)
        val blocks = CRC32()
        val dac = CRC32()
        var nBlocks = 0
        var nDac = 0
        val sb = SoundBlaster(m, out = object : SoundBlaster.Output {
            override fun block(t: Double, data: ByteArray) {
                blocks.update(data)
                nBlocks++
            }

            override fun dac(t: Double, v: Int) {
                dac.update(v)
                nDac++
            }
        })
        vm.sound = sb
        vm.interrupts = true
        vm.runPass()
        val script = mutableMapOf(120 to (Input.SPACE to true), 123 to (Input.SPACE to false))
        for (f in listOf(800, 1000)) {
            script[f] = 0x4F to true
            script[f + 3] = 0x4F to false
        }
        for (f in 1200 until 1400 step 8) {
            script[f] = Input.UP to true
            script[f + 3] = Input.UP to false
        }
        for (f in 1 until 1500) {
            script[f]?.let { (sc, down) -> if (down) Boot.keyDown(m, sc) else Boot.keyUp(m, sc) }
            vm.frame()
        }
        val screen = CRC32().apply { update(m.m, 0xA0000, 64000) }.value
        fun hex(v: Long) = "0x" + v.toString(16).uppercase().padStart(8, '0')
        assertEquals(
            "blocks=1488 0x7F995AA3 dac=140624 0x5B1BEE3B samples=8 tunes=1 screen=0xFDDCCA87 time=53.429",
            "blocks=$nBlocks ${hex(blocks.value)} dac=$nDac ${hex(dac.value)} samples=${sb.samplesStarted} " +
                "tunes=${sb.tunesStarted} screen=${hex(screen)} time=${"%.3f".format(java.util.Locale.ROOT, sb.time)}",
        )
    }
}
