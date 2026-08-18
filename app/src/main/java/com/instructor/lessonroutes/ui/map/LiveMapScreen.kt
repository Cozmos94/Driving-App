package com.instructor.lessonroutes.ui.map

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.instructor.lessonroutes.BuildConfig
import com.instructor.lessonroutes.data.SchoolZone
import com.instructor.lessonroutes.data.SchoolZoneDao
import com.instructor.lessonroutes.data.SpeedCamera
import com.instructor.lessonroutes.data.SpeedCameraDao
import com.instructor.lessonroutes.data.remote.Hazard
import com.instructor.lessonroutes.data.remote.fetchOpenIncidents
import com.instructor.lessonroutes.data.remote.fetchOpenRoadworks
import com.instructor.lessonroutes.util.LOCATION_PERMISSIONS
import com.instructor.lessonroutes.util.hasLocationPermission
import com.instructor.lessonroutes.util.startLocationUpdates
import org.maplibre.android.geometry.LatLng

private const val LOG_TAG = "LiveMapScreen"

/**
 * The app's home screen: a live map that follows the device's position as it moves
 * (Google-Maps-style driving view), with the hazards overlay on by default and the
 * static reference overlays (school zones, speed cameras) always shown too. A button
 * at the bottom moves into route planning (the list/create/detail flow).
 */
@Composable
fun LiveMapScreen(
    schoolZoneDao: SchoolZoneDao,
    speedCameraDao: SpeedCameraDao,
    onPlanRouteClick: () -> Unit,
) {
    val context = LocalContext.current

    var hazards by remember { mutableStateOf<List<Hazard>>(emptyList()) }
    var hazardsError by remember { mutableStateOf<String?>(null) }
    var selectedHazard by remember { mutableStateOf<Hazard?>(null) }
    var schoolZones by remember { mutableStateOf<List<SchoolZone>>(emptyList()) }
    var cameras by remember { mutableStateOf<List<SpeedCamera>>(emptyList()) }

    var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> hasLocationPermission = results.values.any { it } }
    var liveLocation by remember { mutableStateOf<LatLng?>(null) }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    // Continuous location tracking so the map can follow along while driving, rather
    // than a one-off fix -- separate from (and instead of) RouteMapView's own
    // one-shot device-location centering, which this screen turns off below.
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

    LaunchedEffect(Unit) {
        schoolZones = schoolZoneDao.getAll()
        cameras = speedCameraDao.getAll()
    }

    LaunchedEffect(Unit) {
        if (BuildConfig.TFNSW_API_KEY.isBlank()) return@LaunchedEffect
        try {
            hazards = fetchOpenIncidents(BuildConfig.TFNSW_API_KEY) + fetchOpenRoadworks(BuildConfig.TFNSW_API_KEY)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to fetch live hazards", e)
            hazardsError = "Couldn't load hazards right now"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RouteMapView(
            modifier = Modifier.fillMaxSize(),
            hazards = hazards,
            schoolZones = schoolZones,
            cameras = cameras,
            liveLocation = liveLocation,
            followLiveLocation = true,
            onHazardClick = { selectedHazard = it },
            // This screen manages its own continuous location tracking above --
            // RouteMapView's one-shot device-location centering would be redundant
            // and could race with this screen's own permission request.
            centerOnDeviceLocation = false,
        )

        HazardInfoBanner(
            hazard = selectedHazard,
            onDismiss = { selectedHazard = null },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        if (selectedHazard == null) {
            hazardsError?.let { message ->
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                    tonalElevation = 4.dp,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(text = message, modifier = Modifier.padding(8.dp))
                }
            }
        }

        Button(
            onClick = onPlanRouteClick,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
        ) {
            Text("Plan a route")
        }
    }
}
