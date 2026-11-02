package dev.pelotasplus.ishar

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()

actual fun sleepMillis(ms: Long) {
    if (ms > 0) Thread.sleep(ms)
}

actual class AudioOutput actual constructor(rate: Int) {
    private val opening = runCatching {
        val format = javax.sound.sampled.AudioFormat(rate.toFloat(), 16, 1, true, false)
        javax.sound.sampled.AudioSystem.getSourceDataLine(format).apply {
            open(format, rate / 4 * 2) // a quarter second
            start()
        }
    }
    private val line: javax.sound.sampled.SourceDataLine? = opening.getOrNull()

    actual val error: String? = opening.exceptionOrNull()?.let { it.message ?: it.toString() }

    actual fun write(pcm: ShortArray) {
        val l = line ?: return
        val b = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            b[2 * i] = pcm[i].toInt().toByte()
            b[2 * i + 1] = (pcm[i].toInt() shr 8).toByte()
        }
        l.write(b, 0, b.size)
    }

    actual fun resume() {
        line?.start()
    }

    actual fun pause() {
        line?.stop()
    }

    actual fun close() {
        line?.run {
            stop()
            close()
        }
    }
}

actual fun startMuted() = System.getenv("ISHAR_MUTE") == "1"
