package com.instructor.lessonroutes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.data.AppDatabase
import com.instructor.lessonroutes.data.Route
import com.instructor.lessonroutes.data.RoutePoint
import com.instructor.lessonroutes.ui.map.RouteMapView
import com.instructor.lessonroutes.ui.theme.LessonRoutesTheme
import kotlinx.coroutines.flow.first
import org.maplibre.android.geometry.LatLng

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getInstance(applicationContext)
        setContent {
            LessonRoutesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(database = database)
                }
            }
        }
    }
}

/**
 * Steps 1–3 staging ground: a full-screen map plus a status banner. This whole
 * composable is temporary scaffolding — it gets replaced by real navigation between
 * the route list / create / detail / follow screens starting at step 4.
 */
@Composable
private fun MainScreen(database: AppDatabase) {
    var status by remember { mutableStateOf("Checking Room…") }
    var routePoints by remember { mutableStateOf<List<LatLng>>(emptyList()) }

    LaunchedEffect(Unit) {
        val dao = database.routeDao()
        val existingRoutes = dao.getAllRoutes().first()
        val routeId = if (existingRoutes.isEmpty()) {
            val id = dao.insertRoute(
                Route(
                    name = "Seed test route",
                    description = "Inserted by step 2 to prove Room reads/writes work",
                    notes = "Safe to delete once step 4 replaces this with real routes",
                    dateCreated = System.currentTimeMillis(),
                    tag = "test",
                ),
            )
            dao.insertPoints(
                listOf(
                    RoutePoint(routeId = id, latitude = -33.8688, longitude = 151.2093, sequenceOrder = 0),
                    RoutePoint(routeId = id, latitude = -33.8700, longitude = 151.2140, sequenceOrder = 1),
                    RoutePoint(routeId = id, latitude = -33.8735, longitude = 151.2110, sequenceOrder = 2),
                ),
            )
            id
        } else {
            existingRoutes.first().id
        }

        val routeWithPoints = dao.getRouteWithPoints(routeId).first()
        if (routeWithPoints != null) {
            status = "Room OK — \"${routeWithPoints.route.name}\" with ${routeWithPoints.points.size} points"
            routePoints = routeWithPoints.points
                .sortedBy { it.sequenceOrder }
                .map { LatLng(it.latitude, it.longitude) }
        } else {
            status = "Room read-back failed"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RouteMapView(modifier = Modifier.fillMaxSize(), routePoints = routePoints)
        Surface(
            modifier = Modifier.padding(16.dp),
            tonalElevation = 4.dp,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(text = status, modifier = Modifier.padding(8.dp))
        }
    }
}
