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
import com.firesky.app.data.forecastDayKey
import com.firesky.app.data.formatRange
import com.firesky.app.data.sunAzimuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
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
    private const val FEEDBACK_DELAY_MS = 25L * 60L * 1000L
    private const val IMPROVED_JUMP_THRESHOLD = 12
    const val ACTION_RATE_FEEDBACK = "com.firesky.app.ACTION_RATE_FEEDBACK"

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
        val kind: String = "alert",
        val dayKey: String = "",
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
            .put("kind", kind)
            .put("dayKey", dayKey)

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
                kind = json.optString("kind", "alert"),
                dayKey = json.optString("dayKey", ""),
            )
        }
    }

    private fun placeBaseId(place: Place): Int =
        (place.spotKey().hashCode() and 0x0FFF) * 10

    /**
     * Checks whether [nowMillis] falls within the user's configured Quiet Hours
     * (Do Not Disturb) window in the given [timeZone].
     */
    fun isInQuietHours(
        settings: AccountSettings,
        nowMillis: Long = System.currentTimeMillis(),
        timeZone: String? = null,
    ): Boolean {
        if (!settings.quietHoursEnabled) return false
        val zone = runCatching { if (timeZone != null) ZoneId.of(timeZone) else ZoneId.systemDefault() }
            .getOrDefault(ZoneId.systemDefault())
        val zdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        val minuteOfDay = zdt.hour * 60 + zdt.minute
        val start = ((settings.quietHoursStart % 1440) + 1440) % 1440
        val end = ((settings.quietHoursEnd % 1440) + 1440) % 1440
        return if (start <= end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }

    /**
     * Plans the next sunrise and sunset alert for [place], plus optional
     * post-event feedback check-in notifications 25 minutes after peak color.
     */
    fun plan(
        days: List<Forecast>,
        place: Place,
        settings: AccountSettings,
        now: Long = System.currentTimeMillis(),
    ): List<AlertPlan> {
        if (!settings.notificationsEnabled) return emptyList()
        if (place.spotKey() in settings.disabledAlertPlaceKeys) return emptyList()
        val baseId = placeBaseId(place)
        val results = mutableListOf<AlertPlan>()

        listOf(
            Triple("sunrise", settings.sunriseAlerts, if (baseId == 0) SUNRISE_ID else baseId + 1),
            Triple("sunset", settings.sunsetAlerts, if (baseId == 0) SUNSET_ID else baseId + 2),
        ).forEach { (mode, enabled, id) ->
            if (!enabled) return@forEach
            val day = days.take(PLAN_DAYS).firstOrNull { day ->
                day[mode].toEpochMilli() - settings.alertLeadMinutes * 60_000L > now + MIN_LEAD_MS
            }
            if (day != null) {
                val event = day[mode]
                val score = day.scores[mode]
                val probability = Math.round(score.probability).toInt()
                if (probability >= settings.alertThreshold) {
                    val appearance = day.appearanceWindow[mode]
                    results.add(
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
                            kind = "alert",
                            dayKey = forecastDayKey(day) ?: "",
                        ),
                    )
                }
            }

            // Post-event feedback notification (25 min after appearance window ends)
            if (settings.postEventFeedbackAlerts) {
                val feedbackDay = days.take(PLAN_DAYS).firstOrNull { d ->
                    val endInstant = d.appearanceWindow[mode].end ?: d[mode]
                    val trigger = endInstant.toEpochMilli() + FEEDBACK_DELAY_MS
                    trigger > now + MIN_LEAD_MS && Math.round(d.scores[mode].probability).toInt() >= settings.alertThreshold
                }
                if (feedbackDay != null) {
                    val event = feedbackDay[mode]
                    val appearance = feedbackDay.appearanceWindow[mode]
                    val endInstant = appearance.end ?: event
                    val probability = Math.round(feedbackDay.scores[mode].probability).toInt()
                    results.add(
                        AlertPlan(
                            id = id + 500,
                            mode = mode,
                            triggerAt = endInstant.toEpochMilli() + FEEDBACK_DELAY_MS,
                            eventAt = event.toEpochMilli(),
                            probability = probability,
                            place = place,
                            windowLabel = formatRange(appearance.start, appearance.end, feedbackDay.timeZone),
                            bearing = sunAzimuth(event, place.latitude, place.longitude),
                            reason = "",
                            kind = "feedback",
                            dayKey = forecastDayKey(feedbackDay) ?: "",
                        ),
                    )
                }
            }
        }
        return results
    }

    /**
     * Detects when an upcoming event's probability jumps by >= 12% and reaches
     * the user's alert threshold, firing a "Forecast improved" notification.
     */
    fun checkAndNotifyImprovement(
        context: Context,
        days: List<Forecast>,
        place: Place,
        settings: AccountSettings,
        store: Store,
        allowImmediateNotification: Boolean,
    ) {
        if (!settings.notificationsEnabled || !settings.forecastImprovedAlerts) return
        if (place.spotKey() in settings.disabledAlertPlaceKeys) return
        val now = System.currentTimeMillis()
        val baselines = store.alertBaselines
        var modified = false

        listOf("sunrise" to settings.sunriseAlerts, "sunset" to settings.sunsetAlerts).forEach { (mode, enabled) ->
            if (!enabled) return@forEach
            val upcomingDay = days.take(PLAN_DAYS).firstOrNull { d ->
                val eventMs = d[mode].toEpochMilli()
                eventMs > now + 10 * 60_000L && eventMs - now <= 36L * 3600_000L
            } ?: return@forEach

            val event = upcomingDay[mode]
            val hourBucket = event.toEpochMilli() / (3600_000L * 6L)
            val key = "${place.spotKey()}:$mode:$hourBucket"
            val currentProb = Math.round(upcomingDay.scores[mode].probability).toInt()
            val prevEntry = baselines.optJSONObject(key)

            if (prevEntry == null) {
                baselines.put(
                    key,
                    JSONObject()
                        .put("prob", currentProb)
                        .put("notified", false)
                        .put("updatedAt", now),
                )
                modified = true
            } else {
                val prevProb = prevEntry.optInt("prob", currentProb)
                val alreadyNotified = prevEntry.optBoolean("notified", false)
                val delta = currentProb - prevProb
                if (allowImmediateNotification &&
                    !alreadyNotified &&
                    delta >= IMPROVED_JUMP_THRESHOLD &&
                    currentProb >= settings.alertThreshold &&
                    !isInQuietHours(settings, now, upcomingDay.timeZone)
                ) {
                    val appearance = upcomingDay.appearanceWindow[mode]
                    val improvedPlan = AlertPlan(
                        id = placeBaseId(place) + if (mode == "sunrise") 301 else 302,
                        mode = mode,
                        triggerAt = now,
                        eventAt = event.toEpochMilli(),
                        probability = currentProb,
                        place = place,
                        windowLabel = formatRange(appearance.start, appearance.end, upcomingDay.timeZone),
                        bearing = sunAzimuth(event, place.latitude, place.longitude),
                        reason = "Jumped from $prevProb% to $currentProb%",
                        kind = "improved",
                    )
                    notifyImproved(context, improvedPlan, prevProb)
                    prevEntry.put("notified", true)
                    prevEntry.put("prob", currentProb)
                    modified = true
                } else if (currentProb < prevProb) {
                    prevEntry.put("prob", currentProb)
                    modified = true
                }
            }
        }
        if (modified) {
            store.alertBaselines = baselines
        }
    }

    fun schedule(
        context: Context,
        days: List<Forecast>,
        place: Place,
        settings: AccountSettings,
        checkImprovement: Boolean = false,
    ) {
        ensureChannel(context)
        val store = Store(context)
        checkAndNotifyImprovement(context, days, place, settings, store, allowImmediateNotification = checkImprovement)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val existingOtherPlaces = loadPlan(context).filterNot { it.place.sameSpot(place) }
            .filter { it.place.spotKey() !in settings.disabledAlertPlaceKeys }
        val newPlansForPlace = plan(days, place, settings)
        val merged = if (settings.notificationsEnabled) {
            (existingOtherPlaces + newPlansForPlace)
                .filter { it.triggerAt > System.currentTimeMillis() + MIN_LEAD_MS }
        } else {
            emptyList()
        }

        cancelAlarms(context, alarmManager)
        savePlan(context, merged)
        merged.forEach { schedulePlan(context, alarmManager, it) }
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
        val existingIds = loadPlan(context).map { it.id } + listOf(SUNRISE_ID, SUNSET_ID)
        existingIds.distinct().forEach { id ->
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
     */
    internal suspend fun refreshPlan(context: Context, plan: AlertPlan): AlertPlan? {
        val store = Store(context)
        if (!store.notificationsEnabled) return null
        if (plan.place.spotKey() in store.disabledAlertPlaceKeys) return null
        if (plan.mode == "sunrise" && !store.sunriseAlerts) return null
        if (plan.mode == "sunset" && !store.sunsetAlerts) return null
        if (plan.kind == "feedback") {
            return if (store.postEventFeedbackAlerts) plan else null
        }
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

    private fun currentSettingsFromStore(store: Store): AccountSettings = AccountSettings(
        notificationsEnabled = store.notificationsEnabled,
        alertLeadMinutes = store.alertLeadMinutes,
        sunriseAlerts = store.sunriseAlerts,
        sunsetAlerts = store.sunsetAlerts,
        alertThreshold = store.alertThreshold,
        forecastImprovedAlerts = store.forecastImprovedAlerts,
        postEventFeedbackAlerts = store.postEventFeedbackAlerts,
        quietHoursEnabled = store.quietHoursEnabled,
        quietHoursStart = store.quietHoursStart,
        quietHoursEnd = store.quietHoursEnd,
        disabledAlertPlaceKeys = store.disabledAlertPlaceKeys,
    )

    fun notify(context: Context, plan: AlertPlan) {
        ensureChannel(context)
        val store = Store(context)
        val settings = currentSettingsFromStore(store)
        if (isInQuietHours(settings)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (plan.kind == "feedback") {
            notifyPostEventFeedback(context, plan, store)
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
            plan.id + 1000,
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

    private fun notifyImproved(context: Context, plan: AlertPlan, previousProbability: Int) {
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
            plan.id + 2000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val summary = "Forecast jumped $previousProbability% → ${plan.probability}% · Peak ${plan.windowLabel}"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$label forecast improved · ${plan.place.name}")
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .build()
        NotificationManagerCompat.from(context).notify(plan.id, notification)
    }

    private fun notifyPostEventFeedback(context: Context, plan: AlertPlan, store: Store) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val feedbackKey = "${plan.place.spotKey()}:${plan.mode}:${plan.eventAt / 3600_000L}"
        if (feedbackKey in store.sentPostEventFeedbackIds) return
        store.sentPostEventFeedbackIds = (store.sentPostEventFeedbackIds + feedbackKey).toList().takeLast(40).toSet()

        val label = if (plan.mode == "sunrise") "sunrise" else "sunset"
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_MODE, plan.mode)
            putExtra(EXTRA_EVENT_AT, plan.eventAt)
            putExtra(EXTRA_PLACE, plan.place.toJson().toString())
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            context,
            plan.id + 3000,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        fun ratePending(sentiment: String, reqOffset: Int): PendingIntent {
            val rateIntent = Intent(context, SkyAlertReceiver::class.java).apply {
                action = ACTION_RATE_FEEDBACK
                putExtra("notificationId", plan.id)
                putExtra("sentiment", sentiment)
                putExtra("eventAt", plan.eventAt)
                putExtra("mode", plan.mode)
                putExtra("dayKey", plan.dayKey)
                putExtra("probability", plan.probability.toDouble())
                putExtra("place", plan.place.toJson().toString())
            }
            return PendingIntent.getBroadcast(
                context,
                plan.id + reqOffset,
                rateIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val body = "We predicted ${plan.probability}% (${plan.windowLabel}). Did the sky deliver?"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("How was the $label in ${plan.place.name}?")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .addAction(0, "Accurate", ratePending("like", 4001))
            .addAction(0, "Missed", ratePending("dislike", 4002))
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
        if (intent.action == SkyAlerts.ACTION_RATE_FEEDBACK) {
            val notificationId = intent.getIntExtra("notificationId", -1)
            if (notificationId != -1) {
                NotificationManagerCompat.from(context).cancel(notificationId)
            }
            val sentiment = intent.getStringExtra("sentiment") ?: return
            val eventAt = intent.getLongExtra("eventAt", System.currentTimeMillis())
            val mode = intent.getStringExtra("mode") ?: "sunset"
            val dayKey = intent.getStringExtra("dayKey").orEmpty()
            val probability = intent.getDoubleExtra("probability", 0.0)
            val placeRaw = intent.getStringExtra("place")
            val pending = goAsync()
            receiverScope.launch {
                try {
                    val store = Store(context)
                    val place = placeRaw?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() } ?: store.lastPlace
                    // Same event key and entry shape the app screen uses, so the card there shows this vote
                    // and the app's own sync sends it if the request below can't.
                    val key = if (dayKey.isNotBlank()) "$dayKey:$mode" else null
                    val stamp = System.currentTimeMillis()
                    if (key != null && place != null) {
                        val fb = store.feedback
                        fb.put(
                            key,
                            JSONObject()
                                .put("day", dayKey)
                                .put("mode", mode)
                                .put("place", place.name)
                                .put("probability", Math.round(probability))
                                .put("eventAt", eventAt)
                                .put("latitude", place.latitude)
                                .put("longitude", place.longitude)
                                .put("sentiment", sentiment)
                                .put("reason", JSONObject.NULL)
                                .put("synced", false)
                                .put("updatedAt", stamp),
                        )
                        store.feedback = fb
                    }
                    val token = store.accountToken
                    if (key != null && token.isNotBlank() && place != null) {
                        val sent = runCatching {
                            ApiClient(store).postFeedback(
                                token = token,
                                eventAt = eventAt,
                                mode = mode,
                                placeName = place.name,
                                latitude = place.latitude,
                                longitude = place.longitude,
                                probability = probability,
                                sentiment = sentiment,
                                reason = null,
                            )
                        }.isSuccess
                        if (sent) {
                            val fb = store.feedback
                            val entry = fb.optJSONObject(key)
                            if (entry != null && entry.optLong("updatedAt") == stamp) {
                                fb.put(key, entry.put("synced", true))
                                store.feedback = fb
                            }
                        }
                    }
                } finally {
                    pending.finish()
                }
            }
            return
        }

        val id = intent.getIntExtra("id", -1)
        val plan = SkyAlerts.loadPlan(context).firstOrNull { it.id == id } ?: return
        val pending = goAsync()
        receiverScope.launch {
            try {
                val store = Store(context)
                ClockStyle.sync(context, store)
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
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
        ) {
            SkyAlerts.rescheduleFromStore(context)
            ForecastRefreshWorker.ensureScheduled(context)
        }
    }
}
