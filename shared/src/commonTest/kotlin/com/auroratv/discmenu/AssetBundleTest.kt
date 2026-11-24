package com.auroratv.discmenu

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssetBundleTest {
    private val members = listOf(
        "page_sprites/ss1/tag_1.webp" to byteArrayOf(1, 2, 3),
        "page_sprites/ss1/tag_2.webp" to byteArrayOf(9),
        "page_sprites/sf/main_menu_sel.webp" to ByteArray(300) { (it % 251).toByte() },
    )

    private fun entriesOf(bytes: ByteArray): List<AssetBundle.Entry>? {
        val n = AssetBundle.headerLength(bytes.copyOf(AssetBundle.PREFIX_BYTES)) ?: return null
        return AssetBundle.parseEntries(bytes.copyOfRange(AssetBundle.PREFIX_BYTES, AssetBundle.PREFIX_BYTES + n), bytes.size.toLong())
    }

    @Test
    fun everyMemberComesBackByteForByteAtItsPath() {
        val bytes = AssetBundle.build(members)
        val entries = assertNotNull(entriesOf(bytes))
        assertEquals(members.map { it.first }, entries.map { it.path })
        for ((path, data) in members) {
            val e = entries.single { it.path == path }
            assertContentEquals(data, bytes.copyOfRange(e.offset.toInt(), e.offset.toInt() + e.length))
        }
    }

    @Test
    fun theManifestCanListWhatIsInsideWithoutReadingTheFile() {
        val bytes = AssetBundle.build(members)
        val n = AssetBundle.headerLength(bytes)!!
        assertEquals(members.map { it.first }, AssetBundle.paths(bytes.copyOfRange(AssetBundle.PREFIX_BYTES, AssetBundle.PREFIX_BYTES + n)))
    }

    @Test
    fun aFileThatIsNotABundleOrIsCutShortIsRefused() {
        val bytes = AssetBundle.build(members)
        assertNull(AssetBundle.headerLength("RIFFxxxxxxxx".encodeToByteArray()))
        assertNull(AssetBundle.headerLength(bytes.copyOf(5)))
        assertNull(entriesOf(bytes.copyOf(bytes.size - 1)), "a member that runs past the end of the file")
    }

    @Test
    fun unsafeOrRepeatedPathsAreRefused() {
        assertNull(entriesOf(AssetBundle.build(listOf("../x.webp" to byteArrayOf(1)))))
        assertNull(entriesOf(AssetBundle.build(listOf("a.webp" to byteArrayOf(1), "a.webp" to byteArrayOf(2)))))
    }

    @Test
    fun theManifestViewCountsBundledFilesAsPresentAndTheirChecksCatchBadListings() {
        val bundle = ManifestFile("page_sprites.bundle", 400, "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", 2, listOf("page_sprites/a.webp"))
        val pack = ManifestFile("pack.json", 10, bundle.sha256, 0)
        val m = PackManifest(1, "mini", listOf(pack, bundle))
        assertEquals(emptyList(), m.problems())
        val view = ManifestAssets(m)
        assertTrue(view.exists("page_sprites/a.webp"))
        assertFalse(view.exists("page_sprites/b.webp"))
        val twice = m.copy(files = m.files + bundle.copy(path = "other.bundle"))
        assertTrue(twice.problems().any { "more than one bundle" in it })
        val clash = m.copy(files = m.files + ManifestFile("page_sprites/a.webp", 5, bundle.sha256, 2))
        assertTrue(clash.problems().any { "both a file and inside a bundle" in it })
    }
}
