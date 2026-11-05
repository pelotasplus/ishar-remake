package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepository
import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import java.io.File
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The shipped files plus the jump-point saves from the game folder, which the game does not ship:
 * written while the game was taken apart.
 */
class TestResources(private val shipped: ResourceRepository = ResourceRepositoryImpl()) : ResourceRepository by shipped {
    override val allFiles: List<String> get() = shipped.allFiles + saves
    override val programFiles: List<String> get() = shipped.programFiles + saves

    override suspend fun readBytes(fileName: String): Result<ByteArray> =
        if (fileName.uppercase() in saves) runCatching { save(fileName) } else shipped.readBytes(fileName)

    companion object {
        val SAVES = listOf("B.SAV", "G.SAV", "L.SAV", "LR.SAV", "M.SAV", "Q.SAV", "R.SAV", "T.SAV", "Z.SAV")

        /** Ishar 1's game folder, beside one of the folders above this one; null if there is none. */
        private val gameDir: File? by lazy { above("ishar_legend_of_the_fortress_DOSGamer.com/LR.SAV")?.parentFile }

        /** The jump-point saves, when the game folder is there. */
        private val saves get() = if (gameDir != null) SAVES else emptyList()

        /** A save from the game folder; a test that needs one is skipped without it. */
        fun save(name: String): ByteArray {
            needSaves()
            return File(gameDir, name.uppercase()).readBytes()
        }

        /** Skips the calling test when the jump-point saves are not there. */
        fun needSaves() =
            assumeTrue(gameDir != null) { "the game folder (ishar_legend_of_the_fortress_DOSGamer.com) is not above this one: skipped" }

        /** [path] in this folder or the first folder above it that has it. */
        fun above(path: String): File? =
            generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.exists() }
    }
}
