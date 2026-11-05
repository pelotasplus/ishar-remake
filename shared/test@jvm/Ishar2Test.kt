package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import dev.pelotasplus.ishar.data.store.GameSaveStore
import dev.pelotasplus.ishar.data.store.MemorySaveStore
import kotlinx.coroutines.test.runTest
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Ishar 2 on the same engine ([GameBuild.ISHAR2]): its files staged under `files/ishar2/`. */
class Ishar2Test {

    private suspend fun files() = GameFiles.fromResourceRepository(ResourceRepositoryImpl(GameBuild.ISHAR2), saveSize = null)

    @Test
    fun `the intro skipped puts Zubaran on Irvan's Island`() = runTest {
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (f in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, f)
        // main.io 0979: a new game sets 1bh, 2fh, east, contree 1
        assertEquals(Cheats.Position(0x1B, 0x2F, 2, 1), Cheats.position(vm.machine))
        // the reference engine with the same boot answers and keys (--game ishar2, sound B): the same count and screen
        assertEquals(223_038L, vm.statements)
        assertEquals(0xC46FDA36L, CRC32().apply { update(vm.machine.m, 0xA0000, 64000) }.value)
    }

    @Test
    fun `a click on the logo skips the intro and a tap picks the music`() = runTest {
        val vm = Session("boot", 0x14C, 0x57, emptyMap()).boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B')
        val input = Input(vm).also { it.install() }
        repeat(40) { vm.frame() }
        input.pointer(160, 100)
        input.buttons(1)
        repeat(12) { vm.frame() }
        input.buttons(0)
        repeat(150) { vm.frame() }
        assertEquals(0x356B, Frames.resume(vm.machine, 0), "on the MUSIC menu")
        input.pointer(160, 100) // 1: ON
        input.buttons(1)
        repeat(12) { vm.frame() }
        input.buttons(0)
        repeat(400) { vm.frame() }
        assertEquals(Cheats.Position(0x1B, 0x2F, 2, 1), Cheats.position(vm.machine))
        assertEquals(1, vm.machine.rb(GameBuild.ISHAR2.globals, 0x51A5 + 0x2A0), "music on")
    }

    @Test
    fun `each copy-protection block is where the patch looks`() = runTest {
        val f = files()
        val sites = mapOf(
            "textine.io" to listOf(0x044B to 0x91, 0x10C1 to 0x92, 0x1285 to 0x92),
            "textind.io" to listOf(0x0474 to 0x91, 0x10E0 to 0x92, 0x1297 to 0x92),
            "textin.io" to listOf(0x048E to 0x91, 0x1196 to 0x92, 0x1354 to 0x92),
        )
        for ((name, at) in sites) {
            val bytes = IoDecoder.decode(f.read(name), ByteArray(IoDecoder.REFILL_BYTES)).bytes
            val m = Machine(build = GameBuild.ISHAR2)
            bytes.copyInto(m.m, 0x20000)
            CopyProtection.removeIshar2(m, name, 0x20000)
            for ((ofs, checker) in at) {
                assertEquals(0x45, bytes[ofs].toInt() and 0xFF, "$name $ofs")
                assertEquals(checker, bytes[ofs + 1].toInt() and 0xFF, "$name $ofs")
                val rel = if (checker == 0x91) 0x11 else 0x13
                assertContentEquals(byteArrayOf(0x0A, rel.toByte(), 0, 0), m.m.copyOfRange(0x20000 + ofs, 0x20000 + ofs + 4), "$name $ofs")
            }
        }
    }

    @Test
    fun `a tap outside the disk prompt cancels it like F1`() = runTest {
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, i)
        val input = Input(vm).also { it.install() }
        fun click(x: Int, y: Int) {
            input.pointer(x, y)
            input.buttons(1)
            repeat(5) { vm.frame() }
            input.buttons(0)
            repeat(25) { vm.frame() }
        }
        repeat(20) { vm.frame() }
        click(266, 12) // the disk
        click(237, 32) // LOAD
        assertEquals(0x0499, Frames.resume(vm.machine, 0x08), "the name prompt")
        click(120, 60) // the 3D view
        repeat(30) { vm.frame() }
        assertNull(Frames.of(vm.machine, 0x08), "io.io gone")
    }

    @Test
    fun `a held key types its letter once at a disk prompt`() = runTest {
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, i)
        val input = Input(vm).also { it.install() }
        fun click(x: Int, y: Int) {
            input.pointer(x, y); input.buttons(1); repeat(5) { vm.frame() }; input.buttons(0); repeat(25) { vm.frame() }
        }
        repeat(20) { vm.frame() }
        click(266, 12)
        click(237, 32) // LOAD
        val a = Input.scancodeFor('a')!!
        repeat(4) { input.keyDown(a); repeat(3) { vm.frame() } } // the host's auto-repeat
        input.keyUp(a)
        repeat(10) { vm.frame() }
        input.keyDown(Input.ENTER); repeat(4) { vm.frame() }; input.keyUp(Input.ENTER)
        repeat(10) { vm.frame() }
        val m = vm.machine
        val typed = (0 until 8).map { m.rb(GameBuild.ISHAR2.globals, 0x4EF8 + 0x2A0 + it) }.takeWhile { it != 0 }
        assertEquals("a.sav", typed.map { it.toChar() }.joinToString(""), "one letter, not four")
    }

    @Test
    fun `closed doors count past contree 4 only, on their door-edge codes`() = runTest {
        val map = IslandMap.fromFiles(files())
        // Akeer's Island: door 48 at 37,25 (layer 0 44) refused the step from 36,25 while closed (live, W14/A saves)
        val akeer = Cheats.Position(36, 25, 2, 5)
        map.see(akeer, mapOf((37 to 25) to true))
        assertTrue(map.closedDoor(map.sheetX(akeer)!! + 1, 25))
        // Jon's Island: layer 1 74 at 3,12 with its state 0 let the party walk on (live): a place mark, not a door
        val jon = Cheats.Position(2, 12, 2, 4)
        map.see(jon, mapOf((3 to 12) to true))
        assertTrue(!map.closedDoor(map.sheetX(jon)!! + 1, 12))
        // past contree 4 but not on a door-edge code: layer 1 86 on layer 0 83 at 12,20 of Akeer's Island
        val notEdge = Cheats.Position(11, 20, 2, 5)
        map.see(notEdge, mapOf((12 to 20) to true))
        assertTrue(!map.closedDoor(map.sheetX(notEdge)!! + 1, 20))
    }

    @Test
    fun `only a real closed door stops the map's view`() = runTest {
        // Jon's Island: W13 starts at 58,37 facing east, the party walks to 60,37 through layer 1 86 with its state 0
        val jon = Cheats.Position(58, 37, 2, 4)
        val onJon = IslandMap.fromFiles(files())
        onJon.see(jon, mapOf((59 to 37) to true))
        assertNotNull(onJon.colour(onJon.sheetX(jon)!! + 2, 37), "seen past a place mark")
        // Akeer's Island: the real door at 37,25, shut and then open
        val akeer = Cheats.Position(36, 25, 2, 5)
        val onAkeer = IslandMap.fromFiles(files())
        val past = onAkeer.sheetX(akeer)!! + 2
        onAkeer.see(akeer, mapOf((37 to 25) to true))
        assertNull(onAkeer.colour(past, 25), "not seen past a shut door")
        onAkeer.see(akeer, mapOf((37 to 25) to false))
        assertNotNull(onAkeer.colour(past, 25), "seen once it opens")
    }

    @Test
    fun `the island map marks what the party sees and keeps it`() = runTest {
        val f = files()
        val map = IslandMap.fromFiles(f)
        val start = Cheats.Position(0x1B, 0x2F, 2, 1) // a new game, facing east
        val grid = f.read("cont1.fic")
        assertEquals(false, IslandMap.blocks(grid[0x1B * 60 + 0x2F].toInt() and 0xFF), "the party stands on walkable ground")
        map.see(start)
        val x = assertNotNull(map.sheetX(start))
        assertNotNull(map.colour(x, start.y), "its own cell")
        assertNotNull(map.colour(x + 3, start.y), "three ahead")
        assertNull(map.colour(x - 3, start.y), "nothing behind")
        val again = IslandMap.fromFiles(f)
        again.restore(map.encode())
        assertEquals(map.seenBounds(x), again.seenBounds(x))
        assertNull(map.sheetX(Cheats.Position(1, 1, 1, 0)), "no contree, no map")
    }

    @Test
    fun `the island map shows the ground items and buildings the party has seen`() = runTest {
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, i)
        val items = GroundItems.all(vm.machine).filter { it.contree == 1 && it.code <= 0x6E }
        assertTrue(items.any { it.x == 1 && it.y == 28 }, "the item at the standing stone, two cells west of 3,28")
        val map = IslandMap.fromFiles(files())
        assertTrue(map.knownItems(items).isEmpty(), "nothing seen yet")
        map.see(Cheats.Position(3, 28, 4, 1)) // facing west, as in the player's screenshot
        assertTrue(map.knownItems(items).any { it.x == 1 && it.y == 28 })
        map.see(Cheats.Position(18, 5, 1, 1)) // before Gordbnoeuf's door, 18,4
        assertTrue(map.visitedBuildings.isEmpty(), "seen is not visited")
        map.enterScene(Cheats.Position(18, 5, 1, 1)) // pp11.io loads
        map.enterScene(Cheats.Position(2, 53, 4, 1)) // pptravel.io loads at the boat, 1,53
        assertTrue(map.visitedBuildings.any { it.kind == Buildings.Kind.BOAT && it.x == 1 && it.y == 53 })
        assertTrue(map.visitedBuildings.any { it.kind == Buildings.Kind.GORDBNOEUF && it.x == 18 && it.y == 4 })
        map.see(Cheats.Position(22, 19, 4, 1)) // before the stone tower
        assertEquals(0xEB781E, map.colour(assertNotNull(map.sheetX(1, 21, 19)), 19))
    }

    @Test
    fun `the island map shows the people in view and the taverns visited`() = runTest {
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, i)
        val creatures = Creatures.all(vm.machine)
        val map = IslandMap.fromFiles(files())
        val start = Cheats.position(vm.machine) // 1b,2f facing east; the peasants stand 4 cells east
        map.see(start)
        map.spot(start, creatures)
        val known = map.known(creatures)
        assertTrue(known.any { it.hostile } && known.any { !it.hostile }, "hostile and peaceful peasants: " + known.joinToString { "${it.index}@${it.x},${it.y} ${it.mode}" })
        assertTrue(known.all { it.contree == 1 && !it.inParty && it.life > 0 })
        // the party walks into another zone: the peasants are not placed there, but stay where they were seen
        val elsewhere = creatures.map { it.copy(contree = 0) }
        map.spot(start, elsewhere)
        assertEquals(known.map { it.index }, map.known(elsewhere).map { it.index })
        assertTrue(map.known(elsewhere).all { it.contree == 1 })
        // the next district reuses those records for its own people, far away: the ones seen stay put
        val reused = creatures.map { if (it.index in known.map { k -> k.index }) it.copy(zone = it.zone + 1, x = 5, y = 5) else it }
        map.spot(start, reused)
        assertEquals(known.map { it.x to it.y }, map.known(reused).map { it.x to it.y })
        map.enter(Cheats.Position(11, 12, 4, 1), Buildings.Kind.TAVERN) // the player's tavern door, facing west
        val again = IslandMap.fromFiles(files())
        again.restore(map.encode())
        assertEquals(listOf(Buildings.Building(Buildings.Kind.TAVERN, 10, 12, 1)), again.visitedBuildings)
        val seen = map.known(reused).map { listOf(it.zone, it.index, it.x, it.y, it.contree, it.mode, it.life) }
        assertEquals(seen, again.known(emptyList()).map { listOf(it.zone, it.index, it.x, it.y, it.contree, it.mode, it.life) }, "the people seen come back with a save")
        val old = map.encode().let { it.copyOf(it.size - 4 - 8 * seen.size) } // a map saved before people were kept
        val older = IslandMap.fromFiles(files())
        older.restore(old)
        assertEquals(again.visitedBuildings, older.visitedBuildings)
        assertTrue(older.known(emptyList()).isEmpty())
        assertEquals(again.seenBounds(assertNotNull(map.sheetX(start))), older.seenBounds(assertNotNull(map.sheetX(start))))
    }

    @Test
    fun `Space swings at a hostile ahead and only then`() = runTest {
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, i)
        val input = Input(vm).also { it.install() }
        val m = vm.machine
        fun physical() = Cheats.party(m)[0].stats[6]
        fun space() {
            input.keyDown(Input.SPACE); repeat(3) { vm.frame() }; input.keyUp(Input.SPACE); repeat(40) { vm.frame() }
        }
        repeat(20) { vm.frame() }
        space()
        assertEquals(100, physical(), "nothing ahead: no swing")
        // a hostile peasant (record 66, mode -3) on the cell ahead of 1b,2f facing east
        val g = GameBuild.ISHAR2.globals
        m.ww(g, 0x3412 + 0x2A0 + 2 * 66, 0x1C)
        m.ww(g, 0x34F0 + 0x2A0 + 2 * 66, 0x2F)
        space()
        val afterAhead = physical()
        assertTrue(afterAhead < 100, "Zubaran swung")
        // Ishar 2's fighters stand on the party's own cell
        m.ww(g, 0x3412 + 0x2A0 + 2 * 66, 0x1B)
        repeat(60) { vm.frame() }
        val before = physical()
        space()
        assertTrue(physical() < before, "Zubaran swung at the one on his cell")
    }

    /** LR.SAV with [more] members after ARAMIR, warriors like him, as Ishar 1 lays it out (SaveGames). */
    private fun members(vararg more: String): ByteArray {
        val d = TestResources.save("LR.SAV").copyOf()
        fun w(at: Int, v: Int) { d[at] = (v shr 8).toByte(); d[at + 1] = v.toByte() }
        more.forEachIndexed { i, name ->
            val s = i + 1
            name.encodeToByteArray().copyInto(d, 418 + 8 * s)
            w(468 + 2 * s, 30); w(488 + 2 * s, 30) // life, max life
            d[728 + s] = d[728]; d[738 + s] = d[738] // class, level
        }
        return d
    }

    /** Ishar 2 started, with the party of [members] imported through the disk menu's I -> II. */
    private suspend fun imported(vararg more: String): Scheduler {
        val base = MemorySaveStore()
        base.write("mk.sav", members(*more))
        val f = GameFiles.fromResourceRepository(ResourceRepositoryImpl(GameBuild.ISHAR2), store = GameSaveStore(base, ishar2 = true), saveSize = null)
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(f, Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, i)
        val m = vm.machine
        fun click(x: Int, y: Int) { Boot.mouse(m, x, y, 1); repeat(5) { vm.frame() }; Boot.mouse(m, buttons = 0); repeat(25) { vm.frame() } }
        fun key(sc: Int) {
            Session.apply(vm, Session.Event.KeyDown(sc)); Session.apply(vm, Session.Event.SpinUp(sc))
            repeat(3) { vm.frame() }; Session.apply(vm, Session.Event.KeyUp(sc)); repeat(3) { vm.frame() }
        }
        repeat(20) { vm.frame() }
        click(266, 12); click(237, 116) // the disk menu's I -> II
        for (c in "mk") key(Input.scancodeFor(c)!!)
        key(Input.ENTER)
        repeat(300) { vm.frame() }
        val names = listOf("Aramir") + more.map { it.lowercase().replaceFirstChar(Char::uppercase) }
        assertEquals(names, Cheats.party(m).map { it.name }, "imported")
        return vm
    }

    @Test
    fun `Space swings for every member while frise io is busy with one`() = runTest {
        val vm = imported("BRAVO", "CHARLIE", "DELTA", "ECHO")
        val m = vm.machine
        val g = GameBuild.ISHAR2.globals
        Cheats.heal(vm) // the made-up save gave them no formation cells: all five to the front
        repeat(10) { vm.frame() }
        val input = Input(vm).also { it.install() }
        // a hostile peasant (record 66, mode -3) on the cell ahead of 1b,2f facing east
        m.ww(g, 0x3412 + 0x2A0 + 2 * 66, 0x1C)
        m.ww(g, 0x34F0 + 0x2A0 + 2 * 66, 0x2F)
        // Aramir possessed (2fb6, gerdep.io 0429): his swing hits a teammate, and frise.io shows the wound for
        // a while (L536b, 552a). Fists pressed meanwhile replaced each other in 4f10 before frise.io took them.
        m.ww(g, 0x2FB6 + 0x2A0, 1)
        repeat(20) { vm.frame() }
        Boot.mouse(m, 150, 50)
        repeat(5) { vm.frame() }
        val before = Cheats.party(m).map { it.life }
        input.keyDown(Input.SPACE); repeat(3) { vm.frame() }; input.keyUp(Input.SPACE); repeat(400) { vm.frame() }
        val party = Cheats.party(m)
        assertTrue(party.zip(before).any { (p, l) -> p.life < l }, "Aramir hit a teammate")
        val physical = party.drop(1).map { it.stats[6] }
        assertTrue(physical.all { it < 100 }, "the other four swung: PHYSICAL $physical")
    }

    @Test
    fun `Heal all brings a dead member back so he strikes again`() = runTest {
        val vm = imported("BRAVO")
        val m = vm.machine
        val g = GameBuild.ISHAR2.globals
        fun click(x: Int, y: Int) { Boot.mouse(m, x, y, 1); repeat(5) { vm.frame() }; Boot.mouse(m, buttons = 0); repeat(25) { vm.frame() } }
        fun formation() = (0 until 25).map { m.rb(g, 0x49E6 + 0x2A0 + it) }.filter { it != 0 }.sorted()
        Cheats.heal(vm) // the made-up save gave Bravo no formation cell: Heal all places him
        repeat(10) { vm.frame() }
        assertEquals(listOf(1, 2), formation())
        // Bravo's cell of the party panel (x 64..127, y 140..199), where frise.io draws his portrait and skull
        fun portrait() = (140 until 200).flatMap { y -> (64 until 128).map { x -> m.rb(Machine.SCREEN, y * 320 + x) } }
        val alive = portrait()
        m.ww(g, 0x2D5C + 0x2A0 + 2, 0) // Bravo falls
        repeat(60) { vm.frame() }
        assertEquals(listOf(1), formation(), "the game takes the dead out of the formation")
        assertNotEquals(alive, portrait(), "a skull over him")
        Cheats.heal(vm)
        repeat(10) { vm.frame() }
        assertEquals(alive, portrait(), "the skull gone (frise.io event 32)")
        assertEquals(listOf(1, 2), formation(), "back in")
        assertEquals(1, (0 until 5).count { m.rb(g, 0x49E6 + 0x2A0 + it * 5 + 4) == 2 }, "at the front, once")
        m.ww(g, 0x2D5C + 0x2A0 + 2, 0) // Bravo falls again, and his life comes back before any heal
        repeat(60) { vm.frame() }
        m.ww(g, 0x2D5C + 0x2A0 + 2, Cheats.party(m)[1].maxLife)
        repeat(10) { vm.frame() }
        assertNotEquals(alive, portrait(), "a skull over a member with life")
        Cheats.heal(vm)
        Cheats.heal(vm) // twice before frise.io takes the first: one revive
        assertEquals(1, Frames.queued(m, 6).windowed(2).count { it == listOf(32, 1) }, "one revive queued")
        repeat(10) { vm.frame() }
        assertEquals(alive, portrait(), "Heal all takes that skull off too")
        assertEquals(listOf(1, 2), formation(), "back in again")
        assertEquals(1, (0 until 5).count { m.rb(g, 0x49E6 + 0x2A0 + it * 5 + 4) == 2 }, "at the front again")
        val before = Cheats.party(m)[1].stats[6]
        click(277, 113) // fist II
        repeat(20) { vm.frame() }
        assertTrue(Cheats.party(m)[1].stats[6] < before, "Bravo swung")
    }

    @Test
    fun `the boat's map opens and Exit closes it`() = runTest {
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, i)
        val m = vm.machine
        m.wb(GameBuild.ISHAR2.globals, 0x4F13 + 0x2A0, 4) // Gordbnoeuf gave the boat
        Cheats.moveParty(m, 2, 53, 4) // before the boat at 1,53, facing west
        repeat(10) { vm.frame() }
        Boot.keyDown(m, 0x48); repeat(4) { vm.frame() }; Boot.keyUp(m, 0x48)
        repeat(200) { vm.frame() }
        assertNotNull(Frames.of(m, 0x14), "map.io's map (main.io 30c2), whose compound ends with a palette part")
        Boot.mouse(m, 300, 190, 1); repeat(5) { vm.frame() }; Boot.mouse(m, buttons = 0) // Exit
        repeat(100) { vm.frame() }
        assertNull(Frames.of(m, 0x14), "Exit closes it")
    }

    @Test
    fun `the island map shows doors, levers, and a closed door hides what is behind it`() = runTest {
        val m = Machine(build = GameBuild.ISHAR2) // every door state 0: closed
        val map = IslandMap.fromFiles(files())
        val p = Cheats.Position(45, 40, 4, 5) // Akeer's Island, facing west at door 3 (44,40)
        map.see(p, map.doors(m, 5))
        val door = assertNotNull(map.sheetX(5, 44, 40))
        assertEquals(0xFF8C00, map.colour(door, 40), "door 3")
        assertNull(map.colour(door - 1, 40), "nothing seen through the closed door")
        map.see(Cheats.Position(48, 45, 3, 5), map.doors(m, 5)) // lever 3 at 48,46, south of 48,45
        assertEquals(0xFFE600, map.colour(assertNotNull(map.sheetX(5, 48, 46)), 46), "lever 3")
    }

    @Test
    fun `Remove curses clears what creatures put on the party`() = runTest {
        val s = Starts.ISHAR2_SKIP_INTRO
        val vm = s.boot(files(), Machine(build = GameBuild.ISHAR2), sound = 'B')
        for (i in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, i)
        val m = vm.machine
        val g = GameBuild.ISHAR2.globals
        // as eatt.io 1059..105d leaves it: icon 20 and the 2e4a flag on Zubaran
        m.wb(g, 0x4B88 + 0x2A0, 20)
        m.ww(g, 0x2E4A + 0x2A0, 1)
        Cheats.removeCurses(m)
        repeat(60) { vm.frame() }
        assertEquals(0, m.rw(g, 0x2E4A + 0x2A0))
        assertEquals(0, m.rw(g, 0x2E26 + 0x2A0), "the one-tick binding ran out")
        val icons = (0 until 10).map { m.rb(g, 0x4B88 + 0x2A0 + it) }
        assertTrue(20 !in icons, "icon 20 gone: $icons")
    }

    @Test
    fun `LOAD and SAVE no longer say transfered`() = runTest {
        val bytes = IoDecoder.decode(files().read("messagee.io"), ByteArray(IoDecoder.REFILL_BYTES)).bytes
        val m = Machine(build = GameBuild.ISHAR2)
        bytes.copyInto(m.m, 0x20000)
        Ishar2Text.fix(m, "messagee.io", 0x20000, 0x20000 + bytes.size)
        val text = m.m.copyOfRange(0x20000, 0x20000 + bytes.size).decodeToString()
        assertEquals(false, "to be transfered" in text)
        assertEquals(true, "enter the name of the saved game file below" in text)
        assertEquals(true, "F ile is for the other I shar" in text)
    }

    @Test
    fun `Ishar 2 keeps its own saves and reads Ishar 1's to import them`() {
        val base = MemorySaveStore()
        val one = GameSaveStore(base, ishar2 = false)
        val two = GameSaveStore(base, ishar2 = true)
        one.write("lr.sav", ByteArray(5216) { 1 })
        two.write("me.sav", ByteArray(100) { 2 })
        assertEquals(listOf("lr.sav"), one.names())
        assertEquals(listOf("me.sav"), two.names())
        assertEquals(1, two.read("lr.sav")!![0].toInt()) // the I -> II import
        assertNull(one.read("me.sav"))
        two.write("lr.sav", ByteArray(100) { 3 }) // its own of that name wins, Ishar 1's stays
        assertEquals(3, two.read("lr.sav")!![0].toInt())
        assertEquals(1, one.read("lr.sav")!![0].toInt())
    }
}
