package com.instructor.lessonroutes.ui.map

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.location.Location
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import com.instructor.lessonroutes.data.remote.Hazard
import com.instructor.lessonroutes.data.remote.HazardCategory
import com.instructor.lessonroutes.util.LOCATION_PERMISSIONS
import com.instructor.lessonroutes.util.hasLocationPermission
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlinx.coroutines.suspendCancellableCoroutine

/** Free, keyless vector tile style. Liberty is OpenFreeMap's general-purpose style. */
const val OPENFREEMAP_LIBERTY_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Fallback camera when location permission is denied or a fix isn't available yet
 * (e.g. a fresh emulator with no location set). The app is NSW-scoped, so this is
 * Sydney rather than MapLibre's global (zoom 0) default.
 */
private val FALLBACK_CENTER = LatLng(-33.8688, 151.2093)
private const val DEFAULT_ZOOM = 11.0
private const val BOUNDS_PADDING_PX = 96
private const val HAZARD_TAP_RADIUS_PX = 40.0

private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LAYER_ID = "route-layer"
private const val ROUTE_LINE_COLOR = "#2E7D32" // matches the app's theme green

private const val WAYPOINT_SOURCE_ID = "waypoint-source"
private const val WAYPOINT_LAYER_ID = "waypoint-layer"
private const val WAYPOINT_COLOR = "#F57C00" // orange, distinct from the route line

private const val LIVE_LOCATION_SOURCE_ID = "live-location-source"
private const val LIVE_LOCATION_LAYER_ID = "live-location-layer"
private const val LIVE_LOCATION_COLOR = "#1976D2" // blue "you are here" dot

private const val INCIDENT_SOURCE_ID = "incident-source"
private const val INCIDENT_LAYER_ID = "incident-layer"
private const val INCIDENT_COLOR = "#D32F2F" // red, Phase 2 live hazards overlay

private const val ROADWORK_SOURCE_ID = "roadwork-source"
private const val ROADWORK_LAYER_ID = "roadwork-layer"
private const val ROADWORK_ICON_ID = "roadwork-icon"

/**
 * The shared map surface used by every screen: renders free OpenFreeMap tiles inside an
 * `AndroidView`, optionally centers on the device's location or fits a saved route's
 * bounds, draws a route polyline + waypoint markers, shows a live position dot, renders
 * live hazards (incidents as red dots, roadworks with a construction icon), and can
 * report taps back to the caller — either a hazard tap ([onHazardClick]) or a plain map
 * tap ([onMapClick], used for tap-to-create mode).
 *
 * Hazard taps are detected in plain Kotlin (screen-distance against the [hazards] list
 * we already hold), not via MapLibre's feature-query API — we already have every
 * hazard's full data in memory, so there's no need to round-trip through the map's
 * rendered-feature/GeoJSON-properties API just to get it back out again.
 *
 * MapLibre's `MapView` is a plain Android View with its own lifecycle (onStart/onResume/
 * onPause/onStop/onDestroy/onLowMemory) that must be driven manually — it does not know
 * about Compose or the host Activity's lifecycle on its own. We bridge the two with
 * DisposableEffect + LifecycleEventObserver below.
 */
@Composable
fun RouteMapView(
    modifier: Modifier = Modifier,
    styleUrl: String = OPENFREEMAP_LIBERTY_STYLE_URL,
    routePoints: List<LatLng> = emptyList(),
    waypoints: List<LatLng> = emptyList(),
    liveLocation: LatLng? = null,
    /** Phase 2 live hazards overlay (step 9) — empty when the overlay's off. */
    hazards: List<Hazard> = emptyList(),
    /** When true, moves the camera to fit [routePoints] instead of the device location. */
    fitBoundsToRoute: Boolean = false,
    /** Ignored when [fitBoundsToRoute] is true. */
    centerOnDeviceLocation: Boolean = true,
    onMapClick: ((LatLng) -> Unit)? = null,
    onHazardClick: ((Hazard) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    val onMapClickState = rememberUpdatedState(onMapClick)
    val onHazardClickState = rememberUpdatedState(onHazardClick)
    val hazardsState = rememberUpdatedState(hazards)

    var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> hasLocationPermission = results.values.any { it } }

    LaunchedEffect(Unit) {
        if (centerOnDeviceLocation && !fitBoundsToRoute && !hasLocationPermission) {
            permissionLauncher.launch(LOCATION_PERMISSIONS)
        }
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { androidContext ->
            MapView(androidContext).also { view ->
                mapViewRef.value = view
                view.onCreate(Bundle())
                view.getMapAsync { map ->
                    map.cameraPosition = CameraPosition.Builder()
                        .target(FALLBACK_CENTER)
                        .zoom(DEFAULT_ZOOM)
                        .build()
                    map.addOnMapClickListener { point ->
                        val tapScreen = map.projection.toScreenLocation(point)
                        val hitHazard = hazardsState.value.minByOrNull { hazard ->
                            screenDistance(map, hazard, tapScreen)
                        }?.takeIf { hazard -> screenDistance(map, hazard, tapScreen) <= HAZARD_TAP_RADIUS_PX }

                        if (hitHazard != null) {
                            onHazardClickState.value?.invoke(hitHazard)
                            true
                        } else {
                            onMapClickState.value?.invoke(point)
                            onMapClickState.value != null
                        }
                    }
                    map.setStyle(styleUrl) { style ->
                        addSourcesAndLayers(style)
                        // Only exposed once the source/layer exist, so effects below
                        // never race ahead of style setup.
                        mapLibreMap = map
                    }
                }
            }
        },
    )

    // Once permission is granted, move the camera to the device's actual location —
    // this only fires the one time on grant, it doesn't keep tracking a moving
    // position (that's the live-location dot, for record/follow screens).
    LaunchedEffect(hasLocationPermission, fitBoundsToRoute) {
        if (!centerOnDeviceLocation || fitBoundsToRoute || !hasLocationPermission) return@LaunchedEffect
        val map = mapViewRef.value?.awaitMap() ?: return@LaunchedEffect
        val location = LocationServices.getFusedLocationProviderClient(context)
            .awaitCurrentLocation() ?: return@LaunchedEffect
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(DEFAULT_ZOOM)
            .build()
    }

    // Fits the camera to the route's bounding box once both the style and the points
    // are ready (used by the route detail / follow screens instead of device-location
    // centering).
    LaunchedEffect(mapLibreMap, fitBoundsToRoute, routePoints) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!fitBoundsToRoute || routePoints.size < 2) return@LaunchedEffect
        val bounds = LatLngBounds.Builder().apply { routePoints.forEach { include(it) } }.build()
        map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, BOUNDS_PADDING_PX))
    }

    LaunchedEffect(routePoints, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        (style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(lineGeoJson(routePoints))
    }

    LaunchedEffect(waypoints, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        (style.getSource(WAYPOINT_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(waypoints))
    }

    LaunchedEffect(liveLocation, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val points = liveLocation?.let { listOf(it) } ?: emptyList()
        (style.getSource(LIVE_LOCATION_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(points))
    }

    LaunchedEffect(hazards, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val incidentPoints = hazards.filter { it.category == HazardCategory.INCIDENT }
            .map { LatLng(it.latitude, it.longitude) }
        val roadworkPoints = hazards.filter { it.category == HazardCategory.ROADWORK }
            .map { LatLng(it.latitude, it.longitude) }
        (style.getSource(INCIDENT_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(incidentPoints))
        (style.getSource(ROADWORK_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(roadworkPoints))
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = mapViewRef.value ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START -> view.onStart()
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                Lifecycle.Event.ON_STOP -> view.onStop()
                Lifecycle.Event.ON_DESTROY -> view.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            // Don't call view.onDestroy() here too: the ON_DESTROY branch above already
            // does, and it fires first as part of the same teardown — calling it twice
            // is not safe to assume idempotent.
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
}

private fun screenDistance(map: MapLibreMap, hazard: Hazard, tapScreen: PointF): Double {
    val hazardScreen = map.projection.toScreenLocation(LatLng(hazard.latitude, hazard.longitude))
    return hypot((hazardScreen.x - tapScreen.x).toDouble(), (hazardScreen.y - tapScreen.y).toDouble())
}

private fun addSourcesAndLayers(style: Style) {
    style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
    style.addLayer(
        LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Color.parseColor(ROUTE_LINE_COLOR)),
            PropertyFactory.lineWidth(4f),
        ),
    )
    style.addSource(GeoJsonSource(WAYPOINT_SOURCE_ID))
    style.addLayer(
        CircleLayer(WAYPOINT_LAYER_ID, WAYPOINT_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(7f),
            PropertyFactory.circleColor(Color.parseColor(WAYPOINT_COLOR)),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
        ),
    )
    style.addSource(GeoJsonSource(LIVE_LOCATION_SOURCE_ID))
    style.addLayer(
        CircleLayer(LIVE_LOCATION_LAYER_ID, LIVE_LOCATION_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleColor(Color.parseColor(LIVE_LOCATION_COLOR)),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
        ),
    )
    style.addSource(GeoJsonSource(INCIDENT_SOURCE_ID))
    style.addLayer(
        CircleLayer(INCIDENT_LAYER_ID, INCIDENT_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(Color.parseColor(INCIDENT_COLOR)),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
        ),
    )
    style.addImage(ROADWORK_ICON_ID, emojiIcon("🚧")) // 🚧
    style.addSource(GeoJsonSource(ROADWORK_SOURCE_ID))
    style.addLayer(
        SymbolLayer(ROADWORK_LAYER_ID, ROADWORK_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(ROADWORK_ICON_ID),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(0.6f),
        ),
    )
}

/** Renders an emoji glyph to a bitmap so it can be used as a MapLibre icon image. */
private fun emojiIcon(emoji: String, sizePx: Int = 96): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizePx * 0.8f
        textAlign = Paint.Align.CENTER
    }
    val textY = sizePx / 2f - (paint.descent() + paint.ascent()) / 2f
    canvas.drawText(emoji, sizePx / 2f, textY, paint)
    return bitmap
}

private suspend fun MapView.awaitMap(): MapLibreMap = suspendCancellableCoroutine { continuation ->
    getMapAsync { map -> continuation.resume(map) }
}

/** Requests one fresh fix rather than relying on a possibly-null cached last location. */
@SuppressLint("MissingPermission") // caller only reaches here after a permission check
private suspend fun FusedLocationProviderClient.awaitCurrentLocation(): Location? =
    try {
        val cancellationTokenSource = CancellationTokenSource()
        suspendCancellableCoroutine<Location?> { continuation ->
            getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location -> continuation.resume(location) }
                .addOnFailureListener { continuation.resume(null) }
            continuation.invokeOnCancellation { cancellationTokenSource.cancel() }
        }
    } catch (e: SecurityException) {
        null
    }

/**
 * Builds GeoJSON by hand (no geojson library dependency needed). Fewer than 2 points
 * can't form a line, so that case just clears the layer.
 */
private fun lineGeoJson(points: List<LatLng>): String {
    if (points.size < 2) return """{"type":"FeatureCollection","features":[]}"""
    val coordinates = points.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordinates]},"properties":{}}"""
}

private fun pointsGeoJson(points: List<LatLng>): String {
    if (points.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = points.joinToString(",") {
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.longitude},${it.latitude}]},"properties":{}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}
