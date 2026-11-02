package dev.pelotasplus.ishar.data

class ByteReader(
    private val data: ByteArray,
    position: Int = 0,
) {
    var position: Int = position
        private set

    val size: Int
        get() = data.size

    fun hasMore(): Boolean = position < data.size

    fun remaining(): Int = (data.size - position).coerceAtLeast(0)

    fun seek(newPosition: Int) {
        require(newPosition in 0..data.size) { "Seek out of bounds: $newPosition (size=${data.size})" }
        position = newPosition
    }

    fun skip(count: Int) {
        seek(position + count)
    }

    fun readU8(): Int {
        check(position < data.size) { "Unexpected EOF at $position (size=${data.size})" }
        return data[position++].toInt() and 0xFF
    }

    fun readI8(): Int {
        check(position < data.size) { "Unexpected EOF at $position (size=${data.size})" }
        return data[position++].toInt()
    }

    fun readU16LE(): Int {
        val lo = readU8()
        val hi = readU8()
        return lo or (hi shl 8)
    }

    fun readI16LE(): Int = readU16LE().toShort().toInt()

    fun readU16BE(): Int {
        val hi = readU8()
        val lo = readU8()
        return (hi shl 8) or lo
    }

    fun readI16BE(): Int = readU16BE().toShort().toInt()

    fun readU24LE(): Int {
        val b0 = readU8()
        val b1 = readU8()
        val b2 = readU8()
        return b0 or (b1 shl 8) or (b2 shl 16)
    }

    fun readU32LE(): Long {
        val lo = readU16LE().toLong()
        val hi = readU16LE().toLong()
        return lo or (hi shl 16)
    }

    fun readU32BE(): Long {
        val hi = readU16BE().toLong()
        val lo = readU16BE().toLong()
        return (hi shl 16) or lo
    }

    fun readBytes(count: Int): ByteArray {
        require(count >= 0 && position + count <= data.size) {
            "Cannot read $count bytes at $position (size=${data.size})"
        }
        val slice = data.copyOfRange(position, position + count)
        position += count
        return slice
    }

    fun readAscii(count: Int): String {
        val bytes = readBytes(count)
        val end = bytes.indexOfFirst { it == 0.toByte() }.let { if (it == -1) bytes.size else it }
        return buildString(end) {
            for (i in 0 until end) {
                append((bytes[i].toInt() and 0xFF).toChar())
            }
        }
    }

    companion object {
        fun u8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF
        fun i8(data: ByteArray, offset: Int): Int = data[offset].toInt()
        fun u16LE(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        fun i16LE(data: ByteArray, offset: Int): Int = u16LE(data, offset).toShort().toInt()
        fun u16BE(data: ByteArray, offset: Int): Int =
            ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
        fun i16BE(data: ByteArray, offset: Int): Int = u16BE(data, offset).toShort().toInt()
        fun u24LE(data: ByteArray, offset: Int): Int =
            (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16)
        fun u32LE(data: ByteArray, offset: Int): Long =
            (u16LE(data, offset).toLong() and 0xFFFFL) or
                ((u16LE(data, offset + 2).toLong() and 0xFFFFL) shl 16)
        fun u32BE(data: ByteArray, offset: Int): Long =
            ((u16BE(data, offset).toLong() and 0xFFFFL) shl 16) or
                (u16BE(data, offset + 2).toLong() and 0xFFFFL)
    }
}
