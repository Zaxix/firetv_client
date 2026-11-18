package com.auroratv.jellyfin

import com.auroratv.jellyfin.model.BaseItemDto

/** A resolved artwork reference: URL plus its BlurHash (for instant placeholders and accent colours). */
data class Artwork(val url: String, val blurHash: String?)

/**
 * Builds sized image URLs so the server scales artwork for us; we never download a 4K poster for a
 * 200px card. Image endpoints in Jellyfin are public, so no token is attached.
 */
class ImageUrls(private val baseUrl: String) {

    fun url(itemId: String, type: String, tag: String?, maxWidth: Int? = null, maxHeight: Int? = null, index: Int? = null, quality: Int = 90): String =
        buildString {
            append(baseUrl.trimEnd('/')).append("/Items/").append(itemId).append("/Images/").append(type)
            if (index != null) append('/').append(index)
            append("?quality=").append(quality)
            maxWidth?.let { append("&fillWidth=").append(it) }
            maxHeight?.let { append("&fillHeight=").append(it) }
            tag?.let { append("&tag=").append(it) }
        }

    private fun blur(item: BaseItemDto, type: String, tag: String?): String? =
        tag?.let { item.imageBlurHashes[type]?.get(it) }

    /** Portrait poster; for episodes falls back to the series poster. */
    fun poster(item: BaseItemDto, width: Int): Artwork? {
        item.imageTags["Primary"]?.let { tag ->
            if (!item.isEpisode) return Artwork(url(item.id, "Primary", tag, maxWidth = width), blur(item, "Primary", tag))
        }
        if (item.seriesId != null && item.seriesPrimaryImageTag != null) {
            return Artwork(url(item.seriesId, "Primary", item.seriesPrimaryImageTag, maxWidth = width), blur(item, "Primary", item.seriesPrimaryImageTag))
        }
        return item.imageTags["Primary"]?.let { Artwork(url(item.id, "Primary", it, maxWidth = width), blur(item, "Primary", it)) }
    }

    /** 16:9 still: episode primary image, item thumb, backdrop, then parent thumb/backdrop. */
    fun landscape(item: BaseItemDto, width: Int): Artwork? {
        if (item.isEpisode) item.imageTags["Primary"]?.let { return Artwork(url(item.id, "Primary", it, maxWidth = width), blur(item, "Primary", it)) }
        item.imageTags["Thumb"]?.let { return Artwork(url(item.id, "Thumb", it, maxWidth = width), blur(item, "Thumb", it)) }
        item.backdropImageTags.firstOrNull()?.let { return Artwork(url(item.id, "Backdrop", it, maxWidth = width, index = 0), blur(item, "Backdrop", it)) }
        if (item.parentThumbItemId != null && item.parentThumbImageTag != null)
            return Artwork(url(item.parentThumbItemId, "Thumb", item.parentThumbImageTag, maxWidth = width), blur(item, "Thumb", item.parentThumbImageTag))
        if (item.parentBackdropItemId != null && item.parentBackdropImageTags.isNotEmpty())
            return Artwork(url(item.parentBackdropItemId, "Backdrop", item.parentBackdropImageTags[0], maxWidth = width, index = 0), blur(item, "Backdrop", item.parentBackdropImageTags[0]))
        return item.imageTags["Primary"]?.let { Artwork(url(item.id, "Primary", it, maxWidth = width), blur(item, "Primary", it)) }
    }

    /** Full-bleed backdrop for hero and detail pages. */
    fun backdrop(item: BaseItemDto, width: Int): Artwork? {
        item.backdropImageTags.firstOrNull()?.let { return Artwork(url(item.id, "Backdrop", it, maxWidth = width, index = 0, quality = 85), blur(item, "Backdrop", it)) }
        if (item.parentBackdropItemId != null && item.parentBackdropImageTags.isNotEmpty())
            return Artwork(url(item.parentBackdropItemId, "Backdrop", item.parentBackdropImageTags[0], maxWidth = width, index = 0, quality = 85), blur(item, "Backdrop", item.parentBackdropImageTags[0]))
        return null
    }

    fun logo(item: BaseItemDto, width: Int): Artwork? {
        item.imageTags["Logo"]?.let { return Artwork(url(item.id, "Logo", it, maxWidth = width), null) }
        if (item.parentLogoItemId != null && item.parentLogoImageTag != null)
            return Artwork(url(item.parentLogoItemId, "Logo", item.parentLogoImageTag, maxWidth = width), null)
        return null
    }

    fun userAvatar(userId: String, tag: String?, size: Int): String? =
        tag?.let { "${baseUrl.trimEnd('/')}/Users/$userId/Images/Primary?fillWidth=$size&fillHeight=$size&quality=90&tag=$it" }

    fun person(personId: String?, tag: String?, width: Int): String? =
        if (personId == null || tag == null) null else url(personId, "Primary", tag, maxWidth = width)

    /** One trickplay sprite sheet. Unlike other images this endpoint needs the access token (sent as a header). */
    fun trickplayTile(itemId: String, mediaSourceId: String, width: Int, sheetIndex: Int): String =
        "${baseUrl.trimEnd('/')}/Videos/$itemId/Trickplay/$width/$sheetIndex.jpg?mediaSourceId=$mediaSourceId"

    fun chapter(itemId: String, index: Int, tag: String?, width: Int): String? =
        tag?.let { url(itemId, "Chapter", it, maxWidth = width, index = index) }
}
