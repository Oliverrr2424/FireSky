package com.firesky.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.sqrt

data class RegionalContext(
    val sampleCount: Int? = null,
    val horizonOpening: Double? = null,
    val solarCorridor: Double? = null,
    val regionalTexture: Double? = null,
)

data class ScoredPoint(
    val sampleId: Int,
    val latitude: Double,
    val longitude: Double,
    val window: Window,
    val sunBearing: Double,
    val probability: Double,
    val quality: Double,
    val factors: Factors,
    val confidence: Double,
)

private const val SOLAR_CORRIDOR_MAX_KM = 430.0
private const val SOLAR_CORRIDOR_MIN_KM = 25.0

fun computeWindowScore(window: Window, air: AirSnapshot, mode: String, context: RegionalContext = RegionalContext()): Score {
    val aqi = air.usAqi
    val pm25 = air.pm25
    val aod = air.aod
    val dust = air.dust

    val highCloud = scoreBand(window.cloudHigh, 0.0, 28.0, 72.0, 96.0)
    val midCloud = scoreBand(window.cloudMid, 0.0, 18.0, 62.0, 90.0)
    val lowCloudPenalty = scoreLowerBetter(window.cloudLow, 12.0, 72.0)
    val totalCloud = scoreBand(window.cloudTotal, 4.0, 34.0, 76.0, 97.0)
    val humidity = scoreBand(
        window.humidity,
        if (mode == "sunrise") 22.0 else 18.0,
        if (mode == "sunrise") 48.0 else 34.0,
        if (mode == "sunrise") 82.0 else 72.0,
        96.0,
    )
    val rain = scoreLowerBetter((window.precipProbability ?: 0.0) + (window.precipitation ?: 0.0) * 24.0, 5.0, 72.0)
    val visibility = scoreHigherBetter((window.visibility ?: 8000.0) / 1000.0, 5.0, 18.0)
    val aerosol = clamp(
        scoreBand(aod ?: 0.1, 0.0, 0.04, 0.24, 0.65) * 0.42 +
            scoreBand(pm25 ?: 8.0, 0.0, 3.0, 14.0, 42.0) * 0.3 +
            scoreLowerBetter(aqi ?: 40.0, 18.0, 120.0) * 0.2 +
            scoreLowerBetter(dust ?: 0.0, 5.0, 95.0) * 0.05,
    )
    val wind = scoreBand(window.wind, 0.0, 3.0, 22.0, 42.0)
    val instability = scoreLowerBetter(window.cape ?: 0.0, 80.0, 1400.0)
    val vpd = scoreBand(window.vpd ?: 0.7, 0.0, 0.2, 1.4, 2.6)
    val directBeam = window.directNormal ?: window.direct
    val beamPurity = if (directBeam != null && window.diffuse != null && directBeam > 0.0) {
        scoreHigherBetter(directBeam / (window.diffuse + 1.0), 0.45, 3.2)
    } else {
        55.0
    }
    val sunAccess = if (mode == "sunset") {
        weightedAverage(
            listOf(
                scoreHigherBetter(window.directNormal ?: window.direct ?: 0.0, 45.0, 430.0) to 0.5,
                scoreHigherBetter(window.sunshineDuration ?: 0.0, 240.0, 2600.0) to 0.25,
                scoreLowerBetter(window.diffuse ?: 0.0, 40.0, 260.0) to 0.25,
            ),
        )
    } else {
        weightedAverage(
            listOf(
                lowCloudPenalty to 0.45,
                rain to 0.25,
                visibility to 0.3,
            ),
        )
    }
    val localTexture = scoreBand(
        abs((window.cloudHigh ?: 0.0) - (window.cloudMid ?: 0.0)) +
            abs((window.cloudMid ?: 0.0) - (window.cloudLow ?: 0.0)) * 0.35,
        0.0,
        8.0,
        36.0,
        78.0,
    )
    val horizonOpening = context.horizonOpening ?: weightedAverage(
        listOf(
            lowCloudPenalty to 0.48,
            rain to 0.25,
            visibility to 0.2,
            sunAccess to 0.07,
        ),
    )
    val solarCorridor = context.solarCorridor ?: weightedAverage(
        listOf(
            horizonOpening to 0.58,
            lowCloudPenalty to 0.18,
            rain to 0.14,
            sunAccess to 0.1,
        ),
    )
    val regionalTexture = context.regionalTexture ?: localTexture
    val cloudScreen = weightedAverage(
        listOf(
            highCloud to 0.36,
            midCloud to 0.23,
            totalCloud to 0.18,
            lowCloudPenalty to 0.14,
            regionalTexture to 0.09,
        ),
    )
    val blockersClearance = weightedAverage(
        listOf(
            horizonOpening to 0.36,
            rain to 0.27,
            visibility to 0.19,
            lowCloudPenalty to 0.18,
        ),
    )
    val colorChemistry = weightedAverage(
        listOf(
            aerosol to 0.38,
            humidity to 0.3,
            vpd to 0.14,
            wind to 0.1,
            instability to 0.08,
        ),
    )
    val cloudCanvas = weightedAverage(
        listOf(
            highCloud to 0.46,
            midCloud to 0.29,
            totalCloud to 0.14,
            regionalTexture to 0.11,
        ),
    )
    val intensityScreen = weightedAverage(
        listOf(
            highCloud to 0.42,
            midCloud to 0.23,
            regionalTexture to 0.22,
            totalCloud to 0.13,
        ),
    )
    val opticalColor = weightedAverage(
        listOf(
            scoreBand(aod ?: 0.1, 0.0, 0.04, 0.2, 0.55) to 0.34,
            scoreBand(pm25 ?: 8.0, 0.0, 3.0, 12.0, 35.0) to 0.22,
            scoreLowerBetter(aqi ?: 40.0, 18.0, 110.0) to 0.18,
            scoreLowerBetter(dust ?: 0.0, 5.0, 75.0) to 0.1,
            humidity to 0.1,
            vpd to 0.06,
        ),
    )
    val contrast = weightedAverage(
        listOf(
            visibility to 0.38,
            beamPurity to 0.32,
            scoreBand(
                window.humidity,
                if (mode == "sunrise") 18.0 else 14.0,
                if (mode == "sunrise") 36.0 else 28.0,
                if (mode == "sunrise") 76.0 else 68.0,
                92.0,
            ) to 0.3,
        ),
    )

    val probability = weightedProductScore(
        listOf(
            horizonOpening to 0.23,
            solarCorridor to 0.18,
            lowCloudPenalty to 0.17,
            rain to 0.15,
            visibility to 0.09,
            cloudCanvas to 0.18,
        ),
    )

    val quality = weightedAverage(
        listOf(
            intensityScreen to 0.34,
            opticalColor to 0.24,
            contrast to 0.16,
            beamPurity to 0.1,
            regionalTexture to 0.1,
            wind to 0.06,
        ),
    )

    val blockers = buildList {
        if ((window.cloudLow ?: 0.0) > 48.0) add("Low cloud may block the horizon")
        if ((window.precipProbability ?: 0.0) > 45.0) add("Precipitation risk is elevated")
        if ((window.cloudTotal ?: 0.0) < 15.0) add("Too little cloud texture")
        if ((window.cloudTotal ?: 0.0) > 88.0) add("Cloud deck may be too thick")
        if (((window.visibility ?: 10000.0) / 1000.0) < 5.0) add("Visibility is limited")
        if (horizonOpening < 35.0) add(if (mode == "sunset") "Western low-sky opening is weak" else "Eastern low-sky opening is weak")
        if (solarCorridor < 38.0) add(if (mode == "sunset") "Sunset light may be blocked upstream" else "Sunrise light may be blocked upstream")
    }

    val boosts = buildList {
        if ((window.cloudHigh ?: 0.0) in 24.0..70.0) add("High cloud screen is favorable")
        if ((window.cloudMid ?: 0.0) in 18.0..64.0) add("Mid cloud has room to color")
        if ((aod ?: 0.1) in 0.05..0.32) add("Aerosol level is balanced")
        if ((window.precipProbability ?: 0.0) < 25.0) add("Precipitation interference is low")
        if (horizonOpening >= 68.0) add(if (mode == "sunset") "Western low sky is open" else "Eastern low sky is open")
        if (solarCorridor >= 68.0) add(if (mode == "sunset") "Sunset light corridor is open" else "Sunrise light corridor is open")
    }

    val confidence = clamp(
        58.0 + (window.indexes.size - 1) * 8.0 +
            (context.sampleCount?.let { min(12.0, it / 10.0) } ?: 0.0) -
            blockers.size * 6.0,
    )

    return Score(
        probability = probability,
        quality = quality,
        factors = Factors(
            highCloud = highCloud,
            midCloud = midCloud,
            lowCloudPenalty = lowCloudPenalty,
            totalCloud = totalCloud,
            humidity = humidity,
            rain = rain,
            visibility = visibility,
            aerosol = aerosol,
            wind = wind,
            instability = instability,
            beamPurity = beamPurity,
            sunAccess = sunAccess,
            horizonOpening = horizonOpening,
            solarCorridor = solarCorridor,
            regionalTexture = regionalTexture,
            cloudScreen = cloudScreen,
            blockersClearance = blockersClearance,
            colorChemistry = colorChemistry,
            cloudCanvas = cloudCanvas,
            intensityScreen = intensityScreen,
            opticalColor = opticalColor,
            contrast = contrast,
        ),
        blockers = blockers,
        boosts = boosts,
        confidence = confidence,
    )
}

fun mergeMlScore(ruleScore: Score, mlScore: MlScore?): Score {
    val mlPercent = mlScore?.probability ?: return ruleScore
    return ruleScore.copy(
        probability = mlPercent,
        ml = MlScore(probability = mlPercent, fallbackProbability = ruleScore.probability),
    )
}

fun buildForecast(bundle: ForecastBundle, dayIndex: Int = 0): Forecast {
    val weather = bundle.weather
    require(weather.isUsable) { "Weather data is incomplete" }
    val utcOffsetSeconds = weather.utcOffsetSeconds
    val timeZone = weather.timezone
    val sunrise = localIsoToUtcDate(weather.daily.sunrise.getOrNull(dayIndex), utcOffsetSeconds)
        ?: throw IllegalStateException("Weather data is incomplete for this date")
    val sunset = localIsoToUtcDate(weather.daily.sunset.getOrNull(dayIndex), utcOffsetSeconds)
        ?: throw IllegalStateException("Weather data is incomplete for this date")
    val sunriseWindow = pickWindow(weather.hourly, sunrise, "sunrise", utcOffsetSeconds)
    val sunsetWindow = pickWindow(weather.hourly, sunset, "sunset", utcOffsetSeconds)
    val airSnapshot = bundle.air.current
    val airSnapshots = ModeAir(
        sunrise = airSnapshotAt(bundle.air, sunrise, utcOffsetSeconds),
        sunset = airSnapshotAt(bundle.air, sunset, utcOffsetSeconds),
    )

    val mlOk = bundle.ml?.status == "ok"
    val sunriseScore = mergeMlScore(
        computeWindowScore(sunriseWindow, airSnapshots.sunrise, "sunrise"),
        if (dayIndex == 0 && mlOk) bundle.ml?.scores?.get("sunrise") else null,
    )
    val sunsetScore = mergeMlScore(
        computeWindowScore(sunsetWindow, airSnapshots.sunset, "sunset"),
        if (dayIndex == 0 && mlOk) bundle.ml?.scores?.get("sunset") else null,
    )
    val windows = ModeWindows(sunriseWindow, sunsetWindow)
    val blueHour = computeBlueHourWindows(weather.latitude, weather.longitude, sunrise, sunset)
    val appearanceWindow = computeAppearanceWindows(
        weather.latitude,
        weather.longitude,
        sunrise,
        sunset,
        windows,
        airSnapshot,
        airSnapshots,
    )
    return Forecast(
        weather = weather,
        air = bundle.air,
        timeZone = timeZone,
        utcOffsetSeconds = utcOffsetSeconds,
        sunrise = sunrise,
        sunset = sunset,
        blueHour = blueHour,
        appearanceWindow = appearanceWindow,
        windows = windows,
        scores = ModeScores(sunriseScore, sunsetScore),
        ml = bundle.ml,
        current = weather.current,
        airSnapshot = airSnapshot,
        airSnapshots = airSnapshots,
        dayIndex = dayIndex,
    )
}

fun buildForecastDays(bundle: ForecastBundle): List<Forecast> {
    val count = min(7, bundle.weather.daily.sunrise.size)
    return (0 until count).mapNotNull { dayIndex ->
        try {
            buildForecast(bundle, dayIndex)
        } catch (_: Exception) {
            null
        }
    }
}

private fun round4(value: Double): Double = round(value * 10000.0) / 10000.0

fun createGridPlaces(place: Place, steps: Int = 15): List<Place> {
    val latSteps = List(steps) { index -> -2.2 + (4.4 * index) / (steps - 1) }
    val lonSteps = List(steps) { index -> -4.4 + (8.8 * index) / (steps - 1) }
    val result = ArrayList<Place>(steps * steps)
    latSteps.forEach { latOffset ->
        lonSteps.forEach { lonOffset ->
            result.add(
                place.copy(
                    latitude = round4(place.latitude + latOffset),
                    longitude = round4(place.longitude + lonOffset),
                ),
            )
        }
    }
    return result
}

fun distanceKm(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val lat1 = aLat * (Math.PI / 180.0)
    val lat2 = bLat * (Math.PI / 180.0)
    val dLat = (bLat - aLat) * (Math.PI / 180.0)
    val dLon = (bLon - aLon) * (Math.PI / 180.0)
    val sinLat = sin(dLat / 2)
    val sinLon = sin(dLon / 2)
    val h = sinLat * sinLat + cos(lat1) * cos(lat2) * sinLon * sinLon
    return 6371.0 * 2.0 * atan2(sqrt(h), sqrt(1 - h))
}

fun bearingDegrees(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
    val lat1 = aLat * (Math.PI / 180.0)
    val lat2 = bLat * (Math.PI / 180.0)
    val dLon = (bLon - aLon) * (Math.PI / 180.0)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (atan2(y, x) * (180.0 / Math.PI) + 360.0) % 360.0
}

fun angleDeltaDegrees(a: Double, b: Double): Double = abs(((a - b + 540.0) % 360.0) - 180.0)

private data class SunwardPoint(
    val sample: ScoredPoint,
    val distance: Double,
    val weight: Double,
)

private fun regionalContextForPoint(point: ScoredPoint, allPoints: List<ScoredPoint>, mode: String): RegionalContext {
    val fallbackBearing = if (mode == "sunset") 270.0 else 90.0
    val sunBearing = if (!point.sunBearing.isNaN()) point.sunBearing else fallbackBearing
    val near = allPoints.filter { sample ->
        sample.sampleId != point.sampleId &&
            distanceKm(point.latitude, point.longitude, sample.latitude, sample.longitude) <= 150.0
    }
    val sunward = allPoints
        .filter { it.sampleId != point.sampleId }
        .map { sample ->
            val distance = distanceKm(point.latitude, point.longitude, sample.latitude, sample.longitude)
            val alignment = angleDeltaDegrees(
                bearingDegrees(point.latitude, point.longitude, sample.latitude, sample.longitude),
                sunBearing,
            )
            val width = if (distance < 180.0) 30.0 else 20.0
            val weight = max(0.0, 1.0 - alignment / width) * (1.0 / (1.0 + distance / 160.0))
            SunwardPoint(sample, distance, weight)
        }
        .filter { it.distance >= SOLAR_CORRIDOR_MIN_KM && it.distance <= SOLAR_CORRIDOR_MAX_KM && it.weight > 0.0 }
    val horizon = sunward.filter { it.distance <= 190.0 }
    val horizonSet: List<Pair<ScoredPoint, Double>> = if (horizon.isNotEmpty()) {
        horizon.map { it.sample to it.weight }
    } else {
        near.map { it to 1.0 }
    }
    val corridorValues: List<Pair<ScoredPoint, Double>> = if (sunward.isNotEmpty()) {
        sunward.map { it.sample to it.weight }
    } else {
        horizonSet
    }
    val horizonLow = weightedMean(horizonSet.map { it.first.window.cloudLow to it.second }) ?: point.window.cloudLow
    val horizonMid = weightedMean(horizonSet.map { it.first.window.cloudMid to it.second }) ?: point.window.cloudMid
    val horizonTotal = weightedMean(horizonSet.map { it.first.window.cloudTotal to it.second }) ?: point.window.cloudTotal
    val horizonPrecip = weightedMean(horizonSet.map { it.first.window.precipProbability to it.second }) ?: point.window.precipProbability
    val horizonVisibility = weightedMean(horizonSet.map { it.first.window.visibility to it.second }) ?: point.window.visibility
    val corridorLow = weightedMean(corridorValues.map { it.first.window.cloudLow to it.second }) ?: horizonLow
    val corridorMid = weightedMean(corridorValues.map { it.first.window.cloudMid to it.second }) ?: horizonMid
    val corridorTotal = weightedMean(corridorValues.map { it.first.window.cloudTotal to it.second }) ?: horizonTotal
    val corridorPrecip = weightedMean(corridorValues.map { it.first.window.precipProbability to it.second }) ?: horizonPrecip
    val corridorVisibility = weightedMean(corridorValues.map { it.first.window.visibility to it.second }) ?: horizonVisibility
    val corridorOpticalBlock = (corridorLow ?: 0.0) * 0.42 + (corridorMid ?: 0.0) * 0.24 + (corridorTotal ?: 0.0) * 0.2 + (corridorPrecip ?: 0.0) * 0.14
    val regionalTextureRaw = standardDeviation(
        near.flatMap { listOf(it.window.cloudHigh, it.window.cloudMid) },
    )
    val horizonOpticalBlock = (horizonLow ?: 0.0) * 0.52 + (horizonMid ?: 0.0) * 0.18 + (horizonTotal ?: 0.0) * 0.2 + (horizonPrecip ?: 0.0) * 0.1
    return RegionalContext(
        sampleCount = allPoints.size,
        horizonOpening = weightedAverage(
            listOf(
                scoreLowerBetter(horizonOpticalBlock, 20.0, 82.0) to 0.56,
                scoreLowerBetter(horizonPrecip ?: 0.0, 12.0, 70.0) to 0.22,
                scoreHigherBetter((horizonVisibility ?: 8000.0) / 1000.0, 6.0, 18.0) to 0.22,
            ),
        ),
        solarCorridor = weightedAverage(
            listOf(
                scoreLowerBetter(corridorOpticalBlock, 18.0, 78.0) to 0.64,
                scoreLowerBetter(corridorPrecip ?: 0.0, 10.0, 68.0) to 0.18,
                scoreHigherBetter((corridorVisibility ?: 8000.0) / 1000.0, 6.0, 20.0) to 0.18,
            ),
        ),
        regionalTexture = scoreBand(regionalTextureRaw, 0.0, 4.0, 22.0, 46.0),
    )
}

private fun enhanceRegionalScores(points: List<ScoredPoint>, mode: String): List<ScoredPoint> =
    points.map { point ->
        val context = regionalContextForPoint(point, points, mode)
        val score = computeWindowScore(point.window, AirSnapshot(), mode, context)
        point.copy(
            probability = score.probability,
            quality = score.quality,
            factors = score.factors,
            confidence = score.confidence,
        )
    }

fun buildGrid(payload: JSONArray, place: Place, mode: String): List<ScoredPoint> {
    val samples = createGridPlaces(place)
    val rows = min(payload.length(), samples.size)
    val points = (0 until rows).mapNotNull { index ->
        val item = payload.optJSONObject(index) ?: return@mapNotNull null
        val utcOffsetSeconds = item.optInt("utc_offset_seconds", 0)
        val daily = item.optJSONObject("daily")
        val target = if (mode == "sunrise") {
            localIsoToUtcDate(daily.stringList("sunrise").firstOrNull(), utcOffsetSeconds)
        } else {
            localIsoToUtcDate(daily.stringList("sunset").firstOrNull(), utcOffsetSeconds)
        }
        val hourly = HourlyWeather(item.optJSONObject("hourly"))
        val window = pickWindow(hourly, target ?: Instant.now(), mode, utcOffsetSeconds)
        val score = computeWindowScore(window, AirSnapshot(), mode)
        val sample = samples[index]
        ScoredPoint(
            sampleId = index,
            latitude = sample.latitude,
            longitude = sample.longitude,
            window = window,
            sunBearing = target?.let { sunAzimuth(it, sample.latitude, sample.longitude) }
                ?: if (mode == "sunset") 270.0 else 90.0,
            probability = score.probability,
            quality = score.quality,
            factors = score.factors,
            confidence = score.confidence,
        )
    }
    return enhanceRegionalScores(points, mode)
}

fun applyRegionalContextToForecast(forecast: Forecast, regionalPoints: List<ScoredPoint>, place: Place): Forecast {
    if (regionalPoints.isEmpty()) return forecast
    var scores = forecast.scores
    listOf("sunrise", "sunset").forEach { mode ->
        val target = forecast[mode]
        val point = ScoredPoint(
            sampleId = -1,
            latitude = place.latitude,
            longitude = place.longitude,
            window = forecast.windows[mode],
            sunBearing = sunAzimuth(target, place.latitude, place.longitude),
            probability = 0.0,
            quality = 0.0,
            factors = emptyFactors(),
            confidence = 0.0,
        )
        val context = regionalContextForPoint(point, regionalPoints, mode)
        val score = mergeMlScore(
            computeWindowScore(
                forecast.windows[mode],
                forecast.airSnapshots[mode] ?: forecast.airSnapshot,
                mode,
                context,
            ),
            if (forecast.ml?.status == "ok") forecast.ml?.scores?.get(mode) else forecast.scores[mode].ml,
        )
        scores = if (mode == "sunrise") scores.copy(sunrise = score) else scores.copy(sunset = score)
    }
    return forecast.copy(scores = scores)
}

private fun emptyFactors(): Factors = Factors(
    highCloud = 0.0,
    midCloud = 0.0,
    lowCloudPenalty = 0.0,
    totalCloud = 0.0,
    humidity = 0.0,
    rain = 0.0,
    visibility = 0.0,
    aerosol = 0.0,
    wind = 0.0,
    instability = 0.0,
    beamPurity = 0.0,
    sunAccess = 0.0,
    horizonOpening = 0.0,
    solarCorridor = 0.0,
    regionalTexture = 0.0,
    cloudScreen = 0.0,
    blockersClearance = 0.0,
    colorChemistry = 0.0,
    cloudCanvas = 0.0,
    intensityScreen = 0.0,
    opticalColor = 0.0,
    contrast = 0.0,
)

fun weatherDescription(code: Int?, cloudCover: Double = 0.0): String {
    if (code == null) return "Clear"
    if (code in listOf(95, 96, 99)) return "Thunderstorm"
    if (code in listOf(71, 73, 75, 77, 85, 86)) return "Snow"
    if (code in listOf(45, 48)) return "Fog"
    if (code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)) return "Rain"
    if (code in listOf(1, 2)) return if (cloudCover > 55.0) "Partly Cloudy" else "Mostly Clear"
    if (code == 3 || cloudCover > 80.0) return "Cloudy"
    return "Clear"
}

fun weatherTheme(code: Int?, cloudCover: Double = 0.0): String {
    if (code == null) return "clear"
    if (code in listOf(95, 96, 99)) return "storm"
    if (code in listOf(71, 73, 75, 77, 85, 86)) return "snow"
    if (code in listOf(45, 48)) return "fog"
    if (code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)) return "rain"
    if (code == 3 || cloudCover > 72.0) return "cloudy"
    return "clear"
}

fun describeScore(score: Double): String = when {
    score >= 85.0 -> "Excellent"
    score >= 72.0 -> "High chance"
    score >= 63.0 -> "Worth watching"
    score >= 42.0 -> "Possible"
    score >= 24.0 -> "Weak"
    else -> "Unlikely"
}

fun formatPercent(value: Double?): String = "${Math.round(value ?: 0.0)}%"

fun formatScoreRange(a: Double?, b: Double?): String {
    val values = listOfNotNull(a, b).filter { !it.isNaN() }.map { Math.round(it) }
    if (values.isEmpty()) return "--"
    val low = values.min()
    val high = values.max()
    return if (low == high) "$low%" else "$low-$high%"
}

fun compassLabel(degrees: Double): String {
    val names = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[Math.round((((degrees % 360.0) + 360.0) % 360.0) / 45.0).toInt() % 8]
}

fun preferredForecastMode(forecast: Forecast): String {
    val hour = 60L * 60L * 1000L
    val now = System.currentTimeMillis()
    val sunriseEnd = forecast.appearanceWindow.sunrise.end?.toEpochMilli()
    val sunsetEnd = forecast.appearanceWindow.sunset.end?.toEpochMilli()
    if (sunriseEnd != null && sunsetEnd != null && now >= sunriseEnd + hour && now <= sunsetEnd + hour) {
        return "sunset"
    }
    return "sunrise"
}

fun forecastDayKey(forecast: Forecast): String? {
    return try {
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd", java.util.Locale.US)
            .withZone(java.time.ZoneId.of(forecast.timeZone))
            .format(forecast.sunset)
    } catch (_: Exception) {
        null
    }
}

fun jsonArrayOfRows(payload: org.json.JSONArray): JSONArray = payload
