package com.firesky.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.firesky.app.FireSkyViewModel
import com.firesky.app.R
import com.firesky.app.data.PRESET_PLACES
import com.firesky.app.data.Place
import com.firesky.app.UiState
import com.firesky.app.ui.map.HeatMapCard
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.SurfaceControl
import com.firesky.app.ui.theme.SurfaceInset
import com.firesky.app.ui.theme.TextMuted
import com.firesky.app.ui.theme.TextPrimary
import com.firesky.app.ui.theme.TextSecondary
import com.firesky.app.ui.theme.UiFamily
import kotlinx.coroutines.launch

@Composable
fun FireSkyApp(viewModel: FireSkyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var locationBusy by remember { mutableStateOf(false) }
    var notificationsAllowed by remember { mutableStateOf(areNotificationsAllowed(context)) }
    var exactAlarmsAllowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        notificationsAllowed = areNotificationsAllowed(context)
        exactAlarmsAllowed = canScheduleExactAlarms(context)
    }
    // The stored preference only counts as "on" when Android will actually show the alert.
    val notificationsActive = state.notificationsEnabled && notificationsAllowed
    // Without "Alarms & reminders" access Android may deliver an alert up to an hour late.
    val onAllowExactAlarms: (() -> Unit)? = if (notificationsActive && !exactAlarmsAllowed) {
        { runCatching { context.startActivity(exactAlarmSettingsIntent(context)) } }
    } else {
        null
    }

    BackHandler(enabled = state.showAccount) { viewModel.setAccountVisible(false) }
    BackHandler(enabled = !state.showAccount && state.showSearch) { viewModel.setSearchVisible(false) }

    fun showMessage(message: String, settingsIntent: Intent? = null) {
        scope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = if (settingsIntent != null) "Open settings" else null,
                duration = if (settingsIntent != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed && settingsIntent != null) {
                runCatching { context.startActivity(settingsIntent) }
            }
        }
    }

    fun locateWithPermission() {
        if (locationBusy) return
        scope.launch {
            locationBusy = true
            try {
                val location = LocationHelper.getCurrentLocation(context)
                when {
                    location != null -> viewModel.selectPlace(LocationHelper.placeFor(context, location))
                    !LocationHelper.isLocationEnabled(context) -> showMessage(
                        "Location is turned off. Turn it on or search for a city.",
                        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                    )
                    else -> showMessage("Couldn't get your location. Try again or search for a city.")
                }
            } finally {
                locationBusy = false
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            locateWithPermission()
        } else if (canAskAgain(context, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) {
            showMessage("FireSky needs location access to forecast where you are. You can also search for a city.")
        } else {
            showMessage(
                "Location access is blocked for FireSky. Allow it in Settings or search for a city.",
                appDetailsSettingsIntent(context),
            )
        }
    }

    fun promptExactAlarmsIfNeeded() {
        exactAlarmsAllowed = canScheduleExactAlarms(context)
        if (!exactAlarmsAllowed) {
            showMessage(
                "Alerts may arrive up to an hour late. Allow on-time alerts in Alarms & reminders.",
                exactAlarmSettingsIntent(context),
            )
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        notificationsAllowed = areNotificationsAllowed(context)
        if (granted) {
            promptExactAlarmsIfNeeded()
        } else {
            showMessage(
                if (canAskAgain(context, Manifest.permission.POST_NOTIFICATIONS)) {
                    "Notifications are off, so FireSky can't send sky alerts."
                } else {
                    "Notifications are blocked for FireSky. Turn them on in Settings to get sky alerts."
                },
                notificationSettingsIntent(context),
            )
        }
    }

    fun locate() {
        if (LocationHelper.hasPermission(context)) {
            locateWithPermission()
        } else {
            locationPermissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    fun setNotifications(enable: Boolean) {
        if (!enable) {
            if (state.notificationsEnabled) viewModel.toggleNotifications()
            return
        }
        if (!state.notificationsEnabled) viewModel.toggleNotifications()
        notificationsAllowed = areNotificationsAllowed(context)
        if (notificationsAllowed) {
            promptExactAlarmsIfNeeded()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Below Android 13, or the app's notifications were switched off in system settings.
            showMessage(
                "Notifications are turned off for FireSky. Turn them on in Settings to get sky alerts.",
                notificationSettingsIntent(context),
            )
        }
    }

    fun share() {
        val text = viewModel.shareText()
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, "FireSky forecast"))
    }

    Box(Modifier.fillMaxSize()) {
        WeatherBackdrop(theme = state.theme, isNight = state.isNight)
        if (state.showAccount) {
            AccountPage(
                state = state,
                notificationsActive = notificationsActive,
                notificationsBlocked = state.notificationsEnabled && !notificationsAllowed,
                onOpenNotificationSettings = {
                    runCatching { context.startActivity(notificationSettingsIntent(context)) }
                },
                onAllowExactAlarms = onAllowExactAlarms,
                onClose ={ viewModel.setAccountVisible(false) },
                onSignInPassword = { action, email, password, name ->
                    viewModel.signInWithPassword(action, email, password, name)
                },
                onGoogle = {
                    viewModel.startGoogleSignIn { url ->
                        runCatching {
                            androidx.browser.customtabs.CustomTabsIntent.Builder().build()
                                .launchUrl(context, Uri.parse(url))
                        }.onFailure {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        }
                    }
                },
                onSignOut = { viewModel.signOut() },
                onSaveProfile = { viewModel.saveProfile(it) },
                onChangePassword = { current, next, done -> viewModel.changePassword(current, next, done) },
                onDelete = { done -> viewModel.deleteAccount(done) },
                onSettingsChanged = { patch ->
                    patch.notificationsEnabled?.let { setNotifications(it) }
                    patch.alertThreshold?.let { viewModel.setAlertThreshold(it) }
                    patch.alertLeadMinutes?.let { viewModel.setAlertLeadMinutes(it) }
                    patch.sunriseAlerts?.let { viewModel.setSunriseAlerts(it) }
                    patch.sunsetAlerts?.let { viewModel.setSunsetAlerts(it) }
                },
            )
        } else {
            HomeContent(
                state = state,
                locationBusy = locationBusy,
                notificationsActive = notificationsActive,
                onAllowExactAlarms = onAllowExactAlarms,
                onToggleSearch = { viewModel.setSearchVisible(!state.showSearch) },
                onSelectPlace = { viewModel.selectPlace(it) },
                onSelectMode = { viewModel.selectMode(it) },
                onToggleFavorite = { viewModel.toggleFavorite() },
                onLocate = ::locate,
                onRefresh = { viewModel.refresh() },
                onToggleNotifications = { setNotifications(!notificationsActive) },
                onShare = ::share,
                onSearchQuery = { viewModel.updateSearchQuery(it) },
                onSearchSubmit = { viewModel.submitSearch() },
                onSelectDay = { viewModel.selectDay(it) },
                onSampleSelected = { viewModel.selectMapSample(it) },
                onSaveViewpoint = { name -> viewModel.saveViewpoint(name) },
                onOpenViewpoint = { viewModel.openViewpoint(it) },
                onToggleReaction = { viewModel.toggleReaction(it) },
                onFeedbackEditing = { viewModel.setFeedbackEditing(it) },
                onAlertLead = { viewModel.setAlertLeadMinutes(it) },
                onAccount = { viewModel.setAccountVisible(true) },
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .widthIn(max = 560.dp),
        ) { data ->
            Snackbar(
                snackbarData = data,
                shape = RoundedCornerShape(18.dp),
                containerColor = Color(0xF2173A5E),
                contentColor = TextPrimary,
                actionColor = Color(0xFFFFD76A),
            )
        }
    }
}

private fun areNotificationsAllowed(context: Context): Boolean {
    val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

/** False once a permission is denied for good: the system will not show its dialog again. */
private fun canAskAgain(context: Context, vararg permissions: String): Boolean {
    val activity = context.findActivity() ?: return false
    return permissions.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun appDetailsSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

private fun canScheduleExactAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() != false

/** Only used on Android 12+, where [canScheduleExactAlarms] can be false. */
private fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))

private fun notificationSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        appDetailsSettingsIntent(context)
    }

@Composable
private fun HomeContent(
    state: UiState,
    locationBusy: Boolean,
    notificationsActive: Boolean,
    onAllowExactAlarms: (() -> Unit)?,
    onToggleSearch: () -> Unit,
    onSelectPlace: (Place) -> Unit,
    onSelectMode: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onLocate: () -> Unit,
    onRefresh: () -> Unit,
    onToggleNotifications: () -> Unit,
    onShare: () -> Unit,
    onSearchQuery: (String) -> Unit,
    onSearchSubmit: () -> Unit,
    onSelectDay: (Int) -> Unit,
    onSampleSelected: (com.firesky.app.data.ScoredPoint?) -> Unit,
    onSaveViewpoint: (String) -> Unit,
    onOpenViewpoint: (com.firesky.app.data.Viewpoint) -> Unit,
    onToggleReaction: (String) -> Unit,
    onFeedbackEditing: (Boolean) -> Unit,
    onAlertLead: (Int) -> Unit,
    onAccount: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 720.dp
        val wide = maxWidth >= 1180.dp
        val horizontalPadding = if (compact) 14.dp else 28.dp

        Column(
            modifier = Modifier
                .fillMaxSize()
                // The status-bar inset sits outside the scroll so scrolled content is clipped below the
                // status icons instead of drawing under them.
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = if (compact) 16.dp else 28.dp,
                    bottom = if (compact) 24.dp else 36.dp,
                ),
        ) {
            TopBar(state = state, compact = compact, onAccount = onAccount)
            Spacer(Modifier.height(if (compact) 18.dp else 28.dp))
            LocationRow(
                state = state,
                compact = compact,
                locationBusy = locationBusy,
                notificationsActive = notificationsActive,
                onToggleSearch = onToggleSearch,
                onSelectMode = onSelectMode,
                onToggleFavorite = onToggleFavorite,
                onLocate = onLocate,
                onRefresh = onRefresh,
                onToggleNotifications = onToggleNotifications,
                onShare = onShare,
            )
            Spacer(Modifier.height(if (compact) 5.dp else 9.dp))
            SavedPlacesBar(
                favorites = state.favorites,
                place = state.place,
                compact = compact,
                onSelect = onSelectPlace,
            )
            if (state.showSearch) {
                Spacer(Modifier.height(if (compact) 4.dp else 10.dp))
                SearchPanel(
                    state = state,
                    compact = compact,
                    onQuery = onSearchQuery,
                    onSubmit = onSearchSubmit,
                    onSelect = onSelectPlace,
                )
            }

            val outlook = state.selectedOutlook
            // Nothing cached and the load failed (for example the first launch while offline).
            val coldStartError = !state.loading && outlook == null && state.error.isNotBlank()

            if (state.offline) {
                Spacer(Modifier.height(10.dp))
                NoticeCard(R.drawable.ic_triangle_alert, "You are offline. Cached forecasts are shown when available.", warning = true)
            }
            if (state.error.isNotBlank() && !coldStartError) {
                Spacer(Modifier.height(10.dp))
                NoticeCard(R.drawable.ic_triangle_alert, state.error)
            }

            Spacer(Modifier.height(if (compact) 10.dp else 14.dp))

            if (coldStartError) {
                ErrorState(message = state.error, offline = state.offline, onRetry = onRefresh)
            } else if (state.loading || outlook == null) {
                LoadingState()
            } else {
                val active = outlook.scores[state.activeMode]
                val activeWindow = outlook.windows[state.activeMode]
                val appearance = outlook.appearanceWindow[state.activeMode]

                if (compact) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeatMapCard(
                            place = state.place,
                            samples = state.grid,
                            type = "probability",
                            mode = state.activeMode,
                            compact = compact,
                            onSampleSelected = onSampleSelected,
                        )
                        HeatMapCard(
                            place = state.place,
                            samples = state.grid,
                            type = "quality",
                            mode = state.activeMode,
                            compact = compact,
                            onSampleSelected = onSampleSelected,
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HeatMapCard(
                            place = state.place,
                            samples = state.grid,
                            type = "probability",
                            mode = state.activeMode,
                            compact = false,
                            modifier = Modifier.weight(1f),
                            onSampleSelected = onSampleSelected,
                        )
                        HeatMapCard(
                            place = state.place,
                            samples = state.grid,
                            type = "quality",
                            mode = state.activeMode,
                            compact = false,
                            modifier = Modifier.weight(1f),
                            onSampleSelected = onSampleSelected,
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        HeroCard(state = state, outlook = outlook, modifier = Modifier.weight(1f))
                        AstroCard(outlook = outlook, activeMode = state.activeMode, compact = false, modifier = Modifier.weight(1f))
                    }
                } else {
                    HeroCard(state = state, outlook = outlook)
                    Spacer(Modifier.height(12.dp))
                    AstroCard(outlook = outlook, activeMode = state.activeMode, compact = true)
                }

                Spacer(Modifier.height(12.dp))
                ForecastStrip(
                    days = state.days,
                    activeMode = state.activeMode,
                    timeZone = state.data?.timeZone ?: outlook.timeZone,
                    selectedDayIndex = state.selectedDayIndex,
                    compact = compact,
                    onSelect = onSelectDay,
                )

                Spacer(Modifier.height(12.dp))
                MetricRail(activeMode = state.activeMode, data = outlook, columns = if (compact) 2 else if (wide) 6 else 3)

                Spacer(Modifier.height(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    WindowForecastCard(
                        outlook = outlook,
                        activeMode = state.activeMode,
                        active = active,
                        activeWindow = activeWindow,
                        appearance = appearance,
                        air = state.activeAir,
                    )
                    TimelineCard(
                        state = state,
                        outlook = outlook,
                        activeWindow = activeWindow,
                        onSaveViewpoint = onSaveViewpoint,
                        onOpenViewpoint = onOpenViewpoint,
                    )
                    FeedbackCard(
                        state = state,
                        outlook = outlook,
                        onToggleReaction = onToggleReaction,
                        onEditing = onFeedbackEditing,
                        onAlertLead = onAlertLead,
                        onAllowExactAlarms = onAllowExactAlarms,
                    )
                }

                Spacer(Modifier.height(18.dp))
                SourceLine()
            }
        }
    }
}

@Composable
private fun TopBar(state: UiState, compact: Boolean, onAccount: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LucideIcon(R.drawable.ic_sun_medium, null, tint = Color.White, size = 22.dp)
            Text(
                text = "FireSky Now",
                color = Color.White,
                fontFamily = DisplayFamily,
                fontSize = (if (compact) 19 else 25).sp,
                fontWeight = FontWeight.W600,
                letterSpacing = (-0.5).sp,
            )
        }
        // The 38dp avatar keeps its layout slot; the 48dp touch target overflows it evenly.
        Box(Modifier.size(38.dp), contentAlignment = Alignment.Center) {
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .requiredSize(48.dp)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        role = Role.Button,
                        onClickLabel = "Open account and alerts",
                        onClick = onAccount,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(SurfaceControl)
                        .indication(interactionSource, ripple()),
                    contentAlignment = Alignment.Center,
                ) {
                    val avatar = state.account?.user?.avatarUrl
                    if (avatar != null) {
                        AsyncImage(
                            model = avatar,
                            contentDescription = "Account and alerts",
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_user_round),
                            contentDescription = "Account and alerts",
                            tint = TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationRow(
    state: UiState,
    compact: Boolean,
    locationBusy: Boolean,
    notificationsActive: Boolean,
    onToggleSearch: () -> Unit,
    onSelectMode: (String) -> Unit,
    onToggleFavorite: () -> Unit,
    onLocate: () -> Unit,
    onRefresh: () -> Unit,
    onToggleNotifications: () -> Unit,
    onShare: () -> Unit,
) {
    val isFavorite = state.favorites.any { it.sameSpot(state.place) }
    if (compact) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = if (state.showSearch) "Close search" else "Search locations", onClick = onToggleSearch),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.place.name,
                    color = Color.White,
                    fontFamily = DisplayFamily,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.W700,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    painter = painterResource(if (state.showSearch) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            // The mode/action row is 48dp tall with a 38dp visible track, so 5dp of the gap lives inside it.
            Spacer(Modifier.height(5.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ModeToggle(activeMode = state.activeMode, onSelect = onSelectMode, modifier = Modifier.weight(1f))
                QuickActions(
                    // Shift by the touch-target margin so the last circle lines up with the avatar above.
                    modifier = Modifier.offset(x = 7.dp),
                    favorite = isFavorite,
                    notifications = notificationsActive,
                    locationBusy = locationBusy,
                    onFavorite = onToggleFavorite,
                    onLocate = onLocate,
                    onRefresh = onRefresh,
                    onNotifications = onToggleNotifications,
                    onShare = onShare,
                )
            }
        }
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier
                    .clickable(onClickLabel = if (state.showSearch) "Close search" else "Search locations", onClick = onToggleSearch),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = state.place.name,
                    color = Color.White,
                    fontFamily = DisplayFamily,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.W700,
                    letterSpacing = (-0.8).sp,
                    maxLines = 1,
                )
                if (state.place.admin1.isNotBlank()) {
                    Text(" · ", color = TextSecondary, fontSize = 22.sp)
                    Text(
                        state.place.admin1,
                        color = TextSecondary,
                        fontFamily = DisplayFamily,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.W600,
                        maxLines = 1,
                    )
                }
                Icon(
                    painter = painterResource(if (state.showSearch) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            ModeToggle(activeMode = state.activeMode, onSelect = onSelectMode, modifier = Modifier.width(320.dp))
            QuickActions(
                favorite = isFavorite,
                notifications = notificationsActive,
                locationBusy = locationBusy,
                onFavorite = onToggleFavorite,
                onLocate = onLocate,
                onRefresh = onRefresh,
                onNotifications = onToggleNotifications,
                onShare = onShare,
            )
        }
    }
}

@Composable
private fun ModeToggle(activeMode: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    // A 48dp tall row of tabs; the visible 38dp track is drawn inset so the touch targets can be full height.
    Row(
        modifier = modifier
            .height(48.dp)
            .drawBehind {
                val inset = 5.dp.toPx()
                val trackHeight = size.height - inset * 2
                drawRoundRect(
                    color = Color(0x33082745),
                    topLeft = Offset(0f, inset),
                    size = Size(size.width, trackHeight),
                    cornerRadius = CornerRadius(trackHeight / 2),
                )
            }
            .padding(horizontal = 3.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ModeButton("Sunrise", activeMode == "sunrise", Modifier.weight(1f)) { onSelect("sunrise") }
        ModeButton("Sunset", activeMode == "sunset", Modifier.weight(1f)) { onSelect("sunset") }
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxHeight()
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 8.dp)
            .clip(CircleShape)
            .background(if (selected) Color(0x4DFFFFFF) else Color.Transparent)
            .indication(interactionSource, ripple()),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color.White else TextSecondary,
            fontFamily = DisplayFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.W600,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun QuickActions(
    modifier: Modifier = Modifier,
    favorite: Boolean,
    notifications: Boolean,
    locationBusy: Boolean,
    onFavorite: () -> Unit,
    onLocate: () -> Unit,
    onRefresh: () -> Unit,
    onNotifications: () -> Unit,
    onShare: () -> Unit,
) {
    // Each button reserves a 48dp touch target around its 34dp circle, so no extra spacing is needed.
    Row(modifier) {
        ControlIconButton(
            painter = painterResource(R.drawable.ic_star),
            contentDescription = "Saved location",
            onClick = onFavorite,
            selected = favorite,
            checked = favorite,
            size = 34.dp,
        )
        ControlIconButton(
            painter = painterResource(if (locationBusy) R.drawable.ic_loader_circle else R.drawable.ic_locate_fixed),
            contentDescription = if (locationBusy) "Finding your location" else "Use my location",
            onClick = onLocate,
            size = 34.dp,
        )
        ControlIconButton(
            painter = painterResource(R.drawable.ic_refresh_cw),
            contentDescription = "Refresh",
            onClick = onRefresh,
            size = 34.dp,
        )
        ControlIconButton(
            painter = painterResource(if (notifications) R.drawable.ic_bell else R.drawable.ic_bell_off),
            contentDescription = "Forecast alerts",
            onClick = onNotifications,
            selected = notifications,
            checked = notifications,
            size = 34.dp,
        )
        ControlIconButton(
            painter = painterResource(R.drawable.ic_share_2),
            contentDescription = "Share forecast",
            onClick = onShare,
            size = 34.dp,
        )
    }
}

@Composable
private fun SavedPlacesBar(
    favorites: List<Place>,
    place: Place,
    compact: Boolean,
    onSelect: (Place) -> Unit,
) {
    if (favorites.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 12.dp),
    ) {
        if (!compact) {
            Text("Saved places", color = TextMuted, fontSize = 14.sp)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 9.dp else 12.dp),
        ) {
            favorites.forEach { favorite ->
                Pill(
                    text = favorite.name,
                    selected = favorite.sameSpot(place),
                    onClick = { onSelect(favorite) },
                )
            }
        }
    }
}

@Composable
private fun SearchPanel(
    state: UiState,
    compact: Boolean,
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelect: (Place) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clip(CircleShape)
                .background(SurfaceControl)
                .padding(start = 14.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LucideIcon(R.drawable.ic_search, null, tint = TextSecondary, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = state.search.query,
                onValueChange = onQuery,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = TextStyle(fontFamily = UiFamily, fontSize = 15.sp, color = Color.White),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                decorationBox = { inner ->
                    if (state.search.query.isEmpty()) {
                        Text("Search North American cities", color = TextSecondary, fontSize = 15.sp, maxLines = 1)
                    }
                    inner()
                },
            )
            Box(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .clip(CircleShape)
                    .background(SurfaceInset)
                    .clickable(onClick = onSubmit)
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.search.searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                } else {
                    Text("Search", color = TextPrimary, fontSize = 13.sp)
                }
            }
        }
        Spacer(Modifier.height(7.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PRESET_PLACES.forEach { preset ->
                Text(
                    text = preset.name,
                    color = TextSecondary,
                    fontSize = if (compact) 11.sp else 13.sp,
                    modifier = Modifier.clickable { onSelect(preset) },
                )
            }
        }
        if (state.search.error.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(state.search.error, color = TextSecondary, fontSize = 12.sp)
        }
        if (state.search.results.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.search.results.forEach { result ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(SurfaceInset)
                            .clickable { onSelect(result) }
                            .padding(horizontal = 12.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LucideIcon(R.drawable.ic_map_pin, null, tint = TextSecondary, size = 16.dp)
                        Text(
                            text = "${result.name}${if (result.admin1.isNotBlank()) ", ${result.admin1}" else ""}",
                            color = TextPrimary,
                            fontSize = 13.sp,
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
private fun LoadingState() {
    Column(
        modifier = Modifier.fillMaxWidth().height(420.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Loading today's cloud field, air quality, and solar windows...",
            color = Color(0xB8FFFFFF),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ErrorState(message: String, offline: Boolean, onRetry: () -> Unit) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 36.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LucideIcon(R.drawable.ic_cloud_rain, null, tint = TextPrimary, size = 30.dp)
            Spacer(Modifier.height(14.dp))
            Text(
                "Couldn't load the forecast",
                color = TextPrimary,
                fontFamily = DisplayFamily,
                fontSize = 19.sp,
                fontWeight = FontWeight.W700,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (offline) "You're offline and there's no saved forecast for this place yet. Connect and try again." else message,
                color = TextSecondary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            val interactionSource = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(Color(0x2EFFFFFF))
                    .clickable(interactionSource = interactionSource, indication = ripple(), role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LucideIcon(R.drawable.ic_refresh_cw, null, tint = TextPrimary, size = 17.dp)
                Text("Retry", color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.W600)
            }
        }
    }
}

@Composable
private fun SourceLine() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LucideIcon(R.drawable.ic_check, null, tint = TextMuted, size = 14.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            text = "WEATHER AND AIR QUALITY FORECAST · 7-DAY OUTLOOK",
            color = TextMuted,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
    }
}
