package com.auroratv.ui

import com.auroratv.core.AppError
import com.auroratv.core.Log
import com.auroratv.data.SessionManager
import com.auroratv.jellyfin.createJellyfinHttpClient
import com.auroratv.platform.Platform
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Application-wide singletons. Created once per process by the platform entry point. */
class AppContainer(val platform: Platform) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val http = createJellyfinHttpClient()
    val settings = SettingsRepository(platform.files)
    val sessions = SessionManager(http, platform.files, platform.vault, platform.info.deviceName)

    private val capsLock = Mutex()
    private var caps: DeviceCapabilities? = null

    /** Probed once per launch (decoder lists and HDR/audio output don't change mid-session). */
    suspend fun capabilities(): DeviceCapabilities = capsLock.withLock {
        caps ?: withContext(Dispatchers.Default) { platform.probeCapabilities() }.also {
            caps = it
            Log.i("Capabilities", "${it.engineName}: video=${it.video.map { v -> "${v.codec}(${v.maxWidth}x${v.maxHeight},${v.maxBitDepth}bit,hw=${v.hardware})" }} hdr=${it.hdr} audio=${it.audioDecode} passthrough=${it.audioPassthrough}")
        }
    }

    /** Central error hook: expired sessions send the user back to sign-in from anywhere. */
    fun onError(error: Throwable) {
        if (error is AppError.SessionExpired) sessions.onSessionExpired()
    }

    /**
     * Optional deep link applied once after sign-in, e.g. from `--route detail:<itemId>` on desktop.
     * Formats: `section:<Home|Movies|Shows|Continue|Favorites|Search|Libraries|Settings>`, `detail:<id>`, `player:<id>`.
     */
    var initialRoute: String? = null

    /** Set by the UI; platform back buttons route through here. Returns false when the app should close. */
    var backHandler: () -> Boolean = { false }
}
