package com.beatraxus.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class BoxAccount(
    val email: String,
    val accountName: String,
    val photoUrl: String?,
    val userId: String,
    val enabled: Boolean = true
)

private val Context.boxDataStore: DataStore<Preferences> by preferencesDataStore(name = "box_accounts")

class BoxAccountRepository(private val context: Context) {
    companion object {
        private val BOX_ACCOUNTS = stringSetPreferencesKey("box_accounts")
    }

    val accounts: Flow<List<BoxAccount>> = context.boxDataStore.data.map { prefs ->
        (prefs[BOX_ACCOUNTS] ?: emptySet()).mapNotNull { json ->
            try {
                val obj = JSONObject(json)
                BoxAccount(
                    obj.getString("email"),
                    obj.getString("name"),
                    obj.optString("photo").takeIf { it.isNotEmpty() },
                    obj.getString("user_id"),
                    obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                android.util.Log.e("BoxAccountRepo", "Error parsing box account JSON", e)
                null
            }
        }
    }

    suspend fun addAccount(account: BoxAccount) {
        context.boxDataStore.edit { prefs ->
            val current = prefs[BOX_ACCOUNTS] ?: emptySet()
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
                put("user_id", account.userId)
                put("enabled", account.enabled)
            }.toString()
            prefs[BOX_ACCOUNTS] = (filtered + json).toSet()
        }
    }

    suspend fun updateAccountEnabled(email: String, enabled: Boolean) {
        context.boxDataStore.edit { prefs ->
            val current = prefs[BOX_ACCOUNTS] ?: emptySet()
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
            prefs[BOX_ACCOUNTS] = updated
        }
    }

    suspend fun getAccessToken(email: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val session = com.box.androidsdk.content.models.BoxSession(context)
            // session.setUserId(email) // If we store userId
            val auth = session.authenticate().get()
            if (auth.isSuccess) {
                return@withContext session.getAuthInfo()?.accessToken()
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("BoxAccountRepo", "Failed to get access token", e)
            null
        }
    }

    suspend fun removeAccount(email: String) {
        context.boxDataStore.edit { prefs ->
            val current = prefs[BOX_ACCOUNTS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    obj.getString("email") != email
                } catch (e: Exception) {
                    false
                }
            }.toSet()
            prefs[BOX_ACCOUNTS] = filtered
        }
    }
}
