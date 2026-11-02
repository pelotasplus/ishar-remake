package dev.pelotasplus.ishar.data.store

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** `~/.ishar-game/saves`, or the `ishar.saves` system property. */
actual fun platformSaveStore(): SaveStore =
    FileSaveStore(File(System.getProperty("ishar.saves") ?: (System.getProperty("user.home") + "/.ishar-game/saves")))

class FileSaveStore(private val dir: File) : SaveStore {
    override fun names() = dir.list()?.filter { it.endsWith(".sav") }?.sorted().orEmpty()

    override fun read(name: String) = File(dir, name).takeIf { it.isFile }?.readBytes()

    override fun write(name: String, data: ByteArray) {
        dir.mkdirs()
        // Beside it, then over it in one step: a kill or a full disk leaves the old save whole
        val temp = File(dir, "$name.tmp")
        FileOutputStream(temp).use {
            it.write(data)
            it.fd.sync()
        }
        Files.move(temp.toPath(), File(dir, name).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}
