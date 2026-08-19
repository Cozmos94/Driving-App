package com.instructor.lessonroutes.ui.generate

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.instructor.lessonroutes.data.Route
import com.instructor.lessonroutes.data.RouteDao
import com.instructor.lessonroutes.data.RoutePoint
import com.instructor.lessonroutes.data.SchoolZoneDao
import com.instructor.lessonroutes.data.SpeedCameraDao
import com.instructor.lessonroutes.data.StudentProfile
import com.instructor.lessonroutes.data.StudentProfileDao
import com.instructor.lessonroutes.data.remote.GeocodeResult
import com.instructor.lessonroutes.data.remote.fetchMajorRoads
import com.instructor.lessonroutes.data.remote.fetchMergeLaneProxies
import com.instructor.lessonroutes.data.remote.fetchOpenIncidents
import com.instructor.lessonroutes.data.remote.fetchOpenRoadworks
import com.instructor.lessonroutes.data.remote.fetchRoundabouts
import com.instructor.lessonroutes.data.remote.searchAddress
import com.instructor.lessonroutes.data.routegen.FilterPreference
import com.instructor.lessonroutes.data.routegen.GeneratedRoute
import com.instructor.lessonroutes.data.routegen.RouteGenerationFilters
import com.instructor.lessonroutes.data.routegen.ScoringData
import com.instructor.lessonroutes.data.routegen.estimateSearchRadiusDegrees
import com.instructor.lessonroutes.data.routegen.generateRoute
import com.instructor.lessonroutes.data.routegen.midpoint
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.ui.routes.ProfilePickerSection
import com.instructor.lessonroutes.ui.routes.openInNavApp
import com.instructor.lessonroutes.util.LOCATION_PERMISSIONS
import com.instructor.lessonroutes.util.hasLocationPermission
import com.instructor.lessonroutes.util.startLocationUpdates
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val LOG_TAG = "GenerateRouteScreen"

/**
 * Plans a route to actually go drive, rather than record/tap one by hand: pick a
 * destination (loop back to the start, tap the map, or search an address), a
 * start/end time (used only to compute a target duration -- generation happens
 * immediately, this doesn't wait or schedule anything), and avoid/prefer filters
 * across hazards/construction/school zones/cameras/highways/roundabouts/merging
 * lanes. See RouteGenerator.kt for how generation and filter scoring actually
 * work, and its doc comments (plus OverpassApi.kt's) for exactly which filters
 * are real routing constraints versus best-effort proximity scoring.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenerateRouteScreen(
    dao: RouteDao,
    profileDao: StudentProfileDao,
    schoolZoneDao: SchoolZoneDao,
    speedCameraDao: SpeedCameraDao,
    preselectedProfileId: Long?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // -- Start location (current device position) --
    var hasLocationPermission by remember { mutableStateOf(context.hasLocationPermission()) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results -> hasLocationPermission = results.values.any { it } }
    var currentLocation by remember { mutableStateOf<LatLng?>(null) }

    LaunchedEffect(Unit) {
        if (!hasLocationPermission) permissionLauncher.launch(LOCATION_PERMISSIONS)
    }

    val fusedClient = remember { LocationServices.getFusedLocationProviderClient(context) }
    DisposableEffect(hasLocationPermission) {
        if (!hasLocationPermission) return@DisposableEffect onDispose {}
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { currentLocation = LatLng(it.latitude, it.longitude) }
            }
        }
        fusedClient.startLocationUpdates(request, callback)
        onDispose { fusedClient.removeLocationUpdates(callback) }
    }

    // -- Destination --
    var loopBackToStart by remember { mutableStateOf(true) }
    var destination by remember { mutableStateOf<LatLng?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }

    val effectiveDestination = if (loopBackToStart) currentLocation else destination

    // -- Start/end time (only used to compute a target duration) --
    var startTime by remember { mutableStateOf<LocalTime?>(null) }
    var endTime by remember { mutableStateOf<LocalTime?>(null) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    val targetDurationMinutes = remember(startTime, endTime) {
        val start = startTime
        val end = endTime
        if (start != null && end != null && end.isAfter(start)) {
            Duration.between(start, end).toMinutes().toInt()
        } else {
            null
        }
    }

    // -- Filters --
    var filters by remember { mutableStateOf(RouteGenerationFilters()) }

    // -- Generation state --
    var isGenerating by remember { mutableStateOf(false) }
    var generatedRoute by remember { mutableStateOf<GeneratedRoute?>(null) }
    var generationError by remember { mutableStateOf<String?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveComplete by remember { mutableStateOf(false) }

    val canGenerate = currentLocation != null && effectiveDestination != null && targetDurationMinutes != null && targetDurationMinutes > 0

    fun onGenerateClick() {
        val start = currentLocation ?: return
        val end = effectiveDestination ?: return
        val minutes = targetDurationMinutes ?: return
        generatedRoute = null
        generationError = null
        isGenerating = true
        scope.launch {
            try {
                val center = midpoint(start, end)
                val radiusDegrees = estimateSearchRadiusDegrees(minutes)
                val scoringData = buildScoringData(filters, center, radiusDegrees, schoolZoneDao, speedCameraDao)
                val result = generateRoute(start, end, minutes, filters, scoringData)
                if (result == null) {
                    generationError = "Couldn't generate a route right now — check your connection and try again."
                } else {
                    generatedRoute = result
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Route generation failed", e)
                generationError = "Couldn't generate a route right now — check your connection and try again."
            } finally {
                isGenerating = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Plan a trip") },
                actions = { TextButton(onClick = onBack) { Text("Close") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                RouteMapView(
                    modifier = Modifier.fillMaxSize(),
                    routePoints = generatedRoute?.points ?: emptyList(),
                    // Looping back to start's destination is the current location,
                    // already shown as the live-location dot -- a second marker on
                    // top of it would just be a confusing duplicate.
                    waypoints = if (loopBackToStart) emptyList() else listOfNotNull(destination),
                    liveLocation = currentLocation,
                    fitBoundsToRoute = generatedRoute != null,
                    onMapClick = if (!loopBackToStart) {
                        { latLng -> destination = latLng; searchResults = emptyList() }
                    } else {
                        null
                    },
                )
            }

            Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Destination")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = loopBackToStart, onCheckedChange = { loopBackToStart = it })
                    Text("Loop back to where I start")
                }
                if (!loopBackToStart) {
                    Text(
                        if (destination != null) "Destination set — tap the map to change it, or search below." else "Tap the map to set a destination, or search below.",
                    )
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            label = { Text("Search an address") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    isSearching = true
                                    searchError = null
                                    try {
                                        searchResults = searchAddress(searchQuery)
                                        if (searchResults.isEmpty()) searchError = "No matches found"
                                    } catch (e: Exception) {
                                        Log.e(LOG_TAG, "Address search failed", e)
                                        searchError = "Couldn't search right now"
                                    } finally {
                                        isSearching = false
                                    }
                                }
                            },
                            enabled = searchQuery.isNotBlank() && !isSearching,
                        ) {
                            Text("Search")
                        }
                    }
                    searchError?.let { Text(it) }
                    searchResults.forEach { result ->
                        ListItem(
                            headlineContent = { Text(result.label) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            onClick = {
                                destination = result.location
                                searchResults = emptyList()
                                searchQuery = result.label
                            },
                        ) { Text("Use this address") }
                        HorizontalDivider()
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Trip time")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showStartTimePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(startTime?.let { formatTime(it) } ?: "Start time")
                    }
                    OutlinedButton(onClick = { showEndTimePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(endTime?.let { formatTime(it) } ?: "End time")
                    }
                }
                Text(
                    text = targetDurationMinutes?.let { "Duration: ${it / 60}h ${it % 60}m" }
                        ?: if (startTime != null && endTime != null) "End time must be after start time" else "Pick a start and end time",
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("Filters")
                if (BuildConfig.TFNSW_API_KEY.isBlank()) {
                    Text("Hazards/construction filters need a Transport for NSW API key (see Settings) — they'll have no effect without one.")
                }
                FilterRow("Hazards", filters.incidents) { filters = filters.copy(incidents = it) }
                FilterRow("Construction zones", filters.constructionZones) { filters = filters.copy(constructionZones = it) }
                FilterRow("School zones", filters.schoolZones) { filters = filters.copy(schoolZones = it) }
                FilterRow("Speed cameras", filters.speedCameras) { filters = filters.copy(speedCameras = it) }
                FilterRow("Highways", filters.highways) { filters = filters.copy(highways = it) }
                FilterRow("Roundabouts", filters.roundabouts) { filters = filters.copy(roundabouts = it) }
                FilterRow("Merging lanes", filters.mergingLanes) { filters = filters.copy(mergingLanes = it) }
                Text(
                    "\"Avoid\" is a hard routing constraint only for Highways — everything else (and every " +
                        "\"Prefer\") is best-effort: a few candidate routes are generated and the one that " +
                        "best matches your filters is picked, not a guarantee.",
                )

                Spacer(modifier = Modifier.height(12.dp))
                if (currentLocation == null) {
                    Text("Waiting for your location — check location permission is granted.")
                }
                Button(
                    onClick = { onGenerateClick() },
                    enabled = canGenerate && !isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isGenerating) "Generating…" else "Generate route")
                }
                if (isGenerating) {
                    Box(modifier = Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                generationError?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }

                generatedRoute?.let { route ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Generated: ${formatDuration(route.durationSeconds)}, ${formatDistance(route.distanceMeters)}")
                    if (saveComplete) {
                        Text("Saved.")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onGenerateClick() }, modifier = Modifier.weight(1f), enabled = !isGenerating) {
                            Text("Regenerate")
                        }
                        OutlinedButton(
                            onClick = { openInNavApp(context, route.points) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Open in nav app")
                        }
                        Button(onClick = { showSaveDialog = true }, modifier = Modifier.weight(1f)) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }

    if (showStartTimePicker) {
        AppTimePickerDialog(
            initial = startTime ?: LocalTime.of(9, 0),
            onDismiss = { showStartTimePicker = false },
            onConfirm = { startTime = it; showStartTimePicker = false },
        )
    }
    if (showEndTimePicker) {
        AppTimePickerDialog(
            initial = endTime ?: LocalTime.of(10, 0),
            onDismiss = { showEndTimePicker = false },
            onConfirm = { endTime = it; showEndTimePicker = false },
        )
    }

    if (showSaveDialog) {
        val allProfiles by profileDao.getAllProfiles().collectAsState(initial = emptyList())
        var selectedProfileIds by remember { mutableStateOf(setOfNotNull(preselectedProfileId)) }
        SaveGeneratedRouteDialog(
            allProfiles = allProfiles,
            selectedProfileIds = selectedProfileIds,
            onToggleProfile = { id ->
                selectedProfileIds = if (selectedProfileIds.contains(id)) selectedProfileIds - id else selectedProfileIds + id
            },
            onCreateProfile = { name ->
                scope.launch {
                    val id = profileDao.insertProfile(StudentProfile(name = name, dateCreated = System.currentTimeMillis()))
                    selectedProfileIds = selectedProfileIds + id
                }
            },
            onDismiss = { showSaveDialog = false },
            onConfirm = { name, notes ->
                val route = generatedRoute ?: return@SaveGeneratedRouteDialog
                scope.launch {
                    val id = dao.insertRoute(
                        Route(name = name, notes = notes.ifBlank { null }, dateCreated = System.currentTimeMillis()),
                    )
                    dao.insertPoints(
                        route.points.mapIndexed { index, point ->
                            RoutePoint(
                                routeId = id,
                                latitude = point.latitude,
                                longitude = point.longitude,
                                sequenceOrder = index,
                                timestamp = null,
                                isWaypoint = false,
                            )
                        },
                    )
                    if (selectedProfileIds.isNotEmpty()) {
                        dao.setProfilesForRoute(id, selectedProfileIds.toList())
                    }
                    showSaveDialog = false
                    saveComplete = true
                    onSaved()
                }
            },
        )
    }
}

@Composable
private fun FilterRow(label: String, preference: FilterPreference, onChange: (FilterPreference) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, modifier = Modifier.weight(1f))
        FilterChip(
            selected = preference == FilterPreference.AVOID,
            onClick = { onChange(if (preference == FilterPreference.AVOID) FilterPreference.NONE else FilterPreference.AVOID) },
            label = { Text("Avoid") },
            modifier = Modifier.padding(end = 4.dp),
        )
        FilterChip(
            selected = preference == FilterPreference.PREFER,
            onClick = { onChange(if (preference == FilterPreference.PREFER) FilterPreference.NONE else FilterPreference.PREFER) },
            label = { Text("Prefer") },
        )
    }
}

@Composable
private fun AppTimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onConfirm(LocalTime.of(state.hour, state.minute)) }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SaveGeneratedRouteDialog(
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
            TextButton(onClick = { onConfirm(name.ifBlank { "Generated route" }, notes) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Fetches point-of-interest data only for the categories actually set to
 * AVOID/PREFER in [filters] -- fetching the rest would be wasted network calls. */
private suspend fun buildScoringData(
    filters: RouteGenerationFilters,
    center: LatLng,
    radiusDegrees: Double,
    schoolZoneDao: SchoolZoneDao,
    speedCameraDao: SpeedCameraDao,
): ScoringData = coroutineScope {
    val incidents = if (filters.incidents != FilterPreference.NONE) {
        async {
            runCatching { fetchOpenIncidents(BuildConfig.TFNSW_API_KEY).map { LatLng(it.latitude, it.longitude) } }
                .getOrDefault(emptyList())
        }
    } else {
        null
    }
    val construction = if (filters.constructionZones != FilterPreference.NONE) {
        async {
            runCatching { fetchOpenRoadworks(BuildConfig.TFNSW_API_KEY).map { LatLng(it.latitude, it.longitude) } }
                .getOrDefault(emptyList())
        }
    } else {
        null
    }
    val schoolZones = if (filters.schoolZones != FilterPreference.NONE) {
        async { runCatching { schoolZoneDao.getAll().map { LatLng(it.latitude, it.longitude) } }.getOrDefault(emptyList()) }
    } else {
        null
    }
    val speedCameras = if (filters.speedCameras != FilterPreference.NONE) {
        async { runCatching { speedCameraDao.getAll().map { LatLng(it.latitude, it.longitude) } }.getOrDefault(emptyList()) }
    } else {
        null
    }
    val roundabouts = if (filters.roundabouts != FilterPreference.NONE) {
        async {
            runCatching { fetchRoundabouts(center, radiusDegrees).mapNotNull { it.firstOrNull() } }.getOrDefault(emptyList())
        }
    } else {
        null
    }
    val mergeLanes = if (filters.mergingLanes != FilterPreference.NONE) {
        async { runCatching { fetchMergeLaneProxies(center, radiusDegrees).flatten() }.getOrDefault(emptyList()) }
    } else {
        null
    }
    val majorRoads = if (filters.highways == FilterPreference.PREFER) {
        async { runCatching { fetchMajorRoads(center, radiusDegrees).flatten() }.getOrDefault(emptyList()) }
    } else {
        null
    }

    ScoringData(
        incidents = incidents?.await() ?: emptyList(),
        constructionZones = construction?.await() ?: emptyList(),
        schoolZones = schoolZones?.await() ?: emptyList(),
        speedCameras = speedCameras?.await() ?: emptyList(),
        roundabouts = roundabouts?.await() ?: emptyList(),
        mergeLaneProxies = mergeLanes?.await() ?: emptyList(),
        majorRoads = majorRoads?.await() ?: emptyList(),
    )
}

private fun formatTime(time: LocalTime): String =
    time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    return "${minutes / 60}h ${minutes % 60}m"
}

private fun formatDistance(meters: Double): String = "%.1f km".format(meters / 1000)
