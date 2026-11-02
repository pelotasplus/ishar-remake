package dev.pelotasplus.ishar.features.game

/**
 * Fingers to the game's buttons: one finger the left, two the right (cancel). Fingers of a cancel land
 * and lift one at a time, so a first finger waits [WINDOW_MS] for a second, and what stays after a
 * cancel does nothing until it lifts. A left click holds [dev.pelotasplus.ishar.engine.Input.MIN_CLICK],
 * so one let through would reach the game.
 */
class TouchGesture {
    private enum class State { IDLE, WAITING, LEFT, RIGHT }

    private var state = State.IDLE
    private var since = 0L

    /** While it waits, ask again at most this long after [update]: the window may end with no event. */
    val waiting get() = state == State.WAITING

    /** [fingers] down at [now] ms -> the buttons to send, in order. */
    fun update(fingers: Int, now: Long): List<Int> = when (state) {
        State.IDLE -> when {
            fingers >= 2 -> go(State.RIGHT, 2)
            fingers == 1 -> {
                since = now
                state = State.WAITING
                emptyList()
            }
            else -> emptyList()
        }
        State.WAITING -> when {
            fingers >= 2 -> go(State.RIGHT, 2)
            fingers == 0 -> {
                state = State.IDLE
                listOf(1, 0)
            }
            now - since >= WINDOW_MS -> go(State.LEFT, 1)
            else -> emptyList()
        }
        State.LEFT -> when {
            fingers >= 2 -> go(State.RIGHT, 2)
            fingers == 0 -> go(State.IDLE, 0)
            else -> emptyList()
        }
        State.RIGHT -> if (fingers >= 2) emptyList() else rightUp(fingers)
    }

    private var released = false

    /** The right button goes up with the first finger; the rest of the gesture is silent. */
    private fun rightUp(fingers: Int): List<Int> {
        val out = if (released) emptyList() else listOf(0)
        released = fingers > 0
        if (fingers == 0) state = State.IDLE
        return out
    }

    private fun go(next: State, button: Int): List<Int> {
        state = next
        released = false
        return listOf(button)
    }

    companion object {
        /** Two fingers meant together land closer than this; short enough not to feel like lag. */
        const val WINDOW_MS = 80L
    }
}
