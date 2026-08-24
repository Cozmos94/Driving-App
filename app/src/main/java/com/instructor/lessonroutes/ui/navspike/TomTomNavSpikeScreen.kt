package com.instructor.lessonroutes.ui.navspike

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.BuildConfig
import com.instructor.lessonroutes.data.remote.fetchRoutedPaths
import com.tomtom.sdk.common.configuration.buildSdkConfiguration
import com.tomtom.sdk.init.TomTomSdk
import com.tomtom.sdk.init.createRoutePlanner
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.navigation.NavigationOptions
import com.tomtom.sdk.navigation.RoutePlan
import com.tomtom.sdk.routing.RoutePlanningCallback
import com.tomtom.sdk.routing.RoutePlanningResponse
import com.tomtom.sdk.routing.RoutingFailure
import com.tomtom.sdk.routing.options.Itinerary
import com.tomtom.sdk.routing.options.RouteLegOptions
import com.tomtom.sdk.routing.options.RoutePlanningOptions
import com.tomtom.sdk.routing.options.calculation.ReconstructionMode
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

// ---------------------------------------------------------------------------
// THROWAWAY SPIKE -- exists only to answer one question: can TomTom's
// supportingPoints/ReconstructionMode.Route reconstruction actually produce
// good turn-by-turn guidance along a route with several backtracking "petals"
// (RouteGenerator.kt's radius-confined loops), or does it collapse/simplify
// them away the same way Google Maps' waypoint hand-off did? Not wired into
// the real app flow -- reached via a temporary debug button on SettingsScreen.
// Delete this whole package once that question is answered either way.
//
// SECOND ATTEMPT, read before running: the first version of this spike failed
// live with "CANNOT_RESTORE_BASEROUTE: route search failed between origin and
// waypoint 1" -- root cause, confirmed by re-reading what was actually sent:
// each leg's `supportingPoints` was just the two bare petal endpoints
// (`listOf(a, b)`), not an actual routed polyline. TomTom's reconstruction
// needs real road geometry to snap to; two sparse points ~8km apart give it
// nothing to reconstruct *from*, so the engine's own route search between
// them (which is exactly what reconstruction is supposed to skip) failed
// like a normal routing request would. Fixed by fetching a real dense
// Geoapify-computed polyline for the whole loop first (the same shape
// RouteGenerator.kt's GeneratedRoute.points already is for a real generated
// route) and feeding *that* in as supportingPoints -- and, since a dense
// point list needs no separate "waypoints" to define legs between, collapsed
// the itinerary down to a single leg covering the whole loop instead of one
// artificial leg per petal. This is also the exact shape the real "Navigate"
// button integration will need (a GeneratedRoute IS already just a flat
// points list) -- if this works, wiring it into GenerateRouteScreen.kt is a
// comparatively small step from here.
//
// IMPORTANT, read before running: several of the imports above (everything
// under com.tomtom.sdk.*) are my best inference from TomTom's docs, not
// something I could compile/verify myself. When you open this file in
// Android Studio, anything shown as an unresolved reference almost certainly
// just needs Alt+Enter -> "Import" to pick the real class from the actual SDK
// jar now that it's synced -- that's expected, not a sign the approach itself
// is wrong. Send me whatever's still red after that and I'll fix it properly.
// ---------------------------------------------------------------------------

private const val LOG_TAG = "TomTomNavSpike"

// TomTomSdk.initialize() is a real process-wide singleton init, not an
// idempotent no-op -- calling it a second time throws "TomTomSdk is already
// initialized" instead of just succeeding again. LaunchedEffect(Unit) below
// re-runs every time this screen re-enters composition (back out via
// Settings then back in again, or a config change like rotation recreating
// the Activity) even though the SDK itself, once initialized for the
// process, stays initialized regardless -- so a second entry doesn't need
// (and can't survive) another initialize() call. This file-level flag
// remembers that across this screen's own composition lifecycle; the catch
// block below is a second line of defense for the same "already
// initialized" case (e.g. if this flag's own state was somehow lost, such as
// process death + restore) so it's treated as "already ready" rather than a
// real failure either way.
private var sdkInitializedThisProcess = false

/** A hand-picked, deliberately backtracking test loop -- similar shape to what
 * RouteGenerator.refineCandidateWithinRadius produces for a small radius (a
 * few "petals" around a start point, in different compass directions, which
 * necessarily crosses back near the start between each one). Inland Parramatta
 * area, same location already confirmed live to route fine via Geoapify
 * earlier in this project -- avoids the coastline/harbour "no route" issue a
 * Sydney CBD test point kept hitting. LatLng (not GeoPoint) so these can be
 * handed straight to Geoapify's fetchRoutedPaths -- converted to GeoPoint only
 * where TomTom's API actually needs it, further down. */
private val SPIKE_START = LatLng(-33.8151, 151.0011)
private val SPIKE_PETALS = listOf(
    LatLng(-33.7439, 151.0011), // ~8km north
    LatLng(-33.7794, 151.0703), // ~8km at bearing 60°
    LatLng(-33.8508, 151.0703), // ~8km at bearing 120°
    LatLng(-33.8863, 151.0011), // ~8km south
    LatLng(-33.8508, 150.9319), // ~8km at bearing 240°
    LatLng(-33.7794, 150.9319), // ~8km at bearing 300°
)

private fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)

private sealed interface SpikeState {
    data object Idle : SpikeState
    data object FetchingRoute : SpikeState
    data object Planning : SpikeState
    // Displayed via Summary.length/travelTime's own toString() rather than
    // pulling out raw meters/seconds -- confirmed via Dokka that these are
    // route.summary.length (Distance) / route.summary.travelTime (Duration),
    // not route.distance/route.duration directly, but the exact
    // meters/seconds accessor on those two value types wasn't worth chasing
    // further for a spike's display text.
    data class Planned(val length: String, val travelTime: String) : SpikeState
    data object Navigating : SpikeState
    data class Failed(val message: String) : SpikeState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TomTomNavSpikeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SpikeState>(SpikeState.Idle) }
    var sdkReady by remember { mutableStateOf(false) }

    // Initialize once. buildSdkConfiguration is a top-level function (not a
    // TomTomSdk member as first guessed), confirmed via the real Dokka API
    // reference (com.tomtom.sdk.common.configuration) -- the overload used
    // here needs only context/apiKey, no telemetry-consent callback required.
    LaunchedEffect(Unit) {
        if (sdkInitializedThisProcess) {
            sdkReady = true
            return@LaunchedEffect
        }
        try {
            TomTomSdk.initialize(
                context.applicationContext,
                buildSdkConfiguration(
                    context = context.applicationContext,
                    apiKey = BuildConfig.TOMTOM_API_KEY,
                ),
            )
            sdkInitializedThisProcess = true
            sdkReady = true
        } catch (e: Exception) {
            if (e.message?.contains("already initialized", ignoreCase = true) == true) {
                // Actually initialized by an earlier entry into this screen
                // before this flag existed/took effect -- it IS ready, so
                // treat it as such instead of surfacing a scary "Failed".
                Log.w(LOG_TAG, "SDK already initialized elsewhere -- treating as ready")
                sdkInitializedThisProcess = true
                sdkReady = true
            } else {
                Log.e(LOG_TAG, "SDK init failed", e)
                state = SpikeState.Failed("SDK init failed: ${e.message}")
            }
        }
    }

    fun planAndStart() {
        scope.launch {
            state = SpikeState.FetchingRoute
            // Real dense road-following polyline for the whole loop, fetched
            // from Geoapify exactly the way RouteGenerator.kt's
            // refineCandidateWithinRadius does for a real generated route --
            // this IS "an already-computed polyline" in the sense TomTom's
            // supportingPoints is for, unlike the first version of this
            // spike (see the file-level comment above for why that failed).
            val chain = listOf(SPIKE_START) + SPIKE_PETALS + listOf(SPIKE_START)
            val routedPath = try {
                fetchRoutedPaths(chain).first()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Fetching the real polyline to reconstruct failed", e)
                state = SpikeState.Failed("Fetching route to reconstruct failed: ${e.message}")
                return@launch
            }
            val supportingPoints = routedPath.points.map { it.toGeoPoint() }
            Log.d(LOG_TAG, "Fetched real polyline: ${supportingPoints.size} points, ${routedPath.distanceMeters}m, ${routedPath.durationSeconds}s")

            state = SpikeState.Planning
            val routePlanner = TomTomSdk.createRoutePlanner()
            val routePlanningOptions = RoutePlanningOptions(
                itinerary = Itinerary(
                    origin = supportingPoints.first(),
                    destination = supportingPoints.last(),
                    // No separate waypoints -- the dense supportingPoints
                    // below already describes the entire backtracking loop,
                    // so there's nothing left for a waypoint split to add;
                    // one leg is enough regardless of how many petals the
                    // route this came from actually had.
                    waypoints = emptyList(),
                ),
                routeLegOptions = listOf(RouteLegOptions(supportingPoints = supportingPoints)),
                // Route (not Track): our supporting points come from a real
                // routing service (Geoapify) already, not a noisy GPS trace --
                // see RouteGenerator.kt's generateCandidateRoutes doc comment.
                reconstructionMode = ReconstructionMode.Route,
            )
            routePlanner.planRoute(
                routePlanningOptions,
                object : RoutePlanningCallback {
                    override fun onSuccess(result: RoutePlanningResponse) {
                        val route = result.routes.firstOrNull()
                        if (route == null) {
                            state = SpikeState.Failed("Planning succeeded but returned no routes")
                            return
                        }
                        Log.d(LOG_TAG, "Reconstructed route: length=${route.summary.length} travelTime=${route.summary.travelTime}")
                        state = SpikeState.Planned(route.summary.length.toString(), route.summary.travelTime.toString())
                        try {
                            val routePlan = RoutePlan(route = route, routePlanningOptions = routePlanningOptions)
                            TomTomSdk.navigation.start(NavigationOptions(routePlan))
                            state = SpikeState.Navigating
                        } catch (e: Exception) {
                            Log.e(LOG_TAG, "Starting navigation failed", e)
                            state = SpikeState.Failed("Navigation start failed: ${e.message}")
                        }
                    }

                    override fun onFailure(failure: RoutingFailure) {
                        Log.e(LOG_TAG, "Route reconstruction failed: $failure")
                        state = SpikeState.Failed("Reconstruction failed: $failure")
                    }
                },
            )
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("TomTom nav spike") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Throwaway test: fetches a real 6-petal backtracking loop route " +
                    "from Geoapify (same shape RouteGenerator.kt produces for a " +
                    "radius-confined trip), then reconstructs it via TomTom's " +
                    "supportingPoints/ReconstructionMode.Route and starts guidance. " +
                    "Watch Logcat, tag \"$LOG_TAG\", for detail.",
            )
            Button(
                onClick = { planAndStart() },
                enabled = sdkReady && state !is SpikeState.FetchingRoute && state !is SpikeState.Planning && state !is SpikeState.Navigating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (!sdkReady) "Initializing SDK…" else "Plan & start (spike)")
            }
            OutlinedButton(
                onClick = {
                    TomTomSdk.navigation.stop()
                    state = SpikeState.Idle
                },
                enabled = state is SpikeState.Navigating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Stop")
            }
            when (val current = state) {
                is SpikeState.Idle -> Text("Idle.")
                is SpikeState.FetchingRoute -> Text("Fetching real route from Geoapify…")
                is SpikeState.Planning -> Text("Reconstructing via TomTom…")
                is SpikeState.Planned -> Text(
                    "Planned: ${current.length}, ${current.travelTime}. Starting guidance…",
                )
                is SpikeState.Navigating -> Text("Navigating -- check Logcat/guidance UI for turn instructions.")
                is SpikeState.Failed -> Text("Failed: ${current.message}")
            }
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}
