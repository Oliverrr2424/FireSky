package com.firesky.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.firesky.app.data.AccountSettings
import com.firesky.app.data.ApiClient
import com.firesky.app.data.ClockStyle
import com.firesky.app.data.ForecastRepository
import com.firesky.app.data.Store
import java.util.concurrent.TimeUnit

/**
 * Keeps alert plans current while the app is closed: every few hours it
 * re-scores the person's place and re-plans the next sunrise and sunset alert.
 */
class ForecastRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val place = store.lastPlace ?: return Result.success()
        val settings = AccountSettings(
            notificationsEnabled = store.notificationsEnabled,
            alertLeadMinutes = store.alertLeadMinutes,
            sunriseAlerts = store.sunriseAlerts,
            sunsetAlerts = store.sunsetAlerts,
            alertThreshold = store.alertThreshold,
        )
        if (!settings.notificationsEnabled) return Result.success()
        ClockStyle.use24Hour = android.text.format.DateFormat.is24HourFormat(applicationContext)
        return try {
            val result = ForecastRepository(ApiClient(store)).load(place)
            SkyAlerts.schedule(applicationContext, result.days, place, settings)
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "firesky-forecast-refresh"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<ForecastRefreshWorker>(3, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
