package dev.pelotasplus.ishar

import android.os.Build

class AndroidPlatform : Platform {
    override val name: String =
        runCatching { "Android ${Build.VERSION.SDK_INT}" }.getOrDefault("Android")
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun sleepMillis(ms: Long) {
    if (ms > 0) Thread.sleep(ms)
}

actual class AudioOutput actual constructor(rate: Int) {
    private val opening = runCatching {
        android.media.AudioTrack.Builder()
            .setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_GAME)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                android.media.AudioFormat.Builder()
                    .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(rate / 4 * 2)
            .setTransferMode(android.media.AudioTrack.MODE_STREAM)
            .build()
            .apply { play() }
    }
    private val track: android.media.AudioTrack? = opening.getOrNull()

    actual val error: String? = opening.exceptionOrNull()?.let { it.message ?: it.toString() }

    actual fun write(pcm: ShortArray) {
        track?.write(pcm, 0, pcm.size)
    }

    actual fun resume() {
        track?.play()
    }

    actual fun pause() {
        track?.pause()
    }

    actual fun close() {
        track?.run {
            stop()
            release()
        }
    }
}

/** `am start ... --ez mute true`, so a test run plays nothing at whoever holds the phone. */
var androidStartMuted = false

actual fun startMuted() = androidStartMuted
