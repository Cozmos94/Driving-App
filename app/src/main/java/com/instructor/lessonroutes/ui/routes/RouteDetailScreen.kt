package com.instructor.lessonroutes.ui.routes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.data.RouteDao
import com.instructor.lessonroutes.ui.map.RouteMapView
import org.maplibre.android.geometry.LatLng

/**
 * Step 4: shows a route's polyline + notes. "Follow" (step 7) and "Open in nav app"
 * (step 8) actions get added to this screen later — not needed to validate list ->
 * detail navigation, which is this step's actual goal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteDetailScreen(routeId: Long, dao: RouteDao) {
    val routeWithPoints by dao.getRouteWithPoints(routeId).collectAsState(initial = null)
    val current = routeWithPoints

    Scaffold(topBar = { TopAppBar(title = { Text(current?.route?.name ?: "Route") }) }) { padding ->
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…")
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                RouteMapView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    routePoints = current.points
                        .sortedBy { it.sequenceOrder }
                        .map { LatLng(it.latitude, it.longitude) },
                )
                if (!current.route.notes.isNullOrBlank()) {
                    Text(text = current.route.notes, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}
