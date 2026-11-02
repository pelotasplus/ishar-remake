package dev.pelotasplus.ishar.data.store

/** The NAME.SAV files the player saved, kept between runs. Names are lowercase (`lr.sav`). */
interface SaveStore {
    fun names(): List<String>

    fun read(name: String): ByteArray?

    fun write(name: String, data: ByteArray)

    fun delete(name: String)

    /** Ishar 3's party import: whose saves the next reads open ([GameSaveStore.importFrom]); others ignore it. */
    fun importFrom(game: Int?) {}
}

/** The platform's store: files on desktop and Android, `NSUserDefaults` on iOS, `localStorage` on the web. */
expect fun platformSaveStore(): SaveStore

class MemorySaveStore : SaveStore {
    private val files = mutableMapOf<String, ByteArray>()

    override fun names() = files.keys.sorted()

    override fun read(name: String) = files[name]?.copyOf()

    override fun write(name: String, data: ByteArray) {
        files[name] = data.copyOf()
    }

    override fun delete(name: String) {
        files.remove(name)
    }
}

/** Saves as hex strings in a string key-value store, with an index key listing the names. */
class KeyValueSaveStore(
    private val get: (String) -> String?,
    private val set: (String, String) -> Unit,
    private val remove: (String) -> Unit,
) : SaveStore {
    override fun names(): List<String> = get(INDEX)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()

    override fun read(name: String): ByteArray? = get(PREFIX + name)?.let(::unhex)

    override fun write(name: String, data: ByteArray) {
        set(PREFIX + name, hex(data))
        if (name !in names()) set(INDEX, (names() + name).sorted().joinToString(","))
    }

    override fun delete(name: String) {
        remove(PREFIX + name)
        set(INDEX, (names() - name).joinToString(","))
    }

    private companion object {
        const val INDEX = "ishar.saves"
        const val PREFIX = "ishar.save."
        const val DIGITS = "0123456789abcdef"

        fun hex(b: ByteArray) = buildString(b.size * 2) {
            for (v in b) {
                append(DIGITS[(v.toInt() shr 4) and 0xF])
                append(DIGITS[v.toInt() and 0xF])
            }
        }

        fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}

/**
 * One game's view of the player's saves. Ishar 2 keeps its own under [ISHAR2_PREFIX], so the two games'
 * NAME.SAV never meet; it also reads Ishar 1's (5,216 bytes) when it has none of that name: its disk
 * menu's I -> II button imports an Ishar 1 party from them (io.io L0628).
 */
class GameSaveStore(private val base: SaveStore, private val ishar2: Boolean, private val ishar3: Boolean = false) : SaveStore {
    private val prefix = if (ishar3) ISHAR3_PREFIX else if (ishar2) ISHAR2_PREFIX else ""

    private fun key(name: String) = prefix + name

    override fun names(): List<String> = base.names().let { all ->
        if (prefix.isNotEmpty()) all.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        else all.filterNot { it.startsWith(ISHAR2_PREFIX) || it.startsWith(ISHAR3_PREFIX) }
    }

    /**
     * Ishar 3's party import (io.io, its frame's 4ch: ffh "Ishar 1 to Ishar 3", feh "Ishar 2 to Ishar 3"): the game
     * whose save NAME it opens, set through GameFiles; null for its own saves.
     */
    private var importFrom: Int? = null

    override fun importFrom(game: Int?) {
        importFrom = game
    }

    /** Its own save, else what its party import reads: Ishar 2 opens Ishar 1's, Ishar 3 [importFrom]'s. */
    override fun read(name: String): ByteArray? = when {
        ishar3 && importFrom == 1 -> base.read(name)
        ishar3 && importFrom == 2 -> base.read(ISHAR2_PREFIX + name)
        else -> base.read(key(name)) ?: if (ishar2) base.read(name)?.takeIf { it.size == ISHAR1_SAVE_SIZE } else null
    }

    override fun write(name: String, data: ByteArray) = base.write(key(name), data)

    override fun delete(name: String) = base.delete(key(name))

    companion object {
        const val ISHAR2_PREFIX = "ishar2-"
        const val ISHAR3_PREFIX = "ishar3-"
        const val ISHAR1_SAVE_SIZE = 5216
    }
}
