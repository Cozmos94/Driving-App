package com.instructor.lessonroutes.ui.generate

import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
import com.instructor.lessonroutes.data.routegen.routeExceedsRadius
import com.instructor.lessonroutes.data.routegen.summarize
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.ui.routes.ProfilePickerSection
import com.instructor.lessonroutes.ui.routes.openInNavApp
import com.instructor.lessonroutes.ui.theme.BackgroundWhite
import com.instructor.lessonroutes.ui.theme.BorderNavy
import com.instructor.lessonroutes.ui.theme.SelectedBlue
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
import kotlin.math.abs
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private const val LOG_TAG = "GenerateRouteScreen"

/**
 * Plans a route to actually go drive, rather than record/tap one by hand: pick a
 * destination (tap the map or search an address), a
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
    // Always a real place now -- "loop back to where I start" was removed (per
    // Corey's request) since it was a separate, less-used third path to picking
    // a destination alongside map-tap and address search.
    var destination by remember { mutableStateOf<LatLng?>(null) }
    // Optional ceiling on how far the generated route can detour from the
    // start/destination midpoint (see generateCandidateRoutes' maxRadiusKm) --
    // null means no extra limit beyond whatever the target duration implies.
    var selectedRadiusKm by remember { mutableStateOf<Double?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<GeocodeResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    // Suppresses re-searching immediately after picking a result (which sets
    // searchQuery to the result's own label, which would otherwise look just
    // like new typing and fire another search for the exact same text).
    var lastAppliedResultLabel by remember { mutableStateOf<String?>(null) }

    val effectiveDestination = destination

    // Debounced live search: waits 500ms after the user stops typing before
    // actually calling the geocoding API, so results appear as-you-type without
    // firing a request per keystroke.
    LaunchedEffect(searchQuery) {
        // Captured once, at the start of this specific search -- see the guard
        // below for why.
        val queryForThisSearch = searchQuery
        if (queryForThisSearch.isBlank() || queryForThisSearch == lastAppliedResultLabel) {
            searchResults = emptyList()
            searchError = null
            isSearching = false
            return@LaunchedEffect
        }
        isSearching = true
        delay(500)
        searchError = null
        try {
            val results = searchAddress(queryForThisSearch)
            // Guards against a real, confirmed race: LaunchedEffect cancels the
            // *coroutine* when searchQuery changes, but searchAddress()'s
            // underlying OkHttp call is a synchronous, non-cancellation-aware
            // blocking call -- it isn't actually interrupted by that
            // cancellation, and can still complete (successfully) after a
            // newer search has already finished and shown its result. On a
            // fast/uniform emulator network this rarely reorders; on a real
            // mobile connection's much more variable latency, an earlier,
            // less-specific query (e.g. "Mcdonell St", fired during a brief
            // typing pause) can finish *after* a fuller one already displayed
            // the correct numbered match, silently overwriting it -- exactly
            // the "shows up on the emulator but not my phone" symptom. Only
            // apply this result if searchQuery hasn't moved on since.
            if (searchQuery == queryForThisSearch) {
                searchResults = results
                if (results.isEmpty()) searchError = "No matches found"
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Address search failed", e)
            if (searchQuery == queryForThisSearch) searchError = "Couldn't search right now"
        } finally {
            if (searchQuery == queryForThisSearch) isSearching = false
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
    // Set even on a *successful* generation -- a filter can silently have zero
    // effect if its own scoring data failed to load (Overpass/TfNSW down, rate
    // limited, or just slow), which previously had no visible signal at all
    // unless the whole generation also timed out. Real example: Highways->Avoid
    // still picking the M1 could mean every candidate genuinely needed it, OR it
    // could mean fetchMajorRoads quietly came back empty that run -- this makes
    // that second case visible instead of looking identical to the first.
    var dataWarning by remember { mutableStateOf<String?>(null) }
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
        dataWarning = null
        isGenerating = true
        // Populated *during* the withTimeoutOrNull block below, not read from its
        // return value -- if the 45s ceiling fires, the block's own result is
        // discarded entirely, but these plain vars keep whatever they were last
        // set to, which is exactly the partial state needed to tell the
        // instructor what actually went wrong (a specific filter's data fetch
        // failing/timing out, vs. routing itself never responding at all) --
        // and, on a *successful* generation, which active filters (if any) had
        // no scoring data to work with at all.
        var emptyScoringCategories: List<String> = emptyList()
        var candidateCount = 0
        scope.launch {
            try {
                // Hard ceiling so a slow/stuck network call (Geoapify routing,
                // Overpass, or TfNSW) can never leave the spinner running
                // forever -- surfaces as a timeout error instead. Widened
                // 20s->45s: the 20s budget
                // left little slack once a slow-but-not-timed-out-itself Overpass
                // response ate into it (see SCORING_FETCH_TIMEOUT_MS's own 8s
                // per-category bound below), and typical-case generation is still
                // well under 10s regardless of this ceiling.
                val result = withTimeoutOrNull(45_000) {
                    val center = midpoint(start, end)
                    val radiusDegrees = estimateSearchRadiusDegrees(minutes)
                    // Genuinely independent of each other -- run concurrently
                    // rather than one after the other, so a slow Overpass
                    // response (a heavily loaded shared community server) isn't
                    // just added on top of however long the routing API's candidates take.
                    val scoringDataDeferred = async {
                        buildScoringData(filters, center, radiusDegrees, schoolZoneDao, speedCameraDao)
                    }
                    val candidatesDeferred = async {
                        generateCandidateRoutes(
                            start,
                            end,
                            minutes,
                            avoidHighways = filters.highways == FilterPreference.AVOID,
                            maxRadiusKm = selectedRadiusKm,
                        )
                    }
                    val candidates = candidatesDeferred.await()
                    candidateCount = candidates.size
                    val scoringResult = scoringDataDeferred.await()
                    emptyScoringCategories = scoringResult.emptyCategories
                    // pickBestRoute does a nested proximity-comparison loop over
                    // every scoring point x every route point -- for Highways/
                    // Roundabouts/Merging lanes, whose Overpass data is every
                    // vertex of every matching road (can be thousands for a wide
                    // search area), that's real CPU work, not I/O. scope.launch
                    // here runs on the Main dispatcher (rememberCoroutineScope's
                    // default), so without this it was blocking the UI thread
                    // outright -- the reported "hangs and becomes unresponsive",
                    // not just a slow network wait.
                    withContext(Dispatchers.Default) {
                        pickBestRoute(
                            candidates,
                            filters,
                            scoringResult.data,
                            targetSeconds = minutes * 60.0,
                            start = start,
                            maxRadiusKm = selectedRadiusKm,
                        )
                    }
                }
                if (result == null) {
                    // Diagnose *why*, using whatever partial state was captured
                    // above before the ceiling fired, instead of one generic
                    // message for every cause.
                    generationError = when {
                        emptyScoringCategories.isNotEmpty() ->
                            "Couldn't generate a route — couldn't load data for: " +
                                "${emptyScoringCategories.joinToString(", ")}. Their server can be slow, " +
                                "overloaded, or briefly down; try again, or turn those filters off."
                        candidateCount == 0 ->
                            "Couldn't generate a route — the routing service didn't return a route " +
                                "in time. This isn't caused by your filters; check your connection and try again."
                        else -> "Couldn't generate a route in time — check your connection and try again."
                    }
                } else {
                    generatedRoute = result
                    // A route was found, but if an active filter's own scoring
                    // data never loaded, it had literally nothing to score
                    // against and so no effect on which candidate got picked --
                    // surface that instead of it silently looking like the
                    // filter just "didn't work" (e.g. Highways->Avoid picking a
                    // motorway could mean every candidate genuinely needed it,
                    // or it could mean this).
                    // Captured into a local val -- selectedRadiusKm is a `by
                    // remember` delegated property, which Kotlin can't smart-cast
                    // to non-null across the `radiusKm != null` check below (it
                    // can't prove a delegated var won't change in between).
                    val radiusKm = selectedRadiusKm
                    val radiusExceeded = radiusKm != null && routeExceedsRadius(result, start, radiusKm)
                    // How far the actual duration landed from the target -- see the
                    // radiusLimitedDuration case below. Same 25% tolerance concept as
                    // RouteGenerator's own DURATION_TOLERANCE_RATIO (not imported --
                    // that constant is private, and duplicating one plain ratio check
                    // here isn't worth exposing it just for this).
                    val durationErrorRatio = abs(result.durationSeconds - minutes * 60.0) / (minutes * 60.0)
                    // A radius cap takes priority over the target duration by design
                    // (generateCandidateRoutes/pickBestRoute's maxRadiusKm) -- a real,
                    // confirmed case (target 1h30m, radius capped, generated 34min)
                    // where the cap was the actual cause, not a generator bug, but with
                    // no visible signal it read as "the duration target was ignored."
                    val radiusLimitedDuration = radiusKm != null && !radiusExceeded && durationErrorRatio > 0.25
                    dataWarning = when {
                        emptyScoringCategories.isNotEmpty() ->
                            "Couldn't load data for: ${emptyScoringCategories.joinToString(", ")} — " +
                                "those filters had no effect on this route. Try regenerating."
                        // pickBestRoute only ever falls back to an over-radius
                        // candidate when literally none of them stayed within
                        // it -- almost always because the destination itself is
                        // farther from the start than the chosen radius, so no
                        // possible route between them could have stayed inside
                        // it either way.
                        radiusExceeded ->
                            "This route goes beyond your ${radiusKm?.toInt()}km radius — no route to this " +
                                "destination could stay within it. Try a larger radius or a closer destination."
                        radiusLimitedDuration ->
                            "This route came in well short of your target time — your ${radiusKm?.toInt()}km " +
                                "radius didn't allow enough detour to reach it. Try a larger radius for a " +
                                "closer match to your target duration."
                        // No radius cap to blame this time -- a real, confirmed
                        // case (target 2h16m, generated 3h19m, no radius set)
                        // where the duration heuristic itself missed by a lot.
                        // Surfaced rather than left silent, since the generated
                        // number is otherwise indistinguishable from a
                        // well-converged one.
                        radiusKm == null && durationErrorRatio > 0.25 ->
                            "This route's time missed your target by a fair bit (wanted " +
                                "${formatDuration(minutes * 60.0)}, got ${formatDuration(result.durationSeconds)}) " +
                                "— try Regenerate, or a different destination/time."
                        else -> null
                    }
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
                    waypoints = listOfNotNull(destination),
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
                    // Shows the optional radius cap as a circle centered on the
                    // *start* location -- "radius" means how far from where the
                    // trip actually begins, matching how pickBestRoute now
                    // enforces it (distance from start, not from
                    // generateCandidateRoutes' internal search midpoint). Null
                    // while no radius is set or before a location fix exists.
                    radiusCircleCenter = currentLocation,
                    radiusCircleKm = selectedRadiusKm,
                    onMapClick = { latLng ->
                        destination = latLng
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
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Generated") }
                            append(": ${formatDuration(route.durationSeconds)}, ${formatDistance(route.distanceMeters)}")
                        },
                    )
                    if (saveComplete) {
                        Text("Saved.")
                    }
                    dataWarning?.let { Text(it, modifier = Modifier.padding(top = 4.dp)) }
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
                Text("Destination", fontWeight = FontWeight.Bold)
                RadiusPicker(selectedRadiusKm = selectedRadiusKm, onSelect = { selectedRadiusKm = it })
                Text(
                    if (destination != null) {
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
                            searchResults = emptyList()
                            lastAppliedResultLabel = result.label
                            searchQuery = result.label
                        },
                    )
                    HorizontalDivider()
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Trip time", fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { showStartTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(startTime?.let { formatTime(it) } ?: "Start time (now)")
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        OutlinedButton(onClick = { showEndTimePicker = true }, modifier = Modifier.fillMaxWidth()) {
                            Text(endTime?.let { formatTime(it) } ?: "End time")
                        }
                        // An end time is mandatory (canGenerate requires
                        // targetDurationMinutes != null) -- bold red, directly
                        // under the button it's asking about, so it reads as a
                        // real requirement rather than a general hint below the
                        // whole row. Disappears the moment endTime is set (valid
                        // or not) -- either the real duration or the "must be
                        // after start" message below takes over from there.
                        if (endTime == null) {
                            Text("Set End time", color = Color(0xFFD21F3C), fontWeight = FontWeight.Bold)
                        }
                    }
                }
                when {
                    targetDurationMinutes != null -> Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Duration") }
                            append(": ${targetDurationMinutes / 60}h ${targetDurationMinutes % 60}m")
                        },
                    )
                    endTime != null -> Text("End time must be after start time")
                    else -> Unit
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Optional Filters", fontWeight = FontWeight.Bold)
                // Each on its own line, and a step down from "Optional Filters"
                // itself (Medium, not Bold) -- a lighter emphasis than the
                // section header above, but still calling out the label word
                // from its explanation.
                FilterLegendLine("Neither selected", "no preference either way.")
                FilterLegendLine("Avoid", "try not to include it in the generated route at all.")
                FilterLegendLine("Prefer", "try to include more of it (e.g. more school zones, or more roundabouts).")
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
                    "Best-effort, not guaranteed — picks the closest-matching candidate rather than " +
                        "steering around a specific hazard.",
                )

                Spacer(modifier = Modifier.height(12.dp))
                if (currentLocation == null) {
                    Text("Waiting for your location — check location permission is granted.")
                }
                Button(
                    onClick = { onGenerateClick() },
                    enabled = canGenerate && !isGenerating,
                    modifier = Modifier.fillMaxWidth(),
                    // Same look as the live map's "Plan a trip"/"Student
                    // Profiles" buttons -- Corey's explicit choice for this
                    // button specifically, distinct from the app's general
                    // primary/secondary button colors elsewhere. Was a filled
                    // #00B4D8 briefly before Corey settled on white with a
                    // SelectedBlue border instead.
                    colors = ButtonDefaults.buttonColors(containerColor = BackgroundWhite, contentColor = BorderNavy),
                    border = BorderStroke(1.dp, SelectedBlue),
                ) {
                    Text(if (isGenerating) "Generating…" else "Generate route")
                }
                generationError?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }

    // Modal, not just the inline "Generating…" button label -- easy to miss if
    // scrolled past the button, and generation can now take up to 45s. Not
    // dismissible by the user (no route to show yet either way); closes itself
    // as soon as isGenerating flips back to false in onGenerateClick's `finally`
    // block, whether that's because a route was found, generation failed, or it
    // timed out.
    if (isGenerating) {
        GeneratingDialog()
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
                val filterSummary = filters.summarize()
                scope.launch {
                    val id = dao.insertRoute(
                        Route(
                            name = name,
                            notes = notes.ifBlank { null },
                            dateCreated = System.currentTimeMillis(),
                            avoidFilters = filterSummary.avoidCsv,
                            preferFilters = filterSummary.preferCsv,
                        ),
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

/** Optional ceiling on the generated route's detour distance (see
 * generateCandidateRoutes' maxRadiusKm doc comment) -- 5km increments up to
 * 200km, plus "No limit". Uses a plain Box + DropdownMenu rather than
 * ExposedDropdownMenuBox, which needs a version-sensitive `menuAnchor()` API
 * that's changed shape across recent Material3 releases -- this is simpler and
 * has been stable for longer. */
@Composable
private fun RadiusPicker(selectedRadiusKm: Double?, onSelect: (Double?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.padding(top = 4.dp)) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(selectedRadiusKm?.let { "Radius: ${it.toInt()} km" } ?: "Set radius (optional)")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("No limit") }, onClick = { onSelect(null); expanded = false })
            for (km in 5..200 step 5) {
                DropdownMenuItem(text = { Text("$km km") }, onClick = { onSelect(km.toDouble()); expanded = false })
            }
        }
    }
}

/** One line of the Avoid/Prefer legend -- [term] (e.g. "Avoid") bolded to
 * Medium weight, [explanation] following at normal weight. Medium rather than
 * Bold deliberately reads as a lighter emphasis than the "Optional Filters"
 * section header above it. */
@Composable
private fun FilterLegendLine(term: String, explanation: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.Medium)) { append(term) }
            append(": $explanation")
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(label: String, preference: FilterPreference, onChange: (FilterPreference) -> Unit) {
    // Just Avoid/Prefer -- preference is a single FilterPreference value, so it
    // can never actually hold both AVOID and PREFER at once; tapping the
    // already-selected chip clears it back to NONE (no separate "None" chip
    // needed). The two chips looking simultaneously selected on-device was very
    // likely the theme's missing surface/outline colors (see Theme.kt/Color.kt)
    // making an *unselected* chip's outline hard to tell apart from a selected
    // one's fill, not an actual dual-selection bug.
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

/** Shown modally for the whole of [onGenerateClick]'s network round-trip --
 * see the isGenerating check above for why (easy to miss the inline button
 * label once scrolled away, and generation can take up to 45s). Not
 * dismissible by tap-outside/back -- there's nothing useful to fall back to
 * mid-generation, and it closes on its own the moment generation finishes,
 * one way or another. */
@Composable
private fun GeneratingDialog() {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 6.dp) {
            Row(modifier = Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
                Spacer(modifier = Modifier.width(16.dp))
                Text("Generating your route — this can take up to a minute. Please be patient.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppTimePickerDialog(initial: LocalTime, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = false)
    val context = LocalContext.current
    val isDark = isSystemInDarkTheme()
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            // Deliberately opts back OUT of the app's own blue theme here --
            // Corey asked for the clock to stay unstyled. Plain Material3
            // baseline (lightColorScheme()/darkColorScheme() with no seed) is
            // NOT actually "default Android colours" -- it's a purple/pink-
            // seeded demo palette baked into Compose Material3 itself (this
            // app's own earlier bugs already confirmed that), and that's
            // exactly what showed up here after the first attempt at this.
            // What a real device actually looks like by default on Android
            // 12+ is the dynamic/Material You palette derived from the
            // device's own wallpaper -- use that when available (this app
            // already uses dynamicLightColorScheme/dynamicDarkColorScheme
            // elsewhere, just switched off by default for the *rest* of the
            // app so it doesn't fight the custom blue brand palette; here we
            // want the opposite). Pre-Android-12 has no such per-device
            // palette to fall back to, so the purple/pink M3 baseline is the
            // closest thing "default" can mean there.
            val clockColorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else if (isDark) {
                darkColorScheme()
            } else {
                lightColorScheme()
            }
            MaterialTheme(colorScheme = clockColorScheme) {
                TimePicker(state = state)
            }
        },
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

/** [ScoringData] plus the display name of any requested category that ended up
 * with zero usable data -- whether because its fetch didn't finish within its
 * own bound ([SCORING_FETCH_TIMEOUT_MS], or the longer
 * [OVERPASS_SCORING_FETCH_TIMEOUT_MS] for Overpass-backed categories), or
 * because it threw (network/HTTP/parse failure, already caught below), or
 * because the source genuinely returned nothing.
 * Surfaced to the instructor both when generation fails outright and, just as
 * importantly, when it *succeeds*: a filter with no scoring data to work with
 * had no effect on which candidate got picked, silently, unless this is shown --
 * e.g. Highways->Avoid still picking a motorway could mean every candidate
 * genuinely needed one, or it could mean fetchMajorRoads quietly came back
 * empty that run. Those look identical to the instructor without this. */
private data class ScoringFetchResult(val data: ScoringData, val emptyCategories: List<String>)

private const val SCORING_FETCH_TIMEOUT_MS = 8_000L

// Roundabouts/Merging lanes/Highways go through Overpass (OverpassApi.kt), which
// is a real, confirmed step slower than the 8s bound above: tested live at this
// screen's actual search radius for a ~80min trip (a ~65km-wide bbox), the
// highways query alone took ~9s even against a healthy Overpass instance, and a
// separate merge-lanes query took ~10s even at a much smaller bbox -- Overpass
// is just inherently heavier for these broader tag queries than a TfNSW call or
// a local Room read. 8s was marking these "couldn't load" on every run even when
// Overpass would have answered a couple of seconds later. Also see
// OVERPASS_SCORING_RADIUS_CAP_DEGREES below -- the radius fix and this timeout
// fix are both real contributors, confirmed independently.
private const val OVERPASS_SCORING_FETCH_TIMEOUT_MS = 20_000L

// Roundabouts/Merging lanes/Highways scoring doesn't meaningfully improve by
// searching tens of km out -- candidate routes generated for even a long trip
// stay much more localized than the naive duration-implied radius (which,
// confirmed live, produced a ~65km-wide box for an 80min trip target and took
// Overpass ~9s to answer for highways alone at that size). Capping this
// specifically for the Overpass-backed categories cuts both query cost and the
// amount of data to sample/process afterward, without capping the (much
// smaller, already-fast) TfNSW/Room-backed categories' search area.
private const val OVERPASS_SCORING_RADIUS_CAP_DEGREES = 0.15

/** Fetches point-of-interest data only for the categories actually set to
 * AVOID/PREFER in [filters] -- fetching the rest would be wasted network calls.
 * Each category's fetch is bounded independently (see [SCORING_FETCH_TIMEOUT_MS])
 * so one stalled response can't silently consume the whole generation deadline by
 * itself -- it just falls back to "no data for this category", same as any other
 * fetch failure, and gets named in [ScoringFetchResult.emptyCategories]. */
private suspend fun buildScoringData(
    filters: RouteGenerationFilters,
    center: LatLng,
    radiusDegrees: Double,
    schoolZoneDao: SchoolZoneDao,
    speedCameraDao: SpeedCameraDao,
): ScoringFetchResult = coroutineScope {
    // Explicit Deferred<Pair<List<LatLng>, String?>>? type on every val below --
    // Kotlin's type inference can't reliably unify `if (cond) async { ... } else
    // null` on its own (a real compiler limitation, not a style choice). The
    // paired String is this category's display name if it ended up with zero
    // data (timeout OR a caught exception OR a genuinely empty result), null
    // otherwise.
    fun fetchBounded(
        name: String,
        timeoutMs: Long = SCORING_FETCH_TIMEOUT_MS,
        fetch: suspend () -> List<LatLng>,
    ): Deferred<Pair<List<LatLng>, String?>> = async {
        val list = withTimeoutOrNull(timeoutMs) { runCatching { fetch() }.getOrDefault(emptyList()) }
            ?: emptyList()
        list to (if (list.isEmpty()) name else null)
    }
    val overpassRadiusDegrees = minOf(radiusDegrees, OVERPASS_SCORING_RADIUS_CAP_DEGREES)

    val incidents: Deferred<Pair<List<LatLng>, String?>>? = if (filters.incidents != FilterPreference.NONE) {
        fetchBounded("Hazards") { fetchOpenIncidents(BuildConfig.TFNSW_API_KEY).map { LatLng(it.latitude, it.longitude) } }
    } else {
        null
    }
    val construction: Deferred<Pair<List<LatLng>, String?>>? = if (filters.constructionZones != FilterPreference.NONE) {
        fetchBounded("Construction zones") { fetchOpenRoadworks(BuildConfig.TFNSW_API_KEY).map { LatLng(it.latitude, it.longitude) } }
    } else {
        null
    }
    val schoolZones: Deferred<Pair<List<LatLng>, String?>>? = if (filters.schoolZones != FilterPreference.NONE) {
        fetchBounded("School zones") { schoolZoneDao.getAll().map { LatLng(it.latitude, it.longitude) } }
    } else {
        null
    }
    val speedCameras: Deferred<Pair<List<LatLng>, String?>>? = if (filters.speedCameras != FilterPreference.NONE) {
        fetchBounded("Speed cameras") { speedCameraDao.getAll().map { LatLng(it.latitude, it.longitude) } }
    } else {
        null
    }
    val roundabouts: Deferred<Pair<List<LatLng>, String?>>? = if (filters.roundabouts != FilterPreference.NONE) {
        fetchBounded("Roundabouts", OVERPASS_SCORING_FETCH_TIMEOUT_MS) {
            fetchRoundabouts(center, overpassRadiusDegrees).mapNotNull { it.firstOrNull() }
        }
    } else {
        null
    }
    val mergeLanes: Deferred<Pair<List<LatLng>, String?>>? = if (filters.mergingLanes != FilterPreference.NONE) {
        fetchBounded("Merging lanes", OVERPASS_SCORING_FETCH_TIMEOUT_MS) {
            fetchMergeLaneProxies(center, overpassRadiusDegrees).sampleForScoring()
        }
    } else {
        null
    }
    val majorRoads: Deferred<Pair<List<LatLng>, String?>>? = if (filters.highways != FilterPreference.NONE) {
        fetchBounded("Highways", OVERPASS_SCORING_FETCH_TIMEOUT_MS) {
            fetchMajorRoads(center, overpassRadiusDegrees).sampleForScoring()
        }
    } else {
        null
    }
    val highTraffic: Deferred<Pair<List<LatLng>, String?>>? = if (filters.highTraffic != FilterPreference.NONE) {
        fetchBounded("High traffic roads") { fetchHighVolumeRoads(BuildConfig.TFNSW_API_KEY).map { LatLng(it.latitude, it.longitude) } }
    } else {
        null
    }

    val incidentsResult = incidents?.await()
    val constructionResult = construction?.await()
    val schoolZonesResult = schoolZones?.await()
    val speedCamerasResult = speedCameras?.await()
    val roundaboutsResult = roundabouts?.await()
    val mergeLanesResult = mergeLanes?.await()
    val majorRoadsResult = majorRoads?.await()
    val highTrafficResult = highTraffic?.await()

    ScoringFetchResult(
        data = ScoringData(
            incidents = incidentsResult?.first ?: emptyList(),
            constructionZones = constructionResult?.first ?: emptyList(),
            schoolZones = schoolZonesResult?.first ?: emptyList(),
            speedCameras = speedCamerasResult?.first ?: emptyList(),
            roundabouts = roundaboutsResult?.first ?: emptyList(),
            highTraffic = highTrafficResult?.first ?: emptyList(),
            mergeLaneProxies = mergeLanesResult?.first ?: emptyList(),
            majorRoads = majorRoadsResult?.first ?: emptyList(),
        ),
        emptyCategories = listOfNotNull(
            incidentsResult?.second, constructionResult?.second, schoolZonesResult?.second, speedCamerasResult?.second,
            roundaboutsResult?.second, mergeLanesResult?.second, majorRoadsResult?.second, highTrafficResult?.second,
        ),
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
