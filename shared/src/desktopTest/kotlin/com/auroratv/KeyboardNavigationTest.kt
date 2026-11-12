package com.auroratv

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.auroratv.settings.AnimationLevel
import com.auroratv.settings.ThemeMode
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.arrowKeyNavigation
import com.auroratv.ui.theme.AuroraTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Remote / keyboard navigation. Compose maps the Fire TV D-pad (KEYCODE_DPAD_*) and the PC arrow keys
 * to the same Key.Direction* values, so these tests cover both input paths through the shared UI.
 */
@OptIn(ExperimentalTestApi::class)
class KeyboardNavigationTest {

    @Test
    fun arrowKeysMoveFocusAcrossARailAndDownToTheNextRow() = runComposeUiTest {
        setContent {
            AuroraTheme(ThemeMode.Dark, AnimationLevel.Off) {
                Column(Modifier.arrowKeyNavigation()) {
                    Row { repeat(3) { i -> FocusSurface(onClick = {}, modifier = Modifier.size(120.dp).testTag("top$i")) {} } }
                    Row { repeat(3) { i -> FocusSurface(onClick = {}, modifier = Modifier.size(120.dp).testTag("bottom$i")) {} } }
                }
            }
        }
        onNodeWithTag("top0").requestFocus()
        onNodeWithTag("top0").assertIsFocused()
        onNodeWithTag("top0").performKeyInput { pressKey(Key.DirectionRight) }
        onNodeWithTag("top1").assertIsFocused()
        onNodeWithTag("top1").performKeyInput { pressKey(Key.DirectionDown) }
        onNodeWithTag("bottom1").assertIsFocused()
        onNodeWithTag("bottom1").performKeyInput { pressKey(Key.DirectionLeft) }
        onNodeWithTag("bottom0").assertIsFocused()
        onNodeWithTag("bottom0").performKeyInput { pressKey(Key.DirectionUp) }
        onNodeWithTag("top0").assertIsFocused()
    }

    @Test
    fun blurHashDecodesToPlaceholderBitmap() {
        val bmp = com.auroratv.ui.theme.BlurHash.decode("LEHV6nWB2yk8pyo0adR*.7kCMdnj", 8, 4)
        kotlin.test.assertNotNull(bmp)
        assertEquals(8, bmp.width)
    }

    @Test
    fun enterActivatesTheFocusedCard() = runComposeUiTest {
        var clicks by mutableIntStateOf(0)
        setContent {
            AuroraTheme(ThemeMode.Light, AnimationLevel.Off) {
                FocusSurface(onClick = { clicks++ }, modifier = Modifier.size(120.dp).testTag("card")) {}
            }
        }
        onNodeWithTag("card").requestFocus()
        onNodeWithTag("card").performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(1, clicks)
    }
}
