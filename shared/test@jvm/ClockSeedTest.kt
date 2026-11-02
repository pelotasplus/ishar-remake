package dev.pelotasplus.ishar.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClockSeedTest {
    /** The BIOS tick count word: 18.2 a second, in 16 bits. */
    @Test
    fun `the seed runs like the PC's clock`() {
        assertEquals(0, clockSeedAt(0))
        assertEquals(182, clockSeedAt(10_000))
        assertEquals((1_000_000_000_000L * 182 / 10_000 and 0xFFFF).toInt(), clockSeedAt(1_000_000_000_000L))
        assertTrue(clockSeedAt(Long.MAX_VALUE / 1000) in 0..0xFFFF)
        assertTrue(clockSeed() in 0..0xFFFF)
    }

    /** The replays stay fixed: the skip-intro and load input depend on them, and the tests on that. */
    @Test
    fun `the recorded skip intro keeps its seed`() {
        assertEquals(0x0057, Starts.SKIP_INTRO.randomSeed)
    }
}
