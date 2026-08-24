package com.instructor.lessonroutes.ui.navigate

import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.view.View
import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import com.instructor.lessonroutes.data.routegen.GeneratedRoute
import com.tomtom.sdk.init.TomTomSdk
import com.tomtom.sdk.init.createRoutePlanner
import com.tomtom.sdk.location.GeoPoint
import com.tomtom.sdk.navigation.RoutePlan
import com.tomtom.sdk.navigation.ui.NavigationFragment
import com.tomtom.sdk.navigation.ui.NavigationUiOptions
import com.tomtom.sdk.routing.RoutePlanningCallback
import com.tomtom.sdk.routing.RoutePlanningResponse
import com.tomtom.sdk.routing.RoutingFailure
import com.tomtom.sdk.routing.options.Itinerary
import com.tomtom.sdk.routing.options.RouteLegOptions
import com.tomtom.sdk.routing.options.RoutePlanningOptions
import com.tomtom.sdk.routing.options.calculation.ReconstructionMode
import org.maplibre.android.geometry.LatLng

// ---------------------------------------------------------------------------
// Real turn-by-turn guidance for the "Navigate" button on GenerateRouteScreen.kt
// -- replaces the plain custom-map live-tracking placeholder that was there
// before. Confirmed viable first via TomTomNavSpikeScreen.kt: reconstructing a
// real Geoapify-computed polyline through a 6-petal backtracking loop
// (supportingPoints + ReconstructionMode.Route) preserved the route's actual
// distance almost exactly (83718m fetched vs 83869m reconstructed, ~0.2% off)
// rather than collapsing it the way Google Maps' waypoint hand-off did --
// that's the one question this whole TomTom detour existed to answer, and the
// spike answered it live, not just in theory.
//
// Map-only, no voice (Corey's call) -- NavigationUiOptions(isSoundEnabled =
// false) below. The navigation-ui module still pulls in com.tomtom.sdk:tts
// transitively regardless (its own POM depends on it) -- no way around that
// short of not using this module's guidance UI at all, but isSoundEnabled =
// false means it's never actually invoked.
//
// A GeneratedRoute is already just a flat, dense points list (no separate
// waypoints/legs once generated -- RouteGenerator.kt's chained-petal geometry
// is baked into that one list), so unlike the spike (which had real, separate
// petal waypoints to route through) this reconstructs the WHOLE route as a
// single leg -- no network call needed here at all, route.points already IS
// the "already-computed polyline" TomTom's reconstruction wants.
//
// IMPORTANT, read before running: two things here are standard, well-trodden
// patterns (Fragment-in-Compose via AndroidView + FragmentContainerView;
// MainActivity now extends FragmentActivity to host it) that I'm confident
// in. What I *can't* independently confirm is the exact call-ordering
// NavigationFragment itself expects (setTomTomNavigation then startNavigation
// right after the fragment's created, vs needing to wait for its view to
// actually be attached first) -- the Dokka reference lists these as plain
// public methods with no stated ordering restriction, but that's not the same
// as having run it. If this doesn't work first try, send me whatever Logcat
// shows (tag below) rather than a re-guess -- there's a decent chance it's
// just a call-ordering fix, not a wrong-approach one, given the spike already
// proved the underlying reconstruction mechanism works.
// ---------------------------------------------------------------------------

private const val LOG_TAG = "TomTomNavigationScreen"

private fun LatLng.toGeoPoint() = GeoPoint(latitude, longitude)

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TomTomNavigationScreen(route: GeneratedRoute, onExit: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }

    var planningError by remember { mutableStateOf<String?>(null) }
    var routePlan by remember { mutableStateOf<RoutePlan?>(null) }
    var navigationFragment by remember { mutableStateOf<NavigationFragment?>(null) }
    var navigationStarted by remember { mutableStateOf(false) }

    // Reconstructs the already-generated route via TomTom, same shape
    // confirmed live by the spike (see file comment above) -- one leg,
    // supportingPoints = the route's own dense point list, no separate
    // waypoints. No Geoapify call needed here: route.points already IS the
    // dense polyline the spike had to fetch specially.
    LaunchedEffect(route) {
        try {
            TomTomSdkInit.ensureInitialized(context)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "SDK init failed", e)
            planningError = "Couldn't start navigation: SDK init failed (${e.message})"
            return@LaunchedEffect
        }
        val supportingPoints = route.points.map { it.toGeoPoint() }
        if (supportingPoints.size < 2) {
            planningError = "This route doesn't have enough points to navigate."
            return@LaunchedEffect
        }
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

    // Fires once both the embedded fragment and the reconstructed plan are
    // ready, in whichever order they actually arrive (planning is a network
    // call, fragment creation is a view-lifecycle callback -- no fixed order
    // between them). navigationStarted guards against calling startNavigation
    // twice on an unrelated recomposition.
    LaunchedEffect(navigationFragment, routePlan) {
        val fragment = navigationFragment
        val plan = routePlan
        if (fragment != null && plan != null && !navigationStarted) {
            fragment.setTomTomNavigation(TomTomSdk.navigation)
            fragment.startNavigation(plan)
            navigationStarted = true
        }
    }

    // Keyed on Unit, NOT navigationFragment -- onDispose still reads whatever
    // navigationFragment's latest value is at actual teardown time (it's a
    // live state read, not a captured snapshot), but keying on Unit means
    // this only fires once, on this whole composable leaving composition.
    // Keying on navigationFragment directly would have disposed-and-recreated
    // this effect the moment the fragment first appeared (null -> real
    // instance is itself a key change), calling stopNavigation() on a
    // fragment that had just barely started navigating.
    DisposableEffect(Unit) {
        onDispose {
            try {
                // Prefer the fragment's own stopNavigation() (it owns the
                // guidance UI's teardown too, not just the underlying engine)
                // -- fall back to the raw TomTomNavigation singleton only if
                // exiting happened before a fragment ever existed (e.g. left
                // during planning/reconstruction), in which case there's
                // nothing for a fragment-level stop to do anyway.
                navigationFragment?.stopNavigation() ?: TomTomSdk.navigation.stop()
            } catch (e: Exception) {
                // Most likely just means navigation never actually started --
                // not worth surfacing to the instructor, just noting it happened.
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
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                activity == null ->
                    Text("Couldn't start navigation: no activity to host it.", modifier = Modifier.padding(16.dp))
                planningError != null ->
                    Text(planningError.orEmpty(), modifier = Modifier.padding(16.dp))
                routePlan == null ->
                    Text("Preparing turn-by-turn guidance…", modifier = Modifier.padding(16.dp))
                else ->
                    EmbeddedNavigationFragment(
                        activity = activity,
                        modifier = Modifier.fillMaxSize(),
                        onFragmentReady = { navigationFragment = it },
                    )
            }
        }
    }
}

/** Hosts a real [NavigationFragment] inside Compose via the standard
 * AndroidView + FragmentContainerView interop pattern. [activity]'s
 * FragmentManager owns the fragment's lifecycle, not this composable directly
 * -- `update` re-checks `findFragmentById` on every recomposition rather than
 * gating on a `remember`, so a fragment that already exists in this container
 * (e.g. surviving a config change) isn't recreated. */
@Composable
private fun EmbeddedNavigationFragment(
    activity: FragmentActivity,
    modifier: Modifier = Modifier,
    onFragmentReady: (NavigationFragment) -> Unit,
) {
    val containerId = remember { View.generateViewId() }
    var fragment by remember { mutableStateOf<NavigationFragment?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
        update = {
            val fragmentManager = activity.supportFragmentManager
            val existing = fragmentManager.findFragmentById(containerId) as? NavigationFragment
            if (existing != null) {
                if (fragment !== existing) fragment = existing
                return@AndroidView
            }
            val created = NavigationFragment.newInstance(NavigationUiOptions(isSoundEnabled = false))
            // commitNowAllowingStateLoss (synchronous), not the async
            // fragment-ktx `commit {}` helper -- callers of this composable
            // need `fragment` to actually be attached once this returns, not
            // attached on some later main-thread loop iteration.
            fragmentManager.beginTransaction()
                .replace(containerId, created)
                .commitNowAllowingStateLoss()
            fragment = created
        },
    )

    DisposableEffect(containerId) {
        onDispose {
            val existing = activity.supportFragmentManager.findFragmentById(containerId)
            if (existing != null) {
                activity.supportFragmentManager.beginTransaction()
                    .remove(existing)
                    .commitNowAllowingStateLoss()
            }
        }
    }

    LaunchedEffect(fragment) {
        fragment?.let(onFragmentReady)
    }
}
