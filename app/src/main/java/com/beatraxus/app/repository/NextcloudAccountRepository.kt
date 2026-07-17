package com.beatraxus.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class NextcloudAccount(
    val serverUrl: String,
    val username: String,
    val appPassword: String,
    val displayName: String,
    val enabled: Boolean = true
)

private val Context.nextcloudDataStore: DataStore<Preferences> by preferencesDataStore(name = "nextcloud_accounts")

class NextcloudAccountRepository(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "nextcloud_passwords",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private val NEXTCLOUD_ACCOUNTS = stringSetPreferencesKey("nextcloud_accounts")
    }

    val accounts: Flow<List<NextcloudAccount>> = context.nextcloudDataStore.data.map { prefs ->
        (prefs[NEXTCLOUD_ACCOUNTS] ?: emptySet()).mapNotNull { json ->
            try {
                val obj = JSONObject(json)
                val serverUrl = obj.getString("server_url")
                val username = obj.getString("username")
                val password = encryptedPrefs.getString(getPasswordKey(serverUrl, username), "") ?: ""
                NextcloudAccount(
                    serverUrl,
                    username,
                    password,
                    obj.getString("name"),
                    obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                android.util.Log.e("NextcloudAccountRepo", "Error parsing nextcloud account JSON", e)
                null
            }
        }
    }

    private fun getPasswordKey(serverUrl: String, username: String): String {
        return "${serverUrl}|${username}"
    }

    suspend fun addAccount(account: NextcloudAccount) {
        encryptedPrefs.edit().putString(getPasswordKey(account.serverUrl, account.username), account.appPassword).apply()
        
        context.nextcloudDataStore.edit { prefs ->
            val current = prefs[NEXTCLOUD_ACCOUNTS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    obj.getString("server_url") != account.serverUrl || obj.getString("username") != account.username
                } catch (e: Exception) {
                    false
                }
            }
            val json = JSONObject().apply {
                put("server_url", account.serverUrl)
                put("username", account.username)
                put("name", account.displayName)
                put("enabled", account.enabled)
            }.toString()
            prefs[NEXTCLOUD_ACCOUNTS] = (filtered + json).toSet()
        }
    }

    suspend fun updateAccountEnabled(serverUrl: String, username: String, enabled: Boolean) {
        context.nextcloudDataStore.edit { prefs ->
            val current = prefs[NEXTCLOUD_ACCOUNTS] ?: emptySet()
            val updated = current.map { json ->
                try {
                    val obj = JSONObject(json)
                    if (obj.getString("server_url") == serverUrl && obj.getString("username") == username) {
                        obj.put("enabled", enabled)
                        obj.toString()
                    } else {
                        json
                    }
                } catch (e: Exception) {
                    json
                }
            }.toSet()
            prefs[NEXTCLOUD_ACCOUNTS] = updated
        }
    }

    suspend fun removeAccount(serverUrl: String, username: String) {
        encryptedPrefs.edit().remove(getPasswordKey(serverUrl, username)).apply()
        context.nextcloudDataStore.edit { prefs ->
            val current = prefs[NEXTCLOUD_ACCOUNTS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    obj.getString("server_url") != serverUrl || obj.getString("username") != username
                } catch (e: Exception) {
                    false
                }
            }.toSet()
            prefs[NEXTCLOUD_ACCOUNTS] = filtered
        }
    }
}
