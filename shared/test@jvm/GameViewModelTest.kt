package dev.pelotasplus.ishar.features.game

import dev.pelotasplus.ishar.data.repository.ResourceRepository
import dev.pelotasplus.ishar.data.store.MemorySaveStore
import dev.pelotasplus.ishar.data.store.SaveStore
import dev.pelotasplus.ishar.engine.Cheats
import dev.pelotasplus.ishar.engine.Input
import dev.pelotasplus.ishar.engine.Intro
import dev.pelotasplus.ishar.engine.SaveGames
import dev.pelotasplus.ishar.engine.TestResources
import dev.pelotasplus.ishar.engine.WorldMap
import dev.pelotasplus.ishar.engine.VmPairs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {
    private val d = StandardTestDispatcher()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(d)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val store = MemorySaveStore()

    private fun viewModel(frames: Int) = GameViewModel(
        engineDispatcher = d,
        realTime = false,
        resourceRepository = TestResources(),
        saveStore = store,
        frameLimit = frames,
    )

    private fun brokenFile(name: String, read: Result<ByteArray>) = object : ResourceRepository by TestResources() {
        override suspend fun readBytes(fileName: String) =
            if (fileName == name) read else TestResources().readBytes(fileName)
    }

    private fun viewModel(repo: ResourceRepository, frames: Int) = GameViewModel(
        engineDispatcher = d,
        realTime = false,
        resourceRepository = repo,
        saveStore = store,
        frameLimit = frames,
    )

    @Test
    fun `a file that cannot be read says so`() = runTest(d) {
        val vm = viewModel(brokenFile("frise.io", Result.failure(RuntimeException("404"))), 1)
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Cannot read frise.io: 404", s.error)
        assertTrue(!s.loading)
    }

    @Test
    fun `a failing start ends in a message, not a spinner`() = runTest(d) {
        val vm = viewModel(brokenFile("main.io", Result.success(ByteArray(0))), 1)
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("New game", s.mode)
        assertTrue(s.error!!.startsWith("The game stopped"), s.error)
        assertTrue(!s.loading)
        assertNull(vm.onEngine { it }, "no engine left to wait for")
    }

    @Test
    fun `a failure in play keeps the last screen and says why`() = runTest(d) {
        val vm = viewModel(100_000)
        vm.state.first { it.mode == "New game" && !it.loading }
        vm.pause()
        vm.step(5)
        vm.cheat { error("boom") }
        vm.step(5)
        val s = vm.state.value
        assertEquals("The game stopped: java.lang.IllegalStateException: boom", s.error)
        assertNotNull(s.frame)
    }

    /** Esc is a tap like any other key during the intro; it used to be the pause. */
    @Test
    fun `esc skips the intro`() = runTest(d) {
        val vm = viewModel(1000)
        vm.state.first { it.mode == "New game" && !it.loading }
        vm.pause()
        vm.step(20) // past the tap's grace
        assertEquals(Intro.Stage.LOGO, vm.state.value.introStage)
        vm.keyDown(Input.ESC)
        vm.keyUp(Input.ESC)
        vm.step(20)
        assertTrue(vm.state.value.introStage != Intro.Stage.LOGO, "still on the logo")
        vm.stop()
    }

    /**
     * New game while a load is still in its unpaced setup (held here at its file read): the load must
     * not finish last and leave the player's input on its dead run. On real threads, as in the app.
     */
    @Test
    fun `a start cancelled in its setup leaves the input to the newer game`() = runBlocking {
        val inRead = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        var hold = false
        val held = object : SaveStore by store {
            override fun read(name: String): ByteArray? {
                if (hold && name == "mine.sav") {
                    inRead.countDown()
                    letGo.await()
                }
                return store.read(name)
            }
        }
        store.write("mine.sav", TestResources.save("LR.SAV"))
        val vm = GameViewModel(
            engineDispatcher = Dispatchers.Default,
            realTime = false,
            resourceRepository = TestResources(),
            saveStore = held,
        )
        withTimeout(60_000) { vm.state.first { it.mode == "New game" && !it.loading } }
        hold = true
        vm.loadSave("MINE")
        assertTrue(inRead.await(60, TimeUnit.SECONDS), "the load reached its file")
        hold = false
        vm.newGame()
        delay(300)
        letGo.countDown()
        withTimeout(60_000) { vm.state.first { it.mode == "New game" && !it.loading } }
        delay(3000) // the load's setup would be over by now
        vm.pause()
        var applied = false
        vm.cheat { applied = true }
        vm.step(2)
        assertTrue(applied, "the input belongs to the game that runs")
        assertNotNull(vm.state.value.introStage, "and so does the intro")
        vm.stop()
    }

    /** Sound is on unless asked otherwise (the debug menu's switch, or a muted test run), and a new game keeps the choice. */
    @Test
    fun `sound is on by default and the switch survives a new game`() = runTest(d) {
        val vm = GameViewModel(engineDispatcher = d, realTime = false, resourceRepository = TestResources(), saveStore = store, muted = false, frameLimit = 1)
        testScheduler.advanceUntilIdle()
        assertTrue(!vm.state.value.muted)
        vm.setMuted(true)
        vm.newGame()
        testScheduler.advanceUntilIdle()
        assertTrue(vm.state.value.muted)
        vm.setMuted(false)
        assertTrue(!vm.state.value.muted)
    }

    /** A full disk: the player hears about it and plays on, and what was saved stays. */
    @Test
    fun `a save that fails says so and the game goes on`() = runTest(d) {
        var full = true
        val failing = object : SaveStore by store {
            override fun write(name: String, data: ByteArray) {
                if (full) throw java.io.IOException("disk full")
                store.write(name, data)
            }
        }
        val vm = GameViewModel(engineDispatcher = d, realTime = false, resourceRepository = TestResources(), saveStore = failing, frameLimit = 100_000)
        vm.state.first { it.mode == "New game" && !it.loading }
        vm.store.write("io.sav", ByteArray(SaveGames.SIZE))
        assertEquals("Could not save IO: disk full", vm.state.value.saveError)
        assertNull(vm.state.value.error)
        val frames = vm.state.value.frames
        vm.state.first { it.frames > frames }
        full = false
        vm.store.write("io.sav", ByteArray(SaveGames.SIZE))
        assertNull(vm.state.value.saveError, "the next one worked")
        vm.stop()
    }

    /** Listed so they can be deleted, not offered for loading. */
    @Test
    fun `saves that cannot be loaded are told apart`() = runTest(d) {
        val lr = TestResources.save("LR.SAV")
        store.write("good.sav", lr)
        store.write("cut.sav", lr.copyOf(100))
        store.write("my-save.sav", lr)
        store.write("ninechars.sav", lr)
        val vm = viewModel(1)
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals(listOf("GOOD"), s.saveSummaries.map { it.name })
        assertEquals(listOf("CUT", "MY-SAVE", "NINECHARS"), s.unloadableSaves)
        vm.deleteSave("MY-SAVE")
        assertEquals(listOf("CUT", "NINECHARS"), vm.state.value.unloadableSaves)
        assertNull(store.read("my-save.sav"))
    }

    /** The load is typed blind: if the game never opened the file, this is not that save. */
    @Test
    fun `a load that opens no file says so`() = runTest(d) {
        val vm = viewModel(1)
        testScheduler.advanceUntilIdle()
        vm.loadSave("GONE")
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Could not load GONE", s.error)
        assertTrue(!s.loading)
    }

    /** The generator's state some frames into [start] from [seed]. */
    private suspend fun TestScope.rollsAfter(seed: Int, start: GameViewModel.() -> Unit, mode: String): Int {
        val vm = GameViewModel(engineDispatcher = d, realTime = false, resourceRepository = TestResources(), saveStore = store, frameLimit = 100_000, seed = { seed })
        vm.state.first { it.mode == "New game" && !it.loading }
        vm.start()
        vm.state.first { it.mode == mode && !it.loading }
        vm.pause()
        vm.step(30)
        val state = vm.onEngine { it.g(0x0C4E) }!!
        vm.stop()
        return state
    }

    /** Like DOS, where main.io 0596 read the clock: the dice differ from one start to the next, and a load is no replay. */
    @Test
    fun `every start and load rolls its own dice`() = runTest(d) {
        store.write("mine.sav", TestResources.save("LR.SAV"))
        val starts = listOf<Pair<GameViewModel.() -> Unit, String>>(
            Pair({ newGame() }, "New game"),
            Pair({ skipIntro() }, "Skip intro"),
            Pair({ loadSave("MINE") }, "Load MINE"),
        )
        for ((start, mode) in starts) {
            val one = rollsAfter(0x1234, start, mode)
            assertEquals(one, rollsAfter(0x1234, start, mode), "$mode: the seed decides")
            assertTrue(one != rollsAfter(0x4321, start, mode), "$mode: another seed, other dice")
        }
    }

    private suspend fun GameViewModel.clickAt(x: Int, y: Int, frames: Int) {
        pointer(x, y)
        buttons(1)
        step(4)
        buttons(0)
        step(frames)
    }

    /** Walks a step, then the disk menu's PLAY A NEW GAME; -> position and generator state after it, and before it. */
    private suspend fun TestScope.restart(seed: Int, clockBefore: Int?): Triple<Cheats.Position, Int, Cheats.Position> {
        val vm = GameViewModel(engineDispatcher = d, realTime = false, resourceRepository = TestResources(), saveStore = store, frameLimit = 100_000, seed = { seed })
        vm.state.first { it.mode == "New game" && !it.loading }
        vm.skipIntro()
        vm.state.first { it.mode == "Skip intro" && !it.loading }
        vm.pause()
        vm.step(5)
        vm.clickAt(289, 83, 40) // the forward arrow
        val walked = vm.state.value.position!!
        clockBefore?.let { c -> vm.onEngine { it.ww(0x0040, 0x006C, c) } }
        vm.clickAt(310, 58, 25) // the disk
        vm.clickAt(160, 162, 200) // PLAY A NEW GAME
        val after = vm.state.value.position!!
        val dice = vm.onEngine { it.g(0x0C4E) }!!
        vm.stop()
        return Triple(after, dice, walked)
    }

    /** A far cell, marked as if the party had been there. */
    private val farCell = Cheats.Position(40, 50, 1, 1)

    /** A paused game ticks forever: without the stop, a failed assert would hang the test instead of failing it. */
    private suspend fun TestScope.playing(test: suspend (GameViewModel) -> Unit) {
        val vm = playing()
        try {
            test(vm)
        } finally {
            vm.stop()
        }
    }

    private suspend fun TestScope.playing(): GameViewModel {
        val vm = GameViewModel(engineDispatcher = d, realTime = false, resourceRepository = TestResources(), saveStore = store, frameLimit = 100_000)
        vm.state.first { it.mode == "New game" && !it.loading }
        vm.skipIntro()
        vm.state.first { it.mode == "Skip intro" && !it.loading }
        vm.pause()
        vm.step(5)
        assertNotNull(vm.state.value.map).see(farCell)
        assertNotNull(vm.state.value.map!!.colour(farCell.x, farCell.y), "marked")
        return vm
    }

    /** A new party knows nothing: the old one's map stays behind (main.io 0847). */
    @Test
    fun `play a new game forgets the map`() = runTest(d) {
        playing { vm ->
        vm.clickAt(310, 58, 25)
        vm.clickAt(160, 162, 200)
        assertEquals(11, vm.state.value.position!!.x)
        assertNull(vm.state.value.map!!.colour(farCell.x, farCell.y))
        assertNotNull(vm.state.value.map!!.colour(11, 29), "the new party's own cell")
        }
    }

    /** The party wiped (ARAMIR's life to 0, as the notes' live run): game over, a click, and a new party with no map. */
    @Test
    fun `a party wipe forgets the map`() = runTest(d) {
        playing { vm ->
            vm.onEngine { it.ww(0x126B, 0x1542 + 0x2A0, 0) }
            vm.step(100)
            assertTrue(vm.state.value.position!!.contree == 1)
            assertTrue(vm.state.value.party.none { it.life > 0 } || vm.state.value.party.isEmpty(), "dead: ${vm.state.value.party}")
            vm.clickAt(160, 100, 400)
            assertTrue(vm.state.value.party.single().life > 0, "a new party: ${vm.state.value.party}")
            assertEquals(11 to 29, vm.state.value.position!!.let { it.x to it.y })
            assertNull(vm.state.value.map!!.colour(farCell.x, farCell.y))
        }
    }

    /** The disk menu's load takes the same path to 0847, and must keep what the save's map holds. */
    @Test
    fun `a load from the disk menu keeps the save's map`() = runTest(d) {
        store.write("mine.sav", TestResources.save("LR.SAV"))
        val seen = WorldMap((1..6).map { TestResources().readBytes("cont$it.fic").getOrThrow() }).apply { see(farCell) }
        store.write("mine.map", seen.encode())
        playing { vm ->
        vm.state.value.map!!.restore(null)
        vm.clickAt(310, 58, 25)
        vm.clickAt(160, 152, 40)
        for (c in "mine") {
            vm.type(Input.scancodeFor(c)!!)
            vm.step(30)
        }
        vm.type(Input.ENTER)
        vm.step(200)
        assertEquals(SaveGames.header(TestResources.save("LR.SAV")).x, vm.state.value.position!!.x, "it loaded")
        assertNotNull(vm.state.value.map!!.colour(farCell.x, farCell.y))
        }
    }

    /**
     * It goes to main.io 0847, past the seed at 0596: the dice run on rather than start over.
     * A clock word changed just before makes no difference, which it would if 0596 ran again.
     */
    @Test
    fun `play a new game restarts the party but not the dice`() = runTest(d) {
        val (position, dice, walked) = restart(0x1234, null)
        assertTrue(walked.x != 11 || walked.y != 29, "the party had walked: $walked")
        assertEquals(11 to 29, position.x to position.y, "back at the new game's cell")
        assertEquals(1, position.contree)

        val (_, sameClockLater, _) = restart(0x1234, 0x7777)
        assertEquals(dice, sameClockLater, "0596 did not read the clock again")
        val (_, otherSeed, _) = restart(0x4321, null)
        assertTrue(dice != otherSeed, "and each session's dice stay its own")
    }

    /** Away from the app nothing goes on: the party cannot die behind the home screen. */
    @Test
    fun `the game holds while the app is hidden and goes on after`() = runTest(d) {
        val vm = viewModel(100_000)
        vm.state.first { it.mode == "New game" && !it.loading }
        vm.setVisible(false)
        testScheduler.advanceTimeBy(1_000)
        val held = vm.state.value.frames
        assertTrue(held > 0)
        testScheduler.advanceTimeBy(60_000)
        assertEquals(held, vm.state.value.frames)
        assertTrue(!vm.state.value.paused, "not the player's own pause")

        vm.setVisible(true)
        vm.state.first { it.frames > held }
        vm.stop()
    }

    /** Coming back does not end a pause the player chose, and a pause does not end by leaving. */
    @Test
    fun `coming back leaves the player's pause on`() = runTest(d) {
        val vm = viewModel(100_000)
        vm.state.first { it.mode == "New game" && !it.loading }
        vm.pause()
        testScheduler.advanceTimeBy(1_000)
        val held = vm.state.value.frames
        vm.setVisible(false)
        testScheduler.advanceTimeBy(1_000)
        vm.setVisible(true)
        testScheduler.advanceTimeBy(1_000)
        assertTrue(vm.state.value.paused)
        assertEquals(held, vm.state.value.frames)
        vm.step(3)
        assertEquals(held + 3, vm.state.value.frames)
        vm.stop()
    }

    /** A start while hidden (a load already on its way) waits too, and shows when the app is back. */
    @Test
    fun `a game started while hidden holds at its first frame`() = runTest(d) {
        val vm = viewModel(100_000)
        vm.state.first { it.mode == "New game" && !it.loading }
        vm.setVisible(false)
        vm.skipIntro()
        vm.state.first { it.mode == "Skip intro" && !it.loading }
        testScheduler.advanceTimeBy(1_000)
        assertEquals(900, vm.state.value.frames)
        vm.setVisible(true)
        vm.state.first { it.frames > 900 }
        vm.stop()
    }

    /** A save inside cave 15: its contree loads during the unpaced load, and must not ask (telep.io). */
    @Test
    fun `loading a save in cave 15 asks no manual question`() = runTest(d) {
        val save = TestResources.save("LR.SAV").copyOf()
        fun word(i: Int, v: Int) {
            save[2 * i] = (v shr 8).toByte()
            save[2 * i + 1] = v.toByte()
        }
        word(1, 18); word(2, 25); word(3, 1); word(4, 15); word(12, 30); word(15, 0); word(16, 16)
        store.write("cave.sav", save)
        val vm = viewModel(1200)
        testScheduler.advanceUntilIdle()
        vm.loadSave("CAVE")
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals(15, s.position?.contree)
        val panel = (150 until 170).sumOf { y -> (2 until 60).count { x -> s.frame!![y * 320 + x] and 0xFFFFFF != 0 } }
        assertTrue(panel > 500, "the question's text box covers the party ($panel lit)")
    }

    @Test
    fun `a new game boots and runs`() = runTest(d) {
        val vm = viewModel(20)
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("New game", s.mode)
        assertEquals(21, s.frames)
        assertNull(s.stopped)
        assertEquals(64000, s.frame?.size)
    }

    @Test
    fun `skip intro hands Fragonir to the player`() = runTest(d) {
        val vm = viewModel(5)
        testScheduler.advanceUntilIdle()
        vm.skipIntro()
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Skip intro", s.mode)
        assertEquals(905, s.frames)
        assertNull(s.stopped)
        vm.keyDown(Input.UP) // accepted: no session input is playing
    }

    /** A save in the store is listed as the player's and loads (where it lands: SaveGamesTest). */
    @Test
    fun `a stored save is listed and loads`() = runTest(d) {
        val lr = TestResources.save("LR.SAV")
        store.write("mine.sav", lr)
        val vm = viewModel(1)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("MINE"), vm.state.value.saves)
        vm.loadSave("MINE")
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Load MINE", s.mode)
        assertNull(s.stopped)
    }

    /** From the Saves screen, with a digit in the name: it lands where the save was made. */
    @Test
    fun `a stored save with a digit loads`() = runTest(d) {
        val lr = TestResources.save("LR.SAV")
        store.write("t1.sav", lr)
        val vm = viewModel(1)
        testScheduler.advanceUntilIdle()
        vm.loadSave("T1")
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertNull(s.error)
        val h = SaveGames.header(lr)
        assertEquals(h.x to h.y, s.position?.x to s.position?.y)
    }

    /** A debug start hands the developers' party over at its place; the state shows it to the cheats. */
    @Test
    fun `debug start teleports`() = runTest(d) {
        val vm = viewModel(5)
        testScheduler.advanceUntilIdle()
        val krog = Cheats.PLACES.last()
        vm.debugStart(krog)
        testScheduler.advanceUntilIdle()
        val s = vm.state.value
        assertEquals("Debug start ${krog.name}", s.mode)
        assertEquals(Cheats.Position(krog.x, krog.y, s.position!!.facing, krog.contree), s.position)
        assertEquals(5, s.party.size)
    }

    @Test
    fun `the game goes on behind the open map`() = runTest(d) {
        val vm = viewModel(60)
        d.scheduler.advanceUntilIdle()
        vm.skipIntro()
        d.scheduler.advanceUntilIdle()
        val map = assertNotNull(vm.state.value.map)
        val p = assertNotNull(vm.state.value.position)
        assertNotNull(map.colour(assertNotNull(dev.pelotasplus.ishar.engine.WorldMap.worldX(p)), p.y))
        vm.setMapOpen(true)
        assertTrue(!vm.state.value.paused)
        vm.setMapOpen(false)
    }

    @Test
    fun `loading a save brings back what was seen beside it`() = runTest(d) {
        val lr = TestResources.save("LR.SAV")
        store.write("mine.sav", lr)
        val vm = viewModel(10)
        d.scheduler.advanceUntilIdle()
        vm.loadSave("LR")
        d.scheduler.advanceUntilIdle()
        val seen = assertNotNull(vm.state.value.map)
        assertNull(seen.colour(200, 45), "a save without a map has seen nothing there")
        seen.see(Cheats.Position(38, 45, 2, 4))
        store.write("mine.map", seen.encode())

        vm.loadSave("MINE")
        d.scheduler.advanceUntilIdle()
        assertNotNull(assertNotNull(vm.state.value.map).colour(3 * 54 + 38, 45))
    }
}
