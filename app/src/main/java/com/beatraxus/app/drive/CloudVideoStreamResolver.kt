package com.beatraxus.app.drive

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.beatraxus.app.BeatraxusApplication
import com.beatraxus.app.model.VideoSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Turns a `cloudvideo://` address (see [CloudVideoUri]) into something a plain HTTP client can
 * read: a direct https URL and the headers it needs.
 *
 *  - Google Drive: the `alt=media` endpoint with a bearer token.
 *  - Dropbox: a temporary link (the normal download endpoint is a POST, which players can't use).
 *  - OneDrive / Box: the pre-authorised address the `/content` endpoint redirects to. Players keep
 *    request headers when they follow a redirect, which these hosts reject, so the redirect is
 *    resolved here and the final address is used without the token.
 *  - Nextcloud: the WebDAV file URL with basic auth.
 *
 * Results are cached for a few minutes so seeking doesn't pay for the lookup on every reopen.
 */
class CloudVideoStreamResolver private constructor(context: Context) {

    class Resolved(val url: String, val headers: Map<String, String>)

    private val appContext = context.applicationContext
    private val app: BeatraxusApplication get() = appContext as BeatraxusApplication

    private val noRedirectClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private class CachedToken(val token: String, val expiresAt: Long)
    private class CachedLink(val resolved: Resolved, val expiresAt: Long)

    private val tokens = ConcurrentHashMap<String, CachedToken>()
    private val links = ConcurrentHashMap<String, CachedLink>()

    suspend fun resolve(parsed: CloudVideoUri.Parsed): Resolved = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        links[parsed.key]?.takeIf { it.expiresAt > now }?.let { return@withContext it.resolved }

        val (resolved, ttl) = try {
            when (parsed.source) {
                VideoSource.GDRIVE -> resolveDrive(parsed)
                VideoSource.DROPBOX -> resolveDropbox(parsed)
                VideoSource.ONEDRIVE -> resolveOneDrive(parsed)
                VideoSource.BOX -> resolveBox(parsed)
                VideoSource.NEXTCLOUD -> resolveNextcloud(parsed)
                else -> throw IOException("Unsupported cloud source ${parsed.source}")
            }
        } catch (e: IOException) {
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve ${parsed.source} video: ${e.message}")
            throw IOException("Could not open the ${parsed.source.name.lowercase()} video: ${e.message}", e)
        }
        if (ttl > 0) links[parsed.key] = CachedLink(resolved, now + ttl)
        resolved
    }

    // ── Providers ───────────────────────────────────────────────────────────────────────────

    private suspend fun resolveDrive(p: CloudVideoUri.Parsed): Pair<Resolved, Long> {
        val token = cachedToken("GDRIVE|${p.account}") { app.driveAccountRepository.getAccessToken(p.account) }
            ?: throw IOException("Google Drive sign-in expired. Reconnect the account in Settings.")
        val url = "https://www.googleapis.com/drive/v3/files/${Uri.encode(p.fileId)}?alt=media&supportsAllDrives=true"
        return Resolved(url, mapOf("Authorization" to "Bearer $token")) to LINK_TTL_SHORT_MS
    }

    private suspend fun resolveDropbox(p: CloudVideoUri.Parsed): Pair<Resolved, Long> {
        val client = app.dropboxAccountRepository.getClient(p.account)
            ?: throw IOException("Dropbox sign-in expired. Reconnect the account in Settings.")
        val link = client.files().getTemporaryLink(p.fileId).link
        return Resolved(link, emptyMap()) to LINK_TTL_LONG_MS
    }

    private suspend fun resolveOneDrive(p: CloudVideoUri.Parsed): Pair<Resolved, Long> {
        val token = cachedToken("ONEDRIVE|${p.account}") { app.onedriveAccountRepository.getAccessToken(p.account) }
            ?: throw IOException("OneDrive sign-in expired. Reconnect the account in Settings.")
        val auth = mapOf("Authorization" to "Bearer $token")
        val url = "https://graph.microsoft.com/v1.0/me/drive/items/${Uri.encode(p.fileId)}/content"
        val target = peekRedirect(url, auth)
        return if (target != null) Resolved(target, emptyMap()) to LINK_TTL_LONG_MS
        else Resolved(url, auth) to LINK_TTL_SHORT_MS
    }

    private suspend fun resolveBox(p: CloudVideoUri.Parsed): Pair<Resolved, Long> {
        val token = cachedToken("BOX|${p.account}") { app.boxAccountRepository.getAccessToken(p.account) }
            ?: throw IOException("Box sign-in expired. Reconnect the account in Settings.")
        val auth = mapOf("Authorization" to "Bearer $token")
        val url = "https://api.box.com/2.0/files/${Uri.encode(p.fileId)}/content"
        val target = peekRedirect(url, auth)
        // Box's download addresses expire quickly, so keep them for a short time only.
        return if (target != null) Resolved(target, emptyMap()) to LINK_TTL_SHORT_MS
        else Resolved(url, auth) to LINK_TTL_SHORT_MS
    }

    private suspend fun resolveNextcloud(p: CloudVideoUri.Parsed): Pair<Resolved, Long> {
        val accounts = app.nextcloudAccountRepository.accounts.first()
        val fileHost = Uri.parse(p.fileId).host
        val account = accounts.firstOrNull { it.username == p.account && hostOf(it.serverUrl).equals(fileHost, ignoreCase = true) }
            ?: accounts.firstOrNull { it.username == p.account }
            ?: throw IOException("Nextcloud account not found. Reconnect it in Settings.")
        val basic = Base64.encodeToString("${account.username}:${account.appPassword}".toByteArray(), Base64.NO_WRAP)
        return Resolved(p.fileId, mapOf("Authorization" to "Basic $basic")) to LINK_TTL_LONG_MS
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────

    private fun hostOf(serverUrl: String): String? =
        Uri.parse(if (serverUrl.contains("://")) serverUrl else "https://$serverUrl").host

    /** Tokens are re-fetched every few minutes: short enough to never outlive the real expiry. */
    private suspend fun cachedToken(key: String, fetch: suspend () -> String?): String? {
        val now = System.currentTimeMillis()
        tokens[key]?.takeIf { it.expiresAt > now }?.let { return it.token }
        val token = fetch() ?: return null
        tokens[key] = CachedToken(token, now + TOKEN_TTL_MS)
        return token
    }

    /**
     * Asks [url] where the file really lives. Returns the redirect target, or null when the server
     * answers the request itself (then the original URL + headers are used).
     */
    private fun peekRedirect(url: String, headers: Map<String, String>): String? {
        val request = Request.Builder().url(url).get().apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        return noRedirectClient.newCall(request).execute().use { response ->
            when {
                response.isRedirect -> {
                    val location = response.header("Location")
                    if (location == null) null
                    else response.request.url.resolve(location)?.toString() ?: location
                }
                response.code == 401 || response.code == 403 ->
                    throw IOException("Access denied (HTTP ${response.code})")
                !response.isSuccessful -> throw IOException("Server answered HTTP ${response.code}")
                else -> null
            }
        }
    }

    companion object {
        private const val TAG = "CloudVideoResolver"
        private const val TOKEN_TTL_MS = 4 * 60_000L
        private const val LINK_TTL_SHORT_MS = 3 * 60_000L
        private const val LINK_TTL_LONG_MS = 20 * 60_000L

        @Volatile private var instance: CloudVideoStreamResolver? = null

        fun get(context: Context): CloudVideoStreamResolver =
            instance ?: synchronized(this) {
                instance ?: CloudVideoStreamResolver(context.applicationContext).also { instance = it }
            }
    }
}
