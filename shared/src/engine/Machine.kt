package dev.pelotasplus.ishar.engine

/**
 * Memory and registers of the Ishar engine.
 *
 * Keeps the executable's data at its original DOS addresses, those of [build]
 * (Ishar 1 below; Ishar 2 moved them, [GameBuild]):
 * - Engine globals in [ss] (`0x0D88`, `seg_0c0b`, `DS = SS`)
 * - Code segment at `CS = 0x017D` (`seg_0000`: held key `cs:0318`, mouse `cs:11bd..11c1`)
 * - VGA driver at `0x1014` (`seg_0e97`)
 * - Frames and script globals in the frame segment (`0x126B`)
 * - Objects in the object area `[0be8]:[0bea]` (`0x1848`)
 * - Draw page at `PAGE = 0xE000`
 * - Screen at `SCREEN = 0xA000`
 */
class Machine(
    val m: ByteArray = ByteArray(MEMORY_SIZE),
    val build: GameBuild = GameBuild.ISHAR1,
) {
    /** The engine globals' segment. */
    val ss: Int = build.ss

    /** The VGA screen's segment: A000, or F000 for Ishar 3 ([GameBuild.screen]). */
    val screen: Int = build.screen

    var ax: Int = 0

    /**
     * Ishar 3's pointer arrays (declared with count bit 7): the array's place (its linear address) to where its
     * elements live, an offset in the same segment (statement e9h, ScriptStmts.allocPointerArray).
     */
    val pointerArrays = mutableMapOf<Int, Int>()

    /** The next free byte for [pointerArrays] (Boot: right after the frame memory) and the end of that room. */
    var pointerArrayNext = 0
    var pointerArrayEnd = 0
    var bx: Int = 0
    var cx: Int = 0
    var dx: Int = 0
    var si: Int = 0
    var di: Int = 0
    var bp: Int = 0
    var ds: Int = 0
    var es: Int = 0

    // --- Memory access ------------------------------------------------------

    fun rb(seg: Int, ofs: Int): Int =
        m[(seg shl 4) + (ofs and 0xFFFF)].toInt() and 0xFF

    fun rsb(seg: Int, ofs: Int): Int =
        m[(seg shl 4) + (ofs and 0xFFFF)].toInt()

    fun rw(seg: Int, ofs: Int): Int {
        val a = (seg shl 4) + (ofs and 0xFFFF)
        return (m[a].toInt() and 0xFF) or ((m[a + 1].toInt() and 0xFF) shl 8)
    }

    fun rsw(seg: Int, ofs: Int): Int =
        rw(seg, ofs).toShort().toInt()

    fun wb(seg: Int, ofs: Int, v: Int) {
        m[(seg shl 4) + (ofs and 0xFFFF)] = (v and 0xFF).toByte()
    }

    fun ww(seg: Int, ofs: Int, v: Int) {
        val a = (seg shl 4) + (ofs and 0xFFFF)
        m[a] = (v and 0xFF).toByte()
        m[a + 1] = ((v ushr 8) and 0xFF).toByte()
    }

    // --- Engine globals (SS) --------------------------------------------------

    fun g(ofs: Int): Int = rw(ss, ofs)

    fun gs(ofs: Int): Int = rsw(ss, ofs)

    fun gb(ofs: Int): Int = m[(ss shl 4) + (ofs and 0xFFFF)].toInt() and 0xFF

    fun gsb(ofs: Int): Int = m[(ss shl 4) + (ofs and 0xFFFF)].toInt()

    fun sg(ofs: Int, v: Int) {
        ww(ss, ofs, v)
    }

    fun sgb(ofs: Int, v: Int) {
        m[(ss shl 4) + (ofs and 0xFFFF)] = (v and 0xFF).toByte()
    }

    // --- Script pointer (DS:SI) ---------------------------------------------

    fun lodsb(): Int {
        val v = m[(ds shl 4) + (si and 0xFFFF)].toInt() and 0xFF
        si = (si + 1) and 0xFFFF
        ax = (ax and 0xFF00) or v
        return v
    }

    fun lodsw(): Int {
        val a = (ds shl 4) + (si and 0xFFFF)
        val v = (m[a].toInt() and 0xFF) or ((m[a + 1].toInt() and 0xFF) shl 8)
        si = (si + 2) and 0xFFFF
        ax = v
        return v
    }

    // --- Far pointers & huge-pointer normalisation --------------------------

    /** Returns `(ofs, seg)` stored as two little-endian words at `seg:ofs`. */
    fun far(seg: Int, ofs: Int): Pair<Int, Int> =
        rw(seg, ofs) to rw(seg, ofs + 2)

    /** Follow a self-relative u32 at `seg:ofs` -> normalised `(seg, ofs)`. */
    fun entry(seg: Int, ofs: Int): Pair<Int, Int> =
        norm(seg, ofs, rw(seg, ofs), rw(seg, ofs + 2))

    /** Copy a NUL-terminated string; returns `(dofs, sofs)` past the NUL. */
    fun strcpy(dseg: Int, dofs: Int, sseg: Int, sofs: Int): Pair<Int, Int> {
        var d = dofs and 0xFFFF
        var s = sofs and 0xFFFF
        while (true) {
            val c = rb(sseg, s)
            wb(dseg, d, c)
            s = (s + 1) and 0xFFFF
            d = (d + 1) and 0xFFFF
            if (c == 0) return d to s
        }
    }

    companion object {
        const val MEMORY_SIZE = 0x110000
        const val CS = 0x017D
        const val PAGE = 0xE000
        const val SCREEN = 0xA000

        /**
         * The game's huge-pointer step: `SEG:OFS + u32(HI:LO)`, normalised to
         * `OFS & 0fh` (`add / jnb / inc / xchg / shl 4`).
         * Returns `(normSeg, normOfs)`.
         */
        fun norm(seg: Int, ofs: Int, lo: Int, hi: Int): Pair<Int, Int> {
            var s = (ofs and 0xFFFF) + (lo and 0xFFFF)
            var h = hi and 0xFFFF
            if (s > 0xFFFF) {
                h = (h + 1) and 0xFFFF
            }
            s = s and 0xFFFF
            val hx = ((((h and 0xFF) shl 8) or (h ushr 8)) shl 4) and 0xFFFF
            val outSeg = (seg + hx + (s ushr 4)) and 0xFFFF
            val outOfs = s and 0x0F
            return outSeg to outOfs
        }

        /**
         * `idiv` of the 32-bit `DX:AX` pattern `num` by the signed word `d`,
         * emulating `divide_error_handler` (`0000:0e47`): on overflow it shifts
         * `DX:AX` right by 2 (`SAR` when `sar = true`, `SHR` when `sar = false`)
         * and retries until the signed 16-bit quotient fits.
         * Returns `(quotient, remainder)` as unsigned 16-bit words.
         */
        fun idiv32(num: Long, d: Int, sar: Boolean): Pair<Int, Int> {
            var cur = num and 0xFFFFFFFFL
            val divisor = s16(d).toLong()
            if (divisor == 0L) {
                throw UnsupportedEngineOperationException("divide by zero (INT 0 hang)")
            }
            while (true) {
                val n = if ((cur and 0x80000000L) != 0L) cur - (1L shl 32) else cur
                var q = kotlin.math.abs(n) / kotlin.math.abs(divisor)
                if ((n < 0L) != (divisor < 0L)) {
                    q = -q
                }
                if (q in -0x8000L..0x7FFFL) {
                    val rem = n - q * divisor
                    return (q.toInt() and 0xFFFF) to (rem.toInt() and 0xFFFF)
                }
                cur = if (sar) {
                    (n shr 2) and 0xFFFFFFFFL
                } else {
                    (cur ushr 2) and 0xFFFFFFFFL
                }
            }
        }

        /**
         * `div r/m8` with the same INT 0 retry: `AX` shifted right by 1 per fault.
         * Returns `(quotient, remainder)`.
         */
        fun div8(ax: Int, d: Int, sar: Boolean): Pair<Int, Int> {
            var cur = ax and 0xFFFF
            val divisor = d and 0xFF
            if (divisor == 0) {
                throw UnsupportedEngineOperationException("divide by zero in div8")
            }
            while (true) {
                val q = cur / divisor
                val r = cur % divisor
                if (q <= 0xFF) {
                    return q to r
                }
                cur = if (sar) {
                    (s16(cur) shr 1) and 0xFFFF
                } else {
                    cur ushr 1
                }
            }
        }

        fun s16(v: Int): Int = (v and 0xFFFF).toShort().toInt()

        fun s8(v: Int): Int = (v and 0xFF).toByte().toInt()
    }
}

/** The frequent ones are single objects: scripts throw them all the time, and building a stack trace each time cost 17% of the app's CPU. */
open class EngineControlFlowException(message: String? = null) : RuntimeException(message)

/** `add sp,2 / ret` from a statement: leaves `script_vm_run` (`yield` 42h). */
data object LeaveException : EngineControlFlowException()

/** Expression 3ah: `add sp,2 / ret` ends the 38h group loop. */
data object GroupEndException : EngineControlFlowException()

/** `frame_destroy` of the running frame: `add sp,4 / jmp script_scheduler`. */
data object RescheduleException : EngineControlFlowException()

/** 44h in frame 0 (`loc_25e1`): the program ends. */
class ProgramEndException : EngineControlFlowException()

class UnsupportedEngineOperationException(message: String) : RuntimeException(message)
