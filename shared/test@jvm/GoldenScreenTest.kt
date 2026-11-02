package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Golden PNGs of rendered frames (320x200, the DAC applied by [Palette.frameArgb]) in
 * `shared/test@jvm/goldens/`: pairs run to B (the screen when B holds one, else the
 * E000 page) and the intro from a fresh boot. `UPDATE_GOLDENS=1` writes every one from
 * the current render. A missing golden or a mismatch fails and writes the render to
 * `build/golden-failures/`.
 */
class GoldenScreenTest {

    private val goldens = File(VmPairs.root, "ishar-game/shared/test@jvm/goldens")
    private val failures = File(VmPairs.root, "ishar-game/build/golden-failures")
    private val update = System.getenv("UPDATE_GOLDENS") == "1"

    private fun png(argb: IntArray) = BufferedImage(320, 200, BufferedImage.TYPE_INT_RGB).apply {
        setRGB(0, 0, 320, 200, argb, 0, 320)
    }

    private fun writeFailure(name: String, argb: IntArray) {
        failures.mkdirs()
        ImageIO.write(png(argb), "png", File(failures, "$name.png"))
    }

    /** What is wrong with the frame (no golden, or pixels differing), null when it matches or UPDATE_GOLDENS wrote it. */
    private fun check(name: String, argb: IntArray): String? {
        val f = File(goldens, "$name.png")
        if (update) {
            goldens.mkdirs()
            ImageIO.write(png(argb), "png", f)
            return null
        }
        if (!f.exists()) {
            writeFailure(name, argb)
            return "$name: no golden (UPDATE_GOLDENS=1 writes it)"
        }
        val want = ImageIO.read(f).getRGB(0, 0, 320, 200, null, 0, 320)
        val diff = argb.indices.count { (argb[it] and 0xFFFFFF) != (want[it] and 0xFFFFFF) }
        if (diff == 0) return null
        writeFailure(name, argb)
        return "$name: $diff pixels"
    }

    @Test
    fun `pair frames match their goldens`() = runTest {
        val files = GameFiles.fromResourceRepository(TestResources())
        val scenarios = VmPairs.scenarios().filter { it.a in PAIRS }
        assertEquals(PAIRS, scenarios.map { it.a }.toSet(), "every pair has its scenario")
        val bad = scenarios.mapNotNull { s ->
            val (a, b) = VmPairs.load(s)
            val machine = Machine(a.memory)
            val vm = Scheduler.withStatements(machine, files.fresh())
            vm.interrupts = true
            repeat(s.frames) { vm.frame() }
            val seg = if (b.hasScreen) Machine.SCREEN else Machine.PAGE
            check("pair-${s.a}-${s.b}", Palette.frameArgb(machine, seg))
        }
        assertEquals(emptyList(), bad)
    }

    @Test
    fun `intro frames from a fresh boot match their goldens`() = runTest {
        val machine = Machine()
        val files = GameFiles.fromResourceRepository(TestResources())
        Boot.boot(machine, files)
        val vm = Scheduler.withStatements(machine, files)
        vm.interrupts = true
        vm.runPass()
        val bad = BOOT_FRAMES.mapNotNull { n ->
            while (vm.frames < n) vm.frame()
            check("boot-%04d".format(n), Palette.frameArgb(machine))
        }
        assertEquals(emptyList(), bad)
    }

    private companion object {
        val PAIRS = setOf("i", "k", "t", "o", "am", "sb", "csr", "nm", "lv", "go")
        val BOOT_FRAMES = listOf(10, 50, 100, 400)
    }
}
