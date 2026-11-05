package dev.pelotasplus.ishar.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MapTeleportTest {
    private val rows = 90

    private fun grids(edit: ByteArray.() -> Unit = {}, contree: Int = 1) =
        (1..6).map { c -> ByteArray(54 * rows).also { if (c == contree) it.edit() } }

    private fun ByteArray.cell(x: Int, y: Int, code: Int) { this[x * rows + y] = code.toByte() }

    /** Everything in the box seen, as a walk around it would. */
    private fun PartyMap.seeAll(contree: Int, xs: IntRange, ys: IntRange, doors: Map<Pair<Int, Int>, Boolean> = emptyMap()) {
        for (x in xs) for (y in ys) for (f in 1..4) see(Cheats.Position(x, y, f, contree), doors)
    }

    private val at = Cheats.Position(10, 20, 2, 1)

    private fun check(map: PartyMap, x: Int, y: Int, p: Cheats.Position = at, live: Int = 0, creatures: List<Creatures.Creature> = emptyList()) =
        MapTeleport.refusal(map, p, map.sheetX(p)!! - p.x + x, y, live, creatures).also { map.see(p) }

    private fun open(edit: ByteArray.() -> Unit = {}) = WorldMap(grids(edit)).apply { seeAll(1, 5..30, 15..30); see(at) }

    @Test
    fun `a seen open cell in reach is allowed`() {
        assertNull(check(open(), 14, 22))
    }

    @Test
    fun `an unseen cell is refused`() {
        assertEquals(MapTeleport.Refusal.UNSEEN, check(open(), 45, 70))
    }

    @Test
    fun `walls, water and gates are refused`() {
        val map = open { cell(14, 22, 0x9D); cell(15, 22, 0xCD); cell(16, 22, 0xAE); cell(17, 22, 0xB0) }
        for (x in 14..17) assertEquals(MapTeleport.Refusal.BLOCKED, check(map, x, 22), "x $x")
    }

    @Test
    fun `a cell the running game has changed is refused`() {
        assertEquals(MapTeleport.Refusal.BLOCKED, check(open(), 14, 22, live = 0xCD))
    }

    @Test
    fun `no jump across water, even when the far side is seen`() {
        val map = open { for (y in 0 until rows) cell(15, y, 0xCD) }
        assertNull(check(map, 14, 22))
        assertEquals(MapTeleport.Refusal.NOT_REACHABLE, check(map, 16, 22))
    }

    @Test
    fun `a gap in the wall lets it through only over seen cells`() {
        val gapAtSeenEdge = open { for (y in 0 until rows) if (y != 25) cell(15, y, 0x9D) }
        assertNull(check(gapAtSeenEdge, 20, 22))
        val gapUnseen = open { for (y in 0 until rows) if (y != 60) cell(15, y, 0x9D) }
        assertEquals(MapTeleport.Refusal.NOT_REACHABLE, check(gapUnseen, 20, 22))
    }

    @Test
    fun `no walking through a gate on the way`() {
        val map = open { for (y in 0 until rows) cell(15, y, if (y == 25) 0xAF else 0x9D) }
        assertEquals(MapTeleport.Refusal.NOT_REACHABLE, check(map, 20, 22))
    }

    /** Grids 2..5 hold a cave or town from their mouths on: walled in, so that the rest is outdoors. */
    private fun outdoors(contree: Int, vararg mouths: Pair<Int, Int>): ByteArray = ByteArray(54 * rows).also { g ->
        for ((x, y) in mouths) for ((dx, dy) in listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)) g.cell(x + dx, y + dy, 0xCD)
    }

    @Test
    fun `the next contree is refused`() {
        val map = WorldMap(listOf(ByteArray(54 * rows), outdoors(2, 27 to 37)) + (3..6).map { ByteArray(54 * rows) })
        map.seeAll(2, 0..6, 30..40)
        map.seeAll(1, 45..53, 30..40)
        val p = Cheats.Position(50, 35, 2, 1)
        map.see(p)
        assertNull(check(map, 52, 35, p))
        assertEquals(MapTeleport.Refusal.BLOCKED, MapTeleport.refusal(map, p, 56, 35, 0, emptyList()))
    }

    @Test
    fun `a town's mouth and its way out are refused from the outdoors`() {
        val p = Cheats.Position(14, 73, 2, 3)
        val g3 = outdoors(3, 18 to 73, 32 to 57).also { it.cell(17, 73, 0) } // the way out is ground
        val map = WorldMap((1..6).map { if (it == 3) g3 else ByteArray(54 * rows) }).apply { seeAll(3, 10..17, 68..78); see(p) }
        val x = map.sheetX(p)!! - p.x
        assertNull(check(map, 16, 73, p))
        assertTrue(MapTeleport.refusal(map, p, x + 17, 73, 0, emptyList()) != null, "the way out's cell")
        assertTrue(MapTeleport.refusal(map, p, x + 18, 73, 0, emptyList()) != null, "the mouth")
    }

    @Test
    fun `a shut cave door stops it, an open one does not`() {
        val p = Cheats.Position(10, 20, 2, 15)
        fun map(shut: Boolean): WorldMap {
            val doors = mapOf((15 to 20) to shut)
            return WorldMap((1..6).map { c ->
                ByteArray(54 * rows).also { g ->
                    if (c == 5) {
                        for (x in 0 until 54) for (y in 0 until rows) g.cell(x, y, 0x9D)
                        for (x in 8..20) g.cell(x, 20, 0)
                        g.cell(15, 20, 0x2E)
                        g.cell(13, 24, 0); for (y in 20..24) g.cell(13, y, 0) // the cave's entrance, so that this is the cave
                    }
                }
            }).apply { seeAll(15, 5..25, 16..26, doors); see(p, doors) }
        }
        assertEquals(MapTeleport.Refusal.BLOCKED, check(map(true), 15, 20, p), "the door")
        assertEquals(MapTeleport.Refusal.NOT_REACHABLE, check(map(true), 18, 20, p), "behind it")
        assertNull(check(map(false), 18, 20, p))
    }

    @Test
    fun `a creature's cell is refused, a dead one's is not, one beside it is`() {
        val orc = Creatures.Creature(1, "ORC", 14, 22, 1, 10, -3, false)
        assertEquals(MapTeleport.Refusal.OCCUPIED, check(open(), 14, 22, creatures = listOf(orc)))
        assertNull(check(open(), 14, 22, creatures = listOf(orc.copy(life = 0))))
        assertNull(check(open(), 13, 22, creatures = listOf(orc)))
    }

    private fun island(layer0: (Int, Int) -> Int = { _, _ -> 1 }, layer1: (Int, Int) -> Int = { _, _ -> 0 }) = IslandMap(
        (1..7).map { ByteArray(90 * 60) { i -> layer0(i / 60, i % 60).toByte() } },
        (1..7).map { ByteArray(90 * 60) { i -> layer1(i / 60, i % 60).toByte() } },
    )

    /** A line of marks across the island is walked over in the game; a cell that refuses a step is not. */
    @Test
    fun `Ishar 2 crosses message and zone marks, not what stops a step`() {
        val p = Cheats.Position(10, 20, 2, 1)
        fun line(mark: Int) = island(layer1 = { x, _ -> if (x == 15) mark else 0 }).apply { seeAll(1, 5..30, 15..30); see(p) }
        for (crossed in listOf(4, 0xA8, 80)) {
            assertNull(check(line(crossed), 15, 22, p), "on $crossed")
            assertNull(check(line(crossed), 20, 22, p), "past $crossed")
        }
        for (stops in listOf(0x7E, 0x7F)) {
            assertEquals(MapTeleport.Refusal.BLOCKED, check(line(stops), 15, 22, p), "on $stops")
            assertEquals(MapTeleport.Refusal.NOT_REACHABLE, check(line(stops), 20, 22, p), "past $stops")
        }
        val p4 = Cheats.Position(10, 20, 2, 4)
        val sea = island(layer0 = { x, _ -> if (x == 15) 0 else 1 }).apply { seeAll(4, 5..30, 15..30); see(p4) }
        assertEquals(MapTeleport.Refusal.BLOCKED, check(sea, 15, 22, p4))
        assertEquals(MapTeleport.Refusal.NOT_REACHABLE, check(sea, 20, 22, p4))
        val p5 = Cheats.Position(10, 20, 2, 5)
        val plates = island(layer1 = { x, _ -> if (x == 15) 0x90 else 0 }).apply { seeAll(5, 5..30, 15..30); see(p5) }
        assertEquals(MapTeleport.Refusal.NOT_REACHABLE, check(plates, 20, 22, p5), "a cave's plate")
    }

    @Test
    fun `the zone follows the walk`() {
        val map = island(layer1 = { x, _ -> if (x == 15) 0xA8 else 0 })
        val sheet = 2 * 90 // island 3
        assertEquals(0x58 - 40 + 0, map.zoneAfter(3, 4, listOf(sheet + 14 to 20, sheet + 15 to 20, sheet + 16 to 20)))
        // island 1 by place: 4 -> 1 west of X 21 -> 3 south of Y 44
        assertEquals(1, IslandMap.zoneStep(1, 4, 20, 40, 0))
        assertEquals(3, IslandMap.zoneStep(1, 1, 20, 45, 0))
        assertEquals(5, IslandMap.zoneStep(1, 4, 41, 40, 0))
    }

    /** Ishar 2's first island: a real walk over a zone border, and a landing there, end in the same zone with its assets loaded. */
    @Test
    fun `an Ishar 2 landing over a zone border loads that zone as walking does`() = runTest {
        val files = GameFiles.fromResourceRepository(dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl(GameBuild.ISHAR2), saveSize = null)
        fun start(): Scheduler {
            val s = Starts.ISHAR2_SKIP_INTRO
            val vm = s.boot(files.fresh(), Machine(build = GameBuild.ISHAR2), sound = 'B')
            for (f in 1 until Starts.ISHAR2_SKIP_INTRO_FRAMES) s.step(vm, f)
            return vm
        }
        val g = GameBuild.ISHAR2.globals
        fun zone(vm: Scheduler) = vm.machine.rb(g, 0x4EF2 + 0x2A0)
        fun loaded(vm: Scheduler) = vm.machine.rb(g, 0x51B7 + 0x2A0)
        fun heal(vm: Scheduler) = Cheats.heal(vm.machine)
        val walked = start()
        assertEquals(4, zone(walked))
        val input = Input(walked).also { it.install() }
        fun press(key: Int) { heal(walked); input.keyDown(key); repeat(5) { walked.frame() }; input.keyUp(key); repeat(10) { walked.frame() } }
        // north along a clear column to Y 44, then west along that row until the zone changes
        press(0x4B)
        var tries = 0
        while (Cheats.position(walked.machine).y > 44 && tries++ < 20) press(Input.UP)
        press(0x4B)
        tries = 0
        while (zone(walked) == 4 && tries++ < 40) press(Input.UP)
        repeat(30) { walked.frame() }
        val there = Cheats.position(walked.machine)
        assertEquals(1, zone(walked), "walked to $there")
        assertEquals(1, loaded(walked))

        val landed = start()
        val map = IslandMap.fromFiles(files)
        val from = Cheats.position(landed.machine)
        for (y in there.y..from.y) for (x in there.x..from.x) for (f in 1..4) map.see(from.copy(x = x, y = y, facing = f))
        map.see(from)
        assertNull(MapTeleport.go(landed.machine, map, there.x, there.y, there.facing))
        repeat(30) { landed.frame() }
        assertEquals(1, zone(landed))
        assertEquals(1, loaded(landed), "main.io loaded the zone")
        assertEquals(there.x to there.y, Cheats.position(landed.machine).let { it.x to it.y })
    }

    private fun Scheduler.walk(key: Int, steps: Int) {
        val input = Input(this).also { it.install() }
        repeat(steps) {
            input.keyDown(key)
            repeat(4) { frame() }
            input.keyUp(key)
            repeat(4) { frame() }
        }
    }

    private fun glob(m: Machine, g: Int) = m.rw(0x126B, g + 0x2A0)

    /** The landing leaves the game as walking there leaves it: place, 3646, grids, the view; then a walk from both goes the same. */
    @Test
    fun `landing on a cell equals walking to it`() = runTest {
        val files = Play.files()
        val walked = Starts.skipIntro(files.fresh())
        val from = Cheats.position(walked.machine)
        walked.walk(Input.UP, 3)
        val there = Cheats.position(walked.machine)
        assertTrue(there != from, "$from did not move")

        val landed = Starts.skipIntro(files.fresh())
        val input = Input(landed).also { it.install() }
        input.poke { Cheats.moveWithinContree(it, there.x, there.y, (there.facing + 2) % 4 + 1) }
        repeat(3) { landed.frame() }
        input.keyDown(0x51)
        repeat(4) { landed.frame() }
        input.keyUp(0x51)
        repeat(20) { landed.frame() }
        repeat(20) { walked.frame() }

        assertEquals(there, Cheats.position(landed.machine))
        assertEquals(glob(walked.machine, 0x3646), glob(landed.machine, 0x3646), "3646")
        val grid = 0x126B * 16 + 0x80 + 0x2A0
        assertContentEquals(walked.machine.m.copyOfRange(grid, grid + 54 * 90), landed.machine.m.copyOfRange(grid, grid + 54 * 90), "the grid")

        val view = (0 until 140).flatMap { y -> (0 until 240).map { x -> y * 320 + x } }
        val a = Palette.frameArgb(walked.machine)
        val b = Palette.frameArgb(landed.machine)
        val diff = view.count { a[it] != b[it] }
        println("view differs in $diff of ${view.size}")
        assertTrue(diff < view.size / 20, "the view differs in $diff pixels")
    }

    /** The trap in the notes: 3646, not X, decides a seam. A landing near one crosses it with the right map loaded. */
    @Test
    fun `from a landing by a seam the party walks into the next contree`() = runTest {
        val files = Play.files()
        val vm = Starts.skipIntro(files.fresh())
        val input = Input(vm).also { it.install() }
        input.poke { Cheats.moveWithinContree(it, 46, 36, 1) }
        repeat(3) { vm.frame() }
        input.keyDown(0x51)
        repeat(4) { vm.frame() }
        input.keyUp(0x51)
        repeat(10) { vm.frame() }
        assertEquals(Cheats.Position(46, 36, 2, 1), Cheats.position(vm.machine))
        vm.walk(Input.UP, 9)
        val p = Cheats.position(vm.machine)
        assertEquals(2, p.contree, "$p")
        assertTrue(p.x < 8, "$p")
        assertEquals(54 + p.x, glob(vm.machine, 0x3646))
        val grid = 0x126B * 16 + 0x234A + 0x2A0 // an even contree's
        val cont2 = TestResources().readBytes("cont2.fic").getOrThrow()
        assertContentEquals(cont2.copyOf(54 * 90), vm.machine.m.copyOfRange(grid, grid + 54 * 90), "contree 2's grid")
    }

    @Test
    fun `the game refuses while it wants something else, and in Ishar 3`() = runTest {
        val vm = Starts.skipIntro(Play.files())
        val m = vm.machine
        val map = WorldMap.fromFiles(Play.files())
        val p = Cheats.position(m)
        repeat(4) { map.see(p.copy(facing = it + 1)) }
        val x = map.sheetX(p)!!
        val free = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).map { (dx, dy) -> x + dx to p.y + dy }.first { (a, b) -> MapTeleport.refusal(m, map, p, a, b) == null }
        m.wb(0x126B, 0x399E + 0x2A0, 1)
        assertEquals(MapTeleport.Refusal.BUSY, MapTeleport.refusal(m, map, p, free.first, free.second))
        m.wb(0x126B, 0x399E + 0x2A0, 0)
        assertNull(MapTeleport.refusal(m, map, p, free.first, free.second))
        assertEquals(MapTeleport.Refusal.NOT_IN_THIS_GAME, MapTeleport.refusal(Machine(build = GameBuild.ISHAR3), map, p, free.first, free.second))
    }
}
