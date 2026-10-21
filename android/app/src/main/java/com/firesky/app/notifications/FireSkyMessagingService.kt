package com.firesky.app.notifications

import android.content.Context
import com.firesky.app.data.ApiClient
import com.firesky.app.data.Place
import com.firesky.app.data.Store
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/**
 * Handles incoming FCM push messages (server-triggered sky alerts, forecast
 * improvement nudges, and background refresh triggers) and keeps the device's
 * push token registered with `/api/devices` via [ApiClient.registerDevice].
 */
class FireSkyMessagingService : FirebaseMessagingService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val store = Store(applicationContext)
        store.pushToken = token
        val accountToken = store.accountToken
        if (accountToken.isNotBlank()) {
            serviceScope.launch {
                runCatching {
                    ApiClient(store).registerDevice(accountToken, token, "android")
                }
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        val data = message.data
        val store = Store(applicationContext)
        if (!store.notificationsEnabled) return

        val type = data["type"] ?: "forecast_alert"
        if (type == "refresh") {
            ForecastRefreshWorker.runOnceNow(applicationContext)
            return
        }

        val mode = data["mode"] ?: "sunset"
        val probability = data["probability"]?.toIntOrNull() ?: return
        if (probability < store.alertThreshold) return

        val placeName = data["placeName"] ?: store.lastPlace?.name ?: "FireSky"
        val lat = data["latitude"]?.toDoubleOrNull() ?: store.lastPlace?.latitude ?: 0.0
        val lon = data["longitude"]?.toDoubleOrNull() ?: store.lastPlace?.longitude ?: 0.0
        val windowLabel = data["windowLabel"] ?: "--"
        val eventAt = data["eventAt"]?.toLongOrNull() ?: System.currentTimeMillis()
        val bearing = data["bearing"]?.toDoubleOrNull() ?: Double.NaN
        val reason = data["reason"] ?: ""

        val plan = SkyAlerts.AlertPlan(
            id = (placeName.hashCode() and 0x7FFF) + if (mode == "sunrise") 1000 else 2000,
            mode = mode,
            triggerAt = System.currentTimeMillis(),
            eventAt = eventAt,
            probability = probability,
            place = Place(name = placeName, latitude = lat, longitude = lon),
            windowLabel = windowLabel,
            bearing = bearing,
            reason = reason,
        )
        SkyAlerts.notify(applicationContext, plan)
    }
}

object PushTokenManager {
    /**
     * Obtains an FCM registration token when Firebase is configured on the
     * device, falling back to a deterministic installation push token so
     * [ApiClient.registerDevice] can always register the device cleanly.
     */
    suspend fun ensurePushToken(context: Context, store: Store): String {
        val fcmToken = runCatching { fetchFcmToken() }.getOrNull()
        if (!fcmToken.isNullOrBlank()) {
            store.pushToken = fcmToken
            return fcmToken
        }
        val existing = store.pushToken
        if (existing.length >= 32) return existing
        val fallback = "fcm-android-${store.installationId}-${context.packageName}"
        store.pushToken = fallback
        return fallback
    }

    suspend fun syncRegistration(context: Context, store: Store, api: ApiClient) {
        val accountToken = store.accountToken
        if (accountToken.isBlank()) return
        val pushToken = ensurePushToken(context, store)
        runCatching {
            api.registerDevice(accountToken, pushToken, "android")
        }
    }

    private suspend fun fetchFcmToken(): String? = suspendCoroutine { cont ->
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    cont.resume(task.result)
                } else {
                    cont.resume(null)
                }
            }
        } catch (_: Throwable) {
            cont.resume(null)
        }
    }
}
