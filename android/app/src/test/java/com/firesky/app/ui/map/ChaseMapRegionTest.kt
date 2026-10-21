package com.firesky.app.ui.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.tan

class ChaseMapRegionTest {
    private val newYork = ChaseMapRegion(42.9128, 38.5128, -78.4060, -69.6060)

    @Test fun portraitViewportCannotExposeUnsampledCornersAtAnyPermittedCenter() {
        assertCovered(newYork, 393.0, 780.0)
    }

    @Test fun landscapeViewportCannotExposeUnsampledCornersAtAnyPermittedCenter() {
        assertCovered(newYork, 850.0, 340.0)
    }

    @Test fun constraintsAdaptToNorthernLatitudesAndTabletSizes() {
        assertCovered(ChaseMapRegion(66.0, 61.6, -155.0, -146.2), 960.0, 1280.0)
    }

    @Test fun zoomingInMakesRoomToPanWithoutGrowingTheSampledRegion() {
        val minimum = newYork.minimumZoom(393.0, 780.0)
        val close = newYork.cameraTargets(minimum + 2.0, 393.0, 780.0)
        val wide = newYork.cameraTargets(minimum, 393.0, 780.0)
        assertTrue(close.north > wide.north)
        assertTrue(close.south < wide.south)
        assertTrue(close.east > wide.east)
        assertTrue(close.west < wide.west)
        assertEquals(42.9128, newYork.north, 0.000001)
        assertEquals(-78.4060, newYork.west, 0.000001)
    }

    @Test fun noSamplesCannotInventACoverageArea() {
        assertEquals(null, ChaseMapRegion.from(emptyList()))
        assertTrue(!newYork.contains(47.0, -74.0))
        assertTrue(!newYork.contains(40.0, Double.NaN))
    }

    private fun assertCovered(region: ChaseMapRegion, width: Double, height: Double) {
        val minimum = region.minimumZoom(width, height)
        for (zoom in listOf(minimum, minimum + 0.5, minimum + 3.0, 11.5)) {
            val targets = region.cameraTargets(zoom, width, height)
            val world = 512.0 * 2.0.pow(zoom)
            // Project all four extreme allowed camera centers, then recover each
            // screen corner. Every corner must remain in the sampled footprint.
            for (lat in listOf(targets.south, targets.north)) {
                for (lon in listOf(targets.west, targets.east)) {
                    val centerX = (lon + 180.0) / 360.0 * world
                    val centerY = (1.0 - ln(tan(PI / 4.0 + lat * PI / 360.0)) / PI) / 2.0 * world
                    for (dx in listOf(-width / 2, width / 2)) {
                        for (dy in listOf(-height / 2, height / 2)) {
                            val cornerLon = (centerX + dx) / world * 360.0 - 180.0
                            val cornerLat = (2.0 * atan(exp(PI * (1.0 - 2.0 * (centerY + dy) / world))) - PI / 2.0) * 180.0 / PI
                            assertTrue("viewport outside samples at zoom $zoom", cornerLat >= region.south - 1e-8 && cornerLat <= region.north + 1e-8 && cornerLon >= region.west - 1e-8 && cornerLon <= region.east + 1e-8)
                        }
                    }
                }
            }
        }
    }
}
