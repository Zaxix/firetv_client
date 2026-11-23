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
    /** Disc-style film menus found on this device (none unless a pack has been copied there). */
    val discMenus = com.auroratv.discmenu.DiscMenuRepository(platform.discMenus, platform.files)
    val http = createJellyfinHttpClient()
    val settings = SettingsRepository(platform.files)
    val sessions = SessionManager(http, platform.files, platform.vault, platform.info.deviceName)
    /** Optional MDBList / Trakt keys (secure vault, synced through the user's Jellyfin profile). */
    val ratingsKeys = com.auroratv.ratings.RatingsKeyStore(platform.vault)
    private fun today() = com.auroratv.profiles.epochDay(com.auroratv.playback.wallClockMs())
    /** Ratings remembered for a week, per profile. */
    val ratingsStore = com.auroratv.profiles.RatingsStore(platform.files, ::today)
    val ratings = com.auroratv.ratings.RatingsService(http, ratingsStore) { ratingsKeys.keys.value }
    /** When upcoming episodes air (detail pages), per series in memory (see AirDateService). */
    val airDates = com.auroratv.data.AirDateService(appScope, ratings)
    /** Detected skip markers for episodes in progress, per profile (deleted once watched). */
    val skipMarkers = com.auroratv.profiles.SkipMarkerStore(platform.files, ::today)
    /** Recent searches, per profile. */
    val searchHistory = com.auroratv.profiles.SearchHistory(platform.files)
    /** The on-device title index behind search (per signed-in user, refreshed every 30 minutes). */
    val search = com.auroratv.search.SearchService(appScope)
    /** Home's "Because You Watched" / "Recommended For You" rows, per profile (see RecommendationService). */
    val recommendations = com.auroratv.recommend.RecommendationService(appScope, platform.files)
    /** Home's rows as last shown, per profile (this session only; see HomeSnapshot). */
    /** "Top 10 in Your Home" (most watched by this device's profiles on the server). */
    val top10 = com.auroratv.recommend.HouseholdTop10()
    val homeMemory = LinkedHashMap<String, com.auroratv.ui.screens.HomeSnapshot>()
    fun rememberHome(profileKey: String, snapshot: com.auroratv.ui.screens.HomeSnapshot) {
        homeMemory.remove(profileKey); homeMemory[profileKey] = snapshot
        // Only the profiles used most recently: the rows of a profile nobody's using aren't kept.
        while (homeMemory.size > 2) homeMemory.remove(homeMemory.keys.first())
    }
    /** Prepares what Play will start while a detail page is open (see PlaybackPrewarmer). */
    /** Audio/subtitle choices remembered per series (on this device). */
    val trackMemory = com.auroratv.playback.TrackMemory(platform.files, { com.auroratv.playback.wallClockMs() })
    /** When saved file starts (temporary playback files) are deleted; see EpisodeTempFiles. */
    val tempFiles = com.auroratv.playback.EpisodeTempFiles(platform.files, platform.disposableCache(), appScope)
    val prewarmer = com.auroratv.playback.PlaybackPrewarmer(appScope, trackMemory) { p ->
        // A font-heavy MKV (anime release) the viewer is part-way through: fetch its start too, so
        // Resume doesn't wait for it. Only for resumes (likely to be played), never for titles just
        // passing through the banner, to keep the server's disk and network quiet.
        if (p.startMs > 0) com.auroratv.playback.MediaHeader.cacheKey(p.plan)?.let { key ->
            sessions.activeSession?.profile?.key?.let { profile -> tempFiles.fetchedAhead(key, p.item.id, profile) }
            platform.prefetchMediaHeader(p.plan.url, p.plan.headers, key)
        }
    }
    /** In-app updates from the latest-build release. */
    val updates = com.auroratv.update.UpdateManager(http, platform.updater, platform.files, { settings.current }, appScope, { com.auroratv.playback.wallClockMs() })
    /** Studio/network brands found in the library (Home's brand tiles). */
    val brands = com.auroratv.brands.BrandService(appScope)
    /** Settings page to open next time Settings is shown (e.g. "Ratings" from a detail page hint). */
    var pendingSettingsPage: String? = null

    /** A change asked for at launch (e.g. `--es player mpv`), re-applied once a profile's own settings load. */
    /** Where disc menu packs are downloaded from, or null when no source is set. The token (for a private host) lives in the vault. */
    fun discMenuSource(): com.auroratv.discmenu.PackSource? =
        settings.current.discMenuSource.takeIf { it.isNotBlank() }
            ?.let { com.auroratv.discmenu.PackSource(it, platform.vault.get(DISC_MENU_TOKEN_KEY).orEmpty()) }
            ?.takeIf { it.valid }

    fun saveDiscMenuToken(token: String) { if (token.isBlank()) platform.vault.remove(DISC_MENU_TOKEN_KEY) else platform.vault.put(DISC_MENU_TOKEN_KEY, token.trim()) }
    fun discMenuToken(): String = platform.vault.get(DISC_MENU_TOKEN_KEY).orEmpty()

    var launchSettingsOverride: ((com.auroratv.settings.Settings) -> com.auroratv.settings.Settings)? = null

    init {
        // Episode orders: automatic ones follow the setting; TMDB is asked with the open profile's key.
        sessions.orderAuto = { settings.current.autoEpisodeOrder }
        sessions.orderSource = com.auroratv.jellyfin.TmdbEpisodeGroups(http) { ratingsKeys.keys.value.tmdb }
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
            // Menu packs the app downloaded last time (the app was closed or killed inside a menu) are removed unless kept.
            if (!settings.current.discMenuKeepDownloads) runCatching { discMenus.purgeDownloads() }
            Log.i("Profiles", "Opened profile ${profile.displayName}")
        }
        sessions.onProfileRemoved = { profile ->
            com.auroratv.profiles.ProfileFiles.deleteAll(platform.files, profile.key)
            platform.files.delete("home-cache-${profile.server.id}-${profile.user.id}${profile.local?.let { "-p${it.id}" } ?: ""}.json")
            ratingsKeys.forgetProfile(profile.key)
            recommendations.forget(profile.key)
            homeMemory.remove(profile.key)
            tempFiles.profileRemoved(profile.key)
        }
        // Once per start, after Home has settled: delete temporary files whose 5 days are up.
        appScope.launch { kotlinx.coroutines.delay(TEMP_SWEEP_DELAY_MS); tempFiles.sweep() }
    }

    /** Starts preparing [itemId] for playback in the background (main thread; no-op if already prepared). */
    /** [known]: the item with its media details, when the caller already has it (saves asking for it again). */
    fun prewarmPlayback(api: com.auroratv.jellyfin.JellyfinApi, itemId: String, known: com.auroratv.jellyfin.model.BaseItemDto? = null) {
        appScope.launch { prewarmer.prewarm(api, capabilities(), settings.current, itemId, known) }
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
     * Optional deep link applied once after sign-in, e.g. from `--es route detail:<itemId>` (CI).
     * Formats: `section:<Home|Movies|Shows|Continue|Favorites|Search|Libraries|Settings>`, `detail:<id>`, `player:<id>`.
     */
    var initialRoute: String? = null
    /** A link delivered while the app is already open (see MainActivity.onNewIntent); same formats. */
    val links = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Set by the UI; platform back buttons route through here. Returns false when the app should close. */
    var backHandler: () -> Boolean = { false }
}

/** Delay before the once-per-start temporary file sweep (after Home has loaded). */
private const val TEMP_SWEEP_DELAY_MS = 20_000L
private const val DISC_MENU_TOKEN_KEY = "discmenu.source.token"
