package com.auroratv.android

import android.content.Context
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSink
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.auroratv.core.Log
import com.auroratv.jellyfin.SharedOkHttp
import com.auroratv.playback.MediaHeader
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors

/**
 * On-device copy of the start of font-heavy MKVs (see MediaHeader): only the bytes before the
 * first video cluster, at most [MediaHeader.MAX_BYTES] per file, [CACHE_BYTES] in all (least
 * recently used goes first). Video itself is never stored.
 */
@androidx.annotation.OptIn(UnstableApi::class)
object MediaHeaderCache {
    private const val CACHE_BYTES = 256L * 1024 * 1024
    private const val TAG = "HeaderCache"

    @Volatile private var cache: SimpleCache? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "header-prefetch").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    @Volatile private var running: Pair<String, CacheWriter>? = null

    fun attach(context: Context) {
        if (cache != null) return
        synchronized(this) {
            if (cache != null) return
            cache = runCatching {
                SimpleCache(File(context.cacheDir, "media-headers"), LeastRecentlyUsedCacheEvictor(CACHE_BYTES), StandaloneDatabaseProvider(context.applicationContext))
            }.onFailure { Log.w(TAG, "No header cache: ${it.message}") }.getOrNull()
        }
    }

    /** Reads come from the cache where it has them; only a file's start (keys from MediaHeader) is written. */
    fun factory(upstream: DataSource.Factory): CacheDataSource.Factory? {
        val c = cache ?: return null
        return CacheDataSource.Factory()
            .setCache(c)
            .setUpstreamDataSourceFactory(upstream)
            .setCacheWriteDataSinkFactory { HeadOnlySink(CacheDataSink(c, CacheDataSink.DEFAULT_FRAGMENT_SIZE)) }
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /** Fetches [url]'s start into the cache in the background (one at a time; a newer request replaces it). */
    fun prefetch(url: String, headers: Map<String, String>, key: String) {
        val c = cache ?: return
        if (running?.first == key) return
        running?.second?.cancel()
        running = null
        worker.execute {
            try {
                val end = runBlocking { MediaHeader.firstClusterOffset { pos, len -> range(url, headers, pos, len) } }
                if (end == null || end < 1_000_000) return@execute // nothing big ahead of the video
                val length = minOf(MediaHeader.MAX_BYTES, end)
                if (c.isCached(key, 0, length)) return@execute
                val upstream = OkHttpDataSource.Factory(SharedOkHttp.client).setDefaultRequestProperties(headers)
                val source = factory(upstream)!!.createDataSource()
                val spec = DataSpec.Builder().setUri(url).setKey(key).setPosition(0).setLength(length).build()
                val writer = CacheWriter(source, spec, null, null)
                running = key to writer
                val t0 = System.currentTimeMillis()
                writer.cache()
                Log.i(TAG, "Kept the first ${length / 1048576} MB (fonts etc.) of a file for an instant start, in ${System.currentTimeMillis() - t0} ms")
            } catch (t: Throwable) {
                if (t !is InterruptedException) Log.i(TAG, "Prefetch stopped: ${t.javaClass.simpleName}")
            } finally {
                if (running?.first == key) running = null
            }
        }
    }

    /**
     * The cache as EpisodeTempFiles sees it: its keys (from SimpleCache's in-memory index, no disk
     * scan) and deleting one. Only "hdr:" keys ever exist here.
     */
    fun disposable(): com.auroratv.playback.DisposableCache? {
        val c = cache ?: return null
        return object : com.auroratv.playback.DisposableCache {
            override fun keys(): Set<String> = c.keys.filter { it.startsWith("hdr:") }.toSet()
            override fun remove(key: String) {
                running?.takeIf { it.first == key }?.second?.cancel()
                c.removeResource(key)
            }
        }
    }

    private fun range(url: String, headers: Map<String, String>, pos: Long, len: Int): ByteArray? = runCatching {
        val req = okhttp3.Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .header("Range", "bytes=$pos-${pos + len - 1}").build()
        SharedOkHttp.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

    /** Passes on only what lies within a file's first [MediaHeader.MAX_BYTES], and only for header keys. */
    private class HeadOnlySink(private val inner: DataSink) : DataSink {
        private var active = false
        private var remaining = 0L

        override fun open(dataSpec: DataSpec) {
            active = dataSpec.key?.startsWith("hdr:") == true && dataSpec.position < MediaHeader.MAX_BYTES
            if (!active) return
            remaining = MediaHeader.MAX_BYTES - dataSpec.position
            inner.open(dataSpec)
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (!active || remaining <= 0) return
            val n = minOf(length.toLong(), remaining).toInt()
            inner.write(buffer, offset, n)
            remaining -= n
        }

        override fun close() {
            if (active) { active = false; inner.close() }
        }
    }
}
