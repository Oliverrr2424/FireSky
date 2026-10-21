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
import com.firesky.app.data.DEFAULT_PLACE
import com.firesky.app.data.Forecast
import com.firesky.app.data.ForecastRepository
import com.firesky.app.data.ForecastSnapshot
import com.firesky.app.data.GridKey
import com.firesky.app.data.Place
import com.firesky.app.data.ScoredPoint
import com.firesky.app.data.Store
import com.firesky.app.data.Viewpoint
import com.firesky.app.data.forecastDayKey
import com.firesky.app.data.formatRange
import com.firesky.app.data.optNullableString
import com.firesky.app.data.sunAzimuth
import com.firesky.app.data.upcomingEvents
import com.firesky.app.data.weatherDescription
import com.firesky.app.data.weatherTheme
import com.firesky.app.notifications.SkyAlerts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import kotlin.math.abs

data class SearchState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val error: String = "",
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
    val accountToken: String = "",
    val account: AccountState? = null,
    val accountError: String = "",
    val viewpoints: List<Viewpoint> = emptyList(),
    val forecastSnapshots: List<ForecastSnapshot> = emptyList(),
    val feedbackStore: JSONObject = JSONObject(),
    val lastSyncedAt: Long? = null,
    val feedbackEditing: Boolean = false,
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
    val sunsetFeedback: JSONObject? get() {
        val outlook = data ?: return null
        val key = forecastDayKey(outlook) ?: return null
        return feedbackStore.optJSONObject(key)
    }
    val sunsetDay: String? get() = data?.let { forecastDayKey(it) }
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
    private var snapshotFingerprint = ""
    private var settingsSyncJob: Job? = null
    private var syncJob: Job? = null
    private var searchJob: Job? = null
    private var refreshJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerNetworkCallback()
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

    /** Records the selected event's score in the forecast-change history and loads that history. */
    private fun recordSelectedEvent() {
        val current = _state.value
        val outlook = current.selectedOutlook ?: return
        // History is only meaningful for events the regional model can score.
        if (current.selectedDayIndex > 1) {
            _state.update { it.copy(forecastSnapshots = emptyList()) }
            return
        }
        persistSnapshot(outlook, current.activeMode)
        loadForecastHistory(outlook, current.activeMode)
    }

    private fun persistSnapshot(forecast: Forecast, mode: String) {
        val score = forecast.scores[mode]
        val event = forecast[mode]
        val place = displayedPlace ?: return
        val calculatedAt = (System.currentTimeMillis() / (15L * 60L * 1000L)) * 15L * 60L * 1000L
        val snapshot = ForecastSnapshot(
            mode = mode,
            eventAt = event.toEpochMilli(),
            latitude = String.format(Locale.US, "%.4f", place.latitude).toDouble(),
            longitude = String.format(Locale.US, "%.4f", place.longitude).toDouble(),
            calculatedAt = calculatedAt,
            probability = score.probability,
            quality = score.quality,
            modelVersion = forecast.ml?.modelVersion ?: "rules",
        )
        val fingerprint = "${snapshot.mode}:${snapshot.eventAt}:${snapshot.latitude}:${snapshot.longitude}:${snapshot.calculatedAt}:${Math.round(snapshot.probability)}"
        if (snapshotFingerprint == fingerprint) return
        snapshotFingerprint = fingerprint
        persistLocalSnapshot(snapshot)
        val token = _state.value.accountToken
        if (token.isNotBlank()) {
            viewModelScope.launch { runCatching { api.postSnapshot(token, snapshot) } }
        }
    }

    private fun persistLocalSnapshot(snapshot: ForecastSnapshot) {
        val storeJson = store.snapshots
        val key = snapshotKey(snapshot.mode, snapshot.eventAt, snapshot.latitude, snapshot.longitude)
        val previous = storeJson.optJSONArray(key)?.let { array ->
            List(array.length()) { index -> ForecastSnapshot.fromJson(array.getJSONObject(index)) }
        } ?: emptyList()
        val cutoff = System.currentTimeMillis() - SNAPSHOT_RETENTION_MS
        val next = (previous.filter { it.calculatedAt >= cutoff && it.calculatedAt != snapshot.calculatedAt } + snapshot)
            .sortedBy { it.calculatedAt }
            .takeLast(96)
        val array = JSONArray()
        next.forEach { array.put(it.toJson()) }
        storeJson.put(key, array)
        // Drop whole histories for events that are long past, so the store doesn't grow forever.
        storeJson.keys().asSequence().toList().forEach { existing ->
            val eventAt = existing.split(':').getOrNull(1)?.toLongOrNull()
            if (eventAt == null || eventAt < cutoff) storeJson.remove(existing)
        }
        store.snapshots = storeJson
    }

    private fun snapshotKey(mode: String, eventAt: Long, latitude: Double, longitude: Double): String =
        "$mode:$eventAt:${String.format(Locale.US, "%.4f", latitude).toDouble()}:${String.format(Locale.US, "%.4f", longitude).toDouble()}"

    private fun loadForecastHistory(outlook: Forecast, mode: String) {
        val place = displayedPlace ?: return
        val eventAt = outlook[mode].toEpochMilli()
        val key = snapshotKey(mode, eventAt, place.latitude, place.longitude)
        val local = store.snapshots.optJSONArray(key)?.let { array ->
            List(array.length()) { index -> ForecastSnapshot.fromJson(array.getJSONObject(index)) }
        } ?: emptyList()
        _state.update { it.copy(forecastSnapshots = local) }
        val token = _state.value.accountToken
        if (token.isBlank()) return
        viewModelScope.launch {
            runCatching { api.fetchForecastHistory(token, eventAt, mode) }
                .onSuccess { snapshots ->
                    val stillSelected = _state.value.selectedOutlook?.get(_state.value.activeMode)?.toEpochMilli() == eventAt
                    if (snapshots.isNotEmpty() && stillSelected) _state.update { it.copy(forecastSnapshots = snapshots) }
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
                val viewpoints = (sync.viewpoints + localOnly).take(30)
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
                scheduleAlerts()
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

    fun saveViewpoint(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        val place = _state.value.place
        val viewpoint = Viewpoint(
            id = java.util.UUID.randomUUID().toString(),
            name = trimmed,
            latitude = place.latitude,
            longitude = place.longitude,
            updatedAt = System.currentTimeMillis(),
        )
        val next = (listOf(viewpoint) + _state.value.viewpoints.filterNot {
            abs(it.latitude - place.latitude) < 0.0001 && abs(it.longitude - place.longitude) < 0.0001
        }).take(30)
        store.viewpoints = next
        _state.update { it.copy(viewpoints = next) }
        scheduleSyncPush()
    }

    fun openViewpoint(viewpoint: Viewpoint) {
        selectPlace(_state.value.place.copy(name = viewpoint.name, latitude = viewpoint.latitude, longitude = viewpoint.longitude))
    }

    fun toggleReaction(sentiment: String) {
        val current = _state.value
        val day = current.sunsetDay ?: return
        val data = current.data ?: return
        val existing = current.feedbackStore.optJSONObject(day)
        val currentSentiment = existing?.optNullableString("sentiment")
        val next = if (currentSentiment == sentiment) null else sentiment
        updateFeedback(
            sentiment = next,
            accurate = when (next) {
                null -> null
                "like" -> true
                else -> false
            },
        )
        if (next != null) _state.update { it.copy(feedbackEditing = false) }
        api.telemetry("forecast_feedback", JSONObject().put("kind", "sentiment").put("value", next ?: "cleared"))
        val token = current.accountToken
        if (token.isNotBlank()) {
            viewModelScope.launch {
                runCatching {
                    api.postFeedback(
                        token = token,
                        eventAt = data.sunset.toEpochMilli(),
                        mode = "sunset",
                        placeName = "${current.place.name}${if (current.place.admin1.isNotBlank()) " · ${current.place.admin1}" else ""}",
                        latitude = current.place.latitude,
                        longitude = current.place.longitude,
                        probability = data.scores.sunset.probability,
                        outcome = when (next) {
                            null -> "unknown"
                            "like" -> "yes"
                            else -> "no"
                        },
                        sentiment = next,
                    )
                }.onFailure { error ->
                    _state.update { it.copy(accountError = error.message ?: "Feedback sync failed") }
                }
            }
        }
    }

    private fun updateFeedback(sentiment: String?, accurate: Boolean?) {
        val current = _state.value
        val day = current.sunsetDay ?: return
        val data = current.data ?: return
        val existing = current.feedbackStore.optJSONObject(day) ?: JSONObject()
        val nextEntry = JSONObject(existing.toString())
            .put("day", day)
            .put("place", "${current.place.name}${if (current.place.admin1.isNotBlank()) " · ${current.place.admin1}" else ""}")
            .put("probability", Math.round(data.scores.sunset.probability))
            .put("updatedAt", System.currentTimeMillis())
        if (sentiment == null) nextEntry.put("sentiment", JSONObject.NULL) else nextEntry.put("sentiment", sentiment)
        if (accurate == null) nextEntry.put("accurate", JSONObject.NULL) else nextEntry.put("accurate", accurate)
        val nextStore = JSONObject(current.feedbackStore.toString()).put(day, nextEntry)
        store.feedback = nextStore
        _state.update { it.copy(feedbackStore = nextStore) }
    }

    fun setFeedbackEditing(editing: Boolean) {
        _state.update { it.copy(feedbackEditing = editing) }
    }

    fun noteFeedbackForForecast() {
        val current = _state.value
        val data = current.data ?: return
        val day = forecastDayKey(data) ?: return
        val existing = current.feedbackStore.optJSONObject(day) ?: JSONObject()
        val nextEntry = JSONObject(existing.toString())
            .put("probability", Math.round(data.scores.sunset.probability))
            .put("day", day)
            .put("place", "${current.place.name}${if (current.place.admin1.isNotBlank()) " · ${current.place.admin1}" else ""}")
            .put("updatedAt", System.currentTimeMillis())
        val nextStore = JSONObject(current.feedbackStore.toString()).put(day, nextEntry)
        store.feedback = nextStore
        _state.update { it.copy(feedbackStore = nextStore) }
    }

    fun shareText(): String {
        val current = _state.value
        val outlook = current.selectedOutlook ?: return ""
        val score = Math.round(outlook.scores[current.activeMode].probability)
        val window = outlook.appearanceWindow[current.activeMode]
        val title = "${current.place.name}${if (current.place.admin1.isNotBlank()) " · ${current.place.admin1}" else ""}"
        val modeLabel = if (current.activeMode == "sunset") "Sunset" else "Sunrise"
        val windowText = if (window.start != null && window.end != null) {
            " · Best window ${formatRange(window.start, window.end, outlook.timeZone)}"
        } else {
            ""
        }
        return "$title: $modeLabel chance is $score%$windowText."
    }

    fun selectMapSample(sample: ScoredPoint?) {
        _state.update { it.copy(selectedSample = sample) }
    }

    fun onAppForeground() {
        foreground = true
        if (_state.value.offline != !isOnline()) {
            _state.update { it.copy(offline = !isOnline()) }
        }
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
        const val SNAPSHOT_RETENTION_MS = 10L * 86_400_000L

        fun randomUrlSafe(bytes: Int): String {
            val buffer = ByteArray(bytes).also { SecureRandom().nextBytes(it) }
            return Base64.encodeToString(buffer, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        }
    }
}
