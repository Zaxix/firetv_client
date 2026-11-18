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

/** Encrypted storage for secrets (access tokens): the Android Keystore. */
interface SecureVault {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    /** Human-readable description shown in Settings → About, e.g. "Android Keystore (AES-GCM)". */
    val description: String
}

data class PlatformInfo(
    /** e.g. "Android TV", "Fire TV" */
    val platformName: String,
    /** Device model shown in the Jellyfin dashboard, e.g. "AFTKA". */
    val deviceName: String,
    val osVersion: String,
    val isTv: Boolean,
    /** Cache directory for artwork (absolute path). */
    val cacheDir: String,
)

/**
 * Everything platform-specific the shared app needs. Implemented in androidMain (Fire TV / Android
 * TV, the only app) and passed into [com.auroratv.ui.AuroraApp].
 */
interface Platform {
    val info: PlatformInfo
    val files: FileStore
    val vault: SecureVault

    /** Probes the decoders, display and audio output of this device. May be slow; call off the UI thread. */
    suspend fun probeCapabilities(): DeviceCapabilities

    fun createEngine(settings: com.auroratv.settings.Settings): PlaybackEngine

    /** Self-update support (null where the app can't update itself). */
    val updater: com.auroratv.update.AppUpdater? get() = null

    /**
     * Whether text fields take typing as soon as they're highlighted (a computer keyboard). On TVs a
     * highlighted field waits for Select, so the on-screen keyboard never traps the remote.
     */
    val typesOnFocus: Boolean get() = false

    /**
     * Downloads the start of a font-heavy MKV (up to its first video cluster) into the device's
     * media cache ahead of playback; see MediaHeader. No-op where there's no such cache.
     */
    fun prefetchMediaHeader(url: String, headers: Map<String, String>, cacheKey: String) {}

    /** The cache of saved file starts, for EpisodeTempFiles to clean up; null where there's none. */
    fun disposableCache(): com.auroratv.playback.DisposableCache? = null

    /** Low-latency player for the bundled UI sounds, or null where there's none. */
    fun uiSoundOutput(): com.auroratv.ui.sound.UiSoundOutput? = null

    /** "api=N images=N media=N": requests made to the server since launch, or null where not counted. */
    fun requestStats(): String? = null

    /** Whether a VPN is carrying this device's traffic (for clearer "can't reach the server" help). */
    fun vpnActive(): Boolean = false

    /** Whether the user can pick between players (Android 8+ offers ExoPlayer and MPV). */
    val offersPlayerChoice: Boolean get() = false

    /**
     * What the engine chosen in [settings] can play, given the probed device [base]. The default is
     * [base]; Android widens it when mpv is selected (FFmpeg decodes more audio and containers).
     */
    fun capabilitiesFor(base: DeviceCapabilities, settings: com.auroratv.settings.Settings): DeviceCapabilities = base

    /**
     * Hosts the player: video and controls in one view tree. [controls] is the shared Compose player UI.
     */
    @Composable
    fun PlayerHost(engine: PlaybackEngine, onClose: () -> Unit, controls: @Composable () -> Unit)

    /** Dismisses any on-screen keyboard (Android TV's keyboard otherwise keeps capturing the D-pad). */
    fun hideKeyboard() {}

    fun exitApp()
}
