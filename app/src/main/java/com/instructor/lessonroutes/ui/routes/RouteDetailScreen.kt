package com.instructor.lessonroutes.ui.routes

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.BuildConfig
import com.instructor.lessonroutes.data.RouteDao
import com.instructor.lessonroutes.data.remote.Hazard
import com.instructor.lessonroutes.data.remote.fetchOpenIncidents
import com.instructor.lessonroutes.data.remote.fetchOpenRoadworks
import com.instructor.lessonroutes.data.routegen.toFilterList
import com.instructor.lessonroutes.ui.map.InfoBanner
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.ui.map.rememberDisplayRoutePoints
import org.maplibre.android.geometry.LatLng

/**
 * Step 4 (list -> detail with polyline) plus step 7/8's "Follow" and "Open in nav app"
 * actions, since they're cheap additions to a screen that already exists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(routeId: Long, dao: RouteDao, onFollowClick: (Long) -> Unit) {
    val context = LocalContext.current
    val routeWithPoints by dao.getRouteWithPoints(routeId).collectAsState(initial = null)
    val routeWithProfiles by dao.getRouteWithProfiles(routeId).collectAsState(initial = null)
    val current = routeWithPoints

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
            val displayRoutePoints = rememberDisplayRoutePoints(current.points)
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
                FilterBadgeSection("Avoid", current.route.avoidFilters.toFilterList())
                FilterBadgeSection("Prefer", current.route.preferFilters.toFilterList())
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
                    Button(onClick = { onFollowClick(routeId) }, modifier = Modifier.weight(1f)) {
                        Text("Follow")
                    }
                    OutlinedButton(
                        onClick = {
                            if (displayRoutePoints.isNotEmpty()) {
                                openInNavApp(context, displayRoutePoints)
                            }
                        },
                        enabled = displayRoutePoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open in nav app")
                    }
                }
            }
        }
    }
}

/** A labeled row of small pill badges (e.g. "Avoid" / Highways, Hazards) --
 * replaces what used to be one plain paragraph sentence. Hidden entirely if
 * [items] is empty, so a route with nothing set to Avoid (or nothing set to
 * Prefer) doesn't leave a blank labeled section behind. */
@Composable
private fun FilterBadgeSection(label: String, items: List<String>) {
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
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}
