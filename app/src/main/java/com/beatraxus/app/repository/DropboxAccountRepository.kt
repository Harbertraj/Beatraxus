package com.beatraxus.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DropboxAccount(
    val email: String,
    val accountName: String,
    val photoUrl: String?,
    val enabled: Boolean = true
)

private val Context.dropboxDataStore: DataStore<Preferences> by preferencesDataStore(name = "dropbox_accounts")

class DropboxAccountRepository(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "dropbox_credentials",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private val DROPBOX_ACCOUNTS = stringSetPreferencesKey("dropbox_accounts")
        private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    }

    val accounts: Flow<List<DropboxAccount>> = context.dropboxDataStore.data.map { prefs ->
        (prefs[DROPBOX_ACCOUNTS] ?: emptySet()).mapNotNull { json ->
            try {
                val obj = JSONObject(json)
                DropboxAccount(
                    obj.getString("email"),
                    obj.getString("name"),
                    obj.optString("photo").takeIf { it.isNotEmpty() },
                    obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                android.util.Log.e("DropboxAccountRepo", "Error parsing dropbox account JSON", e)
                null
            }
        }
    }

    suspend fun getAccessToken(email: String): String? = withContext(Dispatchers.IO) {
        val cached = tokenCache[email]
        if (cached != null) return@withContext cached

        try {
            val credentialJson = encryptedPrefs.getString(email, null) ?: return@withContext null
            
            val credential = DbxCredential.Reader.readFully(credentialJson)
            val config = DbxRequestConfig.newBuilder("Beatraxus").build()
            val client = DbxClientV2(config, credential)
            
            // DbxCredential handles refresh automatically if it has a refresh token
            val token = credential.accessToken
            if (token != null) {
                tokenCache[email] = token
            }
            token
        } catch (e: Exception) {
            android.util.Log.e("DropboxAccountRepo", "Error getting access token for $email", e)
            null
        }
    }

    suspend fun addAccount(account: DropboxAccount, credentialJson: String) {
        encryptedPrefs.edit().putString(account.email, credentialJson).apply()
        context.dropboxDataStore.edit { prefs ->
            val current = prefs[DROPBOX_ACCOUNTS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    JSONObject(json).getString("email") != account.email
                } catch (e: Exception) {
                    false
                }
            }
            val json = JSONObject().apply {
                put("email", account.email)
                put("name", account.accountName)
                put("photo", account.photoUrl ?: "")
                put("enabled", account.enabled)
            }.toString()
            prefs[DROPBOX_ACCOUNTS] = (filtered + json).toSet()
        }
    }

    suspend fun updateAccountEnabled(email: String, enabled: Boolean) {
        context.dropboxDataStore.edit { prefs ->
            val current = prefs[DROPBOX_ACCOUNTS] ?: emptySet()
            val updated = current.map { json ->
                try {
                    val obj = JSONObject(json)
                    if (obj.getString("email") == email) {
                        obj.put("enabled", enabled)
                        obj.toString()
                    } else {
                        json
                    }
                } catch (e: Exception) {
                    json
                }
            }.toSet()
            prefs[DROPBOX_ACCOUNTS] = updated
        }
    }

    suspend fun removeAccount(email: String) {
        encryptedPrefs.edit().remove(email).apply()
        context.dropboxDataStore.edit { prefs ->
            val current = prefs[DROPBOX_ACCOUNTS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    obj.getString("email") != email
                } catch (e: Exception) {
                    false
                }
            }.toSet()
            prefs[DROPBOX_ACCOUNTS] = filtered
        }
        tokenCache.remove(email)
    }
}
