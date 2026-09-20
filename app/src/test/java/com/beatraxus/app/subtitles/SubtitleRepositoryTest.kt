package com.beatraxus.app.subtitles

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.beatraxus.app.subtitles.api.DownloadRequest
import com.beatraxus.app.subtitles.api.DownloadResponse
import com.beatraxus.app.subtitles.api.LanguagesResponse
import com.beatraxus.app.subtitles.api.LoginRequest
import com.beatraxus.app.subtitles.api.LoginResponse
import com.beatraxus.app.subtitles.api.OpenSubtitlesApi
import com.beatraxus.app.subtitles.api.SubtitleApiConfig
import com.beatraxus.app.subtitles.api.SubtitlesResponse
import com.beatraxus.app.subtitles.data.SubtitleAuthManager
import com.beatraxus.app.subtitles.data.SubtitleCredentialsStore
import com.beatraxus.app.subtitles.data.SubtitleRepositoryImpl
import com.beatraxus.app.subtitles.domain.SubtitleError
import com.beatraxus.app.subtitles.domain.SubtitleException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import java.io.File

class FakeOpenSubtitlesApi : OpenSubtitlesApi {
    var lastAuthHeader: String? = null
    var downloadResponse: Response<DownloadResponse>? = null
    var loginResponse: Response<LoginResponse>? = null
    var loginCallCount = 0

    override suspend fun login(request: LoginRequest): Response<LoginResponse> {
        loginCallCount++
        return loginResponse ?: Response.success(LoginResponse(token = "fake_token_123", status = 200))
    }

    override suspend fun searchSubtitles(
        query: String?, imdbId: Long?, tmdbId: Long?, movieHash: String?,
        languages: String?, type: String?, orderBy: String?, orderDirection: String?, page: Int?
    ): Response<SubtitlesResponse> {
        return Response.success(SubtitlesResponse())
    }

    override suspend fun download(
        authorization: String?,
        request: DownloadRequest
    ): Response<DownloadResponse> {
        lastAuthHeader = authorization
        return downloadResponse ?: Response.success(
            DownloadResponse(
                link = "https://example.com/sub.srt",
                fileName = "sub.srt",
                remaining = 5,
                resetTime = "24 hours"
            )
        )
    }

    override suspend fun getLanguages(): Response<LanguagesResponse> {
        return Response.success(LanguagesResponse())
    }
}

class FakeSubtitleCredentialsStore(context: Context) : SubtitleCredentialsStore(context) {
    var signedIn: Boolean = false
    var tokenValid: Boolean = false
    var storedUsername: String? = "testuser"
    var storedPassword: String? = "testpass"
    var storedToken: String? = "valid_jwt_token"

    override fun isSignedIn(): Boolean = signedIn
    override fun isTokenValid(): Boolean = tokenValid
    override fun getUsername(): String? = storedUsername
    override fun getPassword(): String? = storedPassword
    override fun getToken(): String? = storedToken
    override fun saveToken(token: String, expiryMs: Long) {
        storedToken = token
        tokenValid = true
    }
    override fun clearToken() {
        storedToken = null
        tokenValid = false
    }
    override fun clearAll() {
        signedIn = false
        storedToken = null
        tokenValid = false
    }
}

class FakeSharedPreferences : SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String?, defValue: String?): String? = (data[key] as? String) ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = FakeEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
    override fun putString(key: String?, value: String?): SharedPreferences.Editor { data[key!!] = value; return this }
    override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { data[key!!] = values; return this }
    override fun putInt(key: String?, value: Int): SharedPreferences.Editor { data[key!!] = value; return this }
    override fun putLong(key: String?, value: Long): SharedPreferences.Editor { data[key!!] = value; return this }
    override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { data[key!!] = value; return this }
    override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { data[key!!] = value; return this }
    override fun remove(key: String?): SharedPreferences.Editor { data.remove(key); return this }
    override fun clear(): SharedPreferences.Editor { data.clear(); return this }
    override fun commit(): Boolean = true
    override fun apply() {}
}

open class FakeTestContext : ContextWrapper(null) {
    private val prefs = FakeSharedPreferences()
    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    override fun getFilesDir(): File = File(System.getProperty("java.io.tmpdir"), "beatraxus_test_files")
}

class SubtitleRepositoryTest {

    private lateinit var fakeApi: FakeOpenSubtitlesApi
    private lateinit var fakeCredentialsStore: FakeSubtitleCredentialsStore
    private lateinit var authManager: SubtitleAuthManager
    private lateinit var repository: SubtitleRepositoryImpl
    private lateinit var fakeContext: FakeTestContext

    @Before
    fun setUp() {
        fakeContext = FakeTestContext()
        fakeApi = FakeOpenSubtitlesApi()
        fakeCredentialsStore = FakeSubtitleCredentialsStore(fakeContext)
        authManager = SubtitleAuthManager(fakeApi, fakeCredentialsStore)

        val config = SubtitleApiConfig(apiKeyProvider = { "valid_api_key" })
        repository = SubtitleRepositoryImpl(fakeContext, fakeApi, authManager, config)
    }

    @Test
    fun testAnonymousDownloadSuccess() = runBlocking {
        fakeCredentialsStore.signedIn = false
        fakeApi.downloadResponse = Response.success(
            DownloadResponse(
                link = "https://example.com/sub.srt",
                fileName = "sub.srt",
                remaining = 5,
                resetTime = "24 hours"
            )
        )

        val result = repository.requestDownload(101L)

        assertTrue(result.isSuccess)
        assertNull(fakeApi.lastAuthHeader)
        val info = result.getOrNull()!!
        assertEquals("https://example.com/sub.srt", info.link)
        assertEquals(5, info.remaining)
        assertEquals(5, repository.getLastKnownRemainingDownloads())
    }

    @Test
    fun testAnonymousDownload401ReturnsNotSignedIn() = runBlocking {
        fakeCredentialsStore.signedIn = false
        val jsonError = "{\"message\":\"Unauthorized\"}"
        fakeApi.downloadResponse = Response.error(401, jsonError.toResponseBody("application/json".toMediaType()))

        val result = repository.requestDownload(101L)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as SubtitleException
        assertEquals(SubtitleError.NotSignedIn, exception.error)
    }

    @Test
    fun testAnonymousDownloadQuotaExceeded() = runBlocking {
        fakeCredentialsStore.signedIn = false
        val jsonError = "{\"message\":\"Quota Exceeded\", \"reset_time\": \"12 hours\"}"
        fakeApi.downloadResponse = Response.error(406, jsonError.toResponseBody("application/json".toMediaType()))

        val result = repository.requestDownload(101L)

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull() as SubtitleException
        assertTrue(exception.error is SubtitleError.ApiQuotaExceeded)
    }

    @Test
    fun testSignedInDownloadSuccess() = runBlocking {
        fakeCredentialsStore.signedIn = true
        fakeCredentialsStore.tokenValid = true
        fakeCredentialsStore.storedToken = "valid_jwt_token"

        fakeApi.downloadResponse = Response.success(
            DownloadResponse(
                link = "https://example.com/sub.srt",
                fileName = "sub.srt",
                remaining = 95
            )
        )

        val result = repository.requestDownload(101L)

        assertTrue(result.isSuccess)
        assertEquals("Bearer valid_jwt_token", fakeApi.lastAuthHeader)
        assertEquals(95, result.getOrNull()!!.remaining)
    }

    @Test
    fun testExpiredTokenTriggersReLogin() = runBlocking {
        fakeCredentialsStore.signedIn = true
        fakeCredentialsStore.tokenValid = false
        fakeCredentialsStore.storedUsername = "testuser"
        fakeCredentialsStore.storedPassword = "testpass"

        fakeApi.loginResponse = Response.success(
            LoginResponse(token = "new_relogin_jwt", status = 200)
        )
        fakeApi.downloadResponse = Response.success(
            DownloadResponse(
                link = "https://example.com/sub.srt",
                fileName = "sub.srt",
                remaining = 94
            )
        )

        val result = repository.requestDownload(101L)

        assertTrue(result.isSuccess)
        assertEquals(1, fakeApi.loginCallCount)
        assertEquals("Bearer new_relogin_jwt", fakeApi.lastAuthHeader)
    }
}
