package com.auroratv

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import com.auroratv.ratings.ExternalRatings
import com.auroratv.ratings.Review
import com.auroratv.settings.AnimationLevel
import com.auroratv.settings.ThemeMode
import com.auroratv.ui.components.ScreenStart
import com.auroratv.ui.screens.RatingsStrip
import com.auroratv.ui.screens.ReviewOverlay
import com.auroratv.ui.screens.ReviewsRow
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertNull

/**
 * Renders the ratings strip, review cards and the full-review overlay with sample data (the CI
 * server has no MDBList/Trakt keys) and saves screenshots next to the test reports.
 */
@OptIn(ExperimentalTestApi::class)
class RatingsUiTest {
    private val ratings = ExternalRatings(tomatometer = 88, popcornmeter = 94, imdb = 8.3, imdbVotes = 745_000, metacritic = 76, letterboxd = 4.1, source = "MDBList")
    private val rotten = ExternalRatings(tomatometer = 41, popcornmeter = 52, source = "MDBList")
    private val reviews = listOf(
        Review(1, "Dana R", 9, "An absolute masterclass in tension. The diner scene alone is worth the price of admission, and the score never lets up for a second.", false, 120, "2021-03-01"),
        Review(2, "critic99", 8, "A long and thoughtful review of the craft, the score and the performances. ".repeat(12), true, 3, "2020-01-02"),
        Review(3, "nightowl", null, "Rewatched it on a big screen and noticed how much of the city's sound design carries the story between the set pieces.", false, 8, "2024-11-30"),
    )

    private fun SemanticsNodeInteractionsProvider.save(name: String) {
        val dir = File("build/reports/tests/ui-shots").apply { mkdirs() }
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, name))
    }

    @Test
    fun ratingsAndReviewsRenderAndTheOverlayOpensAndCloses() = runDesktopComposeUiTest(1280, 720) {
        var opened by mutableStateOf<Review?>(null)
        setContent {
            AuroraTheme(ThemeMode.Dark, AnimationLevel.Off) {
                val focus = remember { FocusRequester() }
                Box(Modifier.fillMaxSize().background(Aurora.colors.background)) {
                    Column(Modifier.padding(top = 48.dp)) {
                        Box(Modifier.padding(start = ScreenStart)) { RatingsStrip(ratings) }
                        Spacer(Modifier.height(20.dp))
                        Box(Modifier.padding(start = ScreenStart)) { RatingsStrip(rotten) }
                        Spacer(Modifier.height(40.dp))
                        ReviewsRow(reviews, focus) { opened = it }
                    }
                    opened?.let { ReviewOverlay(it) { opened = null } }
                }
            }
        }
        waitForIdle()
        onNodeWithContentDescription("Audience 94%").assertExists()
        onNodeWithContentDescription("Critics 41%").assertExists()
        onNodeWithContentDescription("Rated 9 out of 10", useUnmergedTree = true).assertExists()
        save("ratings-and-reviews.png")

        onNodeWithText("critic99").performClick()
        waitForIdle()
        save("review-overlay.png")
        onRoot().performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertNull(opened, "Back/Escape closes the full review")
    }
}
