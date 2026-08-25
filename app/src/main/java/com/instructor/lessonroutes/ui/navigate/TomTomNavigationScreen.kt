package com.instructor.lessonroutes.ui.navigate

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.instructor.lessonroutes.data.routegen.GeneratedRoute
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
import com.tomtom.sdk.routing.RoutePlanningCallback
import com.tomtom.sdk.routing.RoutePlanningResponse
import com.tomtom.sdk.routing.RoutingFailure
import com.tomtom.sdk.routing.options.Itinerary
import com.tomtom.sdk.routing.options.RouteLegOptions
import com.tomtom.sdk.routing.options.RoutePlanningOptions
import com.tomtom.sdk.routing.options.calculation.ReconstructionMode
import com.tomtom.sdk.routing.route.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.maplibre.android.geometry.LatLng

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

private fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)

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
        // Reconstructs the already-generated route via TomTom -- confirmed
        // live by TomTomNavSpikeScreen.kt that this preserves a backtracking
        // route's real distance instead of collapsing it. One leg,
        // supportingPoints = the route's own dense point list -- no separate
        // waypoints, no extra network call (route.points already IS the
        // "already-computed polyline" reconstruction wants).
        val supportingPoints = route.points.map { it.toGeoPoint() }
        val routePlanningOptions = RoutePlanningOptions(
            itinerary = Itinerary(
                origin = supportingPoints.first(),
                destination = supportingPoints.last(),
                waypoints = emptyList(),
            ),
            routeLegOptions = listOf(RouteLegOptions(supportingPoints = supportingPoints)),
            reconstructionMode = ReconstructionMode.Route,
        )
        val routePlanner = TomTomSdk.createRoutePlanner()
        routePlanner.planRoute(
            routePlanningOptions,
            object : RoutePlanningCallback {
                override fun onSuccess(result: RoutePlanningResponse) {
                    val tomtomRoute = result.routes.firstOrNull()
                    if (tomtomRoute == null) {
                        planningError = "Couldn't start navigation: reconstruction returned no route."
                        return
                    }
                    Log.d(
                        LOG_TAG,
                        "Reconstructed: length=${tomtomRoute.summary.length} travelTime=${tomtomRoute.summary.travelTime}",
                    )
                    routePlan = RoutePlan(route = tomtomRoute, routePlanningOptions = routePlanningOptions)
                }

                override fun onFailure(failure: RoutingFailure) {
                    Log.e(LOG_TAG, "Reconstruction failed: $failure")
                    planningError = "Couldn't start navigation: $failure"
                }
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
            TomTomSdk.navigation.start(NavigationOptions(plan))
            navigationStarted = true
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
                planningError != null ->
                    Text(planningError.orEmpty(), modifier = Modifier.padding(16.dp))
                !hasEnoughPoints ->
                    Text("This route doesn't have enough points to navigate.", modifier = Modifier.padding(16.dp))
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
    val initialCameraOptions = remember(route) {
        InitialCameraOptions.LocationBased(position = route.points.first().toGeoPoint())
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
