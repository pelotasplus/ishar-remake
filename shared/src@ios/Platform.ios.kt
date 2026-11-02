package dev.pelotasplus.ishar

import kotlinx.cinterop.get
import kotlinx.cinterop.set
import platform.AVFAudio.setActive
import platform.UIKit.UIDevice

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun sleepMillis(ms: Long) {
    if (ms > 0) platform.posix.usleep((ms * 1000).toUInt())
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual class AudioOutput actual constructor(rate: Int) {
    private val engine = platform.AVFAudio.AVAudioEngine()
    private val player = platform.AVFAudio.AVAudioPlayerNode()
    private val format = platform.AVFAudio.AVAudioFormat(standardFormatWithSampleRate = rate.toDouble(), channels = 1u)
    private var failed = false

    actual val error: String? get() = if (failed) "the audio engine did not start" else null

    init {
        // Ambient: the silent switch silences it, and other apps' audio goes on
        platform.AVFAudio.AVAudioSession.sharedInstance().setCategory(platform.AVFAudio.AVAudioSessionCategoryAmbient, null)
        engine.attachNode(player)
        engine.connect(player, engine.mainMixerNode, format)
        running()
    }

    /** The system stops the engine on a call or a route change and does not start it again. */
    private fun running(): Boolean {
        if (engine.isRunning()) return true
        platform.AVFAudio.AVAudioSession.sharedInstance().setActive(true, null)
        val started = engine.startAndReturnError(null)
        failed = !started
        if (started) player.play()
        return started
    }

    actual fun write(pcm: ShortArray) {
        if (pcm.isEmpty() || !running()) return
        val buf = platform.AVFAudio.AVAudioPCMBuffer(pCMFormat = format, frameCapacity = pcm.size.toUInt())
        buf.frameLength = pcm.size.toUInt()
        val ch = buf.floatChannelData?.get(0) ?: return
        for (i in pcm.indices) ch[i] = pcm[i] / 32768f
        player.scheduleBuffer(buf, null)
    }

    actual fun resume() {
        if (running()) player.play()
    }

    actual fun pause() {
        player.pause()
    }

    actual fun close() {
        player.stop()
        engine.stop()
    }
}

actual fun startMuted() = platform.Foundation.NSProcessInfo.processInfo.environment["ISHAR_MUTE"] == "1"
