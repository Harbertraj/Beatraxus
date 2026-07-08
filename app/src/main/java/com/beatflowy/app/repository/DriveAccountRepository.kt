package com.beatflowy.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class DriveAccount(
    val email: String,
    val accountName: String,
    val photoUrl: String?,
    val enabled: Boolean = true
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "drive_accounts")

class DriveAccountRepository(private val context: Context) {
    suspend fun exportPreferences(): Map<String, Any> {
        return context.dataStore.data.first().asMap().mapKeys { it.key.name }.filterValues { it != null } as Map<String, Any>
    }

    suspend fun importPreferences(map: Map<String, Any>) {
        context.dataStore.edit { preferences ->
            map.forEach { (keyName, value) ->
                when (value) {
                    is String -> preferences[stringPreferencesKey(keyName)] = value
                    is Boolean -> preferences[booleanPreferencesKey(keyName)] = value
                    is Int -> preferences[intPreferencesKey(keyName)] = value
                    is Long -> preferences[longPreferencesKey(keyName)] = value
                    is Float -> preferences[floatPreferencesKey(keyName)] = value
                    is Double -> preferences[floatPreferencesKey(keyName)] = value.toFloat()
                    is List<*> -> {
                        if (value.all { it is String }) {
                            @Suppress("UNCHECKED_CAST")
                            preferences[stringSetPreferencesKey(keyName)] = (value as List<String>).toSet()
                        }
                    }
                }
            }
        }
    }

    private data class CachedToken(val token: String, val expiry: Long)

    companion object {
        private val DRIVE_ACCOUNTS = stringSetPreferencesKey("drive_accounts")
        private val tokenCache = java.util.concurrent.ConcurrentHashMap<String, CachedToken>()
    }

    val accounts: Flow<List<DriveAccount>> = context.dataStore.data.map { prefs ->
        (prefs[DRIVE_ACCOUNTS] ?: emptySet()).mapNotNull { json ->
            try {
                val obj = JSONObject(json)
                DriveAccount(
                    obj.getString("email"),
                    obj.getString("name"),
                    obj.optString("photo").takeIf { it.isNotEmpty() },
                    obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                android.util.Log.e("DriveAccountRepo", "Error parsing drive account JSON", e)
                null
            }
        }
    }

    suspend fun getAccessToken(email: String): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cached = tokenCache[email]
        if (cached != null && cached.expiry > now + 60_000) {
            return@withContext cached.token
        }

        try {
            val credential = getCredential(email)
            val token = credential.getToken()
            if (token != null) {
                tokenCache[email] = CachedToken(token, now + 3500_000) // ~58 mins
            }
            token
        } catch (e: Exception) {
            android.util.Log.e("DriveAccountRepo", "Error getting access token for $email", e)
            null
        }
    }

    suspend fun addAccount(account: DriveAccount) {
        android.util.Log.d("DriveAccountRepo", "Adding account: ${account.email}")
        context.dataStore.edit { prefs ->
            val current = prefs[DRIVE_ACCOUNTS] ?: emptySet()
            // Remove any existing entry with the same email
            val filtered = current.filter { json ->
                try {
                    JSONObject(json).getString("email") != account.email
                } catch (e: Exception) {
                    false // Remove malformed
                }
            }
            val json = JSONObject().apply {
                put("email", account.email)
                put("name", account.accountName)
                put("photo", account.photoUrl ?: "")
                put("enabled", account.enabled)
            }.toString()
            prefs[DRIVE_ACCOUNTS] = (filtered + json).toSet()
            android.util.Log.d("DriveAccountRepo", "Account added. Total accounts: ${(filtered + json).size}")
        }
    }

    suspend fun updateAccountEnabled(email: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[DRIVE_ACCOUNTS] ?: emptySet()
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
            prefs[DRIVE_ACCOUNTS] = updated
        }
    }

    suspend fun removeAccount(email: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[DRIVE_ACCOUNTS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    obj.getString("email") != email
                } catch (e: Exception) {
                    false // Remove malformed
                }
            }.toSet()
            prefs[DRIVE_ACCOUNTS] = filtered
        }
    }

    fun getCredential(email: String): GoogleAccountCredential {
        return GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_READONLY, DriveScopes.DRIVE_METADATA_READONLY)
        ).also { it.selectedAccountName = email }
    }
}
