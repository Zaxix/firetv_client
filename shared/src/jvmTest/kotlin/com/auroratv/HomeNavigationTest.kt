package com.auroratv

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.auroratv.jellyfin.ImageUrls
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.settings.AnimationLevel
import com.auroratv.settings.ThemeMode
import com.auroratv.ui.components.CardStyle
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.MinimalBringIntoViewSpec
import com.auroratv.ui.nav.FocusRegions
import com.auroratv.ui.nav.LocalFocusRegions
import com.auroratv.ui.nav.contentRegion
import com.auroratv.ui.screens.HomeRowFocus
import com.auroratv.ui.screens.RailState
import com.auroratv.ui.screens.homeRails
import com.auroratv.ui.screens.homeRowNavigation
import com.auroratv.ui.screens.homeStops
import com.auroratv.ui.theme.AuroraTheme
import kotlinx.coroutines.awaitCancellation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Home's rows driven by the remote, in the shell's real focus setup: the content region, the
 * profile button that only Up from the top reaches, and Home's own rows ([homeRails]) and row
 * navigation ([homeRowNavigation]). Rows can be loaded, empty, still loading, or change while
 * focused. Regression tests for "Up from the lower rows jumps to the profile button".
 */
@OptIn(ExperimentalTestApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
class HomeNavigationTest {
    private fun cards(row: Int, n: Int) = (0 until n).map { BaseItemDto(id = "r$row-i$it", name = "R$row.$it", type = "Movie") }

    /** A row: loaded with [n] cards, empty (0), or still loading (null; its loader never answers). */
    private fun rail(row: Int, n: Int?, style: CardStyle = CardStyle.Poster, timeoutMs: Long = 600_000) =
        RailState("row$row", "Row $row", style, timeoutMs) { kotlinx.coroutines.awaitCancellation() }.also { if (n != null) it.items = cards(row, n) }

    private val list = mutableListOf<RailState>()
    private val rails = androidx.compose.runtime.mutableStateOf<List<RailState>>(emptyList())

    @Composable
    private fun Shell() {
        val regions = remember { FocusRegions() }
        val fm = LocalFocusManager.current
        val rowFocus = remember { HomeRowFocus() }
        val listState = rememberLazyListState()
        val scope = rememberCoroutineScope()
        val heroFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        rowFocus.register("hero", heroFocus)
        AuroraTheme(ThemeMode.Dark, AnimationLevel.Off) {
            CompositionLocalProvider(LocalFocusRegions provides regions, androidx.compose.foundation.gestures.LocalBringIntoViewSpec provides MinimalBringIntoViewSpec) {
                Box(Modifier.size(960.dp, 540.dp).onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (ev.key) {
                        // As App.kt: Up that nothing on screen used opens the profile button.
                        Key.DirectionUp -> { if (!regions.headerHasFocus && !fm.moveFocus(FocusDirection.Up)) regions.openHeader(); true }
                        Key.DirectionDown -> { if (regions.headerHasFocus) regions.focusContent() else fm.moveFocus(FocusDirection.Down); true }
                        Key.DirectionLeft -> { fm.moveFocus(FocusDirection.Left); true }
                        Key.DirectionRight -> { fm.moveFocus(FocusDirection.Right); true }
                        else -> false
                    }
                }) {
                    Box(Modifier.fillMaxSize().contentRegion()) {
                        Box(Modifier.fillMaxSize().focusRequester(regions.content).focusGroup().focusRestorer()) {
                            LazyColumn(
                                state = listState, contentPadding = PaddingValues(bottom = 64.dp), verticalArrangement = Arrangement.spacedBy(28.dp),
                                modifier = Modifier.fillMaxSize().homeRowNavigation(rowFocus, listState, { homeStops(listOf("hero"), rails.value) }, { !regions.headerHasFocus }),
                            ) {
                                item(key = "hero") {
                                    Box(Modifier.fillMaxWidth().height(432.dp).onFocusChanged { if (it.hasFocus) rowFocus.focused("hero", 0) }) {
                                        FocusSurface(onClick = {}, modifier = Modifier.size(120.dp, 40.dp).align(Alignment.BottomStart).testTag("play").focusRequester(heroFocus)) {}
                                    }
                                }
                                homeRails(rails.value, 1, rowFocus, ImageUrls("http://localhost"), load = { r -> scope.launch { r.ensureLoaded() } },
                                    onRetry = {}, onItemClick = {}, onItemFocused = { _, _ -> })
                            }
                        }
                    }
                    FocusSurface(onClick = {}, modifier = Modifier.align(Alignment.TopEnd).size(44.dp).testTag("profile").then(regions.headerModifier())) {}
                }
            }
        }
    }

    private fun ComposeUiTest.focused(): String {
        val n = onAllNodes(isFocused()).fetchSemanticsNodes().firstOrNull() ?: return "-"
        return n.config.getOrNull(SemanticsProperties.TestTag) ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text } ?: "?"
    }

    private fun ComposeUiTest.press(k: Key, times: Int = 1): List<String> = (1..times).map {
        onRoot().performKeyInput { pressKey(k) }
        mainClock.advanceTimeBy(400)
        waitForIdle()
        focused()
    }

    private fun ComposeUiTest.start(rows: List<RailState>) {
        rails.value = rows
        setContent { Shell() }
        waitForIdle()
        onNode(SemanticsMatcher.expectValue(SemanticsProperties.TestTag, "play")).requestFocus()
        waitForIdle()
    }

    /** The row each focus stop is in ("play", "profile", "R5", …). */
    private fun rowsOf(path: List<String>) = path.map { it.substringBefore('.') }

    private fun standard(loading: IntRange? = null, empty: IntRange? = null) =
        listOf(rail(0, 8, CardStyle.Landscape), rail(1, 8, CardStyle.Landscape)) +
            (2..8).map { r -> rail(r, when { loading != null && r in loading -> null; empty != null && r in empty -> 0; else -> 12 }) }

    @Test
    fun downToTheLastRowAndBackUpToTheTop() = runComposeUiTest {
        start(standard())
        val down = press(Key.DirectionDown, 10)
        println("loaded DOWN: $down")
        assertEquals(listOf("R0", "R1", "R2", "R3", "R4", "R5", "R6", "R7", "R8", "R8"), rowsOf(down), "Down walks every row and stops at the last")
        press(Key.DirectionRight, 3)
        val up = press(Key.DirectionUp, 10)
        println("loaded UP: $up")
        assertEquals(listOf("R7", "R6", "R5", "R4", "R3", "R2", "R1", "R0", "play", "profile"), rowsOf(up), "Up walks back row by row; only Up from the banner reaches the profile button")
        // Back from the profile button to the banner.
        assertEquals("play", press(Key.DirectionDown).single())
    }

    @Test
    fun rowsStillLoadingAreSkippedBothWays() = runComposeUiTest {
        start(standard(loading = 2..4))
        val down = press(Key.DirectionDown, 8)
        println("loading DOWN: $down")
        assertEquals(listOf("R0", "R1", "R5", "R6", "R7", "R8", "R8", "R8"), rowsOf(down))
        val up = press(Key.DirectionUp, 7)
        println("loading UP: $up")
        assertEquals(listOf("R7", "R6", "R5", "R1", "R0", "play", "profile"), rowsOf(up))
    }

    @Test
    fun emptyRowsAreSkippedBothWays() = runComposeUiTest {
        start(standard(empty = 2..4))
        assertEquals(listOf("R0", "R1", "R5", "R6", "R7", "R8"), rowsOf(press(Key.DirectionDown, 6)))
        assertEquals(listOf("R7", "R6", "R5", "R1", "R0", "play", "profile"), rowsOf(press(Key.DirectionUp, 7)))
    }

    @Test
    fun manyPlaceholdersInARowDontStrandFocus() = runComposeUiTest {
        // No Continue Watching or Next Up; Top 10 and three recommendation rows still loading, an
        // empty one; then the libraries.
        start((2..5).map { rail(it, null) } + rail(6, 0) + (7..9).map { rail(it, 12) })
        val down = press(Key.DirectionDown, 4)
        println("placeholders DOWN: $down")
        assertEquals(listOf("R7", "R8", "R9", "R9"), rowsOf(down))
        val up = press(Key.DirectionUp, 4)
        println("placeholders UP: $up")
        assertEquals(listOf("R8", "R7", "play", "profile"), rowsOf(up))
    }

    @Test
    fun rowsThatFinishLoadingJoinThePath() = runComposeUiTest {
        val rows = standard(loading = 2..4)
        start(rows)
        press(Key.DirectionDown, 8)
        // While focus is down at the bottom, the rows above finish loading.
        (2..4).forEach { r -> rows[r].items = cards(r, 12) }
        mainClock.advanceTimeBy(400); waitForIdle()
        assertTrue(focused().startsWith("R8"), "focus stays put while rows above load: ${focused()}")
        val up = press(Key.DirectionUp, 10)
        println("late rows UP: $up")
        assertEquals(listOf("R7", "R6", "R5", "R4", "R3", "R2", "R1", "R0", "play", "profile"), rowsOf(up))
    }

    @Test
    fun rowsWithDifferentLengths() = runComposeUiTest {
        start((0..6).map { rail(it, if (it % 2 == 0) 2 else 12) })
        press(Key.DirectionDown, 2)
        val right = press(Key.DirectionRight, 5)
        assertEquals("R1.5", right.last(), "Right moves along a row")
        assertEquals(listOf("R2", "R3", "R4", "R5", "R6"), rowsOf(press(Key.DirectionDown, 5)), "a short row in between doesn't stop Down")
        assertEquals(listOf("R5", "R4", "R3", "R2", "R1", "R0", "play"), rowsOf(press(Key.DirectionUp, 7)))
    }

    @Test
    fun focusComesBackToANearbyRowWhenTheFocusedCardGoesAway() = runComposeUiTest {
        val rows = standard()
        start(rows)
        press(Key.DirectionDown, 7) // R6
        press(Key.DirectionRight, 2) // R6.2
        assertEquals("R6.2", focused())
        // The row reloads without the focused card: focus stays in the row.
        rows[6].items = cards(6, 12).filter { it.id != "r6-i2" }
        mainClock.advanceTimeBy(400); waitForIdle()
        println("after reload: ${focused()}")
        assertTrue(focused().startsWith("R6."), "focus stays in its row: ${focused()}")
        // The row empties (and leaves the screen): focus goes to the row now in its place.
        rows[6].items = emptyList()
        mainClock.advanceTimeBy(400); waitForIdle()
        println("after row emptied: ${focused()}")
        assertTrue(focused().startsWith("R7.") || focused().startsWith("R5."), "focus on a neighbouring row: ${focused()}")
        // And the way up still works.
        val up = press(Key.DirectionUp, 2)
        assertTrue(up.none { it == "profile" }, "Up after recovery: $up")
    }

    @Test
    fun rapidAndHeldPressesNeverReachTheProfileButtonFromBelow() = runComposeUiTest {
        start(standard(loading = 3..4))
        press(Key.DirectionDown, 9)
        // Six quick presses (as fast as a held button repeats), then let things settle.
        onRoot().performKeyInput { repeat(6) { pressKey(Key.DirectionUp, 40) } }
        mainClock.advanceTimeBy(1_000); waitForIdle()
        val at = focused()
        println("after rapid Up: $at")
        assertTrue(at.startsWith("R") || at == "play", "still on Home's rows: $at")
        val rest = listOf(at) + press(Key.DirectionUp, 10)
        val first = rest.indexOf("profile")
        assertTrue(first > 0 && rest[first - 1] == "play", "the profile button only after the banner: $rest")
    }

    @Test
    fun aRowThatNeverAnswersEndsWithTryAgain() = runComposeUiTest {
        val hung = RailState("row0", "Row 0", CardStyle.Poster, attemptTimeoutMs = 1_000) { kotlinx.coroutines.awaitCancellation() }
        start(listOf(hung, rail(1, 8), rail(2, 8)))
        // Three attempts (a second each) with pauses between them (the loads' own clock, not the
        // frame clock): then the placeholder becomes Try again.
        waitUntil(timeoutMillis = 20_000) { mainClock.advanceTimeBy(100); hung.failed }
        waitForIdle()
        assertTrue(hung.failed, "the row gave up")
        val down = press(Key.DirectionDown, 2)
        println("after timeout DOWN: $down")
        assertEquals(listOf("Try again", "R1"), rowsOf(down), "the Try again button is on the path")
        assertEquals(listOf("Try again", "play"), press(Key.DirectionUp, 2))
    }
}
