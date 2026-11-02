package dev.pelotasplus.ishar

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AudioOutputTest {
    /** A line that cannot open: the app goes on without sound, and can say why. */
    @Test
    fun `sound that cannot start says why and does no harm`() {
        val audio = AudioOutput(-1)
        val why = assertNotNull(audio.error)
        assertTrue(why.isNotBlank())
        audio.write(ShortArray(10))
        audio.pause()
        audio.resume()
        audio.close()
    }
}
