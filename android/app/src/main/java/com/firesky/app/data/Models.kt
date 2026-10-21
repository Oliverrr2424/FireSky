package com.firesky.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

data class Place(
    val name: String,
    val admin1: String = "",
    val countryCode: String = "US",
    val latitude: Double,
    val longitude: Double,
    val id: Long? = null,
) {
    fun sameSpot(other: Place): Boolean =
        kotlin.math.abs(other.latitude - latitude) < 0.001 &&
            kotlin.math.abs(other.longitude - longitude) < 0.001

    fun spotKey(): String = String.format(java.util.Locale.US, "%.3f,%.3f", latitude, longitude)

    fun toJson(): JSONObject = JSONObject()
        .put("name", name)
        .put("admin1", admin1)
        .put("country_code", countryCode)
        .put("latitude", latitude)
        .put("longitude", longitude)

    companion object {
        fun fromJson(json: JSONObject): Place = Place(
            name = json.optString("name", "Unknown"),
            admin1 = json.optString("admin1", ""),
            countryCode = json.optString("country_code", "US"),
            latitude = json.optDouble("latitude", 0.0),
            longitude = json.optDouble("longitude", 0.0),
            id = if (json.has("id") && !json.isNull("id")) json.optLong("id") else null,
        )
    }
}

data class CurrentWeather(
    val temperature2m: Double?,
    val weatherCode: Int?,
    val cloudCover: Double?,
) {
    companion object {
        fun fromJson(json: JSONObject?): CurrentWeather {
            if (json == null) return CurrentWeather(null, null, null)
            return CurrentWeather(
                temperature2m = json.optNullableDouble("temperature_2m"),
                weatherCode = json.optNullableInt("weather_code"),
                cloudCover = json.optNullableDouble("cloud_cover"),
            )
        }
    }
}

class HourlyWeather(json: JSONObject?) {
    val time: List<String> = json.stringList("time")
    val cloudCover: List<Double?> = json.doubleList("cloud_cover")
    val cloudLow: List<Double?> = json.doubleList("cloud_cover_low")
    val cloudMid: List<Double?> = json.doubleList("cloud_cover_mid")
    val cloudHigh: List<Double?> = json.doubleList("cloud_cover_high")
    val humidity: List<Double?> = json.doubleList("relative_humidity_2m")
    val precipProbability: List<Double?> = json.doubleList("precipitation_probability")
    val precipitation: List<Double?> = json.doubleList("precipitation")
    val visibility: List<Double?> = json.doubleList("visibility")
    val wind: List<Double?> = json.doubleList("wind_speed_10m")
    val gust: List<Double?> = json.doubleList("wind_gusts_10m")
    val cape: List<Double?> = json.doubleList("cape")
    val shortwave: List<Double?> = json.doubleList("shortwave_radiation")
    val direct: List<Double?> = json.doubleList("direct_radiation")
    val diffuse: List<Double?> = json.doubleList("diffuse_radiation")
    val directNormal: List<Double?> = json.doubleList("direct_normal_irradiance")
    val sunshineDuration: List<Double?> = json.doubleList("sunshine_duration")
    val vpd: List<Double?> = json.doubleList("vapour_pressure_deficit")
}

class DailyWeather(json: JSONObject?) {
    val sunrise: List<String> = json.stringList("sunrise")
    val sunset: List<String> = json.stringList("sunset")
}

data class AirSnapshot(
    val usAqi: Double? = null,
    val pm25: Double? = null,
    val pm10: Double? = null,
    val aod: Double? = null,
    val dust: Double? = null,
) {
    companion object {
        fun fromJson(json: JSONObject?): AirSnapshot {
            if (json == null) return AirSnapshot()
            return AirSnapshot(
                usAqi = json.optNullableDouble("us_aqi"),
                pm25 = json.optNullableDouble("pm2_5"),
                pm10 = json.optNullableDouble("pm10"),
                aod = json.optNullableDouble("aerosol_optical_depth"),
                dust = json.optNullableDouble("dust"),
            )
        }
    }
}

class AirQuality(json: JSONObject?) {
    val current: AirSnapshot = AirSnapshot.fromJson(json?.optJSONObject("current"))
    private val hourlyJson: JSONObject? = json?.optJSONObject("hourly")
    val time: List<String> = hourlyJson.stringList("time")
    val usAqi: List<Double?> = hourlyJson.doubleList("us_aqi")
    val pm25: List<Double?> = hourlyJson.doubleList("pm2_5")
    val pm10: List<Double?> = hourlyJson.doubleList("pm10")
    val aod: List<Double?> = hourlyJson.doubleList("aerosol_optical_depth")
    val dust: List<Double?> = hourlyJson.doubleList("dust")
}

data class WeatherPayload(
    val latitude: Double,
    val longitude: Double,
    val timezone: String,
    val utcOffsetSeconds: Int,
    val current: CurrentWeather,
    val hourly: HourlyWeather,
    val daily: DailyWeather,
) {
    val isUsable: Boolean
        get() = hourly.time.isNotEmpty() && daily.sunrise.isNotEmpty() && daily.sunset.isNotEmpty()

    companion object {
        fun fromJson(json: JSONObject): WeatherPayload = WeatherPayload(
            latitude = json.optDouble("latitude", 0.0),
            longitude = json.optDouble("longitude", 0.0),
            timezone = json.optString("timezone", "UTC"),
            utcOffsetSeconds = json.optInt("utc_offset_seconds", 0),
            current = CurrentWeather.fromJson(json.optJSONObject("current")),
            hourly = HourlyWeather(json.optJSONObject("hourly")),
            daily = DailyWeather(json.optJSONObject("daily")),
        )
    }
}

data class MlScore(
    val probability: Double?,
    val fallbackProbability: Double? = null,
) {
    companion object {
        fun fromJson(json: JSONObject?): MlScore? {
            if (json == null) return null
            val raw = json.optNullableDouble("score") ?: json.optNullableDouble("probability")
            val normalized = raw?.let { if (it <= 1.0) it * 100.0 else it }?.coerceIn(0.0, 100.0)
            return MlScore(
                probability = normalized,
                fallbackProbability = json.optNullableDouble("fallbackProbability"),
            )
        }
    }
}

data class MlPayload(
    val status: String?,
    val modelVersion: String?,
    val scores: Map<String, MlScore>,
) {
    companion object {
        fun fromJson(json: JSONObject?): MlPayload? {
            if (json == null) return null
            val scoresJson = json.optJSONObject("scores")
            val scores = buildMap {
                listOf("sunrise", "sunset").forEach { mode ->
                    MlScore.fromJson(scoresJson?.optJSONObject(mode))?.let { put(mode, it) }
                }
            }
            return MlPayload(
                status = json.optNullableString("status"),
                modelVersion = json.optNullableString("modelVersion"),
                scores = scores,
            )
        }
    }
}

data class Window(
    val mode: String,
    val center: Int,
    val indexes: List<Int>,
    val start: String?,
    val end: String?,
    val cloudTotal: Double?,
    val cloudLow: Double?,
    val cloudMid: Double?,
    val cloudHigh: Double?,
    val humidity: Double?,
    val precipProbability: Double?,
    val precipitation: Double?,
    val visibility: Double?,
    val wind: Double?,
    val gust: Double?,
    val cape: Double?,
    val shortwave: Double?,
    val direct: Double?,
    val diffuse: Double?,
    val directNormal: Double?,
    val sunshineDuration: Double?,
    val vpd: Double?,
)

data class Factors(
    val highCloud: Double,
    val midCloud: Double,
    val lowCloudPenalty: Double,
    val totalCloud: Double,
    val humidity: Double,
    val rain: Double,
    val visibility: Double,
    val aerosol: Double,
    val wind: Double,
    val instability: Double,
    val beamPurity: Double,
    val sunAccess: Double,
    val horizonOpening: Double,
    val solarCorridor: Double,
    val regionalTexture: Double,
    val cloudScreen: Double,
    val blockersClearance: Double,
    val colorChemistry: Double,
    val cloudCanvas: Double,
    val intensityScreen: Double,
    val opticalColor: Double,
    val contrast: Double,
)

data class Score(
    val probability: Double,
    val quality: Double,
    val factors: Factors,
    val blockers: List<String>,
    val boosts: List<String>,
    val confidence: Double,
    val ml: MlScore? = null,
) {
    val ruleFallbackProbability: Double get() = ml?.fallbackProbability ?: probability
    val usesMl: Boolean get() = ml != null
}

data class ModeScores(val sunrise: Score, val sunset: Score) {
    operator fun get(mode: String): Score = if (mode == "sunrise") sunrise else sunset
}

data class ModeWindows(val sunrise: Window, val sunset: Window) {
    operator fun get(mode: String): Window = if (mode == "sunrise") sunrise else sunset
}

data class ModeAir(val sunrise: AirSnapshot, val sunset: AirSnapshot) {
    operator fun get(mode: String): AirSnapshot = if (mode == "sunrise") sunrise else sunset
}

data class BlueHourWindow(val start: Instant?, val end: Instant?)

data class BlueHourWindows(
    val sunrise: BlueHourWindow,
    val sunset: BlueHourWindow,
) {
    operator fun get(mode: String): BlueHourWindow = if (mode == "sunrise") sunrise else sunset
}

data class AppearanceWindow(
    val start: Instant?,
    val end: Instant?,
    val startAltitude: Double,
    val endAltitude: Double,
)

data class AppearanceWindows(
    val sunrise: AppearanceWindow,
    val sunset: AppearanceWindow,
) {
    operator fun get(mode: String): AppearanceWindow = if (mode == "sunrise") sunrise else sunset
}

data class Forecast(
    val weather: WeatherPayload,
    val air: AirQuality,
    val timeZone: String,
    val utcOffsetSeconds: Int,
    val sunrise: Instant,
    val sunset: Instant,
    val blueHour: BlueHourWindows,
    val appearanceWindow: AppearanceWindows,
    val windows: ModeWindows,
    val scores: ModeScores,
    val ml: MlPayload?,
    val current: CurrentWeather,
    val airSnapshot: AirSnapshot,
    val airSnapshots: ModeAir,
    val dayIndex: Int = 0,
) {
    operator fun get(mode: String): Instant = if (mode == "sunrise") sunrise else sunset
}

data class ForecastBundle(
    val weather: WeatherPayload,
    val air: AirQuality,
    val ml: MlPayload?,
)

data class AccountUser(
    val id: String,
    val displayName: String,
    val email: String?,
    val avatarUrl: String?,
    val hasPassword: Boolean,
)

data class AccountSettings(
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
)

data class UnitPrefs(
    val temperatureUnit: String = "C",
    val distanceUnit: String = "km",
    val timeFormat: String = "system",
    val languageTag: String = "system",
)

data class AccountState(
    val user: AccountUser,
    val settings: AccountSettings,
)

data class Viewpoint(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val note: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("note", note)
        .put("updated_at", updatedAt)

    companion object {
        fun fromJson(json: JSONObject): Viewpoint = Viewpoint(
            id = json.optString("id", java.util.UUID.randomUUID().toString()),
            name = json.optString("name", "Viewpoint"),
            latitude = json.optDouble("latitude", 0.0),
            longitude = json.optDouble("longitude", 0.0),
            note = json.optString("note", ""),
            updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
        )
    }
}

data class ForecastSnapshot(
    val mode: String,
    val eventAt: Long,
    val latitude: Double,
    val longitude: Double,
    val calculatedAt: Long,
    val probability: Double,
    val quality: Double,
    val modelVersion: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("mode", mode)
        .put("eventAt", eventAt)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("calculatedAt", calculatedAt)
        .put("probability", probability)
        .put("quality", quality)
        .put("modelVersion", modelVersion)

    companion object {
        fun fromJson(json: JSONObject): ForecastSnapshot = ForecastSnapshot(
            mode = json.optString("mode", "sunset"),
            eventAt = json.optLong("eventAt", 0L),
            latitude = json.optDouble("latitude", 0.0),
            longitude = json.optDouble("longitude", 0.0),
            calculatedAt = json.optLong("calculatedAt", 0L),
            probability = json.optDouble("probability", 0.0),
            quality = json.optDouble("quality", 0.0),
            modelVersion = json.optString("modelVersion", "rules"),
        )
    }
}

fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return if (value.isNaN()) null else value
}

fun JSONObject.optNullableInt(key: String): Int? {
    if (!has(key) || isNull(key)) return null
    return optInt(key)
}

fun JSONObject.optNullableString(key: String): String? {
    if (!has(key) || isNull(key)) return null
    return optString(key)
}

fun JSONObject?.doubleList(key: String): List<Double?> {
    val array: JSONArray = this?.optJSONArray(key) ?: return emptyList()
    return List(array.length()) { index ->
        if (array.isNull(index)) null else array.optDouble(index).takeUnless { it.isNaN() }
    }
}

fun JSONObject?.stringList(key: String): List<String> {
    val array: JSONArray = this?.optJSONArray(key) ?: return emptyList()
    return List(array.length()) { index -> if (array.isNull(index)) "" else array.optString(index) }
}
