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
    /** Size of everything on screen in percent; 0 = automatic (80% on TVs). */
    val interfaceScalePercent: Int = 0,
    val animations: AnimationLevel = AnimationLevel.Full,
    val showBackdrops: Boolean = true,
    /** Behind rows on Home: a frosted colour field from the focused poster (true) or its backdrop art. */
    val frostedBackground: Boolean = true,
    val artworkAccents: Boolean = true,
    val showHeroCarousel: Boolean = true,
    /** Studio/network tiles (Disney, Netflix, Apple TV...) under the featured carousel. */
    val showBrandTiles: Boolean = true,
    /** Reuse audio/subtitle tracks picked by hand for the rest of a series (and for a movie). */
    val rememberTracks: Boolean = true,
    /** Look for a newer build once a day (Settings → About → Updates). */
    val autoCheckUpdates: Boolean = true,
    // Playback
    /** 0 = automatic (no client cap). */
    val maxBitrateMbps: Int = 0,
    val enableDirectPlay: Boolean = true,
    val hardwareDecoding: Boolean = true,
    /** Which video player to use (ExoPlayer, or mpv on Android 8+). */
    val playerEngine: PlayerEngineChoice = PlayerEngineChoice.ExoPlayer,
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
    /** Find intros/credits of any show by matching its sound with another episode (no plugin, stays on the server). */
    val audioSkipDetection: Boolean = true,
    /** Soft sounds when moving focus and selecting (never during playback). */
    val navigationSounds: Boolean = true,
    /** Their volume, percent of the system volume. */
    val uiSoundVolume: Int = 50,
    // Subtitles
    val subtitleMode: SubtitleMode = SubtitleMode.Default,
    val preferredSubtitleLanguage: String = "",
    val subtitleScale: Float = 1.0f,
    /** Look of plain subtitles (colour, edge, background, position, HDR brightness). */
    val subtitleStyle: com.auroratv.playback.SubtitleStyle = com.auroratv.playback.SubtitleStyle(),
    val subtitleDelayMs: Long = 0,
    val assRenderMode: AssRenderMode = AssRenderMode.Full,
)

/** Persists [Settings] as JSON and exposes them as a [StateFlow]. */
/**
 * Settings belong to a profile (`settings-<profile>.json`). `settings.json` mirrors whichever
 * profile was used last, so the app looks right (theme, interface size) before anyone has picked a
 * profile; a profile opened for the first time starts from that, which is also how the settings of
 * the single user from before profiles carry over.
 */
class SettingsRepository(private val files: FileStore) {
    private val fileName = "settings.json"
    private var profileFile: String? = null
    private val _settings = MutableStateFlow(load(fileName) ?: Settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val current: Settings get() = _settings.value

    private fun load(name: String): Settings? = files.read(name)?.let {
        runCatching { AuroraJson.decodeFromString(Settings.serializer(), it) }
            .onFailure { e -> Log.w("Settings", "Corrupt settings in $name, using defaults", e) }
            .getOrNull()
    }

    /** Loads [profile]'s settings (or gives a new profile the current ones as a starting point). */
    fun switchProfile(profile: String) {
        val name = com.auroratv.profiles.ProfileFiles.name("settings", profile)
        if (name == profileFile) return
        profileFile = name
        _settings.value = load(name) ?: _settings.value
        save()
    }

    fun update(transform: (Settings) -> Settings) {
        _settings.update(transform)
        save()
    }

    private fun save() {
        val text = AuroraJson.encodeToString(Settings.serializer(), _settings.value)
        runCatching {
            files.write(fileName, text)
            profileFile?.let { files.write(it, text) }
        }.onFailure { Log.e("Settings", "Failed to save settings", it) }
    }
}

/** Choices offered for [Settings.interfaceScalePercent] (0 = automatic). */
val InterfaceScaleChoices = listOf(0, 70, 80, 90, 100, 110)

/** What "automatic" means: TVs get a tighter layout (Android TV's 960dp-wide screen makes the app's 1:1 sizes look zoomed in). */
fun automaticInterfaceScale(isTv: Boolean): Int = if (isTv) 80 else 100

fun Settings.effectiveInterfaceScale(isTv: Boolean): Int =
    if (interfaceScalePercent in 50..150) interfaceScalePercent else automaticInterfaceScale(isTv)
