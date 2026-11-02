@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package dev.pelotasplus.ishar

import dev.pelotasplus.ishar.engine.AudioQueue

class WasmPlatform: Platform {
    override val name: String = "Web with Kotlin/Wasm"
}

actual fun getPlatform(): Platform = WasmPlatform()

actual fun sleepMillis(ms: Long) {
    if (ms > 0) Unit
}

private fun audioContext(rate: Int): JsAny? =
    js("(() => { try { return new AudioContext({ sampleRate: rate }); } catch (e) { return null; } })()")

private fun audioBuffer(ctx: JsAny, n: Int, rate: Int): JsAny = js("ctx.createBuffer(1, n, rate)")

private fun audioSet(buf: JsAny, i: Int, v: Double) {
    js("buf.getChannelData(0)[i] = v")
}

private fun audioStart(ctx: JsAny, buf: JsAny, at: Double) {
    js("(() => { const s = ctx.createBufferSource(); s.buffer = buf; s.connect(ctx.destination); s.start(at); })()")
}

private fun audioRunning(ctx: JsAny): Boolean = js("ctx.state === 'running'")

private fun audioNow(ctx: JsAny): Double = js("ctx.currentTime")

private fun audioResume(ctx: JsAny) {
    js("ctx.resume()")
}

private fun audioSuspend(ctx: JsAny) {
    js("ctx.suspend()")
}

private fun audioClose(ctx: JsAny) {
    js("ctx.close()")
}

actual class AudioOutput actual constructor(private val rate: Int) {
    private val ctx = audioContext(rate)
    private val queue = AudioQueue()

    actual val error: String? = if (ctx == null) "this browser has no Web Audio" else null

    actual fun write(pcm: ShortArray) {
        val c = ctx ?: return
        if (pcm.isEmpty()) return
        val at = queue.place(audioRunning(c), audioNow(c), pcm.size.toDouble() / rate) ?: return
        val buf = audioBuffer(c, pcm.size, rate)
        for (i in pcm.indices) audioSet(buf, i, pcm[i] / 32768.0)
        audioStart(c, buf, at)
    }

    actual fun resume() {
        ctx?.let { audioResume(it) }
    }

    actual fun pause() {
        ctx?.let { audioSuspend(it) }
    }

    actual fun close() {
        ctx?.let { audioClose(it) }
    }
}

actual fun startMuted() = false
