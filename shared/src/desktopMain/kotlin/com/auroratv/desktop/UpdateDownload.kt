package com.auroratv.desktop

import com.auroratv.update.ReleaseAsset
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/** Streams a release file to disk, then checks its size and SHA-256 (deleting it if they don't match). */
internal object UpdateDownload {
    suspend fun downloadVerified(http: HttpClient, url: String, file: File, asset: ReleaseAsset, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var lastReported = -1
        http.prepareGet(url) {
            timeout { requestTimeoutMillis = 30 * 60_000L; socketTimeoutMillis = 60_000L }
        }.execute { response ->
            if (!response.status.isSuccess()) error("Download failed (HTTP ${response.status.value}).")
            val channel = response.bodyAsChannel()
            val buffer = ByteArray(256 * 1024)
            file.outputStream().buffered(1 shl 20).use { out ->
                while (true) {
                    val n = channel.readAvailable(buffer, 0, buffer.size)
                    if (n < 0) break
                    if (n == 0) continue
                    out.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    total += n
                    val pct = if (asset.size > 0) (total * 100 / asset.size).toInt() else 0
                    if (pct != lastReported) { lastReported = pct; onProgress((pct / 100f).coerceIn(0f, 1f)) }
                }
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (total != asset.size || !hex.equals(asset.sha256, ignoreCase = true)) {
            file.delete()
            error("The download was incomplete or corrupted; nothing was installed.")
        }
    }
}
