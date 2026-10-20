package com.firesky.app

import android.app.Application
import org.maplibre.android.MapLibre

class FireSkyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // OpenGL backend: the Vulkan-only artifact cannot render on the
        // emulator and is less mature than the MapLibre GL JS path.
        MapLibre.getInstance(this)
    }
}
