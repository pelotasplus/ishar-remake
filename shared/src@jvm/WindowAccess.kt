package dev.pelotasplus.ishar.control

import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer
import java.awt.Component
import java.awt.Container
import java.awt.EventQueue
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/** The real app window for MCP: its pixels and clicks posted to its event queue. */
internal object WindowAccess {
    private fun layer(c: Component): SkiaLayer? = when (c) {
        is SkiaLayer -> c
        is Container -> c.components.firstNotNullOfOrNull(::layer)
        else -> null
    }

    fun shot(w: Window): ByteArray {
        var png: ByteArray? = null
        EventQueue.invokeAndWait {
            val bmp = (layer(w) ?: error("no Skia layer")).screenshot() ?: error("no screenshot")
            png = Image.makeFromBitmap(bmp).encodeToData(EncodedImageFormat.PNG)?.bytes
        }
        return png ?: error("no pixels")
    }

    fun click(w: Window, x: Int, y: Int, holdMs: Long): String {
        val l = layer(w) ?: error("no Skia layer")
        lateinit var target: Component
        var p = java.awt.Point(x, y)
        EventQueue.invokeAndWait {
            // the screenshot is in the layer's pixels; events go to the deepest component under that point
            val scale = l.contentScale
            p = java.awt.Point((x / scale).toInt(), (y / scale).toInt())
            target = SwingUtilities.getDeepestComponentAt(l, p.x, p.y) ?: l
            p = SwingUtilities.convertPoint(l, p, target)
        }
        val q = Toolkit.getDefaultToolkit().systemEventQueue
        fun post(id: Int, mods: Int, count: Int) {
            val t = System.currentTimeMillis()
            q.postEvent(MouseEvent(target, id, t, mods, p.x, p.y, count, false, MouseEvent.BUTTON1))
        }
        post(MouseEvent.MOUSE_MOVED, 0, 0)
        Thread.sleep(250) // the scripts look at the pointer a few frames before the button
        post(MouseEvent.MOUSE_PRESSED, InputEvent.BUTTON1_DOWN_MASK, 1)
        Thread.sleep(holdMs)
        post(MouseEvent.MOUSE_RELEASED, 0, 1)
        post(MouseEvent.MOUSE_CLICKED, 0, 1)
        return "clicked ${target.javaClass.simpleName} at ${p.x},${p.y}"
    }

    /** Mouse-wheel [notches] (positive scrolls down) at x, y (window_shot pixels). */
    fun scroll(w: Window, x: Int, y: Int, notches: Int): String {
        val l = layer(w) ?: error("no Skia layer")
        lateinit var target: Component
        var p = java.awt.Point(x, y)
        EventQueue.invokeAndWait {
            val scale = l.contentScale
            p = java.awt.Point((x / scale).toInt(), (y / scale).toInt())
            target = SwingUtilities.getDeepestComponentAt(l, p.x, p.y) ?: l
            p = SwingUtilities.convertPoint(l, p, target)
        }
        val q = Toolkit.getDefaultToolkit().systemEventQueue
        q.postEvent(MouseEvent(target, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0, p.x, p.y, 0, false))
        repeat(kotlin.math.abs(notches)) {
            q.postEvent(java.awt.event.MouseWheelEvent(target, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, p.x, p.y, 0, false,
                java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, if (notches > 0) 1 else -1))
            Thread.sleep(30)
        }
        return "scrolled $notches"
    }

    /** A key press and release handed to the Skia layer's key listeners, as AWT does for the focused window
     *  (posting to the queue would need the window to hold the OS focus). */
    fun key(w: Window, key: String, holdMs: Long): String {
        val l = layer(w) ?: error("no Skia layer")
        val code = if (key.length == 1) java.awt.event.KeyEvent.getExtendedKeyCodeForChar(key[0].code)
        else java.awt.event.KeyEvent::class.java.getField("VK_" + key.uppercase()).getInt(null)
        val ch = if (key.length == 1) key[0] else java.awt.event.KeyEvent.CHAR_UNDEFINED
        var n = 0
        fun send(id: Int, c: Int, chr: Char) = EventQueue.invokeAndWait {
            generateSequence<Component>(l) { (it as? Container)?.components?.firstOrNull() }.forEach { comp ->
                val e = java.awt.event.KeyEvent(comp, id, System.currentTimeMillis(), 0, c, chr)
                comp.keyListeners.forEach { k ->
                    n++
                    when (id) {
                        java.awt.event.KeyEvent.KEY_PRESSED -> k.keyPressed(e)
                        java.awt.event.KeyEvent.KEY_RELEASED -> k.keyReleased(e)
                        else -> k.keyTyped(e)
                    }
                }
            }
        }
        send(java.awt.event.KeyEvent.KEY_PRESSED, code, ch)
        if (key.length == 1) send(java.awt.event.KeyEvent.KEY_TYPED, java.awt.event.KeyEvent.VK_UNDEFINED, ch)
        Thread.sleep(holdMs)
        send(java.awt.event.KeyEvent.KEY_RELEASED, code, ch)
        return "key $key to $n listeners"
    }
}
