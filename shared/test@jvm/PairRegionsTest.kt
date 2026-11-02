package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The memory regions the reference engine compares (the frame table, then each frame's
 * header `bp-34h..bp` and locals in list order) after running each recorded pair
 * with the renderer left out, until N frames or the first palette statement:
 * - against the reference engine doing the same, for all 80 pairs;
 * - against the live game's snapshot B, byte for byte, for the pairs whose regions
 *   do not depend on the renderer or the palette statements (53 of 80; the rest
 *   need `view_pass` freeing erased objects and clearing view flags, Phase 4).
 */
class PairRegionsTest {

    private class Regions(val frames: Int, val statements: Long, val crc: Long, val matchesB: Boolean)

    private class Stop : RuntimeException()

    private val expected = mapOf(
        "a" to Regions(20, 16307, 0xCC552EA1L, true),
        "w" to Regions(30, 37494, 0x80EED2E6L, false),
        "c" to Regions(40, 33176, 0xDDB582BBL, true),
        "t" to Regions(1, 691, 0x79CC6C52L, false),
        "l" to Regions(40, 6142, 0x7BBDD42FL, true),
        "m" to Regions(60, 47101, 0x63351FDBL, true),
        "s" to Regions(40, 30823, 0xA635B4C0L, true),
        "i" to Regions(40, 722, 0xC0885576L, true),
        "k" to Regions(1, 28, 0x1DC48734L, false),
        "v" to Regions(40, 30104, 0x1CB76AD4L, true),
        "n" to Regions(40, 29329, 0xCD436989L, true),
        "h" to Regions(40, 29595, 0xA362DCB5L, true),
        "o" to Regions(30, 37945, 0x4443D19BL, false),
        "mp" to Regions(30, 44631, 0x910B6328L, false),
        "kt" to Regions(30, 44259, 0x6AE367A0L, false),
        "fk" to Regions(40, 29975, 0xFC33D43CL, true),
        "am" to Regions(60, 7083, 0x7AF0D3B9L, true),
        "mx" to Regions(40, 30132, 0x25D6DDF5L, false),
        "rc" to Regions(30, 21880, 0x8A9F4193L, true),
        "nm" to Regions(40, 26663, 0x2D16D134L, true),
        "is" to Regions(30, 20107, 0x44B0C181L, true),
        "ie" to Regions(40, 26260, 0x66B46025L, true),
        "ix" to Regions(30, 21897, 0xFAA8274AL, false),
        "vd" to Regions(40, 33068, 0x715D4965L, true),
        "pm" to Regions(40, 29697, 0xD359AE86L, true),
        "fm" to Regions(30, 22456, 0x3B3479EBL, true),
        "fd" to Regions(20, 14911, 0xDFE338AFL, true),
        "fx" to Regions(20, 14639, 0x4D4A8444L, true),
        "dk" to Regions(40, 5399, 0xD2011B6EL, true),
        "dx" to Regions(40, 32600, 0x48B2C495L, true),
        "mc" to Regions(30, 24534, 0x6FE90A9DL, true),
        "vc" to Regions(40, 32831, 0x8E13D962L, true),
        "vt" to Regions(60, 42583, 0x02452791L, true),
        "ro" to Regions(80, 58661, 0xBECAF15FL, false),
        "te" to Regions(40, 15028, 0xB3A10B46L, false),
        "tr" to Regions(2, 818, 0x88567744L, false),
        "tq" to Regions(3, 1080, 0x463AF68BL, false),
        "tx" to Regions(3, 1523, 0x4423709FL, false),
        "sb" to Regions(60, 20171, 0xE310732FL, true),
        "sm" to Regions(60, 21411, 0x3A4EAD0BL, true),
        "sx" to Regions(60, 21793, 0xE8E781E9L, false),
        "hc" to Regions(60, 20454, 0x15C66598L, false),
        "hy" to Regions(60, 49101, 0x49326E02L, true),
        "hqr" to Regions(40, 4556, 0xD1052A7CL, true),
        "h3r" to Regions(2, 876, 0x277A24CDL, false),
        "csr" to Regions(40, 29805, 0xE9850648L, true),
        "srr" to Regions(60, 43902, 0xAABFBD14L, true),
        "hlr" to Regions(40, 29324, 0x6B92895EL, true),
        "kp" to Regions(30, 25309, 0x5FBDABEBL, true),
        "ks" to Regions(20, 14915, 0x016B1178L, true),
        "ka" to Regions(20, 16612, 0x8D166F4DL, true),
        "pd" to Regions(30, 20071, 0x36B3A383L, true),
        "gs" to Regions(30, 19756, 0x32A606A1L, true),
        "gp" to Regions(40, 27053, 0x6D52BEE7L, true),
        "gm" to Regions(40, 26417, 0x244133A4L, true),
        "gl" to Regions(30, 21548, 0xBA8B3BB3L, true),
        "gx" to Regions(30, 21880, 0x6341D010L, true),
        "dr" to Regions(30, 22259, 0xB25E9FA7L, true),
        "de" to Regions(30, 22254, 0x6611018BL, true),
        "da" to Regions(30, 22402, 0xFA200DF7L, false),
        "kl" to Regions(30, 22673, 0x02792DA6L, true),
        "bd" to Regions(30, 19969, 0xC58696A6L, false),
        "bu" to Regions(30, 19983, 0x4A1A468CL, false),
        "bn" to Regions(4, 2749, 0x52D760CAL, true),
        "bnr" to Regions(30, 21803, 0x683D0244L, true),
        "sf" to Regions(30, 21823, 0x4F5311DAL, false),
        "cm" to Regions(20, 15032, 0xE681BCEFL, true),
        "ap" to Regions(20, 15098, 0x758BB874L, true),
        "wr" to Regions(30, 21954, 0x43BF7473L, true),
        "dl" to Regions(3, 2173, 0xD9422CF7L, false),
        "rg" to Regions(3, 1729, 0xFF5E08EDL, false),
        "rk" to Regions(3, 8086, 0xE69E3D03L, false),
        "lv" to Regions(30, 24519, 0x40B1B0B5L, true),
        "lw" to Regions(30, 24582, 0xD9C7A610L, true),
        "lx" to Regions(30, 24449, 0xB4D426FEL, true),
        "ly" to Regions(30, 24519, 0x3C9BE70CL, true),
        "go" to Regions(30, 493, 0xECF84B89L, false),
        "gk" to Regions(102, 5759, 0xAC6B5358L, false),
        "ld" to Regions(7, 2357, 0xA8B898F7L, false),
        "sw" to Regions(40, 27193, 0x2ECC52B3L, false),
    )

    /** `check.regions`: (seg, ofs, size) in list order. */
    private fun regions(m: Machine): List<Triple<Int, Int, Int>> {
        val es = m.g(0x0BD6)
        val tbl = m.g(0x0BD2)
        val out = mutableListOf(Triple(es, tbl, 6 * m.g(0x0BDA)))
        var bx = 0
        for (i in 0 until 64) {
            val bp = m.rw(es, tbl + bx)
            val (aofs, aseg) = m.far(es, bp - 0x14)
            out += Triple(es, bp - 0x34, 0x34)
            out += Triple(es, bp, m.rw(aseg, aofs + 0x14))
            bx = m.rw(es, tbl + bx + 4)
            if (bx == 0) break
        }
        return out
    }

    @Test
    fun `memory regions of all pairs match the rebuild and the live game`() = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        var live = 0
        for (s in VmPairs.scenarios()) {
            val want = requireNotNull(expected[s.a]) { s.a }
            val (a, b) = VmPairs.load(s)
            val machine = Machine(a.memory)
            val vm = Scheduler(machine, files.fresh())
            val stmts = ScriptStmts(vm).also { it.install() }
            Collide(vm, stmts).install()
            FileIo(vm).install()
            vm.interrupts = true
            vm.trace = { op -> if (vm.stmts[op] == null) throw Stop() }
            try {
                repeat(s.frames) { vm.frame() }
            } catch (e: Stop) {
                // a palette statement: Phase 4
            }
            val crc = CRC32()
            for ((seg, ofs, size) in regions(machine)) {
                val p = (seg shl 4) + ofs
                crc.update(machine.m, p, size)
            }
            assertEquals(
                "frames=${want.frames} statements=${want.statements} crc=${want.crc.toString(16)}",
                "frames=${vm.frames} statements=${vm.statements} crc=${crc.value.toString(16)}",
                "pair ${s.a} -> ${s.b}",
            )
            if (want.matchesB) {
                live++
                val diffs = regions(machine).sumOf { (seg, ofs, size) ->
                    val p = (seg shl 4) + ofs
                    (0 until size).count { machine.m[p + it] != b.memory[p + it] }
                }
                assertEquals(0, diffs, "pair ${s.a} -> ${s.b}: bytes differing from the live game")
            }
        }
        assertEquals(53, live)
    }
}
