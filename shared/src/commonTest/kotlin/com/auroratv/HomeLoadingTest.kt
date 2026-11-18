package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.settings.Settings
import com.auroratv.settings.effectiveInterfaceScale
import com.auroratv.ui.components.CardStyle
import com.auroratv.ui.screens.RailState
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeLoadingTest {
    private val item = BaseItemDto(id = "1", name = "Show", type = "Series")

    @Test
    fun railRetriesBeforeGivingUp() = runTest {
        var calls = 0
        val rail = RailState("latest-shows", "Recently Added in Shows", CardStyle.Poster) {
            calls++
            if (calls < 3) error("timeout") else listOf(item)
        }
        assertEquals(listOf(item), rail.ensureLoaded())
        assertEquals(3, calls)
        assertFalse(rail.failed)
    }

    @Test
    fun failedRailTriesAgainNextTime() = runTest {
        var fail = true
        var calls = 0
        val rail = RailState("latest-shows", "Recently Added in Shows", CardStyle.Poster) {
            calls++
            if (fail) error("server busy") else listOf(item)
        }
        assertTrue(rail.ensureLoaded().isEmpty())
        assertTrue(rail.failed)
        assertEquals(3, calls)
        fail = false
        assertEquals(listOf(item), rail.ensureLoaded())
        assertFalse(rail.failed)
    }

    @Test
    fun heroAndRailShareOneLoad() = runTest {
        var calls = 0
        val rail = RailState("latest-movies", "Recently Added in Movies", CardStyle.Poster) { calls++; listOf(item) }
        val results = List(3) { async { rail.ensureLoaded() } }.awaitAll()
        assertEquals(1, calls)
        assertTrue(results.all { it == listOf(item) })
    }

    @Test
    fun interfaceScaleIsSmallerOnTvsByDefault() {
        assertEquals(80, Settings().effectiveInterfaceScale(isTv = true))
        assertEquals(100, Settings().effectiveInterfaceScale(isTv = false))
        assertEquals(90, Settings(interfaceScalePercent = 90).effectiveInterfaceScale(isTv = true))
        assertEquals(80, Settings(interfaceScalePercent = 5).effectiveInterfaceScale(isTv = true))
    }
}
