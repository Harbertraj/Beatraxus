package com.beatraxus.app.features

import java.security.MessageDigest
import java.security.SecureRandom

/** The ways a song can reach a phone that does not have it, in the order they are tried. */
enum class PtRoute(val label: String) {
    /** Bluetooth / Wi-Fi Direct through Google Nearby Connections. */
    NEARBY("Nearby"),
    /** The controller's own local-only hotspot, joined by the phone (see PtLocalWifi.kt). */
    OWN_WIFI("Own Wi-Fi"),
    /** WebRTC over the internet, signaled through the room. */
    INTERNET("Internet")
}

/**
 * Pure rules for the song routes and the own-Wi-Fi link, so they can be tested on the JVM:
 * route order and fallback, the token check, which address is the controller, the request parser of
 * the controller's small HTTP server, and when the hotspot is switched off again.
 */
internal object PtRoutePlan {
    /** How long a phone waits for the controller to publish its Wi-Fi credentials. */
    const val CREDENTIALS_TIMEOUT_MS = 20_000L
    /** How long a phone waits for the system to join the controller's Wi-Fi (includes the system dialog). */
    const val JOIN_TIMEOUT_MS = 30_000L
    /** The controller switches its hotspot off after this long without any use. */
    const val HOTSPOT_IDLE_MS = 120_000L
    /** A phone leaves the controller's Wi-Fi this long after its last download (the queue is loaded in between). */
    const val CLIENT_IDLE_MS = 15_000L

    private const val TOKEN_BYTES = 16

    /** The routes to try, in order: Nearby, then own Wi-Fi, then the internet. Routes that are not available are left out. */
    fun order(nearby: Boolean, ownWifi: Boolean, internet: Boolean): List<PtRoute> = buildList {
        if (nearby) add(PtRoute.NEARBY)
        if (ownWifi) add(PtRoute.OWN_WIFI)
        if (internet) add(PtRoute.INTERNET)
    }

    /** The route to try after [failed] gave up, or null when there is none left. */
    fun after(order: List<PtRoute>, failed: PtRoute): PtRoute? = order.firstOrNull { it.ordinal > failed.ordinal }

    // ---- token ---------------------------------------------------------------------------------

    /** A random token for one hotspot session (32 hex characters). */
    fun newToken(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(TOKEN_BYTES).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** True only for exactly the token the controller handed out (compared in constant time). */
    fun tokenOk(expected: String, given: String?): Boolean {
        if (expected.isEmpty() || given.isNullOrEmpty()) return false
        return MessageDigest.isEqual(expected.toByteArray(Charsets.UTF_8), given.toByteArray(Charsets.UTF_8))
    }

    // ---- the controller's HTTP server ------------------------------------------------------------

    enum class Kind { SONG, ART }

    class Target(val kind: Kind, val sig: String, val token: String?)

    private val SIG = Regex("[A-Za-z0-9]{1,64}")

    /**
     * Parses the request target of `GET /song/<sig>?t=<token>` or `GET /art/<sig>?t=<token>`.
     * Returns null for anything else (other paths, odd characters in the signature such as "..").
     */
    fun parseTarget(target: String): Target? {
        val path = target.substringBefore('?')
        val query = if (target.contains('?')) target.substringAfter('?') else ""
        val parts = path.split('/')
        if (parts.size != 3 || parts[0].isNotEmpty()) return null
        val kind = when (parts[1]) {
            "song" -> Kind.SONG
            "art" -> Kind.ART
            else -> return null
        }
        val sig = parts[2]
        if (!SIG.matches(sig)) return null
        val token = query.split('&').firstOrNull { it.startsWith("t=") }?.substring(2)
        return Target(kind, sig, token)
    }

    /** The hotspot is switched off when nothing used it for [idleMs] and no transfer is running. */
    fun idleExpired(lastActivityMs: Long, nowMs: Long, activeConnections: Int, idleMs: Long = HOTSPOT_IDLE_MS): Boolean =
        activeConnections <= 0 && nowMs - lastActivityMs >= idleMs

    // ---- the follower --------------------------------------------------------------------------

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

    /**
     * The controller's address on the joined network: the DHCP server (Android 11+) when known,
     * otherwise the first default-route gateway. Only usable IPv4 addresses count.
     */
    fun chooseHost(dhcpServer: String?, gateways: List<String>): String? =
        (listOf(dhcpServer) + gateways).firstOrNull { it != null && IPV4.matches(it) && it != "0.0.0.0" }

    // ---- reasons shown to the person ---------------------------------------------------------------

    /** Text for the `WifiManager.LocalOnlyHotspotCallback.onFailed` codes (1 to 4). */
    fun hotspotFailure(reason: Int): String = when (reason) {
        1 -> "Hotspot error: no Wi-Fi channel is free"
        2 -> "Hotspot error: Android could not start the hotspot"
        3 -> "Hotspot error: Wi-Fi is busy (turn off tethering / Wi-Fi Direct and try again)"
        4 -> "Hotspot error: hotspots are blocked on this phone"
        else -> "Hotspot error ($reason)"
    }
}
