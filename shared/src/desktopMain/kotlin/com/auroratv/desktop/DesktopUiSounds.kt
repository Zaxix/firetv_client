package com.auroratv.desktop

import com.auroratv.core.Log
import com.auroratv.ui.sound.UiSound
import com.auroratv.ui.sound.UiSoundOutput
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.FloatControl
import kotlin.math.log10

/** One pre-opened audio clip per sound (no output device, e.g. a headless machine: silently off). */
class DesktopUiSounds : UiSoundOutput {
    private val clips = HashMap<UiSound, Clip>()
    private var last: Clip? = null

    override fun load(sound: UiSound, bytes: ByteArray) {
        runCatching {
            val stream = AudioSystem.getAudioInputStream(bytes.inputStream().buffered())
            clips[sound] = AudioSystem.getClip().apply { open(stream) }
        }.onFailure { Log.i("UiSound", "No audio output for UI sounds: ${it.javaClass.simpleName}") }
    }

    override fun play(sound: UiSound, volume: Float) {
        val clip = clips[sound] ?: return
        last?.takeIf { it !== clip && it.isRunning }?.stop()
        (clip.getControl(FloatControl.Type.MASTER_GAIN) as? FloatControl)?.let { gain ->
            gain.value = (20f * log10(volume.coerceIn(0.001f, 1f))).coerceIn(gain.minimum, gain.maximum)
        }
        clip.stop(); clip.framePosition = 0; clip.start()
        last = clip
    }
}
