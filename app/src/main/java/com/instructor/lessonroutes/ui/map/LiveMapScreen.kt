package com.instructor.lessonroutes.ui.map

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.BuildConfig
import com.instructor.lessonroutes.data.remote.fetchOpenIncidents
import org.maplibre.android.geometry.LatLng

private const val LOG_TAG = "LiveMapScreen"

/**
 * The app's home screen: a live map centered on the device, with the hazards overlay
 * on by default (no toggle needed) rather than gated behind opening a saved route.
 * A button at the bottom moves into route planning (the list/create/detail flow).
 */
@Composable
fun LiveMapScreen(onPlanRouteClick: () -> Unit) {
    var hazards by remember { mutableStateOf<List<LatLng>>(emptyList()) }
    var hazardsError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (BuildConfig.TFNSW_API_KEY.isBlank()) return@LaunchedEffect
        try {
            hazards = fetchOpenIncidents(BuildConfig.TFNSW_API_KEY).map { LatLng(it.latitude, it.longitude) }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to fetch live hazards", e)
            hazardsError = "Couldn't load hazards right now"
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        RouteMapView(modifier = Modifier.fillMaxSize(), hazards = hazards)

        hazardsError?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                tonalElevation = 4.dp,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(text = message, modifier = Modifier.padding(8.dp))
            }
        }

        Button(
            onClick = onPlanRouteClick,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
        ) {
            Text("Plan a route")
        }
    }
}
