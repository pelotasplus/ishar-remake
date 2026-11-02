package dev.pelotasplus.ishar.data.store

import dev.pelotasplus.ishar.engine.Cheats
import dev.pelotasplus.ishar.engine.Play
import dev.pelotasplus.ishar.engine.SaveGames
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertNull

class SaveStoreTest {

    private fun roundTrip(store: SaveStore) {
        val data = ByteArray(5216) { (it * 7).toByte() }
        assertEquals(emptyList(), store.names())
        store.write("lr.sav", data)
        store.write("b.sav", byteArrayOf(1))
        assertEquals(listOf("b.sav", "lr.sav"), store.names())
        assertContentEquals(data, store.read("lr.sav"))
        store.delete("b.sav")
        assertEquals(listOf("lr.sav"), store.names())
        assertNull(store.read("b.sav"))
    }

    @Test
    fun `stores keep what is written`() {
        roundTrip(MemorySaveStore())
        roundTrip(FileSaveStore(Files.createTempDirectory("saves").toFile()))
        val kv = mutableMapOf<String, String>()
        roundTrip(KeyValueSaveStore(kv::get, kv::set, { kv.remove(it) }))
    }

    /** A kill or a full disk during the write must not cost the save that was there. */
    @Test
    fun `a write that fails leaves the old save whole`() {
        val dir = Files.createTempDirectory("saves").toFile()
        val store = FileSaveStore(dir)
        val old = ByteArray(SaveGames.SIZE) { 1 }
        store.write("lr.sav", old)
        assertEquals(listOf("lr.sav"), dir.list()!!.toList(), "nothing left beside it")
        File(dir, "lr.sav.tmp").mkdir() // the new file cannot be written
        assertFailsWith<Exception> { store.write("lr.sav", ByteArray(SaveGames.SIZE) { 2 }) }
        assertContentEquals(old, store.read("lr.sav"))
        assertEquals(listOf("lr.sav"), store.names(), "a leftover temporary file is no save")
    }

    /** What a kill during the old writes left behind: the prompt answers FILE NOT FOUND, saving over it works. */
    @Test
    fun `a damaged save is not there for the game, and can be saved over`() = runTest {
        val store = MemorySaveStore()
        store.write("io.sav", ByteArray(100))
        val files = Play.files(store)
        assertFalse(files.exists("IO.SAV"))
        assertFailsWith<NoSuchElementException> { files.read("IO.SAV") }

        val (vm, next) = Play.loaded(files, "LR")
        Play.run(vm, SaveGames.saveEvents("IO", next), next, SaveGames.saveFrames("IO")) { Cheats.freeSaves(vm.machine, true) }
        assertEquals(SaveGames.SIZE, store.read("io.sav")!!.size)
        assertTrue(files.exists("IO.SAV"))
    }

    /** A value the store cannot decode counts as damaged, not as a crash. */
    @Test
    fun `an unreadable save is not there either`() = runTest {
        val kv = mutableMapOf("ishar.saves" to "io.sav", "ishar.save.io.sav" to "zz")
        val files = Play.files(KeyValueSaveStore(kv::get, kv::set, { kv.remove(it) }))
        assertFalse(files.exists("IO.SAV"))
    }

    /** A save made through the disk menu lands in the store, and a new GameFiles over the store reads it back. */
    @Test
    fun `a game save goes to the store`() = runTest {
        val dir = Files.createTempDirectory("saves").toFile()
        val files = Play.files(FileSaveStore(dir))
        val (vm, next) = Play.loaded(files, "LR")
        Play.run(vm, SaveGames.saveEvents("IO", next), next, SaveGames.saveFrames("IO")) { Cheats.freeSaves(vm.machine, true) }
        assertEquals(listOf("io.sav"), dir.list()!!.toList())
        val io = File(dir, "io.sav").readBytes()
        assertEquals(SaveGames.SIZE, io.size)
        assertEquals(SaveGames.header(files.read("LR.SAV")), SaveGames.header(io))
        val again = Play.files(FileSaveStore(dir))
        assertContentEquals(io, again.read("io.sav"))
        assertEquals(emptyMap(), files.overlayFiles())
    }
}
