package com.auroratv.settings

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.platform.FileStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable

@Serializable enum class ThemeMode { Dark, Light, System }
@Serializable enum class AnimationLevel { Full, Reduced, Off }
@Serializable enum class SubtitleMode { Default, Always, OnlyForced, Smart, Off }
@Serializable enum class ResumeBehavior { Resume, StartOver }
@Serializable enum class SkipIntroMode { ShowButton, Auto, Off }
@Serializable enum class AssRenderMode { Full, Simple }
@Serializable enum class PlayerEngineChoice { ExoPlayer, Mpv }
@Serializable enum class StillWatchingMode { Smart, After1Hour, After2Hours, After3Hours, Off }

@Serializable
data class Settings(
    // Appearance
    val theme: ThemeMode = ThemeMode.Dark,
    val animations: AnimationLevel = AnimationLevel.Full,
    val showBackdrops: Boolean = true,
    val artworkAccents: Boolean = true,
    val showHeroCarousel: Boolean = true,
    /** Studio/network tiles (Disney, Netflix, Apple TV...) under the featured carousel. */
    val showBrandTiles: Boolean = true,
    // Playback
    /** 0 = automatic (no client cap). */
    val maxBitrateMbps: Int = 0,
    val enableDirectPlay: Boolean = true,
    val hardwareDecoding: Boolean = true,
    /** Android TV: which video player to use. Windows always uses mpv. */
    val playerEngine: PlayerEngineChoice = PlayerEngineChoice.ExoPlayer,
    val audioPassthrough: Boolean = false,
    val preferredAudioLanguage: String = "",
    val autoPlayNext: Boolean = true,
    /** When to ask "Are you still watching?" before auto-playing another episode. */
    val stillWatching: StillWatchingMode = StillWatchingMode.Smart,
    /** Android TV: switch the display to the video's frame rate (removes 24p judder; screen may blank briefly). */
    val matchFrameRate: Boolean = false,
    val resumeBehavior: ResumeBehavior = ResumeBehavior.Resume,
    val seekForwardSeconds: Int = 30,
    val seekBackSeconds: Int = 10,
    val skipIntro: SkipIntroMode = SkipIntroMode.ShowButton,
    /** Look up anime opening/ending times on AniSkip when the server has none (sends title + episode number). */
    val onlineSkipTimes: Boolean = true,
    // Subtitles
    val subtitleMode: SubtitleMode = SubtitleMode.Default,
    val preferredSubtitleLanguage: String = "",
    val subtitleScale: Float = 1.0f,
    val subtitleDelayMs: Long = 0,
    val assRenderMode: AssRenderMode = AssRenderMode.Full,
)

/** Persists [Settings] as JSON and exposes them as a [StateFlow]. */
class SettingsRepository(private val files: FileStore) {
    private val fileName = "settings.json"
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current: Settings get() = _settings.value

    private fun load(): Settings = files.read(fileName)?.let {
        runCatching { AuroraJson.decodeFromString(Settings.serializer(), it) }
            .onFailure { e -> Log.w("Settings", "Corrupt settings, using defaults", e) }
            .getOrNull()
    } ?: Settings()

    fun update(transform: (Settings) -> Settings) {
        _settings.update(transform)
        runCatching { files.write(fileName, AuroraJson.encodeToString(Settings.serializer(), _settings.value)) }
            .onFailure { Log.e("Settings", "Failed to save settings", it) }
    }
}
