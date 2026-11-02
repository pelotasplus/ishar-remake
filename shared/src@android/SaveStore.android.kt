package dev.pelotasplus.ishar.data.store

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** The app's files dir, set by the activity; saves stay in memory until it is. */
var androidFilesDir: File? = null

actual fun platformSaveStore(): SaveStore =
    androidFilesDir?.let { FileSaveStore(File(it, "saves")) } ?: MemorySaveStore()

class FileSaveStore(private val dir: File) : SaveStore {
    override fun names() = dir.list()?.filter { it.endsWith(".sav") }?.sorted().orEmpty()

    override fun read(name: String) = File(dir, name).takeIf { it.isFile }?.readBytes()

    override fun write(name: String, data: ByteArray) {
        dir.mkdirs()
        // Beside it, then over it in one step (a rename replaces atomically here): a kill or a full disk leaves the old save whole
        val temp = File(dir, "$name.tmp")
        FileOutputStream(temp).use {
            it.write(data)
            it.fd.sync()
        }
        if (!temp.renameTo(File(dir, name))) throw IOException("cannot replace $name")
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}
