package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Boot from the files, main.io's first pass, then frames with no input, against
 * the reference engine doing the same: at each checkpoint
 * the frame and statement counts and CRCs of the A000 screen and the DAC shadow.
 */
class BootRunTest {

    private data class Checkpoint(val target: Int, val frames: Int, val statements: Long, val screen: Long, val dac: Long)

    private val expected = listOf(
        Checkpoint(0, 1, 3211L, 0x7DC291ABL, 0xF5A0F415L),
        Checkpoint(1, 1, 3211L, 0x7DC291ABL, 0xF5A0F415L),
        Checkpoint(10, 10, 3375L, 0x43D58A3BL, 0xA5FC724BL),
        Checkpoint(50, 50, 4250L, 0x7E73EAFFL, 0xE5965F6BL),
        Checkpoint(100, 100, 5288L, 0x9D1D9E59L, 0x6B2E35FEL),
        Checkpoint(200, 200, 7424L, 0x142DC570L, 0x6B2E35FEL),
        Checkpoint(400, 400, 11096L, 0x98656B2FL, 0x6B2E35FEL),
    )

    @Test
    fun `boot and the intro frames match the rebuild`() = runTest {
        val machine = Machine()
        val files = GameFiles.fromResourceRepository(TestResources())
        Boot.boot(machine, files)
        val vm = Scheduler.withStatements(machine, files)
        vm.interrupts = true
        vm.runPass()
        val got = expected.map { c ->
            while (vm.frames < c.target) vm.frame()
            val screen = CRC32().apply { update(machine.m, 0xA0000, 64000) }.value
            val dac = CRC32().apply { update(machine.m, (machine.ss shl 4) + 0x1143, 0x300) }.value
            Checkpoint(c.target, vm.frames, vm.statements, screen, dac)
        }
        assertEquals(expected, got)
    }
}
