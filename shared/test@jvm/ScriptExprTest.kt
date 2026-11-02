package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Expression and store programs run against the state the reference engine reaches
 * from the same boot; expected values come from the reference engine.
 */
class ScriptExprTest {

    private class Case(
        val name: String,
        val parts: List<Pair<String, String>>,
        val dx: Int,
        val c58: String,
        val glob10: Int,
        val glob60: String,
        val glob46: Int,
        val si: Int,
    )

    private val cases = listOf(
        Case("add", listOf("step" to "3800075600033a"), 0x000A, "", 0x0000, "", 0x0103, 0x0007),
        Case("sub_pop", listOf("step" to "38000540000358363a"), 0x0002, "", 0x0000, "", 0x0103, 0x0009),
        Case("div_overflow", listOf("step" to "380200804000ff5c363a"), 0xC001, "", 0x0000, "", 0x0103, 0x000A),
        Case("mod_neg", listOf("step" to "3800074000fe5a363a"), 0x0001, "", 0x0000, "", 0x0103, 0x0009),
        Case("div_zero", listOf("step" to "3800074000005c363a"), 0x0007, "", 0x0000, "", 0x0103, 0x0009),
        Case("mul", listOf("step" to "3800fd5e00073a"), 0xFFEB, "", 0x0000, "", 0x0103, 0x0007),
        Case("lt_signed", listOf("step" to "3800ff5200013a"), 0xFFFF, "", 0x0000, "", 0x0103, 0x0007),
        Case("random", listOf("step" to "380064643a"), 0x002D, "", 0x0000, "", 0x0103, 0x0005),
        Case("rand2", listOf("step" to "38020010400064703a"), 0x0040, "", 0x0000, "", 0x0103, 0x0009),
        Case("int_to_str", listOf("step" to "380285ff863a"), 0xFF85, "-123", 0x0000, "", 0x0103, 0x0006),
        Case("int_to_str0", listOf("step" to "380000863a"), 0x0000, "0", 0x0000, "", 0x0103, 0x0005),
        Case("strcat", listOf("step" to "380441420088044344003a"), 0x0000, "ABCD", 0x0000, "", 0x0103, 0x000B),
        Case("str_eq", listOf("step" to "3804616263008a04616263003a"), 0xFFFF, "abc", 0x0000, "", 0x0103, 0x000D),
        Case("str_lt", listOf("step" to "3804616263009204616264003a"), 0xFFFF, "abd", 0x0000, "", 0x0103, 0x000D),
        Case("str_gt", listOf("step" to "3804616264009404616263003a"), 0xFFFF, "abc", 0x0000, "", 0x0103, 0x000D),
        Case("strlen", listOf("step" to "380468656c6c6f00823a"), 0x0005, "hello", 0x0000, "", 0x0103, 0x000A),
        Case("str_to_int", listOf("step" to "38042d3432009a3a"), 0xFFD6, "-42", 0x0000, "", 0x0103, 0x0008),
        Case("str_left", listOf("step" to "380468656c6c6f009600037c3a"), 0x0003, "hel", 0x0000, "", 0x0103, 0x000D),
        Case("str_right", listOf("step" to "380468656c6c6f009600027e3a"), 0x0002, "lo", 0x0000, "", 0x0103, 0x000D),
        Case("glob_w_arr", listOf("step" to "3800022840003a"), 0xBE02, "", 0x0000, "", 0x0103, 0x0007),
        Case("glob_w_arr2", listOf("step" to "3800014000022840003a"), 0xBE02, "", 0x0000, "", 0x0103, 0x000A),
        Case("glob_w_arr_2d", listOf("step" to "3800014000022830003a"), 0xBE02, "", 0x0000, "", 0x0103, 0x000A),
        Case("glob_b_arr", listOf("step" to "3800052640003a"), 0xFFBE, "", 0x0000, "", 0x0103, 0x0007),
        Case("frame_w", listOf("step" to "382c500010003a"), 0x0000, "", 0x0000, "", 0x0103, 0x0007),
        Case("drive", listOf("step" to "38003fa03a"), 0x0043, "", 0x0000, "", 0x0103, 0x0005),
        Case("sign", listOf("step" to "3800f0663a"), 0xFFFF, "", 0x0000, "", 0x0103, 0x0005),
        Case("sysq3", listOf("step" to "380003763a"), 0x0031, "", 0x0000, "", 0x0103, 0x0005),
        Case("sysq0", listOf("step" to "380000763a"), 0x01FE, "", 0x0000, "", 0x0103, 0x0005),
        Case("file_exists", listOf("step" to "38046d61696e2e696f009c3a"), 0xFFFF, "main.io", 0x0000, "", 0x0103, 0x000C),
        Case("card", listOf("step" to "38783a"), 0x07D4, "", 0x0000, "", 0x0103, 0x0003),
        Case("store_w", listOf("step" to "380234123a", "store" to "201000"), 0x1234, "", 0x1234, "", 0x0103, 0x0003),
        Case("store_loc_b", listOf("step" to "3800fe3a", "store" to "1210"), 0xFFFE, "", 0x00FE, "", 0x0103, 0x0002),
        Case("add_store_b", listOf("step" to "3800053a", "store" to "1e1000", "add" to "1e1000"), 0x0005, "", 0x000A, "", 0x0103, 0x0003),
        Case("store_s", listOf("step" to "380478797a003a", "store" to "226000"), 0x0000, "", 0x0000, "xyz", 0x0103, 0x0003),
        Case("add_store_s", listOf("step" to "380478797a003a", "store" to "226000", "step" to "38044142003a", "add" to "226000"), 0x0000, "xyz", 0x0000, "xyzAB", 0x0103, 0x0003),
        Case("store_indexed", listOf("step" to "3800773a", "store" to "3800033a284000"), 0x0077, "", 0x0000, "", 0x0077, 0x0007),
    )

    private suspend fun booted(): Pair<Machine, ScriptExpr> {
        val files = GameFiles.fromResourceRepository(TestResources())
        val machine = Machine()
        Boot.boot(machine, files)
        val expr = ScriptExpr(machine, object : ScriptHost {
            override fun keyWait(): Int = 0
            override fun cpuSpeed(): Int = 379
            override fun fileExists(name: String): Boolean = files.exists(name)
        })
        val es = machine.g(0x0BD6)
        val base = machine.g(0x0BF6)
        machine.es = es
        machine.bp = base
        machine.wb(es, base + 0x3F, 0) // array at 40: no extra dims
        for (i in 0 until 8) machine.ww(es, base + 0x40 + 2 * i, 0xBE00 + i)
        machine.wb(es, base + 0x47, 1)
        machine.wb(es, base + 0x2F, 1) // array at 30: one extra dim, stride 10h
        machine.ww(es, base + 0x2C, 0x10)
        machine.sg(0x0C4E, 1)
        machine.dx = 0
        return machine to expr
    }

    private fun load(machine: Machine, hex: String) {
        for (i in 0 until hex.length / 2) {
            machine.wb(0x9000, i, hex.substring(2 * i, 2 * i + 2).toInt(16))
        }
        machine.ds = 0x9000
        machine.si = 0
    }

    @Test
    fun `expression table has 83 handlers`() = runTest {
        val (_, expr) = booted()
        assertEquals(83, expr.opcodes().size)
    }

    @Test
    fun `programs match the rebuild`() = runTest {
        for (c in cases) {
            val (machine, expr) = booted()
            for ((kind, hex) in c.parts) {
                load(machine, hex)
                when (kind) {
                    "step" -> expr.step()
                    "store" -> expr.store()
                    else -> expr.addStore()
                }
            }
            val es = machine.es
            val base = machine.g(0x0BF6)
            assertEquals(c.dx, machine.dx, "${c.name}: DX")
            assertEquals(c.c58, expr.cstr(machine.ss, machine.g(0x0C58)), "${c.name}: [0c58]")
            assertEquals(c.glob10, machine.rw(es, base + 0x10), "${c.name}: glob 10")
            assertEquals(c.glob60, expr.cstr(es, base + 0x60), "${c.name}: glob 60")
            assertEquals(c.glob46, machine.rw(es, base + 0x46), "${c.name}: glob 46")
            assertEquals(c.si, machine.si, "${c.name}: SI")
        }
    }

    @Test
    fun `odd and missing opcodes are unsupported`() = runTest {
        val (machine, expr) = booted()
        for (hex in listOf("3c", "0d", "aa")) {
            load(machine, hex)
            assertFailsWith<UnsupportedEngineOperationException> { expr.step() }
        }
    }
}
