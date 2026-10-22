package com.firesky.app.ui.map

import android.graphics.Bitmap
import com.firesky.app.data.ScoredPoint
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Port of the web heat field: Gaussian RBF over the regional sample grid,
 * evaluated in screen space and coloured with the same six-stop ramp.
 * The home cards use the web softness; the full Chase Map uses a tighter one.
 */
object HeatField {
    const val DEFAULT_SIGMA = 0.24
    const val CHASE_MAP_SIGMA = 0.14
    private const val CUTOFF_SQUARED = 16.0

    private val STOP_VALUES = doubleArrayOf(1.0, 24.0, 44.0, 62.0, 82.0, 100.0)
    private val STOP_COLORS = arrayOf(
        intArrayOf(36, 96, 151),
        intArrayOf(58, 156, 184),
        intArrayOf(108, 184, 129),
        intArrayOf(244, 207, 73),
        intArrayOf(237, 112, 62),
        intArrayOf(194, 54, 70),
    )

    data class Viewport(
        val north: Double,
        val south: Double,
        val west: Double,
        val east: Double,
    )

    fun colorStop(value: Double): IntArray {
        val v = value.coerceIn(1.0, 100.0)
        for (index in 0 until STOP_VALUES.size - 1) {
            val aValue = STOP_VALUES[index]
            val bValue = STOP_VALUES[index + 1]
            if (v >= aValue && v <= bValue) {
                val t = (v - aValue) / (bValue - aValue)
                val a = STOP_COLORS[index]
                val b = STOP_COLORS[index + 1]
                return intArrayOf(
                    (a[0] + (b[0] - a[0]) * t).roundToInt(),
                    (a[1] + (b[1] - a[1]) * t).roundToInt(),
                    (a[2] + (b[2] - a[2]) * t).roundToInt(),
                )
            }
        }
        return STOP_COLORS.last()
    }

    fun interpolate(
        lat: Double,
        lon: Double,
        latArray: DoubleArray,
        lonArray: DoubleArray,
        valueArray: DoubleArray,
        sigma: Double = DEFAULT_SIGMA,
    ): Double {
        var weighted = 0.0
        var weights = 0.0
        val sig2Inv = 1.0 / (2.0 * sigma * sigma)
        val latScale = cos(lat * PI / 180.0)
        for (index in latArray.indices) {
            val dx = (lon - lonArray[index]) * latScale
            val dy = lat - latArray[index]
            val d2 = dx * dx + dy * dy
            if (d2 > CUTOFF_SQUARED) continue
            val weight = exp(-d2 * sig2Inv)
            weighted += valueArray[index] * weight
            weights += weight
        }
        return if (weights != 0.0) weighted / weights else 0.0
    }

    fun render(
        samples: List<ScoredPoint>,
        type: String,
        cssWidth: Float,
        cssHeight: Float,
        viewport: Viewport,
        fast: Boolean,
        lowPower: Boolean,
        sigma: Double = DEFAULT_SIGMA,
    ): Bitmap? {
        if (samples.isEmpty() || cssWidth <= 0f || cssHeight <= 0f) return null
        val lowPowerMultiplier = if (lowPower) 1.35 else 1.0
        val resolution = (if (fast) {
            if (cssWidth > 1400f) 2.4 else 1.9
        } else {
            if (cssWidth > 1400f) 1.2 else 0.95
        }) * lowPowerMultiplier
        val width = max(1, (cssWidth / resolution).roundToInt())
        val height = max(1, (cssHeight / resolution).roundToInt())

        val latArray = DoubleArray(samples.size) { samples[it].latitude }
        val lonArray = DoubleArray(samples.size) { samples[it].longitude }
        val valueArray = DoubleArray(samples.size) {
            if (type == "probability") samples[it].probability else samples[it].quality
        }
        val pixels = IntArray(width * height)
        val widthScale = max(1, width - 1).toDouble()
        val heightScale = max(1, height - 1).toDouble()

        val mercNorth = mercatorY(viewport.north)
        val mercSouth = mercatorY(viewport.south)
        val lonSpan = viewport.east - viewport.west

        for (y in 0 until height) {
            val t = y / heightScale
            val lat = inverseMercatorY(mercNorth + (mercSouth - mercNorth) * t)
            // MapLibre's ImageSource samples bitmap row 0 at the south edge, so
            // write the north-to-south scanlines bottom-up to keep north up.
            val rowStart = (height - 1 - y) * width
            for (x in 0 until width) {
                val lon = viewport.west + lonSpan * (x / widthScale)
                val value = interpolate(lat, lon, latArray, lonArray, valueArray, sigma)
                val color = colorStop(value)
                val alpha = (0.3 + value / 190.0).coerceIn(0.3, 0.78)
                // The web's custom layer is composited through a premultiplied
                // canvas, which lifts the heat toward the card surface. A small
                // neutral wash reproduces that measured appearance.
                val r = (color[0] * 0.84 + 235 * 0.16).roundToInt()
                val g = (color[1] * 0.84 + 240 * 0.16).roundToInt()
                val b = (color[2] * 0.84 + 245 * 0.16).roundToInt()
                pixels[rowStart + x] = (Math.round(alpha * 255.0).toInt() shl 24) or
                    (r shl 16) or (g shl 8) or b
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun mercatorY(latitude: Double): Double {
        val clamped = latitude.coerceIn(-85.05112878, 85.05112878)
        val rad = clamped * PI / 180.0
        return ln(tan(PI / 4.0 + rad / 2.0))
    }

    private fun inverseMercatorY(y: Double): Double =
        (2.0 * atan(exp(y)) - PI / 2.0) * 180.0 / PI
}
