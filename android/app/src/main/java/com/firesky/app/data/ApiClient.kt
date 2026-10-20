package com.firesky.app.data

import com.firesky.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String, val retryable: Boolean = false, val statusCode: Int = 0) : Exception(message)

class ApiClient(private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(12, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gridClient: OkHttpClient = client.newBuilder()
        .readTimeout(45, TimeUnit.SECONDS)
        .callTimeout(52, TimeUnit.SECONDS)
        .build()

    private val pending = mutableMapOf<String, Deferred<String>>()
    private val pendingLock = Any()

    val baseUrl: String = BuildConfig.API_BASE_URL

    private fun cacheKey(prefix: String, place: Place): String {
        val step = if (prefix == "grid") 0.1 else 0.05
        val lat = roundedCoordinate(place.latitude, step)
        val lon = roundedCoordinate(place.longitude, step)
        return "firesky:v7:$prefix:$lat:$lon"
    }

    private fun roundedCoordinate(value: Double, step: Double): String {
        val rounded = Math.round(value / step) * step
        return String.format(java.util.Locale.US, if (step >= 0.1) "%.1f" else "%.2f", rounded)
    }

    suspend fun fetchForecast(place: Place, force: Boolean = false): ForecastBundle {
        val key = cacheKey("forecast", place)
        if (!force) {
            store.cacheRead(key, CACHE_TTL_MS)?.let { raw ->
                parseBundle(raw)?.let { return it }
            }
        }
        return try {
            val raw = requestRaw(
                "${baseUrl}/api/forecast?lat=${place.latitude}&lon=${place.longitude}",
                "Forecast",
                REQUEST_TIMEOUT_MS,
            )
            val bundle = parseBundle(raw) ?: throw ApiException("Forecast response was incomplete")
            store.cacheWrite(key, raw)
            bundle
        } catch (error: Exception) {
            val stale = store.cacheRead(key, STALE_TTL_MS)?.let { parseBundle(it) }
            if (stale != null) stale else throw error
        }
    }

    suspend fun fetchGrid(place: Place, mode: String, force: Boolean = false): List<ScoredPoint> {
        val key = cacheKey("grid", place)
        if (!force) {
            store.cacheRead(key, CACHE_TTL_MS)?.let { raw ->
                runCatching { buildGrid(JSONArray(raw), place, mode) }.getOrNull()?.let { return it }
            }
        }
        return try {
            val raw = requestRaw(
                "${baseUrl}/api/grid?lat=${place.latitude}&lon=${place.longitude}",
                "Regional grid",
                GRID_REQUEST_TIMEOUT_MS,
                gridClient,
            )
            val grid = buildGrid(JSONArray(raw), place, mode)
            store.cacheWrite(key, raw)
            grid
        } catch (error: Exception) {
            val stale = store.cacheRead(key, STALE_TTL_MS)?.let { raw ->
                runCatching { buildGrid(JSONArray(raw), place, mode) }.getOrNull()
            }
            stale ?: throw error
        }
    }

    suspend fun geocode(query: String): List<Place> {
        val normalized = query.trim()
        if (normalized.length < 2) return emptyList()
        val key = "firesky:v7:geocode:${normalized.lowercase()}"
        store.cacheRead(key, GEOCODE_CACHE_TTL_MS)?.let { raw ->
            return parsePlaces(JSONArray(raw))
        }
        val raw = requestRaw(
            "${baseUrl}/api/geocode?q=${java.net.URLEncoder.encode(normalized, "UTF-8")}",
            "Location search",
            REQUEST_TIMEOUT_MS,
        )
        store.cacheWrite(key, raw)
        return parsePlaces(JSONArray(raw))
    }

    private fun parseBundle(raw: String): ForecastBundle? {
        return try {
            val json = JSONObject(raw)
            val weather = WeatherPayload.fromJson(json.getJSONObject("weather"))
            if (!weather.isUsable) return null
            ForecastBundle(
                weather = weather,
                air = AirQuality(json.optJSONObject("air")),
                ml = MlPayload.fromJson(json.optJSONObject("ml")),
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePlaces(array: JSONArray): List<Place> = List(array.length()) { index ->
        Place.fromJson(array.getJSONObject(index))
    }

    private suspend fun requestRaw(
        url: String,
        label: String,
        timeoutMs: Long,
        httpClient: OkHttpClient = client,
    ): String {
        val existing = synchronized(pendingLock) {
            pending[url] ?: scope.async { performRequest(url, label, timeoutMs, httpClient) }
                .also { pending[url] = it }
        }
        return try {
            existing.await()
        } finally {
            synchronized(pendingLock) {
                if (pending[url] === existing) pending.remove(url)
            }
        }
    }

    private suspend fun performRequest(
        url: String,
        label: String,
        timeoutMs: Long,
        httpClient: OkHttpClient,
    ): String {
        val maxAttempts = 2
        var lastError: Exception? = null
        for (attempt in 1..maxAttempts) {
            val startedAt = System.currentTimeMillis()
            try {
                val response = httpClient.newCall(Request.Builder().url(url).get().build()).await()
                response.use { result ->
                    if (!result.isSuccessful) {
                        val retryAfter = result.header("Retry-After")
                        val suffix = if (retryAfter != null) "; retry after ${retryAfter}s" else ""
                        val retryable = result.code in RETRYABLE_STATUS_CODES
                        throw ApiException("$label request failed (${result.code}$suffix)", retryable, result.code)
                    }
                    val body = result.body?.string() ?: ""
                    telemetry(
                        "api_request",
                        JSONObject()
                            .put("route", routeOf(url))
                            .put("durationMs", System.currentTimeMillis() - startedAt)
                            .put("status", result.code),
                    )
                    return body
                }
            } catch (error: Exception) {
                lastError = error
                val timedOut = error is IOException && (
                    error.message?.contains("timeout", ignoreCase = true) == true ||
                        error.message?.contains("timed out", ignoreCase = true) == true
                    )
                if (timedOut) {
                    lastError = ApiException("$label request timed out", true)
                }
                val retryable = (lastError as? ApiException)?.retryable == true || timedOut
                if (attempt < maxAttempts && retryable) {
                    delay(300L * attempt)
                    continue
                }
                telemetry(
                    "api_error",
                    JSONObject()
                        .put("route", routeOf(url))
                        .put("durationMs", System.currentTimeMillis() - startedAt)
                        .put("reason", (lastError.message ?: label).take(120)),
                )
                throw lastError
            }
        }
        throw lastError ?: ApiException("$label request failed")
    }

    private fun routeOf(url: String): String = runCatching { java.net.URI(url).path }.getOrDefault("/")

    fun telemetry(event: String, detail: JSONObject = JSONObject()) {
        scope.launch {
            try {
                val payload = JSONObject()
                    .put("event", event)
                    .put("at", System.currentTimeMillis())
                    .put("installation", store.installationId)
                    .put("platform", "android")
                    .put("detail", detail)
                    .toString()
                val body = payload.toRequestBody("text/plain;charset=UTF-8".toMediaType())
                client.newCall(
                    Request.Builder().url("$baseUrl/api/telemetry").post(body).build(),
                ).await().close()
            } catch (_: Exception) {
                // Diagnostics must never affect the forecast experience.
            }
        }
    }

    // ---- Account ---------------------------------------------------------

    private suspend fun accountRequest(
        path: String,
        token: String,
        method: String = "GET",
        body: JSONObject? = null,
    ): JSONObject {
        val builder = Request.Builder().url("$baseUrl$path")
        if (token.isNotBlank()) builder.header("Authorization", "Bearer $token")
        val requestBody = body?.toString()?.toRequestBody(JSON_MEDIA_TYPE)
        when (method) {
            "POST" -> builder.post(requestBody ?: EMPTY_BODY)
            "PUT" -> builder.put(requestBody ?: EMPTY_BODY)
            "DELETE" -> builder.delete()
            else -> builder.get()
        }
        val response = client.newCall(builder.build()).await()
        response.use { result ->
            val text = result.body?.string().orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (!result.isSuccessful) {
                throw ApiException(payload.optString("error", "Cloud request failed"), false, result.code)
            }
            return payload
        }
    }

    suspend fun fetchAccount(token: String): AccountState {
        val payload = accountRequest("/api/account", token)
        val userJson = payload.optJSONObject("user") ?: throw ApiException("Account response was incomplete")
        val user = AccountUser(
            id = userJson.optString("id"),
            displayName = userJson.optString("displayName", "FireSky user"),
            email = userJson.optNullableString("email"),
            avatarUrl = userJson.optNullableString("avatarUrl"),
            hasPassword = userJson.optBoolean("hasPassword", false),
        )
        val settingsJson = payload.optJSONObject("settings")
        val settings = if (settingsJson == null) AccountSettings() else AccountSettings(
            notificationsEnabled = settingsJson.optInt("notifications_enabled", 1) != 0,
            alertLeadMinutes = settingsJson.optInt("alert_lead_minutes", 60),
            sunriseAlerts = settingsJson.optInt("sunrise_alerts", 1) != 0,
            sunsetAlerts = settingsJson.optInt("sunset_alerts", 1) != 0,
            alertThreshold = settingsJson.optInt("alert_threshold", 70),
        )
        return AccountState(user, settings)
    }

    suspend fun updateAccount(token: String, settings: AccountSettings, displayName: String?): AccountUser? {
        val body = JSONObject()
            .put("notificationsEnabled", settings.notificationsEnabled)
            .put("alertLeadMinutes", settings.alertLeadMinutes)
            .put("sunriseAlerts", settings.sunriseAlerts)
            .put("sunsetAlerts", settings.sunsetAlerts)
            .put("alertThreshold", settings.alertThreshold)
        if (displayName != null) body.put("displayName", displayName)
        val payload = accountRequest("/api/account", token, "PUT", body)
        val userJson = payload.optJSONObject("user") ?: return null
        return AccountUser(
            id = userJson.optString("id"),
            displayName = userJson.optString("displayName", displayName ?: "FireSky user"),
            email = userJson.optNullableString("email"),
            avatarUrl = userJson.optNullableString("avatarUrl"),
            hasPassword = userJson.optBoolean("hasPassword", false),
        )
    }

    suspend fun deleteAccount(token: String) {
        accountRequest("/api/account", token, "DELETE")
    }

    data class SyncPayload(val locations: List<Place>, val viewpoints: List<Viewpoint>)

    suspend fun fetchSync(token: String): SyncPayload {
        val payload = accountRequest("/api/sync", token)
        val locations = payload.optJSONArray("locations")?.let { array ->
            List(array.length()) { index -> Place.fromJson(array.getJSONObject(index)) }
        } ?: emptyList()
        val viewpoints = payload.optJSONArray("viewpoints")?.let { array ->
            List(array.length()) { index -> Viewpoint.fromJson(array.getJSONObject(index)) }
        } ?: emptyList()
        return SyncPayload(locations, viewpoints)
    }

    suspend fun putSync(token: String, locations: List<Place>, viewpoints: List<Viewpoint>) {
        val body = JSONObject()
        body.put("locations", JSONArray().apply { locations.take(20).forEach { put(it.toJson()) } })
        body.put("viewpoints", JSONArray().apply { viewpoints.take(30).forEach { put(it.toJson()) } })
        accountRequest("/api/sync", token, "PUT", body)
    }

    suspend fun passwordAuth(
        action: String,
        email: String,
        password: String,
        displayName: String?,
    ): String {
        val body = JSONObject()
            .put("action", action)
            .put("email", email)
            .put("password", password)
        if (displayName != null) body.put("displayName", displayName)
        val payload = accountRequest("/api/auth/password", "", "POST", body)
        return payload.optString("token").ifBlank { throw ApiException("Sign in did not return a session") }
    }

    suspend fun changePassword(token: String, currentPassword: String, nextPassword: String) {
        val body = JSONObject()
            .put("action", "change")
            .put("currentPassword", currentPassword)
            .put("nextPassword", nextPassword)
        accountRequest("/api/auth/password", token, "POST", body)
    }

    suspend fun authStart(provider: String, returnTo: String): String {
        val payload = accountRequest(
            "/api/auth/start?provider=$provider&return_to=${java.net.URLEncoder.encode(returnTo, "UTF-8")}",
            "",
        )
        return payload.optString("url").ifBlank { throw ApiException("Sign in is not configured") }
    }

    suspend fun postFeedback(
        token: String,
        eventAt: Long,
        mode: String,
        placeName: String,
        latitude: Double,
        longitude: Double,
        probability: Double,
        outcome: String,
        sentiment: String?,
    ) {
        val body = JSONObject()
            .put("eventAt", eventAt)
            .put("mode", mode)
            .put("placeName", placeName)
            .put("latitude", latitude)
            .put("longitude", longitude)
            .put("probability", probability)
            .put("outcome", outcome)
            .put("sentiment", sentiment ?: JSONObject.NULL)
        accountRequest("/api/feedback", token, "POST", body)
    }

    suspend fun fetchForecastHistory(token: String, eventAt: Long, mode: String): List<ForecastSnapshot> {
        val payload = accountRequest("/api/forecast-history?eventAt=$eventAt&mode=$mode", token)
        val array = payload.optJSONArray("snapshots") ?: return emptyList()
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ForecastSnapshot(
                mode = mode,
                eventAt = eventAt,
                latitude = 0.0,
                longitude = 0.0,
                calculatedAt = item.optLong("calculatedAt", item.optLong("calculated_at", 0L)),
                probability = item.optDouble("probability", 0.0),
                quality = item.optDouble("quality", 0.0),
                modelVersion = item.optString("modelVersion", "rules"),
            )
        }
    }

    suspend fun postSnapshot(token: String, snapshot: ForecastSnapshot) {
        accountRequest("/api/forecast-history", token, "POST", snapshot.toJson())
    }

    suspend fun registerDevice(token: String, pushToken: String, platform: String) {
        val body = JSONObject().put("token", pushToken).put("platform", platform)
        accountRequest("/api/devices", token, "POST", body)
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 12_000L
        private const val GRID_REQUEST_TIMEOUT_MS = 45_000L
        private const val CACHE_TTL_MS = 90L * 60L * 1000L
        private const val STALE_TTL_MS = 6L * 60L * 60L * 1000L
        private const val GEOCODE_CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private val RETRYABLE_STATUS_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
    continuation.invokeOnCancellation { runCatching { cancel() } }
}
