package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every recorded pair run from snapshot A with the renderer left out, against
 * the reference engine doing the same: the CRC of each statement's
 * `op, SI, DS, DX, CX, BP` up to the first statement not installed yet
 * (left out: palette).
 */
class PairTraceTest {

    private class Trace(val frames: Int, val statements: Long, val crc: Long, val stop: Int)

    private class Stop(val op: Int) : RuntimeException()

    private val expected = mapOf(
        "a" to Trace(20, 16307, 0x93F5FAF5L, -1),
        "w" to Trace(30, 37494, 0x0B7D8BB5L, -1),
        "c" to Trace(40, 33176, 0x88B3646CL, -1),
        "t" to Trace(1, 691, 0xE71BDEF8L, 0x68),
        "l" to Trace(40, 6142, 0x5CCE5FC4L, -1),
        "m" to Trace(60, 47101, 0xD0F7F3F6L, -1),
        "s" to Trace(40, 30823, 0xA4D88C68L, -1),
        "i" to Trace(40, 722, 0x2208B832L, -1),
        "k" to Trace(1, 28, 0x52369696L, 0xd7),
        "v" to Trace(40, 30104, 0xE9D7A9ECL, -1),
        "n" to Trace(40, 29329, 0x1347385AL, -1),
        "h" to Trace(40, 29595, 0x2DEFB06DL, -1),
        "o" to Trace(30, 37945, 0xF282D597L, -1),
        "mp" to Trace(30, 44631, 0x0C7068FBL, -1),
        "kt" to Trace(30, 44259, 0x860AFED2L, -1),
        "fk" to Trace(40, 29975, 0x9A1353A6L, -1),
        "am" to Trace(60, 7083, 0x16C6E7FCL, -1),
        "mx" to Trace(40, 30132, 0xED2166F2L, -1),
        "rc" to Trace(30, 21880, 0xD2F97FC2L, -1),
        "nm" to Trace(40, 26663, 0xD6EFE7B8L, -1),
        "is" to Trace(30, 20107, 0x01BA08C3L, -1),
        "ie" to Trace(40, 26260, 0xC586C290L, -1),
        "ix" to Trace(30, 21897, 0xE6EC1B1FL, -1),
        "vd" to Trace(40, 33068, 0x39C7BB87L, -1),
        "pm" to Trace(40, 29697, 0xD822BD77L, -1),
        "fm" to Trace(30, 22456, 0xA8CE1230L, -1),
        "fd" to Trace(20, 14911, 0xFB54E6B9L, -1),
        "fx" to Trace(20, 14639, 0xA4EABBC4L, -1),
        "dk" to Trace(40, 5399, 0xC9F1341DL, -1),
        "dx" to Trace(40, 32600, 0xD13ABE78L, -1),
        "mc" to Trace(30, 24534, 0x66BC501DL, -1),
        "vc" to Trace(40, 32831, 0x97B68D52L, -1),
        "vt" to Trace(60, 42583, 0xE5CC4A29L, -1),
        "ro" to Trace(80, 58661, 0xE762BD88L, -1),
        "te" to Trace(40, 15028, 0x6505269FL, -1),
        "tr" to Trace(2, 818, 0x58470F3FL, 0x68),
        "tq" to Trace(3, 1080, 0xB89791EBL, 0x68),
        "tx" to Trace(3, 1523, 0x21C5E95DL, 0xd7),
        "sb" to Trace(60, 20171, 0xB79EBF32L, -1),
        "sm" to Trace(60, 21411, 0xEB9F452EL, -1),
        "sx" to Trace(60, 21793, 0x872DBEBBL, -1),
        "hc" to Trace(60, 20454, 0x5852F27DL, -1),
        "hy" to Trace(60, 49101, 0x069B92DEL, -1),
        "hqr" to Trace(40, 4556, 0xB87360B4L, -1),
        "h3r" to Trace(2, 876, 0x0DEF2C67L, 0xd7),
        "csr" to Trace(40, 29805, 0x78523B08L, -1),
        "srr" to Trace(60, 43902, 0xF9D006BAL, -1),
        "hlr" to Trace(40, 29324, 0x330C3B0CL, -1),
        "kp" to Trace(30, 25309, 0x44C60A5BL, -1),
        "ks" to Trace(20, 14915, 0x6CB15698L, -1),
        "ka" to Trace(20, 16612, 0x971E5BEBL, -1),
        "pd" to Trace(30, 20071, 0xE317B6F5L, -1),
        "gs" to Trace(30, 19756, 0xD66BC058L, -1),
        "gp" to Trace(40, 27053, 0xADF638AFL, -1),
        "gm" to Trace(40, 26417, 0xDA475E91L, -1),
        "gl" to Trace(30, 21548, 0x5075FB8FL, -1),
        "gx" to Trace(30, 21880, 0x7A6D23B5L, -1),
        "dr" to Trace(30, 22259, 0xBDD5B538L, -1),
        "de" to Trace(30, 22254, 0x4CB19790L, -1),
        "da" to Trace(30, 22402, 0x14741F54L, -1),
        "kl" to Trace(30, 22673, 0x95003C06L, -1),
        "bd" to Trace(30, 19969, 0xB823E4EEL, -1),
        "bu" to Trace(30, 19983, 0xCED106EBL, -1),
        "bn" to Trace(4, 2749, 0xA06768A9L, -1),
        "bnr" to Trace(30, 21803, 0xA4C0A0CEL, -1),
        "sf" to Trace(30, 21823, 0x0310E254L, -1),
        "cm" to Trace(20, 15032, 0xCA064227L, -1),
        "ap" to Trace(20, 15098, 0xC45AC9D0L, -1),
        "wr" to Trace(30, 21954, 0x3F741BAFL, -1),
        "dl" to Trace(3, 2173, 0x74D84E80L, 0x68),
        "rg" to Trace(3, 1729, 0xDB375FEFL, 0xd7),
        "rk" to Trace(3, 8086, 0xF0AC417EL, 0xd7),
        "lv" to Trace(30, 24519, 0x24E68848L, -1),
        "lw" to Trace(30, 24582, 0xD350DC63L, -1),
        "lx" to Trace(30, 24449, 0x256F489DL, -1),
        "ly" to Trace(30, 24519, 0xDF2E1043L, -1),
        "go" to Trace(30, 493, 0xE02E3B11L, 0x68),
        "gk" to Trace(102, 5759, 0x16DD3F1EL, 0xd7),
        "ld" to Trace(7, 2357, 0x20E15AE9L, 0xd7),
        "sw" to Trace(40, 27193, 0xABC17210L, -1),
    )

    private fun installed(vm: Scheduler): Scheduler {
        val stmts = ScriptStmts(vm).also { it.install() }
        Collide(vm, stmts).install()
        FileIo(vm).install()
        return vm
    }

    @Test
    fun `installs the rebuild's statements`() = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        val vm = installed(Scheduler(Machine(), files))
        val ops = (0 until 256).filter { vm.stmts[it] != null }
        assertEquals(REBUILD_OPS.toList(), ops)
    }

    @Test
    fun `statement traces of all pairs match the rebuild`() = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        val scenarios = VmPairs.scenarios()
        assertEquals(80, scenarios.size)
        for (s in scenarios) {
            val want = requireNotNull(expected[s.a]) { s.a }
            val (a, _) = VmPairs.load(s)
            val machine = Machine(a.memory)
            val vm = installed(Scheduler(machine, files.fresh()))
            vm.interrupts = true
            val crc = CRC32()
            val rec = ByteArray(11)
            vm.trace = { op ->
                if (vm.stmts[op] == null) throw Stop(op)
                val words = intArrayOf(machine.si, machine.ds, machine.dx, machine.cx, machine.bp)
                rec[0] = op.toByte()
                words.forEachIndexed { i, w ->
                    rec[1 + 2 * i] = w.toByte()
                    rec[2 + 2 * i] = (w ushr 8).toByte()
                }
                crc.update(rec)
            }
            var stop = -1
            try {
                repeat(s.frames) { vm.frame() }
            } catch (e: Stop) {
                stop = e.op
            }
            val got = "frames=${vm.frames} statements=${vm.statements} crc=${crc.value.toString(16)} stop=$stop"
            val exp = "frames=${want.frames} statements=${want.statements} crc=${want.crc.toString(16)} stop=${want.stop}"
            assertEquals(exp, got, "pair ${s.a} -> ${s.b}")
        }
    }

    companion object {
        private val REBUILD_OPS = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C,
            0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0x18,
            0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F, 0x20, 0x21, 0x22, 0x23, 0x24,
            0x25, 0x26, 0x27, 0x28, 0x29, 0x2A, 0x2B, 0x2C, 0x2D, 0x2E, 0x2F, 0x30,
            0x31, 0x32, 0x33, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3A, 0x3B, 0x3C,
            0x3D, 0x3E, 0x3F, 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F, 0x50, 0x51, 0x52, 0x53, 0x54,
            0x55, 0x56, 0x57, 0x58, 0x59, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60,
            0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E,
            0x6F, 0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7A,
            0x7B, 0x7C, 0x7D, 0x7E, 0x7F, 0x80, 0x81, 0x82, 0x83, 0x84, 0x85, 0x86,
            0x87, 0x88, 0x89, 0x8A, 0x8B, 0x8C, 0x8D, 0x8E, 0x8F, 0x90, 0x91, 0x92,
            0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9A, 0x9B, 0x9C, 0x9D, 0x9E,
            0x9F, 0xA0, 0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6, 0xA7, 0xA8, 0xA9, 0xAA,
            0xAB, 0xAC, 0xAD, 0xAE, 0xAF, 0xB0, 0xB1, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6,
            0xB7, 0xB8, 0xB9, 0xBA, 0xBB, 0xBD, 0xBF, 0xC0, 0xC1, 0xC2, 0xC3, 0xC4,
            0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0xCA, 0xCB, 0xCC, 0xCD, 0xCE, 0xCF, 0xD0,
            0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD8, 0xD9, 0xDA, 0xDB, 0xDC, 0xDD,
            0xDE, 0xDF, 0xE0, 0xE1, 0xE2,
        )
    }
}
