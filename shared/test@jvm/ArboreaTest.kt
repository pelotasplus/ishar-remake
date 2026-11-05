package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import dev.pelotasplus.ishar.data.store.GameSaveStore
import dev.pelotasplus.ishar.data.store.MemorySaveStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Crystals of Arborea on the engine ([GameBuild.ARBOREA]): its files staged under `files/arborea/`. */
class ArboreaTest {
    private fun boot(f: GameFiles): Scheduler {
        val m = Machine(build = GameBuild.ARBOREA)
        Boot.boot(m, f, soundOverride = 'B')
        return Scheduler.withStatements(m, f).also {
            it.sound = SoundBlaster(m)
            it.interrupts = true
            it.runPass()
        }
    }

    private fun files(store: GameSaveStore? = null) = runCatchingFiles(store)

    private fun runCatchingFiles(store: GameSaveStore?) = kotlinx.coroutines.runBlocking {
        GameFiles.fromResourceRepository(ResourceRepositoryImpl(GameBuild.ARBOREA), store = store, saveSize = null, saveNames = GameFiles.ARBOREA_SAVES)
    }

    private fun click(vm: Scheduler, x: Int, y: Int, after: Int = 40) {
        Boot.mouse(vm.machine, x, y, 1); repeat(6) { vm.frame() }; Boot.mouse(vm.machine, buttons = 0); repeat(after) { vm.frame() }
    }

    private fun until(vm: Scheduler, max: Int, what: String, done: () -> Boolean) {
        repeat(max) { if (done()) return; vm.frame() }
        assertTrue(done(), what)
    }

    /** The logo (a key), the intro, the credits (a click): the party screen, ecrans.io (asset 0eh) waiting at 055f. */
    private fun toPartyScreen(vm: Scheduler) {
        until(vm, 400, "logo.io waits for a key") { Frames.of(vm.machine, 0x2F) != null && vm.frames > 200 }
        Boot.keyDown(vm.machine, 0x39); repeat(6) { vm.frame() }; Boot.keyUp(vm.machine, 0x39)
        until(vm, 1500, "the credits over the party screen") { Frames.resume(vm.machine, 0x0E) == 0x2582 }
        click(vm, 160, 100)
        assertEquals(0x055F, Frames.resume(vm.machine, 0x0E))
    }

    @Test
    fun `the game boots to its party screen and walks the forest`() = runTest {
        val vm = boot(files())
        assertEquals(2, vm.machine.gb(0x0C99), "main.io's frame_period 1 waits 2 ticks, as Arborea's 599c clamps it")
        toPartyScreen(vm)
        click(vm, 168, 77, after = 80) // 3D
        assertNotNull(Frames.of(vm.machine, 0x07), "ecran.io: the 3D view")
        val before = Palette.frameArgb(vm.machine)
        val start = Cheats.position(vm.machine)
        assertTrue(start.facing in 1..4, "facing 1..4 at glob 2b3a: $start")
        val input = Input(vm).also { it.install() }
        input.keyDown(0x11) // W: the keypad's 8 with Alt, the move pad's forward
        repeat(3) { vm.frame() }
        input.keyUp(0x11)
        repeat(40) { vm.frame() }
        assertEquals(0, vm.machine.rb(0x40, 0x17) and 0x08, "Alt is let go with the key")
        val after = Palette.frameArgb(vm.machine)
        val moved = before.indices.count { before[it] != after[it] }
        assertTrue(moved > 20_000, "the forest moves past: $moved pixels")
        val stepped = Cheats.position(vm.machine)
        assertEquals(start.facing, stepped.facing)
        assertEquals(1, kotlin.math.abs(stepped.x - start.x) + kotlin.math.abs(stepped.y - start.y), "one cell: $start -> $stepped")
    }

    @Test
    fun `statement 04 is a no-op, as combat io's 065e runs it`() {
        val m = Machine(build = GameBuild.ARBOREA)
        val f = files()
        Boot.boot(m, f, soundOverride = 'B')
        val vm = Scheduler.withStatements(m, f)
        assertNotNull(vm.stmts[0x04], "cs:0022 is a ret in both builds")
    }

    @Test
    fun `a save slot goes to the player's store and loads into a fresh game`() = runTest {
        val base = MemorySaveStore()
        val store = GameSaveStore(base, ishar2 = false, arborea = true)
        val vm = boot(files(store))
        toPartyScreen(vm)
        click(vm, 168, 77, after = 80) // 3D, a step, back out: a state that differs from a new game's
        val input = Input(vm).also { it.install() }
        input.keyDown(0x1E); repeat(3) { vm.frame() }; input.keyUp(0x1E); repeat(40) { vm.frame() } // A: turn left
        click(vm, 300, 188, after = 60) // exit the 3D view
        click(vm, 290, 77) // GAME
        click(vm, 160, 81) // save game
        click(vm, 160, 81, after = 60) // save 1
        val saved = assertNotNull(base.read(GameSaveStore.ARBOREA_PREFIX + "s1.fic"), "saved as arborea-s1.fic")
        assertEquals(1131, saved.size)
        assertEquals(listOf("s1.fic"), GameSaveStore(base, ishar2 = false, arborea = true).names())
        assertEquals(emptyList(), GameSaveStore(base, ishar2 = false).names(), "Ishar 1's list leaves it out")
        assertNotEquals(saved.toList(), files().read("s1.fic").toList(), "not the shipped empty slot")

        val again = boot(files(store))
        toPartyScreen(again)
        click(again, 290, 77) // GAME
        click(again, 160, 98) // load game
        click(again, 160, 81, after = 80) // reload 1
        click(again, 290, 77) // GAME
        click(again, 160, 81) // save game
        click(again, 160, 98, after = 60) // save 2
        val resaved = assertNotNull(base.read(GameSaveStore.ARBOREA_PREFIX + "s2.fic"))
        // What was saved comes back (336 bytes differ from the empty slot), but byte 13 of five 15-byte records in
        // the array at 2b42 (main.io 1945): the first game was in the 3D view before it saved, the second never was
        val shipped = files().read("s1.fic")
        assertTrue(saved.indices.count { saved[it] != shipped[it] } > 300)
        val differ = saved.indices.filter { saved[it] != resaved[it] }
        assertEquals((0 until 5).map { 2 + 6 + 6 + 100 + 100 + 7 + 105 + 13 + 15 * it }, differ)
    }

    @Test
    fun `a fight plays its sounds on the Sound Blaster`() = runTest {
        val f = files()
        val m = Machine(build = GameBuild.ARBOREA)
        Boot.boot(m, f, soundOverride = 'B')
        val vm = Scheduler.withStatements(m, f)
        var loud = 0
        val sb = SoundBlaster(m, out = object : SoundBlaster.Output {
            override fun block(t: Double, data: ByteArray) {
                if (data.any { (it.toInt() and 0xFF) !in 0x7C..0x84 }) loud++
            }

            override fun dac(t: Double, v: Int) {}
        })
        vm.sound = sb
        vm.interrupts = true
        vm.runPass()
        toPartyScreen(vm)
        click(vm, 168, 77, after = 60) // 3D
        val input = Input(vm).also { it.install() }
        repeat(60) { // forward until the party meets enemies ("Irvan is near 4 enemies. fight / fly")
            input.keyDown(0x11); repeat(3) { vm.frame() }; input.keyUp(0x11); repeat(40) { vm.frame() }
        }
        click(vm, 118, 187, after = 80) // fight: combat.io (15h) and its sounds, scomb.io (32h)
        assertNotNull(Frames.of(m, 0x15), "combat.io")
        // each member's turn: its cell, then towards the enemies; scomb.io plays a sample at each blow
        repeat(6) {
            for ((x, y) in listOf(27 to 131, 55 to 131, 83 to 131, 111 to 131, 139 to 131, 167 to 131, 83 to 86, 111 to 86, 167 to 63, 55 to 86, 27 to 40)) {
                click(vm, x, y, after = 50)
            }
        }
        assertTrue(sb.samplesStarted >= 4, "samples started: ${sb.samplesStarted}")
        assertTrue(loud >= 20, "SB blocks with sound: $loud")
    }
}
