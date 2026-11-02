package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepository
import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import java.io.File

/**
 * The shipped files plus the jump-point saves from the game folder, which the game does not ship:
 * written while the game was taken apart.
 */
class TestResources(private val shipped: ResourceRepository = ResourceRepositoryImpl()) : ResourceRepository by shipped {
    override val allFiles: List<String> get() = shipped.allFiles + SAVES
    override val programFiles: List<String> get() = shipped.programFiles + SAVES

    override suspend fun readBytes(fileName: String): Result<ByteArray> =
        if (fileName.uppercase() in SAVES) runCatching { save(fileName) } else shipped.readBytes(fileName)

    companion object {
        val SAVES = listOf("B.SAV", "G.SAV", "L.SAV", "LR.SAV", "M.SAV", "Q.SAV", "R.SAV", "T.SAV", "Z.SAV")

        fun save(name: String) = File(VmPairs.root, "ishar_legend_of_the_fortress_DOSGamer.com/${name.uppercase()}").readBytes()
    }
}
