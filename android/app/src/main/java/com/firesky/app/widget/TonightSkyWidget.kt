package com.firesky.app.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.firesky.app.MainActivity
import com.firesky.app.R
import com.firesky.app.data.ClockStyle
import com.firesky.app.data.Forecast
import com.firesky.app.data.Place
import com.firesky.app.data.Store
import com.firesky.app.data.describeScore
import com.firesky.app.data.formatRange
import com.firesky.app.data.formatTemperature
import com.firesky.app.data.upcomingEvents
import com.firesky.app.data.weatherDescription
import org.json.JSONObject

class TonightSkyWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = readSnapshot(context)
        provideContent {
            WidgetContent(context, snapshot)
        }
    }

    @Composable
    private fun WidgetContent(context: Context, data: WidgetSnapshot) {
        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra("forecastMode", data.mode)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val titleText = if (data.mode == "sunrise") {
            context.getString(R.string.widget_next_sunrise)
        } else {
            context.getString(R.string.widget_tonight_sunset)
        }
        val accentColor = when {
            data.probability >= 75 -> Color(0xFFFFB85C)
            data.probability >= 52 -> Color(0xFFFF8A5B)
            else -> Color(0xFF8CC4FF)
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(22.dp)
                .background(ColorProvider(Color(0xFF132744)))
                .clickable(actionStartActivity(openIntent))
                .padding(14.dp),
        ) {
            Column(
                modifier = GlanceModifier.fillMaxSize(),
                verticalAlignment = Alignment.Vertical.CenterVertically,
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Column(modifier = GlanceModifier.defaultWeight()) {
                        Text(
                            text = "${data.placeName} · $titleText",
                            style = TextStyle(
                                color = ColorProvider(Color(0xCCFFFFFF)),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            maxLines = 1,
                        )
                        Spacer(GlanceModifier.height(4.dp))
                        Text(
                            text = context.getString(R.string.widget_peak_window, data.windowLabel),
                            style = TextStyle(
                                color = ColorProvider(Color.White),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                        Spacer(GlanceModifier.height(2.dp))
                        Text(
                            text = "${data.temperatureLabel} · ${data.weatherLabel}",
                            style = TextStyle(
                                color = ColorProvider(Color(0x99FFFFFF)),
                                fontSize = 11.sp,
                            ),
                            maxLines = 1,
                        )
                    }
                    Spacer(GlanceModifier.width(10.dp))
                    Column(
                        horizontalAlignment = Alignment.Horizontal.End,
                    ) {
                        Text(
                            text = "${data.probability}%",
                            style = TextStyle(
                                color = ColorProvider(accentColor),
                                fontSize = 28.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                        )
                        Text(
                            text = data.qualityLabel,
                            style = TextStyle(
                                color = ColorProvider(Color(0xDDFFFFFF)),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }

    data class WidgetSnapshot(
        val placeName: String,
        val mode: String,
        val probability: Int,
        val qualityLabel: String,
        val windowLabel: String,
        val temperatureLabel: String,
        val weatherLabel: String,
    )

    companion object {
        private const val PREFS_NAME = "firesky_widget_state"
        private const val KEY_PAYLOAD = "payload"

        fun readSnapshot(context: Context): WidgetSnapshot {
            return try {
                val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_PAYLOAD, null) ?: return defaultSnapshot(context)
                val json = JSONObject(raw)
                WidgetSnapshot(
                    placeName = json.optString("placeName", "San Francisco"),
                    mode = json.optString("mode", "sunset"),
                    probability = json.optInt("probability", 68),
                    qualityLabel = json.optString("qualityLabel", "Possible"),
                    windowLabel = json.optString("windowLabel", "18:15–18:40"),
                    temperatureLabel = json.optString("temperatureLabel", "16°"),
                    weatherLabel = json.optString("weatherLabel", "Partly Cloudy"),
                )
            } catch (_: Exception) {
                defaultSnapshot(context)
            }
        }

        private fun defaultSnapshot(context: Context): WidgetSnapshot {
            val store = Store(context)
            val place = store.lastPlace?.name ?: "San Francisco"
            return WidgetSnapshot(
                placeName = place,
                mode = "sunset",
                probability = 68,
                qualityLabel = "Possible",
                windowLabel = "18:15–18:40",
                temperatureLabel = "16°",
                weatherLabel = "Partly Cloudy",
            )
        }

        suspend fun updateFromForecast(context: Context, place: Place, days: List<Forecast>) {
            if (days.isEmpty()) return
            try {
                val store = Store(context)
                ClockStyle.sync(context, store)
                val nextEvent = upcomingEvents(days).firstOrNull()
                val day = days.getOrNull(nextEvent?.dayIndex ?: 0) ?: days.first()
                val mode = nextEvent?.mode ?: "sunset"
                val score = day.scores[mode]
                val appearance = day.appearanceWindow[mode]
                val prob = Math.round(score.probability).toInt()
                val json = JSONObject()
                    .put("placeName", place.name)
                    .put("mode", mode)
                    .put("probability", prob)
                    .put("qualityLabel", describeScore(score.probability))
                    .put("windowLabel", formatRange(appearance.start, appearance.end, day.timeZone))
                    .put("temperatureLabel", formatTemperature(day.current.temperature2m, store.temperatureUnit))
                    .put("weatherLabel", weatherDescription(day.current.weatherCode, day.current.cloudCover ?: 0.0))
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_PAYLOAD, json.toString())
                    .apply()

                val manager = GlanceAppWidgetManager(context)
                val ids = manager.getGlanceIds(TonightSkyWidget::class.java)
                ids.forEach { id -> TonightSkyWidget().update(context, id) }
            } catch (_: Exception) {
            }
        }
    }
}

class TonightSkyWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TonightSkyWidget()
}
