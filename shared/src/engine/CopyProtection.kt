package dev.pelotasplus.ishar.engine

/**
 * The manual's word check, taken out of main.io: each place that asks turns its `jz` (14h) into
 * a `jmp` (0ah) to the same target, so the question is never asked. The c2 -> c3 crossing
 * (monstre.io, 17c9), cave 15 and the towns (telep.io, 1994 and 1ab2), and the anti-crack trap on
 * entering Silmatil, which freezes the game when no answer was typed (421c < 3, 244f).
 */
object CopyProtection {
    private val CHECKS = intArrayOf(0x17C9, 0x1994, 0x1AB2, 0x244F)
    private const val JZ = 0x14
    private const val JMP = 0x0A
    private const val MAIN_IO = 0

    /**
     * Ishar 2 asks in its dialogue files instead: each block that
     * loads `olbar.io` (91h) or `shandar.io` (92h) and waits for it becomes a `jmp` (0ah rel16 00h) to the
     * statement after it. Patched in the decoded file as it loads at [at], where the block's bytes match.
     */
    fun removeIshar2(m: Machine, name: String, at: Int) {
        val sites = ISHAR2_SITES[GameFiles.normalizeName(name)] ?: return
        for (ofs in sites) {
            val a = at + ofs
            val checker = m.m[a + 1].toInt() and 0xFF
            if ((m.m[a].toInt() and 0xFF) != 0x45 || checker !in 0x91..0x92 || m.m[a + 2].toInt() != 0) continue
            val rel = (if (checker == 0x91) 0x15 else 0x17) - 4
            byteArrayOf(JMP.toByte(), rel.toByte(), 0, 0).copyInto(m.m, a)
        }
    }

    private val ISHAR2_SITES = mapOf(
        "textine.io" to intArrayOf(0x044B, 0x10C1, 0x1285),
        "textind.io" to intArrayOf(0x0474, 0x10E0, 0x1297),
        "textin.io" to intArrayOf(0x048E, 0x1196, 0x1354),
    )

    /** Patches main.io where it is still unpatched; between frames. */
    fun remove(m: Machine) {
        if (m.build != GameBuild.ISHAR1) return
        for (ofs in CHECKS) {
            val a = Frames.assetByte(m, MAIN_IO, ofs) ?: return
            if (m.m[a].toInt() and 0xFF == JZ) m.m[a] = JMP.toByte()
        }
    }
}
