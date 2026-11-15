package com.auroratv.update

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.platform.FileStore
import com.auroratv.settings.Settings
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable

/** Where builds are published: the rolling `latest-build` release of this repository. */
object UpdateChannel {
    const val REPO = "Zaxix/firetv_client"
    const val TAG = "latest-build"
    const val DOWNLOAD_BASE = "https://github.com/$REPO/releases/download/$TAG/"
    const val PAGE = "https://github.com/$REPO/releases/tag/$TAG"
}

@Serializable
data class ReleaseAsset(val sha256: String, val size: Long)

/** `version.json`, written by CI next to the builds. */
@Serializable
data class ReleaseInfo(
    val build: Int,
    val version: String,
    val commit: String = "",
    val publishedAt: String = "",
    /** Every CI job passed (tests, real server, emulator, Windows). Failing builds are never offered. */
    val ok: Boolean = false,
    val changes: List<String> = emptyList(),
    val assets: Map<String, ReleaseAsset> = emptyMap(),
)

/** What a platform does to update itself. */
interface AppUpdater {
    /** Build number of the running app (0 for local developer builds). */
    val currentBuild: Int
    val currentVersion: String

    /** The release file this install updates from, or null when it can't update itself (portable copy, Linux, debug build). */
    fun pickAsset(names: Collection<String>): String?

    /** A reason the install can't start yet (e.g. Android's "install unknown apps" switch), or null. */
    fun installBlocker(): String? = null

    /** Opens the system setting that clears [installBlocker]; false if there isn't one. */
    fun openInstallSettings(): Boolean = false

    /** Downloads [url] to a local file, checking size and SHA-256; returns the file path. */
    suspend fun download(http: HttpClient, url: String, asset: ReleaseAsset, onProgress: (Float) -> Unit): String

    /** Hands the verified file to the system installer. Windows closes the app to let the installer replace it. */
    suspend fun install(path: String)

    fun openUrl(url: String)
}

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: ReleaseInfo?) : UpdateState
    data class Available(val info: ReleaseInfo, val asset: String?) : UpdateState
    data class Downloading(val info: ReleaseInfo, val progress: Float) : UpdateState
    data class Installing(val info: ReleaseInfo) : UpdateState
    data class Failed(val message: String, val info: ReleaseInfo? = null) : UpdateState
}

@Serializable
private data class UpdatePrefs(val lastCheckMs: Long = 0, val snoozedBuild: Int = 0, val snoozedUntilMs: Long = 0)

/**
 * Checks for newer builds (at most once a day automatically, or on demand) and installs them.
 * Only builds whose CI run fully passed are offered, files are checked against the SHA-256 in
 * version.json, and Android additionally checks the APK's package and signing key before handing
 * it to the system installer, which always asks the user to confirm.
 */
class UpdateManager(
    private val http: HttpClient,
    val updater: AppUpdater?,
    private val files: FileStore,
    private val settings: () -> Settings,
    private val scope: CoroutineScope,
    private val now: () -> Long,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The update to offer in a prompt (null when there's nothing to offer or it was put off). */
    private val _prompt = MutableStateFlow<ReleaseInfo?>(null)
    val prompt: StateFlow<ReleaseInfo?> = _prompt.asStateFlow()

    private var job: Job? = null
    private var prefs = files.read(FILE)?.let { runCatching { AuroraJson.decodeFromString(UpdatePrefs.serializer(), it) }.getOrNull() } ?: UpdatePrefs()

    val supported get() = updater != null

    /** Automatic check: on start-up, if enabled and the last one was over 20 hours ago. */
    fun checkIfDue(): Job? {
        if (updater == null || !settings().autoCheckUpdates) return null
        if (prefs.lastCheckMs != 0L && now() - prefs.lastCheckMs < AUTO_INTERVAL_MS) return null
        return check(userAsked = false)
    }

    fun checkNow(): Job? = check(userAsked = true)

    private fun check(userAsked: Boolean): Job? {
        val u = updater ?: return null
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            _state.value = UpdateState.Checking
            val info = runCatching {
                withContext(Dispatchers.Default) {
                    withTimeout(15_000) {
                        val r = http.get(UpdateChannel.DOWNLOAD_BASE + "version.json") { parameter("t", now() / 60_000) }
                        if (!r.status.isSuccess()) error("HTTP ${r.status.value}")
                        AuroraJson.decodeFromString(ReleaseInfo.serializer(), r.bodyAsText())
                    }
                }
            }.getOrElse {
                Log.w(TAG, "Update check failed: ${it.message}")
                _state.value = UpdateState.Failed("Couldn't check (${it.message ?: it::class.simpleName}). Try again later.")
                return@launch
            }
            savePrefs(prefs.copy(lastCheckMs = now()))
            if (!info.ok || info.build <= u.currentBuild) {
                _state.value = UpdateState.UpToDate(info)
                return@launch
            }
            val asset = u.pickAsset(info.assets.keys)
            _state.value = UpdateState.Available(info, asset)
            val snoozed = !userAsked && prefs.snoozedBuild == info.build && now() < prefs.snoozedUntilMs
            if (userAsked || !snoozed) _prompt.value = info
            Log.i(TAG, "Update available: ${u.currentVersion} → ${info.version}")
        }.also { job = it }
    }

    /** "Later": don't prompt for this build again for a day (Settings → About still offers it). */
    fun later() {
        val info = _prompt.value ?: return
        _prompt.value = null
        savePrefs(prefs.copy(snoozedBuild = info.build, snoozedUntilMs = now() + AUTO_INTERVAL_MS))
    }

    fun install(): Job? {
        val u = updater ?: return null
        val current = _state.value
        val (info, assetName) = when (current) {
            is UpdateState.Available -> current.info to current.asset
            is UpdateState.Failed -> { val i = current.info ?: return null; i to u.pickAsset(i.assets.keys) }
            else -> return null
        }
        if (assetName == null) { u.openUrl(UpdateChannel.PAGE); return null }
        val asset = info.assets[assetName] ?: return null
        u.installBlocker()?.let { reason ->
            _state.value = UpdateState.Failed(reason, info)
            u.openInstallSettings()
            return null
        }
        job?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            _state.value = UpdateState.Downloading(info, 0f)
            runCatching {
                val path = u.download(http, UpdateChannel.DOWNLOAD_BASE + assetName, asset) { p -> _state.value = UpdateState.Downloading(info, p) }
                _state.value = UpdateState.Installing(info)
                u.install(path)
            }.onFailure {
                Log.w(TAG, "Update install failed: ${it.message}")
                _state.value = UpdateState.Failed(it.message ?: "The update couldn't be installed.", info)
            }
        }.also { job = it }
    }

    fun dismissPrompt() { _prompt.value = null }

    private fun savePrefs(p: UpdatePrefs) {
        prefs = p
        runCatching { files.write(FILE, AuroraJson.encodeToString(UpdatePrefs.serializer(), p)) }
    }

    private companion object {
        const val TAG = "Updates"
        const val FILE = "updates.json"
        const val AUTO_INTERVAL_MS = 20L * 60 * 60 * 1000
    }
}
