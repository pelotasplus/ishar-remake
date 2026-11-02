package dev.pelotasplus.ishar.features.game

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import dev.pelotasplus.ishar.data.store.GameSaveStore
import dev.pelotasplus.ishar.data.store.MemorySaveStore
import dev.pelotasplus.ishar.engine.Boot
import dev.pelotasplus.ishar.engine.Cheats
import dev.pelotasplus.ishar.engine.GameBuild
import dev.pelotasplus.ishar.engine.GameFiles
import dev.pelotasplus.ishar.engine.Input
import dev.pelotasplus.ishar.engine.Machine
import dev.pelotasplus.ishar.engine.Session
import dev.pelotasplus.ishar.engine.Starts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import dev.pelotasplus.ishar.engine.IslandMap
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Ishar 2's saves on the Saves screen: a tap loads one through the game's own LOAD. */
@OptIn(ExperimentalCoroutinesApi::class)
class Ishar2SavesTest {
    private val d = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(d)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    /** Saves "ZZ" with the game's own SAVE, the party at 1c,30 facing south. */
    private suspend fun saveZz(): ByteArray {
        val store = GameSaveStore(MemorySaveStore(), ishar2 = true)
        val f = GameFiles.fromResourceRepository(ResourceRepositoryImpl(GameBuild.ISHAR2), store = store, saveSize = null)
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(f, Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, i)
        val m = vm.machine
        fun click(x: Int, y: Int) {
            Boot.mouse(m, x, y, 1); repeat(4) { vm.frame() }; Boot.mouse(m, buttons = 0); repeat(25) { vm.frame() }
        }
        fun key(sc: Int) {
            Session.apply(vm, Session.Event.KeyDown(sc)); Session.apply(vm, Session.Event.SpinUp(sc))
            repeat(3) { vm.frame() }
            Session.apply(vm, Session.Event.KeyUp(sc))
            repeat(3) { vm.frame() }
        }
        repeat(20) { vm.frame() }
        Cheats.moveParty(m, 0x1C, 0x30, 3)
        click(266, 12)
        click(237, 55) // SAVE
        for (c in "zz") key(Input.scancodeFor(c)!!)
        key(Input.ENTER)
        repeat(80) { vm.frame() }
        return requireNotNull(store.read("zz.sav")) { "ZZ was not saved" }
    }

    /** A map that has seen a cell far from the start (60,20 on Irvan's Island), as a save's .map beside ZZ. */
    private suspend fun storeWithMap(): MemorySaveStore {
        val base = MemorySaveStore()
        base.write(GameSaveStore.ISHAR2_PREFIX + "zz.sav", saveZz())
        val map = IslandMap.fromFiles(GameFiles.fromResourceRepository(ResourceRepositoryImpl(GameBuild.ISHAR2), saveSize = null))
        map.see(Cheats.Position(60, 20, 1, 1))
        base.write(GameSaveStore.ISHAR2_PREFIX + "zz.map", map.encode())
        return base
    }

    private fun GameViewModel.farCellSeen() = state.value.map?.colour(60, 20) != null

    @Test
    fun `a save loaded from the Saves screen brings its map back`() = runTest(d) {
        val vm = GameViewModel(engineDispatcher = d, realTime = false, saveStore = storeWithMap(), frameLimit = 1, build = GameBuild.ISHAR2)
        testScheduler.advanceUntilIdle()
        assertEquals(false, vm.farCellSeen(), "a new game has seen nothing there")
        vm.loadSave("ZZ")
        testScheduler.advanceUntilIdle()
        assertEquals(null, vm.state.value.error)
        assertEquals(true, vm.farCellSeen(), "the save's map")
    }

    @Test
    fun `the game's own LOAD brings the save's map back`() = runBlocking {
        val vm = GameViewModel(engineDispatcher = Dispatchers.Default, realTime = false, saveStore = storeWithMap(), build = GameBuild.ISHAR2)
        withTimeout(120_000) { vm.state.first { it.mode == "New game" && !it.loading } }
        vm.pause()
        vm.skipIntro()
        withTimeout(120_000) { vm.state.first { it.mode == "Skip intro" && !it.loading && it.frame != null } }
        vm.pause()
        suspend fun click(x: Int, y: Int) {
            vm.pointer(x, y); vm.step(2); vm.buttons(1); vm.step(5); vm.buttons(0); vm.step(25)
        }
        suspend fun key(sc: Int) {
            vm.keyDown(sc); vm.step(3); vm.keyUp(sc); vm.step(4)
        }
        assertEquals(false, vm.farCellSeen())
        click(266, 12)
        click(237, 32) // LOAD
        key(Input.scancodeFor('z')!!)
        key(Input.scancodeFor('z')!!)
        key(Input.ENTER)
        vm.step(300)
        assertEquals(Cheats.Position(0x1C, 0x30, 3, 1), vm.state.value.position, "loaded")
        assertEquals(true, vm.farCellSeen(), "the save's map")
        vm.stop()
    }

    @Test
    fun `a tap on an Ishar 2 save loads it`() = runTest(d) {
        val base = MemorySaveStore()
        base.write(GameSaveStore.ISHAR2_PREFIX + "zz.sav", saveZz())
        val vm = GameViewModel(engineDispatcher = d, realTime = false, saveStore = base, frameLimit = 1, build = GameBuild.ISHAR2)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("ZZ"), vm.state.value.ishar2Saves.map { it.first })
        vm.loadSave("ZZ")
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals(null, s.error)
        assertEquals(Cheats.Position(0x1C, 0x30, 3, 1), s.position)
    }
}
