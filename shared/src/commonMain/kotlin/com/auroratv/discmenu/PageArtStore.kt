package com.auroratv.discmenu

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The decoded backdrop and highlight images of a few pages. A 1080p page is about 8 MB decoded, so only the page on
 * screen and the ones it links to are kept, never all eleven. Touched from the main thread only; [version] changes
 * when something finishes decoding so drawing code that reads it redraws.
 */
class PageArtStore(private val pack: DiscMenuPackDef, private val assets: DiscMenuAssets, private val capacity: Int = 4) {
    class Art(val plate: ImageBitmap?, val highlights: Map<String, ImageBitmap>)

    var version by mutableIntStateOf(0)
        private set

    private val cache = LinkedHashMap<String, Art>()
    private val inFlight = HashSet<String>()

    fun peek(pageId: String): Art? = cache[pageId]

    /** Decodes a page unless it is cached or on its way; [keep] are never evicted to make room. */
    suspend fun ensure(pageId: String, keep: Set<String> = emptySet()) {
        val def = pack.pages[pageId] ?: return
        if (cache.containsKey(pageId) || !inFlight.add(pageId)) return
        try {
            val art = withContext(Dispatchers.Default) {
                val plate = assets.decode(def.plate)
                val highlights = HashMap<String, ImageBitmap>()
                for (h in def.hotspots) {
                    h.selected?.let { p -> assets.decode(p)?.let { highlights["${h.id}/sel"] = it } }
                    h.activated?.let { p -> assets.decode(p)?.let { highlights["${h.id}/act"] = it } }
                }
                Art(plate, highlights)
            }
            cache[pageId] = art
            val protectedIds = keep + pageId
            val it = cache.keys.iterator()
            while (cache.size > capacity && it.hasNext()) { val k = it.next(); if (k !in protectedIds) it.remove() }
            version++
        } finally {
            inFlight.remove(pageId)
        }
    }

    /** Pages worth having ready from [pageId]: the ones its links lead to. */
    fun neighbours(pageId: String): Set<String> =
        pack.pages[pageId]?.hotspots?.mapNotNull { h -> h.action.takeIf { it.startsWith("goto:") }?.removePrefix("goto:") }
            ?.filter { it in pack.pages }?.toSet().orEmpty()

    fun clear() { cache.clear(); inFlight.clear() }
}
