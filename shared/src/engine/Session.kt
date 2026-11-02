package dev.pelotasplus.ishar.engine

/**
 * An input script in the log format of the live recordings: the boot's hardware answers and the input per frame, written where the
 * game's interrupt handlers write it. [Starts] holds the ones in use.
 *
 * Log lines are `FRAME kind args...`; frame 0 `hw cpu_speed`/`hw random_seed` are the boot
 * benchmark and the BIOS clock seed. Events at frame F are applied before frame F runs.
 */
class Session(
    val name: String,
    val cpuSpeed: Int,
    val randomSeed: Int,
    val events: Map<Int, List<Event>>,
) {
    sealed interface Event {
        data class KeyDown(val scancode: Int) : Event
        data class KeyUp(val scancode: Int) : Event
        data class Mouse(val x: Int, val y: Int) : Event
        data class Buttons(val buttons: Int) : Event

        /** The key is released once a script spins on it (a prompt waiting inside a frame). */
        data class SpinUp(val scancode: Int) : Event

        /** Characters for `key_wait` (prompts). */
        data class Type(val chars: List<Int>) : Event
        data class SlowMachine(val value: Int) : Event
        data class Poke(val seg: Int, val ofs: Int, val bytes: List<Int>) : Event
    }

    /** The last frame with input. */
    val lastEventFrame: Int get() = events.keys.maxOrNull() ?: 0

    /**
     * Boots from [files] with the session's hardware answers and runs main.io's first pass (frame 1).
     * [sound]: a start.stp sound letter in place of the file's ('B' for the Sound Blaster).
     */
    fun boot(files: GameFiles, machine: Machine = Machine(), sound: Char? = null, sink: ((Machine) -> SoundSink)? = null): Scheduler {
        Boot.boot(machine, files, cpuSpeed = cpuSpeed, randomSeed = randomSeed, soundOverride = sound)
        val vm = Scheduler.withStatements(machine, files)
        sink?.let { vm.sound = it(machine) }
        vm.interrupts = true
        vm.runPass()
        return vm
    }

    /** The input of [frame], then the frame. */
    fun step(vm: Scheduler, frame: Int) {
        events[frame]?.forEach { apply(vm, it) }
        vm.frame()
    }

    companion object {
        fun apply(vm: Scheduler, e: Event) {
            val m = vm.machine
            when (e) {
                is Event.KeyDown -> Boot.keyDown(m, e.scancode)
                is Event.KeyUp -> Boot.keyUp(m, e.scancode)
                is Event.Mouse -> Boot.mouse(m, e.x, e.y)
                is Event.Buttons -> Boot.mouse(m, buttons = e.buttons)
                is Event.SpinUp -> vm.spinRelease.addLast { Boot.keyUp(it.machine, e.scancode) }
                is Event.Type -> vm.typed.addAll(e.chars)
                is Event.SlowMachine -> m.sgb(0x2892, e.value)
                is Event.Poke -> e.bytes.forEachIndexed { i, b -> m.wb(e.seg, e.ofs + i, b) }
            }
        }

        fun parse(name: String, log: String): Session {
            val hw = mutableMapOf<String, Int>()
            val events = mutableMapOf<Int, MutableList<Event>>()
            for (line in log.lines()) {
                val w = line.trim().split(Regex("\\s+"))
                if (w.size < 2) continue
                val f = w[0].toInt()
                if (f == 0 && w[1] == "hw") {
                    hw[w[2]] = w[3].toInt(16)
                    continue
                }
                val e = when (w[1]) {
                    "key" -> if (w[2] == "down") Event.KeyDown(w[3].toInt(16)) else Event.KeyUp(w[3].toInt(16))
                    "mouse" -> Event.Mouse(w[2].toInt(), w[3].toInt())
                    "buttons" -> Event.Buttons(w[2].toInt())
                    "spinup" -> Event.SpinUp(w[2].toInt(16))
                    "type" -> Event.Type(w[2].chunked(2).map { it.toInt(16) })
                    "hw" -> if (w[2] == "slow_machine") Event.SlowMachine(w[3].toInt(16)) else unknown(line)
                    "poke" -> {
                        val (seg, ofs) = w[2].split(':').map { it.toInt(16) }
                        Event.Poke(seg, ofs, w[3].chunked(2).map { it.toInt(16) })
                    }
                    else -> unknown(line)
                }
                events.getOrPut(f) { mutableListOf() } += e
            }
            return Session(name, requireNotNull(hw["cpu_speed"]), requireNotNull(hw["random_seed"]), events)
        }

        private fun unknown(line: String): Nothing = throw IllegalArgumentException("unknown session event: $line")
    }
}
