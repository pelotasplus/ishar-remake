package dev.pelotasplus.ishar.features.game

import kotlin.test.Test
import kotlin.test.assertEquals

class TouchGestureTest {
    private fun run(vararg steps: Pair<Int, Long>): List<Int> {
        val g = TouchGesture()
        return steps.flatMap { (fingers, now) -> g.update(fingers, now) }
    }

    private val w = TouchGesture.WINDOW_MS

    @Test
    fun `a quick tap is a left click`() {
        assertEquals(listOf(1, 0), run(1 to 0L, 0 to 40L))
    }

    @Test
    fun `a held finger is the left button after the window`() {
        assertEquals(listOf(1), run(1 to 0L, 1 to w))
        assertEquals(listOf(1, 0), run(1 to 0L, 1 to w, 1 to w + 500, 0 to w + 600))
    }

    @Test
    fun `no button before the window`() {
        assertEquals(emptyList(), run(1 to 0L, 1 to w - 1))
    }

    /** The cancel: fingers land one after the other and lift one after the other. No left click anywhere in it. */
    @Test
    fun `two fingers are only the right button`() {
        assertEquals(listOf(2, 0), run(1 to 0L, 2 to 30L, 1 to 300L, 0 to 340L))
    }

    @Test
    fun `two fingers landing together`() {
        assertEquals(listOf(2, 0), run(2 to 0L, 0 to 200L))
    }

    @Test
    fun `the finger left after a cancel does nothing until it lifts`() {
        assertEquals(listOf(2, 0), run(2 to 0L, 1 to 100L, 1 to 2_000L, 1 to 4_000L))
    }

    @Test
    fun `the next tap after a cancel works`() {
        assertEquals(listOf(2, 0, 1, 0), run(2 to 0L, 1 to 100L, 0 to 200L, 1 to 300L, 0 to 340L))
    }

    /** A second finger on a left drag already under way: as before, the right button. */
    @Test
    fun `a second finger during a held left press`() {
        assertEquals(listOf(1, 2, 0), run(1 to 0L, 1 to w, 2 to w + 100, 1 to w + 200, 0 to w + 300))
    }

    @Test
    fun `lifting and landing again starts a new gesture`() {
        assertEquals(listOf(1, 0, 1, 0), run(1 to 0L, 0 to 20L, 1 to 200L, 0 to 220L))
    }
}
