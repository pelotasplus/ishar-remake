package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.engine.Machine.Companion.CS
import kotlin.concurrent.Volatile

/**
 * The script scheduler and statement loop:
 * `script_scheduler` (`2632`), `script_vm_run` (`26eb`), the 57.6 Hz tick (`int8_handler` `0d98`)
 * and the timing half of `frame_wait` (`3d3c`).
 *
 * Statement handlers (`script_stmt_table` `cs:0024`) are installed into [stmts] by
 * [ScriptStmts] and friends; the renderer and the sound fill the hooks.
 */
class Scheduler(
    val machine: Machine,
    val files: GameFiles,
) : ScriptHost {
    val expr = ScriptExpr(machine, this)

    /** `script_stmt_table` `cs:0024`; a missing entry is an unsupported statement. */
    val stmts = arrayOfNulls<() -> Unit>(256)

    var trace: ((Int) -> Unit)? = null
    var statements = 0L
    var frames = 0

    /** Run the 57.6 Hz clock (frame_wait's wait, long statement runs, key_wait). */
    var interrupts = false
    var ticks = 0
    private var busy = 0
    private val events = mutableListOf<Pair<Int, (Scheduler) -> Unit>>()

    /** Characters for key_wait (prompts). */
    val typed = ArrayDeque<Int>()

    /** Keys to release once a script spins on them. */
    val spinRelease = ArrayDeque<(Scheduler) -> Unit>()
    private var spinReads = 0

    var sound: SoundSink = NoSound

    /** DOS character output (mode 0 print). */
    val console = mutableListOf<Int>()

    /** Assets loaded by statement 45h, in order. */
    val loaded = mutableListOf<String>()

    /** Statement 45h asked for this asset, loaded already or not. */
    var onAssetAsked: (String) -> Unit = {}

    /** `88h`: where the script asked the pointer to go (INT 33h 4). */
    var mouseRequest: Pair<Int, Int>? = null

    // Hooks filled by later components (renderer, palette).
    var fadeTick: () -> Unit = {}

    /** Real time per tick ([RealTimeClock]); none in tests and replays. */
    var tickPace: () -> Unit = {}

    /** Input posted by the host, applied on the tick ([Input]). */
    var inputTick: () -> Unit = {}
    var voicesTick: () -> Unit = {}

    var pointerTick: () -> Unit = {}

    /** `frame_wait` `3d3c` before the wait: `vdrv_screen_to_page` when `[0c4a]` is set. */
    var screenToPage: () -> Unit = {}

    /** `view_pass` (`3e55`): one shown view, `bx` its record, `cx` its object record. */
    var viewPass: (bx: Int, cx: Int) -> Unit = { _, _ -> }

    /** The pointer object in frame_wait: moved (state 2) or new (state ffh); `di` its record, `cx` `[0bee]`. */
    var pointerObjectMoved: (di: Int, cx: Int) -> Unit = { _, _ -> }
    var pointerObjectNew: (di: Int, cx: Int) -> Unit = { _, _ -> }

    /** `draw_dirty_rect` (`43fb`). */
    var drawDirtyRect: (di: Int, cx: Int) -> Unit = { _, _ -> }

    /** `7ch`: draw object record [di] and copy the dirty rectangle to the screen. */
    var drawObject: (di: Int) -> Unit = {}
    var copyDirtyRect: () -> Unit = {}

    /** `84h`/`85h`: the pointer drawn on / erased from `dseg:dofs`. */
    var pointerShow: (dofs: Int, dseg: Int) -> Unit = { _, _ -> }
    var pointerHide: (dofs: Int, dseg: Int) -> Unit = { _, _ -> }

    /** The player taps a touch screen: the pointer is not drawn (a carried item is an object, still drawn) ([Present.pointerShow]). */
    @Volatile
    var touch = false

    // --- Scheduler (2632) ---------------------------------------------------

    /** One game frame: frame_wait (draw), then every frame in list order until the list wraps to slot 0. */
    fun frame() {
        frameWait()
        runPass()
    }

    fun runPass() {
        spinReads = 0
        machine.es = machine.g(0x0BD6)
        var bx = 0
        while (true) {
            try {
                runSlot(bx)
            } catch (e: RescheduleException) {
                // the running frame destroyed itself: continue from [0b02]
            }
            bx = machine.rw(machine.es, machine.g(0x0BD2) + machine.g(0x0B02) + 4)
            machine.sg(0x0B02, bx)
            if (bx == 0) {
                frames++
                return
            }
        }
    }

    private fun runSlot(bx: Int) {
        val m = machine
        val es = m.es
        m.bp = m.rw(es, m.g(0x0BD2) + bx)
        val bp = m.bp
        m.sgb(0x0C96, 0)
        m.sgb(0x0C19, 0)
        val flags = m.rb(es, bp - 0x24)
        if ((flags and 0x80) != 0 && (flags and 2) == 0) {
            val (aofs, aseg) = m.far(es, bp - 0x14)
            val ev = m.rw(aseg, aofs + 0x0A)
            if (ev != 0) {
                m.ds = aseg
                m.si = (ev + aofs + 0x0A) and 0xFFFF
                m.sg(0x0C56, m.rw(es, bp - 0x0A))
                framePosSave()
                vmRun()
                m.ww(es, m.bp - 0x0A, m.g(0x0C56))
                framePosMoved()
            }
        }
        val al = m.rb(es, m.bp - 4)
        if (al == 0) return
        if ((al and 0x80) != 0) m.wb(es, m.bp - 4, 1)
        val c = (m.rb(es, m.bp - 1) - 1) and 0xFF
        m.wb(es, m.bp - 1, c)
        if (c != 0) return
        framePosSave()
        val (si, ds) = m.far(es, m.bp - 8)
        m.si = si
        m.ds = ds
        m.sgb(0x0C19, m.gb(0x0C19) + 1)
        vmRun()
        m.ww(es, m.bp - 8, m.si)
        val (aofs, aseg) = m.far(es, m.bp - 0x14)
        val tk = m.rw(aseg, aofs + 6)
        if (tk != 0) {
            m.ds = aseg
            m.sgb(0x0C19, 0)
            m.si = (tk + aofs + 6) and 0xFFFF
            m.sg(0x0C56, m.rw(es, m.bp - 0x0A))
            vmRun()
            m.ww(es, m.bp - 0x0A, m.g(0x0C56))
        }
        framePosMoved()
        m.wb(es, m.bp - 1, m.rb(es, m.bp - 2))
    }

    /** `script_vm_run` `26eb`: lodsb, call the statement handler, loop until a yield. */
    fun vmRun() {
        try {
            while (true) {
                val op = machine.lodsb()
                machine.ax = op * 2
                machine.bx = op * 2
                statements++
                trace?.invoke(op)
                if (interrupts) statementTime()
                val h = stmts[op] ?: throw UnsupportedEngineOperationException(
                    "statement ${ScriptExpr.hex2(op)} at ${ScriptExpr.hex4(machine.ds)}:${ScriptExpr.hex4(machine.si - 1)}",
                )
                h()
            }
        } catch (e: LeaveException) {
            // yield
        }
    }

    private fun framePosSave() {
        for (i in 0 until 4) machine.sg(0x0BFA + 2 * i, machine.rw(machine.es, machine.bp + 2 * i))
    }

    /** `3c97`: the position delta moves every object of the frame's list. */
    private fun framePosMoved() {
        val m = machine
        val es = m.es
        val bp = m.bp
        val ax = (m.rw(es, bp) - m.g(0x0BFA)) and 0xFFFF
        val cx = (m.rw(es, bp + 2) - m.g(0x0BFC)) and 0xFFFF
        val dx = (m.rw(es, bp + 4) - m.g(0x0BFE)) and 0xFFFF
        if (ax == 0 && cx == 0 && dx == 0) return
        val ods = m.g(0x0BEA)
        var di = m.rw(es, bp - 0x18)
        while (di != 0) {
            if (m.rb(ods, di) == 0) m.wb(ods, di, 2)
            m.ww(ods, di + 0x16, m.rw(ods, di + 0x16) + ax)
            m.ww(ods, di + 0x18, m.rw(ods, di + 0x18) + cx)
            m.ww(ods, di + 0x1A, m.rw(ods, di + 0x1A) + dx)
            di = m.rw(ods, di + 4)
        }
    }

    // --- frame_wait (3d3c), timing half -------------------------------------

    fun frameWait() {
        val m = machine
        val saved = intArrayOf(m.ax, m.bx, m.cx, m.dx, m.di, m.si, m.ds, m.es, m.bp)
        if (m.gb(0x0C4A) != 0 && m.gb(0x2898) != 0) {
            screenToPage()
            m.sgb(0x0C4A, 0)
        }
        waitTicks()
        m.sgb(0x0C9A, 0)
        for ((d, s) in FRAME_COPIES) m.sg(d, m.g(s))
        val es = m.g(0x0BD6)
        m.es = es
        var ax = m.g(0x0C16)
        while (ax != 0) {
            val bx = (m.g(0x0BF6) + ax) and 0xFFFF
            if ((m.rb(es, bx) and 0x40) == 0) {
                m.sg(0x0B32, bx)
                m.sg(0x0B34, es)
                val cx = m.rw(es, bx + 2)
                m.sg(0x0B18, cx)
                viewPass(bx, cx)
            }
            ax = m.rw(es, bx + 4)
        }
        m.sgb(0x0C49, 0)
        if (m.gb(0x0C37) == 0 && m.gb(0x0CB0) != 0) {
            m.sgb(0x0C18, 0)
            val o = m.g(0x0BEA)
            val di = (m.g(0x0BE8) + m.g(0x0CAE)) and 0xFFFF
            val cx = m.g(0x0BEE)
            when (m.rb(o, di)) {
                2 -> pointerObjectMoved(di, cx)
                0xFF -> pointerObjectNew(di, cx)
                1 -> {
                    m.wb(o, di, 0xFE)
                    drawDirtyRect(di, cx)
                }
            }
        }
        m.ax = saved[0]; m.bx = saved[1]; m.cx = saved[2]; m.dx = saved[3]; m.di = saved[4]
        m.si = saved[5]; m.ds = saved[6]; m.es = saved[7]; m.bp = saved[8]
    }

    // --- The 57.6 Hz tick (int8_handler 0d98) -------------------------------

    /** The tick count, the palette fade, the sound voices, the pointer; sound irq first. */
    fun tick() {
        sound.irq()
        tickPace()
        inputTick()
        ticks++
        while (events.isNotEmpty() && events[0].first <= ticks) {
            events.removeAt(0).second(this)
        }
        val c = machine.gb(0x0C9A)
        if (c != 0xFF) machine.sgb(0x0C9A, c + 1)
        if (machine.gb(0x2898) != 0 && machine.gb(0x0CB8) != 0) fadeTick()
        voicesTick()
        pointerTick()
    }

    /** Run [fn] [delay] ticks from now (the interrupt side: keys, mouse). */
    fun at(delay: Int, fn: (Scheduler) -> Unit) {
        val t = ticks + delay
        val i = events.indexOfFirst { it.first > t }.let { if (it < 0) events.size else it }
        events.add(i, t to fn)
    }

    /**
     * Scripts that wait for input without yielding spin in bytecode while the
     * interrupts keep running: one tick per 2000 statements stands in for the CPU time.
     */
    private fun statementTime() {
        busy++
        if (busy >= 2000) {
            busy = 0
            tick()
        }
    }

    /** frame_wait's wait: ticks until `[0c9a]` reaches the period `[0c99]`. */
    fun waitTicks() {
        if (!interrupts) return
        while (true) {
            tick()
            if (machine.gb(0x0C9A) >= machine.gb(0x0C99)) break
        }
    }

    // --- ScriptHost ---------------------------------------------------------

    /** `143c`: a key down, then up; runs on the interrupt clock. */
    override fun keyWait(): Int {
        var key = 0
        var wasTyped = false
        for (phase in 0..1) {
            var c = 0
            for (i in 0 until 200000) {
                c = machine.rb(CS, 0x0318)
                if (if (phase == 0) c != 0 else c == 0) break
                if (phase == 0 && typed.isNotEmpty()) { // text typed into a prompt
                    machine.wb(CS, 0x0318, typed.removeFirst())
                    wasTyped = true
                    continue
                }
                if (phase == 1 && (wasTyped || typed.isNotEmpty())) {
                    machine.wb(CS, 0x0318, 0) // a typed key is released at once
                    continue
                }
                if (!interrupts) throw UnsupportedEngineOperationException("key_wait with no input to come")
                tick()
            }
            if (phase == 0) key = c
        }
        machine.ax = key
        return key
    }

    override fun cpuSpeed(): Int {
        var v = machine.g(0x0C9F)
        if (machine.g(0x2894) != 2 && machine.g(0x2894) != 7) v = v * 2 / 3
        return v
    }

    override fun fileExists(name: String): Boolean {
        if (machine.build == GameBuild.ISHAR3) files.importFrom(Ishar3Start.importFrom(machine))
        return files.exists(name)
    }

    /** A prompt spins on the held key until it is released (sos.io 0921): the release arrives while it spins. */
    override fun heldKeyRead() {
        if (spinRelease.isEmpty()) return
        spinReads++
        if (spinReads >= 64) {
            spinReads = 0
            spinRelease.removeFirst()(this)
        }
    }

    companion object {
        /** A scheduler with every statement component, the renderer, the pointer and the palette installed. */
        fun withStatements(machine: Machine, files: GameFiles): Scheduler {
            val vm = Scheduler(machine, files)
            val stmts = ScriptStmts(vm).also { it.install() }
            Collide(vm, stmts).install()
            FileIo(vm).install()
            val renderer = Renderer(vm).also { it.install() }
            Present(vm, renderer).install()
            Palette(vm).install()
            vm.voicesTick = { SoundBlaster.voicesTick(machine) }
            return vm
        }

        /** frame_wait copies the draw target and viewport into the pass's working copy. */
        private val FRAME_COPIES = listOf(
            0x1DBF to 0x0B88, 0x1DC1 to 0x0B8A, 0x1DC3 to 0x1DCD, 0x1DC5 to 0x1DCF,
            0x1DC7 to 0x1DD1, 0x1DC9 to 0x1DD3, 0x1DCB to 0x1DD5,
        )
    }
}
