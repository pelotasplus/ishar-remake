package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Session.Event

/**
 * NAME.SAV (5,216 bytes, big-endian words, main.io 480e/4a28) and what the player does to load
 * one: the disk icon, LOAD, the name and Enter, the way s1 loads LR.SAV (frames 2135..2213).
 */
object SaveGames {
    const val SIZE = 5216

    /** The save prompt keeps up to 8 of A..Z and 0..9; another name cannot be typed to load it. */
    fun typeable(name: String) = name.length in 1..8 && name.all { it in 'A'..'Z' || it in '0'..'9' }

    /** The first words of a save: party position and contree. */
    data class Header(val x: Int, val y: Int, val facing: Int, val contree: Int)

    fun header(save: ByteArray): Header {
        fun w(i: Int) = ((save[2 * i].toInt() and 0xFF) shl 8) or (save[2 * i + 1].toInt() and 0xFF)
        return Header(w(1), w(2), w(3), w(4))
    }

    data class Member(val name: String, val level: Int, val life: Int, val maxLife: Int)

    /** What a save browser shows; offsets from the file layout. */
    data class Summary(
        val name: String,
        val x: Int,
        val y: Int,
        val facing: Int,
        val contree: Int,
        val region: Int,
        val party: List<Member>,
        val gold: Int,
    ) {
        val regionName get() = REGIONS.getOrNull(region + 1) ?: "?"
    }

    /** Null when [save] is not a save file. */
    fun summary(name: String, save: ByteArray): Summary? {
        if (save.size != SIZE) return null
        fun b(at: Int) = save[at].toInt() and 0xFF
        fun w(at: Int) = (b(at) shl 8) or b(at + 1)
        val h = header(save)
        val slots = (0 until 5).filter { b(CLASS + it) != 0xFF }
        return Summary(
            name = name,
            x = h.x,
            y = h.y,
            facing = h.facing,
            contree = h.contree,
            region = w(REGION).toShort().toInt(),
            party = slots.map { s ->
                val text = (0 until 8).map { b(NAMES + 8 * s + it) }.takeWhile { it != 0 }
                Member(text.map { it.toChar() }.joinToString("").trim(), b(LEVEL + s), w(LIFE + 2 * s).toShort().toInt(), w(MAX_LIFE + 2 * s))
            },
            gold = slots.sumOf { w(THOUSANDS + 2 * it) * 1000 + w(UNITS + 2 * it) },
        )
    }

    private const val REGION = 32
    private const val NAMES = 418
    private const val LIFE = 468
    private const val THOUSANDS = 478
    private const val MAX_LIFE = 488
    private const val CLASS = 728
    private const val LEVEL = 738
    private const val UNITS = 3993

    /** The kingdoms by region + 1 (gerdep.io 227d). */
    private val REGIONS = listOf(
        "L'OCEAN", "FRAGONIR", "ANGARAHN", "OSGHIROD", "LOTHARIA", "RHUDGAST", "FIMNUIRH", "ARAGARTH", "KANDOMIR",
        "SILMATIL", "URSHURAK", "BALDARON", "VARGAEON", "ZENDORIA", "GIL-ARAS", "HALINDOR", "ULDONYAR", "VALATHAR",
        "ELWINGIL", "FHULGROD", "ISHAR",
    )

    /** The load's input from [start] (the party in play), for [Session.apply]; it takes [loadFrames]. */
    fun loadEvents(name: String, start: Int): Map<Int, List<Event>> =
        diskEvents(start, listOf(MENU_AT to (LOAD_X to LOAD_Y)), TYPE_AT, name)

    /** Frames from the first load event until the loaded game is back in play. */
    fun loadFrames(name: String): Int = TYPE_AT + KEY_GAP * (name.length + 1) + SETTLE

    /**
     * The save's input from [start], as s1 saved LR (frames 1912..2017): the disk, SAVE CURRENT
     * GAME, OK on the 1000 PO fee, the name and Enter. It takes [saveFrames].
     */
    fun saveEvents(name: String, start: Int): Map<Int, List<Event>> =
        diskEvents(start, listOf(MENU_AT to (SAVE_X to SAVE_Y), FEE_AT to (OK_X to OK_Y)), SAVE_TYPE_AT, name)

    fun saveFrames(name: String): Int = SAVE_TYPE_AT + KEY_GAP * (name.length + 1) + SETTLE

    private fun diskEvents(start: Int, clicks: List<Pair<Int, Pair<Int, Int>>>, typeAt: Int, name: String): Map<Int, List<Event>> {
        val e = mutableMapOf<Int, MutableList<Event>>()
        fun at(f: Int, ev: Event) = e.getOrPut(start + f) { mutableListOf() }.add(ev)
        fun click(f: Int, x: Int, y: Int) {
            at(f, Event.Mouse(x, y))
            at(f + 1, Event.Buttons(1))
            at(f + 4, Event.Buttons(0))
        }
        click(0, DISK_X, DISK_Y)
        for ((f, xy) in clicks) click(f, xy.first, xy.second)
        val keys = name.lowercase().map { requireNotNull(Input.scancodeFor(it)) { "no key for $it" } } + Input.ENTER
        keys.forEachIndexed { i, sc ->
            at(typeAt + KEY_GAP * i, Event.KeyDown(sc))
            // sos.io reads the key a frame later (095c), then spins until it is released (0921)
            at(typeAt + KEY_GAP * i + 1, Event.SpinUp(sc))
        }
        return e
    }

    /**
     * Ishar 2's disk menu from [start] (the party in play): the disk icon, LOAD (frise.io 5cb9, io.io
     * 03ac), the name and Enter. io.io waits for each key's release inside a frame (05d3), so each key
     * also queues its release for that spin. It takes [ishar2LoadFrames].
     */
    fun ishar2LoadEvents(name: String, start: Int) = ishar2DiskEvents(name, start, ISHAR2_LOAD_X, ISHAR2_LOAD_Y)

    /** The same for SAVE CURRENT GAME (frise.io 5cb9); it takes [ishar2SaveFrames]. */
    fun ishar2SaveEvents(name: String, start: Int): Map<Int, List<Event>> {
        val e = ishar2DiskEvents(name, start, ISHAR2_SAVE_X, ISHAR2_SAVE_Y).mapValues { it.value.toMutableList() }.toMutableMap()
        // the menu stays open after the save: the disk icon closes it
        val close = start + ISHAR2_TYPE_AT + KEY_GAP * (name.length + 1) + SETTLE
        e.getOrPut(close) { mutableListOf() } += listOf(Event.Mouse(ISHAR2_DISK_X, ISHAR2_DISK_Y), Event.Buttons(1))
        e.getOrPut(close + 4) { mutableListOf() } += Event.Buttons(0)
        return e
    }

    /** Ishar 3's disk menu is laid out as Ishar 2's, and closes itself after a save (io.io). */
    fun ishar3SaveEvents(name: String, start: Int): Map<Int, List<Event>> = ishar2DiskEvents(name, start, ISHAR2_SAVE_X, ISHAR2_SAVE_Y)

    fun ishar2SaveFrames(name: String): Int = ISHAR2_TYPE_AT + KEY_GAP * (name.length + 1) + SETTLE + ISHAR2_CLOSE

    private fun ishar2DiskEvents(name: String, start: Int, x: Int, y: Int): Map<Int, List<Event>> {
        val e = mutableMapOf<Int, MutableList<Event>>()
        fun at(f: Int, ev: Event) = e.getOrPut(start + f) { mutableListOf() }.add(ev)
        fun click(f: Int, x: Int, y: Int) {
            at(f, Event.Mouse(x, y))
            at(f, Event.Buttons(1))
            at(f + 4, Event.Buttons(0))
        }
        click(0, ISHAR2_DISK_X, ISHAR2_DISK_Y)
        click(ISHAR2_MENU_AT, x, y)
        val keys = name.lowercase().map { requireNotNull(Input.scancodeFor(it)) { "no key for $it" } } + Input.ENTER
        keys.forEachIndexed { i, sc ->
            val f = ISHAR2_TYPE_AT + KEY_GAP * i
            at(f, Event.KeyDown(sc))
            at(f, Event.SpinUp(sc))
            at(f + 3, Event.KeyUp(sc))
        }
        return e
    }

    fun ishar2LoadFrames(name: String): Int = ISHAR2_TYPE_AT + KEY_GAP * (name.length + 1) + ISHAR2_SETTLE

    private const val ISHAR2_DISK_X = 266
    private const val ISHAR2_DISK_Y = 12
    private const val ISHAR2_MENU_AT = 29
    private const val ISHAR2_LOAD_X = 237
    private const val ISHAR2_LOAD_Y = 32
    private const val ISHAR2_SAVE_X = 237
    private const val ISHAR2_SAVE_Y = 62
    private const val ISHAR2_CLOSE = 20
    private const val ISHAR2_TYPE_AT = 60

    /** io.io's "loading" message, the restart (event 12h) and the new start (main.io 0966). */
    private const val ISHAR2_SETTLE = 200

    /** Ishar 2's first words: version 2, X, Y, facing, contree (io.io L0ded). */
    fun ishar2Header(save: ByteArray, version: Int = 2): Header? {
        if (save.size < 10) return null
        fun w(i: Int) = ((save[2 * i].toInt() and 0xFF) shl 8) or (save[2 * i + 1].toInt() and 0xFF)
        if (w(0) != version) return null
        // Ishar 3 (io.io 1041): X, Y, facing (1, 2, -1, -2 = N E S W), then its island at word 4
        val facing = if (version == 3) when (w(3) and 0xFF) { 1 -> 1; 2 -> 2; 0xFF -> 3; 0xFE -> 4; else -> 0 } else w(3)
        return Header(w(1), w(2), facing, w(4))
    }

    /** What the Saves screen shows under an Ishar 1 save's name. */
    fun summaryLine(s: Summary) = "${s.regionName}  contree ${s.contree}  X ${s.x}  Y ${s.y}  facing ${"?NESW"[s.facing.coerceIn(0, 4)]}"

    /** What the Saves screen shows under an Ishar 2 or 3 save's name. */
    fun headerLine(build: GameBuild, h: Header?): String {
        if (h == null) return "Cannot be loaded"
        val island = (if (build == GameBuild.ISHAR2) ISHAR2_ISLANDS.getOrNull(h.contree) else null).orEmpty().ifEmpty { "island ${h.contree}" }
        return "$island  X ${h.x}  Y ${h.y}  facing ${"?NESW"[h.facing.coerceIn(0, 4)]}"
    }

    /** The islands by contree (gerdep.io 196b). */
    val ISHAR2_ISLANDS = listOf("", "Irvan's Island", "Thorm's Island", "Zach's Island", "Jon's Island", "Akeer's Island", "Olbar's Island", "")

    private const val DISK_X = 310
    private const val DISK_Y = 58
    private const val LOAD_X = 160
    private const val LOAD_Y = 152
    private const val MENU_AT = 25
    private const val TYPE_AT = 60
    private const val SAVE_X = 160
    private const val SAVE_Y = 142
    private const val FEE_AT = 60
    private const val OK_X = 270
    private const val OK_Y = 157
    private const val SAVE_TYPE_AT = 85
    private const val KEY_GAP = 6
    private const val SETTLE = 60
}
