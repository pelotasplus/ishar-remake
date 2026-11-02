package dev.pelotasplus.ishar.engine

/** the reference engine: the fuzz tests' shared sequence. */
class XorShift32(private var x: Long) {
    fun next(): Long {
        x = x xor ((x shl 13) and 0xFFFFFFFFL)
        x = x xor (x ushr 17)
        x = x xor ((x shl 5) and 0xFFFFFFFFL)
        return x
    }

    /** `next() % n` as the oracle takes it (the value is unsigned). */
    fun below(n: Int): Int = (next() % n).toInt()

    fun bits(): Int = next().toInt()
}
