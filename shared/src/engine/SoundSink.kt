package dev.pelotasplus.ishar.engine

/** Where the sound statements hand off to a card. */
interface SoundSink {
    enum class Music { PARAM, PLAY, FADE }

    /** `9dh`/`9eh`: start the sample record at `seg:ofs` for frame handle [owner]. */
    fun play(seg: Int, ofs: Int, owner: Int) {}

    fun stop() {}

    fun music(kind: Music, vararg args: Int) {}

    /** `6bh`/`6dh`: a tone or a decay with the parameters in `0cc7..0cd3`. */
    fun voice() {}

    /** Called first on every tick (with a tune, the music driver's interrupt calls the tick). */
    fun irq() {}
}

/**
 * No sound card, or the AdLib / speaker paths (not rebuilt): the game keeps only
 * the statement state.
 */
object NoSound : SoundSink
