package dev.pelotasplus.ishar.engine

import androidx.compose.runtime.Stable

/**
 * What the party has seen of the world, for the map beside the game. The `CONTn.FIC` grids never
 * change, so a cell keeps only that it was seen; a code means different
 * things outdoors, in a town and in a cave, so [areaAt] picks its colours.
 *
 * Cells have one X: contrees 1..5 tile west to east from 0 (the coasts continue across the seams),
 * the temple map (contree 6) is apart from [TEMPLE_X].
 */
@Stable
class WorldMap(private val grids: List<ByteArray>) : PartyMap {
    enum class Area { OUTDOOR, TOWN, CAVE, TEMPLE }

    private val seen = ByteArray(COLUMNS * ROWS)
    private var last: Cheats.Position? = null

    /** Where each EN1 record was when last in view: contree, X, Y, packed; -1 before that. */
    private val spotted = IntArray(Creatures.COUNT) { -1 }

    /** The same for the ground items, by code. */
    private val spottedItems = IntArray(GroundItems.COUNT) { -1 }

    /** The buildings the party has been inside. */
    private val visited = LinkedHashSet<Buildings.Building>()
    override val visitedBuildings: List<Buildings.Building> get() = visited.toList()

    fun visit(building: Buildings.Building) {
        visited += building
    }

    /** The cave doors of the party's contree: cell and whether it is closed ([Doors]). */
    private var doors: Map<Pair<Int, Int>, Boolean> = emptyMap()

    /** Marks what the party sees from [p]; only a step or a turn can change it. */
    override fun see(p: Cheats.Position, doors: Map<Pair<Int, Int>, Boolean>) {
        if (p == last && doors == this.doors) return
        last = p
        this.doors = doors
        val area = areaOf(p.contree) ?: return
        val x0 = worldX(p) ?: return
        if (p.y !in 0 until ROWS) return
        val (dx, dy) = FACING.getOrNull(p.facing - 1) ?: return
        for (x in x0 - 1..x0 + 1) for (y in p.y - 1..p.y + 1) if (areaAt(x, y) == area && !behindOwnDoor(p, x - x0, y - p.y)) mark(x, y, x0)
        for (depth in 1..VIEW_DEPTH) for (side in -depth..depth) {
            val x = x0 + dx * depth - dy * side
            val y = p.y + dy * depth + dx * side
            if (inSight(x0, p.y, x, y, area) && !behindOwnDoor(p, x - x0, y - p.y)) mark(x, y, x0)
        }
    }

    /** Notes where the creatures in view of [p] stand. */
    override fun spot(p: Cheats.Position, creatures: List<Creatures.Creature>) {
        for (c in inView(p, creatures)) spotted[c.index] = packed(c)
    }

    /** The creatures to draw: alive, not in the party, and still where they were last in view. */
    override fun known(creatures: List<Creatures.Creature>) =
        creatures.filter { !it.inParty && it.life > 0 && spotted[it.index] == packed(it) }

    /**
     * Notes where the ground items in view of [p] lie; one the game does not draw in this [zone] is not there to see.
     * The game draws only the loaded contree's (affobj.io 0123), so from across a seam an item is neither seen nor gone.
     */
    fun spotItems(p: Cheats.Position, items: List<GroundItems.Item>, zone: Int) {
        for (i in items) if (i.contree == p.contree && inView(p, i.contree, i.x, i.y)) {
            spottedItems[i.code] = if (GroundItems.drawn(i.code, zone)) packed(i.contree, i.x, i.y) else -1
        }
    }

    /** The items to draw: still where they were last in view; a taken one has moved. */
    override fun knownItems(items: List<GroundItems.Item>) = items.filter { spottedItems[it.code] == packed(it.contree, it.x, it.y) }

    private fun packed(c: Creatures.Creature) = packed(c.contree, c.x, c.y)

    private fun packed(contree: Int, x: Int, y: Int) = (contree shl 16) or ((x and 0xFF) shl 8) or (y and 0xFF)

    /** Who the party sees right now: placed, alive, in the same kind of place and in sight of [p]. */
    fun inView(p: Cheats.Position, creatures: List<Creatures.Creature>): List<Creatures.Creature> =
        creatures.filter { c -> !c.inParty && c.life > 0 && inView(p, c.contree, c.x, c.y) }

    private fun inView(p: Cheats.Position, contree: Int, cx: Int, cy: Int): Boolean {
        val area = areaOf(p.contree) ?: return false
        val x0 = worldX(p) ?: return false
        val (dx, dy) = FACING.getOrNull(p.facing - 1) ?: return false
        val x = mapX(contree, cx, cy) ?: return false
        if (areaOf(contree) != area || !sameSheet(x0, x)) return false
        val depth = (x - x0) * dx + (cy - p.y) * dy
        val side = kotlin.math.abs((x - x0) * dy - (cy - p.y) * dx)
        if (behindOwnDoor(p, x - x0, cy - p.y)) return false
        val near = kotlin.math.abs(x - x0) <= 1 && kotlin.math.abs(cy - p.y) <= 1
        return near || depth in 1..VIEW_DEPTH && side <= depth && inSight(x0, p.y, x, cy, area)
    }

    /**
     * Standing on a closed door's cell, nothing past its edge shows: the door is on a side of the cell
     * (code - 2eh, mod 4: west, east, north, south), not between two cells.
     */
    private fun behindOwnDoor(p: Cheats.Position, dx: Int, dy: Int): Boolean {
        if (doors[p.x to p.y] != true) return false
        val x0 = worldX(p) ?: return false
        val door = code(x0, p.y) ?: return false
        return when ((door - 0x2E).mod(4)) {
            0 -> dx < 0
            1 -> dx > 0
            2 -> dy < 0
            else -> dy > 0
        }
    }

    /**
     * No opaque cell between the two (a line of cells, the ends left out), and the line stays in [area]:
     * outdoors it stops at the edge of a cave or town; from inside it leaves only through a way out.
     */
    private fun inSight(x0: Int, y0: Int, x1: Int, y1: Int, area: Area): Boolean {
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0))
        var left = areaAt(x0, y0) != area
        for (i in 1..steps) {
            val x = x0 + ((x1 - x0) * 2 * i + steps).floorDiv(2 * steps)
            val y = y0 + ((y1 - y0) * 2 * i + steps).floorDiv(2 * steps)
            val code = code(x, y) ?: return false
            if (areaAt(x, y) != area) {
                if (area == Area.OUTDOOR || !left && !isExit(x, y)) return false
                left = true
            }
            if (i < steps && sameSheet(x0, x) && opaque(code, area) && !openDoor(x0, x, y)) return false
        }
        return true
    }

    /** [doors] holds the party's contree only, by its own X. */
    private fun openDoor(x0: Int, x: Int, y: Int) = x / WIDTH == x0 / WIDTH && doors[x % WIDTH to y] == false

    private fun isExit(x: Int, y: Int) = INNER[x / WIDTH + 1]?.exits?.contains(x % WIDTH to y) == true

    private fun mark(x: Int, y: Int, from: Int) {
        if (x in 0 until COLUMNS && y in 0 until ROWS && sameSheet(from, x)) seen[x * ROWS + y] = 1
    }

    /** The cells of each grid's cave or town ([inner]), worked out on first use. */
    private val insides = arrayOfNulls<BooleanArray>(5)

    /** The kind of place a cell is part of, wherever it is seen from: caves and towns share their grid with the outdoors. */
    fun areaAt(x: Int, y: Int): Area? {
        if (x !in 0 until COLUMNS || y !in 0 until ROWS) return null
        if (x >= TEMPLE_X) return Area.TEMPLE
        val grid = x / WIDTH
        val place = INNER[grid + 1] ?: return Area.OUTDOOR
        val inside = insides[grid] ?: inner(grids[grid], place).also { insides[grid] = it }
        return if (inside[(x % WIDTH) * ROWS + y]) place.area else Area.OUTDOOR
    }

    private fun code(x: Int, y: Int): Int? {
        if (x !in 0 until COLUMNS || y !in 0 until ROWS) return null
        return grids[x / WIDTH][(x % WIDTH) * ROWS + y].toInt() and 0xFF
    }

    /** The colour of a seen cell (0xRRGGBB), or null while it is unseen. */
    override fun colour(x: Int, y: Int): Int? {
        if (x !in 0 until COLUMNS || y !in 0 until ROWS) return null
        if (seen[x * ROWS + y] == 0.toByte()) return null
        val area = areaAt(x, y)!!
        if (area == Area.OUTDOOR && isSceneryHut(x, y)) return CellClass.HOUSE.rgb
        return classOf(x / WIDTH + 1, area, code(x, y)!!).rgb
    }

    /** Cells >= 80h block a step (gerdep.io 11c3), except the gates aeh..b0h, which move the party. */
    override fun blocks(x: Int, y: Int): Boolean = code(x, y)?.let { it >= 0x80 && it !in 0xAE..0xB0 } ?: false

    /** [doors] by the party's contree's own X (gerdep.io 1629: the door edge refuses the step while 3b64 is 0). */
    override fun closedDoor(x: Int, y: Int): Boolean =
        last?.let(::worldX)?.let { it / WIDTH == x / WIDTH } == true && doors[x % WIDTH to y] == true

    /** Water beside a walkway plank (39h): the plank draws a hut on its north and south sides, door or not. */
    private fun isSceneryHut(x: Int, y: Int) = code(x, y) in 0xCD..0xCE && (code(x, y - 1) == 0x39 || code(x, y + 1) == 0x39)

    /** The seen cells of the sheet with X [x], as X and Y ranges; null when there are none. */
    override fun seenBounds(x: Int): Pair<IntRange, IntRange>? {
        val columns = if (x >= TEMPLE_X) TEMPLE_X until COLUMNS else 0 until TEMPLE_X
        var xs: IntRange? = null
        var y0 = ROWS
        var y1 = -1
        for (cx in columns) for (cy in 0 until ROWS) if (seen[cx * ROWS + cy] != 0.toByte()) {
            xs = (xs?.first ?: cx)..cx
            if (cy < y0) y0 = cy
            if (cy > y1) y1 = cy
        }
        return xs?.let { it to y0..y1 }
    }

    override fun sheetX(p: Cheats.Position) = worldX(p)

    override fun sheetX(contree: Int, x: Int, y: Int) = mapX(contree, x, y)

    override fun sameSheet(a: Int, b: Int) = Companion.sameSheet(a, b)

    override fun onMap(x: Int, y: Int) = x in 0 until COLUMNS && y in 0 until ROWS

    /** Run-length pairs (count, value), kept beside a save. */
    override fun encode(): ByteArray {
        val out = ArrayList<Byte>()
        var i = 0
        while (i < seen.size) {
            var n = 1
            while (n < 255 && i + n < seen.size && seen[i + n] == seen[i]) n++
            out += n.toByte()
            out += seen[i]
            i += n
        }
        for (s in spotted + spottedItems) {
            out += (if (s < 0) 0 else s shr 16).toByte()
            out += (if (s < 0) 0 else s shr 8).toByte()
            out += (if (s < 0) 0 else s).toByte()
        }
        for (b in visited) {
            out += b.kind.ordinal.toByte()
            out += b.x.toByte()
            out += b.y.toByte()
            out += b.contree.toByte()
        }
        return out.toByteArray()
    }

    /** What [encode] gave (the runs until every cell is covered, then [spotted], [spottedItems] and [visited]; older maps lack the last two); null (a save from before the map) or a bad length forgets all. */
    override fun restore(data: ByteArray?) {
        seen.fill(0)
        spotted.fill(-1)
        spottedItems.fill(-1)
        visited.clear()
        last = null
        data ?: return
        var at = 0
        var i = 0
        while (at < seen.size && i + 1 < data.size) {
            val n = data[i].toInt() and 0xFF
            if (at + n > seen.size) return seen.fill(0)
            seen.fill(data[i + 1], at, at + n)
            at += n
            i += 2
        }
        if (at != seen.size) return seen.fill(0)
        val tail = data.size - i
        val places = i + 3 * (spotted.size + spottedItems.size)
        if (tail == 0) return // from before the creatures
        if (tail != 3 * spotted.size && (data.size < places || (data.size - places) % 4 != 0)) return seen.fill(0)
        fun read(into: IntArray, from: Int) {
            for (k in into.indices) {
                val contree = data[from + 3 * k].toInt() and 0xFF
                if (contree != 0) into[k] = (contree shl 16) or ((data[from + 3 * k + 1].toInt() and 0xFF) shl 8) or (data[from + 3 * k + 2].toInt() and 0xFF)
            }
        }
        read(spotted, i)
        if (tail == 3 * spotted.size) return
        read(spottedItems, i + 3 * spotted.size)
        for (at in places until data.size step 4) {
            val kind = Buildings.Kind.entries.getOrNull(data[at].toInt()) ?: continue
            visited += Buildings.Building(kind, data[at + 1].toInt() and 0xFF, data[at + 2].toInt() and 0xFF, data[at + 3].toInt() and 0xFF)
        }
    }

    /**
     * The notes' classes, with the world map's colours, but what
     * the party walks through and that marks nothing (flowers, plants, bushes, trees: codes below
     * 80h) is the colour of the ground.
     */
    enum class CellClass(val rgb: Int) {
        GROUND(0xE2D6A0), STREET(0xCDC3AA), CAVE_FLOOR(0x968C7D), TEMPLE_FLOOR(0x785A8C), WATER(0x2D64C8), SHORE(0x78AAE1),
        BRIDGE(0xA06E3C), FLOWERS(0xE2D6A0), PLANTS(0xE2D6A0), BUSH(0xE2D6A0), TREE(0xE2D6A0), FOREST(0x235528),
        LAKE_VILLAGE(0x965F32), HOUSE(0xEB781E), TOWN_WALL(0x786E69), TOWN_GATE(0xFFAF3C), TOWN_HOUSE(0xC89664), BUILDING(0x8C5032),
        DOOR(0xFF3C8C), FOUNTAIN(0x50E6FF), PEDESTAL(0xFFFFA0), GATE(0xE600C8), CAVE_CORRIDOR(0xB4A58C), CAVE_COLUMN(0xD7CD96),
        CAVE_DOOR(0xFF8C00), CAVE_MOUTH(0x00C878), CAVE_ROCK(0x46372D), TEMPLE_PIECE(0xBE6EE6), TEMPLE_WALL(0x5A286E), CAVE_LEVER(0xFFE600),
    }

    companion object {
        const val WIDTH = 54
        const val ROWS = 90
        const val TEMPLE_X = 5 * WIDTH
        const val COLUMNS = 6 * WIDTH

        /** The scene scripts draw this far ahead. */
        private const val VIEW_DEPTH = 6

        /** 1 N (-Y), 2 E (+X), 3 S (+Y), 4 W (-X). */
        private val FACING = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)

        /** A cave or town on a grid: where the party comes in, where it leaves, its gates. */
        private class Inner(val area: Area, val starts: List<Pair<Int, Int>>, val exits: Set<Pair<Int, Int>>, val gates: Map<Pair<Int, Int>, Pair<Int, Int>> = emptyMap())

        private val INNER = mapOf(
            2 to Inner(Area.CAVE, listOf(27 to 37), setOf(27 to 38)),
            3 to Inner(Area.TOWN, listOf(18 to 73, 32 to 57), setOf(17 to 73, 32 to 56)),
            4 to Inner(Area.TOWN, listOf(26 to 72, 33 to 65), setOf(25 to 72, 33 to 64)),
            5 to Inner(Area.CAVE, listOf(13 to 24), setOf(12 to 24, 50 to 49), mapOf((49 to 20) to (36 to 26), (36 to 26) to (49 to 20))),
        )

        /**
         * Every cell the party can reach from the entrances, and the walls around that,
         * but for its shut doors and backyards, which only matter to a place's own insides.
         */
        private fun inner(grid: ByteArray, place: Inner): BooleanArray {
            fun code(p: Pair<Int, Int>) = grid[p.first * ROWS + p.second].toInt() and 0xFF
            fun around(p: Pair<Int, Int>) = FACING.map { (dx, dy) -> p.first + dx to p.second + dy }.filter { it.first in 0 until WIDTH && it.second in 0 until ROWS }
            val inside = BooleanArray(grid.size)
            fun add(p: Pair<Int, Int>) { inside[p.first * ROWS + p.second] = true }
            fun has(p: Pair<Int, Int>) = inside[p.first * ROWS + p.second]
            val floor = place.starts.toMutableList()
            val walls = ArrayList<Pair<Int, Int>>()
            while (floor.isNotEmpty()) {
                val p = floor.removeLast()
                if (has(p)) continue
                add(p)
                for (q in around(p)) {
                    val gate = place.gates[q]
                    val v = code(q)
                    when {
                        q in place.exits -> if (v >= 0x80) add(q)
                        gate != null -> { add(q); floor += gate }
                        v < 0x80 -> floor += q
                        v == 0x9D -> walls += q
                        v in 0xA9..0xAB -> add(q)
                        place.area == Area.TOWN && (v == 0xE6 || v >= 0xE7 || v in 0xD5..0xD8) -> add(q)
                    }
                }
            }
            while (walls.isNotEmpty()) {
                val p = walls.removeLast()
                if (has(p)) continue
                add(p)
                walls += around(p).filter { code(it) == 0x9D }
            }
            return inside
        }

        fun fromFiles(files: GameFiles) = WorldMap((1..6).map { files.read("cont$it.fic") })

        fun areaOf(contree: Int): Area? = when (contree) {
            in 1..5 -> Area.OUTDOOR
            6 -> Area.TEMPLE
            13, 14 -> Area.TOWN
            12, 15 -> Area.CAVE
            else -> null
        }

        /** The party's X on the map, or null when it is nowhere on one. */
        fun worldX(p: Cheats.Position): Int? {
            if (areaOf(p.contree) == null || p.x !in 0 until WIDTH) return null
            return (p.contree % 10 - 1) * WIDTH + p.x
        }

        /** The temple map is apart: nothing is seen or drawn across to it. */
        fun sameSheet(a: Int, b: Int) = (a >= TEMPLE_X) == (b >= TEMPLE_X)

        /** A creature's X on the map, or null when it is not on one. */
        fun creatureX(c: Creatures.Creature): Int? = mapX(c.contree, c.x, c.y)

        /** The X on the map of something at [x], [y] in [contree], or null when it is not on one. */
        fun mapX(contree: Int, x: Int, y: Int): Int? =
            if (areaOf(contree) == null || x !in 0 until WIDTH || y !in 0 until ROWS) null else (contree % 10 - 1) * WIDTH + x

        /** Sight stops here. Water and open ground do not stop it; a cave door does unless it is open ([doors]). */
        fun opaque(code: Int, area: Area): Boolean = when (area) {
            Area.OUTDOOR -> code in 0xDF..0xE2 || code in 0xE5..0xFF || code in 0x64..0x6E
            Area.TOWN -> code != 0x00 && code != 0xE6
            Area.CAVE -> code == 0x9D || code in 0x2E..0x41 || code in 0x52..0x55
            Area.TEMPLE -> code == 0x9D
        }

        fun classOf(grid: Int, area: Area, code: Int): CellClass = when (area) {
            Area.OUTDOOR -> when (code) {
                0x01 -> CellClass.BRIDGE
                0x02, 0x0A -> CellClass.FLOWERS
                in 0x03..0x09 -> CellClass.PLANTS
                in 0x0B..0x10, 0x19, 0x1A -> if (grid == 2 || grid == 5) CellClass.TREE else if (code == 0x0B || code == 0x1A) CellClass.GROUND else CellClass.BUSH
                in 0x11..0x18 -> CellClass.BUSH
                in 0x1E..0x25 -> CellClass.TREE
                in 0x36..0x39 -> CellClass.LAKE_VILLAGE
                in 0x64..0x6B -> CellClass.TOWN_WALL
                0x6D, 0x6E -> CellClass.TOWN_GATE
                in 0xA9..0xAB -> CellClass.PEDESTAL
                in 0xAE..0xB0 -> CellClass.GATE
                0xCC -> CellClass.SHORE
                0xCD, 0xCE -> CellClass.WATER
                in 0xD5..0xD8 -> CellClass.DOOR
                in 0xDF..0xE2, 0xE5, 0xE6 -> CellClass.FOREST
                in 0xE7..0xFF -> CellClass.HOUSE
                else -> CellClass.GROUND
            }
            Area.TOWN -> when (code) {
                0x00 -> CellClass.STREET
                0x9D -> CellClass.BUILDING
                0xE6 -> CellClass.FOUNTAIN
                in 0xD5..0xD8, in 0xE7..0xFF -> CellClass.DOOR
                in 0x64..0x6B -> CellClass.TOWN_WALL
                0x6D, 0x6E -> CellClass.TOWN_GATE
                else -> CellClass.TOWN_HOUSE
            }
            Area.CAVE -> when (code) {
                0x00 -> CellClass.CAVE_FLOOR
                0x9D -> CellClass.CAVE_ROCK
                in 0x22..0x2D -> CellClass.CAVE_COLUMN
                in 0x42..0x45 -> CellClass.CAVE_LEVER
                0x50, 0x51 -> CellClass.CAVE_MOUTH
                in 0x52..0x55 -> CellClass.CAVE_DOOR
                in 0xA9..0xAB -> CellClass.PEDESTAL
                in 0xAE..0xB0 -> CellClass.GATE
                else -> CellClass.CAVE_CORRIDOR
            }
            Area.TEMPLE -> when (code) {
                0x00 -> CellClass.TEMPLE_FLOOR
                0x9D -> CellClass.TEMPLE_WALL
                else -> CellClass.TEMPLE_PIECE
            }
        }
    }
}
