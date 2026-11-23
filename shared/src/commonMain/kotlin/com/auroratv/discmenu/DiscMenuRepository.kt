package com.auroratv.discmenu

import com.auroratv.core.Log
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.platform.FileStore
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess

/** A pack on this device and the film it belongs to. */
class InstalledPack(val folder: String, val def: DiscMenuPackDef)

/**
 * Finds the disc menu pack for a library item. Matching is by provider ids first (TMDB, IMDb) and only then by a title
 * pattern the pack states, so a similarly named film is not mistaken for the pack's film.
 */
class DiscMenuRepository(private val platform: DiscMenuPlatform?, private val files: FileStore? = null) {
    private var cache: List<InstalledPack>? = null
    private var catalog: PackCatalog? = null
    private var catalogLoadedFromDisk = false
    private var refreshedAtMs = 0L

    fun installed(): List<InstalledPack> = cache ?: scan().also { cache = it }

    fun rescan() { cache = null }

    private fun scan(): List<InstalledPack> {
        val p = platform ?: return emptyList()
        return p.listPacks().mapNotNull { folder ->
            runCatching { InstalledPack(folder, DiscMenuPackDef.parse(p.assets(folder).readText("pack.json"))) }
                .onFailure { Log.w("DiscMenu", "pack '$folder' ignored: ${it.message}") }
                .getOrNull()
        }
    }

    fun packFor(item: BaseItemDto): InstalledPack? = installed().firstOrNull { matches(it.def.match, item) }

    // ---- packs the app can download -------------------------------------------------------------------------

    private fun cachedCatalog(): PackCatalog? {
        if (!catalogLoadedFromDisk) {
            catalogLoadedFromDisk = true
            catalog = runCatching { files?.read(CATALOG_CACHE)?.let(PackCatalog::parse) }.getOrNull()
        }
        return catalog
    }

    /** The downloadable pack for [item], from the catalog fetched earlier (this run or the last). Never touches the network. */
    fun catalogMatch(item: BaseItemDto): CatalogPack? = cachedCatalog()?.packs?.firstOrNull { matches(it.match, item) }

    /** Fetches the catalog, at most once every [REFRESH_EVERY_MS]. Returns null on success or a reason; a failure keeps what was cached. */
    suspend fun refreshCatalog(http: HttpClient, source: PackSource, nowMs: Long, force: Boolean = false): String? {
        if (!source.valid) return "not a web address"
        if (!force && refreshedAtMs != 0L && nowMs - refreshedAtMs < REFRESH_EVERY_MS) return null
        refreshedAtMs = nowMs
        return try {
            val url = source.url("catalog.json")
            val response = http.get(url) { source.headersFor(url).forEach { (k, v) -> header(k, v) } }
            check(response.status.isSuccess()) { "HTTP ${response.status.value} for catalog.json" }
            val text = response.bodyAsText()
            catalog = PackCatalog.parse(text)
            catalogLoadedFromDisk = true
            files?.write(CATALOG_CACHE, text)
            null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("DiscMenu", "catalog not refreshed: ${e.message}")
            e.message ?: "unknown error"
        }
    }

    /**
     * The manifest of a catalog pack. The last good copy is kept, so coming back from the player to a menu that was still
     * downloading works without a connection.
     */
    suspend fun manifestFor(http: HttpClient, source: PackSource, pack: CatalogPack): PackManifest {
        val cacheName = "discmenu-manifest-${pack.id}.json"
        val fresh = try {
            val url = source.url(pack.manifest)
            val response = http.get(url) { source.headersFor(url).forEach { (k, v) -> header(k, v) } }
            check(response.status.isSuccess()) { "HTTP ${response.status.value} for ${pack.manifest}" }
            val text = response.bodyAsText()
            PackManifest.parse(text).also { files?.write(cacheName, text) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w("DiscMenu", "manifest not fetched: ${e.message}")
            val cached = runCatching { files?.read(cacheName)?.let(PackManifest::parse) }.getOrNull()
            cached ?: throw IllegalStateException("couldn't read the menu's file list: ${e.message}")
        }
        require(fresh.id == pack.id) { "the manifest is for '${fresh.id}', not '${pack.id}'" }
        return fresh
    }

    fun catalogSize(): Int = cachedCatalog()?.packs?.size ?: 0

    /** Removes packs the app downloaded, except when the user keeps them. Packs placed by hand are never touched. */
    fun purgeDownloads() {
        val store = platform?.store ?: return
        for (folder in store.downloadedFolders()) runCatching { store.delete(folder) }
        rescan()
    }

    companion object {
        private const val CATALOG_CACHE = "discmenu-catalog.json"
        private const val REFRESH_EVERY_MS = 6L * 60 * 60 * 1000

        fun matches(m: PackMatch, item: BaseItemDto): Boolean {
            val ids = item.providerIds.entries.associate { it.key.lowercase() to it.value }
            if (m.tmdb.isNotEmpty() && ids["tmdb"] in m.tmdb) return true
            if (m.imdb.isNotEmpty() && ids["imdb"] in m.imdb) return true
            // The title pattern is only a fallback for items that carry no provider ids at all.
            val pattern = m.titleRegex
            if (pattern != null && ids["tmdb"] == null && ids["imdb"] == null) {
                return runCatching { Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(item.name.orEmpty()) }.getOrDefault(false)
            }
            return false
        }
    }
}
