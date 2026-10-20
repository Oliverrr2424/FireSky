package com.firesky.app.ui.map

import android.graphics.PointF
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.firesky.app.data.MAP_STYLE_URL
import com.firesky.app.data.Place
import com.firesky.app.data.ScoredPoint
import com.firesky.app.ui.DisplayText
import com.firesky.app.ui.GlassCard
import com.firesky.app.ui.LabelText
import com.firesky.app.ui.LucideIcon
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.SurfaceHairline
import com.firesky.app.ui.theme.SurfacePanel
import com.firesky.app.ui.theme.TextMuted
import com.firesky.app.ui.theme.TextPrimary
import com.firesky.app.ui.theme.TextSecondary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLngQuad
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.RasterLayer
import org.maplibre.android.style.sources.ImageSource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val HEAT_ENABLED = true
private const val HEAT_SOURCE_ID = "fire-sky-heat-src"
private const val HEAT_LAYER_ID = "fire-sky-heat"

data class MapLabel(val x: Float, val y: Float, val value: Double)

@Composable
fun HeatMapCard(
    place: Place,
    samples: List<ScoredPoint>?,
    type: String,
    mode: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onSampleSelected: (ScoredPoint?) -> Unit = {},
) {
    GlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(if (compact) 10.dp else 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = if (type == "probability") "PROBABILITY" else "INTENSITY",
                color = TextSecondary,
                fontFamily = DisplayFamily,
                fontSize = (if (compact) 9 else 13).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
                letterSpacing = (if (compact) 0.8 else 1.4).sp,
            )
            if (!compact) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(com.firesky.app.ui.theme.SurfaceControl)
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = if (mode == "sunset") "Sunset" else "Sunrise",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
        Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
        HeatMap(
            place = place,
            samples = samples,
            type = type,
            compact = compact,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(if (compact) 16f / 10f else 16f / 9f)
                .clip(RoundedCornerShape(if (compact) 16.dp else 21.dp)),
            onSampleSelected = onSampleSelected,
        )
    }
}

@Composable
private fun HeatMap(
    place: Place,
    samples: List<ScoredPoint>?,
    type: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
    onSampleSelected: (ScoredPoint?) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val lowPower = remember {
        val activityManager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as android.app.ActivityManager
        activityManager.isLowRamDevice || Runtime.getRuntime().availableProcessors() <= 4
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(
                CameraPosition.Builder()
                    .target(LatLng(place.latitude, place.longitude))
                    .zoom(7.0)
                    .build(),
            )
            .textureMode(true)
            .logoEnabled(false)
            .attributionEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .scrollGesturesEnabled(false)
            .zoomGesturesEnabled(true)
            .doubleTapGesturesEnabled(true)
            .quickZoomGesturesEnabled(false)
        MapView(context, options).apply { onCreate(null) }
    }
    var controller by remember { mutableStateOf<HeatMapController?>(null) }
    var mapSettled by remember { mutableStateOf(false) }
    var heatReady by remember { mutableStateOf(false) }
    var labels by remember { mutableStateOf<List<MapLabel>>(emptyList()) }
    var zoomLevel by remember { mutableDoubleStateOf(7.0) }
    var zoomLimits by remember { mutableStateOf(4.0 to 8.75) }
    var selected by remember { mutableStateOf<ScoredPoint?>(null) }

    val isReady = mapSettled && heatReady
    val loadingAlpha by animateFloatAsState(if (isReady) 0f else 1f, tween(260), label = "loadingAlpha")

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(Unit) {
        onDispose {
            controller?.destroy()
            runCatching {
                mapView.onPause()
                mapView.onStop()
                mapView.onDestroy()
            }
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map ->
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isScrollGesturesEnabled = false
            map.uiSettings.isZoomGesturesEnabled = true
            map.uiSettings.isDoubleTapGesturesEnabled = true
            map.uiSettings.isQuickZoomGesturesEnabled = false
            map.setMaxZoomPreference(8.75)

            val next = HeatMapController(
                mapView = mapView,
                map = map,
                scope = scope,
                density = density,
                lowPower = lowPower,
                onSettled = { mapSettled = true },
                onHeatReady = { heatReady = true },
                onLabels = { labels = it },
                onZoom = { zoomLevel = it },
                onLimits = { minZoom, maxZoom -> zoomLimits = minZoom to maxZoom },
                onSelect = {
                    selected = it
                    onSampleSelected(it)
                },
            )
            controller = next
            map.addOnCameraIdleListener {
                zoomLevel = map.cameraPosition.zoom
                next.onCameraIdle()
            }
            map.addOnCameraMoveListener {
                zoomLevel = map.cameraPosition.zoom
                next.updateLabels()
            }
            map.addOnMapClickListener { latLng ->
                next.selectNearest(latLng)
                true
            }
            map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                next.attachStyle(style)
            }
        }
    }

    LaunchedEffect(controller, samples, type, compact, place) {
        val active = controller ?: return@LaunchedEffect
        mapSettled = false
        heatReady = false
        selected = null
        onSampleSelected(null)
        active.setSamples(samples.orEmpty(), type, compact, place)
    }

    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { mapView },
            update = { /* MapView owns its own render loop. */ },
        )

        if (isReady) {
            Canvas(Modifier.fillMaxSize()) {
                drawRect(
                    brush = Brush.radialGradient(
                        0f to Color.Transparent,
                        0.58f to Color.Transparent,
                        1f to Color(0x24081426),
                        center = Offset(size.width / 2f, size.height / 2f),
                        radius = max(size.width, size.height) * 0.62f,
                    ),
                )
                drawRect(
                    brush = Brush.verticalGradient(
                        listOf(Color.Transparent, Color(0x0F122A48)),
                    ),
                )
            }
            MapLabels(labels = labels, compact = compact)
        }

        if (isReady && !compact) {
            MapZoomControls(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                canZoomIn = zoomLevel < zoomLimits.second - 0.05,
                canZoomOut = zoomLevel > zoomLimits.first + 0.05,
                onZoomIn = { controller?.zoomIn() },
                onZoomOut = { controller?.zoomOut() },
            )
        }

        if (isReady && !compact) {
            MapLegend(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp),
            )
        }

        if (selected != null) {
            SelectedSamplePanel(
                sample = selected!!,
                type = type,
                compact = compact,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(if (compact) 6.dp else 10.dp),
            )
        }

        Text(
            text = "OpenFreeMap © OpenMapTiles Data from OpenStreetMap",
            color = Color(0xDB1C2736),
            fontSize = 8.sp,
            lineHeight = 9.6.sp,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0x9EFFFFFF))
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )

        if (loadingAlpha > 0f) {
            MapLoadingSurface(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(loadingAlpha),
            )
        }
    }
}

@Composable
private fun MapLabels(labels: List<MapLabel>, compact: Boolean) {
    labels.forEach { label ->
        Row(
            modifier = Modifier
                .offset { IntOffset(label.x.roundToInt(), label.y.roundToInt()) }
                .graphicsLayer {
                    translationX = -size.width / 2f
                    translationY = -size.height / 2f
                }
                .height(if (compact) 18.dp else 22.dp)
                .clip(CircleShape)
                .background(Color(0x57EEF8FF))
                .border(1.dp, Color(0x47FFFFFF), CircleShape)
                .padding(horizontal = if (compact) 5.dp else 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                Modifier
                    .size(if (compact) 4.dp else 5.dp)
                    .clip(CircleShape)
                    .background(Color(0xE6FFFFFF)),
            )
            Text(
                text = "${Math.round(label.value)}%",
                color = Color(0xDBFFFFFF),
                fontFamily = DisplayFamily,
                fontSize = (if (compact) 8 else 10).sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.W800,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MapZoomControls(
    modifier: Modifier = Modifier,
    canZoomIn: Boolean,
    canZoomOut: Boolean,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ZoomButton("+", enabled = canZoomIn, onClick = onZoomIn)
        ZoomButton("−", enabled = canZoomOut, onClick = onZoomOut)
    }
}

@Composable
private fun ZoomButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfacePanel)
            .border(1.dp, SurfaceHairline, RoundedCornerShape(10.dp))
            .alpha(if (enabled) 1f else 0.42f)
            .then(if (enabled) Modifier.androidClickable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontFamily = DisplayFamily,
            fontSize = 20.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.W500,
            lineHeight = 20.sp,
        )
    }
}

private fun Modifier.androidClickable(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun MapLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfacePanel)
            .border(1.dp, SurfaceHairline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Text("1%", color = TextPrimary, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
        Box(
            Modifier
                .width(150.dp)
                .height(8.dp)
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF246097),
                            Color(0xFF3A9CB8),
                            Color(0xFF6CB881),
                            Color(0xFFF4CF49),
                            Color(0xFFED703E),
                            Color(0xFFC23646),
                        ),
                    ),
                ),
        )
        Text("100%", color = TextPrimary, fontSize = 11.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.W700)
    }
}

@Composable
private fun SelectedSamplePanel(
    sample: ScoredPoint,
    type: String,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(Color(0xC7101F36))
            .border(1.dp, Color(0x73FFFFFF), RoundedCornerShape(9.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(
            "Selected area",
            color = Color(0xBFFFFFFF),
            fontSize = (if (compact) 7 else 9).sp,
        )
        Text(
            "${Math.round(if (type == "probability") sample.probability else sample.quality)}%",
            color = Color.White,
            fontFamily = DisplayFamily,
            fontSize = (if (compact) 12 else 17).sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.W700,
            lineHeight = (if (compact) 13 else 18).sp,
        )
        Text(
            "${String.format(java.util.Locale.US, "%.2f", sample.latitude)}, " +
                String.format(java.util.Locale.US, "%.2f", sample.longitude),
            color = Color(0xBFFFFFFF),
            fontSize = (if (compact) 7 else 9).sp,
        )
    }
}

@Composable
private fun MapLoadingSurface(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "mapLoading")
    val sheen by transition.animateFloat(
        initialValue = -0.45f,
        targetValue = 0.45f,
        animationSpec = infiniteRepeatable(
            animation = tween(1450, easing = LinearEasing),
        ),
        label = "sheen",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(0.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0x4778A8CD), Color(0x2E3A5D82), Color(0x47163052)),
                ),
            ),
    ) {
        val grid = 54.dp.toPx()
        var x = 0f
        while (x < size.width) {
            drawLine(Color(0x14FFFFFF), Offset(x, 0f), Offset(x, size.height), 1f)
            x += grid
        }
        var y = 0f
        while (y < size.height) {
            drawLine(Color(0x14FFFFFF), Offset(0f, y), Offset(size.width, y), 1f)
            y += grid
        }
        val contourColor = Color(0x2EFFFFFF)
        rotate(-12f, pivot = Offset(size.width * 0.41f, size.height * 0.35f)) {
            drawOval(
                color = contourColor,
                topLeft = Offset(size.width * 0.12f, size.height * 0.17f),
                size = Size(size.width * 0.58f, size.height * 0.36f),
                style = Stroke(1f),
            )
        }
        rotate(18f, pivot = Offset(size.width * 0.68f, size.height * 0.6f)) {
            drawOval(
                color = contourColor,
                topLeft = Offset(size.width * 0.48f, size.height * 0.45f),
                size = Size(size.width * 0.42f, size.height * 0.31f),
                style = Stroke(1f),
            )
        }
        rotate(9f, pivot = Offset(size.width * 0.36f, size.height * 0.77f)) {
            drawOval(
                color = contourColor,
                topLeft = Offset(size.width * 0.21f, size.height * 0.64f),
                size = Size(size.width * 0.3f, size.height * 0.26f),
                style = Stroke(1f),
            )
        }
        val sheenWidth = size.width * 0.32f
        drawRect(
            brush = Brush.linearGradient(
                listOf(Color.Transparent, Color(0x38FFFFFF), Color.Transparent),
                start = Offset(sheen * size.width, 0f),
                end = Offset(sheen * size.width + sheenWidth, size.height),
            ),
            size = size,
        )
        val radius = 43.dp.toPx() * pulse
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(Color(0x38FFFFFF), radius * 0.2f, center)
        drawCircle(Color(0x1FFFFFFF), radius * 0.38f, center)
        drawCircle(Color(0x47FFFFFF), radius, center, style = Stroke(1.5f))
    }
}

private class HeatMapController(
    private val mapView: MapView,
    private val map: MapLibreMap,
    private val scope: CoroutineScope,
    private val density: Float,
    private val lowPower: Boolean,
    private val onSettled: () -> Unit,
    private val onHeatReady: () -> Unit,
    private val onLabels: (List<MapLabel>) -> Unit,
    private val onZoom: (Double) -> Unit,
    private val onLimits: (Double, Double) -> Unit,
    private val onSelect: (ScoredPoint?) -> Unit,
) {
    private var style: Style? = null
    private var firstSymbolId: String? = null
    private var samples: List<ScoredPoint> = emptyList()
    private var type: String = "probability"
    private var compact: Boolean = true
    private var place: Place? = null
    private var renderJob: Job? = null
    private var settleJob: Job? = null
    private var settlePending = false

    fun attachStyle(next: Style) {
        style = next
        enhanceMapReferenceStyle(next)
        firstSymbolId = firstSymbolLayerId(next)
        if (samples.isNotEmpty()) {
            updateLabels()
            place?.let { fitBounds(it) }
            renderHeat(fast = true)
        }
    }

    fun setSamples(nextSamples: List<ScoredPoint>, nextType: String, nextCompact: Boolean, nextPlace: Place) {
        samples = nextSamples
        type = nextType
        compact = nextCompact
        place = nextPlace
        onSelect(null)
        if (style == null) return
        updateLabels()
        fitBounds(nextPlace)
        renderHeat(fast = true)
    }

    fun onCameraIdle() {
        if (settlePending) {
            settlePending = false
            settleJob?.cancel()
            onSettled()
        }
        renderHeat(fast = true)
    }

    fun zoomIn() {
        map.animateCamera(CameraUpdateFactory.zoomIn(), 220)
    }

    fun zoomOut() {
        map.animateCamera(CameraUpdateFactory.zoomOut(), 220)
    }

    fun selectNearest(latLng: LatLng) {
        if (samples.isEmpty()) return
        val nearest = samples.minByOrNull { sample ->
            val dLat = sample.latitude - latLng.latitude
            val dLon = sample.longitude - latLng.longitude
            dLat * dLat + dLon * dLon
        } ?: return
        onSelect(nearest)
    }

    fun updateLabels() {
        if (samples.isEmpty() || style == null) {
            onLabels(emptyList())
            return
        }
        val ranked = samples
            .filter { val value = valueOf(it); !value.isNaN() }
            .sortedByDescending { valueOf(it) }
        if (ranked.isEmpty()) {
            onLabels(emptyList())
            return
        }
        val indexes = listOf(0.16, 0.5, 0.84).map { ratio ->
            min(ranked.size - 1, (Math.round((ranked.size - 1) * ratio).toInt()))
        }
        val picked = mutableListOf<ScoredPoint>()
        indexes.forEach { index ->
            val candidate = ranked[index]
            if (picked.none { it.sampleId == candidate.sampleId }) picked.add(candidate)
        }
        onLabels(
            picked.take(3).map { sample ->
                val point = map.projection.toScreenLocation(LatLng(sample.latitude, sample.longitude))
                MapLabel(point.x / density, point.y / density, valueOf(sample))
            },
        )
    }

    private fun valueOf(sample: ScoredPoint): Double =
        if (type == "probability") sample.probability else sample.quality

    private fun fitBounds(nextPlace: Place) {
        val activeSamples = samples
        if (activeSamples.isEmpty()) {
            map.animateCamera(
                CameraUpdateFactory.newLatLngZoom(LatLng(nextPlace.latitude, nextPlace.longitude), map.cameraPosition.zoom),
                650,
            )
            markSettling()
            return
        }
        val builder = LatLngBounds.Builder()
        activeSamples.forEach { builder.include(LatLng(it.latitude, it.longitude)) }
        val bounds = builder.build()
        val west = bounds.longitudeWest
        val east = bounds.longitudeEast
        val south = bounds.latitudeSouth
        val north = bounds.latitudeNorth
        val lonPad = max(0.08, (east - west) * 0.08)
        val latPad = max(0.06, (north - south) * 0.08)
        val padded = LatLngBounds.Builder()
            .include(LatLng(south - latPad, west - lonPad))
            .include(LatLng(north + latPad, east + lonPad))
            .build()
        map.setLatLngBoundsForCameraTarget(padded)

        // The web passes [18, 18] / [30, 30] arrays to MapLibre GL JS, which
        // ignores array padding, so the reference fit has zero padding. Match it.
        val paddingPx = 0
        val camera = map.getCameraForLatLngBounds(bounds, intArrayOf(paddingPx, paddingPx, paddingPx, paddingPx))
        val fitZoom = camera?.zoom ?: 7.0
        val minZoom = max(2.0, min(fitZoom, 7.0) - 0.03)
        val maxZoom = max(minZoom + 0.5, 8.75)
        map.setMinZoomPreference(minZoom)
        map.setMaxZoomPreference(maxZoom)
        onLimits(minZoom, maxZoom)
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, paddingPx), 650)
        markSettling()
    }

    private fun markSettling() {
        settlePending = true
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(2200)
            if (settlePending) {
                settlePending = false
                onSettled()
            }
        }
    }

    private fun renderHeat(fast: Boolean) {
        val activeStyle = style ?: return
        if (samples.isEmpty()) return
        renderJob?.cancel()
        renderJob = scope.launch {
            try {
            val widthPx = mapView.width.toFloat()
            val heightPx = mapView.height.toFloat()
            if (widthPx <= 0f || heightPx <= 0f) return@launch
            val topLeft = map.projection.fromScreenLocation(PointF(0f, 0f))
            val topRight = map.projection.fromScreenLocation(PointF(widthPx, 0f))
            val bottomRight = map.projection.fromScreenLocation(PointF(widthPx, heightPx))
            val bottomLeft = map.projection.fromScreenLocation(PointF(0f, heightPx))
            val viewport = HeatField.Viewport(
                north = topLeft.latitude,
                south = bottomLeft.latitude,
                west = topLeft.longitude,
                east = topRight.longitude,
            )
            val quad = LatLngQuad(topLeft, topRight, bottomRight, bottomLeft)
            val bitmap = withContext(Dispatchers.Default) {
                HeatField.render(
                    samples = samples,
                    type = type,
                    cssWidth = widthPx / density,
                    cssHeight = heightPx / density,
                    viewport = viewport,
                    fast = fast,
                    lowPower = lowPower,
                )
            } ?: return@launch
            withContext(Dispatchers.Main) {
                if (style !== activeStyle) return@withContext
                if (HEAT_ENABLED) ensureHeatLayer(activeStyle, quad, bitmap)
                onHeatReady()
            }
            if (fast) {
                delay(850)
                renderHeat(false)
            }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                // Style updates can race with map teardown; the next idle retries.
            }
        }
    }

    private fun ensureHeatLayer(activeStyle: Style, quad: LatLngQuad, bitmap: android.graphics.Bitmap) {
        val existing = activeStyle.getSourceAs<ImageSource>(HEAT_SOURCE_ID)
        if (existing != null) {
            existing.setImage(bitmap)
            existing.setCoordinates(quad)
            return
        }
        try {
            activeStyle.addSource(ImageSource(HEAT_SOURCE_ID, quad, bitmap))
            val layer = RasterLayer(HEAT_LAYER_ID, HEAT_SOURCE_ID).withProperties(
                PropertyFactory.rasterOpacity(1f),
                PropertyFactory.rasterResampling("linear"),
                PropertyFactory.rasterFadeDuration(0f),
            )
            val before = firstSymbolId
            if (before != null) {
                activeStyle.addLayerBelow(layer, before)
            } else {
                activeStyle.addLayer(layer)
            }
            addReferenceLineOverlays(activeStyle, before)
        } catch (_: Exception) {
            // Style updates can race with teardown; the next camera idle retries.
        }
    }

    fun destroy() {
        renderJob?.cancel()
        settleJob?.cancel()
        style = null
    }
}
