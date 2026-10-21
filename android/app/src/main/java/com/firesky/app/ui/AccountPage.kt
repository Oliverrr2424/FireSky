package com.firesky.app.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.firesky.app.R
import com.firesky.app.UiState
import com.firesky.app.data.AccountUser
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.SurfaceControl
import com.firesky.app.ui.theme.SurfaceHairline
import com.firesky.app.ui.theme.SurfaceInset
import com.firesky.app.ui.theme.SurfacePanel
import com.firesky.app.ui.theme.TextMuted
import com.firesky.app.ui.theme.TextPrimary
import com.firesky.app.ui.theme.TextSecondary
import com.firesky.app.ui.theme.UiFamily
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.res.stringResource
import com.firesky.app.data.Place
import com.firesky.app.data.formatMinutesOfDay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SettingsPatch(
    val notificationsEnabled: Boolean? = null,
    val alertThreshold: Int? = null,
    val alertLeadMinutes: Int? = null,
    val sunriseAlerts: Boolean? = null,
    val sunsetAlerts: Boolean? = null,
    val forecastImprovedAlerts: Boolean? = null,
    val postEventFeedbackAlerts: Boolean? = null,
    val quietHoursEnabled: Boolean? = null,
    val quietHoursRange: Pair<Int, Int>? = null,
    val togglePlaceAlert: Place? = null,
    val temperatureUnit: String? = null,
    val distanceUnit: String? = null,
    val timeFormat: String? = null,
    val languageTag: String? = null,
    val replayOnboarding: Boolean = false,
)

/** Same rule the server applies in functions/_shared/account.js normalEmail. */
private val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private const val MIN_PASSWORD_LENGTH = 12
private val ErrorText = Color(0xFFFFD2CF)

private fun isValidEmail(value: String): Boolean {
    val email = value.trim()
    return email.length <= 254 && EMAIL_PATTERN.matches(email)
}

@Composable
fun AccountPage(
    state: UiState,
    notificationsActive: Boolean,
    notificationsBlocked: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onAllowExactAlarms: (() -> Unit)?,
    onClose: () -> Unit,
    onSignInPassword: (String, String, String, String?) -> Unit,
    onGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onSaveProfile: (String) -> Unit,
    onChangePassword: (String, String, (String?) -> Unit) -> Unit,
    onDelete: ((String?) -> Unit) -> Unit,
    onSettingsChanged: (SettingsPatch) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 800.dp
        Box(
            // The status-bar inset sits outside the scroll so scrolled content never draws under the status icons.
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 1180.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                    .padding(
                        horizontal = if (compact) 14.dp else 32.dp,
                        vertical = if (compact) 16.dp else 32.dp,
                    ),
            ) {
                if (compact) {
                    AccountIntro(signedIn = state.account?.user != null, compact = true)
                    Spacer(Modifier.height(18.dp))
                    AccountContent(
                        state = state,
                        compact = true,
                        notificationsActive = notificationsActive,
                        notificationsBlocked = notificationsBlocked,
                        onOpenNotificationSettings = onOpenNotificationSettings,
                        onAllowExactAlarms = onAllowExactAlarms,
                        onSignInPassword = onSignInPassword,
                        onGoogle = onGoogle,
                        onSignOut = onSignOut,
                        onSaveProfile = onSaveProfile,
                        onChangePassword = onChangePassword,
                        onDelete = onDelete,
                        onSettingsChanged = onSettingsChanged,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(64.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        AccountIntro(signedIn = state.account?.user != null, compact = false, modifier = Modifier.weight(0.72f))
                        AccountContent(
                            state = state,
                            compact = false,
                            modifier = Modifier.weight(1.28f),
                            notificationsActive = notificationsActive,
                            notificationsBlocked = notificationsBlocked,
                            onOpenNotificationSettings = onOpenNotificationSettings,
                            onAllowExactAlarms = onAllowExactAlarms,
                            onSignInPassword = onSignInPassword,
                            onGoogle = onGoogle,
                            onSignOut = onSignOut,
                            onSaveProfile = onSaveProfile,
                            onChangePassword = onChangePassword,
                            onDelete = onDelete,
                            onSettingsChanged = onSettingsChanged,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountTopBar(compact: Boolean, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = if (compact) 48.dp else 56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clickable(interactionSource = interactionSource, indication = null, role = Role.Button, onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(SurfaceControl)
                    .indication(interactionSource, ripple()),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(R.drawable.ic_chevron_left, "Back to forecast", tint = TextPrimary, size = 22.dp)
            }
        }
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon(
                R.drawable.ic_sun_medium,
                null,
                tint = Color.White,
                size = if (compact) 19.dp else 22.dp,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "FireSky Now",
                color = Color.White,
                fontFamily = DisplayFamily,
                fontSize = (if (compact) 20 else 25).sp,
                fontWeight = FontWeight.W600,
                letterSpacing = (-0.5).sp,
            )
        }
        Spacer(Modifier.width(48.dp))
    }
}

@Composable
private fun AccountIntro(signedIn: Boolean, compact: Boolean, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            text = if (signedIn) stringResource(R.string.account_title_signed_in) else stringResource(R.string.account_title_guest),
            color = Color.White,
            fontFamily = DisplayFamily,
            fontSize = (if (compact) 30 else 44).sp,
            fontWeight = FontWeight.W700,
            lineHeight = (if (compact) 33 else 46).sp,
            letterSpacing = (-1.2).sp,
        )
        Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
        Text(
            text = if (signedIn) {
                "Keep your profile, forecast alerts and saved photography plans in sync."
            } else {
                "Save locations, camera viewpoints and forecast history across your devices."
            },
            color = TextSecondary,
            fontSize = (if (compact) 14 else 16).sp,
            lineHeight = (if (compact) 21 else 24).sp,
            modifier = Modifier.widthIn(max = 360.dp),
        )
    }
}

@Composable
private fun AccountContent(
    state: UiState,
    compact: Boolean,
    modifier: Modifier = Modifier,
    notificationsActive: Boolean,
    notificationsBlocked: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onAllowExactAlarms: (() -> Unit)?,
    onSignInPassword: (String, String, String, String?) -> Unit,
    onGoogle: () -> Unit,
    onSignOut: () -> Unit,
    onSaveProfile: (String) -> Unit,
    onChangePassword: (String, String, (String?) -> Unit) -> Unit,
    onDelete: ((String?) -> Unit) -> Unit,
    onSettingsChanged: (SettingsPatch) -> Unit,
) {
    val user = state.account?.user
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (user != null) {
            AccountIdentitySection(state, user, compact)
            ProfileSection(state, user, compact, onSaveProfile)
        } else {
            SignInSection(state, compact, onSignInPassword, onGoogle)
        }
        AlertsSection(
            state,
            compact,
            notificationsActive,
            notificationsBlocked,
            onOpenNotificationSettings,
            onAllowExactAlarms,
            onSettingsChanged,
        )
        UnitsAndLanguageSection(state, compact, onSettingsChanged)
        if (user != null) {
            SecuritySection(state, user, compact, onSignOut, onChangePassword, onDelete)
        }
    }
}

@Composable
private fun AccountSection(
    compact: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(if (compact) 22.dp else 28.dp)
    Column(
        modifier = modifier
            .shadow(14.dp, shape, ambientColor = Color(0x240B2645), spotColor = Color(0x240B2645))
            .clip(shape)
            .background(SurfacePanel)
            .drawBehind {
                drawRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0x1DFFFFFF), Color.Transparent),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
            }
            .padding(if (compact) 19.dp else 24.dp),
        content = content,
    )
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(
            title,
            color = TextPrimary,
            fontFamily = DisplayFamily,
            fontSize = 19.sp,
            fontWeight = FontWeight.W700,
            letterSpacing = (-0.3).sp,
        )
        Spacer(Modifier.height(4.dp))
        Text(subtitle, color = TextMuted, fontSize = 13.sp, lineHeight = 19.sp)
    }
}

@Composable
private fun AccountIdentitySection(state: UiState, user: AccountUser, compact: Boolean) {
    AccountSection(compact) {
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                IdentityRow(user)
                Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceHairline))
                SyncStatus(state, compact = true)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IdentityRow(user, modifier = Modifier.weight(1f))
                SyncStatus(state, compact = false)
            }
        }
    }
}

@Composable
private fun IdentityRow(user: AccountUser, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (user.avatarUrl != null) {
            AsyncImage(
                model = user.avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(52.dp).clip(CircleShape),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            )
        } else {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xCC11375B)),
                contentAlignment = Alignment.Center,
            ) {
                LucideIcon(R.drawable.ic_user_round, null, tint = Color.White, size = 22.dp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                user.displayName,
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.W600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                user.email ?: "Google account",
                color = TextMuted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SyncStatus(state: UiState, compact: Boolean) {
    val updated = state.lastSyncedAt?.let {
        "Updated " + DateTimeFormatter.ofPattern("HH:mm", Locale.US)
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(it))
    } ?: "Syncing settings..."
    Column(
        modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
        horizontalAlignment = if (compact) Alignment.Start else Alignment.End,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            LucideIcon(R.drawable.ic_check, null, tint = Color(0xFFC7F4D8), size = 15.dp)
            Text("Cloud sync active", color = Color(0xFFC7F4D8), fontSize = 12.sp)
        }
        Spacer(Modifier.height(2.dp))
        Text(updated, color = TextMuted, fontSize = 11.sp)
    }
}

@Composable
private fun ProfileSection(state: UiState, user: AccountUser, compact: Boolean, onSaveProfile: (String) -> Unit) {
    var profileName by remember(user.displayName) { mutableStateOf(user.displayName) }
    val saving = state.accountBusy
    AccountSection(compact) {
        SectionHeading("Profile", "How your name appears in FireSky.")
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            AccountField(
                label = "Display name",
                value = profileName,
                onValueChange = { profileName = it },
                placeholder = "Your name",
                contentType = ContentType.PersonFullName,
            )
            AccountPillButton(
                text = if (saving) "Saving..." else "Save profile",
                onClick = { onSaveProfile(profileName.trim()) },
                modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
                enabled = !saving && profileName.isNotBlank(),
            )
        }
    }
}

@Composable
private fun AlertsSection(
    state: UiState,
    compact: Boolean,
    notificationsActive: Boolean,
    notificationsBlocked: Boolean,
    onOpenNotificationSettings: () -> Unit,
    onAllowExactAlarms: (() -> Unit)?,
    onSettingsChanged: (SettingsPatch) -> Unit,
) {
    AccountSection(compact) {
        SectionHeading("Forecast alerts", "Choose when FireSky should notify you.")
        ToggleRow(
            label = "Enable notifications",
            icon = R.drawable.ic_bell,
            checked = notificationsActive,
            onCheckedChange = { onSettingsChanged(SettingsPatch(notificationsEnabled = it)) },
        )
        if (notificationsBlocked) {
            Spacer(Modifier.height(8.dp))
            InlineNotice(
                iconRes = R.drawable.ic_bell_off,
                text = "Android is blocking FireSky notifications, so alerts can't appear.",
                actionLabel = "Open settings",
                onAction = onOpenNotificationSettings,
                warning = true,
            )
        } else if (onAllowExactAlarms != null) {
            Spacer(Modifier.height(8.dp))
            ExactAlarmNotice(onAllowExactAlarms)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceHairline))
        Spacer(Modifier.height(16.dp))
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(13.dp)) {
                AccountSelect(
                    label = "Probability threshold",
                    valueLabel = "${state.alertThreshold}% or higher",
                    options = listOf(60 to "60% or higher", 70 to "70% or higher", 80 to "80% or higher"),
                    onSelect = { onSettingsChanged(SettingsPatch(alertThreshold = it)) },
                )
                AccountSelect(
                    label = "Alert lead time",
                    valueLabel = when (state.alertLeadMinutes) {
                        30 -> "30 minutes"
                        120 -> "2 hours"
                        else -> "1 hour"
                    },
                    options = listOf(30 to "30 minutes", 60 to "1 hour", 120 to "2 hours"),
                    onSelect = { onSettingsChanged(SettingsPatch(alertLeadMinutes = it)) },
                )
                ToggleRow("Sunrise alerts", null, state.sunriseAlerts) {
                    onSettingsChanged(SettingsPatch(sunriseAlerts = it))
                }
                ToggleRow("Sunset alerts", null, state.sunsetAlerts) {
                    onSettingsChanged(SettingsPatch(sunsetAlerts = it))
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    AccountSelect(
                        label = "Probability threshold",
                        valueLabel = "${state.alertThreshold}% or higher",
                        options = listOf(60 to "60% or higher", 70 to "70% or higher", 80 to "80% or higher"),
                        onSelect = { onSettingsChanged(SettingsPatch(alertThreshold = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    AccountSelect(
                        label = "Alert lead time",
                        valueLabel = when (state.alertLeadMinutes) {
                            30 -> "30 minutes"
                            120 -> "2 hours"
                            else -> "1 hour"
                        },
                        options = listOf(30 to "30 minutes", 60 to "1 hour", 120 to "2 hours"),
                        onSelect = { onSettingsChanged(SettingsPatch(alertLeadMinutes = it)) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ToggleRow("Sunrise alerts", null, state.sunriseAlerts, Modifier.weight(1f)) {
                        onSettingsChanged(SettingsPatch(sunriseAlerts = it))
                    }
                    ToggleRow("Sunset alerts", null, state.sunsetAlerts, Modifier.weight(1f)) {
                        onSettingsChanged(SettingsPatch(sunsetAlerts = it))
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceHairline))
        Spacer(Modifier.height(14.dp))

        // Alerts v2: Forecast improved, Post-event feedback, Quiet hours, and Per-place toggles
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ToggleRow(
                label = stringResource(R.string.alerts_v2_improved_label),
                icon = R.drawable.ic_sparkles,
                checked = state.forecastImprovedAlerts,
                onCheckedChange = { onSettingsChanged(SettingsPatch(forecastImprovedAlerts = it)) },
            )
            Text(stringResource(R.string.alerts_v2_improved_desc), color = TextMuted, fontSize = 12.sp)

            Spacer(Modifier.height(4.dp))
            ToggleRow(
                label = stringResource(R.string.alerts_v2_feedback_label),
                icon = R.drawable.ic_thumbs_up,
                checked = state.postEventFeedbackAlerts,
                onCheckedChange = { onSettingsChanged(SettingsPatch(postEventFeedbackAlerts = it)) },
            )
            Text(stringResource(R.string.alerts_v2_feedback_desc), color = TextMuted, fontSize = 12.sp)

            Spacer(Modifier.height(4.dp))
            ToggleRow(
                label = stringResource(R.string.alerts_v2_quiet_label),
                icon = R.drawable.ic_moon_star,
                checked = state.quietHoursEnabled,
                onCheckedChange = { onSettingsChanged(SettingsPatch(quietHoursEnabled = it)) },
            )
            if (state.quietHoursEnabled) {
                Text(
                    text = stringResource(
                        R.string.alerts_v2_quiet_range,
                        formatMinutesOfDay(state.quietHoursStart),
                        formatMinutesOfDay(state.quietHoursEnd),
                    ),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        (22 * 60 to 7 * 60) to "22:00 – 07:00",
                        (23 * 60 to 6 * 60 + 30) to "23:00 – 06:30",
                        (21 * 60 to 8 * 60) to "21:00 – 08:00",
                    ).forEach { (range, label) ->
                        val active = state.quietHoursStart == range.first && state.quietHoursEnd == range.second
                        Pill(
                            text = label,
                            selected = active,
                            onClick = { onSettingsChanged(SettingsPatch(quietHoursRange = range)) },
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceHairline))
        Spacer(Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.alerts_v2_places_title),
            color = TextPrimary,
            fontFamily = DisplayFamily,
            fontSize = 16.sp,
            fontWeight = FontWeight.W700,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = stringResource(R.string.alerts_v2_places_subtitle),
            color = TextMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        val allPlaces = remember(state.place, state.favorites) {
            buildList {
                add(state.place)
                state.favorites.forEach { fav ->
                    if (none { it.sameSpot(fav) }) add(fav)
                }
            }
        }
        allPlaces.forEach { placeItem ->
            val enabled = placeItem.spotKey() !in state.disabledAlertPlaceKeys
            val isPrimary = placeItem.sameSpot(state.place)
            ToggleRow(
                label = if (isPrimary) "${placeItem.name} (Primary)" else placeItem.name,
                icon = R.drawable.ic_map_pin,
                checked = enabled,
                onCheckedChange = { onSettingsChanged(SettingsPatch(togglePlaceAlert = placeItem)) },
            )
        }
    }
}

@Composable
private fun UnitsAndLanguageSection(
    state: UiState,
    compact: Boolean,
    onSettingsChanged: (SettingsPatch) -> Unit,
) {
    val prefs = state.unitPrefs
    AccountSection(compact) {
        SectionHeading(
            title = stringResource(R.string.units_section_title),
            subtitle = stringResource(R.string.units_section_subtitle),
        )
        UnitSegmentRow(
            label = stringResource(R.string.unit_temperature),
            options = listOf("C" to "°C Celsius", "F" to "°F Fahrenheit"),
            selected = prefs.temperatureUnit,
            onSelect = { onSettingsChanged(SettingsPatch(temperatureUnit = it)) },
        )
        Spacer(Modifier.height(12.dp))
        UnitSegmentRow(
            label = stringResource(R.string.unit_distance),
            options = listOf("km" to "km (Metric)", "mi" to "mi (Imperial)"),
            selected = prefs.distanceUnit,
            onSelect = { onSettingsChanged(SettingsPatch(distanceUnit = it)) },
        )
        Spacer(Modifier.height(12.dp))
        UnitSegmentRow(
            label = stringResource(R.string.unit_clock),
            options = listOf("system" to "Auto", "24h" to "24-hour", "12h" to "12-hour"),
            selected = prefs.timeFormat,
            onSelect = { onSettingsChanged(SettingsPatch(timeFormat = it)) },
        )
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.unit_language), color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.W600)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                "system" to "System",
                "en" to "English",
                "zh-Hans" to "简体中文",
                "fr-CA" to "Français (CA)",
                "es-MX" to "Español (MX)",
            ).forEach { (tag, label) ->
                Pill(
                    text = label,
                    selected = prefs.languageTag == tag,
                    onClick = { onSettingsChanged(SettingsPatch(languageTag = tag)) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        AccountPillButton(
            text = stringResource(R.string.replay_onboarding),
            icon = R.drawable.ic_sparkles,
            onClick = { onSettingsChanged(SettingsPatch(replayOnboarding = true)) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SecuritySection(
    state: UiState,
    user: AccountUser,
    compact: Boolean,
    onSignOut: () -> Unit,
    onChangePassword: (String, String, (String?) -> Unit) -> Unit,
    onDelete: ((String?) -> Unit) -> Unit,
) {
    var currentPassword by remember { mutableStateOf("") }
    var nextPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var changing by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }
    var noticeIsError by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val currentError = if (attempted && currentPassword.isEmpty()) "Enter your current password." else null
    val nextError = if (attempted && nextPassword.length < MIN_PASSWORD_LENGTH) {
        "Use at least $MIN_PASSWORD_LENGTH characters."
    } else {
        null
    }
    val confirmError = when {
        confirmPassword.isNotEmpty() && confirmPassword != nextPassword &&
            (attempted || confirmPassword.length >= nextPassword.length) -> "Passwords don't match."
        attempted && confirmPassword.isEmpty() -> "Re-enter the new password."
        else -> null
    }
    val busy = changing || state.accountBusy

    fun submitPasswordChange() {
        if (busy) return
        attempted = true
        notice = ""
        if (currentPassword.isEmpty() || nextPassword.length < MIN_PASSWORD_LENGTH || confirmPassword != nextPassword) return
        changing = true
        onChangePassword(currentPassword, nextPassword) { error ->
            changing = false
            if (error == null) {
                currentPassword = ""
                nextPassword = ""
                confirmPassword = ""
                attempted = false
                notice = "Password updated"
                noticeIsError = false
            } else {
                notice = error
                noticeIsError = true
            }
        }
    }

    AccountSection(compact) {
        SectionHeading("Sign-in and security", "Manage how you access your account.")
        if (user.hasPassword) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                AccountField(
                    label = "Current password",
                    value = currentPassword,
                    onValueChange = { currentPassword = it },
                    placeholder = "",
                    password = true,
                    contentType = ContentType.Password,
                    error = currentError,
                    imeAction = ImeAction.Next,
                )
                AccountField(
                    label = "New password",
                    value = nextPassword,
                    onValueChange = { nextPassword = it },
                    placeholder = "",
                    password = true,
                    helper = "Use at least $MIN_PASSWORD_LENGTH characters.",
                    contentType = ContentType.NewPassword,
                    error = nextError,
                    imeAction = ImeAction.Next,
                )
                AccountField(
                    label = "Confirm new password",
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    placeholder = "",
                    password = true,
                    contentType = ContentType.NewPassword,
                    error = confirmError,
                    imeAction = ImeAction.Done,
                    onImeAction = ::submitPasswordChange,
                )
                AccountPillButton(
                    text = if (changing) "Updating..." else "Update password",
                    onClick = ::submitPasswordChange,
                    modifier = if (compact) Modifier.fillMaxWidth() else Modifier,
                    enabled = !busy,
                )
            }
        } else {
            Text(
                "You signed in with Google. Password changes are managed by Google.",
                color = TextMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AccountPillButton("Sign out", R.drawable.ic_log_out, onSignOut, Modifier.fillMaxWidth())
                AccountPillButton(
                    "Delete account and cloud data",
                    null,
                    { confirmDelete = true },
                    Modifier.fillMaxWidth(),
                    destructive = true,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AccountPillButton("Sign out", R.drawable.ic_log_out, onSignOut)
                AccountPillButton("Delete account and cloud data", null, { confirmDelete = true }, destructive = true)
            }
        }
        if (notice.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            AccountNotice(notice, error = noticeIsError)
        }
        if (state.accountError.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            AccountNotice(state.accountError, error = true)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete FireSky account?") },
            text = { Text("This removes your cloud-synced locations, viewpoints and feedback. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete { error ->
                            if (error != null) {
                                notice = error
                                noticeIsError = true
                            }
                        }
                    },
                ) {
                    Text("Delete", color = ErrorText)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun SignInSection(
    state: UiState,
    compact: Boolean,
    onSignInPassword: (String, String, String, String?) -> Unit,
    onGoogle: () -> Unit,
) {
    var authMode by remember { mutableStateOf("login") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    var emailVisited by remember { mutableStateOf(false) }
    val signup = authMode == "signup"
    val busy = state.accountBusy

    val emailValid = isValidEmail(email)
    val emailError = when {
        email.isBlank() -> if (attempted) "Enter your email address." else null
        !emailValid && (attempted || emailVisited) -> "Enter a valid email address, like name@example.com."
        else -> null
    }
    val passwordError = when {
        !attempted -> null
        password.isEmpty() -> "Enter your password."
        password.length < MIN_PASSWORD_LENGTH ->
            if (signup) "Use at least $MIN_PASSWORD_LENGTH characters." else "FireSky passwords have at least $MIN_PASSWORD_LENGTH characters."
        else -> null
    }

    fun submit() {
        if (busy) return
        attempted = true
        if (!emailValid || password.length < MIN_PASSWORD_LENGTH) return
        onSignInPassword(authMode, email.trim(), password, displayName.trim().takeIf { it.isNotBlank() })
    }

    AccountSection(compact, Modifier.widthIn(max = 570.dp)) {
        AccountPillButton(
            text = "Continue with Google",
            icon = R.drawable.ic_log_in,
            onClick = onGoogle,
            modifier = Modifier.fillMaxWidth(),
            google = true,
            enabled = !busy,
        )
        Spacer(Modifier.height(22.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.weight(1f).height(1.dp).background(SurfaceHairline))
            Text("or use email", color = TextMuted, fontSize = 12.sp)
            Box(Modifier.weight(1f).height(1.dp).background(SurfaceHairline))
        }
        Spacer(Modifier.height(22.dp))
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (signup) {
                AccountField(
                    label = "Display name",
                    value = displayName,
                    onValueChange = { displayName = it },
                    placeholder = "",
                    contentType = ContentType.PersonFullName,
                    imeAction = ImeAction.Next,
                )
            }
            AccountField(
                label = "Email",
                value = email,
                onValueChange = { email = it },
                placeholder = "",
                keyboardType = KeyboardType.Email,
                contentType = if (signup) ContentType.NewUsername + ContentType.EmailAddress else ContentType.Username + ContentType.EmailAddress,
                error = emailError,
                imeAction = ImeAction.Next,
                onFocusLost = { emailVisited = true },
            )
            AccountField(
                label = "Password",
                value = password,
                onValueChange = { password = it },
                placeholder = "",
                password = true,
                helper = if (signup) "Use at least $MIN_PASSWORD_LENGTH characters." else "Enter your FireSky password.",
                contentType = if (signup) ContentType.NewPassword else ContentType.Password,
                error = passwordError,
                imeAction = ImeAction.Done,
                onImeAction = ::submit,
            )
            AccountPillButton(
                text = when {
                    busy -> "Please wait..."
                    signup -> "Create account"
                    else -> "Sign in with email"
                },
                onClick = ::submit,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) {
                    authMode = if (signup) "login" else "signup"
                    attempted = false
                }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (signup) "Already have an account? Sign in" else "New to FireSky? Create an account",
                color = TextSecondary,
                fontSize = 13.sp,
            )
        }
        if (state.accountError.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            AccountNotice(state.accountError, error = true)
        }
    }
}

@Composable
private fun AccountField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = false,
    helper: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    contentType: ContentType? = null,
    error: String? = null,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: (() -> Unit)? = null,
    onFocusLost: (() -> Unit)? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    var hadFocus by remember { mutableStateOf(false) }
    val borderColor = if (error != null) Color(0xB3FF9D96) else Color(0x1FFFFFFF)
    Column {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(SurfaceControl)
                .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                .padding(start = 14.dp, end = if (password) 0.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 12.dp)
                    .onFocusChanged { focus ->
                        if (focus.isFocused) {
                            hadFocus = true
                        } else if (hadFocus) {
                            onFocusLost?.invoke()
                        }
                    }
                    .semantics {
                        if (contentType != null) this.contentType = contentType
                        if (error != null) this.error(error)
                    },
                singleLine = true,
                textStyle = TextStyle(fontFamily = UiFamily, fontSize = 15.sp, color = TextPrimary),
                cursorBrush = SolidColor(Color.White),
                visualTransformation = if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else keyboardType,
                    imeAction = imeAction,
                    autoCorrectEnabled = !(password || keyboardType == KeyboardType.Email),
                ),
                keyboardActions = KeyboardActions(onDone = { onImeAction?.invoke() }),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = TextMuted, fontSize = 15.sp, maxLines = 1)
                    }
                    inner()
                },
            )
            if (password) {
                ControlIconButton(
                    painter = androidx.compose.ui.res.painterResource(if (revealed) R.drawable.ic_eye_off else R.drawable.ic_eye),
                    contentDescription = if (revealed) "Hide password" else "Show password",
                    onClick = { revealed = !revealed },
                    size = 34.dp,
                    iconSize = 17.dp,
                )
            }
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(error, color = ErrorText, fontSize = 12.sp, lineHeight = 16.sp)
        } else if (helper != null) {
            Spacer(Modifier.height(6.dp))
            Text(helper, color = TextMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AccountSelect(
    label: String,
    valueLabel: String,
    options: List<Pair<Int, String>>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, color = TextSecondary, fontSize = 13.sp)
        Spacer(Modifier.height(7.dp))
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SurfaceControl)
                    .border(1.dp, Color(0x1FFFFFFF), RoundedCornerShape(16.dp))
                    .clickable(role = Role.DropdownList, onClickLabel = "Change $label") { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(valueLabel, color = TextPrimary, fontSize = 15.sp, modifier = Modifier.weight(1f))
                LucideIcon(R.drawable.ic_chevron_down, null, tint = TextSecondary, size = 16.dp)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (value, text) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            onSelect(value)
                            expanded = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    icon: Int?,
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .semantics { stateDescription = if (checked) "On" else "Off" }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) {
            LucideIcon(icon, null, tint = TextPrimary, size = 17.dp)
        }
        Text(
            label,
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.W600,
            modifier = Modifier.weight(1f),
        )
        AccountSwitch(checked)
    }
}

@Composable
private fun AccountSwitch(checked: Boolean) {
    val knobOffset by animateDpAsState(if (checked) 16.dp else 0.dp, label = "accountSwitch")
    Box(
        modifier = Modifier
            .width(42.dp)
            .height(26.dp)
            .clip(CircleShape)
            .background(if (checked) Color(0xB875BEE8) else Color(0x5708233F))
            .padding(3.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color(0xE0FFFFFF)),
        )
    }
}

@Composable
private fun AccountPillButton(
    text: String,
    icon: Int? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
    google: Boolean = false,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.55f)
            .clip(CircleShape)
            .background(
                when {
                    destructive -> Color(0x40752834)
                    google -> Color(0x2EFFFFFF)
                    else -> SurfaceInset
                },
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            LucideIcon(
                icon,
                null,
                tint = if (destructive) ErrorText else TextPrimary,
                size = 17.dp,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text,
            color = if (destructive) ErrorText else TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.W600,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AccountNotice(text: String, error: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(if (error) Color(0x40752834) else Color(0x381F704A))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(
            text,
            color = if (error) ErrorText else Color(0xFFC7F4D8),
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )
    }
}
