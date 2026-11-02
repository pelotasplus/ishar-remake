package dev.pelotasplus.ishar.engine

import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `object_project` against the reference engine:
 * every object of every view chain of each pair's snapshot A, then fuzzed view and
 * object fields for the 3D, perspective, depth and snap paths no pair reaches.
 */
class ProjectionTest {

    private class Projected(val objects: Int, val crc: Long)

    private val expected = mapOf(
        "a" to Projected(148, 0x0BB790DBL),
        "w" to Projected(148, 0x0BB790DBL),
        "c" to Projected(180, 0xC1F31705L),
        "t" to Projected(181, 0x0FE9F7C2L),
        "l" to Projected(110, 0x738B5542L),
        "m" to Projected(163, 0x35655CEBL),
        "s" to Projected(221, 0x740D366EL),
        "i" to Projected(14, 0xD403400FL),
        "k" to Projected(16, 0x2D6D4784L),
        "v" to Projected(155, 0x3D40A06CL),
        "n" to Projected(155, 0x3D40A06CL),
        "h" to Projected(154, 0x1E567DD9L),
        "o" to Projected(156, 0x44FE8127L),
        "mp" to Projected(149, 0x2CCC8EE1L),
        "kt" to Projected(153, 0x4A932AA4L),
        "fk" to Projected(163, 0xED6318FAL),
        "am" to Projected(237, 0xCBE5617DL),
        "mx" to Projected(238, 0x45B48179L),
        "rc" to Projected(237, 0x921F3A68L),
        "nm" to Projected(163, 0xB8A2039AL),
        "is" to Projected(260, 0x4D6BA06EL),
        "ie" to Projected(260, 0x01EFD19CL),
        "ix" to Projected(260, 0xD955E2F3L),
        "vd" to Projected(164, 0x942AD24DL),
        "pm" to Projected(164, 0x1A7C5E28L),
        "fm" to Projected(163, 0xB8A2039AL),
        "fd" to Projected(169, 0xC0DDFA61L),
        "fx" to Projected(169, 0x56E37B78L),
        "dk" to Projected(163, 0xB8A2039AL),
        "dx" to Projected(218, 0x06D4145DL),
        "mc" to Projected(156, 0x24AD3010L),
        "vc" to Projected(156, 0x24AD3010L),
        "vt" to Projected(156, 0xEBF68D7AL),
        "ro" to Projected(233, 0xB49A8B18L),
        "te" to Projected(114, 0x6798C1E0L),
        "tr" to Projected(114, 0xC6F23774L),
        "tq" to Projected(147, 0xF2F51577L),
        "tx" to Projected(113, 0x7EC39A37L),
        "sb" to Projected(155, 0x1828E740L),
        "sm" to Projected(156, 0x2D4CECC9L),
        "sx" to Projected(156, 0xE05CD16AL),
        "hc" to Projected(216, 0x35B3E487L),
        "hy" to Projected(140, 0x245542C5L),
        "hqr" to Projected(224, 0x2EBE92E5L),
        "h3r" to Projected(84, 0x3A823F1AL),
        "csr" to Projected(161, 0x33A204A6L),
        "srr" to Projected(152, 0xDAE2CA72L),
        "hlr" to Projected(152, 0xDAE2CA72L),
        "kp" to Projected(150, 0x6765AB16L),
        "ks" to Projected(149, 0x5BF886DDL),
        "ka" to Projected(149, 0x5BF886DDL),
        "pd" to Projected(259, 0xDE583BCCL),
        "gs" to Projected(241, 0xA2829542L),
        "gp" to Projected(241, 0x16155541L),
        "gm" to Projected(242, 0x161FF8A2L),
        "gl" to Projected(141, 0x830B06B2L),
        "gx" to Projected(141, 0x830B06B2L),
        "dr" to Projected(183, 0x396D975DL),
        "de" to Projected(178, 0xBCF5517FL),
        "da" to Projected(178, 0x9A53BDC3L),
        "kl" to Projected(138, 0xB0E7FC7DL),
        "bd" to Projected(237, 0xD2D8DC5EL),
        "bu" to Projected(237, 0x5F6421B8L),
        "bn" to Projected(237, 0x599C21CBL),
        "bnr" to Projected(237, 0x599C21CBL),
        "sf" to Projected(198, 0x1DE3573AL),
        "cm" to Projected(213, 0xD12F4F60L),
        "ap" to Projected(213, 0xD12F4F60L),
        "wr" to Projected(165, 0x955B4199L),
        "dl" to Projected(105, 0x2A146343L),
        "rg" to Projected(120, 0x365505DFL),
        "rk" to Projected(120, 0x2F560FBBL),
        "lv" to Projected(100, 0x63B3C383L),
        "lw" to Projected(100, 0x3B0B817EL),
        "lx" to Projected(98, 0xF0BE674BL),
        "ly" to Projected(98, 0x731294F2L),
        "go" to Projected(0, 0x00000000L),
        "gk" to Projected(1, 0xDED96B99L),
        "ld" to Projected(199, 0x64070434L),
        "sw" to Projected(260, 0x29C3B488L),
    )

    private val fuzzed = mapOf(
        "a" to 0x62FF5257L,
        "is" to 0x8C3EFCDFL,
        "ro" to 0xCF7860A5L,
        "ld" to 0xDD91427FL,
    )

    private fun chain(m: Machine): List<Pair<Int, Int>> {
        val es = m.g(0x0BD6)
        val o = m.g(0x0BEA)
        val out = mutableListOf<Pair<Int, Int>>()
        var cx = m.g(0x0C16)
        while (cx != 0) {
            val bx = (m.g(0x0BF6) + cx) and 0xFFFF
            var di = m.rw(es, bx + 2)
            while (true) {
                di = m.rw(o, di + 6)
                if (di == 0) break
                out += bx to di
            }
            cx = m.rw(es, bx + 4)
        }
        return out
    }

    private fun CRC32.add(m: Machine, di: Int) {
        val b = mutableListOf(di and 0xFF, di ushr 8)
        for (k in OUT) m.g(k).let { b += it and 0xFF; b += it ushr 8 }
        b += m.gb(0x0C68)
        update(ByteArray(b.size) { b[it].toByte() })
    }

    @Test
    fun pairChainsMatchRebuild() {
        val got = VmPairs.scenarios().associate { s ->
            val m = Machine(VmPairs.load(s).first.memory)
            val p = Projection(m)
            val crc = CRC32()
            val objs = chain(m)
            for ((_, di) in objs) {
                p.objectProject(di)
                crc.add(m, di)
            }
            s.a to "${objs.size} ${crc.value.toString(16)}"
        }
        assertEquals(expected.mapValues { "${it.value.objects} ${it.value.crc.toString(16)}" }, got)
    }

    @Test
    fun fuzzedFieldsMatchRebuild() {
        val got = VmPairs.scenarios().filter { it.a in fuzzed }.associate { s ->
            val m = Machine(VmPairs.load(s).first.memory)
            val p = Projection(m)
            val es = m.g(0x0BD6)
            val o = m.g(0x0BEA)
            val objs = chain(m)
            val rng = XorShift32(0x1234567L)
            val crc = CRC32()
            repeat(500) {
                val (bx, di) = objs[rng.below(objs.size)]
                m.wb(es, bx + 1, (m.rb(es, bx + 1) and 0x7F) or (rng.bits() and 0x80))
                m.wb(es, bx + 0x1C, rng.below(8))
                m.wb(es, bx + 0x1D, rng.below(10) - 3)
                m.wb(es, bx + 0x1E, rng.below(16))
                for (i in 0 until 9) m.wb(es, bx + 0x20 + i, rng.bits())
                for (k in intArrayOf(0x16, 0x18, 0x1A)) {
                    m.ww(es, bx + k, rng.bits())
                    m.ww(o, di + k, rng.bits())
                }
                m.wb(o, di + 0x1C, rng.bits() and 0x9F)
                m.wb(o, di + 0x1D, rng.bits())
                m.wb(o, di + 0x23, rng.bits() and 1)
                m.wb(o, di + 0x24, rng.below(5))
                m.wb(o, di + 0x25, rng.bits())
                p.objectProject(di)
                crc.add(m, di)
            }
            s.a to crc.value
        }
        assertEquals(fuzzed, got)
    }

    private companion object {
        val OUT = intArrayOf(0x0C5E, 0x0C60, 0x0C62, 0x0C64, 0x0C66, 0x0B1A, 0x0B1C, 0x0B1E, 0x0B18, 0x0B12, 0x0B20)
    }
}
