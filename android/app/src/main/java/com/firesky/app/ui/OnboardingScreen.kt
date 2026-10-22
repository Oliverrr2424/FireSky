package com.firesky.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
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

private val Ink = Color(0xFF07090F)
private val Fog = Color(0xFFD4D6DC)
private val Mist = Color(0xFFAEB4BE)
private val ChoiceSurface = Color(0xFFF7F5F0)
private val ChoiceLine = Color(0xFFE4E0D9)
private val ChoiceInk = Color(0xFF203B3B)
private val SeaGlass = Color(0xFF7CE5DF)

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
    var step by rememberSaveable { mutableIntStateOf(STEP_WELCOME) }
    BackHandler(enabled = step > STEP_WELCOME) { step-- }

    Box(Modifier.fillMaxSize().background(Ink)) {
        SunsetPhotoBackdrop(step = step)

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
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val viewportHeight = maxHeight
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = viewportHeight)
                            .padding(top = 24.dp, bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom,
                    ) {
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
                    }
                }
            }

            StepProgress(step = step, count = STEP_COUNT)
        }
    }
}

// ---- Backdrop ---------------------------------------------------------------

@Composable
private fun SunsetPhotoBackdrop(step: Int) {
    val photo = when (step) {
        STEP_WELCOME -> R.drawable.onboarding_sunset_coast
        STEP_VALUE -> R.drawable.onboarding_sunset_prairie
        STEP_LOCATION -> R.drawable.onboarding_sunset_city
        STEP_ALERTS -> R.drawable.onboarding_sunset_mountain
        STEP_UNITS -> R.drawable.onboarding_sunset_lake
        else -> R.drawable.onboarding_sunset_desert
    }
    // Keep the original slow breathing feel, now through a subtle photographic push-in.
    val scale by rememberInfiniteTransition(label = "sunsetBreath").animateFloat(
        initialValue = 1f,
        targetValue = 1.035f,
        animationSpec = infiniteRepeatable(tween(18000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "photoScale",
    )
    val isSetup = step in STEP_LOCATION..STEP_UNITS
    val shade by animateFloatAsState(if (isSetup) 0.54f else 0.08f, tween(700), label = "photoShade")
    val veil by animateFloatAsState(if (isSetup) 0.24f else 0f, tween(700), label = "setupVeil")

    Crossfade(targetState = photo, animationSpec = tween(900), label = "sunsetLandscape") { resource ->
        Image(
            painter = painterResource(resource),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale },
        )
    }
    // Long forms can reach the upper sky on compact screens or with larger text.
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = veil)))
    // Leave the clouds visible; fade the photograph into black behind copy and controls.
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.15f),
        0.28f to Color.Transparent,
        0.48f to Color.Black.copy(alpha = shade),
        0.70f to Color.Black.copy(alpha = 0.78f),
        0.91f to Color.Black.copy(alpha = 0.98f),
        1f to Color.Black,
    )))
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
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(ChoiceSurface)
                    .clickable(role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(R.drawable.ic_chevron_left, stringResource(R.string.onboard_back), tint = ChoiceInk, size = 18.dp)
            }
        } else {
            Spacer(Modifier.size(36.dp))
        }
        if (showSkip) {
            Text(
                text = stringResource(R.string.onboard_skip),
                color = ChoiceInk,
                fontFamily = UiFamily,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onSkip)
                    .heightIn(min = 48.dp)
                    .background(ChoiceSurface)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            )
        }
    }
}

@Composable
private fun StepProgress(step: Int, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat(), 0f..(count - 1).toFloat(), count - 2) },
        horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val color by animateColorAsState(if (index == step) SeaGlass else Color(0x4DFFFFFF), tween(400), label = "dot$index")
            Box(
                Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color),
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
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(if (enabled) SeaGlass else Color(0xFFDCE4E3))
            .clickable(enabled = enabled && !busy, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ink)
        } else {
            Text(text, color = Ink, fontFamily = UiFamily, fontSize = 15.sp, fontWeight = FontWeight.W600,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
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

/** Opaque warm-white choices; a solid sea-glass fill identifies the current selection. */
@Composable
private fun ChoicePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Int? = null,
) {
    val bg by animateColorAsState(if (selected) SeaGlass else ChoiceSurface, tween(220), label = "choiceBg")
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, if (selected) SeaGlass else ChoiceLine, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            LucideIcon(icon, null, tint = ChoiceInk, size = 14.dp)
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = ChoiceInk, fontFamily = UiFamily, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.W600 else FontWeight.W500, maxLines = 1)
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        color = Fog,
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
