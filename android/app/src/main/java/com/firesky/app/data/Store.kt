package com.firesky.app.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class Store(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("firesky_native", Context.MODE_PRIVATE)
    private val cacheDir = File(context.cacheDir, "firesky-json").apply { mkdirs() }

    var accountToken: String
        get() = prefs.getString(KEY_ACCOUNT_TOKEN, "") ?: ""
        set(value) {
            prefs.edit().apply {
                if (value.isBlank()) remove(KEY_ACCOUNT_TOKEN) else putString(KEY_ACCOUNT_TOKEN, value)
            }.apply()
        }

    val installationId: String
        get() {
            val existing = prefs.getString(KEY_INSTALLATION, null)
            if (existing != null) return existing
            val created = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALLATION, created).apply()
            return created
        }

    var notificationsEnabled: Boolean
        get() = prefs.getBoolean(KEY_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply()

    var alertLeadMinutes: Int
        get() = prefs.getInt(KEY_LEAD, 60)
        set(value) = prefs.edit().putInt(KEY_LEAD, value).apply()

    var sunriseAlerts: Boolean
        get() = prefs.getBoolean(KEY_SUNRISE_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SUNRISE_ALERTS, value).apply()

    var sunsetAlerts: Boolean
        get() = prefs.getBoolean(KEY_SUNSET_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_SUNSET_ALERTS, value).apply()

    var alertThreshold: Int
        get() = prefs.getInt(KEY_THRESHOLD, 70)
        set(value) = prefs.edit().putInt(KEY_THRESHOLD, value).apply()

    var favorites: List<Place>
        get() = try {
            val array = JSONArray(prefs.getString(KEY_FAVORITES, "[]") ?: "[]")
            List(array.length()) { index -> Place.fromJson(array.getJSONObject(index)) }
        } catch (_: Exception) {
            emptyList()
        }
        set(value) {
            val array = JSONArray()
            value.take(8).forEach { array.put(it.toJson()) }
            prefs.edit().putString(KEY_FAVORITES, array.toString()).apply()
        }

    var feedback: JSONObject
        get() = try {
            JSONObject(prefs.getString(KEY_FEEDBACK, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        set(value) = prefs.edit().putString(KEY_FEEDBACK, value.toString()).apply()

    var snapshots: JSONObject
        get() = try {
            JSONObject(prefs.getString(KEY_SNAPSHOTS, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }
        set(value) = prefs.edit().putString(KEY_SNAPSHOTS, value.toString()).apply()

    fun cacheWrite(key: String, raw: String) {
        try {
            val file = File(cacheDir, digest(key) + ".json")
            file.writeText("${System.currentTimeMillis()}\n$raw")
        } catch (_: Exception) {
            // Cache is best-effort.
        }
    }

    fun cacheRead(key: String, maxAgeMs: Long): String? {
        return try {
            val file = File(cacheDir, digest(key) + ".json")
            if (!file.exists()) return null
            val text = file.readText()
            val separator = text.indexOf('\n')
            if (separator <= 0) return null
            val timestamp = text.substring(0, separator).toLongOrNull() ?: return null
            if (System.currentTimeMillis() - timestamp > maxAgeMs) return null
            text.substring(separator + 1)
        } catch (_: Exception) {
            null
        }
    }

    private fun digest(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_ACCOUNT_TOKEN = "account_token"
        private const val KEY_INSTALLATION = "installation_id"
        private const val KEY_NOTIFICATIONS = "notifications_enabled"
        private const val KEY_LEAD = "alert_lead_minutes"
        private const val KEY_SUNRISE_ALERTS = "sunrise_alerts"
        private const val KEY_SUNSET_ALERTS = "sunset_alerts"
        private const val KEY_THRESHOLD = "alert_threshold"
        private const val KEY_FAVORITES = "favorites"
        private const val KEY_FEEDBACK = "feedback"
        private const val KEY_SNAPSHOTS = "snapshots"
    }
}
