package com.instructor.lessonroutes.ui.map

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.location.Location
import android.os.Bundle
import android.view.Gravity
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
import com.instructor.lessonroutes.data.SchoolZone
import com.instructor.lessonroutes.data.SpeedCamera
import com.instructor.lessonroutes.data.SpeedCameraType
import com.instructor.lessonroutes.data.remote.Hazard
import com.instructor.lessonroutes.data.remote.HazardCategory
import com.instructor.lessonroutes.data.remote.HighVolumeRoad
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
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
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
private const val TAP_HIT_RADIUS_PX = 40.0

private const val ROUTE_SOURCE_ID = "route-source"
private const val ROUTE_LAYER_ID = "route-layer"
private const val ROUTE_LINE_COLOR = "#1A1A1A" // matches the app's black/white theme

private const val WAYPOINT_SOURCE_ID = "waypoint-source"
private const val WAYPOINT_LAYER_ID = "waypoint-layer"
private const val WAYPOINT_COLOR = "#FFFFFF" // white, distinct from the black route line (needs a dark stroke -- see below -- since a white dot needs an outline to read against light map tiles)

private const val LIVE_LOCATION_SOURCE_ID = "live-location-source"
private const val LIVE_LOCATION_LAYER_ID = "live-location-layer"
private const val LIVE_LOCATION_COLOR = "#1976D2" // blue "you are here" dot

private const val INCIDENT_SOURCE_ID = "incident-source"
private const val INCIDENT_LAYER_ID = "incident-layer"
private const val INCIDENT_COLOR = "#D32F2F" // red, Phase 2 live hazards overlay

private const val ROADWORK_SOURCE_ID = "roadwork-source"
private const val ROADWORK_LAYER_ID = "roadwork-layer"
private const val ROADWORK_ICON_ID = "roadwork-icon"

// Static reference overlays (spec step 10-adjacent) -- school zones are split by
// speed limit rather than data-driven styling, since only 30/40 km/h occur in
// practice; anything else falls back into the 40 bucket.
private const val SCHOOL_ZONE_40_SOURCE_ID = "school-zone-40-source"
private const val SCHOOL_ZONE_40_LAYER_ID = "school-zone-40-layer"
private const val SCHOOL_ZONE_40_ICON_ID = "school-zone-40-icon"

private const val SCHOOL_ZONE_30_SOURCE_ID = "school-zone-30-source"
private const val SCHOOL_ZONE_30_LAYER_ID = "school-zone-30-layer"
private const val SCHOOL_ZONE_30_ICON_ID = "school-zone-30-icon"

private const val CAMERA_FIXED_SOURCE_ID = "camera-fixed-source"
private const val CAMERA_FIXED_LAYER_ID = "camera-fixed-layer"
private const val CAMERA_FIXED_ICON_ID = "camera-fixed-icon"

private const val CAMERA_REDLIGHT_SOURCE_ID = "camera-redlight-source"
private const val CAMERA_REDLIGHT_LAYER_ID = "camera-redlight-layer"
private const val CAMERA_REDLIGHT_ICON_ID = "camera-redlight-icon"

// Icon marker fallback, for any station Overpass couldn't match to a real road.
private const val HIGH_VOLUME_SOURCE_ID = "high-volume-source"
private const val HIGH_VOLUME_LAYER_ID = "high-volume-layer"
private const val HIGH_VOLUME_ICON_ID = "high-volume-icon"

// The real thing: the matched road segment's actual shape, painted red.
private const val HIGH_VOLUME_LINE_SOURCE_ID = "high-volume-line-source"
private const val HIGH_VOLUME_LINE_LAYER_ID = "high-volume-line-layer"
private const val HIGH_VOLUME_LINE_COLOR = "#B71C1C"

// Step 11: OSM residential/living_street roads as a "quiet road" heuristic proxy
// (spec: no free measured-traffic source exists at street level, so this stands in
// for it -- a road-classification guess, not measured data).
private const val QUIET_ROADS_SOURCE_ID = "quiet-roads-source"
private const val QUIET_ROADS_LAYER_ID = "quiet-roads-layer"
private const val QUIET_ROADS_COLOR = "#00897B"

// The trip generator's optional "how far this trip might range" radius cap --
// see RadiusPicker in GenerateRouteScreen.kt. A translucent fill plus a more
// visible outline (FillLayer's own outline is a hairline that can't be
// widened, hence the separate LineLayer sharing the same source/geometry).
private const val RADIUS_CIRCLE_SOURCE_ID = "radius-circle-source"
private const val RADIUS_CIRCLE_FILL_LAYER_ID = "radius-circle-fill-layer"
private const val RADIUS_CIRCLE_OUTLINE_LAYER_ID = "radius-circle-outline-layer"
private const val RADIUS_CIRCLE_COLOR = "#558B2F" // the app's grass-green brand primary
private const val RADIUS_CIRCLE_SEGMENTS = 64
private const val EMPTY_FEATURE_COLLECTION = """{"type":"FeatureCollection","features":[]}"""

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
    /** When true, the camera pans to follow [liveLocation] as it updates — a driving
     * view, like Google Maps, rather than a static dot. Doesn't change zoom, so a
     * manual pinch-zoom isn't fought on the next update. */
    followLiveLocation: Boolean = false,
    /** Phase 2 live hazards overlay (step 9) — empty when the overlay's off. */
    hazards: List<Hazard> = emptyList(),
    /** Static reference overlays — no tap handling on these yet, display only. */
    schoolZones: List<SchoolZone> = emptyList(),
    cameras: List<SpeedCamera> = emptyList(),
    /** Phase 2: roads over the high-volume threshold — tappable, like hazards. */
    highVolumeRoads: List<HighVolumeRoad> = emptyList(),
    /** Step 11: OSM residential/living_street roads as a "quiet road" heuristic --
     * a road-classification guess, not measured traffic. Tappable, like hazards. */
    quietRoads: List<List<LatLng>> = emptyList(),
    /** Trip generator's optional radius cap, shown as a circle overlay -- both
     * must be non-null (and [radiusCircleKm] > 0) for it to draw. */
    radiusCircleCenter: LatLng? = null,
    radiusCircleKm: Double? = null,
    /** When true, moves the camera to fit [routePoints] instead of the device location. */
    fitBoundsToRoute: Boolean = false,
    /** Ignored when [fitBoundsToRoute] is true. */
    centerOnDeviceLocation: Boolean = true,
    /** When non-null, the camera moves here once (as soon as both the map/style
     * are ready and this has a value -- whichever arrives last), instead of
     * RouteMapView fetching the device location itself. For callers that already
     * track the device's location on their own (so this doesn't run its own
     * redundant permission-request/location-fetch, which could race with the
     * caller's own) -- see [centerOnDeviceLocation]'s doc on other screens for why
     * that race matters. Only fires once; later changes to this value don't
     * re-center (use [followLiveLocation] for continuous tracking instead).
     * Ignored when [fitBoundsToRoute] is true. Set [centerOnDeviceLocation] to
     * false when using this, or the two centering attempts can race. */
    focusPoint: LatLng? = null,
    onMapClick: ((LatLng) -> Unit)? = null,
    onHazardClick: ((Hazard) -> Unit)? = null,
    onHighVolumeClick: ((HighVolumeRoad) -> Unit)? = null,
    onQuietRoadClick: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapViewRef = remember { mutableStateOf<MapView?>(null) }
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    val onMapClickState = rememberUpdatedState(onMapClick)
    val onHazardClickState = rememberUpdatedState(onHazardClick)
    val onHighVolumeClickState = rememberUpdatedState(onHighVolumeClick)
    val onQuietRoadClickState = rememberUpdatedState(onQuietRoadClick)
    val hazardsState = rememberUpdatedState(hazards)
    val highVolumeRoadsState = rememberUpdatedState(highVolumeRoads)
    val quietRoadsState = rememberUpdatedState(quietRoads)

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
                    // Just the logo watermark -- no licensing requirement to keep it
                    // (unlike the separate attribution control, left on, which is how
                    // OpenStreetMap's ODbL-required "© OpenStreetMap contributors"
                    // credit actually gets shown).
                    map.uiSettings.isLogoEnabled = false
                    map.uiSettings.attributionGravity = Gravity.TOP or Gravity.START
                    val attributionMarginPx = (8 * androidContext.resources.displayMetrics.density).toInt()
                    map.uiSettings.setAttributionMargins(
                        attributionMarginPx,
                        attributionMarginPx,
                        attributionMarginPx,
                        attributionMarginPx,
                    )
                    map.cameraPosition = CameraPosition.Builder()
                        .target(FALLBACK_CENTER)
                        .zoom(DEFAULT_ZOOM)
                        .build()
                    map.addOnMapClickListener { point ->
                        val tapScreen = map.projection.toScreenLocation(point)

                        val hazardHit = hazardsState.value
                            .map { it to screenDistance(map, it.latitude, it.longitude, tapScreen) }
                            .minByOrNull { (_, distance) -> distance }
                            ?.takeIf { (_, distance) -> distance <= TAP_HIT_RADIUS_PX }

                        val volumeHit = highVolumeRoadsState.value
                            .map { road ->
                                val distance = road.geometry?.let { screenDistanceToPolyline(map, it, tapScreen) }
                                    ?: screenDistance(map, road.latitude, road.longitude, tapScreen)
                                road to distance
                            }
                            .minByOrNull { (_, distance) -> distance }
                            ?.takeIf { (_, distance) -> distance <= TAP_HIT_RADIUS_PX }

                        val quietRoadDistance = quietRoadsState.value
                            .map { screenDistanceToPolyline(map, it, tapScreen) }
                            .minOrNull()
                            ?.takeIf { it <= TAP_HIT_RADIUS_PX }

                        val bestDistance = minOf(
                            hazardHit?.second ?: Double.MAX_VALUE,
                            volumeHit?.second ?: Double.MAX_VALUE,
                            quietRoadDistance ?: Double.MAX_VALUE,
                        )

                        when {
                            hazardHit != null && hazardHit.second == bestDistance -> {
                                onHazardClickState.value?.invoke(hazardHit.first)
                                true
                            }
                            volumeHit != null && volumeHit.second == bestDistance -> {
                                onHighVolumeClickState.value?.invoke(volumeHit.first)
                                true
                            }
                            quietRoadDistance != null && quietRoadDistance == bestDistance -> {
                                onQuietRoadClickState.value?.invoke()
                                true
                            }
                            else -> {
                                onMapClickState.value?.invoke(point)
                                onMapClickState.value != null
                            }
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

    // Moves the camera to [focusPoint] once (see its doc) -- for callers that
    // already track the device's location themselves. Re-runs whenever
    // focusPoint or mapLibreMap change so it fires as soon as whichever arrives
    // last is ready, but hasAppliedFocusPoint guards it from firing more than once.
    var hasAppliedFocusPoint by remember { mutableStateOf(false) }
    LaunchedEffect(mapLibreMap, focusPoint, fitBoundsToRoute) {
        if (fitBoundsToRoute || hasAppliedFocusPoint) return@LaunchedEffect
        val point = focusPoint ?: return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        map.cameraPosition = CameraPosition.Builder().target(point).zoom(DEFAULT_ZOOM).build()
        hasAppliedFocusPoint = true
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

    // Driving view: pans to the live location on every update instead of just
    // drawing a static dot. Zoom is left alone so a manual pinch-zoom sticks.
    LaunchedEffect(liveLocation, mapLibreMap, followLiveLocation) {
        if (!followLiveLocation) return@LaunchedEffect
        val map = mapLibreMap ?: return@LaunchedEffect
        val location = liveLocation ?: return@LaunchedEffect
        map.easeCamera(CameraUpdateFactory.newLatLng(location), 1000)
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

    LaunchedEffect(schoolZones, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val zone30Points = schoolZones.filter { it.speedLimitKmh == 30 }.map { LatLng(it.latitude, it.longitude) }
        val zone40Points = schoolZones.filter { it.speedLimitKmh != 30 }.map { LatLng(it.latitude, it.longitude) }
        (style.getSource(SCHOOL_ZONE_30_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(zone30Points))
        (style.getSource(SCHOOL_ZONE_40_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(zone40Points))
    }

    LaunchedEffect(cameras, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val fixedPoints = cameras.filter { it.type == SpeedCameraType.FIXED }.map { LatLng(it.latitude, it.longitude) }
        val redLightPoints = cameras.filter { it.type == SpeedCameraType.RED_LIGHT }
            .map { LatLng(it.latitude, it.longitude) }
        (style.getSource(CAMERA_FIXED_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(fixedPoints))
        (style.getSource(CAMERA_REDLIGHT_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(redLightPoints))
    }

    LaunchedEffect(highVolumeRoads, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val (withGeometry, withoutGeometry) = highVolumeRoads.partition { it.geometry != null }
        val lines = withGeometry.mapNotNull { it.geometry }
        val markerPoints = withoutGeometry.map { LatLng(it.latitude, it.longitude) }
        (style.getSource(HIGH_VOLUME_LINE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(multiLineGeoJson(lines))
        (style.getSource(HIGH_VOLUME_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(pointsGeoJson(markerPoints))
    }

    LaunchedEffect(radiusCircleCenter, radiusCircleKm, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        val geoJson = if (radiusCircleCenter != null && radiusCircleKm != null && radiusCircleKm > 0) {
            polygonGeoJson(circlePolygonRing(radiusCircleCenter, radiusCircleKm))
        } else {
            EMPTY_FEATURE_COLLECTION
        }
        (style.getSource(RADIUS_CIRCLE_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(geoJson)
    }

    LaunchedEffect(quietRoads, mapLibreMap) {
        val style = mapLibreMap?.style ?: return@LaunchedEffect
        (style.getSource(QUIET_ROADS_SOURCE_ID) as? GeoJsonSource)?.setGeoJson(multiLineGeoJson(quietRoads))
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

private fun screenDistance(map: MapLibreMap, latitude: Double, longitude: Double, tapScreen: PointF): Double {
    val screen = map.projection.toScreenLocation(LatLng(latitude, longitude))
    return hypot((screen.x - tapScreen.x).toDouble(), (screen.y - tapScreen.y).toDouble())
}

/** Minimum screen-space distance from [tapScreen] to any segment of [polyline] --
 * for tapping a painted road segment rather than a single point marker. */
private fun screenDistanceToPolyline(map: MapLibreMap, polyline: List<LatLng>, tapScreen: PointF): Double {
    if (polyline.size < 2) return Double.MAX_VALUE
    val screenPoints = polyline.map { map.projection.toScreenLocation(it) }
    var minDistance = Double.MAX_VALUE
    for (i in 0 until screenPoints.size - 1) {
        val a = screenPoints[i]
        val b = screenPoints[i + 1]
        val dx = (b.x - a.x).toDouble()
        val dy = (b.y - a.y).toDouble()
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) {
            0.0
        } else {
            (((tapScreen.x - a.x) * dx + (tapScreen.y - a.y) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        }
        val distance = hypot(tapScreen.x - (a.x + t * dx), tapScreen.y - (a.y + t * dy))
        if (distance < minDistance) minDistance = distance
    }
    return minDistance
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
            // Black outline, not white -- the waypoint fill is white now (matching
            // the app's black/white theme), and a white-on-white stroke would be
            // invisible against light map tiles.
            PropertyFactory.circleStrokeColor(Color.parseColor(ROUTE_LINE_COLOR)),
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
    style.addImage(ROADWORK_ICON_ID, emojiIcon("🚧"))
    style.addSource(GeoJsonSource(ROADWORK_SOURCE_ID))
    style.addLayer(
        SymbolLayer(ROADWORK_LAYER_ID, ROADWORK_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(ROADWORK_ICON_ID),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(0.6f),
        ),
    )

    style.addImage(SCHOOL_ZONE_40_ICON_ID, speedLimitSignIcon(40))
    style.addSource(GeoJsonSource(SCHOOL_ZONE_40_SOURCE_ID))
    style.addLayer(
        SymbolLayer(SCHOOL_ZONE_40_LAYER_ID, SCHOOL_ZONE_40_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(SCHOOL_ZONE_40_ICON_ID),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(0.5f),
        ),
    )
    style.addImage(SCHOOL_ZONE_30_ICON_ID, speedLimitSignIcon(30))
    style.addSource(GeoJsonSource(SCHOOL_ZONE_30_SOURCE_ID))
    style.addLayer(
        SymbolLayer(SCHOOL_ZONE_30_LAYER_ID, SCHOOL_ZONE_30_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(SCHOOL_ZONE_30_ICON_ID),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(0.5f),
        ),
    )

    style.addImage(CAMERA_FIXED_ICON_ID, emojiIcon("📷"))
    style.addSource(GeoJsonSource(CAMERA_FIXED_SOURCE_ID))
    style.addLayer(
        SymbolLayer(CAMERA_FIXED_LAYER_ID, CAMERA_FIXED_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(CAMERA_FIXED_ICON_ID),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(0.5f),
        ),
    )
    style.addImage(CAMERA_REDLIGHT_ICON_ID, emojiIcon("🚦"))
    style.addSource(GeoJsonSource(CAMERA_REDLIGHT_SOURCE_ID))
    style.addLayer(
        SymbolLayer(CAMERA_REDLIGHT_LAYER_ID, CAMERA_REDLIGHT_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(CAMERA_REDLIGHT_ICON_ID),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(0.5f),
        ),
    )

    style.addSource(GeoJsonSource(HIGH_VOLUME_LINE_SOURCE_ID))
    style.addLayer(
        LineLayer(HIGH_VOLUME_LINE_LAYER_ID, HIGH_VOLUME_LINE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Color.parseColor(HIGH_VOLUME_LINE_COLOR)),
            PropertyFactory.lineWidth(5f),
        ),
    )
    style.addImage(HIGH_VOLUME_ICON_ID, redStripIcon())
    style.addSource(GeoJsonSource(HIGH_VOLUME_SOURCE_ID))
    style.addLayer(
        SymbolLayer(HIGH_VOLUME_LAYER_ID, HIGH_VOLUME_SOURCE_ID).withProperties(
            PropertyFactory.iconImage(HIGH_VOLUME_ICON_ID),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconSize(0.6f),
        ),
    )

    // Added below the route line so a real created/followed route always draws on
    // top of the quiet-road heuristic layer, not under it.
    style.addSource(GeoJsonSource(QUIET_ROADS_SOURCE_ID))
    style.addLayerBelow(
        LineLayer(QUIET_ROADS_LAYER_ID, QUIET_ROADS_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Color.parseColor(QUIET_ROADS_COLOR)),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(0.7f),
        ),
        ROUTE_LAYER_ID,
    )

    // Below the route line too, for the same reason -- a generated route
    // preview should never be obscured by its own radius-cap boundary.
    style.addSource(GeoJsonSource(RADIUS_CIRCLE_SOURCE_ID))
    style.addLayerBelow(
        FillLayer(RADIUS_CIRCLE_FILL_LAYER_ID, RADIUS_CIRCLE_SOURCE_ID).withProperties(
            PropertyFactory.fillColor(Color.parseColor(RADIUS_CIRCLE_COLOR)),
            PropertyFactory.fillOpacity(0.12f),
        ),
        ROUTE_LAYER_ID,
    )
    style.addLayerBelow(
        LineLayer(RADIUS_CIRCLE_OUTLINE_LAYER_ID, RADIUS_CIRCLE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(Color.parseColor(RADIUS_CIRCLE_COLOR)),
            PropertyFactory.lineWidth(2f),
            PropertyFactory.lineOpacity(0.8f),
        ),
        ROUTE_LAYER_ID,
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

/** Draws an Australian-style speed limit sign: red circle border, white fill, black number. */
private fun speedLimitSignIcon(speedLimitKmh: Int, sizePx: Int = 96): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val center = sizePx / 2f
    val strokeWidth = sizePx * 0.12f
    val radius = center - strokeWidth / 2f - 2f

    val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
    canvas.drawCircle(center, center, radius, fillPaint)

    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED
        style = Paint.Style.STROKE
        this.strokeWidth = strokeWidth
    }
    canvas.drawCircle(center, center, radius, borderPaint)

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = sizePx * 0.4f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    val textY = center - (textPaint.descent() + textPaint.ascent()) / 2f
    canvas.drawText(speedLimitKmh.toString(), center, textY, textPaint)
    return bitmap
}

/**
 * Fallback marker for a high-traffic-volume station Overpass couldn't match to a
 * real road (see matchRoadGeometry) -- most stations get the actual road shape
 * painted via [HIGH_VOLUME_LINE_LAYER_ID] instead; this is just for the leftovers.
 */
private fun redStripIcon(sizePx: Int = 96): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#B71C1C")
        style = Paint.Style.FILL
    }
    val stripHeight = sizePx * 0.28f
    val top = (sizePx - stripHeight) / 2f
    val rect = RectF(sizePx * 0.08f, top, sizePx * 0.92f, top + stripHeight)
    canvas.drawRoundRect(rect, stripHeight / 2f, stripHeight / 2f, stripPaint)

    // A dashed centerline, like a road marking, to read as "road" rather than a bar.
    val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = sizePx * 0.03f
        pathEffect = DashPathEffect(floatArrayOf(sizePx * 0.08f, sizePx * 0.05f), 0f)
    }
    canvas.drawLine(sizePx * 0.12f, sizePx / 2f, sizePx * 0.88f, sizePx / 2f, linePaint)

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

/** Several independent LineString features in one FeatureCollection -- unlike
 * [lineGeoJson], each entry in [lines] is its own separate segment, not one
 * connected path. */
private fun multiLineGeoJson(lines: List<List<LatLng>>): String {
    val validLines = lines.filter { it.size >= 2 }
    if (validLines.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = validLines.joinToString(",") { line ->
        val coordinates = line.joinToString(",") { "[${it.longitude},${it.latitude}]" }
        """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coordinates]},"properties":{}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

private fun pointsGeoJson(points: List<LatLng>): String {
    if (points.isEmpty()) return """{"type":"FeatureCollection","features":[]}"""
    val features = points.joinToString(",") {
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${it.longitude},${it.latitude}]},"properties":{}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}

/** A single Polygon feature from a closed ring (first point == last point --
 * see [circlePolygonRing]). */
private fun polygonGeoJson(ring: List<LatLng>): String {
    if (ring.size < 4) return EMPTY_FEATURE_COLLECTION
    val coordinates = ring.joinToString(",") { "[${it.longitude},${it.latitude}]" }
    return """{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coordinates]]},"properties":{}}"""
}

/** Approximates a real geographic circle of [radiusKm] around [center] as a
 * closed [RADIUS_CIRCLE_SEGMENTS]-sided polygon ring -- an equirectangular
 * approximation, same approach used elsewhere in this app (RouteGenerator.kt's
 * own `offset()`) and fine at the scale a trip-planning radius operates at. */
private fun circlePolygonRing(center: LatLng, radiusKm: Double, segments: Int = RADIUS_CIRCLE_SEGMENTS): List<LatLng> {
    val kmPerDegreeLat = 111.32
    val kmPerDegreeLon = kmPerDegreeLat * cos(Math.toRadians(center.latitude))
    return (0..segments).map { i ->
        val angle = 2.0 * PI * i / segments
        val dLat = (radiusKm * cos(angle)) / kmPerDegreeLat
        val dLon = (radiusKm * sin(angle)) / kmPerDegreeLon
        LatLng(center.latitude + dLat, center.longitude + dLon)
    }
}
