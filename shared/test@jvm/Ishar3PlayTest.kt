package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import dev.pelotasplus.ishar.data.store.GameSaveStore
import dev.pelotasplus.ishar.data.store.MemorySaveStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ishar 3 played through the app's input: the logo, the intro, the menus, the town, the cheats, the imports. */
class Ishar3PlayTest {
    private suspend fun files(store: GameSaveStore? = null) =
        GameFiles.fromResourceRepository(ResourceRepositoryImpl(GameBuild.ISHAR3), store = store, saveSize = null)

    private suspend fun boot(store: GameSaveStore? = null): Scheduler {
        val f = files(store)
        val m = Machine(build = GameBuild.ISHAR3)
        Boot.boot(m, f, soundOverride = 'B')
        return Scheduler.withStatements(m, f).also {
            it.interrupts = true
            it.runPass()
        }
    }

    private fun shot(vm: Scheduler, name: String) {
        val dir = System.getenv("I3SHOTS") ?: return
        val img = java.awt.image.BufferedImage(320, 200, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        img.setRGB(0, 0, 320, 200, Palette.frameArgb(vm.machine), 0, 320)
        java.io.File(dir).mkdirs()
        javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "$name.png"))
    }

    /** The pointer goes there first (create.io highlights the line under it), then a press long enough for its
     *  stats loop, which looks at the button once in four yields (0fa1..0fa5). */
    private fun click(vm: Scheduler, x: Int, y: Int, after: Int = 40) {
        Boot.mouse(vm.machine, x, y); repeat(3) { vm.frame() }
        Boot.mouse(vm.machine, x, y, 1); repeat(6) { vm.frame() }; Boot.mouse(vm.machine, buttons = 0); repeat(after) { vm.frame() }
    }

    private fun type(vm: Scheduler, text: String) {
        for (sc in text.lowercase().map { Input.scancodeFor(it)!! } + Input.ENTER) {
            Boot.keyDown(vm.machine, sc)
            vm.at(9) { s -> Boot.keyUp(s.machine, sc) } // as Input.type: down 9 ticks
            repeat(6) { vm.frame() }
        }
    }

    /** create.io: the square at [x], a human warrior named [name] (the stats left as they are). */
    private fun createCharacter(vm: Scheduler, x: Int, name: String) {
        click(vm, x, 165, after = 100)
        click(vm, 225, 14, after = 60) // Human
        click(vm, 186, 136, after = 60) // the face: ok
        click(vm, 225, 24, after = 60) // Warrior
        click(vm, 226, 73, after = 60) // stats OK
        type(vm, name)
        repeat(60) { vm.frame() }
    }

    private suspend fun inTown(store: GameSaveStore? = null): Scheduler = boot(store).also { vm ->
        Ishar3Start.toMenu(vm)
        click(vm, 160, 170, after = 400) // Start game
    }

    /** The objects of view [view] in drawing order (its chain, frise.io's object records). */
    private fun chain(m: Machine, view: Int): List<Pair<Int, Int>> {
        val o = m.g(0x0BEA)
        var di = m.rw(m.g(0x0BD6), m.g(0x0BF6) + view + 2)
        val out = mutableListOf<Pair<Int, Int>>()
        while (true) {
            di = m.rw(o, di + 6)
            if (di == 0) break
            if ((m.rb(o, di) and 0x80) != 0) continue
            val (seg, ofs) = m.entry(m.rw(o, di + 0x0A), m.rw(o, di + 8))
            out += m.rsw(o, di + 0x10) to m.rb(seg, ofs)
        }
        return out
    }

    @Test
    fun `the logo draws its dragon S through the reveal`() = runTest {
        val vm = boot()
        val m = vm.machine
        repeat(100) { vm.frame() }
        assertNotNull(Frames.of(m, 0x05), "logo.io")
        val s = (110 until 175).sumOf { y -> (10 until 50).count { x -> m.rb(m.screen, y * 320 + x) != 0 } }
        assertTrue(s > 300, "the S beside 'ilm': $s pixels")
    }

    @Test
    fun `the intro plays its FLI pictures and a click skips it to the menu`() = runTest {
        val vm = boot()
        val m = vm.machine
        repeat(500) { vm.frame() } // intro2.io's castle
        assertTrue(Palette.frameArgb(m).toSet().size > 64, "a picture of many colours")
        assertNotNull(Frames.of(m, 0xAA), "runner.io plays the intro")
        assertTrue(Ishar3Start.skipIntro(m))
        repeat(150) { vm.frame() }
        assertNull(Frames.of(m, 0xAA), "the intro is gone")
        assertNotNull(Frames.of(m, 0x07), "accueil.io's menu")
    }

    @Test
    fun `the menu is English and Create the party makes a character`() = runTest {
        val vm = boot()
        val m = vm.machine
        Ishar3Start.toMenu(vm)
        val menu = assertNotNull(Frames.assetByte(m, 0x07, 0))
        val text = String(CharArray(0x4000) { (m.m[menu + it].toInt() and 0xFF).toChar() })
        assertTrue("L oad game" in text && "C reate the party" in text && "S pielstand" !in text, "English lines")

        click(vm, 160, 160, after = 300) // Create the party
        assertNotNull(Frames.of(m, 0x0A), "create.io")
        shot(vm, "c0")
        click(vm, 35, 165, after = 100) // the first square
        shot(vm, "c1")
        click(vm, 225, 14, after = 60) // Human
        shot(vm, "c2")
        click(vm, 186, 136, after = 60) // the face: ok
        shot(vm, "c3")
        click(vm, 225, 24, after = 60) // Warrior
        shot(vm, "c4")
        // the rows' text sits on y' = 175, 165..., the script's y being 199 - y: Stren.'s + is at 301,20 (create.io 1158)
        repeat(10) { click(vm, 301, 20, after = 20) } // STR +
        click(vm, 226, 73, after = 60) // stats OK
        shot(vm, "c5")
        type(vm, "borin")
        repeat(60) { vm.frame() }
        shot(vm, "c6")
        click(vm, 123, 72, after = 600) // start game
        val party = Cheats.party(m)
        assertEquals(listOf("borin"), party.map { it.name.lowercase() })
        assertTrue(party.single().stats[0] > 1, "strength was raised: ${party.single().stats}")
        assertEquals(1, Cheats.position(m).contree)
    }

    @Test
    fun `Start game shows the street, WASD and QE move, the map sees its walls`() = runTest {
        val vm = inTown()
        val m = vm.machine
        val input = Input(vm).also { it.install() }
        assertEquals(Cheats.Position(2, 50, 1, 1), Cheats.position(m))
        assertFalse(Input.textPrompt(m), "no prompt: W/A/S/D/Q/E are moves")
        // fville.io's sky and ground (opaque, depth 0) are drawn first, the street over them
        val drawn = chain(m, 0x52)
        val lastSky = drawn.indexOfLast { (d, t) -> d == 0 && t == 0x16 }
        val firstNear = drawn.indexOfFirst { (d, _) -> d > 0 }
        assertTrue(lastSky >= 0 && firstNear > lastSky, "sky behind the street: $drawn")

        fun press(sc: Int) {
            input.keyDown(sc); repeat(4) { vm.frame() }; input.keyUp(sc); repeat(40) { vm.frame() }
        }
        press(0x11) // W
        assertEquals(49, Cheats.position(m).y)
        press(0x12) // E: turn right
        assertEquals(2, Cheats.position(m).facing)
        press(0x10) // Q: back to north
        assertEquals(1, Cheats.position(m).facing)

        val map = IslandMap.fromIshar3Files(vm.files)
        map.see(Cheats.position(m))
        val x = assertNotNull(map.sheetX(Cheats.position(m)))
        assertEquals(0x463C32, map.colour(x - 1, 49), "the wall west of the street")
        assertEquals(0x463C32, map.colour(x + 1, 49), "the wall east of it")
        assertNotNull(map.colour(x, 48), "the street ahead")
    }

    @Test
    fun `heal, gold, stats, inventory and remove curses`() = runTest {
        val vm = inTown()
        val m = vm.machine
        val g = m.build.globals
        val b = m.build.globBase
        val zubaran = Cheats.party(m).single()
        assertEquals("Zubaran", zubaran.name)
        assertEquals(listOf(15, 14, 12, 13, 14, 100, 100), zubaran.stats)
        assertEquals("sword (+4)", Items.ishar2(m).single().items.single().name)

        Cheats.setGold(m, 0, 54321)
        assertEquals(54321, Cheats.party(m).single().gold)
        Cheats.setStat(m, 0, 0, 20)
        assertEquals(20, Cheats.party(m).single().stats[0])

        m.ww(g, b + 0x16E0, 10)
        Cheats.heal(vm)
        assertEquals(zubaran.maxLife, Cheats.party(m).single().life)


        m.ww(g, b + 0x17EE, 1) // effect 20 (eatt.io 12c0) and its icon
        m.wb(g, b + 0x36B4, 20)
        Cheats.removeCurses(m)
        repeat(400) { vm.frame() } // frise.io's tick counts timers down every tenth time (70f2)
        assertEquals(0, m.rw(g, b + 0x17EE))
        assertEquals(0, m.rw(g, b + 0x17BE), "no binding")
        assertFalse(20 in (0 until 10).map { m.rb(g, b + 0x36B4 + it) }, "the icon is gone")
    }

    @Test
    fun `Ishar 1 to Ishar 3 brings an Ishar 1 party into play`() = runTest {
        val base = MemorySaveStore()
        base.write("lr.sav", TestResources.save("LR.SAV"))
        base.write(GameSaveStore.ISHAR2_PREFIX + "lr.sav", ByteArray(8453) { if (it == 1) 2 else 0 }) // an Ishar 2 LR too
        val vm = boot(GameSaveStore(base, ishar2 = false, ishar3 = true))
        val m = vm.machine
        Ishar3Start.toMenu(vm)
        click(vm, 160, 118) // Load game
        click(vm, 160, 128, after = 60) // Ishar 1 to Ishar 3
        type(vm, "lr")
        repeat(200) { vm.frame() }
        val names = Cheats.party(m).map { it.name }
        assertEquals(listOf("Aramir"), names, "LR.SAV's party") // the reference engine: ARAMIR alone
        click(vm, 160, 170, after = 400) // Start game
        assertEquals(names, Cheats.party(m).map { it.name })
        assertNotNull(Frames.of(m, 0x0D), "in play")
    }

    @Test
    fun `the menu plays its music and the town its sounds`() = runTest {
        val f = files()
        val m = Machine(build = GameBuild.ISHAR3)
        Boot.boot(m, f, soundOverride = 'B')
        val vm = Scheduler.withStatements(m, f)
        val sb = SoundBlaster(m)
        var effects = 0
        vm.sound = object : SoundSink by sb {
            override fun play(seg: Int, ofs: Int, owner: Int) {
                effects++
                sb.play(seg, ofs, owner)
            }
        }
        vm.interrupts = true
        vm.runPass()
        Ishar3Start.toMenu(vm)
        assertEquals(1, m.gb(0x0CD8), "music1.io's tune plays (sound_seq_play, SoundBlaster.musicStart)")
        effects = 0
        click(vm, 160, 170, after = 1200) // Start game, then a minute in town
        assertTrue(effects > 0, "sound effects in town: $effects")
    }

    @Test
    fun `Ishar 2 to Ishar 3 brings an Ishar 2 party into play`() = runTest {
        // an Ishar 2 save made by Ishar 2's own SAVE (as Ishar2SavesTest)
        val base = MemorySaveStore()
        val ishar2 = GameFiles.fromResourceRepository(
            ResourceRepositoryImpl(GameBuild.ISHAR2), store = GameSaveStore(base, ishar2 = true), saveSize = null,
        )
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm2 = s.boot(ishar2, Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm2, i)
        repeat(20) { vm2.frame() }
        click(vm2, 266, 12, after = 25)
        click(vm2, 237, 55, after = 25) // SAVE
        type(vm2, "pp")
        repeat(80) { vm2.frame() }
        assertNotNull(base.read(GameSaveStore.ISHAR2_PREFIX + "pp.sav"), "the Ishar 2 save")
        val ishar2Party = Cheats.party(vm2.machine).map { it.name }

        val vm = boot(GameSaveStore(base, ishar2 = false, ishar3 = true))
        val m = vm.machine
        Ishar3Start.toMenu(vm)
        click(vm, 160, 118) // Load game
        click(vm, 160, 137, after = 60) // Ishar 2 to Ishar 3
        type(vm, "pp")
        repeat(200) { vm.frame() }
        assertEquals(ishar2Party, Cheats.party(m).map { it.name })
        click(vm, 160, 170, after = 400) // Start game
        assertEquals(ishar2Party, Cheats.party(m).map { it.name })
        assertNotNull(Frames.of(m, 0x0D), "in play")
    }

    @Test
    fun `heal brings a dead member back into the party`() = runTest {
        val vm = boot()
        val m = vm.machine
        val g = m.build.globals
        val b = m.build.globBase
        Ishar3Start.toMenu(vm)
        click(vm, 160, 160, after = 300) // Create the party
        createCharacter(vm, 35, "ann")
        createCharacter(vm, 99, "bob")
        click(vm, 123, 72, after = 600) // start game
        val party = Cheats.party(m)
        assertEquals(listOf("ann", "bob"), party.map { it.name.lowercase() })
        m.ww(g, b + 0x16E0 + 2, 0) // bob dies: frise.io puts his skull up
        repeat(120) { vm.frame() }
        assertEquals(0, Cheats.party(m)[1].life)
        Cheats.heal(vm)
        repeat(60) { vm.frame() }
        assertEquals(party[1].maxLife, Cheats.party(m)[1].life)
        assertTrue((0 until 25).any { m.rb(g, b + 0x347A + it) == 2 }, "bob back in the formation grid (frise.io 0e13)")
        assertNotNull(Frames.of(m, 0x0D), "still in play")
    }
}
