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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val database = AppDatabase.getInstance(applicationContext)
        setContent {
            LessonRoutesTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Step 1: just prove the map renders from a free tile source.
                        // Route list / create / follow screens replace this in later steps.
                        RouteMapView(modifier = Modifier.fillMaxSize())
                        RoomSeedCheck(database = database)
                    }
                }
            }
        }
    }
}

/**
 * Step 2 verification only: seeds one fake route (in Sydney, since the spec is now
 * NSW-scoped) on first run, then reads it back through the DAO and shows the result.
 * This whole composable goes away once step 4 wires up the real route list.
 */
@Composable
private fun RoomSeedCheck(database: AppDatabase) {
    var status by remember { mutableStateOf("Checking Room…") }

    LaunchedEffect(Unit) {
        val dao = database.routeDao()
        val existingRoutes = dao.getAllRoutes().first()
        val routeId = if (existingRoutes.isEmpty()) {
            val id = dao.insertRoute(
                Route(
                    name = "Seed test route",
                    description = "Inserted by step 2 to prove Room reads/writes work",
                    notes = "Safe to delete once step 3+ replaces this check",
                    dateCreated = System.currentTimeMillis(),
                    tag = "test",
                ),
            )
            dao.insertPoints(
                listOf(
                    RoutePoint(routeId = id, latitude = -33.8688, longitude = 151.2093, sequenceOrder = 0),
                    RoutePoint(routeId = id, latitude = -33.8700, longitude = 151.2140, sequenceOrder = 1),
                ),
            )
            id
        } else {
            existingRoutes.first().id
        }

        val routeWithPoints = dao.getRouteWithPoints(routeId).first()
        status = if (routeWithPoints != null) {
            "Room OK — \"${routeWithPoints.route.name}\" with ${routeWithPoints.points.size} points"
        } else {
            "Room read-back failed"
        }
    }

    Surface(
        modifier = Modifier.padding(16.dp),
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(text = status, modifier = Modifier.padding(8.dp))
    }
}
