package com.firesky.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesky.app.data.AccountSettings
import com.firesky.app.data.AccountState
import com.firesky.app.data.ApiClient
import com.firesky.app.data.ApiException
import com.firesky.app.data.ClockStyle
import com.firesky.app.data.DEFAULT_PLACE
import com.firesky.app.data.EventReaction
import com.firesky.app.data.Forecast
import com.firesky.app.data.ForecastRepository
import com.firesky.app.data.ForecastSnapshot
import com.firesky.app.data.GridKey
import com.firesky.app.data.Place
import com.firesky.app.data.ReactionSummary
import com.firesky.app.data.ScoredPoint
import com.firesky.app.data.Store
import com.firesky.app.data.UnitPrefs
import com.firesky.app.data.Viewpoint
import com.firesky.app.data.ViewpointPhotos
import com.firesky.app.data.forecastDayKey
import com.firesky.app.data.formatRange
import com.firesky.app.data.optNullableString
import com.firesky.app.data.reactionFor
import com.firesky.app.data.sunAzimuth
import com.firesky.app.data.upcomingEvents
import com.firesky.app.data.weatherDescription
import com.firesky.app.data.weatherTheme
import com.firesky.app.notifications.PushTokenManager
import com.firesky.app.notifications.SkyAlerts
import com.firesky.app.ui.map.HeatField
import com.firesky.app.widget.TonightSkyWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.abs

data class ShareContent(
    val placeName: String,
    val region: String,
    val mode: String,
    val probability: Int,
    val windowRange: String?,
    val dateLine: String,
    val link: String,
)

data class SearchState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val error: String = "",
)

data class ChaseSpotState(
    val latitude: Double,
    val longitude: Double,
    val placeName: String,
    val probability: Double,
    val quality: Double,
    val peakWindowLabel: String,
    val sunBearing: Double,
    val loading: Boolean = false,
)

data class UiState(
    val place: Place = DEFAULT_PLACE,
    val activeMode: String = "sunset",
    /** Today's outlook (days[0]); kept for callers that predate the multi-day list. */
    val data: Forecast? = null,
    val days: List<Forecast> = emptyList(),
    val grid: List<ScoredPoint>? = null,
    val loading: Boolean = true,
    val error: String = "",
    val offline: Boolean = false,
    val favorites: List<Place> = emptyList(),
    val showSearch: Boolean = false,
    val showAccount: Boolean = false,
    val search: SearchState = SearchState(),
    val selectedDayIndex: Int = 0,
    val notificationsEnabled: Boolean = true,
    val alertLeadMinutes: Int = 60,
    val sunriseAlerts: Boolean = true,
    val sunsetAlerts: Boolean = true,
    val alertThreshold: Int = 70,
    val forecastImprovedAlerts: Boolean = true,
    val postEventFeedbackAlerts: Boolean = true,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: Int = 22 * 60,
    val quietHoursEnd: Int = 7 * 60,
    val disabledAlertPlaceKeys: Set<String> = emptySet(),
    val onboardingCompleted: Boolean = true,
    val unitPrefs: UnitPrefs = UnitPrefs(),
    val chaseSpot: ChaseSpotState? = null,
    val accountToken: String = "",
    val account: AccountState? = null,
    val accountError: String = "",
    val viewpoints: List<Viewpoint> = emptyList(),
    val forecastSnapshots: List<ForecastSnapshot> = emptyList(),
    val forecastHistoryLabel: String = "",
    val feedbackStore: JSONObject = JSONObject(),
    val lastSyncedAt: Long? = null,
    /** Community reaction totals per forecast event, keyed like [reactionKey]. */
    val reactionCounts: Map<String, ReactionSummary> = emptyMap(),
    val selectedSample: ScoredPoint? = null,
    val accountBusy: Boolean = false,
    /** When the forecast on screen was fetched or re-scored. */
    val forecastUpdatedAt: Long? = null,
) {
    val selectedOutlook: Forecast? get() = days.getOrNull(selectedDayIndex) ?: data
    val active: com.firesky.app.data.Score? get() = selectedOutlook?.scores?.get(activeMode)
    val activeWindow: com.firesky.app.data.Window? get() = selectedOutlook?.windows?.get(activeMode)
    val activeAppearance: com.firesky.app.data.AppearanceWindow? get() = selectedOutlook?.appearanceWindow?.get(activeMode)
    val activeAir: com.firesky.app.data.AirSnapshot? get() = selectedOutlook?.airSnapshots?.get(activeMode) ?: selectedOutlook?.airSnapshot
    val theme: String get() = selectedOutlook?.let { weatherTheme(it.current.weatherCode, it.current.cloudCover ?: 0.0) } ?: "clear"
    val currentWeather: String get() = selectedOutlook?.let {
        weatherDescription(it.current.weatherCode, it.current.cloudCover ?: 0.0)
    } ?: "Loading"
    val isNight: Boolean get() {
        val outlook = selectedOutlook ?: return false
        val now = System.currentTimeMillis()
        return now < outlook.sunrise.toEpochMilli() || now > outlook.sunset.toEpochMilli()
    }
    val sunBearing: Double get() {
        val outlook = selectedOutlook ?: return 0.0
        val event = outlook[activeMode]
        return sunAzimuth(event, place.latitude, place.longitude)
    }
    /** The forecast event on screen (selected day and mode); reactions are kept per event. */
    val reactionKey: String? get() {
        val outlook = selectedOutlook ?: return null
        return forecastDayKey(outlook)?.let { "$it:$activeMode" }
    }
    val reaction: EventReaction get() = reactionKey?.let { reactionFor(feedbackStore, it) } ?: EventReaction()
    val reactionSummary: ReactionSummary? get() = reactionKey?.let { reactionCounts[it] }
}

class FireSkyViewModel(application: Application) : AndroidViewModel(application) {
    private val store = Store(application)
    private val api = ApiClient(store)
    private val repository = ForecastRepository(api)
    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(
        UiState(
            place = store.lastPlace ?: DEFAULT_PLACE,
            favorites = store.favorites,
            notificationsEnabled = store.notificationsEnabled,
            alertLeadMinutes = store.alertLeadMinutes,
            sunriseAlerts = store.sunriseAlerts,
            sunsetAlerts = store.sunsetAlerts,
            alertThreshold = store.alertThreshold,
            forecastImprovedAlerts = store.forecastImprovedAlerts,
            postEventFeedbackAlerts = store.postEventFeedbackAlerts,
            quietHoursEnabled = store.quietHoursEnabled,
            quietHoursStart = store.quietHoursStart,
            quietHoursEnd = store.quietHoursEnd,
            disabledAlertPlaceKeys = store.disabledAlertPlaceKeys,
            onboardingCompleted = store.onboardingCompleted,
            unitPrefs = UnitPrefs(
                temperatureUnit = store.temperatureUnit,
                distanceUnit = store.distanceUnit,
                timeFormat = store.timeFormat,
                languageTag = store.languageTag,
            ),
            accountToken = store.accountToken,
            account = store.accountToken.takeIf { it.isNotBlank() }?.let { store.cachedAccount },
            viewpoints = store.viewpoints,
            feedbackStore = store.feedback,
            offline = !isOnline(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadSeq = 0

    /** The place whose forecast is actually on screen; [UiState.place] may be one still loading. */
    private var displayedPlace: Place? = null

    /** Regional grids for every event the grid payload covers, keyed by mode and day. */
    private var grids: Map<GridKey, List<ScoredPoint>> = emptyMap()
    private var gridsLoaded = false

    /** null while the regional grid is loading; empty when this event has no regional data. */
    private fun gridFor(mode: String, dayIndex: Int): List<ScoredPoint>? =
        if (gridsLoaded) grids[GridKey(mode, dayIndex)].orEmpty() else null

    /** Set once the person picks a mode or day; until then the screen follows the next event. */
    private var userSelectedMode = false
    private var userSelectedDay = false

    /** An alert being opened: once its place loads, jump to the matching event. */
    private var pendingAlertEvent: Pair<String, Long>? = null

    private var foreground = true
    private var settingsSyncJob: Job? = null
    private var syncJob: Job? = null
    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        ClockStyle.sync(application, store)
        registerNetworkCallback()
        viewModelScope.launch {
            PushTokenManager.ensurePushToken(application, store)
        }
        _state.value.accountToken.takeIf { it.isNotBlank() }?.let { acceptAccountToken(it) }
        load(_state.value.place, _state.value.activeMode)
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(REFRESH_INTERVAL_MS)
                // Background refresh belongs to ForecastRefreshWorker; only refresh what is on screen.
                if (foreground) load(_state.value.place, _state.value.activeMode, force = true, silent = true)
            }
        }
    }

    private fun isOnline(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val wasOffline = _state.value.offline
                _state.update { it.copy(offline = false) }
                // Recover a failed first load as soon as the connection returns.
                val current = _state.value
                if (wasOffline && current.data == null && !current.loading) {
                    load(current.place, current.activeMode)
                }
                // An account check that failed offline is retried rather than left stale.
                if (wasOffline && current.accountToken.isNotBlank() && !current.accountBusy) {
                    acceptAccountToken(current.accountToken)
                }
            }

            override fun onLost(network: Network) {
                _state.update { it.copy(offline = !isOnline()) }
            }
        }
        try {
            connectivity.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: Exception) {
            // Some restricted devices do not allow default network callbacks.
        }
    }

    /** [source] is "location" for the locate button, "search" for everything else (telemetry only). */
    fun selectPlace(place: Place, source: String = "search") {
        _state.update {
            it.copy(
                place = place,
                showSearch = false,
                selectedSample = null,
            )
        }
        userSelectedDay = false
        api.telemetry(
            "location_selected",
            JSONObject().put("source", source),
        )
        load(place, _state.value.activeMode)
    }

    fun selectMode(mode: String) {
        userSelectedMode = true
        if (_state.value.activeMode == mode) return
        _state.update {
            it.copy(
                activeMode = mode,
                selectedSample = null,
                grid = gridFor(mode, it.selectedDayIndex),
            )
        }
        recordSelectedEvent()
    }

    fun selectDay(index: Int) {
        userSelectedDay = true
        _state.update {
            it.copy(
                selectedDayIndex = index,
                selectedSample = null,
                grid = gridFor(it.activeMode, index),
            )
        }
        recordSelectedEvent()
        api.telemetry("outlook_day_selected", JSONObject().put("dayOffset", index).put("mode", _state.value.activeMode))
    }

    fun toggleFavorite() {
        val place = _state.value.place
        val current = _state.value.favorites
        val exists = current.any { it.sameSpot(place) }
        val next = if (exists) {
            current.filterNot { it.sameSpot(place) }
        } else {
            (listOf(place) + current).take(8)
        }
        store.favorites = next
        _state.update { it.copy(favorites = next) }
        api.telemetry("favorite_location", JSONObject().put("action", if (exists) "remove" else "add").put("total", next.size))
        scheduleSyncPush()
    }

    fun toggleNotifications() {
        val next = !_state.value.notificationsEnabled
        store.notificationsEnabled = next
        _state.update { it.copy(notificationsEnabled = next) }
        pushCloudSettings()
        scheduleAlerts()
    }

    fun setAlertLeadMinutes(minutes: Int) {
        store.alertLeadMinutes = minutes
        _state.update { it.copy(alertLeadMinutes = minutes) }
        pushCloudSettings()
        scheduleAlerts()
    }

    fun setSunriseAlerts(enabled: Boolean) {
        store.sunriseAlerts = enabled
        _state.update { it.copy(sunriseAlerts = enabled) }
        pushCloudSettings()
        scheduleAlerts()
    }

    fun setSunsetAlerts(enabled: Boolean) {
        store.sunsetAlerts = enabled
        _state.update { it.copy(sunsetAlerts = enabled) }
        pushCloudSettings()
        scheduleAlerts()
    }

    fun setAlertThreshold(threshold: Int) {
        store.alertThreshold = threshold
        _state.update { it.copy(alertThreshold = threshold) }
        pushCloudSettings()
        scheduleAlerts()
    }

    fun setForecastImprovedAlerts(enabled: Boolean) {
        store.forecastImprovedAlerts = enabled
        _state.update { it.copy(forecastImprovedAlerts = enabled) }
        scheduleAlerts()
    }

    fun setPostEventFeedbackAlerts(enabled: Boolean) {
        store.postEventFeedbackAlerts = enabled
        _state.update { it.copy(postEventFeedbackAlerts = enabled) }
        scheduleAlerts()
    }

    fun setQuietHoursEnabled(enabled: Boolean) {
        store.quietHoursEnabled = enabled
        _state.update { it.copy(quietHoursEnabled = enabled) }
    }

    fun setQuietHoursRange(startMinutes: Int, endMinutes: Int) {
        store.quietHoursStart = startMinutes
        store.quietHoursEnd = endMinutes
        _state.update { it.copy(quietHoursStart = startMinutes, quietHoursEnd = endMinutes) }
    }

    fun togglePlaceAlert(place: Place) {
        val key = place.spotKey()
        val current = _state.value.disabledAlertPlaceKeys
        val next = if (key in current) current - key else current + key
        store.disabledAlertPlaceKeys = next
        _state.update { it.copy(disabledAlertPlaceKeys = next) }
        scheduleAlerts()
    }

    fun setTemperatureUnit(unit: String) {
        store.temperatureUnit = unit
        ClockStyle.sync(getApplication(), store)
        _state.update { it.copy(unitPrefs = it.unitPrefs.copy(temperatureUnit = unit)) }
        displayedPlace?.let { p ->
            viewModelScope.launch { TonightSkyWidget.updateFromForecast(getApplication(), p, _state.value.days) }
        }
    }

    fun setDistanceUnit(unit: String) {
        store.distanceUnit = unit
        ClockStyle.sync(getApplication(), store)
        _state.update { it.copy(unitPrefs = it.unitPrefs.copy(distanceUnit = unit)) }
    }

    fun setTimeFormat(format: String) {
        store.timeFormat = format
        ClockStyle.sync(getApplication(), store)
        _state.update { it.copy(unitPrefs = it.unitPrefs.copy(timeFormat = format)) }
    }

    fun setLanguageTag(tag: String) {
        store.languageTag = tag
        _state.update { it.copy(unitPrefs = it.unitPrefs.copy(languageTag = tag)) }
    }

    fun completeOnboarding() {
        store.onboardingCompleted = true
        _state.update { it.copy(onboardingCompleted = true) }
    }

    fun reopenOnboarding() {
        _state.update { it.copy(onboardingCompleted = false) }
    }

    fun inspectChasePoint(latitude: Double, longitude: Double) {
        val current = _state.value
        val mode = current.activeMode
        val samples = current.grid.orEmpty()
        val region = com.firesky.app.ui.map.ChaseMapRegion.from(samples) ?: return
        if (!region.contains(latitude, longitude)) return
        val outlook = current.selectedOutlook
        val lats = DoubleArray(samples.size) { samples[it].latitude }
        val lons = DoubleArray(samples.size) { samples[it].longitude }
        // Match the softness the Chase Map draws, so an inspected spot reads the colour under the pin.
        val sigma = HeatField.CHASE_MAP_SIGMA
        val interpolatedProb = HeatField.interpolate(latitude, longitude, lats, lons, DoubleArray(samples.size) { samples[it].probability }, sigma)
        val interpolatedQual = HeatField.interpolate(latitude, longitude, lats, lons, DoubleArray(samples.size) { samples[it].quality }, sigma)
        val defaultWindow = outlook?.appearanceWindow?.get(mode)?.let {
            formatRange(it.start, it.end, outlook.timeZone)
        } ?: "--:--"
        val defaultBearing = outlook?.get(mode)?.let {
            sunAzimuth(it, latitude, longitude)
        } ?: current.sunBearing
        val coordLabel = String.format(Locale.US, "Spot %.2f°, %.2f°", latitude, longitude)

        _state.update {
            it.copy(
                chaseSpot = ChaseSpotState(
                    latitude = latitude,
                    longitude = longitude,
                    placeName = coordLabel,
                    probability = interpolatedProb,
                    quality = interpolatedQual,
                    peakWindowLabel = defaultWindow,
                    sunBearing = defaultBearing,
                    loading = false,
                ),
            )
        }

        // Browsing and inspecting reuse the already sampled regional field.
        // An explicit "Forecast here" action loads a new place through selectPlace.
    }

    fun refresh() {
        load(_state.value.place, _state.value.activeMode, force = true)
    }

    /**
     * Loads [place]. A new place only replaces what is on screen once its own
     * forecast arrives; if that fails, the screen keeps (and names) the place
     * it is actually showing.
     */
    fun load(place: Place, mode: String, force: Boolean = false, silent: Boolean = false) {
        val seq = ++loadSeq
        if (!silent) _state.update { it.copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val days = repository.loadDays(place, force)
                if (seq != loadSeq) return@launch
                val samePlace = displayedPlace?.sameSpot(place) == true
                val (dayIndex, nextMode) = pickEvent(days, keepSelection = samePlace)
                displayedPlace = place
                grids = emptyMap()
                gridsLoaded = false
                store.lastPlace = place
                _state.update {
                    it.copy(
                        place = place,
                        data = days.first(),
                        days = days,
                        grid = null,
                        activeMode = nextMode,
                        selectedDayIndex = dayIndex,
                        loading = false,
                        error = "",
                        forecastUpdatedAt = System.currentTimeMillis(),
                    )
                }
                try {
                    val result = repository.regionalize(place, days, force)
                    if (seq != loadSeq) return@launch
                    grids = result.grids
                    gridsLoaded = true
                    _state.update {
                        it.copy(
                            data = result.days.first(),
                            days = result.days,
                            grid = gridFor(it.activeMode, it.selectedDayIndex),
                        )
                    }
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (seq == loadSeq) {
                        gridsLoaded = true
                        _state.update {
                            it.copy(
                                grid = gridFor(it.activeMode, it.selectedDayIndex),
                                error = "Regional map is temporarily unavailable; local weather is still current.",
                            )
                        }
                    }
                }
                if (seq != loadSeq) return@launch
                recordSelectedEvent()
                scheduleAlerts()
                TonightSkyWidget.updateFromForecast(getApplication(), place, _state.value.days)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                if (seq != loadSeq) return@launch
                val message = error.message ?: "Couldn't load the forecast. Try again in a moment."
                val shown = displayedPlace
                _state.update {
                    if (it.data != null && shown != null && !shown.sameSpot(place)) {
                        it.copy(place = shown, loading = false, error = "Couldn't load ${place.name}. $message")
                    } else {
                        it.copy(loading = false, error = message)
                    }
                }
                pendingAlertEvent = null
            }
        }
    }

    /**
     * Chooses which day and mode to lead with. An alert being opened wins; then
     * the person's own picks; otherwise the next sunrise or sunset still worth watching.
     */
    private fun pickEvent(days: List<Forecast>, keepSelection: Boolean): Pair<Int, String> {
        val current = _state.value
        pendingAlertEvent?.let { (mode, eventAt) ->
            pendingAlertEvent = null
            val index = days.indexOfFirst { abs(it[mode].toEpochMilli() - eventAt) <= 3L * 3600_000L }
            if (index >= 0) {
                userSelectedMode = true
                userSelectedDay = true
                return index to mode
            }
        }
        if (keepSelection && userSelectedDay) {
            return current.selectedDayIndex.coerceIn(0, days.lastIndex) to current.activeMode
        }
        val upcoming = upcomingEvents(days)
        if (userSelectedMode) {
            val next = upcoming.firstOrNull { it.mode == current.activeMode }
            return (next?.dayIndex ?: 0) to current.activeMode
        }
        val next = upcoming.firstOrNull() ?: return 0 to "sunset"
        return next.dayIndex to next.mode
    }

    /** Loads shared regional history; local point scores are never appended to it. */
    private fun recordSelectedEvent() {
        val current = _state.value
        val outlook = current.selectedOutlook ?: return
        loadForecastHistory(outlook, current.activeMode)
    }

    private var historyRequestId = 0L

    private fun loadForecastHistory(outlook: Forecast, mode: String) {
        val place = displayedPlace ?: return
        val requestId = ++historyRequestId
        val date = outlook[mode].atZone(java.time.ZoneId.of(outlook.timeZone)).toLocalDate().toString()
        _state.update { it.copy(forecastSnapshots = emptyList(), forecastHistoryLabel = "Loading regional trend…") }
        viewModelScope.launch {
            runCatching { api.fetchRegionalHistory(place, date, mode) }
                .onSuccess { result ->
                    if (requestId != historyRequestId || displayedPlace != place || _state.value.activeMode != mode || _state.value.selectedOutlook?.get(mode) != outlook[mode]) return@onSuccess
                    val region = result.optJSONObject("region")
                    val array = result.optJSONArray("snapshots") ?: JSONArray()
                    val snapshots = List(array.length()) { ForecastSnapshot.fromJson(array.getJSONObject(it)) }
                    val label = when (result.optString("status")) {
                        "outside_coverage" -> "Regional history is not yet available here"
                        "collecting" -> "${region?.optString("name")}: collecting regional history · every 3 hours"
                        "stale" -> "${region?.optString("name")}: regional trend · update delayed"
                        else -> "${region?.optString("name")} area · ${region?.optInt("distanceKm")} km from sample · every 3 hours"
                    }
                    _state.update { it.copy(forecastSnapshots = snapshots, forecastHistoryLabel = label) }
                }.onFailure {
                    if (requestId == historyRequestId) _state.update {
                        it.copy(forecastSnapshots = emptyList(), forecastHistoryLabel = "Regional history temporarily unavailable")
                    }
                }
        }
    }

    fun scheduleAlerts() {
        val current = _state.value
        val place = displayedPlace ?: return
        if (current.days.isEmpty()) return
        SkyAlerts.schedule(getApplication(), current.days, place, currentSettings())
    }

    // ---- Search ----------------------------------------------------------

    fun setSearchVisible(visible: Boolean) {
        _state.update { it.copy(showSearch = visible) }
    }

    fun updateSearchQuery(query: String) {
        _state.update { it.copy(search = it.search.copy(query = query)) }
    }

    fun submitSearch() {
        val query = _state.value.search.query.trim()
        if (query.isEmpty()) return
        searchJob?.cancel()
        _state.update { it.copy(search = it.search.copy(searching = true, error = "", results = emptyList())) }
        searchJob = viewModelScope.launch {
            try {
                val results = api.geocode(query)
                _state.update {
                    it.copy(
                        search = it.search.copy(
                            searching = false,
                            results = results,
                            error = if (results.isEmpty()) "No North American places match \"$query\"." else "",
                        ),
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update {
                    it.copy(search = it.search.copy(searching = false, error = error.message ?: "Unable to search locations"))
                }
            }
        }
    }

    // ---- Account ---------------------------------------------------------

    fun setAccountVisible(visible: Boolean) {
        _state.update { it.copy(showAccount = visible, accountError = "") }
    }

    fun acceptAccountToken(token: String) {
        if (token.isBlank()) return
        store.accountToken = token
        _state.update { it.copy(accountToken = token, accountError = "", accountBusy = true) }
        viewModelScope.launch {
            try {
                val account = api.fetchAccount(token)
                val sync = api.fetchSync(token)
                store.cachedAccount = account
                store.notificationsEnabled = account.settings.notificationsEnabled
                store.alertLeadMinutes = account.settings.alertLeadMinutes
                store.sunriseAlerts = account.settings.sunriseAlerts
                store.sunsetAlerts = account.settings.sunsetAlerts
                store.alertThreshold = account.settings.alertThreshold
                val favorites = if (sync.locations.isNotEmpty()) sync.locations else _state.value.favorites
                // Viewpoints saved before signing in join the cloud copy instead of vanishing.
                val localOnly = _state.value.viewpoints.filter { local -> sync.viewpoints.none { it.id == local.id } }
                // A photo still waiting to upload isn't on the server copy yet; keep the local reference.
                val localById = _state.value.viewpoints.associateBy { it.id }
                val cloud = sync.viewpoints.map { remote ->
                    val local = localById[remote.id]
                    if (remote.photoId == null && local?.photoId != null) remote.copy(photoId = local.photoId, photoUploaded = local.photoUploaded) else remote
                }
                val viewpoints = (cloud + localOnly).take(30)
                store.favorites = favorites
                store.viewpoints = viewpoints
                _state.update {
                    it.copy(
                        account = account,
                        accountBusy = false,
                        favorites = favorites,
                        viewpoints = viewpoints,
                        notificationsEnabled = account.settings.notificationsEnabled,
                        alertLeadMinutes = account.settings.alertLeadMinutes,
                        sunriseAlerts = account.settings.sunriseAlerts,
                        sunsetAlerts = account.settings.sunsetAlerts,
                        alertThreshold = account.settings.alertThreshold,
                        lastSyncedAt = System.currentTimeMillis(),
                    )
                }
                if (localOnly.isNotEmpty() || (sync.locations.isEmpty() && favorites.isNotEmpty())) scheduleSyncPush()
                // Reactions made while signed out go up now, and the counts reload with this account's own vote.
                countsFetchedAt.clear()
                syncPendingReactions()
                refreshReactionCounts()
                uploadPendingPhotos()
                scheduleAlerts()
                PushTokenManager.syncRegistration(getApplication(), store, api)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                val status = (error as? ApiException)?.statusCode ?: 0
                if (status == 401 || status == 403) {
                    // Only a rejected session signs the person out; a dropped connection must not.
                    store.accountToken = ""
                    store.cachedAccount = null
                    _state.update {
                        it.copy(
                            accountToken = "",
                            account = null,
                            accountBusy = false,
                            accountError = "Your session has expired. Please sign in again.",
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            account = it.account ?: store.cachedAccount,
                            accountBusy = false,
                            accountError = if ((it.account ?: store.cachedAccount) != null) "" else error.message ?: "Unable to load account",
                        )
                    }
                }
            }
        }
    }

    fun signInWithPassword(action: String, email: String, password: String, displayName: String?) {
        if (_state.value.accountBusy) return
        _state.update { it.copy(accountBusy = true, accountError = "") }
        viewModelScope.launch {
            try {
                val token = api.passwordAuth(action, email, password, displayName)
                acceptAccountToken(token)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(accountBusy = false, accountError = error.message ?: "Sign in failed") }
            }
        }
    }

    /**
     * Starts Google sign-in with PKCE: the browser only ever sees a challenge,
     * and the one-time code it hands back is worthless without the verifier
     * kept here, so another app catching the redirect can't sign in as you.
     */
    fun startGoogleSignIn(onUrl: (String) -> Unit) {
        if (_state.value.accountBusy) return
        val verifier = randomUrlSafe(32)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        store.pendingAuthVerifier = verifier
        _state.update { it.copy(accountError = "", accountBusy = true) }
        viewModelScope.launch {
            try {
                val url = api.authStart("google", "com.firesky.app://auth", challenge)
                _state.update { it.copy(accountBusy = false) }
                onUrl(url)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                store.pendingAuthVerifier = ""
                _state.update { it.copy(accountBusy = false, accountError = error.message ?: "Sign in failed") }
            }
        }
    }

    /** Finishes Google sign-in from the `com.firesky.app://auth?auth_code=` redirect. */
    fun completeOAuth(code: String) {
        val verifier = store.pendingAuthVerifier
        store.pendingAuthVerifier = ""
        if (verifier.isBlank()) {
            _state.update { it.copy(accountError = "This sign-in link has expired. Please try again.") }
            return
        }
        _state.update { it.copy(accountBusy = true, accountError = "") }
        viewModelScope.launch {
            try {
                acceptAccountToken(api.exchangeAuthCode(code, verifier))
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(accountBusy = false, accountError = error.message ?: "Sign in failed") }
            }
        }
    }

    /**
     * Accepts a raw session token from a server that predates the code
     * exchange, but only while this app has a sign-in in flight, so a stray
     * link can't silently swap the account.
     */
    fun acceptLegacyOAuthToken(token: String) {
        if (store.pendingAuthVerifier.isBlank()) {
            _state.update { it.copy(accountError = "This sign-in link has expired. Please try again.") }
            return
        }
        store.pendingAuthVerifier = ""
        acceptAccountToken(token)
    }

    fun signOut() {
        store.accountToken = ""
        store.cachedAccount = null
        _state.update { it.copy(accountToken = "", account = null, showAccount = false) }
    }

    fun saveProfile(displayName: String) {
        val token = _state.value.accountToken
        if (token.isBlank()) return
        _state.update { it.copy(accountBusy = true, accountError = "") }
        viewModelScope.launch {
            try {
                val settings = currentSettings()
                val user = api.updateAccount(token, settings, displayName)
                _state.update {
                    val account = it.account?.copy(user = user ?: it.account.user)
                    store.cachedAccount = account
                    it.copy(
                        account = account,
                        accountBusy = false,
                        lastSyncedAt = System.currentTimeMillis(),
                    )
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(accountBusy = false, accountError = error.message ?: "Unable to save profile") }
            }
        }
    }

    fun changePassword(currentPassword: String, nextPassword: String, onDone: (String?) -> Unit) {
        val token = _state.value.accountToken
        if (token.isBlank()) return
        viewModelScope.launch {
            try {
                api.changePassword(token, currentPassword, nextPassword)
                onDone(null)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                onDone(error.message ?: "Unable to update password")
            }
        }
    }

    fun deleteAccount(onDone: (String?) -> Unit) {
        val token = _state.value.accountToken
        if (token.isBlank() || _state.value.accountBusy) return
        _state.update { it.copy(accountBusy = true, accountError = "") }
        viewModelScope.launch {
            try {
                api.deleteAccount(token)
                _state.update { it.copy(accountBusy = false) }
                signOut()
                onDone(null)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                _state.update { it.copy(accountBusy = false) }
                onDone(error.message ?: "Unable to delete account")
            }
        }
    }

    fun clearAccountError() {
        _state.update { it.copy(accountError = "") }
    }

    private fun currentSettings() = AccountSettings(
        notificationsEnabled = _state.value.notificationsEnabled,
        alertLeadMinutes = _state.value.alertLeadMinutes,
        sunriseAlerts = _state.value.sunriseAlerts,
        sunsetAlerts = _state.value.sunsetAlerts,
        alertThreshold = _state.value.alertThreshold,
        forecastImprovedAlerts = _state.value.forecastImprovedAlerts,
        postEventFeedbackAlerts = _state.value.postEventFeedbackAlerts,
        quietHoursEnabled = _state.value.quietHoursEnabled,
        quietHoursStart = _state.value.quietHoursStart,
        quietHoursEnd = _state.value.quietHoursEnd,
        disabledAlertPlaceKeys = _state.value.disabledAlertPlaceKeys,
    )

    private fun pushCloudSettings() {
        val token = _state.value.accountToken
        if (token.isBlank() || _state.value.account == null) return
        settingsSyncJob?.cancel()
        settingsSyncJob = viewModelScope.launch {
            delay(400)
            runCatching {
                api.updateAccount(token, currentSettings(), null)
                _state.value.account?.let { store.cachedAccount = it.copy(settings = currentSettings()) }
                _state.update { it.copy(lastSyncedAt = System.currentTimeMillis()) }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { it.copy(accountError = error.message ?: "Cloud sync failed") }
            }
        }
    }

    private fun scheduleSyncPush() {
        val token = _state.value.accountToken
        if (token.isBlank() || _state.value.account == null) return
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(700)
            runCatching {
                api.putSync(token, _state.value.favorites, _state.value.viewpoints)
                _state.update { it.copy(lastSyncedAt = System.currentTimeMillis()) }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { it.copy(accountError = error.message ?: "Cloud sync failed") }
            }
        }
    }

    // ---- Viewpoints & feedback ------------------------------------------

    fun saveViewpoint(name: String, photo: android.net.Uri? = null) {
        val place = _state.value.place
        saveViewpointAt(name, place.latitude, place.longitude, photo)
    }

    fun saveViewpointAt(name: String, latitude: Double, longitude: Double, photo: android.net.Uri? = null) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // Resizing happens off the main thread; the viewpoint still saves if the photo can't be used.
            val processed = photo?.let { ViewpointPhotos.import(getApplication(), it) }
            if (photo != null && processed == null) {
                android.widget.Toast.makeText(getApplication(), "That photo couldn't be used. Saved without it.", android.widget.Toast.LENGTH_LONG).show()
            }
            val viewpoint = Viewpoint(
                id = java.util.UUID.randomUUID().toString(),
                name = trimmed,
                latitude = latitude,
                longitude = longitude,
                updatedAt = System.currentTimeMillis(),
                photoId = processed?.id,
                photoUploaded = processed == null,
            )
            fun sameSpot(other: Viewpoint) = abs(other.latitude - latitude) < 0.0001 && abs(other.longitude - longitude) < 0.0001
            val previous = _state.value.viewpoints
            val next = (listOf(viewpoint) + previous.filterNot(::sameSpot)).take(30)
            // The viewpoint saved here before is replaced, so its photo has no owner left.
            previous.filter(::sameSpot).mapNotNull { it.photoId }.forEach { ViewpointPhotos.delete(getApplication(), it) }
            store.viewpoints = next
            _state.update { it.copy(viewpoints = next) }
            scheduleSyncPush()
            if (processed != null) uploadPendingPhotos()
        }
    }

    private var photoUploadJob: Job? = null

    /** Sends photos that only exist on this device. Safe to call often; it retries whatever is still pending. */
    fun uploadPendingPhotos() {
        val token = _state.value.accountToken
        if (token.isBlank() || photoUploadJob?.isActive == true) return
        photoUploadJob = viewModelScope.launch {
            var uploadedAny = false
            for (viewpoint in _state.value.viewpoints) {
                val id = viewpoint.photoId ?: continue
                if (viewpoint.photoUploaded) continue
                val file = ViewpointPhotos.file(getApplication(), id)
                if (!file.exists()) continue
                try {
                    val (bytes, size) = withContext(Dispatchers.IO) {
                        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        android.graphics.BitmapFactory.decodeFile(file.path, bounds)
                        file.readBytes() to (bounds.outWidth to bounds.outHeight)
                    }
                    api.uploadViewpointPhoto(token, id, bytes, size.first, size.second)
                    markPhotoUploaded(id)
                    uploadedAny = true
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Stays pending; retried on resume and after sign-in.
                }
            }
            // The cloud copy of the viewpoint only names its photo once the image is there.
            if (uploadedAny) scheduleSyncPush()
        }
    }

    private fun markPhotoUploaded(photoId: String) {
        val next = _state.value.viewpoints.map { if (it.photoId == photoId) it.copy(photoUploaded = true) else it }
        store.viewpoints = next
        _state.update { it.copy(viewpoints = next) }
    }

    fun openViewpoint(viewpoint: Viewpoint) {
        selectPlace(_state.value.place.copy(name = viewpoint.name, latitude = viewpoint.latitude, longitude = viewpoint.longitude))
    }

    // ---- Reactions -----------------------------------------------------------
    //
    // Like and dislike apply instantly and are remembered on the device. The server copy is
    // best effort: a reaction made offline, or before signing in, stays queued and is sent on
    // the next resume or sign-in, so tapping never shows an error.

    private val reactionLock = Mutex()
    private val countsFetchedAt = mutableMapOf<String, Long>()

    /** Tapping the active reaction withdraws it; tapping the other one switches. */
    fun toggleReaction(sentiment: String) {
        val current = _state.value
        val key = current.reactionKey ?: return
        val next = if (current.reaction.sentiment == sentiment) null else sentiment
        commitReaction(key, next, reason = null)
        api.telemetry("forecast_feedback", JSONObject().put("kind", "sentiment").put("value", next ?: "cleared"))
    }

    /** The optional "what was off?" answer to a dislike; tapping it again clears it. */
    fun setReactionReason(reason: String) {
        val current = _state.value
        val key = current.reactionKey ?: return
        if (current.reaction.sentiment != "dislike") return
        commitReaction(key, "dislike", if (current.reaction.reason == reason) null else reason)
        api.telemetry("forecast_feedback", JSONObject().put("kind", "reason").put("value", reason))
    }

    private fun commitReaction(key: String, sentiment: String?, reason: String?) {
        val current = _state.value
        val outlook = current.selectedOutlook ?: return
        val mode = current.activeMode
        val entry = JSONObject()
            .put("day", key.substringBefore(':'))
            .put("mode", mode)
            .put("place", "${current.place.name}${if (current.place.admin1.isNotBlank()) " · ${current.place.admin1}" else ""}")
            .put("probability", Math.round(outlook.scores[mode].probability))
            .put("eventAt", outlook[mode].toEpochMilli())
            .put("latitude", current.place.latitude)
            .put("longitude", current.place.longitude)
            .put("sentiment", sentiment ?: JSONObject.NULL)
            .put("reason", reason ?: JSONObject.NULL)
            .put("synced", false)
            .put("updatedAt", System.currentTimeMillis())
        val nextStore = JSONObject(current.feedbackStore.toString()).put(key, entry)
        store.feedback = nextStore
        _state.update { it.copy(feedbackStore = nextStore) }
        pushReaction(key)
    }

    private fun pushReaction(key: String) {
        val token = _state.value.accountToken
        if (token.isBlank()) return
        viewModelScope.launch {
            reactionLock.withLock {
                // Read inside the lock so a quick second tap sends the latest choice, not a stale one.
                val entry = _state.value.feedbackStore.optJSONObject(key) ?: return@withLock
                if (entry.optBoolean("synced", true) || !entry.has("eventAt")) return@withLock
                val stamp = entry.optLong("updatedAt")
                try {
                    val summary = api.postFeedback(
                        token = token,
                        eventAt = entry.getLong("eventAt"),
                        mode = entry.optString("mode", "sunset"),
                        placeName = entry.optString("place"),
                        latitude = entry.getDouble("latitude"),
                        longitude = entry.getDouble("longitude"),
                        probability = entry.optDouble("probability"),
                        sentiment = entry.optNullableString("sentiment"),
                        reason = entry.optNullableString("reason"),
                    )
                    markReactionSynced(key, stamp, summary)
                } catch (error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // Stays queued; syncPendingReactions retries on resume and after sign-in.
                }
            }
        }
    }

    private fun markReactionSynced(key: String, stamp: Long, summary: ReactionSummary) {
        val current = _state.value
        val entry = current.feedbackStore.optJSONObject(key)
        // A newer tap since this push began keeps its own pending state.
        val nextStore = if (entry != null && entry.optLong("updatedAt") == stamp) {
            JSONObject(current.feedbackStore.toString()).put(key, JSONObject(entry.toString()).put("synced", true))
        } else {
            current.feedbackStore
        }
        store.feedback = nextStore
        _state.update { it.copy(feedbackStore = nextStore, reactionCounts = it.reactionCounts + (key to summary)) }
    }

    fun syncPendingReactions() {
        val reactions = _state.value.feedbackStore
        reactions.keys().forEach { key ->
            val entry = reactions.optJSONObject(key) ?: return@forEach
            if (!entry.optBoolean("synced", true)) pushReaction(key)
        }
    }

    /** Loads the community totals (and this account's own reaction) for the event on screen. */
    fun refreshReactionCounts() {
        val current = _state.value
        val key = current.reactionKey ?: return
        val outlook = current.selectedOutlook ?: return
        val now = System.currentTimeMillis()
        if (current.reactionCounts.containsKey(key) && now - (countsFetchedAt[key] ?: 0L) < REACTION_COUNTS_TTL_MS) return
        countsFetchedAt[key] = now
        val mode = current.activeMode
        val place = current.place
        val token = current.accountToken
        viewModelScope.launch {
            try {
                val summary = api.fetchReactions(token, outlook[mode].toEpochMilli(), mode, place.latitude, place.longitude)
                _state.update { it.copy(reactionCounts = it.reactionCounts + (key to summary)) }
                adoptServerReaction(key, mode, summary)
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                countsFetchedAt.remove(key)
            }
        }
    }

    /** A reaction made on another device (or before a reinstall) shows up here once signed in. */
    private fun adoptServerReaction(key: String, mode: String, summary: ReactionSummary) {
        val current = _state.value
        val sentiment = summary.mineSentiment ?: return
        if (current.feedbackStore.has(key)) return
        val entry = JSONObject()
            .put("day", key.substringBefore(':'))
            .put("mode", mode)
            .put("sentiment", sentiment)
            .put("reason", summary.mineReason ?: JSONObject.NULL)
            .put("synced", true)
            .put("updatedAt", System.currentTimeMillis())
        val nextStore = JSONObject(current.feedbackStore.toString()).put(key, entry)
        store.feedback = nextStore
        _state.update { it.copy(feedbackStore = nextStore) }
    }

    /** What the share sheet needs for the forecast on screen: the facts, and a link that opens the same place and event. */
    fun shareContent(): ShareContent? {
        val current = _state.value
        val outlook = current.selectedOutlook ?: return null
        val mode = current.activeMode
        val window = outlook.appearanceWindow[mode]
        val windowRange = if (window.start != null && window.end != null) formatRange(window.start, window.end, outlook.timeZone) else null
        val dateLine = runCatching {
            java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())
                .withZone(java.time.ZoneId.of(outlook.timeZone))
                .format(outlook[mode])
        }.getOrDefault("")
        val link = android.net.Uri.parse(BuildConfig.API_BASE_URL).buildUpon()
            .appendQueryParameter("lat", String.format(Locale.US, "%.4f", current.place.latitude))
            .appendQueryParameter("lon", String.format(Locale.US, "%.4f", current.place.longitude))
            .appendQueryParameter("name", current.place.name)
            .apply { if (current.place.admin1.isNotBlank()) appendQueryParameter("region", current.place.admin1) }
            .appendQueryParameter("mode", mode)
            .appendQueryParameter("utm_source", "android_share")
            .build()
            .toString()
        api.telemetry("forecast_shared", JSONObject().put("mode", mode).put("score", Math.round(outlook.scores[mode].probability)))
        return ShareContent(
            placeName = current.place.name,
            region = current.place.admin1,
            mode = mode,
            probability = Math.round(outlook.scores[mode].probability).toInt(),
            windowRange = windowRange,
            dateLine = dateLine,
            link = link,
        )
    }

    fun selectMapSample(sample: ScoredPoint?) {
        _state.update { it.copy(selectedSample = sample) }
    }

    fun onAppForeground() {
        foreground = true
        if (_state.value.offline != !isOnline()) {
            _state.update { it.copy(offline = !isOnline()) }
        }
        syncPendingReactions()
        uploadPendingPhotos()
        val current = _state.value
        val updatedAt = current.forecastUpdatedAt
        if (!current.loading && updatedAt != null && System.currentTimeMillis() - updatedAt > STALE_ON_RESUME_MS) {
            load(current.place, current.activeMode, silent = true)
        } else if (current.days.isNotEmpty() && !userSelectedDay && !userSelectedMode) {
            // Hours can pass in the background: roll over to the event that is next now.
            val (dayIndex, mode) = pickEvent(current.days, keepSelection = true)
            if (dayIndex != current.selectedDayIndex || mode != current.activeMode) {
                _state.update {
                    it.copy(selectedDayIndex = dayIndex, activeMode = mode, grid = gridFor(mode, dayIndex))
                }
                recordSelectedEvent()
            }
        }
    }

    fun onAppBackground() {
        foreground = false
    }

    /** Opens the forecast an alert was about: its place, its mode, and the day of that event. */
    fun openFromAlert(place: Place?, mode: String, eventAt: Long) {
        if (mode !in listOf("sunrise", "sunset")) return
        val current = _state.value
        val target = place ?: displayedPlace ?: current.place
        if (eventAt > 0) pendingAlertEvent = mode to eventAt
        userSelectedMode = true
        _state.update { it.copy(activeMode = mode, showAccount = false, showSearch = false) }
        if (displayedPlace?.sameSpot(target) == true && current.days.isNotEmpty()) {
            val (dayIndex, nextMode) = pickEvent(current.days, keepSelection = true)
            _state.update { it.copy(selectedDayIndex = dayIndex, activeMode = nextMode, grid = gridFor(nextMode, dayIndex)) }
            recordSelectedEvent()
        } else {
            selectPlace(target)
        }
    }

    fun onNotificationMode(mode: String) = openFromAlert(null, mode, 0L)

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
        networkCallback?.let { runCatching { connectivity.unregisterNetworkCallback(it) } }
    }

    private companion object {
        const val REFRESH_INTERVAL_MS = 90L * 60L * 1000L
        const val STALE_ON_RESUME_MS = 30L * 60L * 1000L
        const val REACTION_COUNTS_TTL_MS = 60L * 1000L

        fun randomUrlSafe(bytes: Int): String {
            val buffer = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}
