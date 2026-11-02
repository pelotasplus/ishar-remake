package dev.pelotasplus.ishar.control

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import dev.pelotasplus.ishar.features.game.GameViewModel
import dev.pelotasplus.ishar.features.game.SavesScreen
import org.jetbrains.skia.EncodedImageFormat

/**
 * The app's Saves screen drawn offscreen (MCP `ui_saves`, `ui_tap`): the same composable with the running app's view
 * model, so its rows are what the window shows and a tap runs the row's own click (loadSave), with no screen capture.
 */
class UiScene(private val game: GameViewModel) {
    private var scene: ImageComposeScene? = null
    /** Set by the screen's onPlay; read and reset under the lock, so a result is about its own tap. */
    private var played = false

    class Tap(val png: ByteArray, val played: Boolean)

    /** The Saves screen as a PNG, [width] x [height]. Synchronized: MCP calls run on a thread pool and share one scene. */
    @Synchronized
    fun saves(width: Int = 800, height: Int = 600): ByteArray {
        scene?.close()
        scene = null // if the new one fails, ui_tap says "ui_saves first" instead of touching a closed scene
        played = false
        scene = ImageComposeScene(width, height, Density(1f)) {
            MaterialTheme { SavesScreen(viewModel = game, onPlay = { played = true }) }
        }
        return render()
    }

    /** A tap at [x], [y] on that screen: the screen after it as a PNG, and whether this tap asked to play. */
    @Synchronized
    fun tap(x: Float, y: Float): Tap {
        val s = scene ?: error("ui_saves first")
        played = false
        s.sendPointerEvent(PointerEventType.Move, Offset(x, y))
        s.sendPointerEvent(PointerEventType.Press, Offset(x, y))
        s.sendPointerEvent(PointerEventType.Release, Offset(x, y))
        return Tap(render(), played)
    }

    private fun render(): ByteArray {
        val s = scene ?: error("no scene")
        s.render() // the first pass lays out; the second has the data
        return s.render().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }
}
