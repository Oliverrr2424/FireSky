package com.firesky.app.ui.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.firesky.app.ChaseSpotState
import com.firesky.app.R
import com.firesky.app.UiState
import com.firesky.app.data.MAP_STYLE_URL
import com.firesky.app.data.Place
import com.firesky.app.data.ScoredPoint
import com.firesky.app.data.bearingDegrees
import com.firesky.app.data.compassLabel
import com.firesky.app.data.distanceKm
import com.firesky.app.data.formatDistanceKm
import com.firesky.app.ui.LucideIcon
import com.firesky.app.ui.localizedScoreLabel
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.SurfaceControl
import com.firesky.app.ui.theme.SurfaceHairline
import com.firesky.app.ui.theme.SurfaceInset
import com.firesky.app.ui.theme.TextMuted
import com.firesky.app.ui.theme.TextPrimary
import com.firesky.app.ui.theme.TextSecondary
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ChaseMapScreen(
    state: UiState,
    onSelectMode: (String) -> Unit,
    onInspectPoint: (Double, Double) -> Unit,
    onUseSpotAsPlace: (Place) -> Unit,
    onSaveSpotViewpoint: (String, Double, Double) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var mapType by remember { mutableStateOf("probability") }
    var mapRef by remember { mutableStateOf<MapLibreMap?>(null) }
    var controller by remember { mutableStateOf<ChaseMapController?>(null) }
    var mapSize by remember { mutableStateOf(IntSize.Zero) }
    var heatReady by remember { mutableStateOf(false) }
    var zoomLevel by remember { mutableDoubleStateOf(7.2) }
    var minimumZoom by remember { mutableDoubleStateOf(7.2) }
    var maximumZoom by remember { mutableDoubleStateOf(11.5) }
    var pinScreenPos by remember { mutableStateOf<Offset?>(null) }
    val latestSpot by rememberUpdatedState(state.chaseSpot)
    val inspectPoint by rememberUpdatedState(onInspectPoint)

    val samples = state.grid.orEmpty()
    val bestSample: ScoredPoint? = remember(samples, mapType) {
        samples.maxByOrNull { if (mapType == "probability") it.probability else it.quality }
    }

    val mapView = remember {
        MapLibre.getInstance(context)
        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(
                CameraPosition.Builder()
                    .target(LatLng(state.place.latitude, state.place.longitude))
                    .zoom(7.2)
                    .build(),
            )
            .textureMode(true)
            .logoEnabled(false)
            .attributionEnabled(false)
            .rotateGesturesEnabled(false)
            .tiltGesturesEnabled(false)
            .scrollGesturesEnabled(true)
            .zoomGesturesEnabled(true)
            .doubleTapGesturesEnabled(true)
        MapView(context, options).apply { onCreate(null) }
    }

    fun updatePinPosition() {
        val map = mapRef ?: return
        val spot = latestSpot ?: run {
            pinScreenPos = null
            return
        }
        val pt = map.projection.toScreenLocation(LatLng(spot.latitude, spot.longitude))
        pinScreenPos = Offset(pt.x, pt.y)
    }

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
            mapRef = map
            map.uiSettings.isCompassEnabled = false
            map.uiSettings.isLogoEnabled = false
            map.uiSettings.isAttributionEnabled = false
            map.uiSettings.isRotateGesturesEnabled = false
            map.uiSettings.isTiltGesturesEnabled = false
            map.uiSettings.isScrollGesturesEnabled = true
            map.uiSettings.isZoomGesturesEnabled = true
            map.uiSettings.isDoubleTapGesturesEnabled = true
            val active = ChaseMapController(
                mapView, map, density, scope,
                onZoom = { zoom, lower, upper ->
                    zoomLevel = zoom
                    minimumZoom = lower
                    maximumZoom = upper
                },
                onHeatReady = { heatReady = it },
            )
            controller = active
            map.addOnCameraIdleListener {
                active.constrainCamera()
                updatePinPosition()
                active.auditViewport()
            }
            map.addOnCameraMoveListener {
                active.constrainCamera()
                updatePinPosition()
            }
            map.addOnMapClickListener { point ->
                if (active.region?.contains(point.latitude, point.longitude) == true) {
                    inspectPoint(point.latitude, point.longitude)
                }
                true
            }
            map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { active.attachStyle(it) }
        }
    }

    LaunchedEffect(controller, state.place, samples, mapType, mapSize) {
        controller?.setContent(state.place, samples, mapType)
    }

    LaunchedEffect(samples, state.activeMode, state.selectedDayIndex) {
        latestSpot?.let { spot ->
            if (ChaseMapRegion.from(samples)?.contains(spot.latitude, spot.longitude) == true) {
                inspectPoint(spot.latitude, spot.longitude)
            }
        }
    }

    LaunchedEffect(state.chaseSpot) {
        updatePinPosition()
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize().onSizeChanged { mapSize = it },
            factory = { mapView },
        )

        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(110.dp).background(
            Brush.verticalGradient(listOf(Color(0x990D1A2C), Color.Transparent)),
        ))

        // Selected chase pin marker on screen
        val pin = pinScreenPos
        if (pin != null && state.chaseSpot != null) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(pin.x.roundToInt() - 22.dp.roundToPx(), pin.y.roundToInt() - 22.dp.roundToPx()) }
                    .size(44.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(Color(0x55FFD76A), radius = size.minDimension / 2f)
                    drawCircle(Color(0xFFFFD76A), radius = size.minDimension / 2.6f, style = Stroke(width = 2.dp.toPx()))
                    drawCircle(Color(0xFFFF6A3D), radius = 6.dp.toPx())
                }
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = 520.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(ChasePanel)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        stringResource(R.string.chase_map_title), color = ChaseMuted,
                        fontSize = 11.sp, fontWeight = FontWeight.W600, letterSpacing = 1.2.sp,
                    )
                    Text(
                        state.place.name, color = Color.White, fontFamily = DisplayFamily,
                        fontSize = 23.sp, fontWeight = FontWeight.W600, lineHeight = 27.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
                val centerLabel = stringResource(R.string.chase_center_map)
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.09f))
                        .semantics { contentDescription = centerLabel }
                        .clickable(role = Role.Button) { controller?.center() },
                    contentAlignment = Alignment.Center,
                ) {
                    LucideIcon(R.drawable.ic_locate_fixed, null, tint = Color.White, size = 21.dp)
                }
            }
            ChaseSegments(
                choices = listOf("sunrise" to stringResource(R.string.mode_sunrise), "sunset" to stringResource(R.string.mode_sunset)),
                selected = state.activeMode, onSelect = onSelectMode,
            )
            ChaseSegments(
                choices = listOf("probability" to stringResource(R.string.label_probability), "quality" to stringResource(R.string.label_intensity)),
                selected = mapType, onSelect = { mapType = it }, subtle = true,
            )
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChaseZoomBtn("+", stringResource(R.string.chase_zoom_in), samples.isNotEmpty() && zoomLevel < maximumZoom - 0.02) { controller?.zoomBy(1.0) }
            ChaseZoomBtn("−", stringResource(R.string.chase_zoom_out), samples.isNotEmpty() && zoomLevel > minimumZoom + 0.02) { controller?.zoomBy(-1.0) }
        }

        if (state.chaseSpot == null) {
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(16.dp).widthIn(max = 520.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom,
            ) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(ChasePanel).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(stringResource(R.string.chase_nearby_area), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.W500)
                    Box(Modifier.width(112.dp).height(5.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(
                        Color(0xFF246097), Color(0xFF6CB881), Color(0xFFF4CF49), Color(0xFFC23646),
                    ))))
                    if (!heatReady) {
                        Text(stringResource(if (state.grid?.isEmpty() == true) R.string.chase_unavailable else R.string.chase_loading), color = ChaseMuted, fontSize = 11.sp)
                    }
                }
                bestSample?.let { best ->
                    Row(
                        Modifier.heightIn(min = 48.dp).clip(CircleShape).background(Color(0xFFFFF6DF))
                            .clickable(role = Role.Button) {
                                controller?.moveTo(best.latitude, best.longitude, 8.1)
                                inspectPoint(best.latitude, best.longitude)
                            }.padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LucideIcon(R.drawable.ic_sparkles, null, tint = Color(0xFF785319), size = 16.dp)
                        Column {
                            Text(stringResource(R.string.chase_best_spot), color = Color(0xFF3C301C), fontSize = 11.sp, fontWeight = FontWeight.W500)
                            Text("${Math.round(if (mapType == "probability") best.probability else best.quality)}%", color = Color(0xFF211C15), fontFamily = DisplayFamily, fontSize = 18.sp, fontWeight = FontWeight.W700)
                        }
                    }
                }
            }
        }

        // Bottom Inspection Sheet when any point is tapped
        val spot = state.chaseSpot
        if (spot != null) {
            ChaseSpotBottomCard(
                spot = spot,
                origin = state.place,
                mode = state.activeMode,
                distanceUnit = state.unitPrefs.distanceUnit,
                onNavigate = {
                    val uri = Uri.parse(
                        String.format(
                            Locale.US,
                            "geo:%.4f,%.4f?q=%.4f,%.4f(%s)",
                            spot.latitude,
                            spot.longitude,
                            spot.latitude,
                            spot.longitude,
                            Uri.encode(spot.placeName),
                        ),
                    )
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                },
                onUseAsPlace = {
                    onUseSpotAsPlace(
                        Place(
                            name = spot.placeName,
                            latitude = spot.latitude,
                            longitude = spot.longitude,
                        ),
                    )
                },
                onSaveViewpoint = {
                    onSaveSpotViewpoint(spot.placeName, spot.latitude, spot.longitude)
                },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 14.dp)
                    .widthIn(max = 600.dp)
                    .fillMaxWidth(),
            )
        }
    }
}

private val ChasePanel = Color(0xFF0D1A2C)
private val ChaseMuted = Color(0xFFB2C1D1)

@Composable
private fun ChaseSegments(choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, subtle: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().clip(CircleShape).background(Color.White.copy(alpha = 0.07f)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        choices.forEach { (key, label) ->
            val active = selected == key
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp).clip(CircleShape)
                    .background(if (active) { if (subtle) Color(0xFF34465B) else Color.White } else Color.Transparent)
                    .clickable(role = Role.Button) { onSelect(key) }.padding(horizontal = 8.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (active && !subtle) Color(0xFF142236) else if (active) Color.White else ChaseMuted,
                    fontSize = 12.sp, fontWeight = if (active) FontWeight.W700 else FontWeight.W500,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2)
            }
        }
    }
}

@Composable
private fun ChaseZoomBtn(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(ChasePanel)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
            .semantics { contentDescription = description }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = if (enabled) Color.White else Color(0xFF5A6C81), fontFamily = DisplayFamily, fontSize = 24.sp)
    }
}

@Composable
private fun ChaseSpotBottomCard(
    spot: ChaseSpotState,
    origin: Place,
    mode: String,
    distanceUnit: String,
    onNavigate: () -> Unit,
    onUseAsPlace: () -> Unit,
    onSaveViewpoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val distKm = distanceKm(origin.latitude, origin.longitude, spot.latitude, spot.longitude)
    val bearing = bearingDegrees(origin.latitude, origin.longitude, spot.latitude, spot.longitude)
    val distText = formatDistanceKm(distKm, distanceUnit)
    val dirText = compassLabel(bearing)

    Column(
        modifier.clip(RoundedCornerShape(24.dp)).background(ChasePanel)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(24.dp)).padding(16.dp),
    ) {
        Text(stringResource(R.string.chase_regional_estimate), color = ChaseMuted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LucideIcon(R.drawable.ic_map_pin, null, tint = Color(0xFFFFD76A), size = 14.dp)
                    Text(
                        text = spot.placeName,
                        color = Color.White,
                        fontFamily = DisplayFamily,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.W700,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (spot.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(13.dp),
                            strokeWidth = 1.8.dp,
                            color = Color(0xFFFFD76A),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.chase_distance_away, distText, dirText) +
                        String.format(Locale.US, " · %.3f°, %.3f°", spot.latitude, spot.longitude),
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "${Math.round(spot.probability)}%",
                    color = Color(0xFFFFD76A),
                    fontFamily = DisplayFamily,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.W700,
                    lineHeight = 26.sp,
                )
                Text(
                    text = localizedScoreLabel(spot.probability),
                    color = TextSecondary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.W600,
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SpotMiniStat(
                label = stringResource(R.string.label_intensity),
                value = "${Math.round(spot.quality)}%",
                modifier = Modifier.weight(1f),
            )
            SpotMiniStat(
                label = stringResource(R.string.label_peak_window),
                value = spot.peakWindowLabel,
                modifier = Modifier.weight(1.3f),
            )
            SpotMiniStat(
                label = stringResource(R.string.sun_direction_title),
                value = "${Math.round(spot.sunBearing)}° ${compassLabel(spot.sunBearing)}",
                modifier = Modifier.weight(1.1f),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .weight(1.25f)
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .background(Color.White)
                    .clickable(role = Role.Button, onClick = onNavigate)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                LucideIcon(R.drawable.ic_compass, null, tint = Color(0xFF101D33), size = 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = stringResource(R.string.chase_navigate),
                    color = Color(0xFF101D33),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W700,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(SurfaceInset)
                    .border(1.dp, SurfaceHairline, CircleShape)
                    .clickable(role = Role.Button, onClick = onUseAsPlace)
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                LucideIcon(R.drawable.ic_sun_medium, null, tint = TextPrimary, size = 15.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    text = stringResource(R.string.chase_set_place),
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.W600,
                    maxLines = 1,
                )
            }
            Row(
                modifier = Modifier
                    .heightIn(min = 44.dp)
                    .clip(CircleShape)
                    .background(SurfaceControl)
                    .border(1.dp, SurfaceHairline, CircleShape)
                    .clickable(role = Role.Button, onClick = onSaveViewpoint)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                LucideIcon(R.drawable.ic_camera, null, tint = TextPrimary, size = 15.dp)
            }
        }
    }
}

@Composable
private fun SpotMiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceInset)
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Text(label, color = TextMuted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            color = Color.White,
            fontFamily = DisplayFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.W700,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
