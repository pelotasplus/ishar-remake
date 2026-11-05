package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepository
import dev.pelotasplus.ishar.data.store.SaveStore

/**
 * Synchronous virtual file system for the engine with case-insensitive DOS file
 * names and an in-memory write overlay. With a [store], `NAME.SAV` files live there instead:
 * the player's saves, read before the bundled ones of the same name.
 */
class GameFiles(
    initialFiles: Map<String, ByteArray> = emptyMap(),
    private val store: SaveStore? = null,
    /** A save of the store that the game can open; Ishar 2's own are another size than Ishar 1's. */
    private val saveSize: Int? = SaveGames.SIZE,
    /** Other names that are the player's saves (Arborea's slots S1..S4.FIC), kept in the [store] as `NAME.SAV` is. */
    private val saveNames: Regex? = null,
) {
    private val shippedFiles: Map<String, ByteArray> =
        initialFiles.entries.associate { (k, v) -> normalizeName(k) to v }
    private val overlayWrites = mutableMapOf<String, ByteArray>()
    private val deletedNames = mutableSetOf<String>()

    /** The game opened this `NAME.SAV` to load it. */
    var onSaveLoaded: ((String) -> Unit)? = null

    /** Ishar 3's party import: the game whose saves the next opens read ([GameSaveStore.importFrom]). */
    fun importFrom(game: Int?) {
        store?.importFrom(game)
    }

    private fun stored(b: String) = store?.takeIf { b.endsWith(".sav") || saveNames?.matches(b) == true }

    /** The player's save [b]; a damaged one is not there, so the game says FILE NOT FOUND and saving over it still works. */
    private fun storedSave(b: String) = stored(b)?.let { s -> runCatching { s.read(b) }.getOrNull() }?.takeIf { if (saveSize == null) it.isNotEmpty() else it.size == saveSize }

    fun exists(name: String): Boolean {
        val b = normalizeName(name)
        if (b in deletedNames) return false
        if (storedSave(b) != null) return true
        return b in overlayWrites || b in shippedFiles
    }

    fun read(name: String): ByteArray {
        val b = normalizeName(name)
        if (b in deletedNames) {
            throw NoSuchElementException("File was deleted: $name")
        }
        overlayWrites[b]?.let { return it.copyOf() }
        storedSave(b)?.let { return it }
        shippedFiles[b]?.let { return it }
        throw NoSuchElementException("File not found in GameFiles: $name")
    }

    fun write(name: String, data: ByteArray) {
        val b = normalizeName(name)
        deletedNames.remove(b)
        val s = stored(b)
        if (s != null) s.write(b, data) else overlayWrites[b] = data.copyOf()
    }

    fun delete(name: String): Boolean {
        if (!exists(name)) return false
        val b = normalizeName(name)
        overlayWrites.remove(b)
        stored(b)?.delete(b)
        deletedNames.add(b)
        return true
    }

    fun overlayFiles(): Map<String, ByteArray> = overlayWrites.toMap()

    /** The shipped files (and the store) without this instance's writes and deletes. */
    fun fresh(): GameFiles = GameFiles(shippedFiles, store, saveSize, saveNames)

    companion object {
        /** Crystals of Arborea's save slots (main.io's fopen 'S1.FIC'..'S4.FIC'). */
        val ARBOREA_SAVES = Regex("s[1-4]\\.fic")

        fun normalizeName(name: String): String =
            name.replace('\\', '/').substringAfterLast('/').lowercase()

        suspend fun fromResourceRepository(
            repo: ResourceRepository,
            includeAllIo: Boolean = true,
            store: SaveStore? = null,
            saveSize: Int? = SaveGames.SIZE,
            saveNames: Regex? = null,
        ): GameFiles {
            val map = LinkedHashMap<String, ByteArray>()
            val names = if (includeAllIo) {
                repo.allFiles
            } else {
                repo.programFiles + repo.ficFiles + listOf("blancpc.io", "main.io").filter { it in repo.ioFiles }
            }
            for (fileName in names) {
                map[normalizeName(fileName)] = repo.readBytes(fileName).getOrElse { e ->
                    throw IllegalStateException("Cannot read $fileName: ${e.message ?: e}", e)
                }
            }
            return GameFiles(map, store, saveSize, saveNames)
        }
    }
}
