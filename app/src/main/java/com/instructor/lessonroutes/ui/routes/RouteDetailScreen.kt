package com.instructor.lessonroutes.ui.routes

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.BuildConfig
import com.instructor.lessonroutes.data.RouteDao
import com.instructor.lessonroutes.data.remote.Hazard
import com.instructor.lessonroutes.data.remote.fetchOpenIncidents
import com.instructor.lessonroutes.data.remote.fetchOpenRoadworks
import com.instructor.lessonroutes.data.routegen.GeneratedRoute
import com.instructor.lessonroutes.data.routegen.effectiveFilterSummary
import com.instructor.lessonroutes.ui.map.InfoBanner
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.ui.map.rememberDisplayRoutePoints
import com.instructor.lessonroutes.ui.navigate.TomTomNavigationScreen
import com.instructor.lessonroutes.ui.theme.AvoidRed
import com.instructor.lessonroutes.ui.theme.PreferGreen
import org.maplibre.android.geometry.LatLng

/**
 * Step 4 (list -> detail with polyline) plus step 7/8's "Open in nav app" action
 * (now "Navigate", opening real TomTom turn-by-turn guidance -- see
 * TomTomNavigationScreen.kt -- rather than handing off to Google Maps). The
 * "Follow" button/screen (FollowScreen.kt) was removed from this screen per
 * Corey's request -- FollowScreen.kt and its nav destination are left in place,
 * just unwired, same as CreateRouteScreen.kt's own precedent, in case that flow
 * is ever wanted back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(routeId: Long, dao: RouteDao) {
    val routeWithPoints by dao.getRouteWithPoints(routeId).collectAsState(initial = null)
    val routeWithProfiles by dao.getRouteWithProfiles(routeId).collectAsState(initial = null)
    val current = routeWithPoints
    val displayRoutePoints = rememberDisplayRoutePoints(current?.points ?: emptyList())
    // Swaps the *whole* screen to real TomTom guidance, same early-return
    // pattern GenerateRouteScreen.kt uses for its own "Navigate" button --
    // TomTomNavigationScreen has its own Scaffold/TopAppBar, so this can't be
    // nested inside RouteDetailScreen's own Scaffold below without stacking
    // two top bars.
    var isNavigating by remember { mutableStateOf(false) }
    if (isNavigating) {
        // durationSeconds/distanceMeters are only used for this screen's own
        // diagnostic logging, not for planning itself -- a saved Route has no
        // stored duration/distance to pass here (unlike a freshly generated
        // one), so 0.0 is a harmless placeholder, not a real value used anywhere.
        val route = GeneratedRoute(points = displayRoutePoints, durationSeconds = 0.0, distanceMeters = 0.0)
        TomTomNavigationScreen(route = route, onExit = { isNavigating = false })
        return
    }

    // Phase 2 step 9: live hazards overlay, no caching (fetched fresh each time it's
    // switched on). Silently does nothing if no API key is configured.
    var showHazards by remember { mutableStateOf(false) }
    var hazards by remember { mutableStateOf<List<Hazard>>(emptyList()) }
    var hazardsError by remember { mutableStateOf<String?>(null) }
    var selectedHazard by remember { mutableStateOf<Hazard?>(null) }

    LaunchedEffect(showHazards) {
        if (!showHazards) {
            hazards = emptyList()
            hazardsError = null
            return@LaunchedEffect
        }
        hazardsError = null
        try {
            hazards = fetchOpenIncidents(BuildConfig.TFNSW_API_KEY) + fetchOpenRoadworks(BuildConfig.TFNSW_API_KEY)
        } catch (e: Exception) {
            Log.e("RouteDetailScreen", "Failed to fetch live hazards", e)
            hazardsError = "Couldn't load hazards right now"
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(current?.route?.name ?: "Route") }) }) { padding ->
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…")
            }
        } else {
            val sortedPoints = current.points.sortedBy { it.sequenceOrder }
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    RouteMapView(
                        modifier = Modifier.fillMaxSize(),
                        routePoints = displayRoutePoints,
                        waypoints = sortedPoints.filter { it.isWaypoint }.map { LatLng(it.latitude, it.longitude) },
                        hazards = hazards,
                        onHazardClick = { selectedHazard = it },
                        fitBoundsToRoute = true,
                    )
                    InfoBanner(
                        title = selectedHazard?.title,
                        subtitle = selectedHazard?.advice,
                        onDismiss = { selectedHazard = null },
                        modifier = Modifier.align(Alignment.TopCenter),
                    )
                }
                if (!current.route.description.isNullOrBlank()) {
                    Text(text = current.route.description, modifier = Modifier.padding(16.dp))
                }
                if (!current.route.notes.isNullOrBlank()) {
                    Text(text = current.route.notes, modifier = Modifier.padding(16.dp))
                }
                routeWithProfiles?.profiles?.takeIf { it.isNotEmpty() }?.let { profiles ->
                    Text(
                        text = "For: ${profiles.joinToString(", ") { it.name }}",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                // Only set for routes saved from the trip generator -- empty,
                // and hidden, for tap/recorded routes and for a generated route
                // saved with nothing set to Avoid/Prefer.
                val filterSummary = current.route.effectiveFilterSummary()
                FilterBadgeSection("Avoid", filterSummary.avoid, textColor = AvoidRed)
                FilterBadgeSection("Prefer", filterSummary.prefer, textColor = PreferGreen)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Switch(
                        checked = showHazards,
                        onCheckedChange = { showHazards = it },
                        enabled = BuildConfig.TFNSW_API_KEY.isNotBlank(),
                    )
                    Text(
                        text = hazardsError
                            ?: if (BuildConfig.TFNSW_API_KEY.isBlank()) {
                                "Live hazards (no API key configured)"
                            } else {
                                "Live hazards"
                            },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Was "Open in nav app" (handed off to Google Maps) --
                    // now opens real TomTom turn-by-turn guidance instead,
                    // same as GenerateRouteScreen.kt's "Navigate" button.
                    OutlinedButton(
                        onClick = { isNavigating = true },
                        enabled = displayRoutePoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Navigate")
                    }
                }
            }
        }
    }
}

/** A labeled row of small pill badges (e.g. "Avoid" / Highways, Hazards) --
 * replaces what used to be one plain paragraph sentence. Hidden entirely if
 * [items] is empty, so a route with nothing set to Avoid (or nothing set to
 * Prefer) doesn't leave a blank labeled section behind. [textColor] is
 * [AvoidRed] for the Avoid section and [PreferGreen] for Prefer, per Corey's
 * request -- the pill's own background stays the neutral theme color, just
 * the label text inside it is colored. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBadgeSection(label: String, items: List<String>, textColor: Color) {
    if (items.isEmpty()) return
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            items.forEach { item ->
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = item,
                        color = textColor,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
