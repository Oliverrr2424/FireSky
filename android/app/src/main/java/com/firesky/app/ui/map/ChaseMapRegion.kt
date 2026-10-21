package com.firesky.app.ui.map

import com.firesky.app.data.ScoredPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/** The sampled footprint is fixed to geography, independently of the camera. */
internal data class ChaseMapRegion(val north: Double, val south: Double, val west: Double, val east: Double) {
    private val top get() = mercatorY(north)
    private val bottom get() = mercatorY(south)
    val aspectRatio get() = ((east - west) / 360.0) / (bottom - top)

    fun contains(latitude: Double, longitude: Double): Boolean =
        latitude in south..north && longitude in west..east

    /** MapLibre zoom uses a 512 dp Mercator world. Fill the viewport, never expose unsampled edges. */
    fun minimumZoom(widthDp: Double, heightDp: Double): Double {
        require(widthDp > 0 && heightDp > 0)
        return max(
            ln(widthDp / (512.0 * (east - west) / 360.0)),
            ln(heightDp / (512.0 * (bottom - top))),
        ) / ln(2.0) + 0.02
    }

    /** A center-only camera constraint must be inset by half of the visible viewport. */
    fun cameraTargets(zoom: Double, widthDp: Double, heightDp: Double): ChaseMapRegion {
        val world = 512.0 * 2.0.pow(max(zoom, minimumZoom(widthDp, heightDp)))
        val halfLongitude = widthDp / world * 180.0
        val halfHeight = heightDp / world / 2.0
        return ChaseMapRegion(
            north = latitude(top + halfHeight),
            south = latitude(bottom - halfHeight),
            west = west + halfLongitude,
            east = east - halfLongitude,
        )
    }

    companion object {
        fun from(samples: List<ScoredPoint>): ChaseMapRegion? {
            val valid = samples.filter {
                it.latitude.isFinite() && it.longitude.isFinite() &&
                    it.latitude in -85.0..85.0 && it.longitude in -180.0..180.0
            }
            if (valid.isEmpty()) return null
            val region = ChaseMapRegion(
                north = valid.maxOf { it.latitude }, south = valid.minOf { it.latitude },
                west = valid.minOf { it.longitude }, east = valid.maxOf { it.longitude },
            )
            return region.takeIf { it.north - it.south > 0.01 && it.east - it.west > 0.01 }
        }

        private fun mercatorY(latitude: Double): Double =
            (1.0 - ln(tan(PI / 4.0 + latitude * PI / 360.0)) / PI) / 2.0

        private fun latitude(y: Double): Double =
            (2.0 * atan(exp(PI * (1.0 - 2.0 * y))) - PI / 2.0) * 180.0 / PI
    }
}
