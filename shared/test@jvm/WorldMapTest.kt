package dev.pelotasplus.ishar.engine

import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorldMapTest {
    private fun blank(edit: ByteArray.() -> Unit = {}) = List(6) { ByteArray(WorldMap.WIDTH * WorldMap.ROWS).apply(edit) }

    private fun ByteArray.set(x: Int, y: Int, code: Int) {
        this[x * WorldMap.ROWS + y] = code.toByte()
    }

    @Test
    fun `every code of the chani's cell table has its class`() {
        val found = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, "ishar.chani") }.firstOrNull { it.exists() }
        assumeTrue(found != null) { "the notes (ishar.chani) are not above this folder: skipped" }
        val chani = found!!
        val line = Regex("""CELL g(\d) (\S+) ([0-9a-f]{2}) x\d+ .*?\[([^\]]+)]""")
        var checked = 0
        for (m in line.findAll(chani.readText())) {
            val (grid, where, code, name) = m.destructured
            if (name == "unused" || where == "none") continue
            val area = WorldMap.areaOf(where.removePrefix("c").toInt())!!
            val want = WorldMap.CellClass.valueOf(name.uppercase().replace(' ', '_'))
            assertEquals(want, WorldMap.classOf(grid.toInt(), area, code.toInt(16)), m.value)
            checked++
        }
        assertTrue(checked > 200, "only $checked lines")
    }

    @Test
    fun `a closed cave door is marked, an open one is not`() {
        val map = WorldMap(blank())
        val p = Cheats.Position(10, 20, 3, 1)
        map.see(p, mapOf((10 to 21) to true, (11 to 20) to false))
        val x = assertNotNull(map.sheetX(p))
        assertTrue(map.closedDoor(x, 21))
        assertTrue(!map.closedDoor(x + 1, 20), "open")
        assertTrue(!map.blocks(x, 21), "a door edge, not a wall: from its other side the step passes")
    }

    @Test
    fun `the party sees ahead across water but not through a forest`() {
        val map = WorldMap(blank {
            set(10, 15, 0xCD) // water two ahead
            set(12, 15, 0xE5) // forest in the next column
        })
        map.see(Cheats.Position(10, 20, 1, 1))
        assertEquals(WorldMap.CellClass.WATER.rgb, map.colour(10, 15))
        assertNotNull(map.colour(10, 14))
        assertNull(map.colour(10, 21 + 1), "behind the party")
        assertNull(map.colour(10, 13), "beyond the view")

        val wood = WorldMap(blank { set(10, 18, 0xE5) })
        wood.see(Cheats.Position(10, 20, 1, 1))
        assertEquals(WorldMap.CellClass.FOREST.rgb, wood.colour(10, 18))
        assertNull(wood.colour(10, 17))
    }

    @Test
    fun `a cave's rock hides the corridor behind it and the same code colours by area`() {
        val map = WorldMap(blank { set(10, 19, 0x9D) })
        map.see(Cheats.Position(10, 20, 1, 12))
        assertEquals(WorldMap.CellClass.CAVE_ROCK.rgb, map.colour(WorldMap.WIDTH + 10, 19))
        assertNull(map.colour(WorldMap.WIDTH + 10, 18))
    }

    @Test
    fun `sight crosses a seam but not onto the temple sheet`() {
        val map = WorldMap(blank { // cave 12's mouth walled off, or a blank grid 2 is all cave
            set(27, 36, 0x9D)
            set(26, 37, 0x9D)
            set(28, 37, 0x9D)
            set(27, 38, 0xE5)
        })
        map.see(Cheats.Position(52, 20, 2, 1))
        assertNotNull(map.colour(WorldMap.WIDTH + 2, 20))

        val last = WorldMap(blank())
        last.see(Cheats.Position(52, 20, 2, 5))
        assertNull(last.colour(WorldMap.TEMPLE_X, 20))
    }

    private fun creature(x: Int, y: Int, contree: Int = 1, life: Int = 5, inParty: Boolean = false) =
        Creatures.Creature(0, "ORC", x, y, contree, life, -3, inParty)

    @Test
    fun `only creatures in sight are in view`() {
        val p = Cheats.Position(10, 20, 3, 1) // facing south
        val map = WorldMap(blank { set(10, 24, 0xE5) })
        fun sees(c: Creatures.Creature) = map.inView(p, listOf(c)).isNotEmpty()
        assertTrue(sees(creature(10, 22)), "ahead")
        assertTrue(sees(creature(10, 19)), "beside the party, even behind")
        assertTrue(!sees(creature(10, 17)), "behind")
        assertTrue(!sees(creature(10, 25)), "behind the forest")
        assertTrue(!sees(creature(10, 22, life = 0)), "dead")
        assertTrue(!sees(creature(10, 22, inParty = true)), "a member")
        assertTrue(!sees(creature(10, 22, contree = 2)), "another contree's X")
        assertTrue(!sees(creature(10, 22, contree = 13)), "inside the town")
        assertTrue(!sees(creature(10, 22, contree = 0)), "not placed")
    }

    @Test
    fun `a creature seen once stays on the map until it moves or dies`() {
        val map = WorldMap(blank())
        val orc = creature(10, 22)
        map.spot(Cheats.Position(10, 20, 3, 1), listOf(orc))
        map.spot(Cheats.Position(10, 20, 1, 1), listOf(orc)) // turned away
        assertEquals(listOf(orc), map.known(listOf(orc)))
        assertEquals(emptyList(), map.known(listOf(creature(11, 22))), "moved while out of sight")
        assertEquals(emptyList(), map.known(listOf(creature(10, 22, life = 0))))
        assertEquals(emptyList(), WorldMap(blank()).known(listOf(orc)), "never seen")

        val back = WorldMap(blank()).apply { restore(map.encode()) }
        assertEquals(listOf(orc), back.known(listOf(orc)), "kept with the save")
    }

    @Test
    fun `a ground item seen once stays on the map until it is taken`() {
        val map = WorldMap(blank { set(10, 24, 0xE5) })
        val item = GroundItems.Item(41, 10, 22, 1)
        val hidden = GroundItems.Item(42, 10, 25, 1)
        val p = Cheats.Position(10, 20, 3, 1)
        assertEquals(emptyList(), map.knownItems(listOf(item, hidden)))
        map.spotItems(p, listOf(item, hidden), ZONE_OUTSIDE)
        assertEquals(listOf(item), map.knownItems(listOf(item, hidden)))
        map.spotItems(Cheats.Position(10, 20, 1, 1), listOf(item), ZONE_OUTSIDE)
        assertEquals(listOf(item), map.knownItems(listOf(item)), "still known when looking away")
        val back = WorldMap(blank()).apply { restore(map.encode()) }
        assertEquals(listOf(item), back.knownItems(listOf(item)), "kept beside the save")
        assertEquals(emptyList(), map.knownItems(listOf(item.copy(x = 0, y = 0, contree = 0))), "taken")
    }

    /** The notes' live match: c1 13,64 facing S looks at item 41, a sack, on the new game's table. */
    @Test
    fun `the new game's table has the sack the view draws`() = runTest {
        val items = GroundItems.all(Starts.skipIntro(Play.files()).machine)
        val sack = items.single { it.code == 41 }
        assertEquals(GroundItems.Item(41, 13, 65, 1), sack)
        assertTrue(sack.money)
        val map = WorldMap((1..6).map { Play.files().read("cont$it.fic") })
        map.spotItems(Cheats.Position(13, 64, 3, 1), items, ZONE_OUTSIDE)
        assertTrue(sack in map.knownItems(items))
    }

    @Test
    fun `visited buildings survive encode and restore`() {
        val map = WorldMap(blank())
        val tavern = Buildings.Building(Buildings.Kind.TAVERN, 18, 56, 1)
        map.visit(tavern)
        map.visit(tavern)
        val back = WorldMap(blank()).apply { restore(map.encode()) }
        assertEquals(listOf(tavern), back.visitedBuildings)
        back.restore(null)
        assertEquals(emptyList(), back.visitedBuildings)
    }

    /** The notes' tables on a new game, and the door a party walking into it stands next to. */
    @Test
    fun `the new game's place tables`() = runTest {
        val all = Buildings.all(Starts.skipIntro(Play.files()).machine)
        assertEquals(mapOf(Buildings.Kind.SHOP to 9, Buildings.Kind.TAVERN to 12, Buildings.Kind.SPELL_HALL to 4, Buildings.Kind.COURSE to 10,
            Buildings.Kind.PSYCHOANALYST to 2, Buildings.Kind.JON to 1, Buildings.Kind.ERWAN to 1, Buildings.Kind.DELORIA to 1,
            Buildings.Kind.THORM to 1, Buildings.Kind.FORBIDDEN to 1, Buildings.Kind.CHEST to 1, Buildings.Kind.HOUSE to 3),
            all.groupingBy { it.kind }.eachCount())
        val tavern = Buildings.Building(Buildings.Kind.TAVERN, 18, 56, 1)
        assertEquals(tavern, Buildings.at(Cheats.Position(17, 56, 2, 1), all))
        assertEquals(Buildings.Building(Buildings.Kind.SHOP, 17, 52, 1), Buildings.at(Cheats.Position(17, 53, 1, 1), all))
        assertNull(Buildings.at(Cheats.Position(17, 56, 2, 2), all))
        assertEquals(Buildings.Kind.ERWAN, Buildings.at(Cheats.Position(18, 11, 1, 4), all)?.kind)
        assertEquals(Buildings.Kind.HOUSE, Buildings.at(Cheats.Position(14, 55, 1, 1), all)?.kind, "not the course on its other side")
    }

    /** c1's walkway: 29,17 is a plank with a door to the north and only water to the south. */
    @Test
    fun `a hut a plank draws over water is a house on the map`() = runTest {
        val map = WorldMap((1..6).map { Play.files().read("cont$it.fic") })
        map.see(Cheats.Position(29, 17, 3, 1))
        map.see(Cheats.Position(29, 17, 1, 1))
        assertEquals(WorldMap.CellClass.HOUSE.rgb, map.colour(29, 18))
        assertEquals(WorldMap.CellClass.DOOR.rgb, map.colour(29, 16))
        assertEquals(WorldMap.CellClass.WATER.rgb, map.colour(28, 18), "water beside it")
    }

    /** Cave 12's mouth is 27,37 on grid 2: from inside, the way out at 27,38.. is still the outdoors. */
    @Test
    fun `the outdoors seen from a cave mouth stays outdoors`() = runTest {
        val map = WorldMap((1..6).map { Play.files().read("cont$it.fic") })
        map.see(Cheats.Position(27, 37, 3, 12))
        val x = WorldMap.WIDTH + 27
        assertEquals(WorldMap.CellClass.CAVE_MOUTH.rgb, map.colour(x, 37))
        assertEquals(WorldMap.CellClass.GROUND.rgb, map.colour(x, 38), "the cell that leaves the cave")
        assertEquals(WorldMap.CellClass.GROUND.rgb, map.colour(x, 39))
        assertEquals(WorldMap.CellClass.GROUND.rgb, map.colour(x, 40))
        map.see(Cheats.Position(27, 36, 1, 12))
        assertEquals(WorldMap.CellClass.CAVE_ROCK.rgb, map.colour(x - 1, 36))
        assertNull(map.colour(x - 1, 37), "the forest beside the mouth, behind the rock")
    }

    private suspend fun realGrids() = (1..6).map { Play.files().read("cont$it.fic") }

    /** Sees from every cell of [contree] the party can stand on and [stand] accepts, in every facing. */
    private fun sweep(map: WorldMap, grid: ByteArray, contree: Int, stand: (Int, Int) -> Boolean) {
        for (x in 0 until WorldMap.WIDTH) for (y in 0 until WorldMap.ROWS) {
            if ((grid[x * WorldMap.ROWS + y].toInt() and 0xFF) >= 0x80 || !stand(x, y)) continue
            for (f in 1..4) map.see(Cheats.Position(x, y, f, contree))
        }
    }

    private fun seenCells(map: WorldMap) = (0 until WorldMap.COLUMNS).flatMap { x ->
        (0 until WorldMap.ROWS).filter { y -> map.colour(x, y) != null }.map { y -> x to y }
    }

    /** Walking the whole outdoors, in every facing, shows nothing of a cave or town. */
    @Test
    fun `no walk outdoors marks a cell of a cave or town`() = runTest {
        val grids = realGrids()
        for (contree in 1..5) {
            val offset = (contree - 1) * WorldMap.WIDTH
            val map = WorldMap(grids)
            sweep(map, grids[contree - 1], contree) { x, y -> map.areaAt(offset + x, y) == WorldMap.Area.OUTDOOR }
            val seen = seenCells(map)
            assertTrue(seen.size > 1000, "c$contree marked only ${seen.size}")
            assertEquals(emptyList(), seen.filter { (x, y) -> map.areaAt(x, y) != WorldMap.Area.OUTDOOR }, "c$contree")
        }
    }

    /** From inside the outdoors shows only through a way out: at most the view's depth past it. */
    @Test
    fun `inside a place the outdoors shows only through its exits`() = runTest {
        val grids = realGrids()
        val exits = mapOf(
            12 to listOf(27 to 38),
            13 to listOf(17 to 73, 32 to 56),
            14 to listOf(25 to 72, 33 to 64),
            15 to listOf(12 to 24, 50 to 49),
        )
        for ((contree, ways) in exits) {
            val offset = (contree % 10 - 1) * WorldMap.WIDTH
            val area = WorldMap.areaOf(contree)
            val map = WorldMap(grids)
            sweep(map, grids[contree % 10 - 1], contree) { x, y -> map.areaAt(offset + x, y) == area }
            val outside = seenCells(map).filter { (x, y) -> map.areaAt(x, y) != area }
            val far = outside.filter { (x, y) ->
                ways.none { (ex, ey) -> kotlin.math.abs(x - offset - ex) <= 6 && kotlin.math.abs(y - ey) <= 6 }
            }
            assertEquals(emptyList(), far, "c$contree")
            if (area == WorldMap.Area.TOWN) {
                assertEquals(ways.map { (ex, ey) -> offset + ex to ey }.toSet(), outside.toSet(), "a town sees out as far as its gates")
            }
        }
    }

    /** The case the review found: c2 27,38 is the way into cave 12, and the floor at 24,32 is deep inside it. */
    @Test
    fun `the way into a cave shows nothing of it until the party is in`() = runTest {
        val map = WorldMap(realGrids())
        val x = WorldMap.WIDTH
        map.see(Cheats.Position(27, 38, 1, 2))
        assertNotNull(map.colour(x + 27, 38), "the cell the party stands on")
        assertNull(map.colour(x + 27, 37), "the mouth")
        assertNull(map.colour(x + 24, 32), "the floor")
        map.see(Cheats.Position(27, 37, 1, 12))
        assertEquals(WorldMap.CellClass.CAVE_MOUTH.rgb, map.colour(x + 27, 37))
    }

    /** What lies past a cave is not marked over it either: that would draw the cave's shape as a hole. */
    @Test
    fun `sight outdoors stops at a place's edge`() {
        val map = WorldMap(blank {
            set(27, 36, 0x9D)
            set(26, 37, 0x9D)
            set(28, 37, 0x9D)
            set(27, 38, 0xE5)
        })
        val x = WorldMap.WIDTH
        map.see(Cheats.Position(23, 37, 2, 2))
        assertNotNull(map.colour(x + 25, 37), "open ground")
        assertNull(map.colour(x + 26, 37), "the rock")
        assertNull(map.colour(x + 27, 37), "the cave")
        assertNull(map.colour(x + 29, 37), "past the cave, and in the view")
    }

    /** c3: the rampart and its gate are the outdoors, the street behind the gate is not. */
    @Test
    fun `a town's wall and gate show from outside, its streets do not`() = runTest {
        val grids = realGrids()
        val map = WorldMap(grids)
        val x = 2 * WorldMap.WIDTH
        map.see(Cheats.Position(15, 73, 2, 3))
        assertEquals(WorldMap.CellClass.TOWN_GATE.rgb, map.colour(x + 17, 73))
        assertNull(map.colour(x + 18, 73), "the street behind it")
        val inside = WorldMap(grids)
        inside.see(Cheats.Position(18, 73, 4, 13))
        assertEquals(WorldMap.CellClass.TOWN_GATE.rgb, inside.colour(x + 17, 73), "the gate from inside")
        assertNull(inside.colour(x + 15, 73), "and not past it")
    }

    /** Sight now reads the area of the cells, so nothing the game places may lie outside its contree's. */
    @Test
    fun `everything placed in a place lies in its area`() = runTest {
        val grids = realGrids()
        val map = WorldMap(grids)
        val m = Starts.skipIntro(Play.files()).machine
        fun wrong(contree: Int, x: Int, y: Int): Boolean {
            val at = WorldMap.mapX(contree, x, y) ?: return false
            return map.areaAt(at, y) != WorldMap.areaOf(contree)
        }
        val creatures = Creatures.all(m).filter { wrong(it.contree, it.x, it.y) }
        val items = GroundItems.all(m).filter { wrong(it.contree, it.x, it.y) }
        assertEquals(emptyList(), creatures)
        assertEquals(emptyList(), items)
    }

    /** A map saved when cells kept the area that saw them still loads, with the right colours. */
    @Test
    fun `cells a cave painted before show right after a restore`() = runTest {
        val grids = (1..6).map { Play.files().read("cont$it.fic") }
        val old = ByteArray(WorldMap.COLUMNS * WorldMap.ROWS)
        val x = WorldMap.WIDTH + 27
        old[x * WorldMap.ROWS + 40] = 3 // CAVE, as the old map stored it
        val data = ArrayList<Byte>()
        var i = 0
        while (i < old.size) {
            var n = 1
            while (n < 255 && i + n < old.size && old[i + n] == old[i]) n++
            data += n.toByte(); data += old[i]; i += n
        }
        val map = WorldMap(grids).apply { restore(data.toByteArray()) }
        assertEquals(WorldMap.CellClass.GROUND.rgb, map.colour(x, 40))
    }

    /** c15 29,24: a door on the east edge of the party's own cell (33h). */
    @Test
    fun `a closed door on the party's cell hides what is past it`() {
        val map = WorldMap(blank { set(29, 24, 0x33) })
        val p = Cheats.Position(29, 24, 2, 15)
        val x = 4 * WorldMap.WIDTH + 29
        map.see(p, mapOf((29 to 24) to true))
        assertNull(map.colour(x + 1, 24), "the cell beyond")
        assertNull(map.colour(x + 3, 24), "further on")
        assertNotNull(map.colour(x - 1, 24), "behind the party")
        map.see(p, mapOf((29 to 24) to false))
        assertNotNull(map.colour(x + 3, 24), "the door opened")
    }

    /** A door ahead (not on the party's cell): closed it stops sight, open the cave goes on behind it. */
    @Test
    fun `sight passes a door ahead only when it is open`() {
        val x = 4 * WorldMap.WIDTH
        val door = 31 to 24
        val p = Cheats.Position(29, 24, 2, 15)
        val map = WorldMap(blank { set(31, 24, 0x33) })
        map.see(p, mapOf(door to true))
        assertNotNull(map.colour(x + 31, 24), "the door itself shows")
        assertNull(map.colour(x + 32, 24), "nothing behind it while closed")

        map.see(p, mapOf(door to false))
        assertNotNull(map.colour(x + 32, 24), "it opened, the party standing still")
        assertNotNull(map.colour(x + 34, 24), "and further on")

        val orc = creature(33, 24, contree = 15)
        assertTrue(map.inView(p, listOf(orc)).isNotEmpty(), "who stands behind an open door is in view")
        map.see(p, mapOf(door to true))
        assertTrue(map.inView(p, listOf(orc)).isEmpty(), "and not behind a closed one")
    }

    /** Door codes that are not in the door table stay walls; and the table is by the party's own grid, not another's same cell. */
    @Test
    fun `only the table's open doors let sight through`() {
        val x = 4 * WorldMap.WIDTH
        val map = WorldMap(blank { set(31, 24, 0x33) })
        map.see(Cheats.Position(29, 24, 2, 15), mapOf((40 to 40) to false))
        assertNull(map.colour(x + 32, 24), "no door in the table here")
    }

    /** The door table on a new game, against the notes' list: 2 at 29,24 closed; 25 at 34,11 open. */
    @Test
    fun `the door table reads the chani's cells`() = runTest {
        val m = Starts.skipIntro(Play.files()).machine
        val c15 = Doors.of(m, 15)
        assertEquals(true, c15[29 to 24])
        assertEquals(true, c15[16 to 24])
        assertEquals(true, Doors.of(m, 12)[33 to 20])
        assertEquals(emptyMap(), Doors.of(m, 1))
    }

    /** affobj.io 0123 takes only items of the loaded contree: across a seam the view draws none, though the cell shows. */
    @Test
    fun `an item across a seam is not marked until the party crosses`() {
        val map = WorldMap(blank { // cave 12's mouth walled off, or a blank grid 2 is all cave
            set(27, 36, 0x9D)
            set(26, 37, 0x9D)
            set(28, 37, 0x9D)
            set(27, 38, 0xE5)
        })
        val sack = GroundItems.Item(41, 1, 20, 2)
        val west = Cheats.Position(52, 20, 2, 1)
        map.see(west)
        assertNotNull(map.colour(WorldMap.WIDTH + 1, 20), "the cell itself shows")
        map.spotItems(west, listOf(sack), ZONE_OUTSIDE)
        assertEquals(emptyList(), map.knownItems(listOf(sack)))

        map.spotItems(Cheats.Position(0, 20, 2, 2), listOf(sack), ZONE_OUTSIDE)
        assertEquals(listOf(sack), map.knownItems(listOf(sack)), "in its own contree")
        map.spotItems(west, listOf(sack), ZONE_OUTSIDE)
        assertEquals(listOf(sack), map.knownItems(listOf(sack)), "looking back from across the seam forgets nothing")
    }

    /** Every affobj.io walk stops at 6eh (0110, 018e): codes above it have nothing to draw them, in any zone. */
    @Test
    fun `only codes the game's loops reach are drawn`() {
        assertTrue(GroundItems.drawn(110, ZONE_OUTSIDE), "the last one")
        assertTrue(GroundItems.drawn(94, ZONE_OUTSIDE), "past the zone-bound ones")
        for (code in 111 until GroundItems.COUNT) {
            assertTrue(!GroundItems.drawn(code, ZONE_OUTSIDE), "$code")
            assertTrue(!GroundItems.drawn(code, 30), "$code in a cave zone")
        }
        val map = WorldMap(blank())
        val p = Cheats.Position(10, 20, 3, 1)
        val beyond = GroundItems.Item(111, 10, 22, 1)
        map.spotItems(p, listOf(beyond), ZONE_OUTSIDE)
        assertEquals(emptyList(), map.knownItems(listOf(beyond)))
    }

    /** c15 29,17 in zone 31: chest 86 at 30,17 is in the table but the game draws no such item here. */
    @Test
    fun `an item the game does not draw in this zone is not marked`() {
        val map = WorldMap(blank())
        val p = Cheats.Position(29, 17, 2, 15)
        val chest = GroundItems.Item(86, 30, 17, 15)
        map.spotItems(p, listOf(chest), 31)
        assertEquals(emptyList(), map.knownItems(listOf(chest)))
        map.spotItems(p, listOf(chest), 30)
        assertEquals(listOf(chest), map.knownItems(listOf(chest)))
        map.spotItems(p, listOf(chest), 31)
        assertEquals(emptyList(), map.knownItems(listOf(chest)), "an old mark goes once it is looked at")
        val key = GroundItems.Item(2, 30, 17, 15)
        map.spotItems(p, listOf(key), 31)
        assertEquals(listOf(key), map.knownItems(listOf(key)), "keys are drawn everywhere")
    }

    /** A cut-off file beside the save: a map from before the creatures is fine, a broken one forgets all. */
    @Test
    fun `a broken tail forgets the map`() {
        val map = WorldMap(blank()).apply { see(Cheats.Position(10, 20, 2, 1)) }
        val data = map.encode()
        val back = WorldMap(blank()).apply { restore(data.copyOf(data.size - 1)) }
        assertNull(back.seenBounds(0))
    }

    @Test
    fun `nowhere on a map is not marked`() {
        val map = WorldMap(blank())
        map.see(Cheats.Position(10, 20, 1, 0))
        map.see(Cheats.Position(200, 20, 1, 1))
        assertNull(map.seenBounds(0))
    }

    @Test
    fun `what was seen survives encode and restore and a null forgets it`() {
        val map = WorldMap(blank())
        map.see(Cheats.Position(10, 20, 2, 13))
        val data = map.encode()
        assertTrue(data.size < 2000, "${data.size} bytes")
        val back = WorldMap(blank()).apply { restore(data) }
        assertEquals(map.seenBounds(100 + 0), back.seenBounds(100 + 0))
        assertEquals(map.colour(2 * WorldMap.WIDTH + 11, 20), back.colour(2 * WorldMap.WIDTH + 11, 20))
        back.restore(null)
        assertNull(back.seenBounds(0))
        back.restore(ByteArray(4))
        assertNull(back.seenBounds(0))
    }

    private companion object {
        const val ZONE_OUTSIDE = 0xFF
    }
}
