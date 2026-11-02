package dev.pelotasplus.ishar.engine

/**
 * The input that gets a game going, taken from the recorded sessions (in their
 * log format): the boot's benchmark and seed, then key presses at fixed frames, run unpaced.
 */
object Starts {
    /** s1 to frame 900: Space on the logo, keypad 1 at the title, keypad 1 for English; the party walks in Fragonir. */
    val SKIP_INTRO = Session.parse(
        "skip intro",
        """
        0 hw cpu_speed 014c
        0 hw random_seed 0057
        1 hw slow_machine 01
        200 key down 39
        203 key up 39
        500 key down 4f
        503 key up 4f
        700 key down 4f
        703 key up 4f
        """.trimIndent(),
    )
    const val SKIP_INTRO_FRAMES = 900

    /**
     * Ishar 2: Enter on the logo skips the whole intro (main.io 063e), then
     * keypad 2 at MUSIC 1/2 (off: the music player is not rebuilt for the SB). Escape is its pause, never a skip.
     */
    val ISHAR2_SKIP_INTRO = Session.parse(
        "skip intro",
        """
        0 hw cpu_speed 014c
        0 hw random_seed 0057
        1 hw slow_machine 01
        40 key down 1c
        43 key up 1c
        190 key down 50
        193 key up 50
        """.trimIndent(),
    )
    const val ISHAR2_SKIP_INTRO_FRAMES = 590

    /** By this frame the intro (Space, the title key) has reached the language menu (main.io 436b), waiting for a key. */
    const val LANGUAGE_MENU_FRAMES = 700

    /** Boots and runs [SKIP_INTRO]'s first keys up to [LANGUAGE_MENU_FRAMES]: the player picks the language. */
    fun toLanguageMenu(files: GameFiles, sound: Char? = null, sink: ((Machine) -> SoundSink)? = null): Scheduler {
        val vm = SKIP_INTRO.boot(files, sound = sound, sink = sink)
        for (f in 1 until LANGUAGE_MENU_FRAMES) SKIP_INTRO.step(vm, f)
        return vm
    }

    /** The language menu waits for its key: main.io's frame resumes at 440d (the yield in its loop 4401..440d). */
    fun languageMenu(m: Machine): Boolean = Intro.stage(m) == Intro.Stage.LANGUAGE

    /**
     * The menu line at source pixel ([x], [y]): its keypad digit 1..4 (ENGLISH, FRANCAIS, DEUTSCH,
     * ITALIANO, drawn at y 69/88/108/128 in the box 85..235 x 50..150), or null.
     */
    fun languageAt(x: Int, y: Int): Int? = if (x !in 85..235 || y !in 50..150) null else when {
        y < 79 -> 1
        y < 98 -> 2
        y < 118 -> 3
        else -> 4
    }

    /** Boots and runs [SKIP_INTRO] up to [SKIP_INTRO_FRAMES]: the next frame is the player's. */
    fun skipIntro(files: GameFiles, sound: Char? = null, sink: ((Machine) -> SoundSink)? = null): Scheduler {
        val vm = SKIP_INTRO.boot(files, sound = sound, sink = sink)
        for (f in 1 until SKIP_INTRO_FRAMES) SKIP_INTRO.step(vm, f)
        return vm
    }

    /**
     * s4 to frame 593: Ctrl+Backspace on the logo and "1" (the developers' test party), Space,
     * Space, keypad 1 for English; at 593 the X/Y/contree/region/zone prompts open ([Cheats.debugStart]).
     */
    val DEBUG_START = Session.parse(
        "debug start",
        """
        0 hw cpu_speed 0333
        0 hw random_seed 017c
        1 hw slow_machine 01
        30 poke 0040:0017 04
        30 key down 0e
        30 type 310d
        31 key up 0e
        31 poke 0040:0017 00
        51 key down 39
        54 key up 39
        305 key down 39
        308 key up 39
        559 key down 4f
        562 key up 4f
        """.trimIndent(),
    )
}
