package com.firesky.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.firesky.app.ui.FireSkyApp
import com.firesky.app.ui.theme.FireSkyTheme

class MainActivity : ComponentActivity() {
    private val viewModel: FireSkyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
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
        viewModel.onAppForeground()
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val data = intent.data
        if (data?.scheme == "com.firesky.app" && data.host == "auth") {
            val token = data.getQueryParameter("auth_token")
            if (!token.isNullOrBlank()) {
                viewModel.setAccountVisible(true)
                viewModel.acceptAccountToken(token)
            } else {
                viewModel.setAccountVisible(true)
            }
        }
        intent.getStringExtra("forecastMode")?.let { viewModel.onNotificationMode(it) }
    }
}
