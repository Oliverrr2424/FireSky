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
import com.firesky.app.data.ApiClient
import com.firesky.app.data.ClockStyle
import com.firesky.app.data.Forecast
import com.firesky.app.data.ForecastRepository
import com.firesky.app.data.Place
import com.firesky.app.data.Store
import com.firesky.app.data.compassLabel
import com.firesky.app.data.describeScore
import com.firesky.app.data.formatRange
import com.firesky.app.data.sunAzimuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

object SkyAlerts {
    const val CHANNEL_ID = "firesky_sky_alerts"
    const val EXTRA_MODE = "forecastMode"
    const val EXTRA_EVENT_AT = "eventAt"
    const val EXTRA_PLACE = "place"
    private const val PREFS = "firesky_alerts"
    private const val PLAN_KEY = "plan"
    private const val SUNRISE_ID = 7101
    private const val SUNSET_ID = 7102
    private const val MIN_LEAD_MS = 15_000L
    private const val EVENT_MATCH_MS = 3L * 60L * 60L * 1000L

    /** Only events in the next two forecast days are planned; the refresh worker keeps rolling them forward. */
    private const val PLAN_DAYS = 2

    data class AlertPlan(
        val id: Int,
        val mode: String,
        val triggerAt: Long,
        val eventAt: Long,
        val probability: Int,
        val place: Place,
        val windowLabel: String,
        val bearing: Double,
        val reason: String,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("id", id)
            .put("mode", mode)
            .put("triggerAt", triggerAt)
            .put("eventAt", eventAt)
            .put("probability", probability)
            .put("place", place.toJson())
            .put("windowLabel", windowLabel)
            .put("bearing", bearing)
            .put("reason", reason)

        companion object {
            fun fromJson(json: JSONObject): AlertPlan = AlertPlan(
                id = json.optInt("id"),
                mode = json.optString("mode"),
                triggerAt = json.optLong("triggerAt"),
                eventAt = json.optLong("eventAt"),
                probability = json.optInt("probability"),
                place = json.optJSONObject("place")?.let { Place.fromJson(it) }
                    ?: Place(name = json.optString("placeName", "FireSky"), latitude = 0.0, longitude = 0.0),
                windowLabel = json.optString("windowLabel"),
                bearing = json.optDouble("bearing", Double.NaN),
                reason = json.optString("reason"),
            )
        }
    }

    /**
     * Plans the next sunrise and sunset alert for [place]. Each mode gets the
     * first event whose lead time is still ahead, so after dark the plan moves
     * on to tomorrow morning instead of going quiet.
     */
    fun plan(days: List<Forecast>, place: Place, settings: AccountSettings, now: Long = System.currentTimeMillis()): List<AlertPlan> {
        if (!settings.notificationsEnabled) return emptyList()
        return listOf(
            Triple("sunrise", settings.sunriseAlerts, SUNRISE_ID),
            Triple("sunset", settings.sunsetAlerts, SUNSET_ID),
        ).mapNotNull { (mode, enabled, id) ->
            if (!enabled) return@mapNotNull null
            val day = days.take(PLAN_DAYS).firstOrNull { day ->
                day[mode].toEpochMilli() - settings.alertLeadMinutes * 60_000L > now + MIN_LEAD_MS
            } ?: return@mapNotNull null
            val event = day[mode]
            val score = day.scores[mode]
            val probability = Math.round(score.probability).toInt()
            if (probability < settings.alertThreshold) return@mapNotNull null
            val appearance = day.appearanceWindow[mode]
            AlertPlan(
                id = id,
                mode = mode,
                triggerAt = event.toEpochMilli() - settings.alertLeadMinutes * 60_000L,
                eventAt = event.toEpochMilli(),
                probability = probability,
                place = place,
                windowLabel = formatRange(appearance.start, appearance.end, day.timeZone),
                bearing = sunAzimuth(event, place.latitude, place.longitude),
                reason = score.boosts.firstOrNull() ?: "",
            )
        }
    }

    fun schedule(context: Context, days: List<Forecast>, place: Place, settings: AccountSettings) {
        ensureChannel(context)
        val plans = plan(days, place, settings)
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelAlarms(context, alarmManager)
        savePlan(context, plans)
        plans.forEach { schedulePlan(context, alarmManager, it) }
    }

    fun rescheduleFromStore(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        cancelAlarms(context, alarmManager)
        loadPlan(context)
            .filter { it.triggerAt > System.currentTimeMillis() + MIN_LEAD_MS }
            .forEach { schedulePlan(context, alarmManager, it) }
    }

    internal fun loadPlan(context: Context): List<AlertPlan> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PLAN_KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        List(array.length()) { index -> AlertPlan.fromJson(array.getJSONObject(index)) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun schedulePlan(context: Context, alarmManager: AlarmManager, plan: AlertPlan) {
        val pending = alarmIntent(context, plan.id)
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

    private fun alarmIntent(context: Context, id: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        id,
        Intent(context, SkyAlertReceiver::class.java).putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** Cancels pending alarms only; alerts already on screen stay until the person dismisses them. */
    private fun cancelAlarms(context: Context, alarmManager: AlarmManager) {
        listOf(SUNRISE_ID, SUNSET_ID).forEach { id ->
            val pending = alarmIntent(context, id)
            alarmManager.cancel(pending)
            pending.cancel()
        }
    }

    private fun savePlan(context: Context, plans: List<AlertPlan>) {
        val array = JSONArray()
        plans.forEach { array.put(it.toJson()) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(PLAN_KEY, array.toString())
            .apply()
    }

    /**
     * Re-checks [plan] against a fresh forecast right before alerting. Returns
     * the plan to announce, or null when the chance has fallen below threshold.
     * A slow or failed refresh falls back to the planned numbers.
     */
    internal suspend fun refreshPlan(context: Context, plan: AlertPlan): AlertPlan? {
        val store = Store(context)
        if (!store.notificationsEnabled) return null
        if (plan.mode == "sunrise" && !store.sunriseAlerts) return null
        if (plan.mode == "sunset" && !store.sunsetAlerts) return null
        val repository = ForecastRepository(ApiClient(store))
        val result = withTimeoutOrNull(8_000L) {
            runCatching { repository.load(plan.place) }.getOrNull()
        } ?: return plan
        val day = result.days.firstOrNull { abs(it[plan.mode].toEpochMilli() - plan.eventAt) <= EVENT_MATCH_MS } ?: return plan
        val score = day.scores[plan.mode]
        val probability = Math.round(score.probability).toInt()
        if (probability < store.alertThreshold) return null
        val appearance = day.appearanceWindow[plan.mode]
        return plan.copy(
            probability = probability,
            eventAt = day[plan.mode].toEpochMilli(),
            windowLabel = formatRange(appearance.start, appearance.end, day.timeZone),
            reason = score.boosts.firstOrNull() ?: plan.reason,
        )
    }

    fun notify(context: Context, plan: AlertPlan) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val label = if (plan.mode == "sunrise") "Sunrise" else "Sunset"
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_MODE, plan.mode)
            putExtra(EXTRA_EVENT_AT, plan.eventAt)
            putExtra(EXTRA_PLACE, plan.place.toJson().toString())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            if (plan.mode == "sunrise") 8101 else 8102,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val direction = if (plan.bearing.isNaN()) "" else " · face ${compassLabel(plan.bearing)} ${Math.round(plan.bearing)}°"
        val summary = "Peak color ${plan.windowLabel}$direction"
        val detail = buildString {
            append(summary)
            append(". ")
            append(describeScore(plan.probability.toDouble()))
            if (plan.reason.isNotBlank()) append(": ${plan.reason.replaceFirstChar { it.lowercase() }}")
            append('.')
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$label color chance ${plan.probability}% · ${plan.place.name}")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setWhen(plan.eventAt)
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()
        NotificationManagerCompat.from(context).notify(plan.id, notification)
    }

    /**
     * Without "Alarms & reminders" access (denied by default from Android 14),
     * alerts fall back to inexact alarms the system may deliver up to an hour late.
     */
    fun exactAlarmsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()

    /** Opens the system screen where the person can allow exact alarms for FireSky. */
    fun exactAlarmSettingsIntent(context: Context): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))
        } else {
            null
        }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Sunrise & sunset alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Heads-up before a colorful sunrise or sunset at your place"
            },
        )
    }
}

class SkyAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val plan = SkyAlerts.loadPlan(context).firstOrNull { it.id == id } ?: return
        val pending = goAsync()
        receiverScope.launch {
            try {
                ClockStyle.use24Hour = android.text.format.DateFormat.is24HourFormat(context)
                SkyAlerts.refreshPlan(context, plan)?.let { SkyAlerts.notify(context, it) }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Exact-alarm access granted later in Settings: re-arm so alerts become on-time.
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            SkyAlerts.rescheduleFromStore(context)
            ForecastRefreshWorker.ensureScheduled(context)
        }
    }
}
