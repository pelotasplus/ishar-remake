package dev.pelotasplus.ishar.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AudioQueueTest {
    @Test
    fun `buffers play one after the other`() {
        val q = AudioQueue()
        assertEquals(1.05, q.place(true, 1.0, 0.5))
        assertEquals(1.55, q.place(true, 1.1, 0.5))
    }

    /** The bug measured in the browser: 20 s before the first click, every sound 20.8 s late for good. */
    @Test
    fun `nothing queues while the clock stands still, so the wait adds no lag`() {
        val q = AudioQueue()
        repeat(1000) { assertNull(q.place(false, 0.0, 0.02)) }
        val first = q.place(true, 0.0, 0.02)!!
        assertTrue(first < 0.1, "plays right away: $first")
    }

    /** A pause: what was queued stays queued, and a gap puts the next buffer just ahead of now. */
    @Test
    fun `after a gap the next buffer starts just ahead of now`() {
        val q = AudioQueue()
        q.place(true, 1.0, 0.5)
        assertNull(q.place(false, 2.0, 0.5))
        assertEquals(10.05, q.place(true, 10.0, 0.5))
    }
}
