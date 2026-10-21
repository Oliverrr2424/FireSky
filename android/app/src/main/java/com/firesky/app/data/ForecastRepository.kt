package com.firesky.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GridKey(val mode: String, val dayIndex: Int)

/**
 * One place's outlook, scored the same way for the home screen, alerts and
 * snapshots. [days] already carry the regional context wherever [grids] has a
 * matching event, so every surface quotes the same percentage.
 */
data class ForecastResult(
    val place: Place,
    val days: List<Forecast>,
    val grids: Map<GridKey, List<ScoredPoint>> = emptyMap(),
)

class ForecastRepository(private val api: ApiClient) {
    /** Point-forecast days only: quick first paint before the regional grid arrives. */
    suspend fun loadDays(place: Place, force: Boolean = false): List<Forecast> {
        val bundle = api.fetchForecast(place, force)
        val days = withContext(Dispatchers.Default) { buildForecastDays(bundle) }
        if (days.isEmpty()) throw ApiException("The forecast came back incomplete. Try again in a moment.", true)
        return days
    }

    /** Scores the regional grid for every event it covers and folds it into [days]. */
    suspend fun regionalize(place: Place, days: List<Forecast>, force: Boolean = false): ForecastResult {
        val payload = api.fetchGridPayload(place, force)
        return withContext(Dispatchers.Default) {
            val grids = buildMap {
                days.forEachIndexed { index, day ->
                    MODES.forEach { mode ->
                        val grid = buildGrid(payload, place, mode, day[mode])
                        if (grid.isNotEmpty()) put(GridKey(mode, index), grid)
                    }
                }
            }
            val regional = days.mapIndexed { index, day ->
                applyRegionalContextToForecast(day, MODES.associateWith { grids[GridKey(it, index)].orEmpty() }, place)
            }
            ForecastResult(place, regional, grids)
        }
    }

    /** Full load for background work; falls back to point-only scores when the grid is unavailable. */
    suspend fun load(place: Place, force: Boolean = false): ForecastResult {
        val days = loadDays(place, force)
        return try {
            regionalize(place, days, force)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            ForecastResult(place, days)
        }
    }

    companion object {
        val MODES = listOf("sunrise", "sunset")
    }
}
