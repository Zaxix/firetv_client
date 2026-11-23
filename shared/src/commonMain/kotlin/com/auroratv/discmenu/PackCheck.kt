package com.auroratv.discmenu

/** What a pack needs on disk, checked before the menu opens so a missing file is a clear message, not a black screen. */
object PackCheck {
    /** Files without which the menu can't run. */
    fun missingRequired(pack: DiscMenuPackDef, assets: DiscMenuAssets): List<String> {
        val needed = LinkedHashSet<String>()
        needed += pack.video.loop.file
        needed += pack.video.transition.file
        needed += pack.music.file
        needed += pack.sparkles.index
        needed += pack.sparkles.data
        for (p in pack.pages.values) {
            needed += p.plate
            for (h in p.hotspots) { h.selected?.let { needed += it }; h.activated?.let { needed += it } }
        }
        return needed.filterNot(assets::exists)
    }

    /** The pack without the optional parts whose files are absent (an intro or letter the author didn't ship). */
    fun withoutMissingOptional(pack: DiscMenuPackDef, assets: DiscMenuAssets): Pair<DiscMenuPackDef, List<String>> {
        val dropped = mutableListOf<String>()
        val intro = pack.video.intro?.takeIf { assets.exists(it.file) || run { dropped += it.file; false } }
        val letter = pack.video.letter?.takeIf { assets.exists(it.file) || run { dropped += it.file; false } }
        return pack.copy(video = pack.video.copy(intro = intro, letter = letter)) to dropped
    }
}
