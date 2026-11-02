package dev.pelotasplus.ishar.engine

/**
 * Ishar 3's cinematics: Autodesk FLI files inside an asset's entries (intro1.io holds PCINTR11.FLI). start.exe's
 * player: f6h (8075) opens one (header magic af11h, d894) and keeps its frame count; b5h (80bc -> d74a) decodes the
 * next frames (magic f1fah, d8b4) into the 320x200 draw page, waiting its delay in timer ticks between them. Chunks
 * (d8d6): 0bh palette (64 levels), 0ch line delta, 0dh black, 0fh RLE frame, 10h raw frame. At the end it rewinds
 * and reports 8004h.
 */
class Fli(private val m: Machine) {
    private var start = 0
    private var end = 0
    private var pos = 0
    var frames = 0
        private set
    var frame = 0
        private set
    var delay = 0
        private set

    /** The picture the frames build on (the engine also draws on its page between them). */
    private val canvas = ByteArray(64000)

    /** Opens FLI number [index] of the asset at linear [asset]; false when there is none. */
    fun open(asset: Int, index: Int, delay: Int): Boolean {
        this.delay = delay
        var found = -1
        var p = asset
        val limit = minOf(asset + 0xA0000, m.m.size - 128)
        while (p < limit) {
            if (u16(p + 4) == 0xAF11 && u16(p + 8) == 320 && u16(p + 10) == 200) {
                found++
                if (found == index) {
                    start = p
                    end = p + u32(p)
                    pos = p + 128
                    frames = u16(p + 6)
                    frame = 0
                    canvas.fill(0)
                    return true
                }
                p += maxOf(u32(p), 128)
                continue
            }
            p++
        }
        frames = 0
        return false
    }

    /** Decodes [n] frames into [page] and [screen]; false once it ran out (then rewound, as d813 does). */
    fun step(n: Int, page: Int, screen: Int): Boolean {
        repeat(maxOf(n, 1)) {
            if (frames == 0 || pos >= end || u16(pos + 4) != 0xF1FA) {
                pos = start + 128
                return false
            }
            val next = pos + u32(pos)
            var c = pos + 16
            repeat(u16(pos + 6)) {
                if (c >= next) return@repeat
                val size = u32(c)
                chunk(u16(c + 4), c + 6)
                c += size
            }
            pos = next
            frame++
        }
        canvas.copyInto(m.m, page shl 4)
        canvas.copyInto(m.m, screen shl 4)
        return true
    }

    private fun chunk(type: Int, d: Int) {
        val c = canvas
        when (type) {
            0x0B -> palette(d)
            0x0C -> { // line delta (d96b): first line, lines; per line packets of skip, signed count
                var s = d
                var line = u16(s); s += 2
                val lines = u8(s); s += 2 // a word, of which d980 keeps the low byte
                repeat(lines) {
                    var o = line * 320
                    val packets = u8(s); s++
                    repeat(packets) {
                        o += u8(s); s++
                        val k = u8(s).toByte().toInt(); s++
                        if (k > 0) {
                            repeat(k) { if (o < 64000) c[o] = m.m[s]; o++; s++ }
                        } else {
                            val v = m.m[s++]
                            repeat(-k) { if (o < 64000) c[o] = v; o++ }
                        }
                    }
                    line++
                }
            }
            0x0D -> c.fill(0)
            0x0F -> { // RLE frame (d9ce): per line a packet count, signed counts (positive fills)
                var s = d
                var o = 0
                repeat(200) {
                    val row = o
                    val packets = u8(s); s++
                    repeat(packets) {
                        val k = u8(s).toByte().toInt(); s++
                        if (k >= 0) {
                            val v = m.m[s++]
                            repeat(k) { if (o < 64000) c[o] = v; o++ }
                        } else {
                            repeat(-k) { if (o < 64000) c[o] = m.m[s]; o++; s++ }
                        }
                    }
                    o = row + 320
                }
            }
            0x10 -> m.m.copyInto(c, 0, d, d + 64000)
        }
    }

    /** 0bh (d936): packets of a skip and a count (0 = 256) of 6-bit RGB triples, to the palette and the DAC. */
    private fun palette(d: Int) {
        var s = d
        val n = u16(s); s += 2
        var i = 0
        val ss = m.ss shl 4
        repeat(n) {
            i += u8(s); s++
            var count = u8(s); s++
            if (count == 0) count = 256
            repeat(count * 3) {
                if (i * 3 + it < 0x300) {
                    val v = m.m[s + it]
                    m.m[ss + 0x0E43 + i * 3 + it] = v
                    m.m[ss + 0x1143 + i * 3 + it] = v
                    m.m[ss + 0x1443 + i * 3 + it] = v
                }
            }
            s += count * 3
            i += count
        }
    }

    private fun u8(a: Int) = m.m[a].toInt() and 0xFF
    private fun u16(a: Int) = u8(a) or (u8(a + 1) shl 8)
    private fun u32(a: Int) = u16(a) or (u16(a + 2) shl 16)
}
