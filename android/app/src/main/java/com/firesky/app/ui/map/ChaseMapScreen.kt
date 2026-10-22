package com.firesky.app.ui.map

import com.firesky.app.ui.icons.tapPulse
import com.firesky.app.ui.icons.rememberIconPulse
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.rotate
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

        Row(
            Modifier.align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .widthIn(max = 520.dp).fillMaxWidth()
                .shadow(4.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp)).background(ChasePanel)
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.chase_map_title), modifier = Modifier.weight(1f),
                color = ChaseInk, fontSize = 15.sp, fontWeight = FontWeight.W700,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            ChaseFilter(
                choices = listOf("sunrise" to stringResource(R.string.mode_sunrise), "sunset" to stringResource(R.string.mode_sunset)),
                selected = state.activeMode, onSelect = onSelectMode,
            )
            ChaseFilter(
                choices = listOf("probability" to stringResource(R.string.chase_probability), "quality" to stringResource(R.string.chase_intensity)),
                selected = mapType, onSelect = { mapType = it },
            )
        }

        Column(
            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val centerLabel = stringResource(R.string.chase_center_map)
            Box(Modifier.size(44.dp).shadow(3.dp, CircleShape).clip(CircleShape).background(ChasePanel)
                .semantics { contentDescription = centerLabel }
                .clickable(role = Role.Button) { controller?.center() }, contentAlignment = Alignment.Center) {
                LucideIcon(R.drawable.ic_locate_fixed, null, tint = ChaseInk, size = 20.dp)
            }
            Column(Modifier.shadow(3.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(ChasePanel)) {
                ChaseZoomBtn("+", stringResource(R.string.chase_zoom_in), samples.isNotEmpty() && zoomLevel < maximumZoom - 0.02) { controller?.zoomBy(1.0) }
                Box(Modifier.width(24.dp).height(1.dp).align(Alignment.CenterHorizontally).background(ChaseLine))
                ChaseZoomBtn("−", stringResource(R.string.chase_zoom_out), samples.isNotEmpty() && zoomLevel > minimumZoom + 0.02) { controller?.zoomBy(-1.0) }
            }
        }

        if (state.chaseSpot == null) {
            Row(
                Modifier.align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                    .padding(16.dp).widthIn(max = 520.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    Modifier.weight(1f).height(64.dp).clip(CircleShape).background(ChasePanel).padding(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                ) {
                    Text(stringResource(if (heatReady) R.string.chase_nearby_area else if (state.grid?.isEmpty() == true) R.string.chase_unavailable else R.string.chase_loading), maxLines = 1, overflow = TextOverflow.Ellipsis, color = ChaseInk, fontSize = 11.sp, fontWeight = FontWeight.W500)
                    Box(Modifier.width(112.dp).height(5.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(
                        Color(0xFF246097), Color(0xFF6CB881), Color(0xFFF4CF49), Color(0xFFC23646),
                    ))))
                }
                bestSample?.let { best ->
                    Row(
                        Modifier.height(64.dp).clip(CircleShape).background(ChasePanel)
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

private val ChasePanel = Color.White
private val ChaseInk = Color(0xFF20262D)
private val ChaseMuted = Color(0xFF6E747C)
private val ChaseLine = Color(0xFFE9EBEE)
private val ChaseInset = Color(0xFFF4F5F6)

@Composable
private fun ChaseFilter(choices: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button) { expanded = true }.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(choices.firstOrNull { it.first == selected }?.second.orEmpty(), color = ChaseInk,
                fontSize = 12.sp, fontWeight = FontWeight.W500, maxLines = 1)
            LucideIcon(R.drawable.ic_chevron_down, null, tint = ChaseMuted, size = 12.dp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, containerColor = ChasePanel) {
            choices.forEach { (key, label) ->
                DropdownMenuItem(text = { Text(label, color = ChaseInk, fontWeight = if (key == selected) FontWeight.W700 else FontWeight.W400) },
                    onClick = { onSelect(key); expanded = false })
            }
        }
    }
}

@Composable
private fun ChaseZoomBtn(label: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).semantics { contentDescription = description }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) ChaseInk else Color(0xFFBBC0C5), fontSize = 23.sp)
    }
}

@Composable
private fun ChaseSpotBottomCard(
    spot: ChaseSpotState,
    origin: Place,
    distanceUnit: String,
    onNavigate: () -> Unit,
    onUseAsPlace: () -> Unit,
    onSaveViewpoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val distKm = distanceKm(origin.latitude, origin.longitude, spot.latitude, spot.longitude)
    val bearing = bearingDegrees(origin.latitude, origin.longitude, spot.latitude, spot.longitude)
    val distText = formatDistanceKm(distKm, distanceUnit)
    val detailsLabel = stringResource(if (expanded) R.string.chase_less_details else R.string.chase_more_details)

    Column(modifier.shadow(6.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
        .background(ChasePanel).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .semantics { contentDescription = detailsLabel }
            .clickable(role = Role.Button) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(spot.placeName, modifier = Modifier.weight(1f, fill = false), color = ChaseInk,
                        fontSize = 15.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (spot.loading) CircularProgressIndicator(Modifier.size(12.dp), color = ChaseInk, strokeWidth = 1.5.dp)
                }
                Text(stringResource(R.string.chase_distance_away, distText, compassLabel(bearing)),
                    color = ChaseMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${Math.round(spot.probability)}%", color = ChaseInk,
                    fontFamily = DisplayFamily, fontSize = 23.sp, fontWeight = FontWeight.W700)
                Text(localizedScoreLabel(spot.probability), color = ChaseMuted, fontSize = 10.sp)
            }
            LucideIcon(R.drawable.ic_chevron_down, null, tint = ChaseMuted, size = 16.dp,
                modifier = Modifier.rotate(if (expanded) 180f else 0f))
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(top = 10.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.chase_regional_estimate) + String.format(Locale.US, " · %.3f°, %.3f°", spot.latitude, spot.longitude),
                    color = ChaseMuted, fontSize = 10.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    SpotMiniStat(stringResource(R.string.label_intensity), "${Math.round(spot.quality)}%", Modifier.weight(1f))
                    SpotMiniStat(stringResource(R.string.label_peak_window), spot.peakWindowLabel, Modifier.weight(1.3f))
                    SpotMiniStat(stringResource(R.string.sun_direction_title), "${Math.round(spot.sunBearing)}° ${compassLabel(spot.sunBearing)}", Modifier.weight(1.1f))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            SpotAction(R.drawable.ic_compass, stringResource(R.string.chase_navigate), onNavigate, Modifier.weight(1f), primary = true)
            SpotAction(R.drawable.ic_sun_medium, stringResource(R.string.chase_set_place), onUseAsPlace, Modifier.weight(1f))
            val saveLabel = stringResource(R.string.chase_save_spot)
            Box(Modifier.size(44.dp).clip(CircleShape).background(ChaseInset)
                .semantics { contentDescription = saveLabel }
                .clickable(role = Role.Button, onClick = onSaveViewpoint), contentAlignment = Alignment.Center) {
                LucideIcon(R.drawable.ic_camera, null, tint = ChaseInk, size = 18.dp)
            }
        }
    }
}

@Composable
private fun SpotAction(icon: Int, label: String, onClick: () -> Unit, modifier: Modifier, primary: Boolean = false) {
    val pulse = rememberIconPulse()
    Row(modifier.heightIn(min = 44.dp).clip(CircleShape).background(if (primary) ChaseInk else ChaseInset)
        .tapPulse(pulse).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        LucideIcon(icon, null, tint = if (primary) Color.White else ChaseInk, size = 15.dp, pulse = pulse)
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (primary) Color.White else ChaseInk, fontSize = 11.sp,
            fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SpotMiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(ChaseInset).padding(horizontal = 8.dp, vertical = 7.dp)) {
        Text(label, color = ChaseMuted, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(3.dp))
        Text(value, color = ChaseInk, fontSize = 12.sp, fontWeight = FontWeight.W600, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
