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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.BuildConfig
import com.tomtom.sdk.init.TomTomSdk
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

// ---------------------------------------------------------------------------
// THROWAWAY SPIKE -- exists only to answer one question: can TomTom's
// supportingPoints/ReconstructionMode.Route reconstruction actually produce
// good turn-by-turn guidance along a route with several backtracking "petals"
// (RouteGenerator.kt's radius-confined loops), or does it collapse/simplify
// them away the same way Google Maps' waypoint hand-off did? Not wired into
// the real app flow -- reached via a temporary debug button on SettingsScreen.
// Delete this whole package once that question is answered either way.
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

/** A hand-picked, deliberately backtracking test loop -- similar shape to what
 * RouteGenerator.refineCandidateWithinRadius produces for a small radius (a
 * few "petals" around a start point, in different compass directions, which
 * necessarily crosses back near the start between each one). Inland Parramatta
 * area, same location already confirmed live to route fine via Geoapify
 * earlier in this project -- avoids the coastline/harbour "no route" issue a
 * Sydney CBD test point kept hitting. */
private val SPIKE_START = GeoPoint(-33.8151, 151.0011)
private val SPIKE_PETALS = listOf(
    GeoPoint(-33.7439, 151.0011), // ~8km north
    GeoPoint(-33.7794, 151.0703), // ~8km at bearing 60°
    GeoPoint(-33.8508, 151.0703), // ~8km at bearing 120°
    GeoPoint(-33.8863, 151.0011), // ~8km south
    GeoPoint(-33.8508, 150.9319), // ~8km at bearing 240°
    GeoPoint(-33.7794, 150.9319), // ~8km at bearing 300°
)

private sealed interface SpikeState {
    data object Idle : SpikeState
    data object Planning : SpikeState
    data class Planned(val distanceMeters: Double, val durationSeconds: Double) : SpikeState
    data object Navigating : SpikeState
    data class Failed(val message: String) : SpikeState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TomTomNavSpikeScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var state by remember { mutableStateOf<SpikeState>(SpikeState.Idle) }
    var sdkReady by remember { mutableStateOf(false) }

    // Initialize once. Real shape of buildSdkConfiguration()'s params (telemetry
    // consent callback, etc.) is one of the inferred-not-verified spots flagged
    // above -- fix alongside Android Studio if this doesn't match.
    LaunchedEffect(Unit) {
        try {
            TomTomSdk.initialize(
                context.applicationContext,
                TomTomSdk.buildSdkConfiguration(
                    context = context.applicationContext,
                    apiKey = BuildConfig.TOMTOM_API_KEY,
                ),
            )
            sdkReady = true
        } catch (e: Exception) {
            Log.e(LOG_TAG, "SDK init failed", e)
            state = SpikeState.Failed("SDK init failed: ${e.message}")
        }
    }

    fun planAndStart() {
        state = SpikeState.Planning
        val routePlanner = TomTomSdk.createRoutePlanner()
        // Chain: start -> petal1 -> petal2 -> ... -> start again (a loop, no
        // separate destination -- same idea as RouteGenerator.kt's
        // spokePoints(), just hardcoded here instead of generated).
        val legs = (listOf(SPIKE_START) + SPIKE_PETALS + listOf(SPIKE_START))
            .zipWithNext { a, b -> listOf(a, b) }
        val routePlanningOptions = RoutePlanningOptions(
            itinerary = Itinerary(
                origin = SPIKE_START,
                destination = SPIKE_START,
                waypoints = SPIKE_PETALS,
            ),
            routeLegOptions = legs.map { legPoints -> RouteLegOptions(supportingPoints = legPoints) },
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
                    Log.d(LOG_TAG, "Reconstructed route: distance=${route.distance} duration=${route.duration}")
                    state = SpikeState.Planned(route.distance, route.duration.inWholeSeconds.toDouble())
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

    Scaffold(
        topBar = { TopAppBar(title = { Text("TomTom nav spike") }) },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Throwaway test: reconstructs a 6-petal backtracking loop " +
                    "(same shape RouteGenerator.kt produces for a radius-confined " +
                    "trip) via TomTom's supportingPoints/ReconstructionMode.Route, " +
                    "then starts guidance. Watch Logcat, tag \"$LOG_TAG\", for detail.",
            )
            Button(
                onClick = { planAndStart() },
                enabled = sdkReady && state !is SpikeState.Planning && state !is SpikeState.Navigating,
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
                is SpikeState.Planning -> Text("Planning…")
                is SpikeState.Planned -> Text(
                    "Planned: ${"%.1f".format(current.distanceMeters / 1000.0)} km, " +
                        "${(current.durationSeconds / 60.0).toInt()} min. Starting guidance…",
                )
                is SpikeState.Navigating -> Text("Navigating -- check Logcat/guidance UI for turn instructions.")
                is SpikeState.Failed -> Text("Failed: ${current.message}")
            }
            TextButton(onClick = onBack) { Text("Back") }
        }
    }
}
