package com.instructor.lessonroutes.ui.routes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.data.Route
import com.instructor.lessonroutes.data.RouteDao
import com.instructor.lessonroutes.data.RouteWithProfiles
import com.instructor.lessonroutes.data.StudentProfile
import com.instructor.lessonroutes.data.StudentProfileDao
import com.instructor.lessonroutes.data.routegen.ALL_FILTER_LABELS
import com.instructor.lessonroutes.data.routegen.effectiveFilterSummary
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteListScreen(
    dao: RouteDao,
    profileDao: StudentProfileDao,
    filterProfileId: Long?,
    onRouteClick: (Long) -> Unit,
    onCreateClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onProfilesClick: () -> Unit,
    onOverviewClick: () -> Unit,
) {
    val routesWithProfiles by dao.getAllRoutesWithProfiles().collectAsState(initial = emptyList())
    val allProfiles by profileDao.getAllProfiles().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // Which student profile (if any) this list is scoped to is chosen on the
    // Student Profiles screen, not here -- this screen just renders the result.
    val filterProfileName = filterProfileId?.let { id -> allProfiles.find { it.id == id }?.name }
    val visibleRoutes = if (filterProfileId == null) {
        routesWithProfiles
    } else {
        routesWithProfiles.filter { rwp -> rwp.profiles.any { it.id == filterProfileId } }
    }
    // Lifetime coverage across every generated route ever saved for this
    // student: "covered" a filter category means it was set to Prefer (an
    // active request to include more of it) in at least one of their routes --
    // a category that was only ever left at NONE or set to Avoid counts as
    // "yet to cover", since Avoid is a deliberate choice to keep the student
    // away from it, and NONE gives no positive evidence they actually drove
    // through it. Only computed scoped to one profile, not on the unfiltered
    // "All" list -- but always shown once scoped, even with zero generated
    // routes (a clear "nothing saved yet" line instead of silently showing
    // nothing at all, which read as broken rather than "no data yet").
    val filterCoverage = if (filterProfileId != null && filterProfileName != null) {
        val summaries = visibleRoutes.map { it.route.effectiveFilterSummary() }.filterNot { it.isEmpty }
        val covered = summaries.flatMap { it.prefer }.toSet()
        FilterCoverage(
            studentName = filterProfileName,
            hasAnyData = summaries.isNotEmpty(),
            covered = ALL_FILTER_LABELS.filter { it in covered },
            notYetCovered = ALL_FILTER_LABELS.filterNot { it in covered },
        )
    } else {
        null
    }

    var actionTarget by remember { mutableStateOf<RouteWithProfiles?>(null) }
    var editTarget by remember { mutableStateOf<RouteWithProfiles?>(null) }
    var deleteTarget by remember { mutableStateOf<Route?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(filterProfileName?.let { "$it's routes" } ?: "My routes") },
                actions = { TextButton(onClick = onSettingsClick) { Text("Settings") } },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onProfilesClick) { Text("Profiles") }
                OutlinedButton(onClick = onOverviewClick) { Text("Overview") }
                FloatingActionButton(onClick = onCreateClick) { Text("+") }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            filterCoverage?.let {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (it.hasAnyData) {
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Obstacles covered: ") }
                                append(it.covered.ifEmpty { listOf("none yet") }.joinToString(", "))
                            },
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            buildAnnotatedString {
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("Obstacles yet to cover: ") }
                                append(it.notYetCovered.joinToString(", "))
                            },
                        )
                    } else {
                        Text("No generated-trip routes saved for ${it.studentName} yet, so nothing to show here.")
                    }
                }
            }
            if (visibleRoutes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (routesWithProfiles.isEmpty()) {
                            "No routes yet. Tap + to record your first route."
                        } else if (filterProfileName != null) {
                            "No routes saved to $filterProfileName yet."
                        } else {
                            "No routes saved to this student profile yet."
                        },
                        modifier = Modifier.padding(32.dp),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(visibleRoutes, key = { it.route.id }) { rwp ->
                        RouteRow(
                            route = rwp.route,
                            profiles = rwp.profiles,
                            onClick = { onRouteClick(rwp.route.id) },
                            onLongClick = { actionTarget = rwp },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    actionTarget?.let { rwp ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(rwp.route.name) },
            text = { Text("What would you like to do?") },
            confirmButton = {
                TextButton(onClick = { editTarget = rwp; actionTarget = null }) { Text("Edit") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = rwp.route; actionTarget = null }) { Text("Delete") }
            },
        )
    }

    editTarget?.let { rwp ->
        var selectedProfileIds by remember(rwp.route.id) {
            mutableStateOf(rwp.profiles.map { it.id }.toSet())
        }
        EditRouteDialog(
            route = rwp.route,
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
            onDismiss = { editTarget = null },
            onSave = { name, notes ->
                scope.launch {
                    dao.updateRoute(rwp.route.copy(name = name, notes = notes.ifBlank { null }))
                    dao.setProfilesForRoute(rwp.route.id, selectedProfileIds.toList())
                }
                editTarget = null
            },
        )
    }

    deleteTarget?.let { route ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${route.name}\"?") },
            text = { Text("This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { scope.launch { dao.deleteRoute(route) }; deleteTarget = null }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RouteRow(
    route: Route,
    profiles: List<StudentProfile>,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(route.name) },
        supportingContent = {
            val tagPrefix = route.tag?.let { "$it • " } ?: ""
            val profileSuffix = if (profiles.isNotEmpty()) " • ${profiles.joinToString(", ") { it.name }}" else ""
            Text("$tagPrefix${formatDate(route.dateCreated)}$profileSuffix")
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun EditRouteDialog(
    route: Route,
    allProfiles: List<StudentProfile>,
    selectedProfileIds: Set<Long>,
    onToggleProfile: (Long) -> Unit,
    onCreateProfile: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (name: String, notes: String) -> Unit,
) {
    var name by remember { mutableStateOf(route.name) }
    var notes by remember { mutableStateOf(route.notes ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit route") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") })
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
            TextButton(onClick = { onSave(name.ifBlank { route.name }, notes) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))

/** Lifetime Prefer-filter coverage for one student across every generated
 * route ever saved for them -- see the "covered" definition where this is
 * computed, in [RouteListScreen] above. [hasAnyData] is false when this
 * student has no generated-trip routes at all yet (covered/notYetCovered are
 * still populated in that case -- covered empty, notYetCovered every category
 * -- but the UI shows a plain "nothing saved yet" line instead). */
private data class FilterCoverage(
    val studentName: String,
    val hasAnyData: Boolean,
    val covered: List<String>,
    val notYetCovered: List<String>,
)
