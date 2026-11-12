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

@Serializable
data class Settings(
    // Appearance
    val theme: ThemeMode = ThemeMode.Dark,
    val animations: AnimationLevel = AnimationLevel.Full,
    val showBackdrops: Boolean = true,
    val artworkAccents: Boolean = true,
    val showHeroCarousel: Boolean = true,
    // Playback
    /** 0 = automatic (no client cap). */
    val maxBitrateMbps: Int = 0,
    val enableDirectPlay: Boolean = true,
    val hardwareDecoding: Boolean = true,
    val audioPassthrough: Boolean = false,
    val preferredAudioLanguage: String = "",
    val autoPlayNext: Boolean = true,
    val resumeBehavior: ResumeBehavior = ResumeBehavior.Resume,
    val seekForwardSeconds: Int = 30,
    val seekBackSeconds: Int = 10,
    val skipIntro: SkipIntroMode = SkipIntroMode.ShowButton,
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
