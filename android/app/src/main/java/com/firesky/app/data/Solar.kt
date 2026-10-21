package com.firesky.app.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

private const val RAD = PI / 180.0
private const val DEG = 180.0 / PI
private const val DAY_MS = 86400000.0
private const val J1970 = 2440588.0
private const val J2000 = 2451545.0

private fun toJulian(date: Instant): Double =
    date.toEpochMilli().toDouble() / DAY_MS - 0.5 + J1970

private fun toDays(date: Instant): Double = toJulian(date) - J2000

private fun rightAscension(l: Double, b: Double): Double {
    val e = 23.4397 * RAD
    return atan2(sin(l) * cos(e) - tan(b) * sin(e), cos(l))
}

private fun declination(l: Double, b: Double): Double {
    val e = 23.4397 * RAD
    return asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(l))
}

private fun solarMeanAnomaly(d: Double): Double = RAD * (357.5291 + 0.98560028 * d)

private fun eclipticLongitude(m: Double): Double {
    val c = RAD * (1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m))
    val p = RAD * 102.9372
    return m + c + p + PI
}

private fun siderealTime(d: Double, lw: Double): Double =
    RAD * (280.16 + 360.9856235 * d) - lw

fun sunAltitude(date: Instant, latitude: Double, longitude: Double): Double {
    val lw = -longitude * RAD
    val phi = latitude * RAD
    val d = toDays(date)
    val m = solarMeanAnomaly(d)
    val l = eclipticLongitude(m)
    val dec = declination(l, 0.0)
    val ra = rightAscension(l, 0.0)
    val h = siderealTime(d, lw) - ra
    return asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h)) / RAD
}

fun sunAzimuth(date: Instant, latitude: Double, longitude: Double): Double {
    val lw = -longitude * RAD
    val phi = latitude * RAD
    val d = toDays(date)
    val m = solarMeanAnomaly(d)
    val l = eclipticLongitude(m)
    val dec = declination(l, 0.0)
    val ra = rightAscension(l, 0.0)
    val h = siderealTime(d, lw) - ra
    val azimuthFromSouth = atan2(
        sin(h),
        cos(h) * sin(phi) - tan(dec) * cos(phi),
    )
    return (azimuthFromSouth * DEG + 180.0 + 360.0) % 360.0
}

fun findSunAltitudeCrossing(
    startDate: Instant,
    endDate: Instant,
    targetAltitude: Double,
    latitude: Double,
    longitude: Double,
): Instant? {
    val start = startDate.toEpochMilli().toDouble()
    val end = endDate.toEpochMilli().toDouble()
    val steps = 72
    var previousTime = start
    var previousValue = sunAltitude(Instant.ofEpochMilli(previousTime.toLong()), latitude, longitude) - targetAltitude
    for (i in 1..steps) {
        val nextTime = start + ((end - start) * i) / steps
        val nextValue = sunAltitude(Instant.ofEpochMilli(nextTime.toLong()), latitude, longitude) - targetAltitude
        if (previousValue == 0.0 || previousValue * nextValue <= 0.0) {
            var low = previousTime
            var high = nextTime
            for (j in 0 until 28) {
                val mid = (low + high) / 2.0
                val midValue = sunAltitude(Instant.ofEpochMilli(mid.toLong()), latitude, longitude) - targetAltitude
                if (previousValue * midValue <= 0.0) {
                    high = mid
                } else {
                    low = mid
                    previousValue = midValue
                }
            }
            return Instant.ofEpochMilli(((low + high) / 2.0).toLong())
        }
        previousTime = nextTime
        previousValue = nextValue
    }
    return null
}

fun computeBlueHourWindows(
    latitude: Double,
    longitude: Double,
    sunrise: Instant?,
    sunset: Instant?,
): BlueHourWindows {
    val threeHours = 3L * 60L * 60L * 1000L
    val morningStart = sunrise?.let {
        findSunAltitudeCrossing(it.minusMillis(threeHours), it, -6.0, latitude, longitude)
    }
    val morningEnd = sunrise?.let {
        findSunAltitudeCrossing(it.minusMillis(threeHours), it, -4.0, latitude, longitude)
    }
    val eveningStart = sunset?.let {
        findSunAltitudeCrossing(it, it.plusMillis(threeHours), -4.0, latitude, longitude)
    }
    val eveningEnd = sunset?.let {
        findSunAltitudeCrossing(it, it.plusMillis(threeHours), -6.0, latitude, longitude)
    }
    return BlueHourWindows(
        sunrise = BlueHourWindow(morningStart, morningEnd),
        sunset = BlueHourWindow(eveningStart, eveningEnd),
    )
}

fun findEventAltitudeTime(
    eventDate: Instant?,
    targetAltitude: Double,
    latitude: Double,
    longitude: Double,
): Instant? {
    if (eventDate == null) return null
    val threeHours = 3L * 60L * 60L * 1000L
    return findSunAltitudeCrossing(
        eventDate.minusMillis(threeHours),
        eventDate.plusMillis(threeHours),
        targetAltitude,
        latitude,
        longitude,
    )
}

private fun peakColorAltitudeBand(window: Window, air: AirSnapshot, mode: String): Pair<Double, Double> {
    val highCloud = scoreBand(window.cloudHigh, 0.0, 26.0, 72.0, 96.0) / 100.0
    val midCloud = scoreBand(window.cloudMid, 0.0, 18.0, 62.0, 90.0) / 100.0
    val lowCloudClearance = scoreLowerBetter(window.cloudLow, 14.0, 72.0) / 100.0
    val totalCloud = scoreBand(window.cloudTotal, 4.0, 34.0, 76.0, 97.0) / 100.0
    val visibilityKm = (window.visibility ?: 10000.0) / 1000.0
    val visibility = scoreHigherBetter(visibilityKm, 5.0, 18.0) / 100.0
    val aerosol = weightedAverage(
        listOf(
            scoreBand(air.aod ?: 0.1, 0.0, 0.04, 0.24, 0.65) to 0.45,
            scoreLowerBetter(air.usAqi ?: 40.0, 18.0, 120.0) to 0.3,
            scoreBand(air.pm25 ?: 8.0, 0.0, 3.0, 14.0, 42.0) to 0.25,
        ),
    ) / 100.0
    val cloudScreen = weightedAverage(
        listOf(
            highCloud * 100.0 to 0.46,
            midCloud * 100.0 to 0.28,
            totalCloud * 100.0 to 0.18,
            lowCloudClearance * 100.0 to 0.08,
        ),
    ) / 100.0
    val clarity = weightedAverage(
        listOf(
            visibility * 100.0 to 0.46,
            lowCloudClearance * 100.0 to 0.26,
            aerosol * 100.0 to 0.18,
            scoreLowerBetter(window.precipProbability ?: 0.0, 8.0, 68.0) to 0.1,
        ),
    ) / 100.0
    val highCloudAfterglow = highCloud * 1.55 + midCloud * 0.42
    val hazeCompression = clamp((0.56 - clarity) * 2.2, 0.0, 1.1)
    val weakScreenCompression = if (cloudScreen < 0.42) 0.75 else 0.0
    val blockedHorizonCompression = if (lowCloudClearance < 0.45) 0.65 else 0.0
    val deepTwilightAltitude = clamp(
        -3.7 - highCloudAfterglow + hazeCompression + weakScreenCompression,
        -7.2,
        -2.35,
    )
    val nearHorizonAltitude = clamp(
        0.75 + midCloud * 0.65 - blockedHorizonCompression - hazeCompression * 0.28,
        -0.45,
        2.1,
    )
    return if (mode == "sunrise") {
        deepTwilightAltitude to nearHorizonAltitude
    } else {
        nearHorizonAltitude to deepTwilightAltitude
    }
}

fun computeAppearanceWindows(
    latitude: Double,
    longitude: Double,
    sunrise: Instant?,
    sunset: Instant?,
    windows: ModeWindows,
    airSnapshot: AirSnapshot,
    airSnapshots: ModeAir,
): AppearanceWindows {
    val (sunriseStartAlt, sunriseEndAlt) = peakColorAltitudeBand(windows.sunrise, airSnapshots.sunrise, "sunrise")
    val (sunsetStartAlt, sunsetEndAlt) = peakColorAltitudeBand(windows.sunset, airSnapshots.sunset, "sunset")
    val sunriseStart = findEventAltitudeTime(sunrise, sunriseStartAlt, latitude, longitude)
    val sunriseEnd = findEventAltitudeTime(sunrise, sunriseEndAlt, latitude, longitude)
    val sunsetStart = findEventAltitudeTime(sunset, sunsetStartAlt, latitude, longitude)
    val sunsetEnd = findEventAltitudeTime(sunset, sunsetEndAlt, latitude, longitude)
    return AppearanceWindows(
        sunrise = AppearanceWindow(sunriseStart, sunriseEnd, sunriseStartAlt, sunriseEndAlt),
        sunset = AppearanceWindow(sunsetStart, sunsetEnd, sunsetStartAlt, sunsetEndAlt),
    )
}

fun localIsoToUtcDate(value: String?, utcOffsetSeconds: Int = 0): Instant? {
    if (value.isNullOrBlank()) return null
    return try {
        val parts = value.split("T")
        val datePart = parts[0]
        val timePart = parts.getOrElse(1) { "00:00" }
        val (year, month, day) = datePart.split("-").map { it.toInt() }
        val timeBits = timePart.split(":").map { it.toInt() }
        val hour = timeBits.getOrElse(0) { 0 }
        val minute = timeBits.getOrElse(1) { 0 }
        val second = timeBits.getOrElse(2) { 0 }
        LocalDateTime.of(year, month, day, hour, minute, second)
            .toInstant(ZoneOffset.ofTotalSeconds(utcOffsetSeconds))
    } catch (_: Exception) {
        null
    }
}

/** Follows the device's 12/24-hour setting; refreshed from the activity on resume. */
object ClockStyle {
    @Volatile
    var use24Hour: Boolean = true
}

fun formatTime(value: Instant?, timeZone: String): String {
    if (value == null) return "--:--"
    return try {
        val pattern = if (ClockStyle.use24Hour) "HH:mm" else "h:mm a"
        DateTimeFormatter.ofPattern(pattern, java.util.Locale.US).withZone(java.time.ZoneId.of(timeZone)).format(value)
    } catch (_: Exception) {
        "--:--"
    }
}

fun formatRange(start: Instant?, end: Instant?, timeZone: String): String {
    if (start == null || end == null) return "--"
    val from = formatTime(start, timeZone)
    val to = formatTime(end, timeZone)
    if (!ClockStyle.use24Hour) {
        // "7:02–7:32 AM" rather than repeating the meridiem on both ends.
        val fromMeridiem = from.takeLast(2)
        if (fromMeridiem == to.takeLast(2) && fromMeridiem in setOf("AM", "PM")) {
            return "${from.dropLast(3)}-$to"
        }
    }
    return "$from-$to"
}

fun formatMinutes(totalMinutes: Double?): String {
    if (totalMinutes == null || totalMinutes.isNaN() || totalMinutes < 0) return "--"
    val hours = kotlin.math.floor(totalMinutes / 60.0).toInt()
    val minutes = kotlin.math.round(totalMinutes % 60.0).toInt()
    if (hours == 0) return "${minutes}m"
    return "${hours}h ${minutes.toString().padStart(2, '0')}m"
}

fun formatDuration(start: Instant?, end: Instant?): String {
    if (start == null || end == null) return "--"
    return formatMinutes((end.toEpochMilli() - start.toEpochMilli()) / 60000.0)
}

fun formatTotalDuration(windows: List<BlueHourWindow>): String {
    val total = windows.sumOf { window ->
        if (window.start == null || window.end == null) 0.0
        else (window.end.toEpochMilli() - window.start.toEpochMilli()) / 60000.0
    }
    return if (total != 0.0) formatMinutes(total) else "--"
}

fun nearestIndex(times: List<String>, target: Instant?, utcOffsetSeconds: Int? = null): Int {
    if (target == null || times.isEmpty()) return 0
    var best = 0
    var bestDiff = Double.MAX_VALUE
    times.forEachIndexed { index, time ->
        val date = if (utcOffsetSeconds == null) {
            try { Instant.parse(time) } catch (_: Exception) { null }
        } else {
            localIsoToUtcDate(time, utcOffsetSeconds)
        }
        if (date != null) {
            val diff = kotlin.math.abs(date.toEpochMilli() - target.toEpochMilli()).toDouble()
            if (diff < bestDiff) {
                best = index
                bestDiff = diff
            }
        }
    }
    return best
}

private fun valueAt(values: List<Double?>, index: Int): Double? = values.getOrNull(index)

fun pickWindow(hourly: HourlyWeather, centerDate: Instant?, mode: String, utcOffsetSeconds: Int? = null): Window {
    val times = hourly.time
    val center = nearestIndex(times, centerDate, utcOffsetSeconds)
    val indexes = listOf(center - 1, center, center + 1).filter { it in times.indices }
    fun average(values: List<Double?>): Double? = avg(indexes.map { valueAt(values, it) })
    return Window(
        mode = mode,
        center = center,
        indexes = indexes,
        start = times.getOrNull(indexes.firstOrNull() ?: 0),
        end = times.getOrNull(indexes.lastOrNull() ?: 0),
        cloudTotal = average(hourly.cloudCover),
        cloudLow = average(hourly.cloudLow),
        cloudMid = average(hourly.cloudMid),
        cloudHigh = average(hourly.cloudHigh),
        humidity = average(hourly.humidity),
        precipProbability = average(hourly.precipProbability),
        precipitation = average(hourly.precipitation),
        visibility = average(hourly.visibility),
        wind = average(hourly.wind),
        gust = average(hourly.gust),
        cape = average(hourly.cape),
        shortwave = average(hourly.shortwave),
        direct = average(hourly.direct),
        diffuse = average(hourly.diffuse),
        directNormal = average(hourly.directNormal),
        sunshineDuration = average(hourly.sunshineDuration),
        vpd = average(hourly.vpd),
    )
}

fun airSnapshotAt(air: AirQuality, targetDate: Instant?, utcOffsetSeconds: Int = 0): AirSnapshot {
    val index = nearestIndex(air.time, targetDate ?: Instant.now(), utcOffsetSeconds)
    return AirSnapshot(
        usAqi = air.usAqi.getOrNull(index) ?: air.current.usAqi,
        pm25 = air.pm25.getOrNull(index) ?: air.current.pm25,
        pm10 = air.pm10.getOrNull(index) ?: air.current.pm10,
        aod = air.aod.getOrNull(index) ?: air.current.aod,
        dust = air.dust.getOrNull(index) ?: air.current.dust,
    )
}
