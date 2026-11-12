package com.auroratv.platform

import androidx.compose.runtime.Composable
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.playback.PlaybackEngine

/** Small key-value text storage in the app's private data directory. */
interface FileStore {
    fun read(name: String): String?
    fun write(name: String, content: String)
    fun delete(name: String)
}

/** Encrypted storage for secrets (access tokens). Android Keystore / Windows DPAPI. */
interface SecureVault {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    /** Human-readable description shown in Settings → About, e.g. "Android Keystore (AES-GCM)". */
    val description: String
}

data class PlatformInfo(
    /** e.g. "Android TV", "Fire TV", "Windows" */
    val platformName: String,
    /** Device model shown in the Jellyfin dashboard, e.g. "AFTKA" or "DESKTOP-1234". */
    val deviceName: String,
    val osVersion: String,
    val isTv: Boolean,
    /** Cache directory for artwork (absolute path). */
    val cacheDir: String,
)

/**
 * Everything platform-specific the shared app needs. Implemented in androidMain and desktopMain and
 * passed into [com.auroratv.ui.AuroraApp].
 */
interface Platform {
    val info: PlatformInfo
    val files: FileStore
    val vault: SecureVault

    /** Probes the decoders, display and audio output of this device. May be slow; call off the UI thread. */
    suspend fun probeCapabilities(): DeviceCapabilities

    fun createEngine(settings: com.auroratv.settings.Settings): PlaybackEngine

    /**
     * Hosts the player. Android draws video and controls in one view tree; Windows places the mpv
     * video window behind a transparent controls window. [controls] is the shared Compose player UI.
     */
    @Composable
    fun PlayerHost(engine: PlaybackEngine, controls: @Composable () -> Unit)

    /** Desktop only: toggles fullscreen "TV mode". No-op on TV. */
    fun toggleFullscreen() {}

    /** Dismisses any on-screen keyboard (Android TV's keyboard otherwise keeps capturing the D-pad). */
    fun hideKeyboard() {}

    fun exitApp()
}
