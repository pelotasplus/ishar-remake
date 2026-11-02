package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SchedulerTest {

    private suspend fun booted(): Scheduler {
        val files = GameFiles.fromResourceRepository(TestResources())
        val machine = Machine()
        Boot.boot(machine, files)
        val vm = Scheduler(machine, files)
        vm.stmts[0x00] = {}
        vm.stmts[0x42] = { throw LeaveException }
        return vm
    }

    /** Points frame 0's main script at `9000:0000` and loads [code] there. */
    private fun script(vm: Scheduler, vararg code: Int) {
        val m = vm.machine
        code.forEachIndexed { i, b -> m.wb(0x9000, i, b) }
        val es = m.g(0x0BD6)
        val bp = m.g(0x0BF6)
        m.ww(es, bp - 8, 0)
        m.ww(es, bp - 6, 0x9000)
        val (aofs, aseg) = m.far(es, bp - 0x14)
        m.ww(aseg, aofs + 6, 0) // no tick script
    }

    @Test
    fun `a pass runs frame 0 to its yield and reloads the countdown`() = runTest {
        val vm = booted()
        val m = vm.machine
        script(vm, 0x00, 0x00, 0x42, 0x00, 0x42)
        val es = m.g(0x0BD6)
        val bp = m.g(0x0BF6)
        m.wb(es, bp - 2, 3) // period 3

        vm.runPass()
        assertEquals(1, vm.frames)
        assertEquals(3, vm.statements)
        assertEquals(3, m.rw(es, bp - 8)) // resumes after the yield
        assertEquals(3, m.rb(es, bp - 1)) // BP-1 reloaded from BP-2
        assertEquals(1, m.gb(0x0C19))

        // the next two passes only count down
        vm.runPass()
        vm.runPass()
        assertEquals(3, vm.statements)
        assertEquals(1, m.rb(es, bp - 1))
        vm.runPass()
        assertEquals(5, vm.statements)
        assertEquals(5, m.rw(es, bp - 8))
    }

    @Test
    fun `a stopped frame does not run`() = runTest {
        val vm = booted()
        val m = vm.machine
        script(vm, 0x42)
        m.wb(m.g(0x0BD6), m.g(0x0BF6) - 4, 0)
        vm.runPass()
        assertEquals(0, vm.statements)
        assertEquals(1, vm.frames)
    }

    @Test
    fun `long statement runs tick once every 2000 statements`() = runTest {
        val vm = booted()
        val m = vm.machine
        // 4500 nops then a yield, in a loop of 1-byte nops
        for (i in 0 until 4500) m.wb(0x9000, i, 0x00)
        m.wb(0x9000, 4500, 0x42)
        script(vm)
        vm.interrupts = true
        m.sgb(0x0C9A, 0)
        vm.runPass()
        assertEquals(4501L, vm.statements)
        assertEquals(2, vm.ticks)
        assertEquals(2, m.gb(0x0C9A))
    }

    @Test
    fun `frame_wait ticks up to the period and resets the counter`() = runTest {
        val vm = booted()
        val m = vm.machine
        vm.interrupts = true
        m.sgb(0x0C99, 3)
        m.sgb(0x0C9A, 0)
        var views = 0
        vm.viewPass = { _, _ -> views++ }
        vm.frameWait()
        assertEquals(3, vm.ticks)
        assertEquals(0, m.gb(0x0C9A))
        assertEquals(0, views) // no view shown at boot
        assertEquals(m.g(0x0B8A), m.g(0x1DC1))
    }

    @Test
    fun `input queued on the clock arrives in order`() = runTest {
        val vm = booted()
        val seen = mutableListOf<Int>()
        vm.at(2) { seen += 2 }
        vm.at(1) { seen += 1 }
        vm.at(2) { seen += 3 }
        repeat(3) { vm.tick() }
        assertEquals(listOf(1, 2, 3), seen)
    }

    @Test
    fun `key_wait takes typed keys`() = runTest {
        val vm = booted()
        vm.typed.addLast(0x41)
        assertEquals(0x41, vm.keyWait())
        assertEquals(0, vm.machine.rb(Machine.CS, 0x0318))
        assertFailsWith<UnsupportedEngineOperationException> { vm.keyWait() }
    }

    @Test
    fun `missing statements are unsupported`() = runTest {
        val vm = booted()
        script(vm, 0xFF)
        assertFailsWith<UnsupportedEngineOperationException> { vm.runPass() }
    }
}
