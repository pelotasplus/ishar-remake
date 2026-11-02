package dev.pelotasplus.ishar

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

/** Blocks the calling thread (the engine's, never the UI's); a no-op where threads cannot block (web). */
expect fun sleepMillis(ms: Long)

/** `ISHAR_MUTE=1` in the environment (desktop, iOS simulator): the game starts muted. */
expect fun startMuted(): Boolean

/** The platform's audio output: 16-bit mono PCM at [rate], written as the engine produces it. */
expect class AudioOutput(rate: Int) {
    /** Why no sound can play, or null. The game goes on without it. */
    val error: String?

    fun write(pcm: ShortArray)

    /** Web: audio may only start after a user gesture. Also ends a [pause], and brings back what the system stopped. */
    fun resume()

    /** The app is out of sight: what is queued waits. */
    fun pause()

    fun close()
}
