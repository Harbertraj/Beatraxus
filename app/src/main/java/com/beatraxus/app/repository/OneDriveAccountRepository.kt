package com.beatraxus.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class OneDriveAccount(
    val email: String,
    val accountName: String,
    val photoUrl: String?,
    val tenantId: String?,
    val enabled: Boolean = true
)

private val Context.onedriveDataStore: DataStore<Preferences> by preferencesDataStore(name = "onedrive_accounts")

class OneDriveAccountRepository(private val context: Context) {
    companion object {
        private val ONEDRIVE_ACCOUNTS = stringSetPreferencesKey("onedrive_accounts")
    }

    val accounts: Flow<List<OneDriveAccount>> = context.onedriveDataStore.data.map { prefs ->
        (prefs[ONEDRIVE_ACCOUNTS] ?: emptySet()).mapNotNull { json ->
            try {
                val obj = JSONObject(json)
                OneDriveAccount(
                    obj.getString("email"),
                    obj.getString("name"),
                    obj.optString("photo").takeIf { it.isNotEmpty() },
                    obj.optString("tenant_id").takeIf { it.isNotEmpty() },
                    obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                android.util.Log.e("OneDriveAccountRepo", "Error parsing onedrive account JSON", e)
                null
            }
        }
    }

    suspend fun addAccount(account: OneDriveAccount) {
        context.onedriveDataStore.edit { prefs ->
            val current = prefs[ONEDRIVE_ACCOUNTS] ?: emptySet()
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
                put("tenant_id", account.tenantId ?: "")
                put("enabled", account.enabled)
            }.toString()
            prefs[ONEDRIVE_ACCOUNTS] = (filtered + json).toSet()
        }
    }

    suspend fun updateAccountEnabled(email: String, enabled: Boolean) {
        context.onedriveDataStore.edit { prefs ->
            val current = prefs[ONEDRIVE_ACCOUNTS] ?: emptySet()
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
            prefs[ONEDRIVE_ACCOUNTS] = updated
        }
    }

    suspend fun getAccessToken(email: String): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val pca = com.microsoft.identity.client.PublicClientApplication.createSingleAccountPublicClientApplication(
                context,
                com.beatraxus.app.R.raw.msal_config
            )
            val account = pca.getCurrentAccount().currentAccount ?: return@withContext null
            if (account.username.equals(email, ignoreCase = true)) {
                val result = pca.acquireTokenSilent(arrayOf("Files.Read", "User.Read"), account.tenantId)
                return@withContext result.accessToken
            }
            null
        } catch (e: Exception) {
            android.util.Log.e("OneDriveAccountRepo", "Failed to get access token", e)
            null
        }
    }

    suspend fun removeAccount(email: String) {
        context.onedriveDataStore.edit { prefs ->
            val current = prefs[ONEDRIVE_ACCOUNTS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    obj.getString("email") != email
                } catch (e: Exception) {
                    false
                }
            }.toSet()
            prefs[ONEDRIVE_ACCOUNTS] = filtered
        }
    }
}
