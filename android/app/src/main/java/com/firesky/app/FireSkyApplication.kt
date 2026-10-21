package com.firesky.app

import android.app.Application
import com.firesky.app.notifications.ForecastRefreshWorker
import com.firesky.app.notifications.SkyAlerts
import org.maplibre.android.MapLibre

class FireSkyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // OpenGL backend: the Vulkan-only artifact cannot render on the
        // emulator and is less mature than the MapLibre GL JS path.
        MapLibre.getInstance(this)
        // Create the channel up front so it shows in system settings before the first alert.
        SkyAlerts.ensureChannel(this)
        ForecastRefreshWorker.ensureScheduled(this)
    }
}
