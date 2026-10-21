package com.firesky.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firesky.app.R
import com.firesky.app.UiState
import com.firesky.app.data.PRESET_PLACES
import com.firesky.app.data.Place
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.UiFamily
import kotlin.math.sin

private val Ink = Color(0xFF07090F)
private val Earth = Color(0xFF0B0F1A)
private val Fog = Color(0xFF9AA3B2)
private val Mist = Color(0xFF5F6878)
private val Pill = Color(0xFF151A26)
private val PillLine = Color(0xFF262D3C)

/** Sky colors above the horizon: dusk for sunset, dawn for sunrise. */
private data class SkyPalette(val core: Color, val halo: Color, val rim: Color)

private val Dusk = SkyPalette(Color(0xFFFF8A4C), Color(0xFF8E2F6B), Color(0xFFFFB07A))
private val Dawn = SkyPalette(Color(0xFFFFC489), Color(0xFF3F6BB5), Color(0xFFBFD8FF))

private const val STEP_WELCOME = 0
private const val STEP_VALUE = 1
private const val STEP_LOCATION = 2
private const val STEP_ALERTS = 3
private const val STEP_UNITS = 4
private const val STEP_DONE = 5
private const val STEP_COUNT = 6

@Composable
fun OnboardingScreen(
    state: UiState,
    locationBusy: Boolean,
    locationGranted: Boolean,
    onRequestLocation: () -> Unit,
    onSelectPlace: (Place) -> Unit,
    onThresholdChanged: (Int) -> Unit,
    onLeadMinutesChanged: (Int) -> Unit,
    onSunriseAlertsChanged: (Boolean) -> Unit,
    onSunsetAlertsChanged: (Boolean) -> Unit,
    onImprovedAlertsChanged: (Boolean) -> Unit,
    onRequestNotificationPermission: (onComplete: () -> Unit) -> Unit,
    onSkipNotifications: () -> Unit,
    onTemperatureUnitChanged: (String) -> Unit,
    onDistanceUnitChanged: (String) -> Unit,
    onTimeFormatChanged: (String) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onFinish: () -> Unit,
) {
    var step by remember { mutableIntStateOf(STEP_WELCOME) }

    // The sky follows what the person is choosing: morning palette when only sunrise is watched.
    val palette = when {
        step == STEP_ALERTS && state.sunriseAlerts && !state.sunsetAlerts -> Dawn
        step == STEP_VALUE || step == STEP_DONE -> Dawn
        else -> Dusk
    }
    val glowStrength = when (step) {
        STEP_WELCOME -> 1f
        STEP_ALERTS -> 0.55f + (state.alertThreshold - 60) / 20f * 0.45f
        STEP_DONE -> 1f
        else -> 0.7f
    }

    Box(Modifier.fillMaxSize().background(Ink)) {
        HorizonBackdrop(palette = palette, strength = glowStrength, lowered = step != STEP_WELCOME)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 28.dp),
        ) {
            TopRow(
                showBack = step > STEP_WELCOME && step < STEP_DONE,
                showSkip = step in STEP_VALUE until STEP_DONE,
                onBack = { step-- },
                onSkip = { step = STEP_DONE },
            )

            AnimatedContent(
                targetState = step,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                transitionSpec = {
                    (fadeIn(tween(420, delayMillis = 80)) + slideInVertically(tween(420, delayMillis = 80)) { it / 12 }) togetherWith
                        (fadeOut(tween(220)) + slideOutVertically(tween(220)) { -it / 16 })
                },
                label = "onboardingStep",
            ) { current ->
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.weight(if (current == STEP_WELCOME) 1.15f else 0.9f))
                    when (current) {
                        STEP_WELCOME -> WelcomeStep(onNext = { step = STEP_VALUE })
                        STEP_VALUE -> ValueStep(onNext = { step = STEP_LOCATION })
                        STEP_LOCATION -> LocationStep(
                            place = state.place,
                            locationBusy = locationBusy,
                            locationGranted = locationGranted,
                            onRequestLocation = onRequestLocation,
                            onSelectPlace = onSelectPlace,
                            onNext = { step = STEP_ALERTS },
                        )
                        STEP_ALERTS -> AlertsStep(
                            state = state,
                            onThresholdChanged = onThresholdChanged,
                            onLeadMinutesChanged = onLeadMinutesChanged,
                            onSunriseAlertsChanged = onSunriseAlertsChanged,
                            onSunsetAlertsChanged = onSunsetAlertsChanged,
                            onEnable = { onRequestNotificationPermission { step = STEP_UNITS } },
                            onSkip = {
                                onSkipNotifications()
                                step = STEP_UNITS
                            },
                        )
                        STEP_UNITS -> UnitsStep(
                            state = state,
                            onTemperatureUnitChanged = onTemperatureUnitChanged,
                            onDistanceUnitChanged = onDistanceUnitChanged,
                            onTimeFormatChanged = onTimeFormatChanged,
                            onLanguageChanged = onLanguageChanged,
                            onNext = { step = STEP_DONE },
                        )
                        else -> DoneStep(state = state, onFinish = onFinish)
                    }
                    Spacer(Modifier.weight(0.35f))
                }
            }

            StepProgress(step = step, count = STEP_COUNT)
        }
    }
}

// ---- Backdrop ---------------------------------------------------------------

@Composable
private fun HorizonBackdrop(palette: SkyPalette, strength: Float, lowered: Boolean) {
    val core by animateColorAsState(palette.core, tween(900), label = "core")
    val halo by animateColorAsState(palette.halo, tween(900), label = "halo")
    val rim by animateColorAsState(palette.rim, tween(900), label = "rim")
    val glow by animateFloatAsState(strength, tween(700, easing = FastOutSlowInEasing), label = "glow")
    val horizon by animateFloatAsState(if (lowered) 0.30f else 0.40f, tween(900, easing = FastOutSlowInEasing), label = "horizon")
    val breath by rememberInfiniteTransition(label = "breath").animateFloat(
        initialValue = 0.92f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(5200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathValue",
    )
    val stars = remember {
        List(26) { index ->
            val t = index * 0.618f
            Offset((t - t.toInt()), ((index * 0.371f) % 1f) * 0.6f)
        }
    }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val arcTop = h * horizon
        val radius = w * 1.65f
        val center = Offset(w / 2f, arcTop + radius)

        stars.forEachIndexed { index, s ->
            val y = s.y * arcTop
            val twinkle = 0.35f + 0.35f * sin(breath * 6f + index).coerceAtLeast(0f)
            drawCircle(Color.White.copy(alpha = twinkle * 0.5f), radius = if (index % 5 == 0) 1.8f else 1.1f, center = Offset(s.x * w, y))
        }

        // Atmosphere: a wide halo sitting on the horizon, breathing slowly.
        val haloRadius = w * 0.95f * breath
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    core.copy(alpha = 0.55f * glow),
                    halo.copy(alpha = 0.32f * glow),
                    halo.copy(alpha = 0.08f * glow),
                    Color.Transparent,
                ),
                center = Offset(w / 2f, arcTop + 40f),
                radius = haloRadius,
            ),
            radius = haloRadius,
            center = Offset(w / 2f, arcTop + 40f),
        )
        // Thin bright band right at the limb.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, rim.copy(alpha = 0.55f * glow), Color.Transparent),
                center = center,
                radius = radius + 18.dp.toPx(),
            ),
            radius = radius + 18.dp.toPx(),
            center = center,
        )

        // The planet itself.
        drawCircle(
            brush = Brush.verticalGradient(listOf(Color(0xFF111726), Earth, Ink), startY = arcTop, endY = h),
            radius = radius,
            center = center,
        )
        drawCircle(rim.copy(alpha = 0.7f * glow), radius = radius, center = center, style = Stroke(1.2.dp.toPx()))
    }
}

// ---- Chrome -----------------------------------------------------------------

@Composable
private fun TopRow(showBack: Boolean, showSkip: Boolean, onBack: () -> Unit, onSkip: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        if (showBack) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0x1FFFFFFF))
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(R.drawable.ic_chevron_left, stringResource(R.string.onboard_back), tint = Color.White, size = 18.dp)
            }
        } else {
            Spacer(Modifier.size(36.dp))
        }
        if (showSkip) {
            Text(
                text = stringResource(R.string.onboard_skip),
                color = Mist,
                fontFamily = UiFamily,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onSkip)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun StepProgress(step: Int, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        repeat(count) { index ->
            val alpha by animateFloatAsState(if (index <= step) 0.9f else 0.18f, tween(400), label = "seg$index")
            Box(
                Modifier
                    .weight(1f)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = alpha)),
            )
        }
    }
}

@Composable
private fun Headline(title: String, body: String? = null) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            color = Color.White,
            fontFamily = DisplayFamily,
            fontSize = 26.sp,
            fontWeight = FontWeight.W600,
            lineHeight = 33.sp,
            letterSpacing = (-0.4).sp,
            textAlign = TextAlign.Center,
        )
        if (body != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = body,
                color = Fog,
                fontFamily = UiFamily,
                fontSize = 15.sp,
                lineHeight = 23.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 420.dp)
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(if (enabled) Color(0xFFF2F2F2) else Color(0x33FFFFFF))
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
        } else {
            Text(text, color = Ink, fontFamily = UiFamily, fontSize = 15.sp, fontWeight = FontWeight.W600)
        }
    }
}

@Composable
private fun GhostButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Fog, fontFamily = UiFamily, fontSize = 14.sp)
    }
}

/** A small dark pill. Selected pills invert to white, like a chosen answer. */
@Composable
private fun ChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
) {
    val bg by animateColorAsState(if (selected) Color(0xFFF2F2F2) else Pill, tween(220), label = "pillBg")
    val fg by animateColorAsState(if (selected) Ink else Color(0xFFE4E7EE), tween(220), label = "pillFg")
    Row(
        modifier = modifier
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, if (selected) Color.Transparent else PillLine, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            LucideIcon(icon, null, tint = fg, size = 14.dp)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = fg, fontFamily = UiFamily, fontSize = 14.sp, fontWeight = FontWeight.W500, maxLines = 1)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = Mist,
        fontFamily = UiFamily,
        fontSize = 12.sp,
        letterSpacing = 0.3.sp,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

// ---- Steps ------------------------------------------------------------------

@Composable
private fun WelcomeStep(onNext: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        LucideIcon(R.drawable.ic_sun_medium, null, tint = Color.White, size = 26.dp)
        Spacer(Modifier.height(18.dp))
        Headline(stringResource(R.string.onboard_welcome_title), stringResource(R.string.onboard_welcome_body))
        Spacer(Modifier.height(36.dp))
        PrimaryButton(stringResource(R.string.onboard_get_started), onNext)
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(R.string.onboard_terms),
            color = Mist,
            fontFamily = UiFamily,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}

@Composable
private fun ValueStep(onNext: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Headline(stringResource(R.string.onboard_value_title), stringResource(R.string.onboard_value_body))
        Spacer(Modifier.height(40.dp))
        PrimaryButton(stringResource(R.string.onboard_continue), onNext)
    }
}

@Composable
private fun LocationStep(
    place: Place,
    locationBusy: Boolean,
    locationGranted: Boolean,
    onRequestLocation: () -> Unit,
    onSelectPlace: (Place) -> Unit,
    onNext: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Headline(stringResource(R.string.onboard_location_title), stringResource(R.string.onboard_location_body))
        Spacer(Modifier.height(28.dp))
        Column(
            modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ChoicePill(
                text = if (locationGranted && PRESET_PLACES.none { it.sameSpot(place) }) place.name else stringResource(R.string.onboard_use_gps),
                selected = locationGranted && PRESET_PLACES.none { it.sameSpot(place) },
                onClick = onRequestLocation,
                icon = if (locationBusy) R.drawable.ic_loader_circle else R.drawable.ic_locate_fixed,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.onboard_or_pick_city),
                color = Mist,
                fontFamily = UiFamily,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 4.dp),
            )
            PRESET_PLACES.forEach { preset ->
                ChoicePill(
                    text = preset.name,
                    selected = preset.sameSpot(place),
                    onClick = { onSelectPlace(preset) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton(stringResource(R.string.onboard_continue), onNext)
    }
}

@Composable
private fun AlertsStep(
    state: UiState,
    onThresholdChanged: (Int) -> Unit,
    onLeadMinutesChanged: (Int) -> Unit,
    onSunriseAlertsChanged: (Boolean) -> Unit,
    onSunsetAlertsChanged: (Boolean) -> Unit,
    onEnable: () -> Unit,
    onSkip: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Headline(stringResource(R.string.onboard_alerts_title), stringResource(R.string.onboard_alerts_body))
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 340.dp).fillMaxWidth()) {
            FieldLabel(stringResource(R.string.onboard_threshold_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(60, 70, 80).forEach { value ->
                    ChoicePill("$value%", state.alertThreshold == value, { onThresholdChanged(value) }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.onboard_lead_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(30 to "30 min", 60 to "1 h", 120 to "2 h").forEach { (minutes, label) ->
                    ChoicePill(label, state.alertLeadMinutes == minutes, { onLeadMinutesChanged(minutes) }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.onboard_watch_label))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill(
                    stringResource(R.string.mode_sunrise),
                    state.sunriseAlerts,
                    { onSunriseAlertsChanged(!state.sunriseAlerts) },
                    Modifier.weight(1f),
                )
                ChoicePill(
                    stringResource(R.string.mode_sunset),
                    state.sunsetAlerts,
                    { onSunsetAlertsChanged(!state.sunsetAlerts) },
                    Modifier.weight(1f),
                )
            }
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton(stringResource(R.string.onboard_enable_alerts_cta), onEnable, enabled = state.sunriseAlerts || state.sunsetAlerts)
        Spacer(Modifier.height(4.dp))
        GhostButton(stringResource(R.string.onboard_skip_alerts_cta), onSkip)
    }
}

@Composable
private fun UnitsStep(
    state: UiState,
    onTemperatureUnitChanged: (String) -> Unit,
    onDistanceUnitChanged: (String) -> Unit,
    onTimeFormatChanged: (String) -> Unit,
    onLanguageChanged: (String) -> Unit,
    onNext: () -> Unit,
) {
    val prefs = state.unitPrefs
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Headline(stringResource(R.string.onboard_units_title), stringResource(R.string.onboard_units_body))
        Spacer(Modifier.height(28.dp))
        Column(Modifier.widthIn(max = 340.dp).fillMaxWidth()) {
            FieldLabel(stringResource(R.string.unit_temperature))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill("°C", prefs.temperatureUnit == "C", { onTemperatureUnitChanged("C") }, Modifier.weight(1f))
                ChoicePill("°F", prefs.temperatureUnit == "F", { onTemperatureUnitChanged("F") }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.unit_distance))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoicePill("km", prefs.distanceUnit == "km", { onDistanceUnitChanged("km") }, Modifier.weight(1f))
                ChoicePill("mi", prefs.distanceUnit == "mi", { onDistanceUnitChanged("mi") }, Modifier.weight(1f))
            }
            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.unit_clock))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("system" to "Auto", "24h" to "24 h", "12h" to "12 h").forEach { (value, label) ->
                    ChoicePill(label, prefs.timeFormat == value, { onTimeFormatChanged(value) }, Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(18.dp))
            FieldLabel(stringResource(R.string.unit_language))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoicePill("Auto", prefs.languageTag == "system", { onLanguageChanged("system") }, Modifier.weight(1f))
                    ChoicePill("English", prefs.languageTag == "en", { onLanguageChanged("en") }, Modifier.weight(1f))
                    ChoicePill("中文", prefs.languageTag == "zh-Hans", { onLanguageChanged("zh-Hans") }, Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoicePill("Français", prefs.languageTag == "fr-CA", { onLanguageChanged("fr-CA") }, Modifier.weight(1f))
                    ChoicePill("Español", prefs.languageTag == "es-MX", { onLanguageChanged("es-MX") }, Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(32.dp))
        PrimaryButton(stringResource(R.string.onboard_continue), onNext)
    }
}

@Composable
private fun DoneStep(state: UiState, onFinish: () -> Unit) {
    val lead = when (state.alertLeadMinutes) {
        30 -> "30 min"
        120 -> "2 h"
        else -> "1 h"
    }
    val summary = buildString {
        append(state.place.name)
        if (state.notificationsEnabled) append("  ·  ${state.alertThreshold}%+  ·  $lead")
        append("  ·  °${state.unitPrefs.temperatureUnit}")
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Headline(stringResource(R.string.onboard_done_title), stringResource(R.string.onboard_done_body))
        Spacer(Modifier.height(20.dp))
        Text(summary, color = Mist, fontFamily = UiFamily, fontSize = 12.sp, letterSpacing = 0.3.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(36.dp))
        PrimaryButton(stringResource(R.string.onboard_finish_cta), onFinish)
    }
}

// ---- Shared with Account page ---------------------------------------------------

@Composable
fun UnitSegmentRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, color = com.firesky.app.ui.theme.TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.W600)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            options.forEach { (value, text) ->
                val isSelected = selected == value
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color(0x3DFFD76A) else com.firesky.app.ui.theme.SurfaceControl)
                        .border(1.dp, if (isSelected) Color(0xFFFFD76A) else com.firesky.app.ui.theme.SurfaceHairline, RoundedCornerShape(12.dp))
                        .clickable { onSelect(value) }
                        .padding(vertical = 9.dp, horizontal = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = text,
                        color = if (isSelected) Color.White else com.firesky.app.ui.theme.TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.W700 else FontWeight.W500,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
