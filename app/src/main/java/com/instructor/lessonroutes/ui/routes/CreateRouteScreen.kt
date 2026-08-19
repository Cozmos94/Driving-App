package com.instructor.lessonroutes.ui.routes

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.instructor.lessonroutes.data.Route
import com.instructor.lessonroutes.data.RouteDao
import com.instructor.lessonroutes.data.RoutePoint
import com.instructor.lessonroutes.data.StudentProfile
import com.instructor.lessonroutes.data.StudentProfileDao
import com.instructor.lessonroutes.data.remote.fetchRoadSnappedPath
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.util.LOCATION_PERMISSIONS
import com.instructor.lessonroutes.util.hasLocationPermission
import com.instructor.lessonroutes.util.startLocationUpdates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng

private enum class CreateMode { TAP, RECORD }

private data class DraftPoint(
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long?,
    val isWaypoint: Boolean,
)

/**
 * Step 5 + 6 combined (per the spec, they're one screen with two modes): tap-to-place
 * waypoints, or record a live GPS trail with start/pause, then save either as a Route.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateRouteScreen(
    dao: RouteDao,
    profileDao: StudentProfileDao,
    preselectedProfileId: Long? = null,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(CreateMode.TAP) }
    val points = remember { mutableStateListOf<DraftPoint>() }
    var isRecording by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var showClearPointsConfirm by remember { mutableStateOf(false) }

    val allProfiles by profileDao.getAllProfiles().collectAsState(initial = emptyList())
    // Pre-selects whichever student profile the instructor was browsing when they
    // tapped +, since that's almost always who this new route is for -- still
    // editable in the save dialog's checklist.
    var selectedProfileIds by remember { mutableStateOf(setOfNotNull(preselectedProfileId)) }

    var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> hasLocationPermission = results.values.any { it } }

    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    DisposableEffect(isRecording, hasLocationPermission) {
        if (!isRecording || !hasLocationPermission) return@DisposableEffect onDispose {}
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                points.add(DraftPoint(location.latitude, location.longitude, System.currentTimeMillis(), false))
            }
        }
        fusedClient.startLocationUpdates(request, callback)
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }

    // Tap mode: hand-placed points are joined by straight lines by default, which
    // rarely matches the real road -- snap them through OSRM (free, keyless, same
    // "best effort" posture as the Overpass calls elsewhere in this app) so the
    // in-progress preview follows actual roads. Debounced so rapid taps don't fire
    // a request per tap; falls back to the straight-line points on failure or while
    // a fetch is in flight. Record mode is untouched -- a live GPS trail is already
    // dense real-road data and shouldn't be rerouted through a routing engine.
    val tapPreviewPoints = points.map { LatLng(it.latitude, it.longitude) }
    var tapSnappedPath by remember { mutableStateOf<List<LatLng>?>(null) }
    LaunchedEffect(mode, tapPreviewPoints) {
        if (mode != CreateMode.TAP || tapPreviewPoints.size < 2) {
            tapSnappedPath = null
            return@LaunchedEffect
        }
        delay(600)
        tapSnappedPath = try {
            fetchRoadSnappedPath(tapPreviewPoints)
        } catch (e: Exception) {
            Log.e("CreateRouteScreen", "Road-snapping failed for tap preview", e)
            null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Create route") },
                actions = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                ModeButton(
                    label = "Tap",
                    selected = mode == CreateMode.TAP,
                    onClick = { isRecording = false; mode = CreateMode.TAP },
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                ModeButton(
                    label = "Record",
                    selected = mode == CreateMode.RECORD,
                    onClick = { mode = CreateMode.RECORD },
                    modifier = Modifier.weight(1f),
                )
            }

            RouteMapView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                routePoints = if (mode == CreateMode.TAP) {
                    tapSnappedPath ?: tapPreviewPoints
                } else {
                    points.map { LatLng(it.latitude, it.longitude) }
                },
                waypoints = points.filter { it.isWaypoint }.map { LatLng(it.latitude, it.longitude) },
                // This screen already manages its own location permission/updates for
                // recording — letting RouteMapView also run its own permission request
                // could race with this screen's Start-button request.
                centerOnDeviceLocation = false,
                onMapClick = if (mode == CreateMode.TAP) {
                    { latLng -> points.add(DraftPoint(latLng.latitude, latLng.longitude, null, false)) }
                } else {
                    null
                },
            )

            if (mode == CreateMode.RECORD) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    Button(
                        onClick = {
                            if (!hasLocationPermission) {
                                permissionLauncher.launch(LOCATION_PERMISSIONS)
                            } else {
                                isRecording = !isRecording
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (isRecording) "Pause" else "Start")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (points.isNotEmpty()) {
                                val last = points.removeAt(points.lastIndex)
                                points.add(last.copy(isWaypoint = true))
                            }
                        },
                        enabled = points.isNotEmpty(),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Mark waypoint")
                    }
                }
            } else if (points.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    OutlinedButton(
                        onClick = { points.removeAt(points.lastIndex) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Undo")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { showClearPointsConfirm = true },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Clear all")
                    }
                }
                OutlinedButton(
                    onClick = {
                        val last = points.removeAt(points.lastIndex)
                        points.add(last.copy(isWaypoint = true))
                    },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                ) {
                    Text("Mark last point as waypoint")
                }
            }

            Button(
                onClick = { showSaveDialog = true },
                enabled = points.size >= 2,
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            ) {
                Text("Save route (${points.size} points)")
            }
        }
    }

    if (showSaveDialog) {
        SaveRouteDialog(
            allProfiles = allProfiles,
            selectedProfileIds = selectedProfileIds,
            onToggleProfile = { id ->
                selectedProfileIds = if (selectedProfileIds.contains(id)) {
                    selectedProfileIds - id
                } else {
                    selectedProfileIds + id
                }
            },
            onCreateProfile = { name ->
                scope.launch {
                    val id = profileDao.insertProfile(StudentProfile(name = name, dateCreated = System.currentTimeMillis()))
                    selectedProfileIds = selectedProfileIds + id
                }
            },
            onDismiss = { showSaveDialog = false },
            onConfirm = { name, notes ->
                scope.launch {
                    val id = dao.insertRoute(
                        Route(
                            name = name,
                            notes = notes.ifBlank { null },
                            dateCreated = System.currentTimeMillis(),
                        ),
                    )
                    dao.insertPoints(
                        points.mapIndexed { index, point ->
                            RoutePoint(
                                routeId = id,
                                latitude = point.latitude,
                                longitude = point.longitude,
                                sequenceOrder = index,
                                timestamp = point.timestamp,
                                isWaypoint = point.isWaypoint,
                            )
                        },
                    )
                    if (selectedProfileIds.isNotEmpty()) {
                        dao.setProfilesForRoute(id, selectedProfileIds.toList())
                    }
                    showSaveDialog = false
                    onSaved()
                }
            },
        )
    }

    if (showClearPointsConfirm) {
        AlertDialog(
            onDismissRequest = { showClearPointsConfirm = false },
            title = { Text("Clear all points?") },
            text = { Text("This removes all ${points.size} tapped points. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { points.clear(); showClearPointsConfirm = false }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearPointsConfirm = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(label) }
    }
}

@Composable
private fun SaveRouteDialog(
    allProfiles: List<StudentProfile>,
    selectedProfileIds: Set<Long>,
    onToggleProfile: (Long) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (name: String, notes: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Save route") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes (optional)") },
                )
                Spacer(modifier = Modifier.height(8.dp))
                ProfilePickerSection(
                    allProfiles = allProfiles,
                    selectedIds = selectedProfileIds,
                    onToggle = onToggleProfile,
                    onCreateProfile = onCreateProfile,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "Untitled route" }, notes) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
