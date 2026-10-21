package com.firesky.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firesky.app.R
import com.firesky.app.UiState
import com.firesky.app.data.AppearanceWindow
import com.firesky.app.data.AirSnapshot
import com.firesky.app.data.Factors
import com.firesky.app.data.Forecast
import com.firesky.app.data.ForecastSnapshot
import com.firesky.app.data.Score
import com.firesky.app.data.Window
import com.firesky.app.data.compassLabel
import com.firesky.app.data.describeScore
import com.firesky.app.data.formatDistanceKm
import com.firesky.app.data.formatDuration
import com.firesky.app.data.formatRange
import com.firesky.app.data.formatScoreRange
import com.firesky.app.data.formatTemperature
import com.firesky.app.data.formatTime
import com.firesky.app.data.formatTotalDuration
import com.firesky.app.data.weatherDescription
import androidx.compose.ui.res.stringResource
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.SurfaceControl
import com.firesky.app.ui.theme.SurfaceHairline
import com.firesky.app.ui.theme.SurfaceInset
import com.firesky.app.ui.theme.TextMuted
import com.firesky.app.ui.theme.TextPrimary
import com.firesky.app.ui.theme.TextSecondary
import com.firesky.app.ui.theme.UiFamily
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun HeroCard(state: UiState, outlook: Forecast, modifier: Modifier = Modifier) {
    val active = outlook.scores[state.activeMode]
    val appearance = outlook.appearanceWindow[state.activeMode]
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LucideIcon(R.drawable.ic_sparkles, null, tint = TextSecondary, size = 14.dp)
                    Text(
                        text = if (state.selectedDayIndex == 0) {
                            stringResource(R.string.todays_fire_sky)
                        } else {
                            formatDayTitle(outlook)
                        },
                        color = TextSecondary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = 0.7.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = formatTemperature(outlook.current.temperature2m, state.unitPrefs.temperatureUnit),
                        color = TextPrimary,
                        fontFamily = DisplayFamily,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.W700,
                        lineHeight = 22.sp,
                    )
                    Text(
                        text = weatherDescription(outlook.current.weatherCode, outlook.current.cloudCover ?: 0.0),
                        color = TextSecondary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W700,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = formatRange(appearance.start, appearance.end, outlook.timeZone),
                    color = TextPrimary,
                    fontFamily = DisplayFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.W700,
                    lineHeight = 20.sp,
                )
            }
            ScoreRing(
                value = active.probability,
                label = localizedScoreLabel(active.probability),
                tone = state.activeMode,
                modifier = Modifier.width(142.dp),
            )
        }
    }
}

@Composable
fun localizedScoreLabel(score: Double): String = when {
    score >= 85.0 -> stringResource(R.string.score_excellent)
    score >= 70.0 -> stringResource(R.string.score_high)
    score >= 52.0 -> stringResource(R.string.score_possible)
    score >= 34.0 -> stringResource(R.string.score_slight)
    else -> stringResource(R.string.score_unlikely)
}

private fun formatDayTitle(outlook: Forecast): String = try {
    DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
        .withZone(ZoneId.of(outlook.timeZone))
        .format(outlook.sunset)
        .uppercase(Locale.US)
} catch (_: Exception) {
    "FIRE SKY FORECAST"
}

@Composable
fun ForecastStrip(
    days: List<Forecast>,
    activeMode: String,
    timeZone: String,
    selectedDayIndex: Int,
    compact: Boolean,
    onSelect: (Int) -> Unit,
) {
    if (days.isEmpty()) return
    GlassCard(
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 13.dp, vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.outlook_7_day),
                color = Color.White,
                fontFamily = DisplayFamily,
                fontSize = 13.sp,
                fontWeight = FontWeight.W700,
            )
            if (!compact) {
                Text(
                    stringResource(R.string.outlook_tap_hint),
                    color = TextMuted,
                    fontSize = 11.sp,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            days.forEachIndexed { index, day ->
                val label = if (index == 0) {
                    stringResource(R.string.label_today)
                } else {
                    try {
                        DateTimeFormatter.ofPattern("EEE, M/d", Locale.getDefault())
                            .withZone(ZoneId.of(timeZone))
                            .format(day.sunset)
                    } catch (_: Exception) {
                        "--"
                    }
                }
                val score = day.scores[activeMode].probability
                Column(
                    modifier = Modifier
                        .widthIn(min = 63.dp)
                        .heightIn(min = 64.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (index == selectedDayIndex) SurfaceInset else Color.Transparent)
                        .clickable { onSelect(index) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        label,
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    MiniScoreGauge(score, Modifier.width(68.dp).height(44.dp))
                    Text(
                        if (index == 0) stringResource(R.string.label_forecast) else stringResource(R.string.label_weather),
                        color = TextSecondary,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
fun AstroCard(outlook: Forecast, activeMode: String, compact: Boolean, modifier: Modifier = Modifier) {
    val appearance = outlook.appearanceWindow[activeMode]
    val solarRows = listOf(
        stringResource(R.string.mode_sunrise) to formatTime(outlook.sunrise, outlook.timeZone),
        stringResource(R.string.mode_sunset) to formatTime(outlook.sunset, outlook.timeZone),
    )
    val solarTiles = listOf(
        stringResource(R.string.label_peak_color) to formatRange(appearance.start, appearance.end, outlook.timeZone),
        stringResource(R.string.label_best_duration) to formatDuration(appearance.start, appearance.end),
    )
    val blueRows = listOf(
        stringResource(R.string.label_morning) to formatRange(outlook.blueHour.sunrise.start, outlook.blueHour.sunrise.end, outlook.timeZone),
        stringResource(R.string.label_evening) to formatRange(outlook.blueHour.sunset.start, outlook.blueHour.sunset.end, outlook.timeZone),
    )
    val blueTiles = listOf(
        stringResource(R.string.label_morning_length) to formatDuration(outlook.blueHour.sunrise.start, outlook.blueHour.sunrise.end),
        stringResource(R.string.label_evening_length) to formatDuration(outlook.blueHour.sunset.start, outlook.blueHour.sunset.end),
    )
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(if (compact) 18.dp else 26.dp),
    ) {
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            AstroColumn(
                accent = Color(0x6BE8F5FF),
                kicker = stringResource(R.string.kicker_solar_events),
                rows = solarRows,
                noteLabel = stringResource(R.string.label_day_length),
                noteValue = formatDuration(outlook.sunrise, outlook.sunset),
                tiles = solarTiles,
                modifier = Modifier.fillMaxWidth(),
            )
            AstroColumn(
                accent = Color(0xB3FFFFFF),
                kicker = stringResource(R.string.kicker_blue_hour),
                rows = blueRows,
                noteLabel = stringResource(R.string.label_total),
                noteValue = formatTotalDuration(listOf(outlook.blueHour.sunrise, outlook.blueHour.sunset)),
                tiles = blueTiles,
                modifier = Modifier.fillMaxWidth(),
            )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AstroColumn(
                    accent = Color(0x6BE8F5FF),
                    kicker = stringResource(R.string.kicker_solar_events),
                    rows = solarRows,
                    noteLabel = stringResource(R.string.label_day_length),
                    noteValue = formatDuration(outlook.sunrise, outlook.sunset),
                    tiles = solarTiles,
                    modifier = Modifier.weight(1f),
                )
                AstroColumn(
                    accent = Color(0xB3FFFFFF),
                    kicker = stringResource(R.string.kicker_blue_hour),
                    rows = blueRows,
                    noteLabel = stringResource(R.string.label_total),
                    noteValue = formatTotalDuration(listOf(outlook.blueHour.sunrise, outlook.blueHour.sunset)),
                    tiles = blueTiles,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AstroColumn(
    accent: Color,
    kicker: String,
    rows: List<Pair<String, String>>,
    noteLabel: String,
    noteValue: String,
    tiles: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .width(3.dp)
                .heightIn(min = 120.dp)
                .clip(CircleShape)
                .background(accent),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                kicker.uppercase(Locale.US),
                color = TextMuted,
                fontFamily = DisplayFamily,
                fontSize = 11.sp,
                letterSpacing = 0.8.sp,
            )
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(label, color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text(
                        value,
                        color = Color.White,
                        fontFamily = DisplayFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W700,
                        maxLines = 1,
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(noteLabel, color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    noteValue,
                    color = Color.White,
                    fontFamily = DisplayFamily,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.W700,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tiles.forEach { (label, value) ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceInset)
                            .padding(horizontal = 9.dp, vertical = 8.dp),
                    ) {
                        Text(
                            label.uppercase(Locale.US),
                            color = TextMuted,
                            fontFamily = DisplayFamily,
                            fontSize = 11.sp,
                            letterSpacing = 0.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            value,
                            color = Color.White,
                            fontFamily = DisplayFamily,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.W700,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MetricRail(activeMode: String, data: Forecast, columns: Int = 2) {
    val score = data.scores[activeMode]
    val activeAir = data.airSnapshots[activeMode] ?: data.airSnapshot
    val items = listOf(
        Triple("Now", weatherDescription(data.current.weatherCode, data.current.cloudCover ?: 0.0), R.drawable.ic_cloud_sun),
        Triple("Sunset", formatScoreRange(data.scores.sunset.probability, data.scores.sunset.quality + 8), R.drawable.ic_sun_medium),
        Triple("Sunrise", formatScoreRange(data.scores.sunrise.probability, data.scores.sunrise.quality + 8), R.drawable.ic_moon_star),
        Triple("Cloud Screen", "${Math.round(score.factors.cloudScreen)}%", R.drawable.ic_sparkles),
        Triple("Corridor", "${Math.round(score.factors.solarCorridor)}%", R.drawable.ic_aperture),
        Triple("Window AQI", activeAir.usAqi?.let { "${Math.round(it)}" } ?: "--", 0),
    )
    GlassCard(shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(0.dp)) {
        Column {
            val rowCount = (items.size + columns - 1) / columns
            for (rowIndex in 0 until rowCount) {
                // Cells in a row share the tallest cell's height so the hairline borders stay aligned.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    for (columnIndex in 0 until columns) {
                        val index = rowIndex * columns + columnIndex
                        val item = items.getOrNull(index) ?: continue
                        RailCell(
                            title = item.first,
                            value = item.second,
                            icon = item.third,
                            modifier = Modifier.weight(1f),
                            showStartBorder = columnIndex > 0,
                            showTopBorder = rowIndex > 0,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RailCell(
    title: String,
    value: String,
    icon: Int,
    modifier: Modifier = Modifier,
    showStartBorder: Boolean,
    showTopBorder: Boolean,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = 52.dp)
            .background(Color.Transparent)
            .drawBorders(showStartBorder, showTopBorder)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (icon != 0) {
                LucideIcon(icon, null, tint = TextMuted, size = 16.dp)
            }
            Text(
                title,
                color = TextMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            value,
            color = Color.White,
            fontFamily = DisplayFamily,
            fontSize = 17.sp,
            fontWeight = FontWeight.W700,
            lineHeight = 19.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun Modifier.drawBorders(start: Boolean, top: Boolean): Modifier = this.then(
    Modifier.drawBehind {
        if (start) drawLine(SurfaceHairline, Offset(0f, 0f), Offset(0f, size.height), 1f)
        if (top) drawLine(SurfaceHairline, Offset(0f, 0f), Offset(size.width, 0f), 1f)
    },
)

@Composable
fun WindowForecastCard(
    outlook: Forecast,
    activeMode: String,
    active: Score,
    activeWindow: Window,
    appearance: AppearanceWindow,
    air: AirSnapshot?,
) {
    GlassCard(shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(18.dp)) {
        Text(
            stringResource(R.string.window_forecast_title),
            color = Color.White,
            fontFamily = DisplayFamily,
            fontSize = 17.sp,
            fontWeight = FontWeight.W700,
            lineHeight = 19.sp,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScoreTile(
                label = stringResource(R.string.label_weather_signal),
                value = "${Math.round(active.ruleFallbackProbability)}%",
                modifier = Modifier.weight(1f),
            )
            ScoreTile(
                label = stringResource(R.string.label_intensity),
                value = "${Math.round(active.quality)}%",
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataTile(
                value = formatRange(appearance.start, appearance.end, outlook.timeZone),
                label = stringResource(R.string.label_peak_window),
                modifier = Modifier.weight(1f),
            )
            DataTile(
                value = formatDuration(appearance.start, appearance.end),
                label = stringResource(R.string.label_best_duration),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataTile(
                value = air?.usAqi?.let { "${Math.round(it)}" } ?: "--",
                label = stringResource(R.string.label_window_aqi),
                modifier = Modifier.weight(1f),
            )
            DataTile(
                value = formatDistanceKm((activeWindow.visibility ?: 0.0) / 1000.0),
                label = stringResource(R.string.label_window_visibility),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(16.dp))
        FactorBars(active.factors)
    }
}

@Composable
private fun ScoreTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceInset)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            label.uppercase(Locale.US),
            color = TextMuted,
            fontFamily = DisplayFamily,
            fontSize = 11.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = 0.5.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = Color.White,
            fontFamily = DisplayFamily,
            fontSize = 19.sp,
            fontWeight = FontWeight.W700,
        )
    }
}

@Composable
private fun DataTile(value: String, label: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceInset)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            value,
            color = Color.White,
            fontFamily = DisplayFamily,
            fontSize = 19.sp,
            fontWeight = FontWeight.W700,
            lineHeight = 21.sp,
            maxLines = 2,
        )
        Spacer(Modifier.height(4.dp))
        Text(label, color = TextMuted, fontSize = 11.sp, maxLines = 1)
    }
}

@Composable
fun FactorBars(factors: Factors) {
    val items = listOf(
        stringResource(R.string.factor_cloud_screen) to factors.cloudScreen,
        stringResource(R.string.factor_blocker_clearance) to factors.blockersClearance,
        stringResource(R.string.factor_solar_corridor) to factors.solarCorridor,
        stringResource(R.string.factor_color_chemistry) to factors.colorChemistry,
        stringResource(R.string.factor_sun_access) to factors.sunAccess,
        stringResource(R.string.factor_regional_texture) to factors.regionalTexture,
        stringResource(R.string.factor_rain_clearance) to factors.rain,
        stringResource(R.string.factor_visibility) to factors.visibility,
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(
                                "${Math.round(value)}",
                                color = Color.White,
                                fontFamily = DisplayFamily,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.W700,
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(7.dp)
                                .clip(CircleShape)
                                .background(SurfaceControl),
                        ) {
                            val fraction = (value.coerceIn(0.0, 100.0) / 100.0).toFloat()
                            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(7.dp)) {
                                // The spectrum spans the full track; only its visible extent changes.
                                drawRoundRect(
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            Color(0xFF246097), Color(0xFF3A9CB8),
                                            Color(0xFF6CB881), Color(0xFFF4CF49),
                                            Color(0xFFED703E), Color(0xFFC23646),
                                        ),
                                        endX = size.width,
                                    ),
                                    size = Size(size.width * fraction, size.height),
                                    cornerRadius = CornerRadius(size.height / 2f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TimelineCard(
    state: UiState,
    outlook: Forecast,
    activeWindow: Window,
    onSaveViewpoint: (String) -> Unit,
    onOpenViewpoint: (com.firesky.app.data.Viewpoint) -> Unit,
) {
    val sunBearing = state.sunBearing
    val rainRisk = activeWindow.precipProbability ?: 0.0
    GlassCard(shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(18.dp)) {
        if (state.forecastHistoryLabel.isNotBlank()) {
            Text(state.forecastHistoryLabel, color = TextSecondary, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
        }
        ForecastTimeline(state.forecastSnapshots, state.activeMode, outlook.timeZone)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0x22082A4A)),
        ) {
            FieldTool(
                icon = R.drawable.ic_compass,
                title = stringResource(R.string.sun_direction_title),
                value = "${Math.round(sunBearing)}° ${compassLabel(sunBearing)}",
                note = "Face this direction for the ${state.activeMode} horizon.",
                warning = false,
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceHairline))
            FieldTool(
                icon = R.drawable.ic_cloud_rain,
                title = stringResource(R.string.precip_alert_title),
                value = "${Math.round(rainRisk)}% near the window",
                note = if (rainRisk >= 45) {
                    "Rain may block the view; check again before leaving."
                } else {
                    "No elevated rain risk near the color window."
                },
                warning = rainRisk >= 45,
            )
            SaveViewpointButton(onSaveViewpoint)
        }

        if (state.viewpoints.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.saved_viewpoints_label), color = TextMuted, fontSize = 12.sp)
                state.viewpoints.take(4).forEach { viewpoint ->
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SurfaceControl)
                            .clickable { onOpenViewpoint(viewpoint) }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        LucideIcon(R.drawable.ic_camera, null, tint = TextSecondary, size = 13.dp)
                        Text(viewpoint.name, color = TextPrimary, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldTool(icon: Int, title: String, value: String, note: String, warning: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LucideIcon(icon, null, tint = if (warning) Color(0xFFFF9586) else Color(0xFFFFDA7D), size = 18.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = TextMuted, fontSize = 11.sp)
            Text(
                value,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.W600,
            )
            Text(note, color = TextMuted, fontSize = 11.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun SaveViewpointButton(onSaveViewpoint: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(Color(0x24FFFFFF))
            .clickable {
                name = ""
                showDialog = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            LucideIcon(R.drawable.ic_camera, null, tint = TextPrimary, size = 17.dp)
            Text("Save camera viewpoint", color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.W600)
        }
    }
    if (showDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Name this camera viewpoint") },
            text = {
                androidx.compose.foundation.text.BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(Color.White),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x33000000))
                        .padding(10.dp),
                )
            },
            confirmButton = {
                Text(
                    "Save",
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            if (name.trim().isNotEmpty()) {
                                onSaveViewpoint(name)
                                showDialog = false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
            dismissButton = {
                Text(
                    "Cancel",
                    color = TextSecondary,
                    modifier = Modifier
                        .clickable { showDialog = false }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            },
        )
    }
}

@Composable
fun ForecastTimeline(snapshots: List<ForecastSnapshot>, mode: String, timeZone: String) {
    if (snapshots.isEmpty()) return
    val max = snapshots.maxOf { it.probability }.coerceAtLeast(1.0)
    val min = snapshots.minOf { it.probability }.coerceAtLeast(0.0)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LucideIcon(R.drawable.ic_clock_3, null, tint = TextSecondary, size = 16.dp)
            Text(
                "Regional forecast changes",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.W700,
                modifier = Modifier.weight(1f),
            )
            Text(
                if (mode == "sunrise") "Sunrise event forecast" else "Sunset event forecast",
                color = TextMuted,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            snapshots.forEach { snapshot ->
                val size = 20.0 + ((snapshot.probability - min) / max(1.0, max - min)) * 80.0
                Column(
                    modifier = Modifier.widthIn(min = 52.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier.height(46.dp),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            Modifier
                                .width(18.dp)
                                .height((46 * size / 100.0).dp)
                                .clip(RoundedCornerShape(topStart = 9.dp, topEnd = 9.dp, bottomStart = 2.dp, bottomEnd = 2.dp))
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color(0xFFFFD76A), Color(0xFFED7048)),
                                    ),
                                ),
                        )
                    }
                    Text(
                        "${Math.round(snapshot.probability)}%",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.W700,
                    )
                    Text(
                        java.time.format.DateTimeFormatter.ofPattern("M/d HH:mm").withZone(java.time.ZoneId.of(timeZone)).format(java.time.Instant.ofEpochMilli(snapshot.calculatedAt)),
                        color = TextMuted,
                        fontSize = 11.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Each point is a scheduled regional estimate for the selected $mode. Times show when the forecast was sampled.",
            color = TextMuted,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun FeedbackCard(
    state: UiState,
    outlook: Forecast,
    onToggleReaction: (String) -> Unit,
    onEditing: (Boolean) -> Unit,
    onAlertLead: (Int) -> Unit,
    onAllowExactAlarms: (() -> Unit)? = null,
) {
    val day = state.sunsetDay ?: "--"
    val feedback = state.sunsetFeedback
    val sentiment = feedback?.optString("sentiment")?.takeIf { it.isNotBlank() && it != "null" }
    val hasFeedback = sentiment != null || (feedback?.has("accurate") == true && !feedback.isNull("accurate"))
    val active = outlook.scores[state.activeMode]
    GlassCard(shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(18.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.rate_tonights_sunset),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    letterSpacing = 0.7.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(day, color = Color.White, fontFamily = DisplayFamily, fontSize = 13.sp, fontWeight = FontWeight.W700)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.predicted_sunset_chance), color = TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(
                    "${Math.round(outlook.scores.sunset.probability)}%",
                    color = Color.White,
                    fontFamily = DisplayFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W700,
                )
            }
            Spacer(Modifier.height(9.dp))
            if (hasFeedback && !state.feedbackEditing) {
                Row(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LucideIcon(R.drawable.ic_check, null, tint = Color(0xFFD8F7DF), size = 16.dp)
                    Text("Feedback submitted", color = Color(0xFFD8F7DF), fontSize = 12.sp)
                    Text(
                        when (sentiment) {
                            "like" -> "Liked"
                            "dislike" -> "Disliked"
                            else -> ""
                        },
                        color = TextSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.weight(1f))
                    val updateInteraction = remember { MutableInteractionSource() }
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(
                                interactionSource = updateInteraction,
                                indication = null,
                                role = Role.Button,
                                onClickLabel = "Update sunset feedback",
                                onClick = { onEditing(true) },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(Color(0x1AFFFFFF))
                                .indication(updateInteraction, ripple())
                                .padding(horizontal = 11.dp, vertical = 7.dp),
                        ) {
                            Text("Update", color = TextSecondary, fontSize = 12.sp)
                        }
                    }
                }
            } else {
                // 48dp tall for touch; the visible 44dp track is drawn inset.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .drawBehind {
                            val inset = 2.dp.toPx()
                            val trackHeight = size.height - inset * 2
                            drawRoundRect(
                                color = Color(0x0FFFFFFF),
                                topLeft = Offset(0f, inset),
                                size = Size(size.width, trackHeight),
                                cornerRadius = CornerRadius(trackHeight / 2),
                            )
                        }
                        .padding(horizontal = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    ReactionButton(
                        label = "Like",
                        icon = R.drawable.ic_thumbs_up,
                        selected = sentiment == "like",
                        modifier = Modifier.weight(1f),
                        onClick = { onToggleReaction("like") },
                    )
                    ReactionButton(
                        label = "Dislike",
                        icon = R.drawable.ic_thumbs_down,
                        selected = sentiment == "dislike",
                        modifier = Modifier.weight(1f),
                        onClick = { onToggleReaction("dislike") },
                    )
                }
            }
            Spacer(Modifier.height(7.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Local alert lead time", color = TextSecondary, fontSize = 12.sp, modifier = Modifier.weight(1f))
                SelectPill(
                    value = state.alertLeadMinutes,
                    options = listOf(30 to "30 minutes", 60 to "1 hour", 120 to "2 hours"),
                    onSelect = onAlertLead,
                )
            }
            if (onAllowExactAlarms != null) {
                Spacer(Modifier.height(6.dp))
                ExactAlarmNotice(onAllowExactAlarms)
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceHairline))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LucideIcon(R.drawable.ic_triangle_alert, null, tint = TextSecondary, size = 18.dp)
                Text(
                    text = if (active.blockers.isNotEmpty()) {
                        "Local constraints: ${active.blockers.joinToString("; ")}. Confidence is about ${Math.round(active.confidence)}%."
                    } else {
                        "Local advantages: ${active.boosts.joinToString("; ")}. Confidence is about ${Math.round(active.confidence)}%."
                    },
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

/** Shown while alerts are on but Android has not granted "Alarms & reminders" access. */
@Composable
fun ExactAlarmNotice(onAllow: () -> Unit, modifier: Modifier = Modifier) {
    InlineNotice(
        iconRes = R.drawable.ic_clock_3,
        text = "Alerts may arrive up to an hour late.",
        actionLabel = "Allow on-time alerts",
        onAction = onAllow,
        modifier = modifier,
    )
}

@Composable
private fun ReactionButton(
    label: String,
    icon: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .padding(vertical = 5.dp)
            .clip(CircleShape)
            .background(if (selected) SurfaceInset else Color.Transparent)
            .indication(interactionSource, ripple()),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = if (selected) TextPrimary else TextSecondary,
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (selected) TextPrimary else TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.W600)
    }
}

@Composable
fun SelectPill(value: Int, options: List<Pair<Int, String>>, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = options.firstOrNull { it.first == value }?.second ?: "$value"
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.DropdownList,
                onClick = { expanded = true },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(Color(0x1FFFFFFF))
                .indication(interactionSource, ripple())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, color = Color.White, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            LucideIcon(R.drawable.ic_chevron_down, null, tint = TextSecondary, size = 14.dp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (optionValue, optionLabel) ->
                DropdownMenuItem(
                    text = { Text(optionLabel) },
                    onClick = {
                        onSelect(optionValue)
                        expanded = false
                    },
                )
            }
        }
    }
}
