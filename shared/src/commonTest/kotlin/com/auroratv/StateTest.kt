package com.auroratv

import com.auroratv.settings.SettingsRepository
import com.auroratv.settings.ThemeMode
import com.auroratv.ui.nav.Navigator
import com.auroratv.ui.nav.Route
import com.auroratv.ui.nav.Section
import com.auroratv.ui.theme.BlurHash
import com.auroratv.ui.theme.DarkAurora
import com.auroratv.ui.theme.LightAurora
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StateTest {

    @Test
    fun themeChoiceIsPersistedAndRestored() {
        val files = MemoryFileStore()
        val repo = SettingsRepository(files)
        assertEquals(ThemeMode.Dark, repo.current.theme)
        repo.update { it.copy(theme = ThemeMode.Light) }
        assertEquals(ThemeMode.Light, repo.settings.value.theme)
        assertEquals(ThemeMode.Light, SettingsRepository(files).current.theme, "Survives relaunch")
        repo.update { it.copy(theme = ThemeMode.System) }
        assertEquals(ThemeMode.System, SettingsRepository(files).current.theme)
    }

    @Test
    fun corruptSettingsFallBackToDefaults() {
        val files = MemoryFileStore().apply { data["settings.json"] = "{broken" }
        assertEquals(ThemeMode.Dark, SettingsRepository(files).current.theme)
    }

    @Test
    fun darkAndLightPalettesDiffer() {
        assertTrue(DarkAurora.isDark); assertFalse(LightAurora.isDark)
        assertNotEquals(DarkAurora.background, LightAurora.background)
        assertNotEquals(DarkAurora.onBackground, LightAurora.onBackground)
    }

    @Test
    fun backStackRestoresScreenStateAndFocusKey() {
        val nav = Navigator(Route.Root(Section.Home))
        val home = nav.current
        home.model("home") { "cached-home-data" }
        home.focus.lastKey = "latest-movies/item42"
        nav.push(Route.Detail("item42"))
        assertTrue(nav.canGoBack)
        assertTrue(nav.pop())
        assertSame(home, nav.current, "Same entry, so data and scroll state are kept")
        assertEquals("cached-home-data", nav.current.model("home") { "fresh" })
        assertEquals("latest-movies/item42", nav.current.focus.lastKey)
        assertTrue(nav.restoringFocus)
        assertFalse(nav.pop(), "Root can't be popped")
    }

    @Test
    fun selectingSectionResetsStack() {
        val nav = Navigator(Route.Root(Section.Home))
        nav.push(Route.Detail("a")); nav.push(Route.Detail("b"))
        nav.selectSection(Section.Search)
        assertFalse(nav.canGoBack)
        assertEquals(Section.Search, nav.currentSection)
    }

    @Test
    fun blurHashAverageColourAndAccent() {
        val hash = "LEHV6nWB2yk8pyo0adR*.7kCMdnj" // reference hash from blurha.sh
        val avg = assertNotNull(BlurHash.averageColor(hash))
        assertTrue(avg.red > 0f)
        assertNull(BlurHash.averageColor("x"))
        assertNull(BlurHash.accentFrom(hash, dark = true), "Near-grey artwork keeps the theme accent")
        val blue = "005?~E" // 1x1 BlurHash of #3366CC
        val avgBlue = assertNotNull(BlurHash.averageColor(blue))
        assertEquals(0x33 / 255f, avgBlue.red, 0.01f)
        val accent = assertNotNull(BlurHash.accentFrom(blue, dark = true))
        assertTrue(accent.blue > accent.red, "Accent keeps the artwork's hue")
    }
}
