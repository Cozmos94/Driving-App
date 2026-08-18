package com.instructor.lessonroutes.ui.routes

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
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.util.LOCATION_PERMISSIONS
import com.instructor.lessonroutes.util.hasLocationPermission
import com.instructor.lessonroutes.util.startLocationUpdates
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
fun CreateRouteScreen(dao: RouteDao, onSaved: () -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableStateOf(CreateMode.TAP) }
    val points = remember { mutableStateListOf<DraftPoint>() }
    var isRecording by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }

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
                routePoints = points.map { LatLng(it.latitude, it.longitude) },
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
                    showSaveDialog = false
                    onSaved()
                }
            },
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
private fun SaveRouteDialog(onDismiss: () -> Unit, onConfirm: (name: String, notes: String) -> Unit) {
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.ifBlank { "Untitled route" }, notes) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
