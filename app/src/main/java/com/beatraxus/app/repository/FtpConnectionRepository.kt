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

enum class FtpProtocol { FTP, SFTP, FTPS }

data class FtpServer(
    val host: String,
    val port: Int,
    val protocol: FtpProtocol,
    val username: String,
    val password: String? = null,
    val privateKeyPath: String? = null,
    val displayName: String,
    val enabled: Boolean = true
) {
    val id: String get() = "${protocol.name}://${host}:${port}|${username}"
}

private val Context.ftpDataStore: DataStore<Preferences> by preferencesDataStore(name = "ftp_connections")

class FtpConnectionRepository(private val context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "ftp_passwords",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private val FTP_CONNECTIONS = stringSetPreferencesKey("ftp_connections")
    }

    val connections: Flow<List<FtpServer>> = context.ftpDataStore.data.map { prefs ->
        (prefs[FTP_CONNECTIONS] ?: emptySet()).mapNotNull { json ->
            try {
                val obj = JSONObject(json)
                val protocol = FtpProtocol.valueOf(obj.getString("protocol"))
                val host = obj.getString("host")
                val port = obj.getInt("port")
                val username = obj.getString("username")
                val id = "${protocol.name}://${host}:${port}|${username}"
                val password = encryptedPrefs.getString(id, null)
                
                FtpServer(
                    host = host,
                    port = port,
                    protocol = protocol,
                    username = username,
                    password = password,
                    privateKeyPath = obj.optString("private_key_path").takeIf { it.isNotEmpty() },
                    displayName = obj.getString("name"),
                    enabled = obj.optBoolean("enabled", true)
                )
            } catch (e: Exception) {
                android.util.Log.e("FtpConnectionRepo", "Error parsing FTP connection JSON", e)
                null
            }
        }
    }

    suspend fun addConnection(server: FtpServer) {
        if (server.password != null) {
            encryptedPrefs.edit().putString(server.id, server.password).apply()
        } else {
            encryptedPrefs.edit().remove(server.id).apply()
        }
        
        context.ftpDataStore.edit { prefs ->
            val current = prefs[FTP_CONNECTIONS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    val id = "${obj.getString("protocol")}://${obj.getString("host")}:${obj.getInt("port")}|${obj.getString("username")}"
                    id != server.id
                } catch (e: Exception) {
                    false
                }
            }
            val json = JSONObject().apply {
                put("protocol", server.protocol.name)
                put("host", server.host)
                put("port", server.port)
                put("username", server.username)
                put("private_key_path", server.privateKeyPath ?: "")
                put("name", server.displayName)
                put("enabled", server.enabled)
            }.toString()
            prefs[FTP_CONNECTIONS] = (filtered + json).toSet()
        }
    }

    suspend fun updateConnectionEnabled(id: String, enabled: Boolean) {
        context.ftpDataStore.edit { prefs ->
            val current = prefs[FTP_CONNECTIONS] ?: emptySet()
            val updated = current.map { json ->
                try {
                    val obj = JSONObject(json)
                    val serverId = "${obj.getString("protocol")}://${obj.getString("host")}:${obj.getInt("port")}|${obj.getString("username")}"
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
            prefs[FTP_CONNECTIONS] = updated
        }
    }

    suspend fun removeConnection(id: String) {
        encryptedPrefs.edit().remove(id).apply()
        context.ftpDataStore.edit { prefs ->
            val current = prefs[FTP_CONNECTIONS] ?: emptySet()
            val filtered = current.filter { json ->
                try {
                    val obj = JSONObject(json)
                    val serverId = "${obj.getString("protocol")}://${obj.getString("host")}:${obj.getInt("port")}|${obj.getString("username")}"
                    serverId != id
                } catch (e: Exception) {
                    false
                }
            }.toSet()
            prefs[FTP_CONNECTIONS] = filtered
        }
    }
}
