package dev.pelotasplus.ishar.features.game

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.decodeToImageBitmap

/** A 320x200 packed ARGB frame as an [ImageBitmap], through an in-memory 24-bit BMP (common code on every target). */
fun frameToImageBitmap(argb: IntArray, width: Int = 320, height: Int = 200): ImageBitmap {
    val row = width * 3
    val size = 54 + row * height
    val b = ByteArray(size)
    fun le(at: Int, v: Int, n: Int) {
        for (i in 0 until n) b[at + i] = (v ushr (8 * i)).toByte()
    }
    b[0] = 'B'.code.toByte()
    b[1] = 'M'.code.toByte()
    le(2, size, 4)
    le(10, 54, 4)
    le(14, 40, 4)
    le(18, width, 4)
    le(22, height, 4) // bottom-up
    le(26, 1, 2)
    le(28, 24, 2)
    le(34, row * height, 4)
    for (y in 0 until height) {
        var p = 54 + (height - 1 - y) * row
        for (x in 0 until width) {
            val c = argb[y * width + x]
            b[p++] = c.toByte()
            b[p++] = (c ushr 8).toByte()
            b[p++] = (c ushr 16).toByte()
        }
    }
    return b.decodeToImageBitmap()
}

/** The game screen at the 4:3 of a 1992 monitor: square VGA pixels stretched 1.2x vertically, kept sharp; [imageModifier] goes on the picture itself (input). */
@Composable
fun CrtScreen(argb: IntArray, modifier: Modifier = Modifier, imageModifier: Modifier = Modifier) {
    val image = remember(argb) { frameToImageBitmap(argb) }
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF101010))
            .padding(10.dp),
    ) {
        Image(
            bitmap = image,
            contentDescription = "Game screen",
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
            modifier = Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(4.dp)).then(imageModifier),
        )
    }
}
