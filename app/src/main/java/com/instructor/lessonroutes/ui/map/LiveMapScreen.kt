package com.instructor.lessonroutes.ui.map

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.instructor.lessonroutes.data.HighVolumeRoadDao
import com.instructor.lessonroutes.data.SchoolZone
import com.instructor.lessonroutes.data.SchoolZoneDao
import com.instructor.lessonroutes.data.SpeedCamera
import com.instructor.lessonroutes.data.SpeedCameraDao
import com.instructor.lessonroutes.data.remote.Hazard
import com.instructor.lessonroutes.data.remote.HighVolumeRoad
import com.instructor.lessonroutes.data.remote.fetchOpenIncidents
import com.instructor.lessonroutes.data.remote.fetchOpenRoadworks
import com.instructor.lessonroutes.data.remote.fetchQuietRoads
import com.instructor.lessonroutes.ui.theme.BackgroundWhite
import com.instructor.lessonroutes.ui.theme.BorderNavy
import com.instructor.lessonroutes.ui.theme.SelectedBlue
import com.instructor.lessonroutes.data.remote.matchRoadGeometry
import com.instructor.lessonroutes.util.LOCATION_PERMISSIONS
import com.instructor.lessonroutes.util.hasLocationPermission
import com.instructor.lessonroutes.util.startLocationUpdates
import org.maplibre.android.geometry.LatLng

private const val LOG_TAG = "LiveMapScreen"

/**
 * The app's home screen: a live map that follows the device's position as it moves
 * (Google-Maps-style driving view), with the hazards + high-traffic-volume overlays
 * on by default and the static reference overlays (school zones, speed cameras)
 * always shown too. A button at the bottom moves into route planning.
 */
@Composable
fun LiveMapScreen(
    schoolZoneDao: SchoolZoneDao,
    speedCameraDao: SpeedCameraDao,
    highVolumeRoadDao: HighVolumeRoadDao,
    onPlanRouteClick: () -> Unit,
    onGenerateTripClick: () -> Unit,
) {
    val context = LocalContext.current

    var hazards by remember { mutableStateOf<List<Hazard>>(emptyList()) }
    var highVolumeRoads by remember { mutableStateOf<List<HighVolumeRoad>>(emptyList()) }
    var networkError by remember { mutableStateOf<String?>(null) }
    var schoolZones by remember { mutableStateOf<List<SchoolZone>>(emptyList()) }
    var cameras by remember { mutableStateOf<List<SpeedCamera>>(emptyList()) }
    var quietRoads by remember { mutableStateOf<List<List<LatLng>>>(emptyList()) }

    // One shared top-of-map banner slot for whatever the user last tapped (a hazard,
    // a high-volume road) or a fetch error -- never more than one shown at once.
    var bannerTitle by remember { mutableStateOf<String?>(null) }
    var bannerSubtitle by remember { mutableStateOf<String?>(null) }

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
            networkError = "Couldn't load hazards right now"
        }
    }

    // Was a live TfNSW call on every load -- now reads the bundled/seeded
    // high_volume_roads snapshot from Room instead (see HighVolumeRoadEntity's
    // own doc comment for why), so this no longer needs TFNSW_API_KEY at all
    // and can't fail from a network/API outage. The Overpass road-matching
    // step below is unrelated to that change and still genuinely live/best-
    // effort -- it's what turns each station point into painted road
    // geometry, not something that makes sense to bundle (a matched shape
    // could vary if OSM's own road data changes).
    LaunchedEffect(Unit) {
        try {
            val stations = highVolumeRoadDao.getAll().map {
                HighVolumeRoad(it.stationKey, it.roadName, it.latitude, it.longitude, it.year, it.trafficCount)
            }
            val geometryByIndex = try {
                matchRoadGeometry(stations.map { LatLng(it.latitude, it.longitude) })
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Overpass road-matching failed, falling back to markers", e)
                emptyMap()
            }
            highVolumeRoads = stations.mapIndexed { index, station ->
                station.copy(geometry = geometryByIndex[index])
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to load high-volume roads", e)
            networkError = "Couldn't load traffic volume data right now"
        }
    }

    // Step 11: OSM quiet-road heuristic (no free measured-traffic source exists at
    // street level, per spec). Fetches once around whatever center is available at
    // that moment (a real fix is rarely in yet this early, so this is usually
    // Sydney) rather than waiting for a location fix that might never arrive --
    // a known simplification, not a bug.
    LaunchedEffect(Unit) {
        val center = liveLocation ?: LatLng(-33.8688, 151.2093)
        try {
            quietRoads = fetchQuietRoads(center)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to fetch quiet roads", e)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RouteMapView(
            modifier = Modifier.fillMaxSize(),
            hazards = hazards,
            schoolZones = schoolZones,
            cameras = cameras,
            highVolumeRoads = highVolumeRoads,
            quietRoads = quietRoads,
            liveLocation = liveLocation,
            followLiveLocation = true,
            onHazardClick = { bannerTitle = it.title; bannerSubtitle = it.advice },
            onHighVolumeClick = { bannerTitle = "High Traffic Volume"; bannerSubtitle = null },
            onQuietRoadClick = {
                bannerTitle = "Quiet road (estimate)"
                bannerSubtitle = "Based on road classification, not measured traffic."
            },
            // This screen manages its own continuous location tracking above --
            // RouteMapView's one-shot device-location centering would be redundant
            // and could race with this screen's own permission request.
            centerOnDeviceLocation = false,
        )

        InfoBanner(
            title = bannerTitle ?: networkError,
            subtitle = bannerSubtitle,
            onDismiss = { bannerTitle = null; bannerSubtitle = null; networkError = null },
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Row(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Corey's explicit choice: these two (plus Generate route on the
            // trip-generator screen) get their own look, distinct from the
            // app's general primary/secondary button colors elsewhere. Was a
            // filled #00B4D8/#0096C7 briefly before Corey settled on white
            // with a SelectedBlue border instead.
            val liveMapButtonColors = ButtonDefaults.buttonColors(
                containerColor = BackgroundWhite,
                contentColor = BorderNavy,
            )
            val liveMapButtonBorder = BorderStroke(1.dp, SelectedBlue)
            Button(
                onClick = onPlanRouteClick,
                modifier = Modifier.weight(1f),
                colors = liveMapButtonColors,
                border = liveMapButtonBorder,
            ) {
                Text("Student Profiles")
            }
            Button(
                onClick = onGenerateTripClick,
                modifier = Modifier.weight(1f),
                colors = liveMapButtonColors,
                border = liveMapButtonBorder,
            ) {
                Text("Plan a trip")
            }
        }
    }
}
