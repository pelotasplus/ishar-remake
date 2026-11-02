package dev.pelotasplus.ishar.features.game

import kotlin.test.Test
import kotlin.test.assertEquals

/** [frameToImageBitmap]: the in-memory BMP decodes back to the same ARGB pixels. */
class CrtScreenTest {
    @Test
    fun `the BMP route keeps every pixel`() {
        val argb = IntArray(64000) { i -> (0xFF shl 24) or ((i * 2654435761L).toInt() and 0xFFFFFF) }
        val image = frameToImageBitmap(argb)
        assertEquals(320 to 200, image.width to image.height)
        val got = IntArray(64000)
        image.readPixels(got)
        assertEquals(argb.toList(), got.toList())
    }
}
