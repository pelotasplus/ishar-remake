package dev.pelotasplus.ishar.engine

import kotlin.math.max

/**
 * When each buffer plays on a clock that can stand still (Web Audio before the first gesture, or suspended).
 * Nothing is queued then: what was queued would all play after the gesture, late by the wait, for good.
 */
class AudioQueue(private val lead: Double = 0.05) {
    private var next = 0.0

    /** The start of a buffer of [seconds] at clock time [now], or null to drop it. */
    fun place(running: Boolean, now: Double, seconds: Double): Double? {
        if (!running) return null
        val at = max(next, now + lead)
        next = at + seconds
        return at
    }
}
