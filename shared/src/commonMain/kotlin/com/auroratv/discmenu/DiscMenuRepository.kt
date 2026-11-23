package com.auroratv.discmenu

import com.auroratv.core.Log
import com.auroratv.jellyfin.model.BaseItemDto

/** A pack on this device and the film it belongs to. */
class InstalledPack(val folder: String, val def: DiscMenuPackDef)

/**
 * Finds the disc menu pack for a library item. Matching is by provider ids first (TMDB, IMDb) and only then by a title
 * pattern the pack states, so a similarly named film is not mistaken for the pack's film.
 */
class DiscMenuRepository(private val platform: DiscMenuPlatform?) {
    private var cache: List<InstalledPack>? = null

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

    companion object {
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
