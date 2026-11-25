package com.auroratv

import com.auroratv.core.AuroraJson
import com.auroratv.settings.Settings
import com.auroratv.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals

/** The build's disc menu source address is filled in once per profile, without overriding a choice. */
class BuiltInSourceTest {
    private val built = "https://example.invalid/menus"

    private fun saved(files: MemoryFileStore, name: String = "settings.json") =
        AuroraJson.decodeFromString(Settings.serializer(), files.read(name)!!)

    @Test
    fun aNewInstallStartsWithIt() {
        val repo = SettingsRepository(MemoryFileStore(), built)
        assertEquals(built, repo.current.discMenuSource)
    }

    @Test
    fun anExistingInstallWithoutAnAddressGetsIt() {
        val files = MemoryFileStore()
        files.write("settings.json", AuroraJson.encodeToString(Settings.serializer(), Settings(discMenuSource = "")))
        val repo = SettingsRepository(files, built)
        assertEquals(built, repo.current.discMenuSource)
        repo.switchProfile("p1")
        assertEquals(built, saved(files).discMenuSource)
    }

    @Test
    fun anAddressTypedInSettingsIsKept() {
        val files = MemoryFileStore()
        files.write("settings.json", AuroraJson.encodeToString(Settings.serializer(), Settings(discMenuSource = "https://mine.example/menus")))
        assertEquals("https://mine.example/menus", SettingsRepository(files, built).current.discMenuSource)
    }

    @Test
    fun clearingItAfterwardsSticks() {
        val files = MemoryFileStore()
        val repo = SettingsRepository(files, built)
        repo.update { it.copy(discMenuSource = "") }
        assertEquals("", SettingsRepository(files, built).current.discMenuSource, "reopened: still cleared")
    }

    @Test
    fun aBuildWithoutOneChangesNothing() {
        val files = MemoryFileStore()
        val repo = SettingsRepository(files, "")
        assertEquals("", repo.current.discMenuSource)
        repo.update { it }
        // A later build that has one still fills it in.
        assertEquals(built, SettingsRepository(files, built).current.discMenuSource)
    }
}
