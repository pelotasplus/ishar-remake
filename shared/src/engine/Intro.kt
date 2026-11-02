package dev.pelotasplus.ishar.engine

import kotlin.concurrent.Volatile

/**
 * The intro (main.io 055a..0847), run by the port: while it is [active] the player's taps and
 * keys come here, not to the game. Each frame, on the engine's thread, the [Stage] is read from
 * where main.io's frame resumes; a tap (or the stage's timeout) leaves it the script's own way,
 * by resuming main.io right after its wait loop. The presentation's two scenes end on the next
 * one's picture, so a tap there [fastForward]s to the next stage instead. A tap during a fast
 * forward, or within half a second of the last tap or of a fast forward's end, is dropped, so a
 * double tap leaves one stage.
 */
class Intro(private val vm: Scheduler, private val input: Input) {
    enum class Stage(val resume: Int, val leave: Int?, val seconds: Int?) {
        /** The Silmarils logo: main.io waits for a key or button in 05ae..05f2, then 05f4. */
        LOGO(0x05CC, 0x05F4, 6),
        /** presen.io 00a7..0042: the grey gate, the sorcerer standing, then the flames. main.io waits for presen's 7b (0692). */
        SORCERER(0x0693, null, null),
        /** presen.io 0043..00a6, then presti.io: the doors open, the ISHAR letters, ending on the title's picture. */
        DOORS(0x0693, null, null),
        /** The title with its music: main.io waits for a key or button in 0718, then 073a fades the music. */
        TITLE(0x0719, 0x073A, 10),
        /** The language menu waits for a keypad digit (4401..440d); a tap on a line presses it. */
        LANGUAGE(0x440D, null, null),
        /** The credits: main.io shows them for two frames (0825..082c); held on the yield at 082b, then 082d. */
        CREDITS(0x082C, 0x082D, 6),
    }

    /** Until the party is in the world. */
    @Volatile
    var active = true
        private set

    /** Run unpaced and silent, the screen kept as it is: the presentation after a tap, to its end. */
    @Volatile
    var fastForward = false
        private set

    private var stage: Stage? = null
    private var since = 0
    private var lastTap = -TAP_GRACE
    private var tapped = false
    private var choice: Int? = null

    /** A tap or a key from the player; [language] is the menu line it picks (1..4), if any. Any thread. */
    fun tap(language: Int?) = input.poke {
        tapped = true
        choice = language
    }

    /** After each frame, on the engine's thread. */
    fun frame() {
        val m = vm.machine
        if (Cheats.position(m).contree != 0) {
            active = false
            return
        }
        val now = stage(m)
        if (now != stage) {
            stage = now
            since = vm.ticks
            if (fastForward) lastTap = vm.ticks
            fastForward = false
        }
        val tap = tapped.also { tapped = false } && now != null && !fastForward && vm.ticks - lastTap >= TAP_GRACE
        if (tap) lastTap = vm.ticks
        if (now == null) return
        if (now == Stage.LANGUAGE) {
            choice?.takeIf { tap }?.let { input.press(Input.KEYPAD_DIGITS[it]) }
            return
        }
        val timedOut = now.seconds?.let { vm.ticks - since >= it * TICKS_PER_SECOND } == true
        if (tap && (now == Stage.SORCERER || now == Stage.DOORS)) {
            fastForward = true
        } else if (tap || timedOut) {
            now.leave?.let { Frames.resumeAt(m, MAIN_IO, it) }
            stage = null
        } else if (now == Stage.CREDITS) {
            Frames.resumeAt(m, MAIN_IO, HOLD)
        }
    }

    companion object {
        private const val MAIN_IO = 0
        private const val TICKS_PER_SECOND = 57.6
        private const val TAP_GRACE = 29

        /** The yield before [Stage.CREDITS]: the hold sends main.io back to it. */
        private const val HOLD = 0x082B

        fun stage(m: Machine): Stage? = Frames.resume(m, MAIN_IO)?.let { r ->
            when (r) {
                HOLD -> Stage.CREDITS
                Stage.SORCERER.resume -> if (Frames.resume(m, PRESEN_IO) in SORCERER_CODE) Stage.SORCERER else Stage.DOORS
                else -> Stage.entries.firstOrNull { it.resume == r }
            }
        }

        private const val PRESEN_IO = 0x3A

        /** presen.io's gate-and-sorcerer routine (00a7..01a2), and the wait before the doors (0038..0042). */
        private val SORCERER_CODE = listOf(0x0038..0x0042, 0x00A7..0x01A2).flatten().toSet()
    }
}
