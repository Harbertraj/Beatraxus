package com.beatraxus.app.features

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PtRoutePlanTest {

    private val all = PtRoutePlan.order(nearby = true, ownWifi = true, internet = true)

    // ---- route order and fallback ----------------------------------------------------------------

    @Test
    fun routesAreTriedNearbyThenOwnWifiThenInternet() {
        assertEquals(listOf(PtRoute.NEARBY, PtRoute.OWN_WIFI, PtRoute.INTERNET), all)
    }

    @Test
    fun unavailableRoutesAreLeftOut() {
        assertEquals(listOf(PtRoute.OWN_WIFI, PtRoute.INTERNET), PtRoutePlan.order(false, true, true))
        assertEquals(listOf(PtRoute.NEARBY, PtRoute.INTERNET), PtRoutePlan.order(true, false, true))
        assertEquals(listOf(PtRoute.INTERNET), PtRoutePlan.order(false, false, true))
        assertEquals(listOf(PtRoute.NEARBY, PtRoute.OWN_WIFI), PtRoutePlan.order(true, true, false))
        assertTrue(PtRoutePlan.order(false, false, false).isEmpty())
    }

    @Test
    fun aFailedRouteFallsBackToTheNextOneInOrder() {
        assertEquals(PtRoute.OWN_WIFI, PtRoutePlan.after(all, PtRoute.NEARBY))
        assertEquals(PtRoute.INTERNET, PtRoutePlan.after(all, PtRoute.OWN_WIFI))
    }

    @Test
    fun nothingFollowsTheLastRoute() {
        assertNull(PtRoutePlan.after(all, PtRoute.INTERNET))
        assertNull(PtRoutePlan.after(listOf(PtRoute.NEARBY, PtRoute.OWN_WIFI), PtRoute.OWN_WIFI))
    }

    @Test
    fun fallbackSkipsRoutesThatWereNotAvailable() {
        // old phone (no own Wi-Fi): Nearby -> Internet
        assertEquals(PtRoute.INTERNET, PtRoutePlan.after(PtRoutePlan.order(true, false, true), PtRoute.NEARBY))
        // Nearby switched off: own Wi-Fi -> Internet
        assertEquals(PtRoute.INTERNET, PtRoutePlan.after(PtRoutePlan.order(false, true, true), PtRoute.OWN_WIFI))
    }

    @Test
    fun walkingTheWholeChainVisitsEachRouteOnce() {
        val seen = ArrayList<PtRoute>()
        var route: PtRoute? = all.first()
        while (route != null) { seen += route; route = PtRoutePlan.after(all, route) }
        assertEquals(all, seen)
    }

    @Test
    fun timeoutsMatchTheDesign() {
        assertEquals(20_000L, PtRoutePlan.CREDENTIALS_TIMEOUT_MS)
        assertEquals(30_000L, PtRoutePlan.JOIN_TIMEOUT_MS)
        assertEquals(120_000L, PtRoutePlan.HOTSPOT_IDLE_MS)
    }

    @Test
    fun routeLabelsAreWhatTheScreenShows() {
        assertEquals("Nearby", PtRoute.NEARBY.label)
        assertEquals("Own Wi-Fi", PtRoute.OWN_WIFI.label)
        assertEquals("Internet", PtRoute.INTERNET.label)
    }

    // ---- token ---------------------------------------------------------------------------------

    @Test
    fun tokensAreRandomHex() {
        val a = PtRoutePlan.newToken()
        val b = PtRoutePlan.newToken()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(a, b)
    }

    @Test
    fun onlyTheExactTokenIsAccepted() {
        val t = PtRoutePlan.newToken()
        assertTrue(PtRoutePlan.tokenOk(t, t))
        assertFalse(PtRoutePlan.tokenOk(t, null))
        assertFalse(PtRoutePlan.tokenOk(t, ""))
        assertFalse(PtRoutePlan.tokenOk(t, t.dropLast(1)))
        assertFalse(PtRoutePlan.tokenOk(t, t + "0"))
        assertFalse(PtRoutePlan.tokenOk(t, t.uppercase()))
        assertFalse(PtRoutePlan.tokenOk(t, "x".repeat(t.length)))
    }

    @Test
    fun anEmptyExpectedTokenAcceptsNothing() {
        assertFalse(PtRoutePlan.tokenOk("", ""))
        assertFalse(PtRoutePlan.tokenOk("", "abc"))
    }

    // ---- request parser ------------------------------------------------------------------------

    @Test
    fun parsesSongAndArtRequests() {
        val song = PtRoutePlan.parseTarget("/song/ab12cd34ef56ab78?t=tok123")
        assertNotNull(song)
        assertEquals(PtRoutePlan.Kind.SONG, song!!.kind)
        assertEquals("ab12cd34ef56ab78", song.sig)
        assertEquals("tok123", song.token)

        val art = PtRoutePlan.parseTarget("/art/abc?x=1&t=tok")
        assertEquals(PtRoutePlan.Kind.ART, art!!.kind)
        assertEquals("tok", art.token)
    }

    @Test
    fun aRequestWithoutTokenIsParsedButHasNoToken() {
        assertNull(PtRoutePlan.parseTarget("/song/abc")!!.token)
        assertNull(PtRoutePlan.parseTarget("/song/abc?x=1")!!.token)
    }

    @Test
    fun otherPathsAndOddSignaturesAreRejected() {
        assertNull(PtRoutePlan.parseTarget("/"))
        assertNull(PtRoutePlan.parseTarget("/other/abc?t=1"))
        assertNull(PtRoutePlan.parseTarget("/song/?t=1"))
        assertNull(PtRoutePlan.parseTarget("/song/../etc?t=1"))
        assertNull(PtRoutePlan.parseTarget("/song/a/b?t=1"))
        assertNull(PtRoutePlan.parseTarget("song/abc?t=1"))
        assertNull(PtRoutePlan.parseTarget("/song/a%2Fb?t=1"))
        assertNull(PtRoutePlan.parseTarget("/song/" + "a".repeat(65) + "?t=1"))
    }

    // ---- which address is the controller ---------------------------------------------------------

    @Test
    fun theDhcpServerIsPreferredWhenKnown() {
        assertEquals("192.168.43.1", PtRoutePlan.chooseHost("192.168.43.1", listOf("10.0.0.1")))
    }

    @Test
    fun withoutDhcpTheFirstGatewayIsUsed() {
        assertEquals("10.0.0.1", PtRoutePlan.chooseHost(null, listOf("10.0.0.1", "10.0.0.2")))
    }

    @Test
    fun unusableAddressesAreSkipped() {
        assertEquals("192.168.49.1", PtRoutePlan.chooseHost("0.0.0.0", listOf("fe80::1%wlan0", "", "0.0.0.0", "192.168.49.1")))
        assertEquals("172.20.10.1", PtRoutePlan.chooseHost("", listOf("fe80::1", "172.20.10.1")))
    }

    @Test
    fun noUsableAddressMeansNoHost() {
        assertNull(PtRoutePlan.chooseHost(null, emptyList()))
        assertNull(PtRoutePlan.chooseHost("0.0.0.0", listOf("fe80::1%wlan0", "not-an-ip")))
    }

    // ---- hotspot idle and reasons ---------------------------------------------------------------

    @Test
    fun hotspotStopsAfterTwoIdleMinutesButNeverDuringATransfer() {
        assertFalse(PtRoutePlan.idleExpired(lastActivityMs = 0, nowMs = 119_999, activeConnections = 0))
        assertTrue(PtRoutePlan.idleExpired(lastActivityMs = 0, nowMs = 120_000, activeConnections = 0))
        assertFalse(PtRoutePlan.idleExpired(lastActivityMs = 0, nowMs = 600_000, activeConnections = 1))
    }

    @Test
    fun hotspotFailuresHaveReadableReasons() {
        val reasons = (1..4).map { PtRoutePlan.hotspotFailure(it) }
        assertEquals(4, reasons.toSet().size)
        assertTrue(reasons.all { it.startsWith("Hotspot error") })
        assertEquals("Hotspot error (99)", PtRoutePlan.hotspotFailure(99))
    }
}
