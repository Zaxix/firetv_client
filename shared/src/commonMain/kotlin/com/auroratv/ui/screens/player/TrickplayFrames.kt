package com.auroratv.ui.screens.player

import androidx.compose.ui.graphics.ImageBitmap
import coil3.disk.DiskCache
import com.auroratv.core.Log
import com.auroratv.ui.theme.decodeImageRegion
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Scrub-preview frames cut straight out of Jellyfin's trickplay sprite sheets.
 *
 * A sheet is a grid of up to 100 thumbnails (3200×1800 px for 320 px thumbnails, about 23 MB once
 * decoded). Instead of decoding whole sheets to show one tile, the compressed sheet is kept on disk
 * (and the last few in memory) and only the needed tile is decoded, a ~230 KB bitmap that takes a
 * few milliseconds. Prefetching just downloads the sheets; nothing is decoded until you scrub.
 */
class TrickplayFrames(private val http: HttpClient, private val disk: DiskCache?) {
    private val lock = Mutex()
    private val sheets = LinkedHashMap<String, ByteArray>()
    private val frames = LinkedHashMap<String, ImageBitmap>()

    /** An already-decoded frame (cheap; for showing it without a frame of delay). */
    suspend fun cached(url: String, col: Int, row: Int): ImageBitmap? = lock.withLock { frames.touch(frameKey(url, col, row)) }

    /** The tile at ([col], [row]) of the sheet at [url], each tile being [w]×[h] px. */
    suspend fun frame(url: String, headers: Map<String, String>, col: Int, row: Int, w: Int, h: Int): ImageBitmap? {
        val key = frameKey(url, col, row)
        lock.withLock { frames.touch(key) }?.let { return it }
        val bytes = sheet(url, headers) ?: return null
        val bmp = withContext(Dispatchers.Default) { decodeImageRegion(bytes, col * w, row * h, w, h) } ?: return null
        lock.withLock {
            frames[key] = bmp
            while (frames.size > MAX_FRAMES) frames.remove(frames.keys.first())
        }
        return bmp
    }

    /** Downloads a sheet into the disk cache ahead of time (no decoding). */
    suspend fun prefetch(url: String, headers: Map<String, String>) {
        if (readDisk(url) == null) download(url, headers)
    }

    private suspend fun sheet(url: String, headers: Map<String, String>): ByteArray? {
        lock.withLock { sheets.touch(url) }?.let { return it }
        val bytes = readDisk(url) ?: download(url, headers) ?: return null
        lock.withLock {
            sheets[url] = bytes
            while (sheets.size > MAX_SHEETS) sheets.remove(sheets.keys.first())
        }
        return bytes
    }

    private suspend fun readDisk(url: String): ByteArray? {
        val cache = disk ?: return null
        return withContext(Dispatchers.Default) {
            runCatching {
                cache.openSnapshot(diskKey(url))?.use { snap -> cache.fileSystem.read(snap.data) { readByteArray() } }
            }.getOrNull()
        }
    }

    private suspend fun download(url: String, headers: Map<String, String>): ByteArray? {
        val bytes = runCatching {
            withContext(Dispatchers.Default) {
                val r = http.get(url) { headers.forEach { (k, v) -> header(k, v) } }
                if (r.status.isSuccess()) r.readRawBytes() else null
            }
        }.onFailure { Log.w("Trickplay", "Sheet download failed: ${it.message}") }.getOrNull() ?: return null
        val cache = disk ?: return bytes
        withContext(Dispatchers.Default) {
            runCatching {
                val editor = cache.openEditor(diskKey(url)) ?: return@runCatching
                try {
                    cache.fileSystem.write(editor.data) { write(bytes) }
                    editor.commit()
                } catch (t: Throwable) {
                    editor.abort(); throw t
                }
            }
        }
        return bytes
    }

    private fun <V> LinkedHashMap<String, V>.touch(key: String): V? {
        val v = remove(key) ?: return null
        this[key] = v // most recently used last
        return v
    }

    private fun frameKey(url: String, col: Int, row: Int) = "$url#$col,$row"
    private fun diskKey(url: String) = "trickplay:$url"

    private companion object {
        const val MAX_SHEETS = 4
        const val MAX_FRAMES = 40
    }
}
