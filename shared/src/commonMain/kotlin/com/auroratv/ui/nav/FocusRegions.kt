package com.auroratv.ui.nav

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Directional focus for the main screens: one place that decides where the D-pad may take focus.
 *
 * Compose moves focus geometrically: anything focusable that lies in the pressed direction is a
 * candidate. That's what sent Right at the end of a row, or Right out of the sidebar, to the profile
 * button in the top-right corner (the only thing "to the right"), and Down from there went wherever
 * geometry pointed. The screen is split into three regions with explicit rules instead:
 *
 * - **Sidebar** (left): only entered on purpose (Left from the content, Back). Right or Back closes
 *   it and returns to the card or button that had focus before. See SideBarState.
 * - **Content** (the screen): Right or Down past its edge stays put ([contentRegion]); rows keep
 *   Right at their last card ([focusRow]).
 * - **Header** (the profile button): never a geometric candidate. Up from the top of a screen opens
 *   it ([openHeader]); Down or Back returns to the content's last focus; Left/Right/Up do nothing.
 */
class FocusRegions {
    /** The screen area; requesting it restores the card/button that last had focus there. */
    val content = FocusRequester()
    val header = FocusRequester()
    var headerHasFocus by mutableStateOf(false)
        private set
    private var headerEntry by mutableStateOf(false)

    /** Moves to the profile button (the only way in). */
    fun openHeader(): Boolean {
        headerEntry = true
        return try { runCatching { header.requestFocus() }.isSuccess } finally { headerEntry = false }
    }

    /** From the header back to the content's last focus; false if the screen had nothing to take it. */
    fun focusContent(): Boolean {
        runCatching { content.requestFocus() }
        return !headerHasFocus
    }

    /** For the header element: reachable only through [openHeader]; Down goes back to the content. */
    fun headerModifier(): Modifier = Modifier
        .focusRequester(header)
        .onFocusChanged { headerHasFocus = it.hasFocus }
        .focusProperties {
            canFocus = headerEntry || headerHasFocus
            left = FocusRequester.Cancel
            right = FocusRequester.Cancel
            up = FocusRequester.Cancel
            down = content
        }
}

val LocalFocusRegions = staticCompositionLocalOf { FocusRegions() }

/**
 * The screen content region: focus never leaves it sideways to the right, downwards or upwards
 * (the header is opened explicitly, the sidebar has its own entry rule), so an edge keeps focus.
 */
fun Modifier.contentRegion(): Modifier = focusProperties {
    onExit = {
        when (requestedFocusDirection) {
            FocusDirection.Right, FocusDirection.Down, FocusDirection.Up -> cancelFocusChange()
            else -> Unit
        }
    }
}.focusGroup()

/**
 * A horizontal row: Right at its last item keeps focus there instead of jumping diagonally to
 * whatever lies further right on screen. Left still leaves (towards the sidebar), Up/Down move
 * between rows. Put it outside the row's own focusRestorer.
 */
fun Modifier.focusRow(): Modifier = focusProperties {
    onExit = { if (requestedFocusDirection == FocusDirection.Right) cancelFocusChange() }
}.focusGroup()
