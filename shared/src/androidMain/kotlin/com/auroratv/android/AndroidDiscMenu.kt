package com.auroratv.android

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.auroratv.core.Log
import com.auroratv.discmenu.DiscMenuAssets
import com.auroratv.discmenu.DiscMenuMedia
import com.auroratv.discmenu.DiscMenuMediaListener
import com.auroratv.discmenu.DiscMenuPackDef
import com.auroratv.discmenu.DiscMenuPlatform
import com.auroratv.discmenu.MenuEffect
import java.io.File

private const val TAG = "DiscMenu"

/**
 * Disc menu packs live in the app's own folder on shared storage, `Android/data/<app id>/files/discmenus/<pack>/`,
 * which `adb push` and a file manager can write without any permission.
 */
class AndroidDiscMenuPlatform(private val activity: Activity) : DiscMenuPlatform {
    private val root: File get() = File(activity.getExternalFilesDir(null) ?: activity.filesDir, "discmenus")

    override val packsLocation: String get() = root.absolutePath

    override fun listPacks(): List<String> =
        root.listFiles()?.filter { File(it, "pack.json").isFile }?.map { it.name }?.sorted().orEmpty()

    override fun assets(folder: String): DiscMenuAssets = FileDiscMenuAssets(File(root, folder))

    override fun createMedia(folder: String, pack: DiscMenuPackDef): DiscMenuMedia = ExoDiscMenuMedia(activity, File(root, folder), pack)
}

class FileDiscMenuAssets(private val dir: File) : DiscMenuAssets {
    private fun file(path: String): File {
        val f = File(dir, path)
        // pack.json is data from outside the app: a path must not climb out of the pack's folder.
        require(f.canonicalPath.startsWith(dir.canonicalPath + File.separator)) { "path leaves the pack folder: $path" }
        return f
    }

    override fun exists(path: String): Boolean = runCatching { file(path).isFile }.getOrDefault(false)
    override fun readText(path: String): String = file(path).readText()
    override fun readBytes(path: String): ByteArray = file(path).readBytes()
    override fun decode(bytes: ByteArray, offset: Int, length: Int): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, offset, length)?.asImageBitmap()
}

/**
 * Three ExoPlayers, no more, so a Fire TV's few hardware decoders are enough:
 *  - the menu loop (a two-item playlist on repeat so the loop point is an item change, not a seek),
 *  - one for the intro, the transition and the letter, which never play together,
 *  - the music (audio only).
 * Everything is released in [release].
 */
@OptIn(UnstableApi::class)
class ExoDiscMenuMedia(private val activity: Activity, private val dir: File, private val pack: DiscMenuPackDef) : DiscMenuMedia {
    override var listener: DiscMenuMediaListener? = null
    override var effectVisible: Boolean by mutableStateOf(false)
        private set

    private enum class Effect { None, Intro, Transition, Letter }

    private val handler = Handler(Looper.getMainLooper())
    private var released = false
    private var effect = Effect.None
    private var pausedByLifecycle = false
    private var loopWasPlaying = false
    private var fxWasPlaying = false
    private var musicWasPlaying = false

    private fun item(rel: String) = MediaItem.fromUri(Uri.fromFile(File(dir, rel)))

    private fun newVideoPlayer(): ExoPlayer = ExoPlayer.Builder(activity)
        // Local files: a short buffer is plenty and keeps memory down.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 5_000, 250, 500).build())
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), /* handleAudioFocus = */ false)
        .build()

    private val loop: ExoPlayer = newVideoPlayer().apply {
        trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
        val loopItem = item(pack.video.loop.file)
        setMediaItems(listOf(loopItem, loopItem))
        repeatMode = Player.REPEAT_MODE_ALL
        seekTo(0, pack.video.loop.entryMs)
        playWhenReady = false
        addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { problem("the menu loop can't play", error) }
        })
        prepare()
    }

    private val fx: ExoPlayer = newVideoPlayer().apply {
        addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (released) return
                effectVisible = true
                if (effect == Effect.Intro) listener?.onIntroFirstPicture()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (released) return
                if (effect == Effect.Intro) { Log.w(TAG, "intro failed", error); listener?.onIntroFailed() }
                else problem("a menu video can't play", error)
            }
        })
    }

    private val music: ExoPlayer = ExoPlayer.Builder(activity)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), /* handleAudioFocus = */ false)
        .build().apply {
            val m = item(pack.music.file)
            setMediaItems(listOf(m, m))
            repeatMode = Player.REPEAT_MODE_ALL
            playWhenReady = false
            addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) { problem("the menu music can't play", error) }
            })
            prepare()
        }

    private var pendingMusicStart: Runnable? = null
    private var fade: Runnable? = null

    private fun problem(what: String, error: Throwable) {
        if (released) return
        Log.w(TAG, what, error)
        listener?.onMediaProblem(what)
    }

    // ---- effects ----------------------------------------------------------------------------------------------

    override fun apply(effect: MenuEffect) {
        if (released) return
        when (effect) {
            MenuEffect.StartIntro -> pack.video.intro?.let { playEffect(Effect.Intro, it.file, play = true) } ?: listener?.onIntroFailed()
            MenuEffect.StopIntro -> stopEffect(Effect.Intro)
            is MenuEffect.StartMainVideo -> { loop.seekTo(0, effect.fromMs); loop.playWhenReady = true }
            MenuEffect.StopMainVideo -> {
                loop.playWhenReady = false
                // The next entry shows its first picture at once instead of the frame the loop stopped on.
                loop.seekTo(0, pack.video.loop.entryMs)
            }
            is MenuEffect.StartMusic -> startMusic(effect.delayMs)
            MenuEffect.StopMusic -> stopMusic()
            is MenuEffect.FadeOutMusic -> fadeOutMusic(effect.startAfterMs, effect.durationMs)
            MenuEffect.PrepareTransition -> {
                if (this.effect == Effect.Intro) stopEffect(Effect.Intro)
                playEffect(Effect.Transition, pack.video.transition.file, play = false)
            }
            MenuEffect.StartTransition -> {
                if (this.effect != Effect.Transition) playEffect(Effect.Transition, pack.video.transition.file, play = true)
                else fx.playWhenReady = true
            }
            MenuEffect.StopTransition -> stopEffect(Effect.Transition)
            MenuEffect.StartLetter -> pack.video.letter?.let { playEffect(Effect.Letter, it.file, play = true) }
            MenuEffect.StopLetter -> stopEffect(Effect.Letter)
            else -> Unit // host-handled effects never reach the media layer
        }
    }

    private fun playEffect(kind: Effect, rel: String, play: Boolean) {
        effect = kind
        effectVisible = false
        fx.stop()
        fx.setMediaItem(item(rel))
        fx.repeatMode = Player.REPEAT_MODE_OFF
        fx.playWhenReady = play
        fx.prepare()
    }

    private fun stopEffect(kind: Effect) {
        if (effect != kind) return
        effect = Effect.None
        fx.stop()
        fx.clearMediaItems()
        effectVisible = false
    }

    private fun startMusic(delayMs: Long) {
        cancelMusicTimers()
        music.volume = 1f
        music.playWhenReady = false
        music.seekTo(0, 0L)
        if (delayMs <= 0L) { music.playWhenReady = true; return }
        val start = Runnable { if (!released) music.playWhenReady = true }
        pendingMusicStart = start
        handler.postDelayed(start, delayMs)
    }

    private fun stopMusic() {
        cancelMusicTimers()
        music.playWhenReady = false
        music.seekTo(0, 0L)
        music.volume = 1f
    }

    private fun cancelMusicTimers() {
        pendingMusicStart?.let(handler::removeCallbacks); pendingMusicStart = null
        fade?.let(handler::removeCallbacks); fade = null
    }

    private fun fadeOutMusic(startAfterMs: Long, durationMs: Long) {
        fade?.let(handler::removeCallbacks)
        val stepMs = 40L
        var elapsed = 0L
        val step = object : Runnable {
            override fun run() {
                if (released) return
                elapsed += stepMs
                val left = 1f - (elapsed.toFloat() / durationMs.coerceAtLeast(1L))
                if (left <= 0f) { stopMusic(); return }
                music.volume = left
                handler.postDelayed(this, stepMs)
            }
        }
        fade = step
        handler.postDelayed(step, startAfterMs)
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> pause()
            androidx.lifecycle.Lifecycle.Event.ON_START -> resume()
            else -> Unit
        }
    }

    init {
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(lifecycleObserver)
    }

    override fun pause() {
        if (released || pausedByLifecycle) return
        pausedByLifecycle = true
        loopWasPlaying = loop.playWhenReady; fxWasPlaying = fx.playWhenReady; musicWasPlaying = music.playWhenReady
        loop.playWhenReady = false; fx.playWhenReady = false; music.playWhenReady = false
    }

    override fun resume() {
        if (released || !pausedByLifecycle) return
        pausedByLifecycle = false
        loop.playWhenReady = loopWasPlaying; fx.playWhenReady = fxWasPlaying; music.playWhenReady = musicWasPlaying
    }

    override fun release() {
        if (released) return
        released = true
        (activity as? LifecycleOwner)?.lifecycle?.removeObserver(lifecycleObserver)
        handler.removeCallbacksAndMessages(null)
        loop.release(); fx.release(); music.release()
        effectVisible = false
    }

    // ---- surfaces -----------------------------------------------------------------------------------------------

    @Composable
    override fun LoopSurface(modifier: Modifier) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { if (!released) loop.setVideoTextureView(it) } },
            modifier = modifier,
            onRelease = { tv -> if (!released) loop.clearVideoTextureView(tv) },
        )
    }

    @Composable
    override fun EffectSurface(modifier: Modifier) {
        AndroidView(
            factory = { ctx -> TextureView(ctx).also { if (!released) fx.setVideoTextureView(it) } },
            modifier = modifier,
            onRelease = { tv -> if (!released) fx.clearVideoTextureView(tv) },
        )
    }
}
