package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import dev.pelotasplus.ishar.data.store.GameSaveStore
import dev.pelotasplus.ishar.data.store.MemorySaveStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Ishar 3's saves: the disk menu's SAVE, and LOAD through the start menu ([Ishar3Start]). */
class Ishar3SavesTest {
    private fun boot(f: GameFiles): Scheduler {
        val m = Machine(build = GameBuild.ISHAR3)
        Boot.boot(m, f, soundOverride = 'B')
        return Scheduler.withStatements(m, f).also {
            it.interrupts = true
            it.runPass()
        }
    }

    private fun click(vm: Scheduler, x: Int, y: Int, after: Int = 40) {
        Boot.mouse(vm.machine, x, y, 1); repeat(4) { vm.frame() }; Boot.mouse(vm.machine, buttons = 0); repeat(after) { vm.frame() }
    }

    private fun key(vm: Scheduler, sc: Int) {
        Boot.keyDown(vm.machine, sc)
        vm.at(9) { s -> Boot.keyUp(s.machine, sc) } // as Input.type: down 9 ticks
        repeat(6) { vm.frame() }
    }

    @Test
    fun `a save from the disk menu loads from the start menu where it was made`() = runTest {
        val base = MemorySaveStore()
        val store = GameSaveStore(base, ishar2 = false, ishar3 = true)
        val f = GameFiles.fromResourceRepository(ResourceRepositoryImpl(GameBuild.ISHAR3), store = store, saveSize = null)
        val vm = boot(f)
        Ishar3Start.toMenu(vm)
        click(vm, 160, 170, after = 400) // Start game
        repeat(3) { key(vm, 0x48); repeat(30) { vm.frame() } } // three steps north
        assertEquals(47, Cheats.position(vm.machine).y)
        click(vm, 266, 12) // the disk icon
        click(vm, 237, 62) // SAVE
        for (c in "zz") key(vm, Input.scancodeFor(c)!!)
        key(vm, Input.ENTER)
        repeat(100) { vm.frame() }
        val saved = assertNotNull(base.read(GameSaveStore.ISHAR3_PREFIX + "zz.sav"), "saved as ishar3-zz.sav")
        assertEquals(SaveGames.Header(2, 47, 1, 1), SaveGames.ishar2Header(saved, version = 3))
        // the app's Saves screen: Ishar 3's list and the line under each name
        assertEquals(listOf("zz.sav"), GameSaveStore(base, ishar2 = false, ishar3 = true).names())
        assertEquals("island 1  X 2  Y 47  facing N", SaveGames.headerLine(GameBuild.ISHAR3, SaveGames.ishar2Header(saved, version = 3)))

        val again = boot(f.fresh())
        var ops = 0L
        var lastFrame = -1
        again.trace = {
            if (again.frames != lastFrame) { lastFrame = again.frames; ops = 0 }
            if (++ops > 3_000_000) {
                val m = again.machine
                val es = m.g(0x0BD6)
                val (aofs, _) = m.far(es, m.bp - 0x14)
                throw IllegalStateException("spin in asset %x at %x".format(m.rb(es, m.bp - 0x10), (m.si - aofs) and 0xFFFF))
            }
        }
        Ishar3Start.toMenu(again)
        assertNotNull(Ishar3Start.load(again, "zz"), "LOAD -> Ishar 3 -> zz starts the game")
        assertEquals(Cheats.Position(2, 47, 1, 1), Cheats.position(again.machine))
    }
}
