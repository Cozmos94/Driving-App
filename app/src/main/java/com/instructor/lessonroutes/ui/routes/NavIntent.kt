package com.instructor.lessonroutes.ui.routes

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.maplibre.android.geometry.LatLng
import kotlin.math.cos
import kotlin.math.hypot

/** Google Maps caps the "Get Directions" URL API at a handful of waypoints --
 * comfortably under any documented limit, and more than enough to shape the
 * computed route. */
private const val MAX_NAV_WAYPOINTS = 8

/**
 * No SDK, no cost. Opens Google Maps' "Get Directions" URL
 * (https://developers.google.com/maps/documentation/urls/get-started -- free, no
 * key) with [routePoints]'s last point as the destination and up to 8 points
 * along the route -- evenly spaced by *distance*, not by index, see
 * [sampleWaypoints] -- as waypoints, so Maps' own computed driving directions
 * track the given route much more closely than handing it a single destination
 * pin would (a single pin gives Maps nothing to shape its path around -- real
 * usage showed its route could look completely different from the one planned,
 * e.g. substituting a long detour through a totally different area). Still not
 * exact fidelity -- Maps computes its own turn-by-turn path between waypoints,
 * it doesn't replay the given points -- that would need a paid turn-by-turn SDK.
 * Falls back to a plain https intent (whatever handles it) if Google Maps isn't
 * installed. Shared by RouteDetailScreen (a saved route) and GenerateRouteScreen
 * (a freshly generated one) -- both just need "here's a list of points, navigate
 * through them."
 */
fun openInNavApp(context: Context, routePoints: List<LatLng>) {
    if (routePoints.isEmpty()) return
    val destination = routePoints.last()
    val waypoints = sampleWaypoints(routePoints.dropLast(1), MAX_NAV_WAYPOINTS)
    val waypointsParam = if (waypoints.isNotEmpty()) {
        "&waypoints=" + waypoints.joinToString("|") { "${it.latitude},${it.longitude}" }
    } else {
        ""
    }
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1" +
            "&destination=${destination.latitude},${destination.longitude}" +
            waypointsParam +
            "&travelmode=driving",
    )
    val googleMapsIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
    val fallbackIntent = Intent(Intent.ACTION_VIEW, uri)
    val intent = if (googleMapsIntent.resolveActivity(context.packageManager) != null) {
        googleMapsIntent
    } else {
        fallbackIntent
    }
    if (intent.resolveActivity(context.packageManager) != null) {
        context.startActivity(intent)
    }
}

/**
 * Picks up to [maxPoints] points evenly spaced by *distance* along [points], not
 * by index. An OSRM/GPS polyline isn't uniformly spaced -- far more vertices on
 * curvy roads, far fewer on a long straight stretch (a highway run, say) -- so
 * index-based sampling could leave a long straight section with zero waypoints
 * at all, handing Google Maps complete freedom to substitute a totally different
 * path through that gap. This is a likely real cause of a generated/saved route
 * looking "completely different" once opened in Maps, especially for a loop
 * route (there-and-back) where the waypoint budget has to cover both legs, so
 * gaps matter twice as much.
 */
private fun sampleWaypoints(points: List<LatLng>, maxPoints: Int): List<LatLng> {
    if (points.size <= maxPoints) return points

    val cumulativeMeters = DoubleArray(points.size)
    for (i in 1 until points.size) {
        cumulativeMeters[i] = cumulativeMeters[i - 1] + approxDistanceMeters(points[i - 1], points[i])
    }
    val totalMeters = cumulativeMeters.last()
    if (totalMeters <= 0.0) return points.take(maxPoints)

    val result = mutableListOf<LatLng>()
    var searchIndex = 0
    for (i in 0 until maxPoints) {
        // i=0 -> distance 0 (first point), i=maxPoints-1 -> totalMeters (last
        // point) -- inclusive even spacing across the whole route.
        val targetMeters = totalMeters * i / (maxPoints - 1)
        while (searchIndex < cumulativeMeters.size - 1 && cumulativeMeters[searchIndex] < targetMeters) searchIndex++
        result.add(points[searchIndex])
    }
    return result.distinct()
}

/** Equirectangular approximation -- fine at this scale, same approach used
 * elsewhere in this app (OverpassApi.kt, RouteGenerator.kt). */
private fun approxDistanceMeters(a: LatLng, b: LatLng): Double {
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(a.latitude))
    val dx = (b.longitude - a.longitude) * metersPerDegreeLon
    val dy = (b.latitude - a.latitude) * metersPerDegreeLat
    return hypot(dx, dy)
}
