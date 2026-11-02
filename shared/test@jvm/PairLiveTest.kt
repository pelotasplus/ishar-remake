package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Phase 4 gate: every recorded pair with the whole engine (`Scheduler.withStatements`)
 * runs its N frames and equals the live snapshot B byte for byte on the memory regions,
 * the E000 page, the A000 screen (the 71 pairs whose B holds one) and the DAC shadow
 * `ss:1143`; the statement count equals the reference engine's.
 */
class PairLiveTest {

    private val statements = mapOf(
        "a" to 16307,
        "w" to 37494,
        "c" to 33176,
        "t" to 23222,
        "l" to 6142,
        "m" to 47101,
        "s" to 30823,
        "i" to 722,
        "k" to 3873,
        "v" to 30104,
        "n" to 29329,
        "h" to 29595,
        "o" to 37945,
        "mp" to 44631,
        "kt" to 44259,
        "fk" to 29975,
        "am" to 7083,
        "mx" to 30132,
        "rc" to 21880,
        "nm" to 26663,
        "is" to 20107,
        "ie" to 26260,
        "ix" to 21897,
        "vd" to 33068,
        "pm" to 29697,
        "fm" to 22456,
        "fd" to 14911,
        "fx" to 14639,
        "dk" to 5399,
        "dx" to 32600,
        "mc" to 24534,
        "vc" to 32831,
        "vt" to 42583,
        "ro" to 58661,
        "te" to 15028,
        "tr" to 22309,
        "tq" to 13986,
        "tx" to 31652,
        "sb" to 20171,
        "sm" to 21411,
        "sx" to 21793,
        "hc" to 20454,
        "hy" to 49101,
        "hqr" to 4556,
        "h3r" to 39281,
        "csr" to 29805,
        "srr" to 43902,
        "hlr" to 29324,
        "kp" to 25309,
        "ks" to 14915,
        "ka" to 16612,
        "pd" to 20071,
        "gs" to 19756,
        "gp" to 27053,
        "gm" to 26417,
        "gl" to 21548,
        "gx" to 21880,
        "dr" to 22259,
        "de" to 22254,
        "da" to 22402,
        "kl" to 22673,
        "bd" to 19969,
        "bu" to 19983,
        "bn" to 2749,
        "bnr" to 21803,
        "sf" to 21823,
        "cm" to 15032,
        "ap" to 15098,
        "wr" to 21954,
        "dl" to 13947,
        "rg" to 3747,
        "rk" to 88717,
        "lv" to 24519,
        "lw" to 24582,
        "lx" to 24449,
        "ly" to 24519,
        "go" to 1229,
        "gk" to 17142,
        "ld" to 24979,
        "sw" to 27193,
    )

    private fun regions(m: Machine): List<Pair<Int, Int>> {
        val es = m.g(0x0BD6)
        val tbl = m.g(0x0BD2)
        val out = mutableListOf((es shl 4) + tbl to 6 * m.g(0x0BDA))
        var bx = 0
        for (i in 0 until 64) {
            val bp = m.rw(es, tbl + bx)
            val (aofs, aseg) = m.far(es, bp - 0x14)
            out += (es shl 4) + bp - 0x34 to 0x34
            out += (es shl 4) + bp to m.rw(aseg, aofs + 0x14)
            bx = m.rw(es, tbl + bx + 4)
            if (bx == 0) break
        }
        return out
    }

    @Test
    fun `all pairs equal the live snapshot B`() = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        val failures = mutableListOf<String>()
        var screens = 0
        for (s in VmPairs.scenarios()) {
            val (a, b) = VmPairs.load(s)
            val machine = Machine(a.memory)
            val vm = Scheduler.withStatements(machine, files.fresh())
            vm.interrupts = true
            val err = runCatching { repeat(s.frames) { vm.frame() } }.exceptionOrNull()
            fun diff(from: Int, size: Int) = (0 until size).count { machine.m[from + it] != b.memory[from + it] }
            val parts = mutableListOf(
                "regions" to regions(machine).sumOf { (p, n) -> diff(p, n) },
                "page" to diff(0xE0000, 64000),
                "dac" to diff((machine.ss shl 4) + 0x1143, 0x300),
            )
            if (b.hasScreen) {
                screens++
                parts += "screen" to diff(0xA0000, 64000)
            }
            val bad = parts.filter { it.second != 0 }
            if (err != null || bad.isNotEmpty() || vm.statements != statements[s.a]?.toLong()) {
                failures += "${s.a}: ${err?.message ?: ""} $bad statements ${vm.statements}/${statements[s.a]}"
            }
        }
        assertEquals(emptyList(), failures)
        assertEquals(71, screens)
    }
}
