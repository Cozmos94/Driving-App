package com.instructor.lessonroutes.ui.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
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

private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LAYER_ID = "route-layer"
private const val ROUTE_LINE_COLOR = "#2E7D32" // matches the app's theme green

/**
 * The main map surface: renders free OpenFreeMap tiles inside an `AndroidView` (step 1),
 * centers on the device's location once permission is granted, and draws [routePoints]
 * as a polyline (step 3) — an empty list just shows no line.
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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }

    var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> hasLocationPermission = results.values.any { it } }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
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
                    map.setStyle(styleUrl) { style ->
                        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
                        style.addLayer(
                            LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
                                PropertyFactory.lineColor(Color.parseColor(ROUTE_LINE_COLOR)),
                                PropertyFactory.lineWidth(4f),
                            ),
                        )
                        // Only exposed once the source/layer exist, so the route-drawing
                        // effect below never races ahead of style setup.
                        mapLibreMap = map
                    }
                }
            }
        },
    )

    // Once permission is granted, move the camera to the device's actual location —
    // this only fires the one time on grant, it doesn't keep tracking a moving
    // position (that's the Follow view, step 7).
    LaunchedEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@LaunchedEffect
        val map = mapViewRef.value?.awaitMap() ?: return@LaunchedEffect
        val location = LocationServices.getFusedLocationProviderClient(context)
            .awaitCurrentLocation() ?: return@LaunchedEffect
        map.cameraPosition = CameraPosition.Builder()
            .target(LatLng(location.latitude, location.longitude))
            .zoom(DEFAULT_ZOOM)
            .build()
    }

    // Redraws the route line whenever the points change (or as soon as the style
    // becomes ready, if points were already available).
    LaunchedEffect(routePoints, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val source = style.getSource(ROUTE_SOURCE_ID) as? GeoJsonSource ?: return@LaunchedEffect
        source.setGeoJson(routeGeoJson(routePoints))
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

private fun Context.hasLocationPermission(): Boolean {
    val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
    val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
    return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
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
 * Builds a GeoJSON LineString feature by hand (no geojson library dependency needed).
 * Fewer than 2 points can't form a line, so that case just clears the layer.
 */
private fun routeGeoJson(points: List<LatLng>): String {
    if (points.size < 2) return """{"type":"FeatureCollection","features":[]}"""
    val coordinates = points.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordinates]},"properties":{}}"""
}
