package com.instructor.lessonroutes.ui.routes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.data.Route
import com.instructor.lessonroutes.data.RouteDao
import com.instructor.lessonroutes.data.RouteWithProfiles
import com.instructor.lessonroutes.data.StudentProfile
import com.instructor.lessonroutes.data.StudentProfileDao
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteListScreen(
    dao: RouteDao,
    profileDao: StudentProfileDao,
    onRouteClick: (Long) -> Unit,
    onCreateClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val routesWithProfiles by dao.getAllRoutesWithProfiles().collectAsState(initial = emptyList())
    val allProfiles by profileDao.getAllProfiles().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    // null = "All" -- no profile filter applied.
    var filterProfileId by remember { mutableStateOf<Long?>(null) }
    val visibleRoutes = if (filterProfileId == null) {
        routesWithProfiles
    } else {
        routesWithProfiles.filter { rwp -> rwp.profiles.any { it.id == filterProfileId } }
    }

    var actionTarget by remember { mutableStateOf<RouteWithProfiles?>(null) }
    var editTarget by remember { mutableStateOf<RouteWithProfiles?>(null) }
    var deleteTarget by remember { mutableStateOf<Route?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My routes") },
                actions = { TextButton(onClick = onSettingsClick) { Text("Settings") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = onCreateClick) { Text("+") } },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (allProfiles.isNotEmpty()) {
                LazyRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    item {
                        FilterChip(
                            selected = filterProfileId == null,
                            onClick = { filterProfileId = null },
                            label = { Text("All") },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                    items(allProfiles, key = { it.id }) { profile ->
                        FilterChip(
                            selected = filterProfileId == profile.id,
                            onClick = { filterProfileId = profile.id },
                            label = { Text(profile.name) },
                            modifier = Modifier.padding(end = 4.dp),
                        )
                    }
                }
                HorizontalDivider()
            }

            if (visibleRoutes.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (routesWithProfiles.isEmpty()) {
                            "No routes yet. Tap + to record your first route."
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
