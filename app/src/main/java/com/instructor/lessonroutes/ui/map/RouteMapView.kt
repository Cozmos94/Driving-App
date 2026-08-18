package com.instructor.lessonroutes.ui.map

import android.annotation.SuppressLint
import android.graphics.Color
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
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.coroutines.resume
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

private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LAYER_ID = "route-layer"
private const val ROUTE_LINE_COLOR = "#2E7D32" // matches the app's theme green

private const val WAYPOINT_SOURCE_ID = "waypoint-source"
private const val WAYPOINT_LAYER_ID = "waypoint-layer"
private const val WAYPOINT_COLOR = "#F57C00" // orange, distinct from the route line

private const val LIVE_LOCATION_SOURCE_ID = "live-location-source"
private const val LIVE_LOCATION_LAYER_ID = "live-location-layer"
private const val LIVE_LOCATION_COLOR = "#1976D2" // blue "you are here" dot

private const val HAZARD_SOURCE_ID = "hazard-source"
private const val HAZARD_LAYER_ID = "hazard-layer"
private const val HAZARD_COLOR = "#D32F2F" // red, Phase 2 live hazards overlay

/**
 * The shared map surface used by every screen: renders free OpenFreeMap tiles inside an
 * `AndroidView`, optionally centers on the device's location or fits a saved route's
 * bounds, draws a route polyline + waypoint markers, shows a live position dot, and can
 * report taps back to the caller (tap-to-create mode).
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
    hazards: List<LatLng> = emptyList(),
    /** When true, moves the camera to fit [routePoints] instead of the device location. */
    fitBoundsToRoute: Boolean = false,
    /** Ignored when [fitBoundsToRoute] is true. */
    centerOnDeviceLocation: Boolean = true,
    onMapClick: ((LatLng) -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    val onMapClickState = rememberUpdatedState(onMapClick)

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
                        onMapClickState.value?.invoke(point)
                        onMapClickState.value != null
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
        (style.getSource(HAZARD_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(hazards))
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
    style.addSource(GeoJsonSource(HAZARD_SOURCE_ID))
    style.addLayer(
        CircleLayer(HAZARD_LAYER_ID, HAZARD_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(6f),
            PropertyFactory.circleColor(Color.parseColor(HAZARD_COLOR)),
            PropertyFactory.circleStrokeWidth(2f),
            PropertyFactory.circleStrokeColor(Color.WHITE),
        ),
    )
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
