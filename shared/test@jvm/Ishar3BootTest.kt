package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.repository.ResourceRepositoryImpl
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Ishar 3 on the engine ([GameBuild.ISHAR3]): its files staged under `files/ishar3/`. */
class Ishar3BootTest {
    @Test
    fun `the intro plays, the menu comes up, and Start game walks the town`() = runTest {
        val f = GameFiles.fromResourceRepository(ResourceRepositoryImpl(GameBuild.ISHAR3), saveSize = null)
        val m = Machine(build = GameBuild.ISHAR3)
        Boot.boot(m, f, soundOverride = 'B')
        val vm = Scheduler.withStatements(m, f)
        vm.interrupts = true
        vm.runPass()
        var colours = 0
        var logo = 0
        repeat(1900) { i ->
            vm.frame()
            if (i == 228) logo = (150 until 195).sumOf { y -> (25 until 60).count { x -> m.rb(m.screen, y * 320 + x) != 0 } }
            if (i == 500) colours = Palette.frameArgb(m).toSet().size // intro2.io's castle (FLI frames)
        }
        assertTrue(logo > 200, "the logo's dragon S is drawn: $logo pixels")
        assertTrue(colours > 64, "the intro draws its pictures: $colours colours")
        assertNotNull(Frames.of(m, 0x07), "accueil.io's menu")

        Boot.mouse(m, 160, 170, 1) // Start game
        repeat(5) { vm.frame() }
        Boot.mouse(m, buttons = 0)
        repeat(400) { vm.frame() }
        assertEquals(Cheats.Position(2, 50, 1, 1), Cheats.position(m))
        assertEquals("Zubaran", Cheats.party(m).single().name)
        assertEquals("sword (+4)", Items.ishar2(m).single().items.single().name)

        Boot.keyDown(m, 0x48) // forward
        repeat(6) { vm.frame() }
        Boot.keyUp(m, 0x48)
        repeat(40) { vm.frame() }
        assertEquals(49, Cheats.position(m).y)
        assertEquals(2000, m.rsw(m.build.globals, m.build.globBase + 0x20C8 + 2 * 81), "the last boss's life (r81) survives the map's load")
    }
}
