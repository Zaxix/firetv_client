package com.auroratv.discmenu

import com.auroratv.core.AuroraJson
import kotlinx.serialization.Serializable

/**
 * A disc menu pack's `pack.json`. Every reference-dependent number lives here (with its provenance in the
 * pack's own notes), never in code. A value the pack author could not measure is `null`, which is
 * different from a measured zero.
 *
 * Coordinates are pixels in the pack's [PackCanvas] (top-left origin); rectangles are `[left, top, right, bottom]`.
 */
@Serializable
data class DiscMenuPackDef(
    val schema: Int,
    val id: String,
    val title: String,
    val edition: String = "",
    val match: PackMatch = PackMatch(),
    val canvas: PackCanvas = PackCanvas(),
    val video: PackVideo,
    val music: PackMusic,
    val sparkles: PackSparkles,
    val timing: PackTiming,
    val activatedOutline: OutlineStyle = OutlineStyle(),
    val selectedOutline: OutlineStyle = OutlineStyle(color = "#A55A3E"),
    val main: MainMenuDef,
    val pages: Map<String, PageDef>,
    /** Ordered groups of sibling pages (scene pages, cast and crew) that Next/Previous page keys walk through. */
    val sequences: Map<String, List<String>> = emptyMap(),
    val credits: CreditsDef? = null,
) {
    companion object {
        const val SUPPORTED_SCHEMA = 2

        fun parse(json: String): DiscMenuPackDef {
            val def = AuroraJson.decodeFromString(serializer(), json)
            require(def.schema == SUPPORTED_SCHEMA) { "Unsupported disc menu pack schema ${def.schema} (expected $SUPPORTED_SCHEMA)" }
            return def
        }
    }
}

@Serializable
data class PackMatch(
    val tmdb: List<String> = emptyList(),
    val imdb: List<String> = emptyList(),
    val titleRegex: String? = null,
)

@Serializable
data class PackCanvas(val w: Int = 1920, val h: Int = 1080)

@Serializable
data class PackVideo(
    val loop: LoopVideo,
    /** The transition into any page that has no entry in [transitions]. */
    val transition: TransitionVideo,
    /** Per target page id: a disc whose buttons each play their own transition (the Polar Express: scenes and languages differ). */
    val transitions: Map<String, TransitionVideo> = emptyMap(),
    val intro: IntroVideo? = null,
    val letter: LetterVideo? = null,
) {
    fun transitionFor(target: String): TransitionVideo = transitions[target] ?: transition
    val allTransitions: List<TransitionVideo> get() = listOf(transition) + transitions.values
}

@Serializable
data class LoopVideo(val file: String, val durationMs: Long, val entryMs: Long, val fps: Double)

@Serializable
data class TransitionVideo(val file: String, val durationMs: Long, val pageCutMs: Long, val audioDelayMs: Long)

/** The disc's opening animation (logo, clips in the clouds, pan to the castle). Optional: a pack without it opens on the menu. */
@Serializable
data class IntroVideo(
    val file: String,
    /** Where the menu takes over; the loop is entered at [LoopVideo.entryMs] and the music starts here. */
    val durationMs: Long,
    /** Video available past [durationMs] so a crossfade can run; equals [durationMs] when there is none. */
    val clipDurationMs: Long = durationMs,
    val crossfadeMs: Long = 0,
    val skippable: Boolean = true,
)

/** The Play Movie dissolve into the letter screen. */
@Serializable
data class LetterVideo(
    val file: String,
    val durationMs: Long,
    val holdLastFrame: Boolean = true,
    val staticPlate: String? = null,
)

@Serializable
data class PackMusic(
    val file: String,
    val restartOnMainEntry: Boolean = true,
    val startLagAfterVideoEntryMs: Long = 0,
    val fadeOutOnPlayMovie: MusicFade? = null,
    /**
     * Where in the intro (ms) the music file's first sample plays, when the intro's own sound already carries the music
     * (the Polar Express: the music comes in under the intro's last seconds). At the hand-off the music then continues from
     * its position at that moment instead of starting over. `null` = the music starts from its beginning at the hand-off.
     */
    val startsInIntroAtMs: Long? = null,
)

@Serializable
data class MusicFade(val startAfterDissolveMs: Long, val durationMs: Long)

@Serializable
data class PackSparkles(val index: String, val data: String)

@Serializable
data class PackTiming(
    /** Per main-menu button: how long after activation the transition video starts. `null` = not observed. */
    val activationToTransitionMs: Map<String, Long?> = emptyMap(),
    val subActivationToCutMs: Long,
    val playMovie: PlayMovieTiming? = null,
    /**
     * How long before the transition (or the Play Movie clip) starts the music is cut. `null` = at the moment of activation
     * (the first disc). The Polar Express keeps playing for a while after the button is pressed.
     */
    val musicStopBeforeTransitionMs: Long? = null,
    /** Whether a page button keeps its highlight picture while its action runs (false = the highlight is gone, as on the Polar Express). */
    val pageActivationShowsHighlight: Boolean = true,
)

@Serializable
data class PlayMovieTiming(
    val activationToDissolveMs: Long? = null,
    /** How long the letter stays before playback starts. `null` = UNKNOWN: the engine starts playback when the dissolve ends. */
    val letterHoldBeforePlaybackMs: Long? = null,
)

@Serializable
data class OutlineStyle(val color: String = "#5E5A42", val thicknessPx: Int = 6)

@Serializable
data class MainMenuDef(val buttons: List<MainButtonDef>, val hotspotPadPx: Int)

@Serializable
data class MainButtonDef(
    val id: String,
    val label: String,
    val rect: List<Int>,
    val selected: String? = null,
    val activated: String? = null,
    val action: String,
    val default: Boolean = false,
)

@Serializable
data class PageDef(
    val title: String,
    val plate: String,
    val default: String,
    val entryDefaultFrom: Map<String, String> = emptyMap(),
    /** Where Back goes. `null` = the main menu. */
    val backTo: String? = null,
    val hotspots: List<HotspotDef>,
)

@Serializable
data class HotspotDef(
    val id: String,
    val rect: List<Int>,
    val spriteRect: List<Int>? = null,
    val selected: String? = null,
    val activated: String? = null,
    val action: String,
    val provenance: String? = null,
    /** How long after activation the action takes effect, when it differs from [PackTiming.subActivationToCutMs]. */
    val cutDelayMs: Long? = null,
)

/**
 * Server-driven cast and crew on the credits pages. When present (and the pages' clean backdrops exist), the baked
 * names are replaced by the server's, drawn in a similar condensed face. Absent = the baked plates are shown as they are.
 */
@Serializable
data class CreditsDef(
    val textLeftPx: Int,
    val firstLineCenterYPx: Int,
    val lineHeightPx: Int,
    val fontSizePx: Int,
    val textScaleX: Float = 1f,
    val maxWidthPx: Int,
    val color: String,
    /** In display order: cast pages fill in order, then the crew page. */
    val pages: Map<String, CreditsPageDef>,
)

@Serializable
data class CreditsPageDef(val kind: String, val capacity: Int, val cleanPlate: String)

/** An axis-aligned rectangle in canvas pixels. Right and bottom are exclusive. */
data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f
    fun contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom
    fun padded(p: Int) = Rect(left - p, top - p, right + p, bottom + p)
}

fun List<Int>.toRect(what: String = "rect"): Rect {
    require(size == 4) { "$what must have 4 numbers, has $size" }
    return Rect(this[0], this[1], this[2], this[3])
}
