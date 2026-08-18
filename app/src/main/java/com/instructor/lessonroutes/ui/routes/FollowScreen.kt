package com.instructor.lessonroutes.ui.routes

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.instructor.lessonroutes.data.RouteDao
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.util.LOCATION_PERMISSIONS
import com.instructor.lessonroutes.util.hasLocationPermission
import com.instructor.lessonroutes.util.startLocationUpdates
import org.maplibre.android.geometry.LatLng

/**
 * Step 7: the route's saved polyline stays fixed (camera fit to its bounds), with a
 * live position dot updating on top of it so the instructor can see where they are
 * relative to the planned path. The camera itself doesn't chase the dot — deliberately
 * simple, avoids a jumpy/re-centering camera during a lesson.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FollowScreen(routeId: Long, dao: RouteDao) {
    val context = LocalContext.current
    val routeWithPoints by dao.getRouteWithPoints(routeId).collectAsState(initial = null)

    var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> hasLocationPermission = results.values.any { it } }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    var liveLocation by remember { mutableStateOf<LatLng?>(null) }
    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose {}
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { liveLocation = LatLng(it.latitude, it.longitude) }
            }
        }
        fusedClient.startLocationUpdates(request, callback)
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }

    val current = routeWithPoints
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(current?.route?.name?.let { "Following: $it" } ?: "Follow") })
        },
    ) { padding ->
        if (current == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text("Loading…")
            }
        } else {
            val sortedPoints = current.points.sortedBy { it.sequenceOrder }
            RouteMapView(
                modifier = Modifier.fillMaxSize().padding(padding),
                routePoints = sortedPoints.map { LatLng(it.latitude, it.longitude) },
                waypoints = sortedPoints.filter { it.isWaypoint }.map { LatLng(it.latitude, it.longitude) },
                liveLocation = liveLocation,
                fitBoundsToRoute = true,
            )
        }
    }
}
