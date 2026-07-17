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

data class SmbServer(
    val host: String,
    val port: Int = 445,
    val shareName: String,
    val username: String,
    val password: String,
    val domain: String? = null,
    val displayName: String,
    val enabled: Boolean = true
) {
    val id: String get() = "${host}:${port}/${shareName}|${username}"
}

private val Context.smbDataStore: DataStore<Preferences> by preferencesDataStore(name = "smb_connections")

class SmbConnectionRepository(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "smb_passwords",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private val SMB_CONNECTIONS = stringSetPreferencesKey("smb_connections")
    }

    val connections: Flow<List<SmbServer>> = context.smbDataStore.data.map { prefs ->
        (prefs[SMB_CONNECTIONS] ?: emptySet()).mapNotNull { json ->
            try {
                val obj = JSONObject(json)
                val host = obj.getString("host")
                val port = obj.getInt("port")
                val shareName = obj.getString("share_name")
                val username = obj.getString("username")
                val id = "${host}:${port}/${shareName}|${username}"
                val password = encryptedPrefs.getString(id, "") ?: ""
                
                SmbServer(
                    host = host,
                    port = port,
                    shareName = shareName,
                    username = username,
                    password = password,
                    domain = obj.optString("domain").takeIf { it.isNotEmpty() },
                    displayName = obj.getString("name"),
                    enabled = obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                android.util.Log.e("SmbConnectionRepo", "Error parsing SMB connection JSON", e)
                null
            }
        }
    }

    suspend fun addConnection(server: SmbServer) {
        encryptedPrefs.edit().putString(server.id, server.password).apply()
        
        context.smbDataStore.edit { prefs ->
            val current = prefs[SMB_CONNECTIONS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    val id = "${obj.getString("host")}:${obj.getInt("port")}/${obj.getString("share_name")}|${obj.getString("username")}"
                    id != server.id
                } catch (e: Exception) {
                    false
                }
            }
            val json = JSONObject().apply {
                put("host", server.host)
                put("port", server.port)
                put("share_name", server.shareName)
                put("username", server.username)
                put("domain", server.domain ?: "")
                put("name", server.displayName)
                put("enabled", server.enabled)
            }.toString()
            prefs[SMB_CONNECTIONS] = (filtered + json).toSet()
        }
    }

    suspend fun updateConnectionEnabled(id: String, enabled: Boolean) {
        context.smbDataStore.edit { prefs ->
            val current = prefs[SMB_CONNECTIONS] ?: emptySet()
            val updated = current.map { json ->
                try {
                    val obj = JSONObject(json)
                    val serverId = "${obj.getString("host")}:${obj.getInt("port")}/${obj.getString("share_name")}|${obj.getString("username")}"
                    if (serverId == id) {
                        obj.put("enabled", enabled)
                        obj.toString()
                    } else {
                        json
                    }
                } catch (e: Exception) {
                    json
                }
            }.toSet()
            prefs[SMB_CONNECTIONS] = updated
        }
    }

    suspend fun removeConnection(id: String) {
        encryptedPrefs.edit().remove(id).apply()
        context.smbDataStore.edit { prefs ->
            val current = prefs[SMB_CONNECTIONS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    val serverId = "${obj.getString("host")}:${obj.getInt("port")}/${obj.getString("share_name")}|${obj.getString("username")}"
                    serverId != id
                } catch (e: Exception) {
                    false
                }
            }.toSet()
            prefs[SMB_CONNECTIONS] = filtered
        }
    }
}
