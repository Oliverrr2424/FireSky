package com.firesky.app.ui

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ClipData
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
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import coil.compose.AsyncImage
import com.firesky.app.BuildConfig
import com.firesky.app.FireSkyViewModel
import com.firesky.app.R
import com.firesky.app.UiState
import com.firesky.app.data.Place
import com.firesky.app.ui.community.CommunityScreen
import com.firesky.app.ui.icons.rememberIconPulse
import com.firesky.app.ui.icons.tapPulse
import com.firesky.app.ui.map.ChaseMapScreen
import com.firesky.app.ui.map.HeatMapCard
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.SurfaceControl
import com.firesky.app.ui.theme.SurfaceHairline
import com.firesky.app.ui.theme.SurfaceInset
import com.firesky.app.ui.theme.SurfacePanel
import com.firesky.app.ui.theme.TextMuted
import com.firesky.app.ui.theme.TextPrimary
import com.firesky.app.ui.theme.TextSecondary
import com.firesky.app.ui.theme.UiFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun FireSkyApp(viewModel: FireSkyViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LocalizedAppContainer(languageTag = state.unitPrefs.languageTag) {
        FireSkyAppContent(viewModel = viewModel, state = state)
    }
}

@Composable
private fun FireSkyAppContent(
    viewModel: FireSkyViewModel,
    state: UiState,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: "home"
    DisposableEffect(currentRoute, state.onboardingCompleted, context) {
        val window = context.findActivity()?.window
        window?.let {
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = state.onboardingCompleted && currentRoute == "map"
                // The bottom bar now sits on the dark weather backdrop, so the gesture pill stays light.
                isAppearanceLightNavigationBars = false
            }
        }
        onDispose { }
    }


    var locationBusy by remember { mutableStateOf(false) }
    var locationGranted by remember { mutableStateOf(LocationHelper.hasPermission(context)) }
    var notificationsAllowed by remember { mutableStateOf(areNotificationsAllowed(context)) }
    var exactAlarmsAllowed by remember { mutableStateOf(canScheduleExactAlarms(context)) }
    var pendingOnboardingNotificationCallback by remember { mutableStateOf<(() -> Unit)?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        locationGranted = LocationHelper.hasPermission(context)
        notificationsAllowed = areNotificationsAllowed(context)
        exactAlarmsAllowed = canScheduleExactAlarms(context)
    }

    val notificationsActive = state.notificationsEnabled && notificationsAllowed
    val onAllowExactAlarms: (() -> Unit)? = if (notificationsActive && !exactAlarmsAllowed) {
        { runCatching { context.startActivity(exactAlarmSettingsIntent(context)) } }
    } else {
        null
    }

    fun navigateToTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Deep-link OAuth callbacks set state.showAccount = true; route to the Account tab.
    LaunchedEffect(state.showAccount) {
        if (state.showAccount) {
            navigateToTab("account")
            viewModel.setAccountVisible(false)
        }
    }

    BackHandler(enabled = currentRoute == "home" && state.showSearch) {
        viewModel.setSearchVisible(false)
    }

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
                    location != null -> {
                        locationGranted = true
                        viewModel.selectPlace(LocationHelper.placeFor(context, location), source = "location")
                    }
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
            locationGranted = true
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
        val callback = pendingOnboardingNotificationCallback
        pendingOnboardingNotificationCallback = null
        if (callback != null) {
            callback()
        } else if (granted) {
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
            showMessage(
                "Notifications are turned off for FireSky. Turn them on in Settings to get sky alerts.",
                notificationSettingsIntent(context),
            )
        }
    }

    // Shares a picture of the forecast plus a link that opens the same place, so the people it
    // reaches can use FireSky straight away. If the picture can't be made, the text and link still go.
    fun share() {
        val content = viewModel.shareContent() ?: return
        scope.launch {
            val eventLabel = resources.getString(
                if (content.mode == "sunrise") R.string.feedback_event_sunrise else R.string.feedback_event_sunset,
            )
            val title = content.placeName + content.region.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            val windowLine = content.windowRange?.let { resources.getString(R.string.share_best_window, it) }
            val text = buildString {
                append(resources.getString(R.string.share_summary, title, eventLabel, content.probability))
                if (windowLine != null) append(" · ").append(windowLine)
                append("\n\n").append(resources.getString(R.string.share_cta)).append('\n').append(content.link)
            }
            val send = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, text)
            }
            try {
                val spec = ShareCardSpec(
                    mode = content.mode,
                    probability = content.probability,
                    scoreLabel = resources.getString(scoreLabelRes(content.probability.toDouble())),
                    eventLabel = eventLabel,
                    placeName = content.placeName,
                    region = content.region,
                    dateLine = content.dateLine,
                    windowLine = windowLine,
                    tagline = resources.getString(R.string.share_tagline),
                    linkText = BuildConfig.API_BASE_URL.removePrefix("https://").trimEnd('/'),
                )
                val file = withContext(Dispatchers.Default) { ShareCard.write(context, ShareCard.render(context, spec)) }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                send.type = "image/png"
                send.putExtra(Intent.EXTRA_STREAM, uri)
                send.clipData = ClipData.newRawUri("", uri)
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                send.type = "text/plain"
            }
            context.startActivity(Intent.createChooser(send, resources.getString(R.string.share_chooser_title)))
        }
    }

    Box(Modifier.fillMaxSize()) {
        WeatherBackdrop(theme = state.theme, isNight = state.isNight)

        if (!state.onboardingCompleted) {
            OnboardingScreen(
                state = state,
                locationBusy = locationBusy,
                locationGranted = locationGranted,
                onRequestLocation = ::locate,
                onSelectPlace = { viewModel.selectPlace(it) },
                onThresholdChanged = { viewModel.setAlertThreshold(it) },
                onLeadMinutesChanged = { viewModel.setAlertLeadMinutes(it) },
                onSunriseAlertsChanged = { viewModel.setSunriseAlerts(it) },
                onSunsetAlertsChanged = { viewModel.setSunsetAlerts(it) },
                onImprovedAlertsChanged = { viewModel.setForecastImprovedAlerts(it) },
                onRequestNotificationPermission = { onComplete ->
                    if (!state.notificationsEnabled) viewModel.toggleNotifications()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) {
                        pendingOnboardingNotificationCallback = onComplete
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onComplete()
                    }
                },
                onSkipNotifications = {
                    if (state.notificationsEnabled) viewModel.toggleNotifications()
                },
                onTemperatureUnitChanged = { viewModel.setTemperatureUnit(it) },
                onDistanceUnitChanged = { viewModel.setDistanceUnit(it) },
                onTimeFormatChanged = { viewModel.setTimeFormat(it) },
                onLanguageChanged = { viewModel.setLanguageTag(it) },
                onFinish = { viewModel.completeOnboarding() },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    NavHost(
                        navController = navController,
                        startDestination = "home",
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        composable("home") {
                            HomeContent(
                                state = state,
                                locationBusy = locationBusy,
                                notificationsActive = notificationsActive,
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
                                onSaveViewpoint = { name, photo -> viewModel.saveViewpoint(name, photo) },
                                onOpenViewpoint = { viewModel.openViewpoint(it) },
                                onToggleReaction = { viewModel.toggleReaction(it) },
                                onReactionReason = { viewModel.setReactionReason(it) },
                                onRefreshReactionCounts = { viewModel.refreshReactionCounts() },
                                onOpenFullMap = { navigateToTab("map") },
                            )
                        }
                        composable("map") {
                            ChaseMapScreen(
                                state = state,
                                onSelectMode = { viewModel.selectMode(it) },
                                onInspectPoint = { lat, lon -> viewModel.inspectChasePoint(lat, lon) },
                                onUseSpotAsPlace = { place ->
                                    viewModel.selectPlace(place)
                                    navigateToTab("home")
                                },
                                onSaveSpotViewpoint = { name, lat, lon ->
                                    viewModel.saveViewpointAt(name, lat, lon)
                                    showMessage("Saved chase viewpoint: $name")
                                },
                            )
                        }
                        composable("community") {
                            CommunityScreen()
                        }
                        composable("account") {
                            AccountPage(
                                state = state,
                                notificationsActive = notificationsActive,
                                notificationsBlocked = state.notificationsEnabled && !notificationsAllowed,
                                onOpenNotificationSettings = {
                                    runCatching { context.startActivity(notificationSettingsIntent(context)) }
                                },
                                onAllowExactAlarms = onAllowExactAlarms,
                                onClose = { navigateToTab("home") },
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
                                    patch.forecastImprovedAlerts?.let { viewModel.setForecastImprovedAlerts(it) }
                                    patch.postEventFeedbackAlerts?.let { viewModel.setPostEventFeedbackAlerts(it) }
                                    patch.quietHoursEnabled?.let { viewModel.setQuietHoursEnabled(it) }
                                    patch.quietHoursRange?.let { (start, end) -> viewModel.setQuietHoursRange(start, end) }
                                    patch.togglePlaceAlert?.let { viewModel.togglePlaceAlert(it) }
                                    patch.temperatureUnit?.let { viewModel.setTemperatureUnit(it) }
                                    patch.distanceUnit?.let { viewModel.setDistanceUnit(it) }
                                    patch.timeFormat?.let { viewModel.setTimeFormat(it) }
                                    patch.languageTag?.let { viewModel.setLanguageTag(it) }
                                    if (patch.replayOnboarding) viewModel.reopenOnboarding()
                                },
                            )
                        }
                    }
                }
                FireSkyBottomNavBar(
                    currentRoute = currentRoute,
                    avatarUrl = state.account?.user?.avatarUrl,
                    onSelectRoute = ::navigateToTab,
                )
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 14.dp, vertical = 72.dp)
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

@Composable
private fun FireSkyBottomNavBar(
    currentRoute: String,
    avatarUrl: String?,
    onSelectRoute: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = SurfaceHairline,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f,
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.5.dp)
                .selectableGroup(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BottomNavItem(
                route = "home",
                label = stringResource(R.string.nav_forecast),
                iconRes = R.drawable.ic_sun_medium,
                selected = currentRoute == "home",
                modifier = Modifier.weight(1f),
                onClick = { onSelectRoute("home") },
            )
            BottomNavItem(
                route = "map",
                label = stringResource(R.string.nav_chase_map),
                iconRes = R.drawable.ic_compass,
                selected = currentRoute == "map",
                modifier = Modifier.weight(1f),
                onClick = { onSelectRoute("map") },
            )
            BottomNavItem(
                route = "community",
                label = stringResource(R.string.nav_community),
                iconRes = R.drawable.ic_users,
                selected = currentRoute == "community",
                modifier = Modifier.weight(1f),
                onClick = { onSelectRoute("community") },
            )
            BottomNavItem(
                route = "account",
                label = stringResource(R.string.nav_account),
                iconRes = R.drawable.ic_user_round,
                avatarUrl = avatarUrl,
                selected = currentRoute == "account",
                modifier = Modifier.weight(1f),
                onClick = { onSelectRoute("account") },
            )
        }
    }
}

@Composable
private fun BottomNavItem(
    route: String,
    label: String,
    iconRes: Int,
    selected: Boolean,
    modifier: Modifier = Modifier,
    avatarUrl: String? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pulse = rememberIconPulse()
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .tapPulse(pulse)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = ripple(color = Color.White),
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(vertical = 3.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Box(
            modifier = Modifier
                .height(24.dp)
                .width(48.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0x4DFFFFFF) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (route == "account" && avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = label,
                    modifier = Modifier.size(20.dp).clip(CircleShape),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                )
            } else {
                LucideIcon(
                    iconRes,
                    label,
                    tint = if (selected) Color.White else TextSecondary,
                    size = 17.dp,
                    pulse = pulse,
                    playKey = selected,
                )
            }
        }
        Text(
            text = label,
            color = if (selected) Color.White else TextSecondary,
            fontFamily = UiFamily,
            fontSize = 10.5.sp,
            fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun areNotificationsAllowed(context: Context): Boolean {
    val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    return permitted && NotificationManagerCompat.from(context).areNotificationsEnabled()
}

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

private fun exactAlarmSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.packageName))

private fun notificationSettingsIntent(context: Context): Intent =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    } else {
        appDetailsSettingsIntent(context)
    }

/** Height of everything on the first screen besides the two map bodies (header, card chrome, hero card). */
private val HOME_FIXED_CHROME = 404.dp

@Composable
private fun HomeContent(
    state: UiState,
    locationBusy: Boolean,
    notificationsActive: Boolean,
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
    onSaveViewpoint: (String, Uri?) -> Unit,
    onOpenViewpoint: (com.firesky.app.data.Viewpoint) -> Unit,
    onToggleReaction: (String) -> Unit,
    onReactionReason: (String) -> Unit,
    onRefreshReactionCounts: () -> Unit,
    onOpenFullMap: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 720.dp
        val wide = maxWidth >= 1180.dp
        val horizontalPadding = if (compact) 14.dp else 28.dp
        val screenWidth = maxWidth
        val screenHeight = maxHeight

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(
                    start = horizontalPadding,
                    end = horizontalPadding,
                    top = if (compact) 12.dp else 24.dp,
                    bottom = if (compact) 24.dp else 36.dp,
                ),
        ) {
            LocationHeaderAndControls(
                state = state,
                compact = compact,
                locationBusy = locationBusy,
                notificationsActive = notificationsActive,
                onToggleSearch = onToggleSearch,
                onSelectPlace = onSelectPlace,
                onSelectMode = onSelectMode,
                onToggleFavorite = onToggleFavorite,
                onLocate = onLocate,
                onRefresh = onRefresh,
                onToggleNotifications = onToggleNotifications,
                onShare = onShare,
                onSearchQuery = onSearchQuery,
                onSearchSubmit = onSearchSubmit,
            )

            val outlook = state.selectedOutlook
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
                    // Share the screen height between the two maps so the score gauge below
                    // them is fully visible when the app opens; tall phones keep 16:10 maps.
                    val aspectHeight = (screenWidth - horizontalPadding * 2 - 20.dp) * 10f / 16f
                    val mapHeight = ((screenHeight - HOME_FIXED_CHROME) / 2f).coerceIn(120.dp, maxOf(120.dp, aspectHeight))
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HeatMapCard(
                            place = state.place,
                            samples = state.grid,
                            type = "probability",
                            mode = state.activeMode,
                            compact = compact,
                            onSampleSelected = onSampleSelected,
                            onOpenFullMap = onOpenFullMap,
                            mapHeight = mapHeight,
                        )
                        HeatMapCard(
                            place = state.place,
                            samples = state.grid,
                            type = "quality",
                            mode = state.activeMode,
                            compact = compact,
                            onSampleSelected = onSampleSelected,
                            onOpenFullMap = onOpenFullMap,
                            mapHeight = mapHeight,
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
                            onOpenFullMap = onOpenFullMap,
                        )
                        HeatMapCard(
                            place = state.place,
                            samples = state.grid,
                            type = "quality",
                            mode = state.activeMode,
                            compact = false,
                            modifier = Modifier.weight(1f),
                            onSampleSelected = onSampleSelected,
                            onOpenFullMap = onOpenFullMap,
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
                        onReason = onReactionReason,
                        onRefreshCounts = onRefreshReactionCounts,
                    )
                }

                Spacer(Modifier.height(18.dp))
                SourceLine()
            }
        }
    }
}

/**
 * Cleanly structured top header:
 * 1. Place Title Row (tap to expand/collapse search right underneath)
 * 2. Expanded Search & Unified Cities Drawer (directly under the place title, no duplicate city rows!)
 *    OR collapsed Saved Places chips row when search is closed.
 * 3. Sunrise / Sunset mode selector + Quick Action buttons.
 */
@Composable
private fun LocationHeaderAndControls(
    state: UiState,
    compact: Boolean,
    locationBusy: Boolean,
    notificationsActive: Boolean,
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
) {
    val isFavorite = state.favorites.any { it.sameSpot(state.place) }
    val headerPulse = rememberIconPulse()
    Column(Modifier.fillMaxWidth()) {
        // 1. Place Title Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .tapPulse(headerPulse)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = if (state.showSearch) "Close search" else "Search locations",
                    onClick = onToggleSearch,
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = state.place.name,
                        color = Color.White,
                        fontFamily = DisplayFamily,
                        fontSize = (if (compact) 22 else 28).sp,
                        fontWeight = FontWeight.W700,
                        letterSpacing = (-0.6).sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (state.place.admin1.isNotBlank()) {
                        Text(
                            text = "· ${state.place.admin1}",
                            color = TextSecondary,
                            fontFamily = DisplayFamily,
                            fontSize = (if (compact) 15 else 20).sp,
                            fontWeight = FontWeight.W500,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(SurfaceControl),
                contentAlignment = Alignment.Center,
            ) {
                Crossfade(targetState = state.showSearch, animationSpec = tween(220), label = "searchToggleIcon") { open ->
                    LucideIcon(
                        if (open) R.drawable.ic_chevron_down else R.drawable.ic_search,
                        null,
                        tint = Color.White,
                        size = 16.dp,
                        pulse = headerPulse,
                        playOnAppear = true,
                    )
                }
            }
        }

        // 2. Immediately below the Place Title: Search Panel (when open) OR Saved Places row (when closed)
        AnimatedVisibility(
            visible = state.showSearch,
            enter = fadeIn(tween(200, delayMillis = 60)) +
                expandVertically(tween(300, easing = FastOutSlowInEasing), expandFrom = Alignment.Top) +
                slideInVertically(tween(300, easing = FastOutSlowInEasing)) { -it / 5 },
            exit = fadeOut(tween(120)) +
                shrinkVertically(tween(240, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) +
                slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { -it / 5 },
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                SearchPanel(
                    state = state,
                    onQuery = onSearchQuery,
                    onSubmit = onSearchSubmit,
                    onSelect = onSelectPlace,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        AnimatedVisibility(
            visible = !state.showSearch && state.favorites.isNotEmpty(),
            enter = fadeIn(tween(220, delayMillis = 120)) + expandVertically(tween(260), expandFrom = Alignment.Top),
            exit = fadeOut(tween(100)) + shrinkVertically(tween(200), shrinkTowards = Alignment.Top),
        ) {
            Column {
                Spacer(Modifier.height(6.dp))
                SavedPlacesBar(
                    favorites = state.favorites,
                    place = state.place,
                    compact = compact,
                    onSelect = onSelectPlace,
                )
                Spacer(Modifier.height(4.dp))
            }
        }

        // 3. Sunrise / Sunset Mode Toggle + Quick Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModeToggle(
                activeMode = state.activeMode,
                onSelect = onSelectMode,
                modifier = if (compact) Modifier.weight(1f) else Modifier.width(300.dp),
            )
            if (!compact) Spacer(Modifier.weight(1f))
            QuickActions(
                modifier = if (compact) Modifier.offset(x = 7.dp) else Modifier,
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
        ModeButton(stringResource(R.string.mode_sunrise), activeMode == "sunrise", Modifier.weight(1f)) { onSelect("sunrise") }
        ModeButton(stringResource(R.string.mode_sunset), activeMode == "sunset", Modifier.weight(1f)) { onSelect("sunset") }
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
    Row(modifier) {
        ControlIconButton(
            iconRes = R.drawable.ic_star,
            contentDescription = "Saved location",
            onClick = onFavorite,
            selected = favorite,
            checked = favorite,
            size = 34.dp,
        )
        ControlIconButton(
            iconRes = if (locationBusy) R.drawable.ic_loader_circle else R.drawable.ic_locate_fixed,
            contentDescription = if (locationBusy) "Finding your location" else "Use my location",
            onClick = onLocate,
            size = 34.dp,
        )
        ControlIconButton(
            iconRes = R.drawable.ic_refresh_cw,
            contentDescription = "Refresh",
            onClick = onRefresh,
            size = 34.dp,
        )
        ControlIconButton(
            iconRes = if (notifications) R.drawable.ic_bell else R.drawable.ic_bell_off,
            contentDescription = "Forecast alerts",
            onClick = onNotifications,
            selected = notifications,
            checked = notifications,
            size = 34.dp,
        )
        ControlIconButton(
            iconRes = R.drawable.ic_share_2,
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
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        if (!compact) {
            Text(stringResource(R.string.label_saved_places), color = TextMuted, fontSize = 14.sp)
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
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
    onQuery: (String) -> Unit,
    onSubmit: () -> Unit,
    onSelect: (Place) -> Unit,
) {
    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(26.dp),
        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 5.dp),
    ) {
        // 1. Search Input Bar at the top
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 42.dp)
                .padding(start = 14.dp, end = 3.dp),
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
                        Text(
                            stringResource(R.string.search_placeholder),
                            color = TextSecondary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                },
            )
            Box(
                modifier = Modifier
                    .heightIn(min = 34.dp)
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
                    Text(stringResource(R.string.action_search), color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.W600)
                }
            }
        }

        // 2. Search results (if any) directly under the input bar
        if (state.search.error.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
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
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LucideIcon(R.drawable.ic_map_pin, null, tint = Color(0xFFFFD76A), size = 15.dp)
                        Text(
                            text = "${result.name}${if (result.admin1.isNotBlank()) ", ${result.admin1}" else ""}",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.W600,
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
            val retryPulse = rememberIconPulse()
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(Color(0x2EFFFFFF))
                    .tapPulse(retryPulse)
                    .clickable(interactionSource = interactionSource, indication = ripple(), role = Role.Button, onClick = onRetry)
                    .padding(horizontal = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LucideIcon(R.drawable.ic_refresh_cw, null, tint = TextPrimary, size = 17.dp, pulse = retryPulse)
                Text(stringResource(R.string.action_retry), color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.W600)
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
            text = stringResource(R.string.source_line_footer),
            color = TextMuted,
            fontSize = 11.sp,
            letterSpacing = 1.sp,
            textAlign = TextAlign.Center,
        )
    }
}
