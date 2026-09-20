package com.beatraxus.app.subtitles.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

open class SubtitleCredentialsStore(context: Context) {

    private val encryptedPrefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    companion object {
        private const val PREFS_NAME = "subtitle_credentials"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_EXPIRY_MS = "token_expiry_ms"
    }

    open fun saveCredentials(username: String, password: String) {
        encryptedPrefs.edit()
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD, password)
            .apply()
    }

    open fun saveToken(token: String, expiryMs: Long) {
        encryptedPrefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_EXPIRY_MS, expiryMs)
            .apply()
    }

    open fun getUsername(): String? = encryptedPrefs.getString(KEY_USERNAME, null)

    open fun getPassword(): String? = encryptedPrefs.getString(KEY_PASSWORD, null)

    open fun getToken(): String? = encryptedPrefs.getString(KEY_TOKEN, null)

    open fun getTokenExpiryMs(): Long = encryptedPrefs.getLong(KEY_EXPIRY_MS, 0L)

    open fun isTokenValid(): Boolean {
        val token = getToken()
        val expiry = getTokenExpiryMs()
        return !token.isNullOrBlank() && System.currentTimeMillis() < (expiry - 5 * 60 * 1000L)
    }

    open fun isSignedIn(): Boolean {
        val username = getUsername()
        val password = getPassword()
        return !username.isNullOrBlank() && !password.isNullOrBlank()
    }

    open fun clearToken() {
        encryptedPrefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EXPIRY_MS)
            .apply()
    }

    open fun clearAll() {
        encryptedPrefs.edit().clear().apply()
    }
}
