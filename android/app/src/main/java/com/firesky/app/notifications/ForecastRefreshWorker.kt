package com.firesky.app.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.firesky.app.data.AccountSettings
import com.firesky.app.data.ApiClient
import com.firesky.app.data.ClockStyle
import com.firesky.app.data.ForecastRepository
import com.firesky.app.data.Place
import com.firesky.app.data.Store
import com.firesky.app.widget.TonightSkyWidget
import java.util.concurrent.TimeUnit

/**
 * Keeps alert plans and the home-screen widget current while the app is closed:
 * every 3 hours it re-scores the primary place + alert-enabled favorite places,
 * notifies if an upcoming forecast improved significantly, and re-plans alarms.
 */
class ForecastRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val primaryPlace = store.lastPlace ?: return Result.success()
        val settings = AccountSettings(
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
        ClockStyle.sync(applicationContext, store)
        val api = ApiClient(store)
        val repository = ForecastRepository(api)

        // Keep device push registration fresh alongside background WorkManager runs.
        PushTokenManager.syncRegistration(applicationContext, store, api)

        val watchedPlaces = buildList<Place> {
            add(primaryPlace)
            store.favorites.forEach { fav ->
                if (none { it.sameSpot(fav) }) add(fav)
            }
        }.take(5)

        var primaryLoaded = false
        for (place in watchedPlaces) {
            try {
                val result = repository.load(place)
                if (place.sameSpot(primaryPlace)) {
                    primaryLoaded = true
                    TonightSkyWidget.updateFromForecast(applicationContext, place, result.days)
                }
                if (settings.notificationsEnabled && place.spotKey() !in settings.disabledAlertPlaceKeys) {
                    SkyAlerts.schedule(
                        context = applicationContext,
                        days = result.days,
                        place = place,
                        settings = settings,
                        checkImprovement = true,
                    )
                }
            } catch (_: Exception) {
            }
        }
        return if (!primaryLoaded && runAttemptCount < 2) Result.retry() else Result.success()
    }

    companion object {
        private const val WORK_NAME = "firesky-forecast-refresh"
        private const val IMMEDIATE_WORK_NAME = "firesky-forecast-refresh-now"

        fun ensureScheduled(context: Context) {
            val request = PeriodicWorkRequestBuilder<ForecastRefreshWorker>(3, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun runOnceNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ForecastRefreshWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(IMMEDIATE_WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
