package com.instructor.lessonroutes.ui.navigate

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.RoundaboutLeft
import androidx.compose.material.icons.filled.RoundaboutRight
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.UTurnLeft
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import com.tomtom.sdk.map.display.camera.CameraOptions
import com.tomtom.sdk.map.display.camera.CameraTrackingMode
import com.tomtom.sdk.map.display.camera.InitialCameraOptions
import com.tomtom.sdk.map.display.compose.TomTomMap
import com.tomtom.sdk.map.display.compose.model.MapDisplayInfrastructure
import com.tomtom.sdk.map.display.compose.model.PolylineData
import com.tomtom.sdk.map.display.compose.nodes.CurrentLocationMarker
import com.tomtom.sdk.map.display.compose.nodes.Polyline
import com.tomtom.sdk.map.display.compose.properties.CurrentLocationMarkerProperties
import com.tomtom.sdk.map.display.compose.properties.PolylineProperties
import com.tomtom.sdk.map.display.compose.state.rememberMapViewState
import com.tomtom.sdk.map.display.location.LocationMarkerOptions
import com.tomtom.sdk.map.display.style.StandardStyles
import com.tomtom.sdk.map.display.style.StyleMode
import com.tomtom.sdk.map.display.visualization.navigation.NavigationVisualizationDataProvider
import com.tomtom.sdk.map.display.visualization.navigation.compose.NavigationVisualization
import com.tomtom.sdk.map.display.visualization.navigation.compose.model.NavigationVisualizationInfrastructure
import com.tomtom.sdk.map.display.visualization.routing.RoutingVisualizationDataProvider
import com.tomtom.sdk.navigation.GuidanceUpdatedListener
import com.tomtom.sdk.navigation.NavigationOptions
import com.tomtom.sdk.navigation.ProgressUpdatedListener
import com.tomtom.sdk.navigation.RoutePlan
import com.tomtom.sdk.navigation.guidance.GuidanceAnnouncement
import com.tomtom.sdk.navigation.guidance.InstructionPhase
import com.tomtom.sdk.navigation.guidance.instruction.ArrivalGuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.DepartureGuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.ExitHighwayGuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.ExitRoundaboutGuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.ForkGuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.GuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.MandatoryTurnGuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.MergeGuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.RoundaboutGuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.TurnAroundWhenPossibleGuidanceInstruction
import com.tomtom.sdk.navigation.guidance.instruction.TurnGuidanceInstruction
import com.tomtom.quantity.Distance
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.time.Duration

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
// no route-alternatives/preview UI (we already have exactly one planned route
// ready to go -- routingVisualizationDataProvider is fed that one real route,
// not a driver-facing choice of several), no TTS (matches "no voice" --
// there's simply no code here that would ever speak anything). The guidance
// text overlay (next maneuver, distance, ETA) IS built now -- see
// describeInstruction() and LiveNavigationMap's own listener setup.
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

// How far down the screen the followed "you are here" chevron sits while
// tracking is active (Corey: "currently it's all the way
// at the bottom... let's go 20% below center" -- i.e. ~70% down the screen,
// not ~100%). TomTom doesn't expose a documented anchor/offset property for
// this directly (confirmed against their own Dokka reference and guides --
// CameraOptions only has location/zoom/tilt/rotation, nothing padding-
// related); safeArea is the one property in this file already confirmed to
// move real content on screen (it's what fixed "the map stretches below the
// bottom border where ETA is" earlier this project), so it's the lever used
// here too: bumping the safe area's bottom inset well past the ETA card's own
// height pushes the tracked point up along with it. 30% of screen height is
// a first estimate, not something confirmed live (there's no way to measure
// the actual on-screen result without a device) -- if it lands short of or
// past "20% below center" once Corey checks it, this is the number to retune.
private const val FOLLOW_MARKER_MIN_BOTTOM_INSET_FRACTION = 0.30f

private fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)

/** Reduces [this] to at most [maxPoints] by even-stride sampling, always
 * keeping the first and last point (start/destination) intact. A no-op when
 * already at or under the cap. */
private fun <T> List<T>.downsampledTo(maxPoints: Int): List<T> {
    if (size <= maxPoints || maxPoints < 2) return this
    val stride = (size - 1).toDouble() / (maxPoints - 1)
    return (0 until maxPoints).map { i -> this[(i * stride).toInt().coerceAtMost(size - 1)] }
}

/** "850m" below 1km, "12.3km" at or above -- Distance's own toString() gives
 * a generic default format, not the km-with-one-decimal convention this
 * project already uses elsewhere (e.g. GenerateRouteScreen.kt's
 * formatDistance()). inMeters()/inKilometers() are confirmed-real Distance
 * methods (com.tomtom.quantity.Distance), read off TomTom's own Dokka
 * reference. */
private fun formatDistance(distance: Distance): String {
    val km = distance.inKilometers()
    return if (km >= 1.0) "%.1fkm".format(km) else "${distance.inMeters().roundToInt()}m"
}

/** "1h 44m" / "44m" -- kotlin.time.Duration's own toString() ("1h 44m 26s")
 * includes seconds, which is more precision than a remaining-drive-time
 * display needs. RouteProgress.remainingTime is a plain kotlin.time.Duration
 * (confirmed by ruling it out: com.tomtom.quantity has no Duration class at
 * all, unlike Distance), not a TomTom-specific quantity type. */
private fun formatDuration(duration: Duration): String {
    val totalMinutes = duration.inWholeMinutes
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/** Wall-clock arrival estimate ("3:45 pm") -- computed fresh from
 * LocalTime.now() at call time, not memoized, same "now needs to actually
 * mean now" reasoning GenerateRouteScreen.kt's own start-time handling
 * already uses. */
private fun formatArrivalTime(remaining: Duration): String =
    LocalTime.now().plusSeconds(remaining.inWholeSeconds).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

/** Turns a value's raw name into readable words ("turn left") without
 * needing to know its exact type shape or constant names ahead of time --
 * receiver is `Any`, not `Enum<*>`, since TurnDirection/ForkDirection turned
 * out not to be plain Kotlin enums (confirmed by a real compile error:
 * "Enum<*>.readableName()" was unresolved against them) -- safer than
 * hardcoding a guessed set of enum values, or a guessed type shape, that
 * could be wrong either way.
 *
 * Real, confirmed bug fixed here: this originally only lowercased + replaced
 * underscores, on a wrong assumption the raw name would be SCREAMING_SNAKE
 * ("TURN_LEFT"). Corey's actual on-device report -- "Turn turnleft onto Bank
 * Street" -- gave away the real shape: TomTom's TurnDirection values are
 * PascalCase with no separator at all ("TurnLeft"), so the old code just
 * lowercased the whole thing into one squished word ("turnleft") instead of
 * splitting it. Now splits before each interior capital letter first
 * ("TurnSharpLeft" -> "Turn Sharp Left"), then lowercases -- the underscore
 * replace is kept too in case some other subtype's name ever does use one. */
private fun Any.readableName(): String =
    toString().replace(Regex("(?<!^)([A-Z])"), " $1").replace('_', ' ').lowercase()

/** Real, human-readable turn-by-turn text -- previously this screen only
 * showed the upcoming road name ("onto Bank Street"), never an actual
 * maneuver verb, because GuidanceInstruction's base interface genuinely
 * doesn't carry one: direction/turn info only exists on its subclasses
 * (confirmed via TomTom's own Dokka reference, not guessed). Covers the
 * common on-road cases; falls through to a plain road-name mention for the
 * remaining subclasses (merge lanes, carpool lanes, tollgates, border
 * crossings, waypoints) rather than guessing at how to phrase those too. */
private fun describeInstruction(instruction: GuidanceInstruction): String {
    val roadName = instruction.nextSignificantRoad?.name
    val ontoRoad = roadName?.let { " onto $it" } ?: ""
    return when (instruction) {
        // No hardcoded "Turn " prefix here (unlike the other branches below) --
        // TurnDirection's own raw name already says "Turn..." ("TurnLeft",
        // "TurnSharpLeft", confirmed live), so readableName() alone already
        // reads naturally once capitalized. Prepending "Turn " again on top of
        // it was the actual cause of the "Turn turnleft" duplicate bug.
        is TurnGuidanceInstruction -> "${instruction.turnDirection.readableName().replaceFirstChar { it.titlecase() }}$ontoRoad"
        is MandatoryTurnGuidanceInstruction ->
            "${instruction.turnDirection.readableName().replaceFirstChar { it.titlecase() }}$ontoRoad"
        is RoundaboutGuidanceInstruction ->
            "At the roundabout" + (instruction.exitNumber?.let { ", take exit $it" } ?: "") + ontoRoad
        is ExitRoundaboutGuidanceInstruction -> "Exit the roundabout$ontoRoad"
        is ForkGuidanceInstruction -> "Keep ${instruction.forkDirection.readableName()}$ontoRoad"
        is MergeGuidanceInstruction -> "Merge$ontoRoad"
        is ExitHighwayGuidanceInstruction -> "Take the exit$ontoRoad"
        is ArrivalGuidanceInstruction -> "Arrive at your destination"
        is DepartureGuidanceInstruction -> "Head out$ontoRoad"
        is TurnAroundWhenPossibleGuidanceInstruction -> "Turn around when possible"
        else -> "Continue$ontoRoad"
    }
}

/** A real directional arrow for the top instruction banner (Corey's
 * reference: TomTom's own marketing screenshot shows a distinct turn-shaped
 * arrow, not just text) -- matched by subtype first (roundabout/arrival need
 * their own icons regardless of any turnDirection-shaped field), then by
 * checking the readable direction text for known words ("sharp"/"slight"/
 * "left"/"right"/"u turn") rather than matching against guessed exact enum
 * constants, same defensive reasoning as readableName() itself. Falls back
 * to a plain "continue straight" arrow for every other subclass. */
private fun instructionIcon(instruction: GuidanceInstruction): ImageVector {
    if (instruction is ArrivalGuidanceInstruction) return Icons.Default.Flag
    val roundaboutDirection = when (instruction) {
        is RoundaboutGuidanceInstruction -> instruction.roundaboutDirection.readableName()
        is ExitRoundaboutGuidanceInstruction -> instruction.roundaboutDirection.readableName()
        else -> null
    }
    if (roundaboutDirection != null) {
        return if (roundaboutDirection.contains("left")) Icons.Default.RoundaboutLeft else Icons.Default.RoundaboutRight
    }
    val direction = when (instruction) {
        is TurnGuidanceInstruction -> instruction.turnDirection.readableName()
        is MandatoryTurnGuidanceInstruction -> instruction.turnDirection.readableName()
        else -> null
    } ?: return Icons.Default.ArrowUpward
    return when {
        direction.contains("sharp") && direction.contains("left") -> Icons.Default.TurnSharpLeft
        direction.contains("sharp") && direction.contains("right") -> Icons.Default.TurnSharpRight
        direction.contains("slight") && direction.contains("left") -> Icons.Default.TurnSlightLeft
        direction.contains("slight") && direction.contains("right") -> Icons.Default.TurnSlightRight
        direction.contains("u") && direction.contains("turn") -> Icons.Default.UTurnLeft
        direction.contains("left") -> Icons.Default.TurnLeft
        direction.contains("right") -> Icons.Default.TurnRight
        else -> Icons.Default.ArrowUpward
    }
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
                actions = {
                    IconButton(onClick = onExit) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        val plan = routePlan
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
                plan == null ->
                    Text("Preparing turn-by-turn guidance…", modifier = Modifier.padding(16.dp))
                else ->
                    LiveNavigationMap(route = route, tomtomRoute = plan.route, isNavigating = navigationStarted)
            }
        }
    }
}

/** The actual live map: real TomTom tiles, a chevron marker at the device's
 * current position, and the active navigation session's route line --
 * confirmed-real API surface, see this file's own top comment for where each
 * piece came from. */
@Composable
private fun LiveNavigationMap(route: GeneratedRoute, tomtomRoute: Route, isNavigating: Boolean) {
    val styleMode = if (isSystemInDarkTheme()) StyleMode.DARK else StyleMode.MAIN
    // animateCamera is a suspend function (confirmed by a real compile
    // error when it was called directly from the Recenter FAB's plain,
    // non-suspend onClick below) -- needs a real coroutine to launch into,
    // same reasoning as any other suspend call from a Compose click handler.
    val coroutineScope = rememberCoroutineScope()

    val mapDisplayInfrastructure = remember {
        MapDisplayInfrastructure(sdkContext = TomTomSdk.sdkContext) {
            locationInfrastructure = MapLocationInfrastructure {
                locationProvider = TomTomSdk.locationProvider
            }
        }
    }
    // Real bug, confirmed live: feeding this trivial *empty* flows (no route-
    // alternatives/preview concept here, unlike the example app which lets
    // the driver pick among several planned routes before starting) meant
    // nothing ever drew on the map even though navigation.start() genuinely
    // succeeded and progress/guidance data was flowing -- the route line
    // itself apparently needs to actually be present in
    // RoutingVisualizationDataProvider's own routes/selectedRouteId, not just
    // referenced indirectly via the shared TomTomNavigation engine. Fixed by
    // feeding it the real planned route (tomtomRoute, the same Route object
    // that's actually being navigated) instead of an empty placeholder.
    val navigationVisualizationInfrastructure = remember(tomtomRoute) {
        NavigationVisualizationInfrastructure(
            routingVisualizationDataProvider = flowOf(
                RoutingVisualizationDataProvider(
                    routes = MutableStateFlow(listOf(tomtomRoute)),
                    selectedRouteId = flowOf(tomtomRoute.id),
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

    // safeArea (a real MapViewState property, confirmed via TomTom's own
    // example app -- MapScreenContent.kt sets it from device safe-area
    // insets the same way) tells the map to keep its own important content
    // (camera framing, attribution) clear of whatever's overlaid on top of
    // it. Without it the map itself doesn't know the top instruction card/
    // bottom ETA card exist, so it can render "behind" them rather than
    // treating that space as unavailable -- confirmed real gap (Corey:
    // "the map stretches below the bottom border where eta is"). Measured
    // dynamically from the actual card sizes (onGloballyPositioned below)
    // rather than a guessed fixed dp value, since card height depends on
    // their actual text content.
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    var topCardHeightPx by remember { mutableStateOf(0) }
    var bottomCardHeightPx by remember { mutableStateOf(0) }
    LaunchedEffect(topCardHeightPx, bottomCardHeightPx, screenHeightDp) {
        val bottomCardHeightDp = with(density) { bottomCardHeightPx.toDp() }
        // See FOLLOW_MARKER_MIN_BOTTOM_INSET_FRACTION's own comment -- the
        // bottom inset needs to be at least this fraction of the screen to
        // pull the followed position up off the very bottom edge, on top of
        // whatever it already needs to clear the real ETA card underneath it.
        val followMarkerInsetDp = screenHeightDp.dp * FOLLOW_MARKER_MIN_BOTTOM_INSET_FRACTION
        mapViewState.safeArea = PaddingValues(
            top = with(density) { topCardHeightPx.toDp() },
            bottom = maxOf(bottomCardHeightDp, followMarkerInsetDp),
        )
    }

    // A freshly-constructed MapViewState has no visible tiles until a real
    // style is loaded -- easy to miss (this is what silently produced a
    // blank-but-otherwise-working map the first time around, in the *other*
    // sense: NavigationFragment's map never existed at all; this loadStyle
    // call is what makes an actual TomTomMap show real tiles).
    //
    // Wrapped in try/catch, previously bare -- a real, confirmed gap: Corey
    // reported the map showing no buildings/land at all (just a flat
    // background + this app's own drawn route line/markers), with nothing in
    // Logcat to explain why. MapStyleState.loadStyle() (the Compose wrapper)
    // is a plain suspend fun with no return value and no callback param
    // (confirmed via TomTom's own Dokka reference) -- unlike the raw,
    // non-Compose StyleController.loadStyle(style, callback) it presumably
    // wraps internally, which DOES expose a real LoadingStyleFailure (a
    // message, plus an httpCode on its HttpFailure subclass) on failure. If
    // the Compose wrapper wraps a real coroutine bridge and surfaces that
    // failure by throwing (rather than silently doing nothing), this is what
    // will finally reveal it next time -- previously nothing here could show
    // whether the base style/tiles genuinely failed to load, vs. some other
    // rendering issue further down the (still-suspected, see this file's own
    // native-crash comments) shared-MapLibre-state path.
    LaunchedEffect(Unit) {
        try {
            // Diagnostic DRIVING -> BROWSING swap (now reverted) confirmed
            // this isn't a DRIVING-specific issue: BROWSING rendered exactly
            // as blank, even though loadStyle succeeded for both. The
            // renderer isn't painting ANY TomTom Orbis Maps style's layers --
            // see this file's own native-crash comments (same MapRenderer
            // subsystem) for the leading theory (this app's own separate
            // MapLibre instances, used on every other screen, likely
            // corrupting/stalling TomTom's embedded MapLibre renderer via
            // shared native process-wide state).
            mapViewState.styleState.loadStyle(StandardStyles.TomTomOrbisMaps.DRIVING)
            Log.d(LOG_TAG, "loadStyle: succeeded")
        } catch (e: CancellationException) {
            throw e // normal coroutine cancellation (e.g. screen closed mid-load), not a real failure
        } catch (e: Exception) {
            Log.e(LOG_TAG, "loadStyle: failed", e)
        }
    }

    LaunchedEffect(isNavigating) {
        if (isNavigating) {
            // Briefly switched to CameraTrackingMode.FollowDirection (tracks
            // raw device compass/GPS heading) to fix a real, confirmed bug --
            // FollowRouteDirection didn't respond to physically turning
            // around while stationary. That trade turned out worse in
            // practice: Corey's on-device driving test showed real jitter
            // (the camera spun with no movement, overshot on a real turn,
            // then stopped responding while still moving) -- a known,
            // physical limitation of phone magnetometers, not a bug in this
            // code: a car's metal body/engine/any magnetic phone mount
            // genuinely disrupts compass-based heading, which TomTom's
            // FollowDirection tracks directly with no GPS-course blending.
            // Reverted back to FollowRouteDirection (progress-along-the-
            // route-derived heading, effectively equivalent to GPS course)
            // -- Corey's explicit choice, prioritizing smooth/reliable
            // rotation while actually driving over responsiveness while
            // stationary, matching how Google Maps/Waze/Apple Maps all
            // behave in a moving vehicle (GPS course while driving, compass
            // heading mostly ignored -- for this exact reason).
            mapViewState.cameraState.trackingMode = CameraTrackingMode.FollowRouteDirection
            // 45-degree perspective tilt (Corey's request) -- tracking mode
            // only governs position/bearing (see onMapPanningListener's own
            // comment below), tilt is a separate CameraOptions property that
            // needs setting explicitly. 0 = straight down, 90 = horizon,
            // confirmed via TomTom's own Dokka reference.
            mapViewState.cameraState.animateCamera(CameraOptions(tilt = 45.0))
        }
    }

    DisposableEffect(Unit) {
        TomTomSdk.locationProvider.enable()
        onDispose {
            TomTomSdk.locationProvider.disable()
        }
    }

    // Guidance text overlay -- confirmed-real fields, read verbatim out of
    // TomTom's own example app's MapScreenViewModel.kt: RouteProgress.
    // remainingDistance/remainingTime (via ProgressUpdatedListener) for "time
    // left"/"how far to go"/ETA, and GuidanceUpdatedListener.
    // onDistanceToNextInstructionChanged's own distance param (NOT
    // GuidanceInstruction.routeOffset, which is distance-from-route-start,
    // not distance-to-this-instruction) plus describeInstruction() for the
    // actual maneuver text. Raw Distance/Duration kept in state (not
    // pre-formatted strings) so formatDistance/formatDuration/
    // formatArrivalTime can apply this app's own km/m-below-1km + hh:mm
    // conventions instead of TomTom's generic toString() output.
    var remainingTime by remember { mutableStateOf<Duration?>(null) }
    var remainingDistance by remember { mutableStateOf<Distance?>(null) }
    var instructionText by remember { mutableStateOf<String?>(null) }
    var instructionIconVector by remember { mutableStateOf<ImageVector?>(null) }
    var distanceToManeuver by remember { mutableStateOf<Distance?>(null) }

    // Was a two-tone route line (current-shade blue ahead, darker blue for
    // the already-driven part behind), removed entirely per Corey's
    // explicit request after two real problems: (1) a route-line color that
    // depended on MaterialTheme.colorScheme.primary happened to resolve to
    // literally the same color (AppBlack) for both ends on this particular
    // screen -- see the single remaining lineColor below for the actual
    // fix to *that* -- and (2) more fundamentally, a genuinely-driven
    // segment showed up immediately on navigation start, before any real
    // movement: TomTom's own reported distanceAlongRoute can be a small
    // non-zero value right away (the reconstructed route's own start point
    // doesn't necessarily land exactly on the live GPS fix, so some of it
    // can already read as "covered" purely from that snap, not real
    // driving). Corey: "there should be no line for 'already driven'... I
    // don't think there should ever be a line for 'already driven' or a
    // legend for it... if we're just sticking with the 1 blue colour"
    // -- simplest fix, and also removes the per-progress-tick re-slicing
    // that was a real contributor to an ANR on this screen (see the
    // now-deleted cumulativeDistancesMeters/splitIndexFor -- the single
    // line below is computed once via remember(route), never recomputed on
    // a progress update at all).
    DisposableEffect(Unit) {
        val progressListener = ProgressUpdatedListener { progress ->
            remainingTime = progress.remainingTime
            remainingDistance = progress.remainingDistance
        }
        val guidanceListener = object : GuidanceUpdatedListener {
            override fun onAnnouncementGenerated(announcement: GuidanceAnnouncement, shouldPlay: Boolean) {
                // Intentionally no-op -- no voice/TTS in this app (Corey's call).
            }

            // Corey report: the whole instruction card ("turn left in 150m")
            // disappeared entirely partway through navigating. Both callbacks
            // used to unconditionally set instructionText/instructionIconVector
            // to null whenever `instructions` came back empty -- since the top
            // card is only shown `if (instructionText != null)`, any momentary
            // gap in TomTom's own instructions list (a real possibility between
            // maneuvers, or a brief GPS/guidance hiccup -- a genuine "trip
            // complete" state would still carry a real ArrivalGuidanceInstruction,
            // not an empty list) blanked the entire card instead of just leaving
            // it stale for a moment. Now only updates on a real instruction;
            // an empty list simply leaves whatever was last shown in place,
            // which is a strict improvement either way -- briefly-stale
            // guidance text beats no guidance text at all.
            override fun onDistanceToNextInstructionChanged(
                distance: Distance,
                instructions: List<GuidanceInstruction>,
                currentPhase: InstructionPhase,
            ) {
                val next = instructions.firstOrNull() ?: return
                distanceToManeuver = distance
                instructionText = describeInstruction(next)
                instructionIconVector = instructionIcon(next)
            }

            override fun onInstructionsChanged(instructions: List<GuidanceInstruction>) {
                val next = instructions.firstOrNull() ?: return
                instructionText = describeInstruction(next)
                instructionIconVector = instructionIcon(next)
            }
        }
        TomTomSdk.navigation.addProgressUpdatedListener(progressListener)
        TomTomSdk.navigation.addGuidanceUpdatedListener(guidanceListener)
        onDispose {
            TomTomSdk.navigation.removeProgressUpdatedListener(progressListener)
            TomTomSdk.navigation.removeGuidanceUpdatedListener(guidanceListener)
        }
    }

    // Explicit, theme-independent blue -- real, confirmed bug from the old
    // two-tone version: this screen renders *before* GenerateRouteScreen.kt's
    // PlanTripTheme wrapper begins (see the `return` right above where that
    // wrapper starts), so it falls back to the main app's own theme, where
    // MaterialTheme.colorScheme.primary is literally AppBlack. A real,
    // deliberately-picked blue here instead means this line's own color
    // doesn't depend on whatever the ambient theme's primary role happens
    // to resolve to on this particular screen.
    val routeLineColor = Color(0xFF1A73E8)

    Box(Modifier.fillMaxSize()) {
        TomTomMap(
            infrastructure = mapDisplayInfrastructure,
            state = mapViewState,
            modifier = Modifier.fillMaxSize(),
            // Real gap, confirmed against TomTom's own example app: FollowRouteDirection
            // re-centers/re-orients the camera on every position update, which fights any
            // manual pan gesture (zoom wasn't affected since tracking mode only overrides
            // position/bearing, not zoom -- matches "can zoom but can't pan" exactly).
            // Their MapView.kt wires onMapPanningListener to drop back to
            // CameraTrackingMode.None the moment the user manually pans -- same fix here.
            onMapPanningListener = { mapViewState.cameraState.trackingMode = CameraTrackingMode.None },
        ) {
            CurrentLocationMarker(
                CurrentLocationMarkerProperties {
                    type = LocationMarkerOptions.Type.Chevron
                },
            )
            NavigationVisualization(infrastructure = navigationVisualizationInfrastructure) {}
            // Drawn on top of NavigationVisualization's own (single-color)
            // route line -- composed after it, so this renders above it.
            // One single line for the whole route, computed once (route
            // doesn't change during a navigation session) -- see this
            // section's own comment above for why the earlier two-tone
            // version was removed.
            Polyline(
                data = PolylineData(geoPoints = route.points.map { it.toGeoPoint() }),
                properties = PolylineProperties { lineColor = routeLineColor.toArgb() },
            )
        }

        // Floating rounded card with margin, NOT edge-to-edge -- matches
        // Corey's TomTom marketing-site reference screenshot, where the
        // instruction panel is a distinct card sitting on top of the map
        // (map visible around its edges), not a solid full-width instrument
        // strip. Icon + big distance on one line (the single most important
        // number, same visual weight the reference gives it), road/maneuver
        // text below.
        if (instructionText != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
                    .onGloballyPositioned { topCardHeightPx = it.size.height },
                shape = RoundedCornerShape(16.dp),
                // White background + black text (Corey's request), matching
                // the bottom ETA card's own styling instead of a colored
                // banner.
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    instructionIconVector?.let {
                        Icon(
                            it,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.width(16.dp))
                    }
                    Column {
                        Text(
                            distanceToManeuver?.let(::formatDistance) ?: "",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            instructionText.orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        // Recenter FAB, Google-Maps-style -- placed just above the ETA card
        // so it doesn't overlap it. Panning drops the camera out of
        // FollowRouteDirection (see onMapPanningListener above); this is the
        // way back in, matching every real turn-by-turn app's own affordance
        // for "you panned away, tap here to resume following". Kept in sync
        // with the initial tracking mode set above.
        //
        // Also restores the 45-degree tilt -- Corey: "I also would like it
        // to always default to that 45 degree close angled view upon
        // starting". A manual pinch/two-finger gesture can tilt the camera
        // away from that (TomTomMap's own built-in gesture handling, not
        // something this file drives), and previously nothing ever set it
        // back -- only the very first navigation-start LaunchedEffect above
        // ever applied it, once. Recenter is the natural place to restore
        // it too, matching how it already restores tracking mode after a
        // manual pan.
        FloatingActionButton(
            onClick = {
                mapViewState.cameraState.trackingMode = CameraTrackingMode.FollowRouteDirection
                coroutineScope.launch { mapViewState.cameraState.animateCamera(CameraOptions(tilt = 45.0)) }
            },
            modifier = Modifier.align(Alignment.BottomEnd).padding(
                bottom = if (remainingTime != null || remainingDistance != null) 112.dp else 16.dp,
                end = 16.dp,
            ),
        ) {
            Icon(Icons.Default.MyLocation, contentDescription = "Recenter")
        }

        // Bottom sheet-style card: rounded top corners only, a drag-handle
        // bar for visual affordance (not actually draggable -- purely
        // cosmetic, matching the reference's look without adding a real
        // expand/collapse feature that isn't needed here), ETA/duration/
        // distance in one row.
        if (remainingTime != null || remainingDistance != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .onGloballyPositioned { bottomCardHeightPx = it.size.height },
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
            ) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(width = 32.dp, height = 4.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(2.dp)),
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(24.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        remainingTime?.let {
                            Text(formatDuration(it), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        }
                        remainingDistance?.let {
                            Text(
                                formatDistance(it),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        remainingTime?.let {
                            Text(
                                "ETA ${formatArrivalTime(it)}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    // No legend here any more -- Corey: "I don't think there
                    // should be a legend at all if we're just sticking with
                    // the 1 blue colour." A single, unambiguous route color
                    // doesn't need explaining.
                }
            }
        }
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
