package com.beatflowy.app.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.services.drive.DriveScopes
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class DriveAccount(
    val email: String,
    val accountName: String,
    val photoUrl: String?,
    val enabled: Boolean = true
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "drive_accounts")

class DriveAccountRepository(private val context: Context) {

    companion object {
        private val DRIVE_ACCOUNTS = stringSetPreferencesKey("drive_accounts")
    }

    val accounts: Flow<List<DriveAccount>> = context.dataStore.data.map { prefs ->
        (prefs[DRIVE_ACCOUNTS] ?: emptySet()).map { json ->
            val obj = JSONObject(json)
            DriveAccount(
                obj.getString("email"),
                obj.getString("name"),
                obj.optString("photo", ""),
                obj.optBoolean("enabled", true)
            )
        }
    }

    suspend fun addAccount(account: DriveAccount) {
        context.dataStore.edit { prefs ->
            val current = prefs[DRIVE_ACCOUNTS] ?: emptySet()
            // Check if already exists by email to avoid duplicates
            val filtered = current.filter { json ->
                JSONObject(json).getString("email") != account.email
            }
            val json = JSONObject().apply {
                put("email", account.email)
                put("name", account.accountName)
                put("photo", account.photoUrl ?: "")
                put("enabled", account.enabled)
            }.toString()
            prefs[DRIVE_ACCOUNTS] = (filtered + json).toSet()
        }
    }

    suspend fun updateAccountEnabled(email: String, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[DRIVE_ACCOUNTS] ?: emptySet()
            val updated = current.map { json ->
                val obj = JSONObject(json)
                if (obj.getString("email") == email) {
                    obj.put("enabled", enabled)
                    obj.toString()
                } else {
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
                val obj = JSONObject(json)
                obj.getString("email") != email
            }.toSet()
            prefs[DRIVE_ACCOUNTS] = filtered
        }
    }

    fun getCredential(email: String): GoogleAccountCredential {
        return GoogleAccountCredential.usingOAuth2(context, listOf(DriveScopes.DRIVE_READONLY))
            .also { it.selectedAccountName = email }
    }
}
