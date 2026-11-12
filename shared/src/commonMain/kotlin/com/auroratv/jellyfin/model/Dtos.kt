package com.auroratv.jellyfin.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Jellyfin uses 100-nanosecond "ticks" for all durations. */
const val TICKS_PER_MS = 10_000L

fun Long.ticksToMs(): Long = this / TICKS_PER_MS
fun Long.msToTicks(): Long = this * TICKS_PER_MS

@Serializable
data class PublicSystemInfo(
    @SerialName("ServerName") val serverName: String? = null,
    @SerialName("Version") val version: String? = null,
    @SerialName("Id") val id: String? = null,
    @SerialName("ProductName") val productName: String? = null,
    @SerialName("LocalAddress") val localAddress: String? = null,
    @SerialName("StartupWizardCompleted") val startupWizardCompleted: Boolean? = null,
)

@Serializable
data class UserDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
    @SerialName("HasPassword") val hasPassword: Boolean = true,
)

@Serializable
data class AuthenticateByName(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val password: String,
)

@Serializable
data class AuthenticationResult(
    @SerialName("User") val user: UserDto,
    @SerialName("AccessToken") val accessToken: String,
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
data class QueryResult<T>(
    @SerialName("Items") val items: List<T> = emptyList(),
    @SerialName("TotalRecordCount") val totalRecordCount: Int = 0,
    @SerialName("StartIndex") val startIndex: Int = 0,
)

@Serializable
data class UserItemData(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long = 0,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Int? = null,
    @SerialName("PlayCount") val playCount: Int = 0,
)

@Serializable
data class PersonDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

@Serializable
data class NameIdPair(
    @SerialName("Name") val name: String? = null,
    @SerialName("Id") val id: String? = null,
)

@Serializable
data class ChapterInfo(
    @SerialName("StartPositionTicks") val startPositionTicks: Long = 0,
    @SerialName("Name") val name: String? = null,
    @SerialName("ImageTag") val imageTag: String? = null,
)

/** The subset of Jellyfin's BaseItemDto that Aurora uses. Unknown fields are ignored. */
@Serializable
data class BaseItemDto(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("OriginalTitle") val originalTitle: String? = null,
    @SerialName("ServerId") val serverId: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("MediaType") val mediaType: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("Taglines") val taglines: List<String> = emptyList(),
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("CriticRating") val criticRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("Studios") val studios: List<NameIdPair> = emptyList(),
    @SerialName("People") val people: List<PersonDto> = emptyList(),
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("IndexNumberEnd") val indexNumberEnd: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("RecursiveItemCount") val recursiveItemCount: Int? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropImageTags: List<String> = emptyList(),
    @SerialName("ImageBlurHashes") val imageBlurHashes: Map<String, Map<String, String>> = emptyMap(),
    @SerialName("PrimaryImageAspectRatio") val primaryImageAspectRatio: Double? = null,
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropImageTags: List<String> = emptyList(),
    @SerialName("ParentLogoItemId") val parentLogoItemId: String? = null,
    @SerialName("ParentLogoImageTag") val parentLogoImageTag: String? = null,
    @SerialName("ParentThumbItemId") val parentThumbItemId: String? = null,
    @SerialName("ParentThumbImageTag") val parentThumbImageTag: String? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("UserData") val userData: UserItemData? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSourceInfo> = emptyList(),
    @SerialName("MediaStreams") val mediaStreams: List<MediaStream> = emptyList(),
    @SerialName("Chapters") val chapters: List<ChapterInfo> = emptyList(),
    @SerialName("LocationType") val locationType: String? = null,
) {
    val isSeries get() = type == "Series"
    val isSeason get() = type == "Season"
    val isEpisode get() = type == "Episode"
    val isMovie get() = type == "Movie"
    val isPlayable get() = type in PLAYABLE_TYPES
    val isBrowsableFolder get() = isFolder && !isSeries && !isSeason

    val runtimeMs: Long? get() = runTimeTicks?.ticksToMs()
    val resumePositionMs: Long get() = userData?.playbackPositionTicks?.ticksToMs() ?: 0
    val progressFraction: Float?
        get() {
            val total = runTimeTicks ?: return null
            val pos = userData?.playbackPositionTicks ?: return null
            if (total <= 0 || pos <= 0) return null
            return (pos.toDouble() / total).toFloat().coerceIn(0f, 1f)
        }

    /** "S1:E4" style label for episodes. */
    val episodeLabel: String?
        get() {
            if (!isEpisode) return null
            val s = parentIndexNumber
            val e = indexNumber ?: return null
            val end = indexNumberEnd?.let { "-$it" } ?: ""
            return if (s != null) "S$s:E$e$end" else "E$e$end"
        }

    companion object {
        val PLAYABLE_TYPES = setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer")
    }
}

@Serializable
data class MediaSegmentDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("Type") val type: String,
    @SerialName("StartTicks") val startTicks: Long,
    @SerialName("EndTicks") val endTicks: Long,
)
