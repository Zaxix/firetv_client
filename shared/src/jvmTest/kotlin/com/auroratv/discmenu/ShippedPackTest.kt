package com.auroratv.discmenu

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Checks the measured hp1-uk-disc1 pack's configuration (coordinates and graph only; its media are not in the repository). */
class ShippedPackTest {
    private val pack: DiscMenuPackDef by lazy {
        val text = checkNotNull(javaClass.classLoader.getResource("discmenu/hp1-uk-disc1.pack.json")) { "fixture missing" }.readText()
        DiscMenuPackDef.parse(text)
    }

    @Test
    fun everyActionTargetExistsAndEveryRectIsValid() {
        val problems = mutableListOf<String>()
        fun checkAction(where: String, a: String) {
            if (a.startsWith("goto:")) {
                val t = a.removePrefix("goto:")
                if (t != "main" && t !in pack.pages) problems += "$where -> unknown page $t"
            }
        }
        pack.main.buttons.forEach { b -> b.rect.toRect(b.id); checkAction("main.${b.id}", b.action) }
        for ((pid, p) in pack.pages) {
            assertTrue(p.hotspots.any { it.id == p.default }, "$pid default '${p.default}' missing")
            p.backTo?.let { assertNotNull(pack.pages[it], "$pid backTo") }
            for (h in p.hotspots) {
                val r = h.rect.toRect("$pid.${h.id}")
                if (r.width <= 0 || r.height <= 0 || r.right > pack.canvas.w || r.bottom > pack.canvas.h) problems += "$pid.${h.id} rect out of canvas: $r"
                checkAction("$pid.${h.id}", h.action)
            }
        }
        for ((name, seq) in pack.sequences) seq.forEach { if (it !in pack.pages) problems += "sequence $name has unknown page $it" }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun everyHotspotOnEveryPageIsReachableFromItsDefaultWithTheArrowKeys() {
        val unreachable = mutableListOf<String>()
        for ((pid, p) in pack.pages) {
            val items = p.hotspots.map { NavItem(it.id, it.rect.toRect()) }
            val seen = mutableSetOf(p.default)
            val queue = ArrayDeque(listOf(p.default))
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst()
                for (d in Dir.entries) MenuGeometry.neighbour(cur, items, d)?.let { if (seen.add(it)) queue += it }
            }
            val missing = items.map { it.id }.toSet() - seen
            if (missing.isNotEmpty()) unreachable += "$pid: $missing"
        }
        assertEquals(emptyList(), unreachable)
    }

    @Test
    fun theMainMenuWalksInOrderAndBack() {
        val items = pack.main.buttons.map { NavItem(it.id, it.rect.toRect().padded(pack.main.hotspotPadPx)) }
        var cur = "play_movie"
        val order = mutableListOf(cur)
        repeat(3) { cur = MenuGeometry.neighbour(cur, items, Dir.Down)!!; order += cur }
        assertEquals(listOf("play_movie", "special_features", "scene_selections", "languages"), order)
        repeat(3) { cur = MenuGeometry.neighbour(cur, items, Dir.Up)!! }
        assertEquals("play_movie", cur)
    }

    @Test
    fun theSceneHotspotsCoverThirtyFiveChaptersExactly() {
        val numbers = pack.pages.filterKeys { it.startsWith("ss") }.values.flatMap { it.hotspots }.map { it.action }
            .filter { it.startsWith("chapter:") && it != "chapter:last" }.map { it.removePrefix("chapter:").toInt() }.toSortedSet()
        assertEquals((1..35).toList(), numbers.toList())
    }

    @Test
    fun theIntroAndLetterAreDescribedWithMeasuredNumbers() {
        val intro = assertNotNull(pack.video.intro)
        assertEquals(42_233L, intro.durationMs)
        assertEquals(400L, intro.crossfadeMs)
        assertNotNull(pack.video.letter)
        assertEquals(null, pack.timing.playMovie?.letterHoldBeforePlaybackMs) // UNKNOWN stays unknown
    }
}
