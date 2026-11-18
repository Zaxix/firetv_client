package com.auroratv.jellyfin

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.UserItemData

/**
 * Watch state for a profile that lives only on this device (a Netflix-style profile under one
 * Jellyfin sign-in; see SessionManager). When a [JellyfinApi] has one, every item the app receives
 * gets this profile's progress, watched mark and favourite instead of the account's, and everything
 * the profile does (watching, marking watched, favourites) is recorded here instead of on the server,
 * so the account's own history is never touched.
 */
interface LocalWatchData {
    /** This profile's state for [item] (and notes what's needed later: runtime, series). */
    fun userData(item: BaseItemDto): UserItemData
    fun setPlayed(itemId: String, played: Boolean)
    fun setFavorite(itemId: String, favorite: Boolean)
    /** Playback reached [positionTicks]; [stopped] when it ended or the viewer left. */
    fun progress(itemId: String, positionTicks: Long, stopped: Boolean)
    /** In-progress items, most recently watched first. */
    fun resumeIds(limit: Int): List<String>
    fun favoriteIds(): List<String>
    /** Watched items, most recent first. */
    fun playedIds(limit: Int): List<String>
    /** Series this profile watched episodes of, most recent first. */
    fun recentSeries(limit: Int): List<String>
    /** The episode of [seriesId] this profile finished most recently, or null. */
    fun lastFinishedEpisode(seriesId: String): String?
    /** When this profile last watched [itemId] (epoch ms), or null. */
    fun lastWatched(itemId: String): Long?
}
