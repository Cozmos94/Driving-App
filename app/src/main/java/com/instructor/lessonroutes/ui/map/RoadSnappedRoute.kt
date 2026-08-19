package com.instructor.lessonroutes.ui.map

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.instructor.lessonroutes.data.RoutePoint
import com.instructor.lessonroutes.data.remote.fetchRoadSnappedPath
import org.maplibre.android.geometry.LatLng

private const val LOG_TAG = "RoadSnappedRoute"

/**
 * The polyline to actually draw for a saved route. Tap-created routes (every point
 * has a null `timestamp` -- see RoutePoint's own doc) are a handful of hand-placed
 * points that would otherwise be joined by straight lines, which rarely matches
 * the real road; snapping them through OSRM (see OsrmApi.kt) turns them into a
 * path that follows the actual roads. Recorded routes (any point has a timestamp)
 * are already a dense real-GPS trail -- already looks like a road -- so they're
 * left exactly as recorded rather than run through a routing engine that could
 * reroute around a deliberate off-road/wrong-lane maneuver the instructor recorded
 * on purpose (e.g. practicing a driveway pull-in).
 *
 * Falls back to the original straight-line points if OSRM fails or hasn't
 * returned yet, so the map never shows nothing while a fetch is in flight.
 */
@Composable
fun rememberDisplayRoutePoints(points: List<RoutePoint>): List<LatLng> {
    val sortedPoints = remember(points) { points.sortedBy { it.sequenceOrder } }
    val rawLatLngs = remember(sortedPoints) { sortedPoints.map { LatLng(it.latitude, it.longitude) } }
    val isTapCreated = remember(sortedPoints) {
        sortedPoints.isNotEmpty() && sortedPoints.all { it.timestamp == null }
    }

    var snappedPath by remember(sortedPoints) { mutableStateOf<List<LatLng>?>(null) }
    LaunchedEffect(sortedPoints) {
        snappedPath = null
        if (isTapCreated && rawLatLngs.size >= 2) {
            snappedPath = try {
                fetchRoadSnappedPath(rawLatLngs)
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Road-snapping failed, falling back to straight lines", e)
                null
            }
        }
    }

    return snappedPath ?: rawLatLngs
}
