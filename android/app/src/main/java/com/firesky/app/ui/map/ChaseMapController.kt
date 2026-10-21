package com.firesky.app.ui.map

import android.graphics.Bitmap
import android.util.Log
import android.view.View
import com.firesky.app.BuildConfig
import com.firesky.app.data.Place
import com.firesky.app.data.ScoredPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import kotlin.math.max

private const val SOURCE_ID = "firesky-chase-heat-src"
private const val LAYER_ID = "firesky-chase-heat-layer"

/** Owns one local sample set. Camera motion never renders heat or fetches forecasts. */
internal class ChaseMapController(
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val density: Float,
    private val scope: CoroutineScope,
    private val onZoom: (Double, Double, Double) -> Unit,
    private val onHeatReady: (Boolean) -> Unit,
) {
    var region: ChaseMapRegion? = null
        private set
    private var style: Style? = null
    private var samples: List<ScoredPoint> = emptyList()
    private var place: Place? = null
    private var type = "probability"
    private val bitmaps = mutableMapOf<String, Bitmap>()
    private var renderJob: Job? = null
    private var applyingCamera = false
    private var minimumZoom = 7.2
    private val maximumZoom = 11.5
    private var width = 0
    private var height = 0
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        place?.let { setContent(it, samples, type) }
    }

    init {
        mapView.addOnLayoutChangeListener(layoutListener)
    }

    fun attachStyle(next: Style) {
        style = next
        enhanceMapReferenceStyle(next)
        render()
    }

    fun setContent(nextPlace: Place, nextSamples: List<ScoredPoint>, nextType: String) {
        val nextRegion = ChaseMapRegion.from(nextSamples)
        val reset = place?.sameSpot(nextPlace) != true || nextRegion != region
        val dataChanged = nextSamples != samples
        val typeChanged = nextType != type
        val resized = width != mapView.width || height != mapView.height
        if (dataChanged) {
            renderJob?.cancel()
            bitmaps.clear()
            onHeatReady(false)
            if (reset || nextSamples.isEmpty()) clearHeat()
        }
        samples = nextSamples
        region = nextRegion
        place = nextPlace
        type = nextType
        width = mapView.width
        height = mapView.height
        val available = nextRegion != null
        map.uiSettings.isScrollGesturesEnabled = available
        map.uiSettings.isZoomGesturesEnabled = available
        map.uiSettings.isDoubleTapGesturesEnabled = available
        if (available && width > 0 && height > 0 && (reset || resized)) {
            minimumZoom = nextRegion.minimumZoom(width / density.toDouble(), height / density.toDouble())
            applyingCamera = true
            try {
                map.setLatLngBoundsForCameraTarget(null)
                map.setMaxZoomPreference(max(maximumZoom, minimumZoom + 0.5))
                map.setMinZoomPreference(minimumZoom)
                map.setLatLngBoundsForCameraTarget(LatLngBounds.from(nextRegion.north, nextRegion.east, nextRegion.south, nextRegion.west))
                if (reset) {
                    map.moveCamera(CameraUpdateFactory.newLatLngZoom(
                        LatLng(nextPlace.latitude, nextPlace.longitude), max(7.2, minimumZoom + 0.35),
                    ))
                }
            } finally {
                applyingCamera = false
            }
            constrainCamera()
        }
        if (dataChanged || typeChanged) render()
    }

    fun constrainCamera() {
        if (applyingCamera || width <= 0 || height <= 0) return
        val footprint = region ?: return
        val camera = map.cameraPosition
        val targets = footprint.cameraTargets(camera.zoom, width / density.toDouble(), height / density.toDouble())
        applyingCamera = true
        try {
            val target = map.cameraPosition.target
            if (target != null && !targets.contains(target.latitude, target.longitude)) {
                map.moveCamera(CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(map.cameraPosition).target(LatLng(
                        target.latitude.coerceIn(targets.south, targets.north),
                        target.longitude.coerceIn(targets.west, targets.east),
                    )).build(),
                ))
            }
        } finally {
            applyingCamera = false
        }
        onZoom(map.cameraPosition.zoom, minimumZoom, map.maxZoomLevel)
    }

    fun center() {
        val origin = place ?: return
        moveTo(origin.latitude, origin.longitude, max(7.2, minimumZoom + 0.35))
    }

    fun moveTo(latitude: Double, longitude: Double, zoom: Double) {
        if (width <= 0 || height <= 0) return
        val footprint = region ?: return
        val nextZoom = zoom.coerceIn(minimumZoom, map.maxZoomLevel)
        val targets = footprint.cameraTargets(nextZoom, width / density.toDouble(), height / density.toDouble())
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(
            latitude.coerceIn(targets.south, targets.north), longitude.coerceIn(targets.west, targets.east),
        ), nextZoom))
    }

    fun auditViewport() {
        if (!BuildConfig.DEBUG) return
        val footprint = region ?: return
        val corners = map.projection.visibleRegion.latLngBounds
        val covered = corners.latitudeNorth <= footprint.north + 0.0001 && corners.latitudeSouth >= footprint.south - 0.0001 &&
            corners.longitudeWest >= footprint.west - 0.0001 && corners.longitudeEast <= footprint.east + 0.0001
        Log.d("ChaseMap", "zoom=${map.cameraPosition.zoom}, minimum=$minimumZoom, viewportCovered=$covered")
    }

    fun zoomBy(delta: Double) {
        val target = map.cameraPosition.target ?: return
        moveTo(target.latitude, target.longitude, map.cameraPosition.zoom + delta)
    }

    private fun render() {
        val activeStyle = style ?: return
        val footprint = region ?: run { clearHeat(); onHeatReady(false); return }
        val points = samples
        val mapType = type
        renderJob?.cancel()
        renderJob = scope.launch {
            try {
                val bitmap = bitmaps[mapType] ?: withContext(Dispatchers.Default) {
                    val ratio = footprint.aspectRatio
                    HeatField.render(
                        samples = points, type = mapType,
                        cssWidth = (if (ratio >= 1) 768.0 else 768.0 * ratio).toFloat(),
                        cssHeight = (if (ratio >= 1) 768.0 / ratio else 768.0).toFloat(),
                        viewport = HeatField.Viewport(footprint.north, footprint.south, footprint.west, footprint.east),
                        fast = false, lowPower = false,
                    )
                } ?: return@launch
                if (style !== activeStyle) return@launch
                bitmaps[mapType] = bitmap
                val existing = activeStyle.getSourceAs<ImageSource>(SOURCE_ID)
                if (existing != null) {
                    existing.setImage(bitmap)
                } else {
                    // Image coordinates are the sampled footprint, never the current screen corners.
                    val quad = LatLngQuad(
                        LatLng(footprint.north, footprint.west), LatLng(footprint.north, footprint.east),
                        LatLng(footprint.south, footprint.east), LatLng(footprint.south, footprint.west),
                    )
                    activeStyle.addSource(ImageSource(SOURCE_ID, quad, bitmap))
                    val layer = RasterLayer(LAYER_ID, SOURCE_ID).withProperties(
                        PropertyFactory.rasterOpacity(0.84f), PropertyFactory.rasterResampling("linear"),
                        PropertyFactory.rasterFadeDuration(0f),
                    )
                    firstSymbolLayerId(activeStyle)?.let { activeStyle.addLayerBelow(layer, it) }
                        ?: activeStyle.addLayer(layer)
                }
                onHeatReady(true)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onHeatReady(false)
                Log.w("ChaseMap", "Could not render the local heat field", error)
            }
        }
    }

    private fun clearHeat() {
        style?.let { active ->
            active.getLayer(LAYER_ID)?.let { active.removeLayer(it) }
            active.getSource(SOURCE_ID)?.let { active.removeSource(it) }
        }
    }

    fun destroy() {
        mapView.removeOnLayoutChangeListener(layoutListener)
        renderJob?.cancel()
        bitmaps.clear()
        style = null
    }
}
