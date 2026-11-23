package com.auroratv.discmenu

import com.auroratv.core.Log
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Disk access for packs the app downloads itself. Packs placed by hand never carry the downloaded marker and are never deleted. */
interface PackStore {
    /** True when [path] exists in [folder] with exactly [size] bytes (a finished file; partial ones never have their final name). */
    fun hasFile(folder: String, path: String, size: Long): Boolean
    /** Starts (or resumes) a download: the folder is not listed as an installed pack until [finish]. */
    fun begin(folder: String)
    /** Fetches one file, checks its size and SHA-256, and only then gives it its final name. Throws if it doesn't match. */
    suspend fun fetch(http: HttpClient, url: String, headers: Map<String, String>, folder: String, file: ManifestFile, onBytes: (Long) -> Unit)
    fun finish(folder: String)
    /** Folders this app downloaded (complete or not). */
    fun downloadedFolders(): List<String>
    /** Removes a folder the app downloaded. Does nothing for a folder it didn't. */
    fun delete(folder: String)
}

sealed interface InstallState {
    /** [readyStage] is the highest stage fully on disk (see [PackManifest.readyStage]). */
    data class Running(val readyStage: Int, val doneBytes: Long, val totalBytes: Long) : InstallState {
        val percent: Int get() = if (totalBytes <= 0) 0 else (doneBytes * 100 / totalBytes).toInt().coerceIn(0, 100)
    }
    data object Complete : InstallState
    data class Failed(val readyStage: Int, val message: String) : InstallState
}

val InstallState.readyStage: Int
    get() = when (this) {
        is InstallState.Running -> readyStage
        InstallState.Complete -> PackManifest.ALL_STAGES
        is InstallState.Failed -> readyStage
    }

/**
 * Downloads a pack's files in stage order, skipping files that are already complete on disk (so an interrupted
 * download carries on where it stopped, to the file). The network part is [fetch], so this can be tested without one.
 */
class PackInstall(
    private val manifest: PackManifest,
    private val folder: String,
    private val store: PackStore,
    private val fetch: suspend (ManifestFile, (Long) -> Unit) -> Unit,
    private val retries: Int = 3,
    private val backoffMs: Long = 1_000L,
) {
    private val _state = MutableStateFlow<InstallState>(InstallState.Running(currentStage(), 0L, manifest.totalBytes))
    val state: StateFlow<InstallState> = _state.asStateFlow()

    private fun present(f: ManifestFile) = store.hasFile(folder, f.path, f.size)
    private fun currentStage() = manifest.readyStage(::present)

    suspend fun run() {
        store.begin(folder)
        var done = manifest.files.filter(::present).sumOf { it.size }
        _state.value = InstallState.Running(currentStage(), done, manifest.totalBytes)
        for (file in manifest.downloadOrder()) {
            if (present(file)) continue
            var attempt = 0
            while (true) {
                attempt++
                var inFile = 0L
                try {
                    fetch(file) { n ->
                        inFile = n
                        _state.value = InstallState.Running(currentStage(), done + inFile, manifest.totalBytes)
                    }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w("DiscMenu", "download of ${file.path} failed (attempt $attempt): ${e.message}")
                    if (attempt >= retries) {
                        _state.value = InstallState.Failed(currentStage(), "couldn't download ${file.path}: ${e.message ?: "unknown error"}")
                        return
                    }
                    delay(backoffMs * attempt)
                }
            }
            done += file.size
            _state.value = InstallState.Running(currentStage(), done, manifest.totalBytes)
        }
        store.finish(folder)
        _state.value = InstallState.Complete
    }
}
