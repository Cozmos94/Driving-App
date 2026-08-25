package com.instructor.lessonroutes.ui.navigate

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.instructor.lessonroutes.data.routegen.GeneratedRoute
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.util.LOCATION_PERMISSIONS
import com.instructor.lessonroutes.util.hasLocationPermission
import com.instructor.lessonroutes.util.startLocationUpdates
import com.tomtom.sdk.init.TomTomSdk
import com.tomtom.sdk.init.createRoutePlanner
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.map.display.MapLocationInfrastructure
import com.tomtom.sdk.map.display.camera.CameraTrackingMode
import com.tomtom.sdk.map.display.camera.InitialCameraOptions
import com.tomtom.sdk.map.display.compose.TomTomMap
import com.tomtom.sdk.map.display.compose.model.MapDisplayInfrastructure
import com.tomtom.sdk.map.display.compose.nodes.CurrentLocationMarker
import com.tomtom.sdk.map.display.compose.properties.CurrentLocationMarkerProperties
import com.tomtom.sdk.map.display.compose.state.rememberMapViewState
import com.tomtom.sdk.map.display.location.LocationMarkerOptions
import com.tomtom.sdk.map.display.style.StandardStyles
import com.tomtom.sdk.map.display.style.StyleMode
import com.tomtom.sdk.map.display.visualization.navigation.NavigationVisualizationDataProvider
import com.tomtom.sdk.map.display.visualization.navigation.compose.NavigationVisualization
import com.tomtom.sdk.map.display.visualization.navigation.compose.model.NavigationVisualizationInfrastructure
import com.tomtom.sdk.map.display.visualization.routing.RoutingVisualizationDataProvider
import com.tomtom.sdk.navigation.NavigationOptions
import com.tomtom.sdk.navigation.RoutePlan
import com.tomtom.sdk.routing.RoutePlanner
import com.tomtom.sdk.routing.RoutePlanningCallback
import com.tomtom.sdk.routing.RoutePlanningResponse
import com.tomtom.sdk.routing.RoutingFailure
import com.tomtom.sdk.routing.buildRoutePlanningOptions
import com.tomtom.sdk.routing.options.Itinerary
import com.tomtom.sdk.routing.options.RouteLegOptions
import com.tomtom.sdk.routing.options.RoutePlanningOptions
import com.tomtom.sdk.routing.options.calculation.ReconstructionMode
import com.tomtom.sdk.routing.route.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.maplibre.android.geometry.LatLng
import java.util.Locale

// ---------------------------------------------------------------------------
// Real turn-by-turn guidance for the "Navigate" button on GenerateRouteScreen.kt.
//
// SECOND REWRITE, read before running: the first version used
// com.tomtom.sdk.navigation:ui-android-complete's NavigationFragment (a
// View/Fragment-based UI wrapper) -- confirmed live on-device that it renders
// its guidance chrome (maneuver banner, speed display) but NO map at all.
// Root cause, confirmed by reading that module's own POM: it has zero
// dependency on any map-rendering module, and there's no documented way to
// attach one to it. Rather than keep guessing at an increasingly-clearly
// unsupported path, I read TomTom's own current, actively-maintained example
// app (github.com/tomtom-international/tomtom-example-app) directly -- it
// doesn't use NavigationFragment/navigation-ui at all. It uses a Compose-
// native map (map-display-compose-standard) + a separate route/position
// visualization layer (visualization-compose), both driven by the same
// *headless* TomTomNavigation engine the original spike already proved works
// (TomTomSdk.navigation.start(NavigationOptions(routePlan)), no Fragment
// involved). This rewrite follows that confirmed-real pattern, not a guess:
// every API used below (MapDisplayInfrastructure, NavigationVisualization,
// NavigationVisualizationInfrastructure, RoutingVisualizationDataProvider,
// TomTomSdk.sdkContext/locationProvider, etc.) was read verbatim out of that
// repo's actual source files, not inferred from stale docs.
//
// What's genuinely simplified vs their app (deliberately, not by oversight):
// no route-alternatives/preview UI (we already have exactly one reconstructed
// route ready to go, so routingVisualizationDataProvider is fed trivial empty
// flows -- NavigationVisualization is what actually draws the active route
// once navigation.start() is called), no maneuver-text panel yet (a fast-
// follow once this baseline is confirmed working on-device -- the map + live
// position + route line + camera-follow below IS the core turn-by-turn
// experience; a text overlay is polish on top, not required for it to work),
// no TTS (matches "no voice" -- there's simply no code here that would ever
// speak anything).
// ---------------------------------------------------------------------------

private const val LOG_TAG = "TomTomNavigationScreen"

// TomTom's exact reconstruction has shown a real, repeatable failure mode in
// live testing -- CANNOT_RESTORE_BASEROUTE recurred across several attempts
// in the same area (Wollongong: coastal + escarpment, genuinely sparser road
// coverage in some directions). Point count looked correlated at first
// (1271 succeeded, 1487/1610 failed), but capping supportingPoints at 1000
// -- well below that apparent threshold -- *still* failed, ruling out a
// simple point-count ceiling as the (sole) cause. The more likely real
// explanation: TomTom's own road data has gaps Geoapify's (OSM-based)
// doesn't in this area, and exact reconstruction has zero tolerance for
// that mismatch -- it demands a matching road for every segment of an
// externally-computed path. Downsampling still helps keep individual
// requests smaller/faster, so it's kept, but see planRouteWithFallback
// below for the real fix: falling through to genuine route planning when
// reconstruction can't be satisfied.
private const val MAX_SUPPORTING_POINTS = 1000

// Shape-defining waypoint count for the route-planning fallback (see
// planRouteWithFallback) -- well under Google's 25-waypoint hard cap, one of
// the original reasons this project chose TomTom over Google's own Nav SDK.
// Real route planning through a few dozen waypoints is baseline
// functionality for any navigation API; unlike exact reconstruction, it
// doesn't need to match a specific external road, just pass near each
// waypoint in order, so a modest count here still preserves the overall
// backtracking/petal shape without the strict segment-matching that made
// reconstruction fragile.
private const val SHAPE_WAYPOINT_COUNT = 20

private fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)

/** Reduces [this] to at most [maxPoints] by even-stride sampling, always
 * keeping the first and last point (start/destination) intact. A no-op when
 * already at or under the cap. */
private fun <T> List<T>.downsampledTo(maxPoints: Int): List<T> {
    if (size <= maxPoints || maxPoints < 2) return this
    val stride = (size - 1).toDouble() / (maxPoints - 1)
    return (0 until maxPoints).map { i -> this[(i * stride).toInt().coerceAtMost(size - 1)] }
}

/** Tries each of [attempts] (label to options) against [routePlanner] in
 * order, calling [onSuccess] with the first one that actually returns a
 * route, or [onAllFailed] if every attempt fails. See this file's own
 * "THIRD APPROACH" comment (in [TomTomNavigationScreen]) for why this exists
 * -- exact reconstruction and real route planning have very different
 * failure modes, so trying reconstruction first (best fidelity) and falling
 * through to real planning (best reliability) covers both. */
private fun planRouteWithFallback(
    routePlanner: RoutePlanner,
    attempts: List<Pair<String, RoutePlanningOptions>>,
    onSuccess: (Route, RoutePlanningOptions) -> Unit,
    onAllFailed: () -> Unit,
) {
    if (attempts.isEmpty()) {
        onAllFailed()
        return
    }
    val (label, options) = attempts.first()
    routePlanner.planRoute(
        options,
        object : RoutePlanningCallback {
            override fun onSuccess(result: RoutePlanningResponse) {
                val tomtomRoute = result.routes.firstOrNull()
                if (tomtomRoute == null) {
                    Log.w(LOG_TAG, "$label: planning succeeded but returned no routes, trying next")
                    planRouteWithFallback(routePlanner, attempts.drop(1), onSuccess, onAllFailed)
                    return
                }
                Log.d(LOG_TAG, "$label: succeeded")
                onSuccess(tomtomRoute, options)
            }

            override fun onFailure(failure: RoutingFailure) {
                Log.w(LOG_TAG, "$label: failed ($failure), trying next")
                planRouteWithFallback(routePlanner, attempts.drop(1), onSuccess, onAllFailed)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TomTomNavigationScreen(route: GeneratedRoute, onExit: () -> Unit) {
    val context = LocalContext.current
    var planningError by remember { mutableStateOf<String?>(null) }
    var routePlan by remember { mutableStateOf<RoutePlan?>(null) }
    var navigationStarted by remember { mutableStateOf(false) }
    // Checked synchronously (not just inside the async LaunchedEffect below)
    // so a degenerate route can never reach LiveNavigationMap's
    // route.points.first() before the async check would have caught it.
    val hasEnoughPoints = route.points.size >= 2

    LaunchedEffect(route) {
        if (!hasEnoughPoints) {
            planningError = "This route doesn't have enough points to navigate."
            return@LaunchedEffect
        }
        try {
            TomTomSdkInit.ensureInitialized(context)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "SDK init failed", e)
            planningError = "Couldn't start navigation: SDK init failed (${e.message})"
            return@LaunchedEffect
        }
        // THIRD APPROACH, read before running: exact reconstruction
        // (ReconstructionMode.Route + dense supportingPoints) turned out to
        // be unreliable in real testing -- confirmed live across several
        // attempts in the same area that reconstruction can fail with
        // CANNOT_RESTORE_BASEROUTE regardless of point count (1271 points
        // succeeded; 1000, 1487, and 1610 all failed at various times).
        // Reconstruction demands TomTom's own road data match Geoapify's
        // exact chosen path segment-for-segment -- an unreasonably strict
        // bar in any area where TomTom's coverage is thinner than
        // Geoapify's (OSM-based), which real testing showed is a real
        // condition here, not a hypothetical one.
        //
        // Fixed by trying exact reconstruction first (best fidelity to the
        // generated route when it works), then automatically falling
        // through to a genuine TomTom *route-planning* call -- letting
        // TomTom compute its own path using only roads it actually has,
        // through a handful of shape-defining waypoints sampled from the
        // generated route, rather than demanding an exact external match.
        // This can't hit "no valid route" the same way real reconstruction
        // can: TomTom is choosing its own roads throughout, not trying to
        // snap onto someone else's. buildRoutePlanningOptions (not the raw
        // RoutePlanningOptions constructor, and no reconstructionMode at
        // all) is TomTom's own real, confirmed pattern for this -- read
        // verbatim out of RoutesViewModel.kt in TomTom's current example
        // app, the same one that already grounded the map-rendering
        // rewrite. Only a modest waypoint count (SHAPE_WAYPOINT_COUNT) is
        // used, well under Google's 25-waypoint hard cap that was one of
        // the original reasons this project chose TomTom over Google's own
        // Nav SDK in the first place -- real route planning through a few
        // dozen waypoints is baseline functionality for any real
        // navigation API, not the exotic capability exact reconstruction
        // turned out to be.
        val routePlanner = TomTomSdk.createRoutePlanner()
        val reconstructionPoints = route.points.downsampledTo(MAX_SUPPORTING_POINTS).map { it.toGeoPoint() }
        val reconstructionOptions = RoutePlanningOptions(
            itinerary = Itinerary(
                origin = reconstructionPoints.first(),
                destination = reconstructionPoints.last(),
                waypoints = emptyList(),
            ),
            routeLegOptions = listOf(RouteLegOptions(supportingPoints = reconstructionPoints)),
            reconstructionMode = ReconstructionMode.Route,
        )
        val shapePoints = route.points.downsampledTo(SHAPE_WAYPOINT_COUNT).map { it.toGeoPoint() }
        val routePlanningOptionsFallback = buildRoutePlanningOptions(
            itinerary = Itinerary(
                origin = shapePoints.first(),
                destination = shapePoints.last(),
                waypoints = shapePoints.drop(1).dropLast(1),
            ),
            language = Locale.getDefault(),
        )
        Log.d(
            LOG_TAG,
            "Planning: ${reconstructionPoints.size} reconstruction supportingPoints " +
                "(from ${route.points.size}), ${shapePoints.size} fallback shape waypoints, " +
                "${"%.1f".format(route.distanceMeters / 1000.0)}km, ${"%.1f".format(route.durationSeconds / 60.0)}min",
        )
        planRouteWithFallback(
            routePlanner = routePlanner,
            attempts = listOf("exact reconstruction" to reconstructionOptions, "waypoint routing" to routePlanningOptionsFallback),
            onSuccess = { tomtomRoute, options ->
                Log.d(LOG_TAG, "Planned: length=${tomtomRoute.summary.length} travelTime=${tomtomRoute.summary.travelTime}")
                routePlan = RoutePlan(route = tomtomRoute, routePlanningOptions = options)
            },
            onAllFailed = {
                planningError = "Couldn't start navigation: TomTom couldn't plan a route for this trip."
            },
        )
    }

    // Headless engine start -- exactly the call the spike already confirmed
    // works, just triggered here instead of from a debug button. No Fragment
    // involved; NavigationVisualization (in LiveNavigationMap below) draws
    // whatever this session is actively navigating automatically.
    LaunchedEffect(routePlan) {
        val plan = routePlan
        if (plan != null && !navigationStarted) {
            try {
                TomTomSdk.navigation.start(NavigationOptions(plan))
                navigationStarted = true
                Log.d(LOG_TAG, "navigation.start() succeeded")
            } catch (e: Exception) {
                // Previously uncaught -- a silent failure here would explain
                // "map loads but no route/guidance ever appears" with zero
                // trace of why, which is exactly the symptom being chased.
                Log.e(LOG_TAG, "navigation.start() failed", e)
                planningError = "Couldn't start navigation: ${e.message}"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                TomTomSdk.navigation.stop()
            } catch (e: Exception) {
                // Most likely just means navigation never actually started
                // (e.g. exited during planning/reconstruction).
                Log.w(LOG_TAG, "Stopping navigation on exit failed (probably wasn't running)", e)
            }
        }
    }

    BackHandler { onExit() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Navigate") },
                actions = { TextButton(onClick = onExit) { Text("Close") } },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                !hasEnoughPoints ->
                    Text("This route doesn't have enough points to navigate.", modifier = Modifier.padding(16.dp))
                // TomTom can genuinely fail to reconstruct/start guidance for
                // a given route (confirmed live: CANNOT_RESTORE_BASEROUTE on
                // some routes but not others, seemingly tied to route
                // geometry/size rather than a fixable bug on our end) --
                // rather than dead-ending on an error message, fall back to
                // the plain map + live position view this screen replaced,
                // so the instructor still has something usable.
                planningError != null ->
                    FallbackLiveMap(route = route, reason = planningError.orEmpty())
                // Real bug, confirmed via a crash: this branch used to be
                // reached as soon as hasEnoughPoints was true -- which is
                // true on the very first composition, before the
                // LaunchedEffect above (which calls TomTomSdkInit.
                // ensureInitialized) has actually had a chance to run (that's
                // async; this `when` renders synchronously in the same initial
                // composition pass). LiveNavigationMap reads TomTomSdk.
                // sdkContext immediately, so it crashed with "TomTomSdk is
                // not initialized" every time. Gating on routePlan != null
                // guarantees the SDK-init LaunchedEffect above has already
                // run to completion first (routePlan is only ever set later
                // in that same coroutine, after ensureInitialized succeeded).
                routePlan == null ->
                    Text("Preparing turn-by-turn guidance…", modifier = Modifier.padding(16.dp))
                else ->
                    LiveNavigationMap(route = route, isNavigating = navigationStarted)
            }
        }
    }
}

/** The actual live map: real TomTom tiles, a chevron marker at the device's
 * current position, and the active navigation session's route line --
 * confirmed-real API surface, see this file's own top comment for where each
 * piece came from. */
@Composable
private fun LiveNavigationMap(route: GeneratedRoute, isNavigating: Boolean) {
    val styleMode = if (isSystemInDarkTheme()) StyleMode.DARK else StyleMode.MAIN

    val mapDisplayInfrastructure = remember {
        MapDisplayInfrastructure(sdkContext = TomTomSdk.sdkContext) {
            locationInfrastructure = MapLocationInfrastructure {
                locationProvider = TomTomSdk.locationProvider
            }
        }
    }
    // No route-alternatives/preview concept here (unlike the example app,
    // which lets the driver pick among several planned routes before
    // starting) -- we already have exactly one reconstructed route, so this
    // is fed trivial empty flows. NavigationVisualizationDataProvider (tied
    // to the same TomTomSdk.navigation engine started above) is what
    // actually draws the active route once navigation.start() has run.
    val navigationVisualizationInfrastructure = remember {
        NavigationVisualizationInfrastructure(
            routingVisualizationDataProvider = flowOf(
                RoutingVisualizationDataProvider(
                    routes = MutableStateFlow<List<Route>>(emptyList()),
                    selectedRouteId = flowOf(null),
                ),
            ),
            navigationVisualizationDataProvider = flowOf(
                NavigationVisualizationDataProvider(tomtomNavigation = TomTomSdk.navigation),
            ),
        )
    }
    // zoom explicitly set -- confirmed real gap: TomTom's own example app
    // always passes an explicit zoom to InitialCameraOptions.LocationBased
    // (e.g. its FREE_DRIVING_CAMERA_ZOOM = 16.0); the version of this file
    // that left zoom unset likely opened at whatever TomTom's own default
    // is, which could easily be a country/region-wide view -- a real
    // candidate for "map loads, position marker shows, but nothing else is
    // visible" (a route line a few km long is imperceptible zoomed that far
    // out). 16.0 matches the close, driving-style zoom this project already
    // settled on for the old placeholder live-tracking view.
    val initialCameraOptions = remember(route) {
        InitialCameraOptions.LocationBased(position = route.points.first().toGeoPoint(), zoom = 16.0)
    }
    val mapViewState = rememberMapViewState(initialCameraOptions = initialCameraOptions) {
        styleState.styleMode = styleMode
    }

    // A freshly-constructed MapViewState has no visible tiles until a real
    // style is loaded -- easy to miss (this is what silently produced a
    // blank-but-otherwise-working map the first time around, in the *other*
    // sense: NavigationFragment's map never existed at all; this loadStyle
    // call is what makes an actual TomTomMap show real tiles).
    LaunchedEffect(Unit) {
        mapViewState.styleState.loadStyle(StandardStyles.TomTomOrbisMaps.DRIVING)
    }

    LaunchedEffect(isNavigating) {
        if (isNavigating) {
            mapViewState.cameraState.trackingMode = CameraTrackingMode.FollowRouteDirection
        }
    }

    DisposableEffect(Unit) {
        TomTomSdk.locationProvider.enable()
        onDispose {
            TomTomSdk.locationProvider.disable()
        }
    }

    TomTomMap(
        infrastructure = mapDisplayInfrastructure,
        state = mapViewState,
        modifier = Modifier.fillMaxSize(),
    ) {
        CurrentLocationMarker(
            CurrentLocationMarkerProperties {
                type = LocationMarkerOptions.Type.Chevron
            },
        )
        NavigationVisualization(infrastructure = navigationVisualizationInfrastructure) {}
    }
}

/** Fallback shown when TomTom fails for any reason (SDK init, reconstruction,
 * or navigation.start() itself) -- no real turn-by-turn guidance, just the
 * generated route drawn on this app's own map with a live position dot, the
 * same shape the old placeholder "Navigate" view used before TomTom was wired
 * in. Tracks location itself (this screen has no other location source to
 * reuse, unlike GenerateRouteScreen.kt) via the same FusedLocationProvider
 * pattern already used there -- centerOnDeviceLocation=false on RouteMapView
 * since this live stream already drives followLiveLocation, avoiding the
 * exact permission/centering race already documented on that other screen. */
@Composable
private fun FallbackLiveMap(route: GeneratedRoute, reason: String) {
    val context = LocalContext.current
    var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> hasLocationPermission = results.values.any { it } }
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose {}
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { currentLocation = LatLng(it.latitude, it.longitude) }
            }
        }
        fusedClient.startLocationUpdates(request, callback)
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Full turn-by-turn guidance couldn't start ($reason). Showing your route on the map instead.",
            modifier = Modifier.padding(12.dp),
        )
        RouteMapView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            routePoints = route.points,
            liveLocation = currentLocation,
            followLiveLocation = true,
            centerOnDeviceLocation = false,
            focusPoint = currentLocation,
            focusZoom = 16.0,
        )
    }
}
