package com.instructor.lessonroutes.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.data.StudentProfile
import com.instructor.lessonroutes.data.StudentProfileDao
import kotlinx.coroutines.launch

/**
 * The landing screen for "Plan a route": pick a student profile (or "All") to see
 * their routes. Searchable by name; "+" creates a new profile directly (separate
 * from the inline create-a-profile option in the save/edit route dialogs). The
 * bottom-left "Routes" button is a shortcut equivalent to tapping "All" -- lets the
 * instructor jump straight to the unfiltered route list without picking a profile.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudentProfilesScreen(
    profileDao: StudentProfileDao,
    onProfileClick: (Long) -> Unit,
    onAllRoutesClick: () -> Unit,
    onOverviewClick: () -> Unit,
) {
    val allProfiles by profileDao.getAllProfiles().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var searchQuery by remember { mutableStateOf("") }
    val filteredProfiles = allProfiles.filter { it.name.contains(searchQuery, ignoreCase = true) }

    var showCreateDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Student profiles") }) },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(onClick = onAllRoutesClick) { Text("Routes") }
                OutlinedButton(onClick = onOverviewClick) { Text("Overview") }
                FloatingActionButton(onClick = { showCreateDialog = true }) { Text("+") }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search students") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                item {
                    ListItem(
                        headlineContent = { Text("All") },
                        supportingContent = { Text("Every saved route") },
                        modifier = Modifier.clickable(onClick = onAllRoutesClick),
                    )
                    HorizontalDivider()
                }
                items(filteredProfiles, key = { it.id }) { profile ->
                    ListItem(
                        headlineContent = { Text(profile.name) },
                        modifier = Modifier.clickable { onProfileClick(profile.id) },
                    )
                    HorizontalDivider()
                }
                if (searchQuery.isNotBlank() && filteredProfiles.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                            Text(
                                text = "No students match \"$searchQuery\".",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                } else if (allProfiles.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(32.dp)) {
                            Text(
                                text = "No student profiles yet. Tap + to add one, " +
                                    "or tap Routes to see all saved routes.",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreateProfileDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                scope.launch {
                    profileDao.insertProfile(StudentProfile(name = name, dateCreated = System.currentTimeMillis()))
                }
                showCreateDialog = false
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateProfileDialog(onDismiss: () -> Unit, onConfirm: (name: String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New student profile") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
