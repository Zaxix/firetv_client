package com.auroratv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import com.auroratv.update.ReleaseInfo
import com.auroratv.update.UpdateManager
import com.auroratv.update.UpdateState

/**
 * The remote's keys while the update card is up. The app shell hands it every key before anything
 * else sees it, so the card works even if focus is still on the screen underneath (Home grabs focus
 * for its banner as it opens, which is exactly when the card appears).
 */
class UpdatePromptKeys(private val updates: UpdateManager) {
    var selected by mutableIntStateOf(0)
    private val busy get() = updates.state.value.let { it is UpdateState.Downloading || it is UpdateState.Installing }

    fun activate(index: Int) {
        if (busy) return
        if (index == 0) updates.install() else updates.later()
    }

    /** Consumes every key (nothing behind the card reacts); Left/Right choose, OK activates, Back = Later. */
    fun onKey(ev: androidx.compose.ui.input.key.KeyEvent): Boolean {
        val up = ev.type == KeyEventType.KeyUp
        when (ev.key) {
            Key.DirectionLeft -> if (up) selected = 0
            Key.DirectionRight -> if (up) selected = 1
            Key.Enter, Key.DirectionCenter, Key.NumPadEnter, Key.Spacebar -> if (up) activate(selected)
            Key.Back, Key.Escape -> if (up && !busy) updates.later()
        }
        return true
    }
}

/** "Update available" card over the app. Left/Right pick a button, OK activates, Back means Later. */
@Composable
fun UpdatePrompt(updates: UpdateManager, info: ReleaseInfo, keys: UpdatePromptKeys) {
    val state by updates.state.collectAsState()
    val colors = Aurora.colors
    val focus = remember { FocusRequester() }
    val selected = keys.selected
    val busy = state is UpdateState.Downloading || state is UpdateState.Installing
    val selfInstall = (state as? UpdateState.Available)?.asset != null || updates.updater?.pickAsset(info.assets.keys) != null
    val primaryLabel = when {
        state is UpdateState.Failed && (state as UpdateState.Failed).info != null -> "Try again"
        selfInstall -> "Update now"
        else -> "Open download page"
    }
    // Also take focus (keeps the screen underneath from showing a focused card through the scrim);
    // the keys work regardless, see UpdatePromptKeys.
    LaunchedEffect(Unit) { repeat(10) { runCatching { focus.requestFocus() }; kotlinx.coroutines.delay(100) } }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxWidth(0.62f).clip(RoundedCornerShape(24.dp)).background(colors.surfaceRaised).padding(36.dp)) {
            Text("Update available", style = AuroraType.caption, color = colors.onSurfaceFaint)
            Spacer(Modifier.height(6.dp))
            Text("${com.auroratv.AppInfo.NAME} ${info.version}", style = AuroraType.title, color = colors.onBackground)
            updates.updater?.let { Text("You have ${it.currentVersion}", style = AuroraType.body, color = colors.onSurfaceMuted) }
            if (info.changes.isNotEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text("What's new", style = AuroraType.label, color = colors.onBackground)
                Spacer(Modifier.height(6.dp))
                info.changes.take(6).forEach { Text("•  " + it.substringBefore(';').take(140), style = AuroraType.body, color = colors.onSurfaceMuted, maxLines = 2) }
            }
            Spacer(Modifier.height(22.dp))
            when (val s = state) {
                is UpdateState.Downloading -> {
                    Text("Downloading… ${(s.progress * 100).toInt()}%", style = AuroraType.bodyStrong, color = colors.onBackground)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)), color = colors.accent, trackColor = colors.onBackground.copy(alpha = 0.15f))
                }
                is UpdateState.Installing -> Text(
                    if (updates.updater?.pickAsset(info.assets.keys)?.endsWith(".apk") == true) "Confirm the install on the next screen. ${com.auroratv.AppInfo.NAME} restarts when it's done."
                    else "Installing: ${com.auroratv.AppInfo.NAME} will close and reopen in a moment.",
                    style = AuroraType.bodyStrong, color = colors.onBackground,
                )
                is UpdateState.Failed -> Text(s.message, style = AuroraType.body, color = Color(0xFFFF8A80))
                else -> if (!selfInstall) Text(
                    "This copy can't update itself (portable or developer build). The download page has the new version.",
                    style = AuroraType.body, color = colors.onSurfaceMuted,
                )
            }
            if (!busy) {
                Spacer(Modifier.height(22.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    PromptButton(primaryLabel, selected == 0) { keys.selected = 0; keys.activate(0) }
                    PromptButton("Later", selected == 1) { keys.selected = 1; keys.activate(1) }
                }
            }
        }
    }
}

@Composable
private fun PromptButton(text: String, highlighted: Boolean, onClick: () -> Unit) {
    val colors = Aurora.colors
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (highlighted) colors.onBackground else colors.onBackground.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 14.dp),
    ) {
        Text(text, style = AuroraType.label, color = if (highlighted) colors.background else colors.onBackground)
    }
}

/** One-line status for Settings → About. */
fun updateStatusText(state: UpdateState, supported: Boolean): String = when (state) {
    UpdateState.Idle -> if (supported) "Not checked yet" else "Download new builds from the release page"
    UpdateState.Checking -> "Checking…"
    is UpdateState.UpToDate -> "Up to date" + (state.latest?.let { " (latest build ${it.version})" } ?: "")
    is UpdateState.Available -> "${state.info.version} is available"
    is UpdateState.Downloading -> "Downloading ${state.info.version}… ${(state.progress * 100).toInt()}%"
    is UpdateState.Installing -> "Installing ${state.info.version}…"
    is UpdateState.Failed -> state.message
}
