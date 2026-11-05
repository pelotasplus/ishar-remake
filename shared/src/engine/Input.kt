package dev.pelotasplus.ishar.engine

import kotlinx.coroutines.channels.Channel

/**
 * Keyboard and mouse from the host, delivered on the 57.6 Hz tick the way the game's
 * interrupt handlers deliver them: `int9_handler` (`1072`, the `cs:04be` table into the held
 * character `cs:0318` and the key-down flags `cs:031a[sc-1]`) and the INT 33h callback
 * (`1249`: `cs:11bd` x, `cs:11bf` y, `cs:11c1` buttons 1 left / 2 right), in source pixels.
 *
 * Any thread may post; the engine applies the events at its next tick, so they also reach a
 * script waiting inside a statement (`key_wait`, prompts).
 */
class Input(private val vm: Scheduler) {
    /** The focus went elsewhere: these key-ups will not come. */
    fun releaseKeys() = post { m -> sent.keys.toList().forEach { up(m, it) } }

    /** The app went out of sight: nor will the button's release. */
    fun releaseAll() = post { m ->
        sent.keys.toList().forEach { up(m, it) }
        Boot.mouse(m, buttons = 0)
    }

    private val queue = Channel<(Machine) -> Unit>(Channel.UNLIMITED)

    fun install() {
        vm.inputTick = ::drain
    }

    /** Releases queued with each press, by scancode: the engine's own until the host's key-up. */
    private val releases = mutableMapOf<Int, (Scheduler) -> Unit>()

    /**
     * A prompt (sos.io 0921) spins on a held key inside a frame, where no tick brings the host's
     * key-up, so each press also queues its release for such a spin; the key-up cancels it.
     *
     * Esc stays out: all it does in the game is main.io 4e60's pause, which spins inside a statement
     * until another key and so hangs the web's one thread. The debug menu pauses instead.
     */
    fun keyDown(scancode: Int) {
        if (scancode != ESC) post { down(it, scancode) }
    }

    private fun down(m: Machine, scancode: Int) {
        // The host's auto-repeat while a key is held: at a name prompt it would type the letter again ("aaaa")
        if (scancode in sent && textPrompt(m)) return
        val arborea = m.build == GameBuild.ARBOREA
        if (scancode == SPACE && !arborea && !textPrompt(m) && (if (m.build == GameBuild.ISHAR1) attackAll(m) else attackAll2(m))) return
        val keys = when (m.build) {
            GameBuild.ARBOREA -> ARBOREA_KEYS
            GameBuild.ISHAR1 -> WASD
            else -> ISHAR2_WASD
        }
        val sc = keys[scancode]?.takeUnless { _ -> textPrompt(m) } ?: scancode
        if (arborea && scancode in ARBOREA_KEYS) altHeld(m, true)
        sent[scancode] = sc
        keyDownAt[scancode] = vm.ticks
        Boot.keyDown(m, sc)
        val release: (Scheduler) -> Unit = { vm -> Boot.keyUp(vm.machine, sc) }
        releases.put(sc, release)?.let { old -> vm.spinRelease.remove(old) }
        vm.spinRelease.addLast(release)
    }

    /** A key stays down [KEY_HOLD] ticks, as a click does [MIN_CLICK]; a press since then keeps it down. */
    fun keyUp(scancode: Int) = post {
        val at = keyDownAt[scancode] ?: -KEY_HOLD
        val early = KEY_HOLD - (vm.ticks - at)
        if (early > 0) vm.at(early) { s -> if (keyDownAt[scancode] == at) up(s.machine, scancode) } else up(it, scancode)
    }

    private val keyDownAt = mutableMapOf<Int, Int>()

    private fun up(m: Machine, scancode: Int) {
        val sc = sent.remove(scancode) ?: scancode
        Boot.keyUp(m, sc)
        if (m.build == GameBuild.ARBOREA && sent.keys.none { it in ARBOREA_KEYS }) altHeld(m, false)
        releases.remove(sc)?.let { r -> vm.spinRelease.remove(r) }
    }

    /** Arborea's souris.io takes the keypad as the move pad only with Alt held (BIOS shift flags bit 3). */
    private fun altHeld(m: Machine, on: Boolean) {
        val f = m.rb(0x40, 0x17)
        m.wb(0x40, 0x17, if (on) f or 0x08 else f and 0x08.inv())
    }

    /** What each held host key went down as (W/A/S/D/Q/E: a move key, or the letter at a prompt). */
    private val sent = mutableMapOf<Int, Int>()

    /** [scancode] down, and up again [ticks] later on the engine's clock (a tap on the screen standing in for a key). */
    fun press(scancode: Int, ticks: Int = 9) = post {
        Boot.keyDown(it, scancode)
        vm.at(ticks) { s -> Boot.keyUp(s.machine, scancode) }
    }

    /** A typed key (the phone's keyboard): down [TYPE_HOLD] ticks, once the key typed before it is up. */
    fun type(scancode: Int) = post {
        val wait = maxOf(0, typeFree - vm.ticks)
        typeFree = vm.ticks + wait + TYPE_HOLD + TYPE_GAP
        vm.at(wait) { s -> Boot.keyDown(s.machine, scancode) }
        vm.at(wait + TYPE_HOLD) { s -> Boot.keyUp(s.machine, scancode) }
    }

    private var typeFree = 0

    /** A held Space repeats; a round runs out first. False when Space is just a key ([AttackAll.slots]). */
    private fun attackAll(m: Machine): Boolean {
        if (vm.ticks < attackFree) return true
        val slots = AttackAll.slots(m)
        if (slots.isEmpty()) return false
        attackFree = vm.ticks + slots.size * ATTACK_GAP
        val x = m.rw(Machine.CS, m.build.mouse)
        val y = m.rw(Machine.CS, m.build.mouse + 2)
        slots.forEachIndexed { i, slot ->
            vm.at(i * ATTACK_GAP) { s -> Boot.mouse(s.machine, AttackAll.tabX(slot), AttackAll.TAB_Y, 1) }
            vm.at(i * ATTACK_GAP + MIN_CLICK) { s -> Boot.mouse(s.machine, buttons = 0) }
        }
        vm.at(slots.size * ATTACK_GAP) { s -> Boot.mouse(s.machine, x, y) }
        return true
    }

    private var attackFree = 0

    /**
     * Ishar 2: each living member's fist under the move arrows in turn, the game's own click. The fists
     * are parts of frise.io's compound box (shape 9), class = the member's number, which souris.io
     * writes to glob 4f10 and frise.io 08d0 -> 0909 swings with. Only with a hostile on the cell ahead
     * or on the party's own cell, where Ishar 2's fighters stand: a swing costs PHYSICAL even at nothing.
     * souris.io puts the fist's class in 4f10 while the button is down, and frise.io takes it only between
     * swings (08d0, cleared at 09a5); a press during a swing overwrites the one still waiting. So each fist
     * waits until frise.io has taken the one before ([FIST_WAIT] ticks at most).
     */
    private fun attackAll2(m: Machine): Boolean {
        if (vm.ticks < attackFree) return true
        val p = Cheats.position(m)
        val (dx, dy) = listOf(0 to -1, 1 to 0, 0 to 1, -1 to 0).getOrNull(p.facing - 1) ?: return false
        // a fighting creature stands on the party's own cell (seen live: record 90 at 60,34 with the party at 60,34)
        val ahead = Creatures.all(m).any {
            it.hostile && !it.inParty && it.life > 0 && it.contree == p.contree &&
                ((it.x == p.x + dx && it.y == p.y + dy) || (it.x == p.x && it.y == p.y))
        }
        if (!ahead) return false
        val slots = Cheats.party(m).filter { it.life > 0 }.map { it.slot }
        if (slots.isEmpty()) return false
        attackFree = vm.ticks + slots.size * (MIN_CLICK + FIST_WAIT)
        val x = m.rw(Machine.CS, m.build.mouse)
        val y = m.rw(Machine.CS, m.build.mouse + 2)
        fun fist(i: Int, s: Scheduler) {
            if (i == slots.size) {
                val last = slots.last()
                val at = s.machine.build.mouse
                // the pointer back, unless the player has moved it meanwhile
                if (s.machine.rw(Machine.CS, at) == ISHAR2_FIST_X[last] && s.machine.rw(Machine.CS, at + 2) == ISHAR2_FIST_Y[last]) {
                    Boot.mouse(s.machine, x, y)
                }
                attackFree = s.ticks
                return
            }
            Boot.mouse(s.machine, ISHAR2_FIST_X[slots[i]], ISHAR2_FIST_Y[slots[i]], 1)
            s.at(MIN_CLICK) { s2 ->
                Boot.mouse(s2.machine, buttons = 0)
                fun taken(left: Int, s3: Scheduler) {
                    if (left == 0 || s3.machine.rw(s3.machine.build.globals, FIST_CLASS + s3.machine.build.globBase) == 0) fist(i + 1, s3)
                    else s3.at(1) { taken(left - 1, it) }
                }
                taken(FIST_WAIT, s2)
            }
        }
        vm.at(0) { fist(0, it) }
        return true
    }

    fun pointer(x: Int, y: Int, touch: Boolean = false) {
        vm.touch = touch
        post { Boot.mouse(it, x.coerceIn(0, 319), y.coerceIn(0, 199)) }
    }

    /** A press stays down at least [MIN_CLICK] ticks: the scripts poll once a frame, so a quick tap would be lost. */
    fun buttons(buttons: Int) = post {
        if (buttons != 0 && it.build == GameBuild.ISHAR3) {
            if (Ishar3Start.skipIntro(it)) return@post // what Escape does in the intro
            if (Frames.of(it, 0x05) != null) { // Enter ends the logo (logo.io)
                Boot.keyDown(it, ENTER)
                vm.at(KEY_HOLD) { s -> Boot.keyUp(s.machine, ENTER) }
                return@post
            }
        }
        if (buttons != 0 && ishar2Logo(it)) {
            // Ishar 2: only Enter on the logo skips the intro (main.io 063e); a click would end just the logo
            Boot.keyDown(it, ENTER)
            vm.at(KEY_HOLD) { s -> Boot.keyUp(s.machine, ENTER) }
            return@post
        }
        if (buttons != 0 && ishar2NameOutside(it)) {
            // Ishar 2's disk prompts cancel on F1 only (io.io 04e9): a tap outside the text box is F1
            Boot.keyDown(it, F1)
            vm.at(KEY_HOLD) { s -> Boot.keyUp(s.machine, F1) }
            return@post
        }
        if (buttons != 0) ishar2MusicChoice(it)?.let { digit ->
            // Ishar 2's MUSIC menu answers to 1/2 only (main.io 356d): a tap on a line is that key
            val sc = KEYPAD_DIGITS[digit]
            Boot.keyDown(it, sc)
            vm.at(KEY_HOLD) { s -> Boot.keyUp(s.machine, sc) }
            return@post
        }
        val early = MIN_CLICK - (vm.ticks - downAt)
        if (buttons == 0 && early > 0) {
            val at = downAt
            vm.at(early) { s -> if (downAt == at) Boot.mouse(s.machine, buttons = 0) }
            return@post
        }
        if (buttons != 0) downAt = vm.ticks
        Boot.mouse(it, buttons = buttons)
    }

    private var downAt = -MIN_CLICK


    /** main.io waits in Ishar 2's logo loop (0606..069e). */
    private fun ishar2Logo(m: Machine) = m.build == GameBuild.ISHAR2 && Frames.resume(m, 0) in 0x0606..0x069E

    /** io.io waits for a key of the LOAD/SAVE/import name (0498..04a8) and the pointer is above its text box (y < 136). */
    private fun ishar2NameOutside(m: Machine): Boolean {
        if (m.build != GameBuild.ISHAR2 || Frames.resume(m, 0x08) !in 0x0498..0x04A9) return false
        return m.rw(Machine.CS, m.build.mouse + 2) < 136
    }

    /** On Ishar 2's MUSIC menu (main.io 355f..359a), the line under the pointer: 1 ON (y 88..109), 2 OFF (110..135). */
    private fun ishar2MusicChoice(m: Machine): Int? {
        if (m.build != GameBuild.ISHAR2 || Frames.resume(m, 0) !in 0x355F..0x359A) return null
        val x = m.rw(Machine.CS, m.build.mouse)
        val y = m.rw(Machine.CS, m.build.mouse + 2)
        return when {
            x !in 85..235 -> null
            y in 88..109 -> 1
            y in 110..135 -> 2
            else -> null
        }
    }

    /** Any edit of the machine, applied on the engine's thread at the next tick. */
    fun poke(edit: (Machine) -> Unit) = post(edit)

    /** Heal all, which in Ishar 2 finishes over a few frames ([Cheats.heal]). */
    fun heal() = post { Cheats.heal(vm) }

    /** Frame-exact input ([SaveGames]' events, keyed by frames from now), applied at the first tick of each frame. */
    fun schedule(events: Map<Int, List<Session.Event>>) = post {
        for ((f, list) in events) scheduled.getOrPut(vm.frames + f) { mutableListOf() }.addAll(list)
    }

    private val scheduled = mutableMapOf<Int, MutableList<Session.Event>>()

    private fun post(e: (Machine) -> Unit) {
        queue.trySend(e)
    }

    private fun drain() {
        while (true) (queue.tryReceive().getOrNull() ?: break)(vm.machine)
        if (scheduled.isEmpty()) return
        for (f in scheduled.keys.filter { it <= vm.frames }.sorted()) scheduled.remove(f)?.forEach { Session.apply(vm, it) }
    }

    companion object {
        /**
         * W/S forward/back and A/D strafe (the arrows, '8'/'2'/'4'/'6'), Q/E turn (keypad '1'/'3',
         * gerdep.io action codes 5/6), except while a [textPrompt] takes letters.
         */
        private val WASD = mapOf(
            0x11 to UP, 0x1E to LEFT, 0x1F to DOWN, 0x20 to RIGHT,
            0x10 to 0x4F, 0x12 to 0x51,
        )

        /**
         * Arborea's move pad from the keypad (souris.io 00cf, with Alt): 8 forward, 2 back, 4/6 turn, 7/9 strafe,
         * 1 the map, 3 out of the 3D view. W/S/A/D and the arrows walk and turn, Q/E strafe.
         */
        private val ARBOREA_KEYS = mapOf(
            0x11 to UP, 0x1F to DOWN, 0x1E to LEFT, 0x20 to RIGHT,
            UP to UP, DOWN to DOWN, LEFT to LEFT, RIGHT to RIGHT,
            0x10 to 0x47, 0x12 to 0x49,
        )

        /** Ishar 2 swapped them: keypad 4/6 (Left/Right) turn, keypad 1/3 strafe. */
        private val ISHAR2_WASD = mapOf(
            0x11 to UP, 0x1E to 0x4F, 0x1F to DOWN, 0x20 to 0x51,
            0x10 to LEFT, 0x12 to RIGHT,
        )

        /** Assets whose frame asks for typed text: the copy-protection questions (monstre.io, telep.io) and the save/load name (sos.io). */
        private val PROMPT_ASSETS = setOf(0x54, 0x55, 0x56)

        /** Ishar 2's disk menu, io.io (asset 8: frise.io 5ca6): its save, load and transfer prompts. */
        private val ISHAR2_PROMPT_ASSETS = setOf(0x08)

        /** Ishar 3's io.io is asset 13h (main.io); its 8 is buste.io, always running. */
        private val ISHAR3_PROMPT_ASSETS = setOf(0x13)

        /** A frame of a [PROMPT_ASSETS] asset exists (sos.io stays loaded in play; its frame lives only while the prompt is up). */
        fun textPrompt(m: Machine): Boolean {
            val assets = when (m.build) {
                GameBuild.ARBOREA -> emptySet()
                GameBuild.ISHAR1 -> PROMPT_ASSETS
                GameBuild.ISHAR2 -> ISHAR2_PROMPT_ASSETS
                GameBuild.ISHAR3 -> ISHAR3_PROMPT_ASSETS
            }
            val es = m.g(0x0BD6)
            val tbl = m.g(0x0BD2)
            var bx = 0
            repeat(64) {
                if (m.rw(es, m.rw(es, tbl + bx) - 0x10) in assets) return true
                bx = m.rw(es, tbl + bx + 4)
                if (bx == 0) return false
            }
            return false
        }

        /** Where sos.io's name loop begins (the first key's spin, 0921), by language 426f: French, English, German, Italian. */
        private val NAME_LOOP = intArrayOf(0x0921, 0x086D, 0x08C7, 0x08C6)

        /**
         * The frame is in the save or load prompt's name loop, from the yield before it to its last yield, not
         * at the fee's OK / EXIT: only here does the game take typed letters, so only here a phone needs its keyboard.
         */
        fun namePrompt(m: Machine): Boolean {
            if (m.build != GameBuild.ISHAR1) return false
            val at = Frames.resume(m, 0x56) ?: return false
            val loop = NAME_LOOP.getOrElse(m.rb(0x126B, 0x426F + 0x2A0)) { NAME_LOOP[0] }
            return at in loop - 13..loop + 0x181
        }

        const val MIN_CLICK = 9

        /** The middle of Ishar 2's fists I..V (frise.io shape parts 4..8, class 1..5). */
        private val ISHAR2_FIST_X = intArrayOf(266, 277, 287, 298, 308)
        private val ISHAR2_FIST_Y = intArrayOf(86, 113, 86, 113, 86)

        /** A key stays down this many ticks: past a frame (3 ticks), so the scripts' once-a-frame read sees it. */
        const val KEY_HOLD = 6

        /** Ticks from one member's ATTACK click to the next: the press and the strike's frames. */
        const val ATTACK_GAP = 21

        /** Ishar 2: the fist souris.io saw pressed (glob 4f10), until frise.io takes it. */
        private const val FIST_CLASS = 0x4F10
        private const val FIST_WAIT = 90
        const val TYPE_HOLD = 9
        const val TYPE_GAP = 6

        const val ESC = 0x01
        const val ENTER = 0x1C
        const val SPACE = 0x39
        const val BACKSPACE = 0x0E
        const val TAB = 0x0F
        const val LEFT_SHIFT = 0x2A
        const val RIGHT_SHIFT = 0x36
        const val CTRL = 0x1D
        const val ALT = 0x38
        const val UP = 0x48
        const val DOWN = 0x50
        const val LEFT = 0x4B
        const val RIGHT = 0x4D
        const val F1 = 0x3B

        /** Keypad 0..9: the game's menus answer to these (its table is AZERTY: top-row digits type `& é " '`). */
        val KEYPAD_DIGITS = intArrayOf(0x52, 0x4F, 0x50, 0x51, 0x4B, 0x4C, 0x4D, 0x47, 0x48, 0x49)

        /** The scancode whose `cs:04be` entry is [c]; a digit's is on the keypad, the rest of which stays out. */
        fun scancodeFor(c: Char): Int? {
            if (c in '0'..'9') return KEYPAD_DIGITS[c - '0']
            val t = ExeData.KBD_TABLE
            val i = (0 until minOf(t.size, 0x46)).firstOrNull { (t[it].toInt() and 0xFF) == c.code }
            return i?.plus(1)
        }
    }
}
