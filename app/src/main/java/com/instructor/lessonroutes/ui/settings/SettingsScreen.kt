package com.instructor.lessonroutes.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.BuildConfig
import com.instructor.lessonroutes.data.RouteDao
import kotlinx.coroutines.launch

/**
 * Spec marks Settings as optional/last, and there isn't much genuine substance for
 * this specific app to configure (one tile style, one unit system -- Australia is
 * metric-only, no real toggle needed). What's actually useful: data attribution
 * (spec requires this if published) and a way to clear local data.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(routeDao: RouteDao, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    var cleared by remember { mutableStateOf(false) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("NSW Driving Instructor Route Planner", fontWeight = FontWeight.Bold)
            Text("Version ${BuildConfig.VERSION_NAME}")

            HorizontalDivider()

            Text("Data sources", fontWeight = FontWeight.Bold)
            Text(
                "Map tiles, address search, and route planning: © Geoapify, " +
                    "© OpenMapTiles, © OpenStreetMap contributors.\n" +
                    "Live hazards, traffic volume, school zones, speed cameras: " +
                    "Transport for NSW Open Data Hub.\n" +
                    "Quiet-road estimate: OpenStreetMap road classification (a heuristic, " +
                    "not measured traffic).",
            )

            HorizontalDivider()

            Text("Local data", fontWeight = FontWeight.Bold)
            Text("Everything is stored only on this device — no account, no cloud sync.")
            OutlinedButton(onClick = { showClearConfirm = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Clear all saved routes")
            }
            if (cleared) {
                Text("All saved routes cleared.")
            }

            HorizontalDivider()

            TextButton(onClick = onBack) { Text("Back") }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all saved routes?") },
            text = { Text("This deletes every saved route on this device. This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            routeDao.deleteAllRoutes()
                            cleared = true
                        }
                        showClearConfirm = false
                    },
                ) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") } },
        )
    }
}
