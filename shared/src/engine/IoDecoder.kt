package dev.pelotasplus.ishar.engine

import dev.pelotasplus.ishar.data.ByteReader

/**
 * Decodes Ishar `.io` containers (`asset_load_direct` `0000:78f9`,
 * `asset_load_catalogue` `0000:76bc`, `asset_lz_decode` `0000:7b85`).
 */
object IoDecoder {
    const val REFILL_BYTES = 0x1F40

    data class BootBlock(
        val raw: ByteArray,
        val assetTableEntries: Int,
        val frameSlots: Int,
        val heapLimit: Long,
        val frameMemoryBytes: Int,
        val reservedWord: Int,
        val objectRecords: Int,
    )

    data class ContainerHeader(
        val totalSize: Int,
        val mode: Int,
        val direct: Int,
        val headerLength: Int,
        val decodedSize: Int,
        val bootBlock: BootBlock?,
    ) {
        val isCompressed: Boolean get() = (mode and 0x80) != 0
        val isLz: Boolean get() = isCompressed && (mode and 0xF0) == 0xA0
        val isCatalogue: Boolean get() = direct == 0
    }

    data class DecodedContainer(
        val header: ContainerHeader,
        val bytes: ByteArray,
        val overrun: ByteArray,
    )

    /**
     * `asset_lz_read_bits` (`0000:7ceb`) over the 8,000-byte refill buffer
     * (`E000:0000` draw page). A refill past EOF copies only available bytes
     * and leaves older bytes in place (stale input).
     */
    private class PageBitReader(
        private val payload: ByteArray,
        private val buf: ByteArray,
    ) {
        private var fp: Int = 0
        private var di: Int = 8
        private var bits: Int = 0
        private var n: Int = 0

        init {
            refill()
        }

        private fun refill() {
            val avail = (payload.size - fp).coerceIn(0, REFILL_BYTES)
            if (avail > 0) {
                payload.copyInto(buf, destinationOffset = 0, startIndex = fp, endIndex = fp + avail)
            }
            fp += REFILL_BYTES
        }

        fun read(k: Int): Int {
            var v = 0
            repeat(k) {
                if (n == 0) {
                    if (di >= REFILL_BYTES) {
                        refill()
                        di = 0
                    }
                    val hi = buf[di].toInt() and 0xFF
                    val lo = buf[di + 1].toInt() and 0xFF
                    bits = (hi shl 8) or lo
                    di += 2
                    n = 16
                }
                v = (v shl 1) or ((bits ushr 15) and 1)
                bits = (bits shl 1) and 0xFFFF
                n -= 1
            }
            return v
        }
    }

    fun parseHeader(raw: ByteArray): ContainerHeader {
        require(raw.size >= 6) { "Container too short: ${raw.size}" }
        val totalSize = ByteReader.u24LE(raw, 0)
        val mode = ByteReader.u8(raw, 3) and 0xFE
        val direct = ByteReader.u16LE(raw, 4)
        val headerLength = if (direct != 0) 6 else 22
        require(raw.size >= headerLength) { "Container shorter than header: ${raw.size} < $headerLength" }
        val bootBlock = if (direct == 0) {
            val blockBytes = raw.copyOfRange(6, 22)
            BootBlock(
                raw = blockBytes,
                assetTableEntries = ByteReader.u16LE(blockBytes, 0),
                frameSlots = ByteReader.u16LE(blockBytes, 2),
                heapLimit = ByteReader.u32LE(blockBytes, 4),
                frameMemoryBytes = ByteReader.u16LE(blockBytes, 8),
                reservedWord = ByteReader.u16LE(blockBytes, 10),
                objectRecords = ByteReader.u16LE(blockBytes, 12),
            )
        } else {
            null
        }
        return ContainerHeader(
            totalSize = totalSize,
            mode = mode,
            direct = direct,
            headerLength = headerLength,
            decodedSize = totalSize - headerLength,
            bootBlock = bootBlock,
        )
    }

    /**
     * Decodes LZ payload (`0000:7b85`) using [pageBuf] (the first 8,000 bytes of
     * `E000:0000`, modified in place). Returns `(decodedBytes, overrunBytes)`.
     */
    fun decodeLz(
        payload: ByteArray,
        size: Int,
        pageBuf: ByteArray = ByteArray(REFILL_BYTES),
    ): Pair<ByteArray, ByteArray> {
        require(pageBuf.size >= REFILL_BYTES) { "pageBuf must be at least $REFILL_BYTES bytes" }
        val br = PageBitReader(payload, pageBuf)
        val widths = IntArray(8) { pageBuf[it].toInt() and 0xFF }
        var out = ByteArray(size + 64)
        var outLen = 0

        fun append(b: Int) {
            if (outLen >= out.size) {
                out = out.copyOf(out.size * 2)
            }
            out[outLen++] = (b and 0xFF).toByte()
        }

        while (outLen < size) {
            if (br.read(1) != 0) {
                var n = 1
                while (true) {
                    val v = br.read(2)
                    n += v
                    if (v != 3) break
                }
                repeat(n) {
                    append(br.read(8))
                }
                if (outLen >= size) break
            }
            val v = br.read(3)
            val w = widths[v]
            val n: Int
            val off: Int
            if ((v and 3) != 0) {
                n = (v and 3) + 1
                off = br.read(w)
            } else {
                off = br.read(w)
                var lenAcc = 4
                while (true) {
                    val k = br.read(3)
                    lenAcc += k
                    if (k != 7) break
                }
                n = lenAcc + 1
            }
            val src = outLen - off - 1
            for (i in 0 until n) {
                val idx = src + i
                append(if (idx >= 0) out[idx].toInt() and 0xFF else 0)
            }
        }
        val decoded = out.copyOfRange(0, size)
        val overrun = out.copyOfRange(size, outLen)
        return decoded to overrun
    }

    /**
     * The RLE containers (`0000:7a79`; Ishar 3 ships some, mode 80h): control c < 80h copies c literals, c >= 80h
     * repeats the next byte c & 7fh times. Pass p writes out[p], out[p + passes], ...
     */
    fun decodeRle(payload: ByteArray, outLen: Int, passes: Int): ByteArray {
        val out = ByteArray(outLen)
        var src = 0
        for (p in 0 until passes) {
            var di = p
            while (di < outLen) {
                val c = payload[src++].toInt() and 0xFF
                if (c and 0x80 != 0) {
                    val b = payload[src++]
                    repeat(c and 0x7F) {
                        if (di < outLen) out[di] = b
                        di += passes
                    }
                } else {
                    repeat(c) {
                        if (di < outLen) out[di] = payload[src]
                        src++
                        di += passes
                    }
                }
            }
        }
        return out
    }

    /**
     * Decodes a `.io` container. When [pageBuf] is supplied, the 8,000-byte
     * refill buffer is updated in place exactly as the DOS engine updates `E000:0000`.
     */
    fun decode(raw: ByteArray, pageBuf: ByteArray? = null): DecodedContainer {
        val header = parseHeader(raw)
        val body = raw.copyOfRange(header.headerLength, raw.size)
        val n = header.decodedSize
        if (header.isCompressed && !header.isLz) {
            val passes = if (header.mode == 0x80) 1 else 8
            return DecodedContainer(header = header, bytes = decodeRle(body, n, passes), overrun = ByteArray(0))
        }
        return if (header.isCompressed) {
            val buf = pageBuf ?: ByteArray(REFILL_BYTES)
            val (decoded, overrun) = decodeLz(body, n, buf)
            DecodedContainer(header = header, bytes = decoded, overrun = overrun)
        } else {
            DecodedContainer(
                header = header,
                bytes = body.copyOfRange(0, n),
                overrun = ByteArray(0),
            )
        }
    }
}
