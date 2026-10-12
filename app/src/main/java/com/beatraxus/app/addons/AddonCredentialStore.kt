package com.beatraxus.app.addons

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

/**
 * Keeps add-on sign-in sessions (server URL + access token) encrypted on the device so the user
 * stays signed in after the app restarts. Everything is best-effort: if the keystore is
 * unavailable the add-on simply asks for sign-in again.
 */
object AddonCredentialStore {

    private const val FILE = "beatraxus_addon_sessions"

    private fun prefs(context: Context): SharedPreferences? = try {
        val appContext = context.applicationContext
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        null
    }

    fun save(context: Context, addonId: String, values: Map<String, String>) {
        val json = JSONObject()
        values.forEach { (k, v) -> json.put(k, v) }
        prefs(context)?.edit()?.putString(addonId, json.toString())?.apply()
    }

    fun load(context: Context, addonId: String): Map<String, String>? {
        val raw = prefs(context)?.getString(addonId, null) ?: return null
        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.optString(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun clear(context: Context, addonId: String) {
        prefs(context)?.edit()?.remove(addonId)?.apply()
    }
}
