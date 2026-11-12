package com.auroratv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.unit.dp
import com.auroratv.AppInfo
import com.auroratv.core.toAppError
import com.auroratv.playback.DeviceCapabilities
import com.auroratv.settings.AnimationLevel
import com.auroratv.settings.AssRenderMode
import com.auroratv.settings.ResumeBehavior
import com.auroratv.settings.Settings
import com.auroratv.settings.SkipIntroMode
import com.auroratv.settings.SubtitleMode
import com.auroratv.settings.ThemeMode
import com.auroratv.ui.AppContainer
import com.auroratv.ui.LocalSession
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.ScreenPadding
import com.auroratv.ui.nav.LocalEntry
import com.auroratv.ui.nav.LocalNavigator
import com.auroratv.ui.nav.ScreenFocus
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

private enum class SettingsPage(val label: String) { Appearance("Appearance"), Playback("Playback"), Subtitles("Subtitles"), Server("Server"), About("About") }

private val LANGUAGES = listOf("" to "Default", "eng" to "English", "spa" to "Spanish", "fra" to "French", "deu" to "German", "ita" to "Italian",
    "por" to "Portuguese", "jpn" to "Japanese", "kor" to "Korean", "zho" to "Chinese", "rus" to "Russian", "nld" to "Dutch", "swe" to "Swedish",
    "pol" to "Polish", "tur" to "Turkish", "ara" to "Arabic", "hin" to "Hindi")

private fun <T> List<T>.after(current: T): T = this[(indexOf(current) + 1).mod(size)]

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(container: AppContainer) {
    val navigator = LocalNavigator.current
    val entry = LocalEntry.current!!
    var page by remember { mutableStateOf(SettingsPage.Appearance) }
    val first = remember { FocusRequester() }
    val colors = Aurora.colors
    ScreenFocus(entry, navigator, first)

    Row(Modifier.fillMaxSize().padding(start = ScreenPadding, top = 48.dp, end = ScreenPadding)) {
        Column(Modifier.width(260.dp).focusRestorer(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Settings", style = AuroraType.title, color = colors.onBackground, modifier = Modifier.padding(bottom = 24.dp))
            SettingsPage.entries.forEachIndexed { i, p ->
                FocusSurface(
                    onClick = { page = p },
                    onFocused = { page = p },
                    shape = RoundedCornerShape(12.dp),
                    ringWidth = 0.dp,
                    scaleOnFocus = false,
                    modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                ) { focused ->
                    Text(
                        p.label,
                        style = AuroraType.label,
                        color = if (focused) colors.background else if (page == p) colors.onBackground else colors.onSurfaceMuted,
                        modifier = Modifier.fillMaxWidth()
                            .background(if (focused) colors.focusFill else if (page == p) colors.onBackground.copy(alpha = 0.1f) else colors.background.copy(alpha = 0f))
                            .padding(horizontal = 18.dp, vertical = 14.dp),
                    )
                }
            }
        }
        Spacer(Modifier.width(48.dp))
        Column(Modifier.fillMaxHeight().weight(1f)) {
            when (page) {
                SettingsPage.Appearance -> AppearancePage(container)
                SettingsPage.Playback -> PlaybackPage(container)
                SettingsPage.Subtitles -> SubtitlePage(container)
                SettingsPage.Server -> ServerPage(container)
                SettingsPage.About -> AboutPage(container)
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, value: String?, description: String? = null, onClick: () -> Unit) {
    val colors = Aurora.colors
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(12.dp), ringWidth = 0.dp, scaleOnFocus = false, modifier = Modifier.fillMaxWidth()) { focused ->
        Row(
            Modifier.fillMaxWidth().background(if (focused) colors.focusFill else colors.surface.copy(alpha = 0.6f)).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = AuroraType.label, color = if (focused) colors.background else colors.onBackground)
                description?.let { Text(it, style = AuroraType.caption, color = if (focused) colors.background.copy(alpha = 0.7f) else colors.onSurfaceFaint) }
            }
            value?.let { Text(it, style = AuroraType.bodyStrong, color = if (focused) colors.background else colors.onSurfaceMuted) }
        }
    }
}

@Composable
private fun SettingsList(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(top = 64.dp, bottom = 64.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

@Composable
private fun Header(text: String) {
    Text(text, style = AuroraType.section, color = Aurora.colors.onBackground, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
}

@Composable
private fun AppearancePage(container: AppContainer) {
    val s by container.settings.settings.collectAsState()
    fun set(t: (Settings) -> Settings) = container.settings.update(t)
    SettingsList {
        item { SettingRow("Theme", s.theme.name, "Dark, Light, or follow the device") { set { it.copy(theme = ThemeMode.entries.after(it.theme)) } } }
        item { SettingRow("Animations", s.animations.name, "Reduce motion on slower devices") { set { it.copy(animations = AnimationLevel.entries.after(it.animations)) } } }
        item { SettingRow("Backdrop artwork", onOff(s.showBackdrops), "Large background art on Home and detail pages") { set { it.copy(showBackdrops = !it.showBackdrops) } } }
        item { SettingRow("Featured carousel", onOff(s.showHeroCarousel), "Rotating featured titles at the top of Home") { set { it.copy(showHeroCarousel = !it.showHeroCarousel) } } }
        item { SettingRow("Artwork colour accents", onOff(s.artworkAccents), "Tint highlights with colours from the artwork") { set { it.copy(artworkAccents = !it.artworkAccents) } } }
    }
}

private fun onOff(b: Boolean) = if (b) "On" else "Off"
private fun languageName(code: String) = LANGUAGES.firstOrNull { it.first == code }?.second ?: code

@Composable
private fun PlaybackPage(container: AppContainer) {
    val s by container.settings.settings.collectAsState()
    fun set(t: (Settings) -> Settings) = container.settings.update(t)
    val bitrates = listOf(0, 120, 80, 60, 40, 20, 10, 8, 4, 2)
    val isDesktop = !container.platform.info.isTv
    SettingsList {
        item { Header("Quality") }
        item { SettingRow("Maximum streaming quality", if (s.maxBitrateMbps == 0) "Auto" else "${s.maxBitrateMbps} Mbps", "Above this, the server transcodes to a lower bitrate") { set { it.copy(maxBitrateMbps = bitrates.after(it.maxBitrateMbps)) } } }
        item { SettingRow("Direct Play", onOff(s.enableDirectPlay), "Play original files when the device supports them") { set { it.copy(enableDirectPlay = !it.enableDirectPlay) } } }
        if (isDesktop) {
            item { SettingRow("Hardware decoding", onOff(s.hardwareDecoding), "Use the GPU (D3D11) to decode video") { set { it.copy(hardwareDecoding = !it.hardwareDecoding) } } }
            item { SettingRow("Audio passthrough", onOff(s.audioPassthrough), "Send AC3/E-AC3/DTS/TrueHD bitstream to a receiver (S/PDIF or HDMI)") { set { it.copy(audioPassthrough = !it.audioPassthrough) } } }
        }
        item { Header("Behaviour") }
        item { SettingRow("Audio language", languageName(s.preferredAudioLanguage)) { set { it.copy(preferredAudioLanguage = LANGUAGES.map { l -> l.first }.after(it.preferredAudioLanguage)) } } }
        item { SettingRow("Auto-play next episode", onOff(s.autoPlayNext)) { set { it.copy(autoPlayNext = !it.autoPlayNext) } } }
        item { SettingRow("When starting a partly watched item", if (s.resumeBehavior == ResumeBehavior.Resume) "Resume" else "Start over") { set { it.copy(resumeBehavior = ResumeBehavior.entries.after(it.resumeBehavior)) } } }
        item { SettingRow("Skip forward", "${s.seekForwardSeconds}s") { set { it.copy(seekForwardSeconds = listOf(10, 15, 30, 60).after(it.seekForwardSeconds)) } } }
        item { SettingRow("Skip back", "${s.seekBackSeconds}s") { set { it.copy(seekBackSeconds = listOf(5, 10, 15, 30).after(it.seekBackSeconds)) } } }
        item { SettingRow("Intros and recaps", when (s.skipIntro) { SkipIntroMode.ShowButton -> "Show Skip button"; SkipIntroMode.Auto -> "Skip automatically"; SkipIntroMode.Off -> "Off" }, "Requires media segments on the server (Jellyfin 10.10+)") { set { it.copy(skipIntro = SkipIntroMode.entries.after(it.skipIntro)) } } }
    }
}

@Composable
private fun SubtitlePage(container: AppContainer) {
    val s by container.settings.settings.collectAsState()
    fun set(t: (Settings) -> Settings) = container.settings.update(t)
    val caps by produceState<DeviceCapabilities?>(null) { value = container.capabilities() }
    SettingsList {
        item {
            SettingRow("Subtitle mode", when (s.subtitleMode) {
                SubtitleMode.Default -> "Default"; SubtitleMode.Always -> "Always"; SubtitleMode.OnlyForced -> "Forced only"; SubtitleMode.Smart -> "Smart"; SubtitleMode.Off -> "Off"
            }, "Smart shows subtitles only when the audio isn't in your language") { set { it.copy(subtitleMode = SubtitleMode.entries.after(it.subtitleMode)) } }
        }
        item { SettingRow("Subtitle language", languageName(s.preferredSubtitleLanguage)) { set { it.copy(preferredSubtitleLanguage = LANGUAGES.map { l -> l.first }.after(it.preferredSubtitleLanguage)) } } }
        item { SettingRow("Text size", "${(s.subtitleScale * 100).toInt()}%", "Applies to SRT/VTT; styled ASS keeps its own sizes unless the engine can scale it") { set { it.copy(subtitleScale = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).after(it.subtitleScale)) } } }
        if (caps?.supportsSubtitleDelay == true) {
            item { SettingRow("Default subtitle delay", "${s.subtitleDelayMs} ms", "Adjust per video from the player too") { set { it.copy(subtitleDelayMs = listOf(0L, 100L, 250L, 500L, 1000L, -100L, -250L, -500L, -1000L).after(it.subtitleDelayMs)) } } }
        }
        if (container.platform.info.isTv) {
            item { SettingRow("ASS/SSA rendering", if (s.assRenderMode == AssRenderMode.Full) "Full (libass)" else "Simple", "Full keeps fonts, positioning and animation; Simple is lighter for older devices") { set { it.copy(assRenderMode = AssRenderMode.entries.after(it.assRenderMode)) } } }
        }
    }
}

@Composable
private fun ServerPage(container: AppContainer) {
    val session = LocalSession.current
    val servers by container.sessions.servers.collectAsState()
    val scope = rememberCoroutineScope()
    var diagnostics by remember { mutableStateOf<List<String>?>(null) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    SettingsList {
        item { Header("Current") }
        item { SettingRow(session.server.name, session.user.name, session.server.url) {} }
        item {
            SettingRow("Connection diagnostics", null, "Checks reachability, latency and server version") {
                diagnostics = listOf("Running…")
                scope.launch {
                    val mark = TimeSource.Monotonic.markNow()
                    diagnostics = try {
                        val info = session.api.publicSystemInfo()
                        val latency = mark.elapsedNow().inWholeMilliseconds
                        val me = runCatching { session.api.currentUser(); "valid" }.getOrElse { "invalid (${it.toAppError().userMessage})" }
                        val caps = container.capabilities()
                        listOf(
                            "Server: ${info.serverName} — Jellyfin ${info.version}",
                            "Address: ${session.server.url}",
                            "Response time: $latency ms",
                            "Session token: $me",
                            "Playback engine: ${caps.engineName}",
                            "Direct-play video: ${caps.video.joinToString { it.codec + if (it.hardware) " (hw)" else " (sw)" }}",
                        )
                    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        listOf("Failed: ${t.toAppError().userMessage}")
                    }
                }
            }
        }
        diagnostics?.let { lines -> items(lines) { Text(it, style = AuroraType.body, color = Aurora.colors.onSurfaceMuted, modifier = Modifier.padding(start = 20.dp)) } }
        item { SettingRow("Switch user", null, "Sign in as someone else on this server") { container.sessions.showLogin(session.server) } }
        item { SettingRow("Sign out", null, "Removes the saved sign-in for ${session.user.name}") { scope.launch { container.sessions.signOut() } } }
        item { Header("Servers") }
        items(servers, key = { it.id }) { server ->
            val isCurrent = server.id == session.server.id
            SettingRow(server.name, if (isCurrent) "Connected" else "Switch", server.url) {
                if (!isCurrent) scope.launch { container.sessions.switchTo(server) }
            }
        }
        item { SettingRow("Add server", null, "Connect to another Jellyfin server") { container.sessions.showAddServer() } }
        items(servers.filter { it.id != session.server.id }, key = { "remove-" + it.id }) { server ->
            SettingRow("Remove ${server.name}", if (confirmRemove == server.id) "Press again to confirm" else null, "Forgets the server and its saved sign-ins") {
                if (confirmRemove == server.id) { container.sessions.removeServer(server); confirmRemove = null } else confirmRemove = server.id
            }
        }
    }
}

@Composable
private fun AboutPage(container: AppContainer) {
    val caps by produceState<DeviceCapabilities?>(null) { value = container.capabilities() }
    val info = container.platform.info
    val colors = Aurora.colors
    SettingsList {
        item { Header("Aurora TV ${AppInfo.VERSION}") }
        item { Line("Platform", "${info.platformName} · ${info.osVersion}") }
        item { Line("Device", info.deviceName) }
        item { Line("Token storage", container.platform.vault.description) }
        val c = caps
        if (c == null) item { Line("Capabilities", "Probing…") } else {
            item { Line("Playback engine", c.engineName) }
            items(c.video) { v ->
                Line(v.codec.uppercase(), "${if (v.hardware) "Hardware" else "Software"} · up to ${v.maxWidth}x${v.maxHeight} · ${v.maxBitDepth}-bit" + (v.decoderName?.let { " · $it" } ?: ""))
            }
            item { Line("HDR output", if (c.hdr.isEmpty()) (if (c.canToneMapHdr) "Tone-mapped to SDR" else "None (server tone-maps)") else c.hdr.joinToString { it.name }) }
            item { Line("Audio decode", c.audioDecode.sorted().joinToString()) }
            item { Line("Audio passthrough", c.audioPassthrough.sorted().joinToString().ifBlank { "None detected" }) }
            item { Line("Max channels", c.maxAudioChannels.toString()) }
            item { Line("Subtitles (embedded)", c.subtitleEmbedded.sorted().joinToString()) }
            item { Line("ASS/SSA", if (c.libassRendering) "libass — full styling" else "Basic styling only") }
            items(c.notes) { Text("• $it", style = AuroraType.caption, color = colors.onSurfaceFaint) }
        }
        item { Header("Open-source licenses") }
        item {
            Text(
                "Aurora TV is free software under the GNU GPL v3. It includes: Kotlin & kotlinx (Apache 2.0), " +
                    "Compose Multiplatform (Apache 2.0), Ktor (Apache 2.0), Coil (Apache 2.0), AndroidX Media3 (Apache 2.0), " +
                    "Jellyfin Media3 FFmpeg decoder (GPL v3, FFmpeg LGPL/GPL), libass-android / ass-media (MIT; libass ISC, FreeType FTL, " +
                    "HarfBuzz MIT, FriBidi LGPL), libmpv (GPL v2+ build), JNA (Apache 2.0/LGPL). Full texts ship in the LICENSES folder.",
                style = AuroraType.caption, color = colors.onSurfaceMuted,
            )
        }
    }
}

@Composable
private fun Line(k: String, v: String) {
    val colors = Aurora.colors
    FocusSurface(onClick = {}, shape = RoundedCornerShape(8.dp), ringWidth = 0.dp, scaleOnFocus = false, modifier = Modifier.fillMaxWidth()) { focused ->
        Row(Modifier.fillMaxWidth().background(if (focused) colors.onBackground.copy(alpha = 0.08f) else colors.background.copy(alpha = 0f)).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(k, style = AuroraType.label, color = colors.onSurfaceFaint, modifier = Modifier.width(220.dp))
            Text(v, style = AuroraType.body, color = colors.onBackground)
        }
    }
}

@Suppress("unused")
private val spacer = Modifier.height(1.dp)
