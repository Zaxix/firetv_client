package com.auroratv

import com.auroratv.discmenu.isGitHubAddress
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The built-in disc menu token goes to GitHub addresses only, never to any other site. */
class BuiltInTokenTest {
    @Test
    fun githubAddressesGetIt() {
        assertTrue(isGitHubAddress("https://raw.githubusercontent.com/someone/menus/main"))
        assertTrue(isGitHubAddress("https://api.github.com/repos/someone/menus/contents"))
        assertTrue(isGitHubAddress("https://github.com/someone/menus/raw/main"))
        assertTrue(isGitHubAddress(" HTTPS://RAW.GITHUBUSERCONTENT.COM/someone/menus/main "))
    }

    @Test
    fun nothingElseDoes() {
        assertFalse(isGitHubAddress("https://example.com/discmenus"))
        assertFalse(isGitHubAddress("https://raw.githubusercontent.com.example.com/x"), "a look-alike host")
        assertFalse(isGitHubAddress("https://github.com@evil.example/x"), "a host hidden behind user info")
        assertFalse(isGitHubAddress("https://evil.example/?u=https://github.com/x"))
        assertFalse(isGitHubAddress("http://raw.githubusercontent.com/someone/menus/main"), "never over plain http")
        assertFalse(isGitHubAddress("http://192.168.1.10:8096/web/discmenus"))
        assertFalse(isGitHubAddress(""))
    }
}
