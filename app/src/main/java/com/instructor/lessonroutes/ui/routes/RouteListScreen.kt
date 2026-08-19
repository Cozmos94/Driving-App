package com.instructor.lessonroutes.ui.routes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteListScreen(
    dao: RouteDao,
    onRouteClick: (Long) -> Unit,
    onCreateClick: () -> Unit,
    onSettingsClick: () -> Unit,
) {
    val routes by dao.getAllRoutes().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()

    var actionTarget by remember { mutableStateOf<Route?>(null) }
    var editTarget by remember { mutableStateOf<Route?>(null) }
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
        if (routes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No routes yet. Tap + to record your first route.",
                    modifier = Modifier.padding(32.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(routes, key = { it.id }) { route ->
                    RouteRow(
                        route = route,
                        onClick = { onRouteClick(route.id) },
                        onLongClick = { actionTarget = route },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    actionTarget?.let { route ->
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(route.name) },
            text = { Text("What would you like to do?") },
            confirmButton = {
                TextButton(onClick = { editTarget = route; actionTarget = null }) { Text("Edit") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = route; actionTarget = null }) { Text("Delete") }
            },
        )
    }

    editTarget?.let { route ->
        EditRouteDialog(
            route = route,
            onDismiss = { editTarget = null },
            onSave = { name, notes ->
                scope.launch { dao.updateRoute(route.copy(name = name, notes = notes.ifBlank { null })) }
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
private fun RouteRow(route: Route, onClick: () -> Unit, onLongClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(route.name) },
        supportingContent = {
            val tagPrefix = route.tag?.let { "$it • " } ?: ""
            Text("$tagPrefix${formatDate(route.dateCreated)}")
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun EditRouteDialog(route: Route, onDismiss: () -> Unit, onSave: (name: String, notes: String) -> Unit) {
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
