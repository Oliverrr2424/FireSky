package com.firesky.app.data

import com.firesky.app.notifications.SkyAlerts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class ForecastEventsTest {
    private val offsetSeconds = -4 * 3600
    private val place = Place("Toronto", "Ontario", "CA", 43.6532, -79.3832)

    private fun local(value: String): Instant =
        LocalDateTime.parse(value).toInstant(ZoneOffset.ofTotalSeconds(offsetSeconds))

    private fun hourlyJson(days: Int): JSONObject {
        val hours = days * 24
        fun series(value: Double) = JSONArray().apply { repeat(hours) { put(value) } }
        return JSONObject()
            .put("time", JSONArray().apply {
                for (day in 0 until days) for (hour in 0 until 24) {
                    put(String.format("2026-10-%02dT%02d:00", 21 + day, hour))
                }
            })
            .put("cloud_cover", series(45.0))
            .put("cloud_cover_low", series(10.0))
            .put("cloud_cover_mid", series(35.0))
            .put("cloud_cover_high", series(50.0))
            .put("relative_humidity_2m", series(60.0))
            .put("precipitation_probability", series(5.0))
            .put("precipitation", series(0.0))
            .put("visibility", series(20000.0))
            .put("wind_speed_10m", series(10.0))
            .put("wind_gusts_10m", series(15.0))
            .put("cape", series(0.0))
            .put("shortwave_radiation", series(200.0))
            .put("direct_radiation", series(120.0))
            .put("diffuse_radiation", series(80.0))
            .put("direct_normal_irradiance", series(300.0))
            .put("sunshine_duration", series(1800.0))
            .put("vapour_pressure_deficit", series(0.8))
    }

    private fun dailyJson(days: Int) = JSONObject()
        .put("sunrise", JSONArray().apply { for (day in 0 until days) put(String.format("2026-10-%02dT07:35", 21 + day)) })
        .put("sunset", JSONArray().apply { for (day in 0 until days) put(String.format("2026-10-%02dT18:20", 21 + day)) })

    private fun days(): List<Forecast> {
        val weather = JSONObject()
            .put("latitude", place.latitude)
            .put("longitude", place.longitude)
            .put("timezone", "America/Toronto")
            .put("utc_offset_seconds", offsetSeconds)
            .put("current", JSONObject().put("temperature_2m", 9.0).put("weather_code", 2).put("cloud_cover", 45.0))
            .put("hourly", hourlyJson(2))
            .put("daily", dailyJson(2))
        val bundle = ForecastBundle(WeatherPayload.fromJson(weather), AirQuality(null), null)
        return buildForecastDays(bundle)
    }

    @Test
    fun beforeSunriseLeadsWithTodaysSunrise() {
        val next = nextForecastEvent(days(), local("2026-10-21T05:00"))!!
        assertEquals(0, next.dayIndex)
        assertEquals("sunrise", next.mode)
    }

    @Test
    fun middayLeadsWithTonightsSunset() {
        val next = nextForecastEvent(days(), local("2026-10-21T12:00"))!!
        assertEquals(0, next.dayIndex)
        assertEquals("sunset", next.mode)
    }

    @Test
    fun afterDarkRollsOverToTomorrowsSunrise() {
        val next = nextForecastEvent(days(), local("2026-10-21T21:30"))!!
        assertEquals(1, next.dayIndex)
        assertEquals("sunrise", next.mode)
    }

    @Test
    fun eveningPlanSchedulesTomorrowMorning() {
        val settings = AccountSettings(alertThreshold = 0, alertLeadMinutes = 60)
        val plans = SkyAlerts.plan(days(), place, settings, now = local("2026-10-21T21:30").toEpochMilli())
        val sunrise = plans.single { it.mode == "sunrise" && it.kind == "alert" }
        assertEquals(local("2026-10-22T07:35").toEpochMilli(), sunrise.eventAt)
        assertEquals(local("2026-10-22T06:35").toEpochMilli(), sunrise.triggerAt)
        val sunset = plans.single { it.mode == "sunset" && it.kind == "alert" }
        assertEquals(local("2026-10-22T18:20").toEpochMilli(), sunset.eventAt)
    }

    @Test
    fun planRespectsThresholdAndToggles() {
        val strict = SkyAlerts.plan(days(), place, AccountSettings(alertThreshold = 101), now = local("2026-10-21T05:00").toEpochMilli())
        assertTrue(strict.isEmpty())
        val sunsetOnly = SkyAlerts.plan(
            days(),
            place,
            AccountSettings(alertThreshold = 0, sunriseAlerts = false),
            now = local("2026-10-21T05:00").toEpochMilli(),
        )
        assertEquals(listOf("sunset"), sunsetOnly.filter { it.kind == "alert" }.map { it.mode })
        assertTrue(sunsetOnly.all { it.mode == "sunset" })
    }

    @Test
    fun feedbackIsSeparateFromThePreEventAlertAndFollowsTheWindow() {
        val forecastDays = days()
        val plans = SkyAlerts.plan(
            forecastDays, place, AccountSettings(alertThreshold = 0, postEventFeedbackAlerts = true),
            now = local("2026-10-21T05:00").toEpochMilli(),
        )
        val alert = plans.single { it.mode == "sunset" && it.kind == "alert" }
        val feedback = plans.single { it.mode == "sunset" && it.kind == "feedback" }
        val end = forecastDays.first().appearanceWindow.sunset.end ?: forecastDays.first().sunset
        assertEquals(end.toEpochMilli() + 25 * 60_000L, feedback.triggerAt)
        assertTrue(feedback.triggerAt > alert.triggerAt)
        assertTrue(feedback.id != alert.id)
    }

    @Test
    fun gridIgnoresEventsItDoesNotCover() {
        val samples = createGridPlaces(place)
        val row = JSONObject()
            .put("utc_offset_seconds", offsetSeconds)
            .put("hourly", hourlyJson(1))
            .put("daily", dailyJson(1))
        val payload = JSONArray().apply { repeat(samples.size) { put(row) } }
        val today = buildGrid(payload, place, "sunset", local("2026-10-21T18:20"))
        assertEquals(samples.size, today.size)
        val tomorrow = buildGrid(payload, place, "sunrise", local("2026-10-22T07:35"))
        assertTrue("a one-day grid must not score tomorrow", tomorrow.isEmpty())
    }

    @Test
    fun regionalContextOnlyTouchesItsOwnMode() {
        val day = days().first()
        val samples = createGridPlaces(place)
        val row = JSONObject()
            .put("utc_offset_seconds", offsetSeconds)
            .put("hourly", hourlyJson(1).put("cloud_cover_low", JSONArray().apply { repeat(24) { put(95.0) } }))
            .put("daily", dailyJson(1))
        val payload = JSONArray().apply { repeat(samples.size) { put(row) } }
        val sunsetGrid = buildGrid(payload, place, "sunset", day.sunset)
        val regional = applyRegionalContextToForecast(day, mapOf("sunset" to sunsetGrid), place)
        assertEquals(day.scores.sunrise.probability, regional.scores.sunrise.probability, 0.0001)
        assertTrue(regional.scores.sunset.probability < day.scores.sunset.probability)
    }
}
