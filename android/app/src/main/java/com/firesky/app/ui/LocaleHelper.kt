package com.firesky.app.ui

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

@Composable
fun LocalizedAppContainer(
    languageTag: String,
    content: @Composable () -> Unit,
) {
    val baseContext = LocalContext.current
    val baseConfig = LocalConfiguration.current
    val (localizedContext, localizedConfig) = remember(baseContext, baseConfig, languageTag) {
        if (languageTag == "system" || languageTag.isBlank()) {
            baseContext to baseConfig
        } else {
            val locale = Locale.forLanguageTag(languageTag)
            Locale.setDefault(locale)
            val config = Configuration(baseConfig).apply {
                setLocale(locale)
            }
            baseContext.createConfigurationContext(config) to config
        }
    }
    CompositionLocalProvider(
        LocalContext provides localizedContext,
        LocalConfiguration provides localizedConfig,
        content = content,
    )
}
