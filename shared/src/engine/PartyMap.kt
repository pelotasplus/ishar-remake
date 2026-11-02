package dev.pelotasplus.ishar.engine

/**
 * What the party has seen, for the mini map and the full map: Ishar 1's [WorldMap] or Ishar 2's
 * [IslandMap]. Cells are on sheets side by side; X runs across them.
 */
interface PartyMap {
    /** Marks what the party sees from [p] ([doors]: Ishar 1's cave doors). */
    fun see(p: Cheats.Position, doors: Map<Pair<Int, Int>, Boolean> = emptyMap())

    /** The colour of a seen cell (0xRRGGBB), or null while it is unseen. */
    fun colour(x: Int, y: Int): Int?

    /** The party cannot step onto this cell (its code); false off the map. */
    fun blocks(x: Int, y: Int): Boolean

    /** A closed door of the party's contree: the step across its door side is refused, from the other side it passes. */
    fun closedDoor(x: Int, y: Int): Boolean

    /** The seen cells of the sheet with X [x], as X and Y ranges; null when there are none. */
    fun seenBounds(x: Int): Pair<IntRange, IntRange>?

    /** The party's X across the sheets, or null where there is no map. */
    fun sheetX(p: Cheats.Position): Int?

    /** A cell of [contree] at [x], [y] as X across the sheets. */
    fun sheetX(contree: Int, x: Int, y: Int): Int?

    fun sameSheet(a: Int, b: Int): Boolean

    /** The cell is on one of the map's sheets; X across the sheets. */
    fun onMap(x: Int, y: Int): Boolean

    /** Kept beside a save. */
    fun encode(): ByteArray

    /** What [encode] gave; null forgets all. */
    fun restore(data: ByteArray?)

    /** Notes where the creatures in view of [p] stand. */
    fun spot(p: Cheats.Position, creatures: List<Creatures.Creature>) {}

    fun known(creatures: List<Creatures.Creature>): List<Creatures.Creature> = emptyList()

    fun knownItems(items: List<GroundItems.Item>): List<GroundItems.Item> = emptyList()

    val visitedBuildings: List<Buildings.Building> get() = emptyList()
}
