package dev.pelotasplus.ishar.engine

/**
 * Ishar 2's map of what the party has seen. CONTn.FIC (n = 1..7, the islands) holds two layers of 90
 * by 60 cells, cell X*60+Y (main.io 35ce/35d3 read them into globs 80h and 159ch; gerdep.io 0a5f
 * indexes them). The step check (gerdep.io L0b72) refuses a cell whose layer-0 code is negative
 * (0f14), and the codes -21..-18 and -5 post a scene event instead (0e54). The islands sit side by
 * side, one sheet each.
 */
class IslandMap(
    private val grids: List<ByteArray>,
    private val scenes: List<ByteArray> = emptyList(),
    /** Cells across and down one island (Ishar 2: 90 x 60; Ishar 3: 82 x 57, contN-3.fic). */
    private val WIDTH: Int = 90,
    private val ROWS: Int = 60,
    /** Ishar 3: no door or lever states mapped yet. */
    private val ishar3: Boolean = false,
) : PartyMap {
    private val ISLANDS = grids.size
    private val COLUMNS = ISLANDS * WIDTH

    private val seen = ByteArray(COLUMNS * ROWS)
    private var last: Cheats.Position? = null

    /** The cells of the last [see], X * ROWS + Y across the sheets. */
    private val inView = HashSet<Int>()


    private val visited = LinkedHashSet<Buildings.Building>()

    /** Doors gone through, as in Ishar 1: taverns and shops ([enter]) and scene doors ([enterScene]). */
    override val visitedBuildings: List<Buildings.Building> get() = visited.toList()

    /**
     * A scene started ([SCENE_SCRIPTS]) with the party before a scene door (layer-0 code -22..-18 or -5):
     * the door gets the scene its layer-1 code names (gerdep.io 0ea4 posts event 11h with it; main.io
     * 2a14 picks the script).
     */
    fun enterScene(p: Cheats.Position) {
        val (dx, dy) = FACING.getOrNull(p.facing - 1) ?: return
        val x = p.x + dx
        val y = p.y + dy
        if (sheetX(p.contree, x, y) == null) return
        val i = x * ROWS + y
        val grid = grids.getOrNull(p.contree - 1) ?: return
        val layer1 = scenes.getOrNull(p.contree - 1) ?: return
        val code = layer1[i].toInt() and 0xFF
        when {
            code == 0x82 || code == 0x83 -> visited += Buildings.Building(Buildings.Kind.BOAT, x, y, p.contree) // -126/-125: the boat (gerdep.io 0ed5)
            grid[i] in SCENES -> visited += Buildings.Building(sceneKind(code), x, y, p.contree)
        }
    }

    /** The party went in at the door ahead of [p]: main.io loads [kind]'s interior (taverne.io 2d68, marchand.io 2dc0). */
    fun enter(p: Cheats.Position, kind: Buildings.Kind) {
        val (dx, dy) = FACING.getOrNull(p.facing - 1) ?: return
        if (sheetX(p.contree, p.x + dx, p.y + dy) == null) return
        visited += Buildings.Building(kind, p.x + dx, p.y + dy, p.contree)
    }

    /**
     * What was last seen of each person, by zone and record, kept on the map as Ishar 1 keeps them: a
     * creature has no contree, only a zone (449a), and the town's zones hand their records to the next
     * district's people, so the live record cannot say where someone seen earlier still stands.
     * A snapshot goes when its cell is in view without that person, or when that person dies.
     */
    private val seenPeople = LinkedHashMap<Int, Creatures.Creature>()

    override fun spot(p: Cheats.Position, creatures: List<Creatures.Creature>) {
        val here = HashSet<Int>()
        for (c in creatures) {
            if (c.contree == 0 || c.inParty) continue
            val key = c.zone * 256 + c.index
            if (c.life <= 0) { seenPeople.remove(key); continue }
            val x = sheetX(c.contree, c.x, c.y) ?: continue
            if (x * ROWS + c.y !in inView) continue
            seenPeople[key] = c
            here += key
        }
        // a remembered spot in view with nobody there, in a zone the game has placed now: they moved on
        // (out of the current zone nobody is placed, so an empty cell says nothing)
        val placed = creatures.filter { it.contree != 0 }.map { it.zone }.toSet()
        seenPeople.entries.removeAll { (key, c) ->
            key !in here && c.zone in placed && sheetX(c.contree, c.x, c.y)?.let { it * ROWS + c.y in inView } == true
        }
    }

    override fun known(creatures: List<Creatures.Creature>) = seenPeople.values.toList()

    override fun see(p: Cheats.Position, doors: Map<Pair<Int, Int>, Boolean>) {
        val sheet = (p.contree - 1) * WIDTH
        val shut = doors.filterValues { it }.keys.filterTo(HashSet()) { (x, y) -> isDoor(sheet + x, y) }
        if (p == last && shut == closed) return
        last = p
        closed = shut
        inView.clear()
        val x0 = sheetX(p) ?: return
        val (dx, dy) = FACING.getOrNull(p.facing - 1) ?: return
        for (x in x0 - 1..x0 + 1) for (y in p.y - 1..p.y + 1) mark(x, y, x0)
        for (depth in 1..VIEW_DEPTH) for (side in -depth..depth) {
            val x = x0 + dx * depth - dy * side
            val y = p.y + dy * depth + dx * side
            if (inSight(x0, p.y, x, y)) mark(x, y, x0)
        }
    }

    /** Layer 1 of cell [x] (across the sheets), [y]: doors, levers, scenes, zones. */
    private fun scene(x: Int, y: Int): Int? = scenes.getOrNull(x / WIDTH)?.get((x % WIDTH) * ROWS + y)?.toInt()?.and(0xFF)

    /**
     * A door (dj1.io 1f76): layer 1 from 61 to 109, the door's number being code - 60 (state glob 4aa4). gerdep.io
     * 0f26 counts it past contree 4 only, on a layer-0 door-edge code (0f33). Elsewhere those layer-1 codes mark
     * places, whole areas of them on Jon's Island, and the party walks through.
     */
    private fun isDoor(x: Int, y: Int) =
        !ishar3 && x / WIDTH >= 4 && code(x, y) in DOOR_EDGES && scene(x, y)?.let { it in 61..109 } == true

    /**
     * A lever (dj1.io 1cde/1d39): layer 1 -11 and below, number |code| - 10 (state glob 4ad8, and pulling it
     * flips door 4aa4 of the same number, pcave.io 046b), on a lever wall: layer 0 of 58..64 or 78/79.
     */
    private fun isLever(x: Int, y: Int, c: Int) = !ishar3 && (c in 58..64 || c == 78 || c == 79) && scene(x, y)?.let { it in 0x80..0xF5 } == true

    /** The party's island's closed doors, by cell, from the live states (glob 4aa4); see [see]. */
    private var closed: Set<Pair<Int, Int>> = emptySet()

    /** The doors of [contree] and whether each is closed, for [see]. */
    fun doors(m: Machine, contree: Int): Map<Pair<Int, Int>, Boolean> {
        if (ishar3) return emptyMap()
        val layer1 = scenes.getOrNull(contree - 1) ?: return emptyMap()
        val out = HashMap<Pair<Int, Int>, Boolean>()
        for (x in 0 until WIDTH) for (y in 0 until ROWS) {
            val c = layer1[x * ROWS + y].toInt() and 0xFF
            if (c in 61..109) out[x to y] = m.rb(m.build.globals, 0x4AA4 + 0x2A0 + c - 60) == 0
        }
        return out
    }

    /** No obstacle between: the end cell itself may be one (a wall is seen, not what is behind it). */
    private fun inSight(x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        val steps = maxOf(kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0))
        for (i in 1 until steps) {
            val x = x0 + ((x1 - x0) * 2 * i + steps).floorDiv(2 * steps)
            val y = y0 + ((y1 - y0) * 2 * i + steps).floorDiv(2 * steps)
            val code = code(x, y) ?: return false
            if (!sameSheet(x0, x) || blocks(code) || (x % WIDTH to y) in closed) return false
        }
        return true
    }

    /** Items affobj.io draws (codes up to 6eh) on a cell the party has seen. */
    override fun knownItems(items: List<GroundItems.Item>) = items.filter { i ->
        i.code <= LAST_DRAWN && sheetX(i.contree, i.x, i.y)?.let { seen[it * ROWS + i.y] != 0.toByte() } == true
    }

    private fun mark(x: Int, y: Int, from: Int) {
        if (x in 0 until COLUMNS && y in 0 until ROWS && sameSheet(from, x)) {
            seen[x * ROWS + y] = 1
            inView += x * ROWS + y
        }
    }

    private fun code(x: Int, y: Int): Int? {
        if (x !in 0 until COLUMNS || y !in 0 until ROWS) return null
        val grid = grids.getOrNull(x / WIDTH) ?: return null
        return grid[(x % WIDTH) * ROWS + y].toInt() and 0xFF
    }

    override fun blocks(x: Int, y: Int): Boolean = code(x, y)?.let(::blocks) ?: false

    /** The step across a door's edge is refused while the door (glob 4aa4) is 0: gerdep.io 0f26, [isDoor]. */
    override fun closedDoor(x: Int, y: Int): Boolean {
        val here = last?.let(::sheetX) ?: return false
        return sameSheet(here, x) && (x % WIDTH to y) in closed
    }

    override fun colour(x: Int, y: Int): Int? {
        if (x !in 0 until COLUMNS || y !in 0 until ROWS) return null
        if (seen[x * ROWS + y] == 0.toByte()) return null
        val c = code(x, y) ?: return null
        if (ishar3) return when {
            c == 0 -> null // outside the island
            blocks(c) -> OBSTACLE
            else -> GROUND
        }
        return when {
            c == 0 -> GROUND
            isDoor(x, y) -> DOOR
            isLever(x, y, c) -> LEVER
            c.toByte() in SCENES -> SCENE
            c in BUILDINGS -> BUILDING
            blocks(c) -> OBSTACLE
            c in 90..93 -> PIER
            c in 30..33 -> GROUND_DARK
            c in 11..12 -> GROUND_LIGHT
            else -> GROUND
        }
    }

    override fun seenBounds(x: Int): Pair<IntRange, IntRange>? {
        val sheet = x / WIDTH
        if (sheet !in 0 until ISLANDS) return null
        var xs: IntRange? = null
        var y0 = ROWS
        var y1 = -1
        for (cx in sheet * WIDTH until (sheet + 1) * WIDTH) for (cy in 0 until ROWS) if (seen[cx * ROWS + cy] != 0.toByte()) {
            xs = (xs?.first ?: cx)..cx
            if (cy < y0) y0 = cy
            if (cy > y1) y1 = cy
        }
        return xs?.let { it to y0..y1 }
    }

    override fun sheetX(p: Cheats.Position): Int? = sheetX(p.contree, p.x, p.y)

    override fun sheetX(contree: Int, x: Int, y: Int): Int? =
        if (contree in 1..ISLANDS && x in 0 until WIDTH && y in 0 until ROWS) (contree - 1) * WIDTH + x else null

    override fun sameSheet(a: Int, b: Int) = a / WIDTH == b / WIDTH

    override fun onMap(x: Int, y: Int) = x in 0 until COLUMNS && y in 0 until ROWS

    /** Run-length pairs (count, value), as [WorldMap.encode]'s cells, then the visited doors (kind, X, Y, contree). */
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
        for (b in visited) {
            out += b.kind.ordinal.toByte()
            out += b.x.toByte()
            out += b.y.toByte()
            out += b.contree.toByte()
        }
        for (c in seenPeople.values) {
            for (v in listOf(c.zone, c.index, c.x, c.y, c.contree, c.mode, c.life shr 8, c.life)) out += v.toByte()
        }
        out += PEOPLE[0]
        out += PEOPLE[1]
        out += (seenPeople.size shr 8).toByte()
        out += seenPeople.size.toByte()
        return out.toByteArray()
    }

    override fun restore(data: ByteArray?) {
        seen.fill(0)
        last = null
        inView.clear()
        seenPeople.clear()
        visited.clear()
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
        // the people seen: 8 bytes each, then "PE" and their count (maps from before them end in a building,
        // whose kind byte is never 50h)
        var end = data.size
        if (end - i >= 4 && data[end - 4] == PEOPLE[0] && data[end - 3] == PEOPLE[1]) {
            val n = (data[end - 2].toInt() and 0xFF) shl 8 or (data[end - 1].toInt() and 0xFF)
            end -= 4 + 8 * n
            if (end < i) return seen.fill(0)
            for (k in end until end + 8 * n step 8) {
                fun u(o: Int) = data[k + o].toInt() and 0xFF
                val c = Creatures.Creature(
                    index = u(1), name = "", x = u(2), y = u(3), contree = u(4),
                    life = (u(6) shl 8) or u(7), mode = data[k + 5].toInt(), inParty = false, zone = u(0),
                )
                seenPeople[c.zone * 256 + c.index] = c
            }
        }
        if (at != seen.size || (end - i) % 4 != 0) return seen.fill(0).also { seenPeople.clear() }
        for (k in i until end step 4) {
            val kind = Buildings.Kind.entries.getOrNull(data[k].toInt()) ?: continue
            visited += Buildings.Building(kind, data[k + 1].toInt() and 0xFF, data[k + 2].toInt() and 0xFF, data[k + 3].toInt() and 0xFF)
        }
    }

    companion object {
        private val PEOPLE = "PE".encodeToByteArray()
        private const val ISHAR2_WIDTH = 90
        private const val ISHAR2_ROWS = 60
        private const val ISHAR2_ISLANDS = 7
        private const val VIEW_DEPTH = 6

        /** The last item code affobj.io's walks reach (00ec). */
        private const val LAST_DRAWN = 0x6E
        private val FACING = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0)

        /** Layer-0 codes that post a scene (gerdep.io 0e54): doors, gates. */
        private val SCENES = setOf<Byte>(-22, -21, -20, -19, -18, -5)

        /** One code per building, one to three cells each (-17..-10; the stone tower on Irvan's Island at 21,19 is -10). */
        private val BUILDINGS = 0xEF..0xF6

        /** The layer-0 codes gerdep.io 0f33 checks a door edge on. */
        private val DOOR_EDGES = ((38..45) + (54..57) + (70..77) + 84).toSet()

        private const val GROUND = 0xC8C08C
        private const val GROUND_LIGHT = 0xDCD4A0
        private const val GROUND_DARK = 0x8CA064
        private const val OBSTACLE = 0x463C32
        private const val SCENE = 0xFF3C8C

        /** Ishar 1's cave door and lever colours (WorldMap.CellClass). */
        private const val DOOR = 0xFF8C00
        private const val LEVER = 0xFFE600
        private const val BUILDING = 0xEB781E

        /** The jetty's planks by the boat (codes 90..93, Irvan's Island 1..4,53). */
        private const val PIER = 0xA06E3C

        /** gerdep.io 0f14: a negative code refuses the step. */
        fun blocks(code: Int) = code >= 0x80

        /** The scripts main.io 2a14 loads for a scene door's event. */
        val SCENE_SCRIPTS = setOf("pp11.io", "pp12.io", "pp2i.io", "pp3mn.io", "pp3ban.io", "pp3tb.io", "ppsac.io", "ppechap.io", "pptravel.io")

        /** The scene a door's layer-1 code starts (main.io 2a14): pp11/pp12 the manor, pp3mn Olbar, pp2i Zeldy. */
        fun sceneKind(code: Int): Buildings.Kind = when (code) {
            0xCE, 0x33, 0x34 -> Buildings.Kind.GORDBNOEUF
            0xC8, 0xC9 -> Buildings.Kind.OLBAR
            in 0xC4..0xC6 -> Buildings.Kind.ZELDY
            else -> Buildings.Kind.SCENE
        }

        /** The two layers of each island: CONTn.FIC's first and second 5,400 bytes. */
        fun fromFiles(files: GameFiles): IslandMap {
            val files7 = (1..ISHAR2_ISLANDS).map { files.read("cont$it.fic") }
            val n = ISHAR2_WIDTH * ISHAR2_ROWS
            return IslandMap(files7.map { it.copyOf(n) }, files7.map { it.copyOfRange(n, 2 * n) })
        }

        /** Ishar 3: contN-3.fic (n = 1..6), two layers of 82 x 57, cell X*57+Y (main.io 42dd reads them into 260h, 14aeh). */
        fun fromIshar3Files(files: GameFiles): IslandMap {
            val w = 82
            val h = 57
            val n = w * h
            val all = (1..6).map { files.read("cont$it-3.fic") }
            return IslandMap(all.map { it.copyOf(n) }, all.map { it.copyOfRange(n, 2 * n) }, w, h, ishar3 = true)
        }
    }
}
