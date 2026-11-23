package com.auroratv.discmenu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap

/** Read access to one pack folder. Paths are relative to the folder, as written in `pack.json`. */
interface DiscMenuAssets {
    fun exists(path: String): Boolean
    fun readText(path: String): String
    fun readBytes(path: String): ByteArray
    /** Decodes an image (WebP/PNG) held in [bytes]; null when it can't be decoded. */
    fun decode(bytes: ByteArray, offset: Int, length: Int): ImageBitmap?
}

fun DiscMenuAssets.decode(path: String): ImageBitmap? = readBytes(path).let { decode(it, 0, it.size) }

/** Called by the media layer on the main thread. */
interface DiscMenuMediaListener {
    /** The intro's first picture reached the screen. */
    fun onIntroFirstPicture()
    /** The intro can't be played. */
    fun onIntroFailed()
    /** A video effect (transition, letter) could not start; the state machine's clock carries on without it. */
    fun onMediaProblem(what: String)
}

/**
 * Plays the menu's videos and music. One implementation per platform; everything it owns (decoders, surfaces, audio)
 * is released by [release], which the screen calls when it leaves composition.
 */
interface DiscMenuMedia {
    var listener: DiscMenuMediaListener?
    /** Whether the shared effect surface has a picture to show (it is kept invisible until the first frame is drawn). Observable by Compose. */
    val effectVisible: Boolean
    fun apply(effect: MenuEffect)
    fun pause()
    fun resume()
    fun release()

    /** The looping menu video's surface; always composed, the media layer keeps it black until the loop runs. */
    @Composable fun LoopSurface(modifier: Modifier)
    /** The surface the intro, the transition and the letter dissolve share. */
    @Composable fun EffectSurface(modifier: Modifier)
}

/** What the app asks of the platform for disc menus. Null where the platform has none. */
interface DiscMenuPlatform {
    /** Human-readable place packs are read from (shown when none is found). */
    val packsLocation: String
    /** Folder names under [packsLocation] that contain a `pack.json`. */
    fun listPacks(): List<String>
    fun assets(folder: String): DiscMenuAssets
    fun createMedia(folder: String, pack: DiscMenuPackDef): DiscMenuMedia
}
