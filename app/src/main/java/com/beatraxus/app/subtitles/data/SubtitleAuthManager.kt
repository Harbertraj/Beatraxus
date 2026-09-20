package com.beatraxus.app.subtitles.data

import com.beatraxus.app.subtitles.api.LoginRequest
import com.beatraxus.app.subtitles.api.OpenSubtitlesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SubtitleAuthManager(
    private val api: OpenSubtitlesApi,
    private val credentialsStore: SubtitleCredentialsStore
) {
    private val mutex = Mutex()

    fun isSignedIn(): Boolean = credentialsStore.isSignedIn()

    suspend fun login(username: String, password: String): Result<String> = mutex.withLock {
        try {
            val response = api.login(LoginRequest(username = username, password = password))
            val body = response.body()
            val token = body?.token

            if (response.isSuccessful && !token.isNullOrBlank()) {
                credentialsStore.saveCredentials(username, password)
                val expiryMs = System.currentTimeMillis() + (23 * 60 * 60 * 1000L)
                credentialsStore.saveToken(token, expiryMs)
                Result.success(token)
            } else {
                val code = response.code()
                val message = body?.message ?: "Login failed with status $code"
                Result.failure(Exception("Login error ($code): $message"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTokenIfSignedIn(): String? = mutex.withLock {
        if (!credentialsStore.isSignedIn()) {
            return null
        }

        if (credentialsStore.isTokenValid()) {
            return credentialsStore.getToken()
        }

        val username = credentialsStore.getUsername()
        val password = credentialsStore.getPassword()

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            return null
        }

        try {
            val response = api.login(LoginRequest(username = username, password = password))
            val body = response.body()
            val token = body?.token

            if (response.isSuccessful && !token.isNullOrBlank()) {
                val expiryMs = System.currentTimeMillis() + (23 * 60 * 60 * 1000L)
                credentialsStore.saveToken(token, expiryMs)
                token
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun relogin(): Result<String> {
        val username = credentialsStore.getUsername()
        val password = credentialsStore.getPassword()

        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            return Result.failure(Exception("No stored credentials"))
        }

        return login(username, password)
    }

    suspend fun invalidateToken() = mutex.withLock {
        credentialsStore.clearToken()
    }

    suspend fun logout() = mutex.withLock {
        credentialsStore.clearAll()
    }
}
