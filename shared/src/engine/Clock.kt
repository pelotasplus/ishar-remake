package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.sleepMillis
import kotlinx.coroutines.delay
import kotlin.time.ExperimentalTime
import kotlin.time.TimeSource

/** What main.io 0596 read at boot on DOS: the BIOS tick count word, 18.2 a second. */
fun clockSeedAt(epochMillis: Long): Int = (epochMillis / 10_000 * 182 + epochMillis % 10_000 * 182 / 10_000).toInt() and 0xFFFF

@OptIn(ExperimentalTime::class)
fun clockSeed(): Int = clockSeedAt(kotlin.time.Clock.System.now().toEpochMilliseconds())

/**
 * Real time for the engine's 57.6 Hz tick (`Scheduler.tickPace`): each tick waits until its
 * moment. Falling behind by more than a quarter second
 * (a load, a slow device) restarts the schedule instead of rushing to catch up.
 *
 * [paceTo] blocks the engine thread, which is what lets a prompt wait inside `key_wait`; where
 * threads cannot block (web) it returns at once and [settle], between frames, suspends instead.
 */
class RealTimeClock {
    private var start = TimeSource.Monotonic.markNow()
    private var startEmu = 0.0
    private var emu = 0.0

    private fun restart() {
        start = TimeSource.Monotonic.markNow()
        startEmu = emu
    }

    /** Starts the schedule at emulated time [seconds] (after a run that was not paced). */
    fun sync(seconds: Double) {
        emu = seconds
        restart()
    }

    /** Emulated time has reached [seconds] (the sound's clock: music runs the game at 49.97 Hz). */
    fun paceTo(seconds: Double) {
        emu = seconds
        val ahead = ahead()
        when {
            ahead > 0 -> sleepMillis(ahead.toLong())
            ahead < -250 -> restart()
        }
    }

    suspend fun settle() {
        val ahead = ahead()
        if (ahead >= 1) delay(ahead.toLong())
    }

    /** Milliseconds until the emulated time reached is due (negative: behind). */
    private fun ahead() = (emu - startEmu) * 1000.0 - start.elapsedNow().inWholeMicroseconds / 1000.0
}
