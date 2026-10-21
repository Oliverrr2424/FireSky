package com.firesky.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.firesky.app.data.ClockStyle
import com.firesky.app.data.Place
import com.firesky.app.data.Store
import com.firesky.app.notifications.SkyAlerts
import com.firesky.app.ui.FireSkyApp
import com.firesky.app.ui.theme.FireSkyTheme
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    private val viewModel: FireSkyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Every backdrop is a saturated sky behind white type, so system bars always use light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        ClockStyle.sync(this, Store(this))
        // A recreated activity must not replay the intent that launched it (e.g. a consumed OAuth code).
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            FireSkyTheme {
                FireSkyApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        ClockStyle.sync(this, Store(this))
        viewModel.onAppForeground()
    }

    override fun onStop() {
        super.onStop()
        viewModel.onAppBackground()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (data?.scheme == "com.firesky.app" && data.host == "auth") {
            viewModel.setAccountVisible(true)
            val code = data.getQueryParameter("auth_code")
            val legacyToken = data.getQueryParameter("auth_token")
            when {
                !code.isNullOrBlank() -> viewModel.completeOAuth(code)
                !legacyToken.isNullOrBlank() -> viewModel.acceptLegacyOAuthToken(legacyToken)
            }
        }
        val mode = intent.getStringExtra(SkyAlerts.EXTRA_MODE)
        if (mode != null) {
            val place = intent.getStringExtra(SkyAlerts.EXTRA_PLACE)?.let { raw ->
                runCatching { Place.fromJson(JSONObject(raw)) }.getOrNull()
            }
            viewModel.openFromAlert(place, mode, intent.getLongExtra(SkyAlerts.EXTRA_EVENT_AT, 0L))
            // Consume the alert so a later configuration change doesn't reopen it.
            intent.removeExtra(SkyAlerts.EXTRA_MODE)
        }
    }
}
