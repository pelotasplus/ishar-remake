package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputTest {

    @Test
    fun `a posted key arrives on the next tick through the game's table`() {
        val m = Machine()
        ExeData.load(m)
        val vm = Scheduler(m, GameFiles())
        val input = Input(vm).also { it.install() }
        input.keyDown(Input.SPACE)
        assertEquals(0, m.rb(Machine.CS, 0x0318))
        vm.tick()
        assertEquals(' '.code, m.rb(Machine.CS, 0x0318))
        assertEquals(1, m.rb(Machine.CS, 0x031A + Input.SPACE - 1))
        input.keyUp(Input.SPACE)
        input.pointer(400, 120)
        input.buttons(1)
        repeat(Input.KEY_HOLD) { vm.tick() }
        assertEquals(listOf(0, 0, 319, 120, 1), listOf(
            m.rb(Machine.CS, 0x0318), m.rb(Machine.CS, 0x031A + Input.SPACE - 1),
            m.rw(Machine.CS, 0x11BD), m.rw(Machine.CS, 0x11BF), m.rw(Machine.CS, 0x11C1),
        ))
    }

    @Test
    fun `letters map through the game's table`() {
        assertEquals(listOf(0x1E, 0x10, 0x2C, 0x11), "aqzw".map { Input.scancodeFor(it) })
    }

    /** The skip-intro keys posted through [Input] reach the same screen as written in directly. */
    @Test
    fun `posted input plays the intro like applied input`() = runTest {
        val start = Starts.SKIP_INTRO
        val vm = start.boot(Play.files())
        val input = Input(vm).also { it.install() }
        for (f in 1 until Starts.SKIP_INTRO_FRAMES) {
            for (e in start.events[f].orEmpty()) {
                when (e) {
                    is Session.Event.KeyDown -> input.keyDown(e.scancode)
                    is Session.Event.KeyUp -> input.keyUp(e.scancode)
                    else -> Session.apply(vm, e)
                }
            }
            vm.frame()
        }
        val want = Starts.skipIntro(Play.files()).machine.m
        assertEquals(0, (0 until 64000).count { vm.machine.m[0xA0000 + it] != want[0xA0000 + it] })
        assertEquals(Cheats.Position(11, 29, 2, 1), Cheats.position(vm.machine))
    }

    /** A tap (press and release posted together) still skips the logo: the press is held [Input.MIN_CLICK] ticks. */
    @Test
    fun `a quick tap skips the logo`() = runTest {
        suspend fun run(tap: Boolean): ByteArray {
            val vm = Starts.SKIP_INTRO.boot(Play.files())
            val input = Input(vm).also { it.install() }
            repeat(150) { vm.frame() }
            if (tap) { input.pointer(160, 100); input.buttons(1); input.buttons(0) }
            repeat(150) { vm.frame() }
            return vm.machine.m.copyOfRange(0xA0000, 0xA0000 + 64000)
        }
        assertFalse(run(false).contentEquals(run(true)))
    }

    /** A finger leaves the pointer undrawn: the screens differ only in its 16x16 square. */
    @Test
    fun `a touch hides the pointer`() = runTest {
        suspend fun screen(touch: Boolean): ByteArray {
            val vm = Starts.skipIntro(Play.files())
            val input = Input(vm).also { it.install() }
            input.pointer(150, 60, touch)
            repeat(5) { vm.frame() }
            return vm.machine.m.copyOfRange(0xA0000, 0xA0000 + 64000)
        }
        val mouse = screen(false)
        val finger = screen(true)
        val differ = (0 until 64000).filter { mouse[it] != finger[it] }
        assertTrue(differ.isNotEmpty())
        assertTrue(differ.all { it % 320 in 150 until 166 && it / 320 in 60 until 76 })
    }

    /** The load prompt typed like a player (press, hold 4 frames, release): it reads the name, no hang. */
    @Test
    fun `a player types a save name into the prompt`() = typeAndLoad("LR")

    /** Digits are the keypad's: the table's top row is AZERTY symbols, and sos.io keeps only 0..9 and A..Z. */
    @Test
    fun `digits have keys`() {
        assertEquals(Input.KEYPAD_DIGITS.toList(), ('0'..'9').map { Input.scancodeFor(it) })
        ('0'..'9').forEach { c ->
            assertEquals(c.code, ExeData.KBD_TABLE[Input.scancodeFor(c)!! - 1].toInt() and 0xFF)
        }
    }

    @Test
    fun `a save name with a digit loads`() = typeAndLoad("A1")

    /**
     * The web has one thread: nothing from the host arrives during a frame, so a prompt may not depend
     * on it. sos.io 0921 spins until the key is up; [Scheduler.spinRelease] has to end that by itself.
     */
    @Test
    fun `a prompt ends its key spin with no key-up from the host`() = typeAndLoad("LR", hostKeyUps = false)

    /** W/A/S/D type letters at the prompt (WASD.SAV, a copy of LR, loads) and move in play; Q/E turn. */
    @Test
    fun `wasd types at the prompt and moves in play`() = typeAndLoad("WASD") { vm, input ->
        val m = vm.machine
        input.keyDown(scancodeFor('w')!!)
        vm.tick()
        assertEquals(listOf(1, 0), listOf(m.rb(Machine.CS, 0x031A + Input.UP - 1), m.rb(Machine.CS, 0x031A + 0x11 - 1)))
        input.keyUp(scancodeFor('w')!!)
        repeat(Input.KEY_HOLD) { vm.tick() }
        assertEquals(0, m.rb(Machine.CS, 0x031A + Input.UP - 1))
        // Q turns left, E right (facing 1 N, 2 E, 3 S, 4 W)
        fun turn(c: Char): Int {
            val before = Cheats.position(m).facing
            input.keyDown(scancodeFor(c)!!)
            repeat(4) { vm.frame() }
            input.keyUp(scancodeFor(c)!!)
            repeat(12) { vm.frame() }
            return (Cheats.position(m).facing - before + 4) % 4
        }
        assertEquals(listOf(3, 1), listOf(turn('q'), turn('e')))
    }

    /** The game never sees Esc, so main.io 4e60's pause cannot start (it would hang the web's one thread). */
    @Test
    fun `esc does not pause the game`() = typeAndLoad("ESC") { vm, input ->
        input.keyDown(Input.ESC)
        vm.tick()
        assertEquals(0, vm.machine.rb(Machine.CS, 0x0318))
        val frames = vm.frames
        repeat(10) { vm.frame() }
        assertEquals(frames + 10, vm.frames)
        input.keyUp(Input.ESC)
    }

    /** Leaving the app with a finger or key down: nothing is held on the way back, and the late key-up does no harm. */
    @Test
    fun `release all lets go of keys and buttons`() = typeAndLoad("REL") { vm, input ->
        val m = vm.machine
        input.keyDown(scancodeFor('w')!!)
        input.buttons(1)
        vm.tick()
        assertEquals(1, m.rb(Machine.CS, 0x031A + Input.UP - 1))
        assertEquals(1, m.rb(Machine.CS, 0x11C1))
        input.releaseAll()
        vm.tick()
        assertEquals(0, m.rb(Machine.CS, 0x031A + Input.UP - 1))
        assertEquals(0, m.rb(Machine.CS, 0x0318))
        assertEquals(0, m.rb(Machine.CS, 0x11C1))
        input.keyUp(scancodeFor('w')!!)
        vm.tick()
        assertEquals(0, m.rb(Machine.CS, 0x0318))
    }

    /**
     * The scripts read keys once a frame: a press and release between two reads must still count (as a mouse click
     * does), and count once. E is a turn, which needs no free cell ahead.
     */
    @Test
    fun `a quick key tap counts, once`() = typeAndLoad("TAP") { vm, input ->
        val m = vm.machine
        val facing = Cheats.position(m).facing
        input.keyDown(scancodeFor('e')!!)
        input.keyUp(scancodeFor('e')!!)
        repeat(30) { vm.frame() }
        assertEquals(facing % 4 + 1, Cheats.position(m).facing, "one turn right")
        assertEquals(0, m.rb(Machine.CS, 0x0318), "and let go")
    }

    /** The key pressed again before a tap's held release: the late release must not cut the new press short. */
    @Test
    fun `a new press outlives the last tap's release`() = typeAndLoad("TAP2") { vm, input ->
        val m = vm.machine
        val key = scancodeFor('w')!!
        input.keyDown(key)
        input.keyUp(key)
        vm.tick()
        input.keyDown(key)
        repeat(Input.KEY_HOLD * 2) { vm.tick() }
        assertEquals(1, m.rb(Machine.CS, 0x031A + Input.UP - 1), "still held")
        input.keyUp(key)
        repeat(Input.KEY_HOLD) { vm.tick() }
        assertEquals(0, m.rb(Machine.CS, 0x031A + Input.UP - 1))
    }

    /** Focus gone (alt-tab, a click on the debug menu): the key-up will not come. A mouse or finger press stays its time. */
    @Test
    fun `release keys lets go of keys but not of a press`() = typeAndLoad("KEYS") { vm, input ->
        val m = vm.machine
        input.keyDown(scancodeFor('w')!!)
        input.buttons(1)
        vm.tick()
        assertEquals(1, m.rb(Machine.CS, 0x031A + Input.UP - 1))
        input.releaseKeys()
        vm.tick()
        assertEquals(0, m.rb(Machine.CS, 0x031A + Input.UP - 1))
        assertEquals(1, m.rb(Machine.CS, 0x11C1))
        input.buttons(0)
        repeat(Input.MIN_CLICK) { vm.tick() }
        input.keyDown(scancodeFor('w')!!)
        vm.tick()
        assertEquals(1, m.rb(Machine.CS, 0x031A + Input.UP - 1), "the same key works again")
        input.keyUp(scancodeFor('w')!!)
    }

    /** The phone's keyboard: the whole name and Enter typed in one burst when the prompt opens; each key still lands. */
    @Test
    fun `typed keys in a burst all reach the prompt`() = runTest {
        val files = Play.files()
        files.write("WORD.SAV", files.read("LR.SAV"))
        val vm = Starts.skipIntro(files)
        val input = Input(vm).also { it.install() }
        val load = SaveGames.loadEvents("WORD", Play.START)
        var typed = false
        for (f in Play.START until Play.START + SaveGames.loadFrames("WORD") + 200) {
            for (e in load[f].orEmpty()) {
                when (e) {
                    is Session.Event.KeyDown -> if (!typed) {
                        typed = true
                        "word".forEach { input.type(scancodeFor(it)!!) }
                        input.type(Input.ENTER)
                    }
                    is Session.Event.KeyUp, is Session.Event.SpinUp -> {}
                    else -> Session.apply(vm, e)
                }
            }
            vm.frame()
        }
        assertEquals(SaveGames.header(files.read("LR.SAV")), Play.position(vm.machine))
    }

    private fun scancodeFor(c: Char) = Input.scancodeFor(c)

    private fun typeAndLoad(name: String, hostKeyUps: Boolean = true, after: (Scheduler, Input) -> Unit = { _, _ -> }) = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        files.write("$name.SAV", files.read("LR.SAV"))
        val vm = Starts.skipIntro(files)
        val input = Input(vm).also { it.install() }
        val load = SaveGames.loadEvents(name, 900)
        val ups = mutableMapOf<Int, Int>()
        for (f in 900 until 900 + SaveGames.loadFrames(name)) {
            ups.remove(f)?.let { if (hostKeyUps) input.keyUp(it) }
            for (e in load[f].orEmpty()) {
                when (e) {
                    is Session.Event.KeyDown -> input.keyDown(e.scancode).also { ups[f + 4] = e.scancode }
                    is Session.Event.SpinUp -> {}
                    else -> Session.apply(vm, e)
                }
            }
            vm.frame()
        }
        val m = vm.machine
        assertEquals(SaveGames.header(files.read("LR.SAV")), SaveGames.Header(m.rb(0x126B, 0x161C), m.rb(0x126B, 0x161D), m.rb(0x126B, 0x161E), m.rb(0x126B, 0x17A8)))
        after(vm, input)
    }
}
