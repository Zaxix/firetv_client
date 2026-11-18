package com.auroratv

import com.auroratv.core.AppError
import com.auroratv.data.SessionManager
import com.auroratv.data.SessionState
import com.auroratv.ui.screens.connectionHelp
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Connecting through VPNs (Tailscale/WireGuard/commercial) and switching between home and VPN addresses. */
class VpnConnectivityTest {
    /** What a dropped or refused connection looks like to the app (named like the JVM's ConnectException). */
    private class FakeConnectException : Exception("connect failed")

    private val info = """{"ServerName":"Home","Version":"10.10.7","Id":"s1","ProductName":"Jellyfin Server"}"""
    private var homeNetworkUp = true

    private fun server() = FakeServer { req ->
        val host = req.url.host
        val path = req.url.encodedPath
        when {
            // A VPN that silently drops traffic for a wrong guess (https on 443): no answer at all.
            host == "media.example.com" && req.url.port == 443 -> { delay(60_000); json("{}") }
            host == "refuse.example.com" -> json("", HttpStatusCode.Forbidden)
            host == "home.lan" && !homeNetworkUp -> throw FakeConnectException()
            path == "/System/Info/Public" -> json(info)
            path == "/Users/AuthenticateByName" -> json("""{"User":{"Id":"u1","Name":"zak","ServerId":"s1"},"AccessToken":"TOKEN","ServerId":"s1"}""")
            path == "/Users/Me" -> json("""{"Id":"u1","Name":"zak"}""")
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun aSilentAddressNoLongerHoldsUpConnecting() = runTest {
        val sm = SessionManager(server().client, MemoryFileStore(), MemoryVault(), "TV")
        val (url, _) = sm.discover("media.example.com")
        assertEquals("http://media.example.com:8096", url)
        assertTrue(testScheduler.currentTime < 2_000, "answered in ${testScheduler.currentTime} ms, not after a timeout per guess")
    }

    @Test
    fun aServerRefusingVpnAddressesSaysSoInsteadOfNotJellyfin() = runTest {
        val sm = SessionManager(server().client, MemoryFileStore(), MemoryVault(), "TV")
        val e = assertFailsWith<AppError.Refused> { sm.discover("refuse.example.com") }
        assertTrue(e.userMessage.contains("Allow remote connections"))
    }

    @Test
    fun homeAndVpnAddressesOfOneServerBothWorkAndTheAppSwitchesToTheOneThatAnswers() = runTest {
        val srv = server()
        val files = MemoryFileStore(); val vault = MemoryVault()
        val sm = SessionManager(srv.client, files, vault, "TV")
        sm.discover("100.101.102.103").let { (u, i) -> sm.addServer(u, i) }
        // The same server added again by its home-network address: one server, both addresses.
        val (homeUrl, i2) = sm.discover("home.lan")
        val saved = sm.addServer(homeUrl, i2)
        assertEquals("http://home.lan:8096", saved.url)
        assertEquals(1, sm.servers.value.size)
        assertEquals(setOf("http://100.101.102.103:8096", "http://home.lan:8096"), saved.allUrls.toSet())
        sm.login(saved, "zak", "pw")

        // Away from home (or the TV's VPN blocks the home network): the saved address stops answering.
        homeNetworkUp = false
        val again = SessionManager(srv.client, files, vault, "TV")
        again.start(skipPicker = true)
        val ready = assertIs<SessionState.Ready>(again.state.value)
        assertEquals("http://100.101.102.103:8096", ready.session.server.url, "switched to the address that answers, still signed in")
    }

    @Test
    fun vpnAdviceWhenTheServerCantBeReached() {
        val home = connectionHelp(AppError.Unreachable(), "http://192.168.1.20:8096", vpnOn = true)
        assertTrue(home.contains("local network") && home.contains("split tunnelling"), home)
        val viaVpn = connectionHelp(AppError.Timeout(), "http://100.101.102.103:8096", vpnOn = true)
        assertTrue(viaVpn.contains("Tailscale"), viaVpn)
        assertEquals(AppError.Unreachable().userMessage, connectionHelp(AppError.Unreachable(), "http://192.168.1.20:8096", vpnOn = false))
    }
}
