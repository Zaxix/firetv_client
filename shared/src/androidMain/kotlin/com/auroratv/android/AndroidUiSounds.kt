package com.auroratv.android

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.auroratv.ui.sound.UiSound
import com.auroratv.ui.sound.UiSoundOutput
import java.io.File

/**
 * SoundPool: decoded once into memory, plays with next to no latency. Played as UI sound effects
 * (sonification), so the TV's own volume/mute apply and media playback isn't ducked or paused.
 */
class AndroidUiSounds(private val context: Context) : UiSoundOutput {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = HashMap<UiSound, Int>()
    private val ready = HashSet<Int>()
    private var lastStream = 0

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(ready) { ready += id } }
    }

    override fun load(sound: UiSound, bytes: ByteArray) {
        // SoundPool loads from a path; the files are tiny and live in the app's cache.
        val file = File(context.cacheDir, "ui-${sound.name.lowercase()}.wav")
        if (!file.exists() || file.length() != bytes.size.toLong()) file.writeBytes(bytes)
        ids[sound] = pool.load(file.path, 1)
    }

    override fun play(sound: UiSound, volume: Float) {
        val id = ids[sound] ?: return
        if (synchronized(ready) { id !in ready }) return
        if (lastStream != 0) pool.stop(lastStream)
        lastStream = pool.play(id, volume, volume, 1, 0, 1f)
    }
}
