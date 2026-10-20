package com.firesky.app.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.firesky.app.MainActivity
import com.firesky.app.R
import com.firesky.app.data.AccountSettings
import com.firesky.app.data.Forecast
import org.json.JSONArray
import org.json.JSONObject

object SkyAlerts {
    const val CHANNEL_ID = "firesky_sky_alerts"
    private const val PREFS = "firesky_alerts"
    private const val PLAN_KEY = "plan"
    private const val SUNRISE_ID = 7101
    private const val SUNSET_ID = 7102

    data class AlertPlan(
        val id: Int,
        val mode: String,
        val triggerAt: Long,
        val probability: Int,
        val placeName: String,
    )

    fun schedule(context: Context, forecast: Forecast, placeName: String, settings: AccountSettings) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancel(context, alarmManager)
        if (!settings.notificationsEnabled) {
            savePlan(context, emptyList())
            return
        }

        val plans = listOf(
            Triple("sunrise", settings.sunriseAlerts, SUNRISE_ID),
            Triple("sunset", settings.sunsetAlerts, SUNSET_ID),
        ).mapNotNull { (mode, enabled, id) ->
            if (!enabled) return@mapNotNull null
            val event = forecast[mode]
            val probability = Math.round(forecast.scores[mode].probability).toInt()
            if (probability < settings.alertThreshold) return@mapNotNull null
            val triggerAt = event.toEpochMilli() - settings.alertLeadMinutes * 60_000L
            if (triggerAt <= System.currentTimeMillis() + 15_000L) return@mapNotNull null
            AlertPlan(id, mode, triggerAt, probability, placeName)
        }
        savePlan(context, plans)
        plans.forEach { plan -> schedulePlan(context, alarmManager, plan) }
    }

    fun rescheduleFromStore(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancel(context, alarmManager)
        loadPlan(context)
            .filter { it.triggerAt > System.currentTimeMillis() + 15_000L }
            .forEach { schedulePlan(context, alarmManager, it) }
    }

    private fun schedulePlan(context: Context, alarmManager: AlarmManager, plan: AlertPlan) {
        val intent = Intent(context, SkyAlertReceiver::class.java).apply {
            putExtra("id", plan.id)
            putExtra("mode", plan.mode)
            putExtra("probability", plan.probability)
            putExtra("placeName", plan.placeName)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            plan.id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pending)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pending)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, plan.triggerAt, pending)
        }
    }

    private fun cancel(context: Context, alarmManager: AlarmManager) {
        listOf(SUNRISE_ID, SUNSET_ID).forEach { id ->
            val intent = Intent(context, SkyAlertReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            alarmManager.cancel(pending)
            pending.cancel()
        }
        NotificationManagerCompat.from(context).cancelAll()
    }

    private fun savePlan(context: Context, plans: List<AlertPlan>) {
        val array = JSONArray()
        plans.forEach { plan ->
            array.put(
                JSONObject()
                    .put("id", plan.id)
                    .put("mode", plan.mode)
                    .put("triggerAt", plan.triggerAt)
                    .put("probability", plan.probability)
                    .put("placeName", plan.placeName),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PLAN_KEY, array.toString())
            .apply()
    }

    private fun loadPlan(context: Context): List<AlertPlan> {
        return try {
            val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PLAN_KEY, "[]") ?: "[]"
            val array = JSONArray(raw)
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                AlertPlan(
                    id = item.optInt("id"),
                    mode = item.optString("mode"),
                    triggerAt = item.optLong("triggerAt"),
                    probability = item.optInt("probability"),
                    placeName = item.optString("placeName"),
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun notify(context: Context, mode: String, probability: Int, placeName: String) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val label = if (mode == "sunrise") "Sunrise" else "Sunset"
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("forecastMode", mode)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            if (mode == "sunrise") 8101 else 8102,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("FireSky $label Alert")
            .setContentText("$placeName: $mode chance $probability% (check the horizon).")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$placeName: $mode chance $probability%. Conditions may line up for a colorful sky."),
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()
        NotificationManagerCompat.from(context).notify(if (mode == "sunrise") SUNRISE_ID else SUNSET_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "FireSky sky alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Sunrise and sunset color alerts"
            },
        )
    }
}

class SkyAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mode = intent.getStringExtra("mode") ?: return
        val probability = intent.getIntExtra("probability", 0)
        val placeName = intent.getStringExtra("placeName") ?: "FireSky"
        SkyAlerts.notify(context, mode, probability, placeName)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            SkyAlerts.rescheduleFromStore(context)
        }
    }
}
