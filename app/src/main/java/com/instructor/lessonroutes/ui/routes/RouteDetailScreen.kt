package com.instructor.lessonroutes.ui.routes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.data.RouteDao
import com.instructor.lessonroutes.ui.map.RouteMapView
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
            val sortedPoints = current.points.sortedBy { it.sequenceOrder }
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                RouteMapView(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    routePoints = sortedPoints.map { LatLng(it.latitude, it.longitude) },
                    waypoints = sortedPoints.filter { it.isWaypoint }.map { LatLng(it.latitude, it.longitude) },
                    fitBoundsToRoute = true,
                )
                if (!current.route.notes.isNullOrBlank()) {
                    Text(text = current.route.notes, modifier = Modifier.padding(16.dp))
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
                            sortedPoints.firstOrNull()?.let {
                                openInNavApp(context, it.latitude, it.longitude, current.route.name)
                            }
                        },
                        enabled = sortedPoints.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Open in nav app")
                    }
                }
            }
        }
    }
}

/** No SDK, no cost — just hands off to whatever nav app the user already has. */
private fun openInNavApp(context: Context, latitude: Double, longitude: Double, label: String) {
    val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude(${Uri.encode(label)})")
    val intent = Intent(Intent.ACTION_VIEW, uri)
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}
