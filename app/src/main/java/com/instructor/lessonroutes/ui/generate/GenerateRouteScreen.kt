package com.instructor.lessonroutes.ui.generate

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.instructor.lessonroutes.data.remote.fetchHighVolumeRoads
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
import com.instructor.lessonroutes.data.routegen.generateCandidateRoutes
import com.instructor.lessonroutes.data.routegen.midpoint
import com.instructor.lessonroutes.data.routegen.pickBestRoute
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.ui.routes.ProfilePickerSection
import com.instructor.lessonroutes.ui.routes.openInNavApp
import com.instructor.lessonroutes.util.LOCATION_PERMISSIONS
import com.instructor.lessonroutes.util.hasLocationPermission
import com.instructor.lessonroutes.util.startLocationUpdates
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    // Suppresses re-searching immediately after picking a result (which sets
    // searchQuery to the result's own label, which would otherwise look just
    // like new typing and fire another search for the exact same text).
    var lastAppliedResultLabel by remember { mutableStateOf<String?>(null) }

    val effectiveDestination = if (loopBackToStart) currentLocation else destination

    // Debounced live search: waits 500ms after the user stops typing before
    // actually calling Nominatim, so results appear as-you-type without firing a
    // request per keystroke (Nominatim's usage policy asks for ~1 request/second
    // at most). Not gated on loopBackToStart -- the search box is always visible
    // now, and picking a result unchecks "loop back to start" itself, so search
    // should always respond to typing regardless of the checkbox's state.
    LaunchedEffect(searchQuery) {
        if (searchQuery.isBlank() || searchQuery == lastAppliedResultLabel) {
            searchResults = emptyList()
            searchError = null
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        delay(500)
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

    // -- Start/end time (only used to compute a target duration) --
    // Start time is optional -- left blank, it's assumed to mean "now". Computed
    // fresh on every recomposition (not memoized/remembered) since "now" is
    // exactly that -- whatever the current wall-clock time actually is when the
    // duration is next displayed or generation is next kicked off.
    var startTime by remember { mutableStateOf<LocalTime?>(null) }
    var endTime by remember { mutableStateOf<LocalTime?>(null) }
    var showStartTimePicker by remember { mutableStateOf(false) }
    var showEndTimePicker by remember { mutableStateOf(false) }
    val effectiveStartTime = startTime ?: LocalTime.now()
    val targetDurationMinutes = endTime?.let { end ->
        if (end.isAfter(effectiveStartTime)) Duration.between(effectiveStartTime, end).toMinutes().toInt() else null
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
        // Recomputed here (not read from the composable-scope value above) so a
        // blank start time really does mean "right now, at the moment Generate
        // was tapped" rather than whatever "now" happened to be at the last
        // recomposition.
        val actualStartTime = startTime ?: LocalTime.now()
        val minutes = endTime?.let { end2 ->
            if (end2.isAfter(actualStartTime)) Duration.between(actualStartTime, end2).toMinutes().toInt() else null
        } ?: return
        generatedRoute = null
        generationError = null
        isGenerating = true
        scope.launch {
            try {
                // Hard ceiling so a slow/stuck network call (OSRM, Overpass, or
                // TfNSW) can never leave the spinner running forever -- surfaces
                // as a timeout error instead. 20s comfortably covers the worst
                // case now (3 bearings running concurrently, each up to 2
                // sequential OSRM calls at 6s max plus, when Highways/
                // Roundabouts/Merging lanes is set, one more bounded at 5s --
                // see OsrmApi.kt/RouteGenerator.kt's own tighter per-call
                // timeouts) while target typical-case generation is well under
                // 10s.
                val result = withTimeoutOrNull(20_000) {
                    val center = midpoint(start, end)
                    val radiusDegrees = estimateSearchRadiusDegrees(minutes)
                    // Genuinely independent of each other -- run concurrently
                    // rather than one after the other, so a slow Overpass
                    // response (a heavily loaded shared community server) isn't
                    // just added on top of however long OSRM's candidates take.
                    val scoringDataDeferred = async {
                        buildScoringData(filters, center, radiusDegrees, schoolZoneDao, speedCameraDao)
                    }
                    // fetchAlternatives adds one extra sequential OSRM call per
                    // bearing (after that bearing's own refinement converges), so
                    // it's only turned on for the filters that actually need a
                    // meaningfully different path shape per bearing to be worth
                    // scoring against -- Highways/Roundabouts/Merging lanes, none
                    // of which OSRM can be told to route around directly (unlike
                    // a real exclude constraint, which doesn't exist here -- see
                    // RouteGenerator's doc comments). Hazards/school-zones/etc.
                    // don't get this, keeping that path just as fast as before.
                    val needsAlternatives = filters.highways != FilterPreference.NONE ||
                        filters.roundabouts != FilterPreference.NONE ||
                        filters.mergingLanes != FilterPreference.NONE
                    val candidatesDeferred = async {
                        generateCandidateRoutes(
                            start,
                            end,
                            minutes,
                            avoidHighways = filters.highways == FilterPreference.AVOID,
                            fetchAlternatives = needsAlternatives,
                        )
                    }
                    val candidates = candidatesDeferred.await()
                    val scoringData = scoringDataDeferred.await()
                    // pickBestRoute does a nested proximity-comparison loop over
                    // every scoring point x every route point -- for Highways/
                    // Roundabouts/Merging lanes, whose Overpass data is every
                    // vertex of every matching road (can be thousands for a wide
                    // search area), that's real CPU work, not I/O. scope.launch
                    // here runs on the Main dispatcher (rememberCoroutineScope's
                    // default), so without this it was blocking the UI thread
                    // outright -- the reported "hangs and becomes unresponsive",
                    // not just a slow network wait.
                    withContext(Dispatchers.Default) { pickBestRoute(candidates, filters, scoringData) }
                }
                if (result == null) {
                    generationError = "Couldn't generate a route right now (timed out or no route found) — " +
                        "check your connection and try again."
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
            // Fixed height, NOT weighted: a Column can't correctly split space
            // between a weighted sibling and an unweighted verticalScroll sibling
            // below (the unweighted scrolling column measures to its full content
            // height since nothing bounds it, which starved this map down to
            // near-zero height in practice -- the earlier bug where the map
            // appeared to not exist at all). The scrollable content column below
            // is the one that gets weight(1f) now.
            Box(modifier = Modifier.height(260.dp).fillMaxWidth()) {
                RouteMapView(
                    modifier = Modifier.fillMaxSize(),
                    routePoints = generatedRoute?.points ?: emptyList(),
                    // Looping back to start's destination is the current location,
                    // already shown as the live-location dot -- a second marker on
                    // top of it would just be a confusing duplicate.
                    waypoints = if (loopBackToStart) emptyList() else listOfNotNull(destination),
                    liveLocation = currentLocation,
                    fitBoundsToRoute = generatedRoute != null,
                    // This screen already tracks the device's location itself
                    // (above) -- centerOnDeviceLocation=false plus focusPoint
                    // avoids RouteMapView also running its own redundant
                    // permission-request/location-fetch, which raced with this
                    // screen's own and left the camera stuck on the Sydney
                    // fallback instead of the device's real location.
                    centerOnDeviceLocation = false,
                    focusPoint = currentLocation,
                    // Always active now (was conditionally null while "loop back
                    // to start" was checked) -- tapping a destination is itself a
                    // clear enough signal to switch out of loop mode automatically,
                    // rather than silently doing nothing because a checkbox
                    // elsewhere hadn't been unticked first (that read as "tapping
                    // the map is broken").
                    onMapClick = { latLng ->
                        destination = latLng
                        loopBackToStart = false
                        searchResults = emptyList()
                    },
                )
            }

            // Pinned between the map and the scrollable content below -- NOT part
            // of the scroll, so Regenerate/Open in nav app/Save stay visible no
            // matter how far down the instructor scrolls through Destination/Time/
            // Filters. Same fixed-height-sibling-plus-weighted-scroll pattern as
            // the map above (a Column can't correctly share space between a
            // weighted sibling and an unbounded one otherwise).
            generatedRoute?.let { route ->
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("Generated: ${formatDuration(route.durationSeconds)}, ${formatDistance(route.distanceMeters)}")
                    if (saveComplete) {
                        Text("Saved.")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
                HorizontalDivider()
            }

            Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("Destination")
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = loopBackToStart, onCheckedChange = { loopBackToStart = it })
                    Text("Loop back to where I start")
                }
                // Always visible now -- these used to be hidden while "loop back to
                // start" was checked, which looked like the address box had
                // disappeared and tapping the map did nothing (both were
                // intentionally disabled, but with no visible reason why).
                // Tapping the map or picking a search result unchecks the box
                // above automatically, so there's no separate step to remember.
                Text(
                    if (destination != null && !loopBackToStart) {
                        "Destination set — tap the map to change it, or search below."
                    } else {
                        "Tap the map to set a destination, or search below."
                    },
                )
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search an address") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (isSearching) {
                    Text("Searching…")
                }
                searchError?.let { Text(it) }
                searchResults.forEach { result ->
                    ListItem(
                        headlineContent = { Text(result.label) },
                        modifier = Modifier.fillMaxWidth().clickable {
                            destination = result.location
                            loopBackToStart = false
                            searchResults = emptyList()
                            lastAppliedResultLabel = result.label
                            searchQuery = result.label
                        },
                    )
                    HorizontalDivider()
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Trip time")
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { showStartTimePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(startTime?.let { formatTime(it) } ?: "Start time (now)")
                    }
                    OutlinedButton(onClick = { showEndTimePicker = true }, modifier = Modifier.weight(1f)) {
                        Text(endTime?.let { formatTime(it) } ?: "End time")
                    }
                }
                Text(
                    text = targetDurationMinutes?.let { "Duration: ${it / 60}h ${it % 60}m" }
                        ?: if (endTime != null) "End time must be after start time" else "Pick an end time (start defaults to now)",
                )

                Spacer(modifier = Modifier.height(12.dp))
                Text("Filters")
                if (BuildConfig.TFNSW_API_KEY.isBlank()) {
                    Text(
                        "Hazards/construction/high traffic filters need a Transport for NSW API key " +
                            "(see Settings) — they'll have no effect without one.",
                    )
                }
                FilterRow("Hazards", filters.incidents) { filters = filters.copy(incidents = it) }
                FilterRow("Construction zones", filters.constructionZones) { filters = filters.copy(constructionZones = it) }
                FilterRow("School zones", filters.schoolZones) { filters = filters.copy(schoolZones = it) }
                FilterRow("Speed cameras", filters.speedCameras) { filters = filters.copy(speedCameras = it) }
                FilterRow("High traffic roads", filters.highTraffic) { filters = filters.copy(highTraffic = it) }
                FilterRow("Highways", filters.highways) { filters = filters.copy(highways = it) }
                FilterRow("Roundabouts", filters.roundabouts) { filters = filters.copy(roundabouts = it) }
                FilterRow("Merging lanes", filters.mergingLanes) { filters = filters.copy(mergingLanes = it) }
                Text(
                    "These are best-effort, not guarantees: a few candidate routes are generated and " +
                        "whichever one best matches your filters is picked — none of them can be steered " +
                        "around a specific hazard/zone while being generated. Highways/Roundabouts/Merging " +
                        "lanes add a little extra time to generation (an extra check per route direction).",
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
                    Text(
                        "Trying a few different routes and picking the best fit for your filters — " +
                            "usually just a few seconds, up to 20 at most.",
                    )
                }
                generationError?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
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

@OptIn(ExperimentalMaterial3Api::class)
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

@OptIn(ExperimentalMaterial3Api::class)
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
    // Explicit Deferred<List<LatLng>>? type on every val below -- Kotlin's type
    // inference can't reliably unify `if (cond) async { ... } else null` into
    // Deferred<T>? on its own (a real compiler limitation, not a style choice).
    val incidents: Deferred<List<LatLng>>? = if (filters.incidents != FilterPreference.NONE) {
        async {
            runCatching { fetchOpenIncidents(BuildConfig.TFNSW_API_KEY).map { LatLng(it.latitude, it.longitude) } }
                .getOrDefault(emptyList())
        }
    } else {
        null
    }
    val construction: Deferred<List<LatLng>>? = if (filters.constructionZones != FilterPreference.NONE) {
        async {
            runCatching { fetchOpenRoadworks(BuildConfig.TFNSW_API_KEY).map { LatLng(it.latitude, it.longitude) } }
                .getOrDefault(emptyList())
        }
    } else {
        null
    }
    val schoolZones: Deferred<List<LatLng>>? = if (filters.schoolZones != FilterPreference.NONE) {
        async { runCatching { schoolZoneDao.getAll().map { LatLng(it.latitude, it.longitude) } }.getOrDefault(emptyList()) }
    } else {
        null
    }
    val speedCameras: Deferred<List<LatLng>>? = if (filters.speedCameras != FilterPreference.NONE) {
        async { runCatching { speedCameraDao.getAll().map { LatLng(it.latitude, it.longitude) } }.getOrDefault(emptyList()) }
    } else {
        null
    }
    val roundabouts: Deferred<List<LatLng>>? = if (filters.roundabouts != FilterPreference.NONE) {
        async {
            runCatching { fetchRoundabouts(center, radiusDegrees).mapNotNull { it.firstOrNull() } }.getOrDefault(emptyList())
        }
    } else {
        null
    }
    val mergeLanes: Deferred<List<LatLng>>? = if (filters.mergingLanes != FilterPreference.NONE) {
        async { runCatching { fetchMergeLaneProxies(center, radiusDegrees).sampleForScoring() }.getOrDefault(emptyList()) }
    } else {
        null
    }
    val majorRoads: Deferred<List<LatLng>>? = if (filters.highways != FilterPreference.NONE) {
        async { runCatching { fetchMajorRoads(center, radiusDegrees).sampleForScoring() }.getOrDefault(emptyList()) }
    } else {
        null
    }
    val highTraffic: Deferred<List<LatLng>>? = if (filters.highTraffic != FilterPreference.NONE) {
        async {
            runCatching { fetchHighVolumeRoads(BuildConfig.TFNSW_API_KEY).map { LatLng(it.latitude, it.longitude) } }
                .getOrDefault(emptyList())
        }
    } else {
        null
    }

    ScoringData(
        incidents = incidents?.await() ?: emptyList(),
        constructionZones = construction?.await() ?: emptyList(),
        schoolZones = schoolZones?.await() ?: emptyList(),
        speedCameras = speedCameras?.await() ?: emptyList(),
        roundabouts = roundabouts?.await() ?: emptyList(),
        highTraffic = highTraffic?.await() ?: emptyList(),
        mergeLaneProxies = mergeLanes?.await() ?: emptyList(),
        majorRoads = majorRoads?.await() ?: emptyList(),
    )
}

/** Max representative points kept per matched road ("way") for scoring. Overpass
 * returns every vertex of every matching way -- for a wide search area, major
 * roads/motorway_link+trunk_link ways can add up to thousands of points, and
 * proximity-scoring checks every scoring point against every route point (an
 * O(n*m) nested loop in RouteGenerator.scoreRoute) -- flattening the full vertex
 * list made that comparison expensive enough to visibly freeze the UI even after
 * moving the scoring step off the main thread. A ~40m proximity check doesn't
 * need every vertex of a road to know whether a route passes near it; a handful
 * of evenly-spread points per way is more than enough. */
private const val MAX_SCORING_POINTS_PER_WAY = 4

private fun List<List<LatLng>>.sampleForScoring(): List<LatLng> = flatMap { way ->
    if (way.size <= MAX_SCORING_POINTS_PER_WAY) {
        way
    } else {
        val stride = way.size.toDouble() / MAX_SCORING_POINTS_PER_WAY
        (0 until MAX_SCORING_POINTS_PER_WAY).map { i -> way[(i * stride).toInt()] }
    }
}

private fun formatTime(time: LocalTime): String =
    time.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

private fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).toInt()
    return "${minutes / 60}h ${minutes % 60}m"
}

private fun formatDistance(meters: Double): String = "%.1f km".format(meters / 1000)
