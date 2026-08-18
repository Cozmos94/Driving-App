package com.instructor.lessonroutes.ui.routes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.data.Route
import com.instructor.lessonroutes.data.RouteDao
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteListScreen(dao: RouteDao, onRouteClick: (Long) -> Unit) {
    val routes by dao.getAllRoutes().collectAsState(initial = emptyList())

    Scaffold(topBar = { TopAppBar(title = { Text("My routes") }) }) { padding ->
        if (routes.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No routes yet. Record your first route to see it here.",
                    modifier = Modifier.padding(32.dp),
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(routes, key = { it.id }) { route ->
                    RouteRow(route = route, onClick = { onRouteClick(route.id) })
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun RouteRow(route: Route, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(route.name) },
        supportingContent = {
            val tagPrefix = route.tag?.let { "$it • " } ?: ""
            Text("$tagPrefix${formatDate(route.dateCreated)}")
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

private fun formatDate(epochMillis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(epochMillis))
