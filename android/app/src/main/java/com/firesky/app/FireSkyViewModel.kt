package com.firesky.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firesky.app.data.AccountSettings
import com.firesky.app.data.AccountState
import com.firesky.app.data.ApiClient
import com.firesky.app.data.ApiException
import com.firesky.app.data.DEFAULT_PLACE
import com.firesky.app.data.Forecast
import com.firesky.app.data.ForecastSnapshot
import com.firesky.app.data.Place
import com.firesky.app.data.ScoredPoint
import com.firesky.app.data.Store
import com.firesky.app.data.optNullableString
import com.firesky.app.data.Viewpoint
import com.firesky.app.data.applyRegionalContextToForecast
import com.firesky.app.data.buildForecastDays
import com.firesky.app.data.compassLabel
import com.firesky.app.data.forecastDayKey
import com.firesky.app.data.formatRange
import com.firesky.app.data.localIsoToUtcDate
import com.firesky.app.data.preferredForecastMode
import com.firesky.app.data.sunAzimuth
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SearchState(
    val query: String = "",
    val results: List<Place> = emptyList(),
    val searching: Boolean = false,
    val error: String = "",
)

data class UiState(
    val place: Place = DEFAULT_PLACE,
    val activeMode: String = "sunset",
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
    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow(
        UiState(
            favorites = store.favorites,
            notificationsEnabled = store.notificationsEnabled,
            alertLeadMinutes = store.alertLeadMinutes,
            sunriseAlerts = store.sunriseAlerts,
            sunsetAlerts = store.sunsetAlerts,
            alertThreshold = store.alertThreshold,
            accountToken = store.accountToken,
            feedbackStore = store.feedback,
            offline = !isOnline(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadSeq = 0
    private var userSelectedMode = false
    private var snapshotFingerprint = ""
    private var settingsSyncJob: Job? = null
    private var syncJob: Job? = null
    private var searchJob: Job? = null
    private var refreshJob: Job? = null

    init {
        registerNetworkCallback()
        _state.value.accountToken.takeIf { it.isNotBlank() }?.let { acceptAccountToken(it) }
        load(DEFAULT_PLACE, "sunset")
        refreshJob = viewModelScope.launch {
            while (isActive) {
                delay(90L * 60L * 1000L)
                load(_state.value.place, _state.value.activeMode, force = true, silent = true)
            }
        }
    }

    private fun isOnline(): Boolean {
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun registerNetworkCallback() {
        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _state.update { it.copy(offline = false) }
                }

                override fun onLost(network: Network) {
                    _state.update { it.copy(offline = !isOnline()) }
                }
            })
        } catch (_: Exception) {
            // Some restricted devices do not allow default network callbacks.
        }
    }

    fun selectPlace(place: Place) {
        _state.update {
            it.copy(
                place = place,
                selectedDayIndex = 0,
                showSearch = false,
                selectedSample = null,
            )
        }
        api.telemetry(
            "location_selected",
            JSONObject().put("source", if (place.name == "Current Location") "location" else "search"),
        )
        load(place, _state.value.activeMode)
    }

    fun selectMode(mode: String) {
        userSelectedMode = true
        if (_state.value.activeMode == mode) return
        _state.update { it.copy(activeMode = mode, selectedSample = null) }
        val current = _state.value
        val data = current.data ?: return
        viewModelScope.launch {
            try {
                val grid = api.fetchGrid(current.place, mode)
                _state.update {
                    it.copy(
                        grid = grid,
                        data = applyRegionalContextToForecast(data, grid, current.place),
                    )
                }
            } catch (_: Exception) {
                _state.update { it.copy(grid = null) }
            }
        }
    }

    fun selectDay(index: Int) {
        _state.update { it.copy(selectedDayIndex = index) }
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

    fun load(place: Place, mode: String, force: Boolean = false, silent: Boolean = false) {
        val seq = ++loadSeq
        if (!silent) _state.update { it.copy(loading = true, error = "") }
        else _state.update { it.copy(error = "") }
        viewModelScope.launch {
            try {
                val bundle = api.fetchForecast(place, force)
                val forecast = com.firesky.app.data.buildForecast(bundle)
                val modeForGrid = if (userSelectedMode) mode else preferredForecastMode(forecast)
                if (seq != loadSeq) return@launch
                if (!userSelectedMode && modeForGrid != _state.value.activeMode) {
                    _state.update { it.copy(activeMode = modeForGrid) }
                }
                _state.update {
                    it.copy(
                        data = forecast,
                        days = buildForecastDays(bundle),
                        grid = null,
                        loading = false,
                    )
                }
                persistSnapshot(forecast)
                scheduleAlerts()
                try {
                    val grid = api.fetchGrid(place, modeForGrid, force)
                    if (seq != loadSeq) return@launch
                    _state.update {
                        it.copy(
                            grid = grid,
                            data = applyRegionalContextToForecast(forecast, grid, place),
                        )
                    }
                    persistSnapshot(_state.value.data ?: forecast)
                    loadForecastHistory()
                } catch (gridError: Exception) {
                    if (seq == loadSeq) {
                        _state.update { it.copy(error = "Regional map is temporarily unavailable; local weather is still current.") }
                    }
                }
            } catch (error: Exception) {
                if (seq == loadSeq) {
                    _state.update {
                        it.copy(
                            loading = false,
                            error = error.message ?: "Loading failed",
                        )
                    }
                }
            }
        }
    }

    private fun persistSnapshot(forecast: Forecast) {
        val mode = _state.value.activeMode
        val score = forecast.scores[mode]
        val event = forecast[mode]
        val place = _state.value.place
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
        val snapshots = persistLocalSnapshot(snapshot)
        _state.update { it.copy(forecastSnapshots = snapshots) }
        val token = _state.value.accountToken
        if (token.isNotBlank()) {
            viewModelScope.launch { runCatching { api.postSnapshot(token, snapshot) } }
        }
    }

    private fun persistLocalSnapshot(snapshot: ForecastSnapshot): List<ForecastSnapshot> {
        val storeJson = store.snapshots
        val key = "${snapshot.mode}:${snapshot.eventAt}:${snapshot.latitude}:${snapshot.longitude}"
        val previous = storeJson.optJSONArray(key)?.let { array ->
            List(array.length()) { index -> ForecastSnapshot.fromJson(array.getJSONObject(index)) }
        } ?: emptyList()
        val cutoff = System.currentTimeMillis() - 10L * 86400000L
        val next = (previous.filter { it.calculatedAt >= cutoff && it.calculatedAt != snapshot.calculatedAt } + snapshot)
            .sortedBy { it.calculatedAt }
            .takeLast(96)
        val array = JSONArray()
        next.forEach { array.put(it.toJson()) }
        storeJson.put(key, array)
        store.snapshots = storeJson
        return next
    }

    private fun loadForecastHistory() {
        val current = _state.value
        val outlook = current.data ?: return
        val mode = current.activeMode
        val eventAt = outlook[mode].toEpochMilli()
        val key = "$mode:$eventAt:${String.format(Locale.US, "%.4f", current.place.latitude).toDouble()}:${String.format(Locale.US, "%.4f", current.place.longitude).toDouble()}"
        val local = store.snapshots.optJSONArray(key)?.let { array ->
            List(array.length()) { index -> ForecastSnapshot.fromJson(array.getJSONObject(index)) }
        } ?: emptyList()
        _state.update { it.copy(forecastSnapshots = local) }
        val token = current.accountToken
        if (token.isBlank()) return
        viewModelScope.launch {
            runCatching { api.fetchForecastHistory(token, eventAt, mode) }
                .onSuccess { snapshots ->
                    if (snapshots.isNotEmpty()) _state.update { it.copy(forecastSnapshots = snapshots) }
                }
        }
    }

    fun scheduleAlerts() {
        val current = _state.value
        val forecast = current.data ?: return
        SkyAlerts.schedule(
            getApplication(),
            forecast,
            current.place.name,
            AccountSettings(
                notificationsEnabled = current.notificationsEnabled,
                alertLeadMinutes = current.alertLeadMinutes,
                sunriseAlerts = current.sunriseAlerts,
                sunsetAlerts = current.sunsetAlerts,
                alertThreshold = current.alertThreshold,
            ),
        )
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
                _state.update { it.copy(search = it.search.copy(searching = false, results = results)) }
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
                store.notificationsEnabled = account.settings.notificationsEnabled
                store.alertLeadMinutes = account.settings.alertLeadMinutes
                store.sunriseAlerts = account.settings.sunriseAlerts
                store.sunsetAlerts = account.settings.sunsetAlerts
                store.alertThreshold = account.settings.alertThreshold
                if (sync.locations.isNotEmpty()) store.favorites = sync.locations
                _state.update {
                    it.copy(
                        account = account,
                        accountBusy = false,
                        favorites = if (sync.locations.isNotEmpty()) sync.locations else it.favorites,
                        viewpoints = sync.viewpoints,
                        notificationsEnabled = account.settings.notificationsEnabled,
                        alertLeadMinutes = account.settings.alertLeadMinutes,
                        sunriseAlerts = account.settings.sunriseAlerts,
                        sunsetAlerts = account.settings.sunsetAlerts,
                        alertThreshold = account.settings.alertThreshold,
                        lastSyncedAt = System.currentTimeMillis(),
                    )
                }
                scheduleAlerts()
            } catch (error: Exception) {
                store.accountToken = ""
                _state.update {
                    it.copy(
                        accountToken = "",
                        account = null,
                        accountBusy = false,
                        accountError = error.message ?: "Unable to load account",
                    )
                }
            }
        }
    }

    fun signInWithPassword(action: String, email: String, password: String, displayName: String?) {
        _state.update { it.copy(accountBusy = true, accountError = "") }
        viewModelScope.launch {
            try {
                val token = api.passwordAuth(action, email, password, displayName)
                acceptAccountToken(token)
            } catch (error: Exception) {
                _state.update { it.copy(accountBusy = false, accountError = error.message ?: "Sign in failed") }
            }
        }
    }

    fun startGoogleSignIn(onUrl: (String) -> Unit) {
        _state.update { it.copy(accountError = "", accountBusy = true) }
        viewModelScope.launch {
            try {
                val url = api.authStart("google", "com.firesky.app://auth")
                _state.update { it.copy(accountBusy = false) }
                onUrl(url)
            } catch (error: Exception) {
                _state.update { it.copy(accountBusy = false, accountError = error.message ?: "Sign in failed") }
            }
        }
    }

    fun signOut() {
        store.accountToken = ""
        _state.update { it.copy(accountToken = "", account = null, showAccount = false, viewpoints = emptyList()) }
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
                    it.copy(
                        account = it.account?.copy(user = user ?: it.account.user),
                        accountBusy = false,
                        lastSyncedAt = System.currentTimeMillis(),
                    )
                }
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
            } catch (error: Exception) {
                onDone(error.message ?: "Unable to update password")
            }
        }
    }

    fun deleteAccount(onDone: (String?) -> Unit) {
        val token = _state.value.accountToken
        if (token.isBlank()) return
        viewModelScope.launch {
            try {
                api.deleteAccount(token)
                signOut()
                onDone(null)
            } catch (error: Exception) {
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
                _state.update { it.copy(lastSyncedAt = System.currentTimeMillis()) }
            }.onFailure { error ->
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
            kotlin.math.abs(it.latitude - place.latitude) < 0.0001 &&
                kotlin.math.abs(it.longitude - place.longitude) < 0.0001
        }).take(30)
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
        if (_state.value.offline != !isOnline()) {
            _state.update { it.copy(offline = !isOnline()) }
        }
    }

    fun onNotificationMode(mode: String) {
        if (mode in listOf("sunrise", "sunset")) {
            userSelectedMode = true
            _state.update { it.copy(activeMode = mode) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        refreshJob?.cancel()
    }
}
