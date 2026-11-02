package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Session.Event

/**
 * Ishar 3's start menus driven as a player would, unpaced: Enter ends the logo (logo.io, asset 5), [skipIntro] the
 * intro (runner.io aah), then accueil.io's menu (asset 7). Load game -> Ishar 3 opens io.io (13h) with its
 * list of the last saves (BACK.INF) and "Other" (06f3..0849), which asks for a name.
 */
object Ishar3Start {
    private const val LOGO = 0x05
    private const val MENU = 0x07
    private const val RUNNER = 0xAA
    private const val FRISE = 0x0D

    /**
     * Escape in the intro: runner.io's tick (03ce) jumps to its end (0381), which deletes the intro's frames, sets
     * its 4dh to 9dh for main.io (0a53) and stops; in a tick that stop does not leave (start.exe 7894), so the tick
     * goes on into frame_delete_self, and main.io reads 9dh from the freed frame. Here the runner goes to 0381 on its
     * main path instead, where the stop leaves and the frame stays for main.io to delete. False when no intro runs.
     */
    fun skipIntro(m: Machine): Boolean {
        val bp = Frames.of(m, RUNNER) ?: return false
        if (Frames.of(m, MENU) != null || (Frames.resume(m, RUNNER) ?: return false) >= INTRO_END) return false
        Frames.resumeAt(m, RUNNER, INTRO_END)
        val es = m.g(0x0BD6)
        m.wb(es, bp - 4, 1)
        m.wb(es, bp - 1, 1)
        return true
    }

    private const val INTRO_END = 0x0381

    /** io.io (asset 13h) importing a party: its 4ch is ffh from Ishar 1, feh from Ishar 2 (io.io 01b4/01e8); else null. */
    fun importFrom(m: Machine): Int? {
        val bp = Frames.of(m, 0x13) ?: return null
        return when (m.rb(m.g(0x0BD6), bp + 0x4C)) {
            0xFF -> 1
            0xFE -> 2
            else -> null
        }
    }

    /** Timer ticks a typed key stays down. */
    private const val KEY_TICKS = 9

    /** Runs [vm] to the start menu; the frames it took. */
    fun toMenu(vm: Scheduler, limit: Int = 8000): Int {
        val m = vm.machine
        var n = 0
        var settled = 0
        while (n < limit) {
            skipIntro(m)
            val key = if (Frames.of(m, LOGO) != null) Input.ENTER else null
            if (key != null && n % 20 == 0) Session.apply(vm, Event.KeyDown(key))
            if (key != null && n % 20 == 5) Session.apply(vm, Event.KeyUp(key))
            if (Frames.of(m, MENU) != null && Frames.of(m, RUNNER) == null) settled++ else settled = 0
            vm.frame()
            n++
            if (settled > 60) break
        }
        Session.apply(vm, Event.KeyUp(Input.ENTER)) // the logo's Enter may still be down
        vm.frame()
        return n + 1
    }

    /** From the start menu, LOAD's "Ishar 3" with [name] typed; the frames it took, or null when no game began. */
    fun load(vm: Scheduler, name: String, limit: Int = 3000): Int? {
        val m = vm.machine
        var n = 0
        fun run(k: Int) = repeat(k) { vm.frame(); n++ }
        fun click(x: Int, y: Int) {
            Session.apply(vm, Event.Mouse(x, y))
            Session.apply(vm, Event.Buttons(1))
            run(4)
            Session.apply(vm, Event.Buttons(0))
        }
        click(LOAD_X, LOAD_Y)
        run(40)
        click(ISHAR3_X, ISHAR3_Y)
        run(60)
        if (vm.files.exists("back.inf")) {
            click(OTHER_X, OTHER_Y)
            run(40)
        }
        // as the app types (Input.type): each key goes up on a timer tick, so a prompt spinning inside a frame sees it
        for (sc in name.lowercase().map { requireNotNull(Input.scancodeFor(it)) { "no key for $it" } } + Input.ENTER) {
            Boot.keyDown(m, sc)
            vm.at(KEY_TICKS) { s -> Boot.keyUp(s.machine, sc) }
            run(6)
        }
        while (n < limit) {
            run(1)
            if (Frames.of(m, FRISE) != null && Frames.of(m, MENU) == null) {
                run(60)
                return n
            }
        }
        return null
    }

    /** accueil.io's first line (Load game), then its submenu's first (Ishar 3). */
    private const val LOAD_X = 160
    private const val LOAD_Y = 118
    private const val ISHAR3_X = 160
    private const val ISHAR3_Y = 118

    /** io.io's cell 7 of the last saves: column 3 (x / 80), the lower row ((200 - y - 21) / 14 = 0). */
    private const val OTHER_X = 280
    private const val OTHER_Y = 172
}
