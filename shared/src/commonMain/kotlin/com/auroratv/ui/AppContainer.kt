package com.auroratv.ui

import com.auroratv.core.AppError
import com.auroratv.core.Log
import com.auroratv.data.SessionManager
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.createJellyfinHttpClient
import com.auroratv.platform.Platform
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.playback.SkipDetector
import com.auroratv.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Application-wide singletons. Created once per process by the platform entry point. */
class AppContainer(val platform: Platform) {
    /** When the app started (for the "Perf" timings logged on the way to a usable screen). */
    val startMark = kotlin.time.TimeSource.Monotonic.markNow()
    private var homeReadyLogged = false
    private var homeFreshLogged = false
    fun logHomeReady(how: String = "from the server") {
        val ms = startMark.elapsedNow().inWholeMilliseconds
        if (!homeReadyLogged) { homeReadyLogged = true; Log.i("Perf", "Home ready in $ms ms ($how)") }
        if (how == "from the server" && !homeFreshLogged) { homeFreshLogged = true; Log.i("Perf", "Home rows from the server in $ms ms") }
    }
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    val http = createJellyfinHttpClient()
    val settings = SettingsRepository(platform.files)
    val sessions = SessionManager(http, platform.files, platform.vault, platform.info.deviceName)
    /** Optional MDBList / Trakt keys (secure vault, synced through the user's Jellyfin profile). */
    val ratingsKeys = com.auroratv.ratings.RatingsKeyStore(platform.vault)
    private fun today() = com.auroratv.profiles.epochDay(com.auroratv.playback.wallClockMs())
    /** Ratings remembered for a week, per profile. */
    val ratingsStore = com.auroratv.profiles.RatingsStore(platform.files, ::today)
    val ratings = com.auroratv.ratings.RatingsService(http, ratingsStore) { ratingsKeys.keys.value }
    /** Detected skip markers for episodes in progress, per profile (deleted once watched). */
    val skipMarkers = com.auroratv.profiles.SkipMarkerStore(platform.files, ::today)
    /** Recent searches, per profile. */
    val searchHistory = com.auroratv.profiles.SearchHistory(platform.files)
    /** The on-device title index behind search (per signed-in user, refreshed every 30 minutes). */
    val search = com.auroratv.search.SearchService(appScope)
    /** Home's "Because You Watched" / "Recommended For You" rows, per profile (see RecommendationService). */
    val recommendations = com.auroratv.recommend.RecommendationService(appScope, platform.files)
    /** Prepares what Play will start while a detail page is open (see PlaybackPrewarmer). */
    /** Audio/subtitle choices remembered per series (on this device). */
    val trackMemory = com.auroratv.playback.TrackMemory(platform.files, { com.auroratv.playback.wallClockMs() })
    val prewarmer = com.auroratv.playback.PlaybackPrewarmer(appScope, trackMemory) { p ->
        // A font-heavy MKV (anime release) the viewer is part-way through: fetch its start too, so
        // Resume doesn't wait for it. Only for resumes (likely to be played), never for titles just
        // passing through the banner, to keep the server's disk and network quiet.
        if (p.startMs > 0) com.auroratv.playback.MediaHeader.cacheKey(p.plan)?.let { key -> platform.prefetchMediaHeader(p.plan.url, p.plan.headers, key) }
    }
    /** In-app updates from the latest-build release. */
    val updates = com.auroratv.update.UpdateManager(http, platform.updater, platform.files, { settings.current }, appScope, { com.auroratv.playback.wallClockMs() })
    /** Studio/network brands found in the library (Home's brand tiles). */
    val brands = com.auroratv.brands.BrandService(appScope)
    /** Settings page to open next time Settings is shown (e.g. "Ratings" from a detail page hint). */
    var pendingSettingsPage: String? = null

    /** A change asked for at launch (e.g. `--es player mpv`), re-applied once a profile's own settings load. */
    var launchSettingsOverride: ((com.auroratv.settings.Settings) -> com.auroratv.settings.Settings)? = null

    init {
        // Opening a profile loads its own settings and local data before any screen reads them.
        sessions.onActivate = { profile ->
            val key = profile.key
            settings.switchProfile(key)
            launchSettingsOverride?.let { settings.update(it) }
            trackMemory.switchProfile(key)
            ratingsKeys.switchProfile(key)
            ratingsStore.switchProfile(key)
            skipMarkers.switchProfile(key)
            searchHistory.switchProfile(key)
            prewarmer.invalidate()
            Log.i("Profiles", "Opened profile ${profile.displayName}")
        }
        sessions.onProfileRemoved = { profile ->
            com.auroratv.profiles.ProfileFiles.deleteAll(platform.files, profile.key)
            platform.files.delete("home-cache-${profile.server.id}-${profile.user.id}${profile.local?.let { "-p${it.id}" } ?: ""}.json")
            ratingsKeys.forgetProfile(profile.key)
            recommendations.forget(profile.key)
        }
    }

    /** Starts preparing [itemId] for playback in the background (main thread; no-op if already prepared). */
    fun prewarmPlayback(api: com.auroratv.jellyfin.JellyfinApi, itemId: String) {
        appScope.launch { prewarmer.prewarm(api, capabilities(), settings.current, itemId) }
    }

    private var detector: Pair<JellyfinApi, SkipDetector>? = null

    /** One detector per signed-in server, so its AniList/MAL lookups stay cached between episodes. */
    fun skipDetector(api: JellyfinApi): SkipDetector =
        detector?.takeIf { it.first === api }?.second
            ?: SkipDetector(api, http, audioAllowed = { settings.current.audioSkipDetection }, onlineAllowed = { settings.current.onlineSkipTimes }).also { detector = api to it }

    private val capsLock = Mutex()
    private var caps: DeviceCapabilities? = null

    /**
     * Probed once per launch (decoder lists and HDR/audio output don't change mid-session), then
     * adjusted for the player engine chosen in Settings, which can change at any time.
     */
    suspend fun capabilities(): DeviceCapabilities {
        val base = capsLock.withLock {
            caps ?: withContext(Dispatchers.Default) { platform.probeCapabilities() }.also {
                caps = it
                Log.i("Capabilities", "${it.engineName}: video=${it.video.map { v -> "${v.codec}(${v.maxWidth}x${v.maxHeight},${v.maxBitDepth}bit,hw=${v.hardware})" }} hdr=${it.hdr} audio=${it.audioDecode} passthrough=${it.audioPassthrough}")
            }
        }
        return platform.capabilitiesFor(base, settings.current)
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
