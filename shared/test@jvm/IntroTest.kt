package dev.pelotasplus.ishar.engine

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntroTest {

    private class Run(val vm: Scheduler, val intro: Intro) {
        /** Each stage seen, with the tick it began. */
        val stages = mutableListOf<Pair<Intro.Stage?, Int>>()
        val stage get() = Intro.stage(vm.machine)

        fun frames(n: Int, until: (Intro.Stage?) -> Boolean = { false }) {
            repeat(n) {
                vm.frame()
                intro.frame()
                if (stages.lastOrNull()?.first != stage) stages += stage to vm.ticks
                if (until(stage)) return
            }
        }

        fun wait(seconds: Double) = (vm.ticks + (seconds * 57.6).toInt()).let { end -> frames(10_000) { vm.ticks >= end } }

        fun to(s: Intro.Stage) = frames(10_000) { it == s }.also { assertEquals(s, stage) }

        fun toGame() = frames(10_000) { Cheats.position(vm.machine).contree != 0 }

        fun seconds(fn: () -> Unit): Double = vm.ticks.let { t -> fn(); (vm.ticks - t) / 57.6 }

        fun lasted(s: Intro.Stage) = stages.indexOfLast { it.first == s }.let { i ->
            (stages[i + 1].second - stages[i].second) / 57.6
        }

        fun screen() = Palette.frameArgb(vm.machine)

        fun lit() = (0 until 64000).count { Palette.frameArgb(vm.machine)[it] and 0xFFFFFF != 0 }
    }

    private suspend fun boot(): Run {
        val vm = Starts.SKIP_INTRO.boot(Play.files())
        val input = Input(vm).also { it.install() }
        return Run(vm, Intro(vm, input))
    }

    private fun language(r: Run) = r.vm.machine.rb(0x126B, 0x426F + 0x2A0)

    /** No taps: logo 6 s, the presentation to its end, title 10 s, the menu waits; then the credits 6 s, on screen. */
    @Test
    fun `the intro runs by itself`() = runTest {
        val r = boot()
        r.to(Intro.Stage.LANGUAGE)
        assertEquals(
            listOf(Intro.Stage.LOGO, Intro.Stage.SORCERER, Intro.Stage.DOORS, Intro.Stage.TITLE, Intro.Stage.LANGUAGE),
            r.stages.mapNotNull { it.first },
        )
        assertTrue(r.lasted(Intro.Stage.LOGO) in 5.9..6.5, "logo ${r.lasted(Intro.Stage.LOGO)}")
        assertTrue(r.lasted(Intro.Stage.SORCERER) > 3, "sorcerer ${r.lasted(Intro.Stage.SORCERER)}")
        assertTrue(r.lasted(Intro.Stage.DOORS) > 3, "doors ${r.lasted(Intro.Stage.DOORS)}")
        assertTrue(r.lasted(Intro.Stage.TITLE) in 9.9..10.5, "title ${r.lasted(Intro.Stage.TITLE)}")
        r.wait(20.0)
        assertEquals(Intro.Stage.LANGUAGE, r.stage, "the menu waits")
        r.intro.tap(2)
        r.to(Intro.Stage.CREDITS)
        r.wait(1.0)
        assertTrue(r.lit() > 10_000, "the credits are on screen")
        r.toGame()
        assertTrue(r.lasted(Intro.Stage.CREDITS) in 5.9..6.5, "credits ${r.lasted(Intro.Stage.CREDITS)}")
        assertEquals(0, language(r), "French")
        r.frames(1)
        assertFalse(r.intro.active)
    }

    /** The screen [after] seconds into [stage] of a run without taps. */
    private suspend fun untapped(stage: Intro.Stage, after: Double): IntArray = boot().run { to(stage); wait(after); screen() }

    private fun assertLooksLike(want: IntArray, r: Run, what: String) {
        val now = r.screen()
        val same = now.indices.count { now[it] == want[it] }
        assertTrue(same > 60_000, "$what ($same of 64000 pixels as without taps)")
    }

    /** One tap leaves one stage, at once; a tap within half a second of the last is dropped. */
    @Test
    fun `each tap leaves one stage`() = runTest {
        val r = boot()
        r.to(Intro.Stage.LOGO)
        r.wait(2.0)
        r.intro.tap(null)
        assertTrue(r.seconds { r.to(Intro.Stage.SORCERER) } < 1.5)
        r.wait(1.0)
        r.intro.tap(null)
        r.frames(2)
        assertTrue(r.intro.fastForward, "the sorcerer runs to its end")
        r.intro.tap(null) // a double tap
        r.to(Intro.Stage.DOORS)
        assertFalse(r.intro.fastForward)
        r.wait(0.3)
        assertLooksLike(untapped(Intro.Stage.DOORS, 0.3), r, "the closed doors")
        r.intro.tap(null) // the double tap's late twin, just after the fast forward
        r.wait(1.0)
        assertEquals(Intro.Stage.DOORS, r.stage, "the doors open")
        r.intro.tap(null)
        r.to(Intro.Stage.TITLE)
        r.wait(1.0)
        assertEquals(Intro.Stage.TITLE, r.stage)
        assertLooksLike(untapped(Intro.Stage.TITLE, 1.0), r, "the title's picture")
        r.intro.tap(null)
        r.to(Intro.Stage.LANGUAGE)
        r.intro.tap(null) // not on a line
        r.wait(1.0)
        assertEquals(Intro.Stage.LANGUAGE, r.stage)
        r.intro.tap(4)
        r.frames(2)
        r.intro.tap(null) // a double tap
        r.to(Intro.Stage.CREDITS)
        r.wait(1.0)
        assertEquals(Intro.Stage.CREDITS, r.stage)
        r.intro.tap(null)
        assertTrue(r.seconds { r.toGame() } < 1.5)
        assertEquals(3, language(r), "Italian")
    }
}
