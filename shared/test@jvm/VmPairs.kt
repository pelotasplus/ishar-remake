package dev.pelotasplus.ishar.engine

import java.io.File
import java.util.zip.GZIPInputStream

/** One `data/vm/scenarios.txt` line: N frames from snapshot A should give snapshot B. */
data class VmScenario(
    val a: String,
    val b: String,
    val frames: Int,
    val what: String,
)

/** A frame-trap snapshot laid out as [Machine] memory; [hasScreen] when it holds the A000 screen. */
class VmSnapshot(val memory: ByteArray, val hasScreen: Boolean)

/** The recorded pairs read straight from the repository. */
object VmPairs {
    val root: File by lazy {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "data/vm/scenarios.txt").exists()) d = d.parentFile
        requireNotNull(d) { "data/vm not found above ${File("").absolutePath}" }
    }

    private val dir get() = File(root, "data/vm")

    fun scenarios(): List<VmScenario> =
        File(dir, "scenarios.txt").readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line ->
                val w = line.trim().split(Regex("\\s+"), limit = 4)
                VmScenario(w[0], w[1], w[2].toInt(), w.getOrElse(3) { "" })
            }

    private fun gunzip(f: File): ByteArray = GZIPInputStream(f.inputStream()).use { it.readBytes() }

    fun load(s: VmScenario): Pair<VmSnapshot, VmSnapshot> {
        val pair = File(dir, "pairs/${s.a}-${s.b}.gz")
        if (pair.exists()) return splitPair(gunzip(pair))
        return image(gunzip(File(dir, "${s.a}.bin.gz"))) to image(gunzip(File(dir, "${s.b}.bin.gz")))
    }

    /** Conventional memory, then the E000 page, then (optionally) the A000 screen. */
    private fun image(raw: ByteArray): VmSnapshot {
        val m = ByteArray(Machine.MEMORY_SIZE)
        raw.copyInto(m, 0, 0, 0xA0000)
        raw.copyInto(m, 0xE0000, 0xA0000, 0xA0000 + 64000)
        val scr = (raw.size - (0xA0000 + 64000)).coerceIn(0, 64000)
        raw.copyInto(m, 0xA0000, 0xA0000 + 64000, 0xA0000 + 64000 + scr)
        return VmSnapshot(m, scr > 0)
    }

    /** A then A xor B. */
    private fun splitPair(raw: ByteArray): Pair<VmSnapshot, VmSnapshot> {
        val n = raw.size / 2
        val ra = raw.copyOfRange(0, n)
        val rb = ByteArray(n) { (ra[it].toInt() xor raw[n + it].toInt()).toByte() }
        return image(ra) to image(rb)
    }
}
