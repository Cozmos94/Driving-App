package com.instructor.lessonroutes.ui.routes

import android.content.Context
import android.content.Intent
import android.net.Uri
import org.maplibre.android.geometry.LatLng

/** Google Maps caps the "Get Directions" URL API at a handful of waypoints --
 * comfortably under any documented limit, and more than enough to shape the
 * computed route. */
private const val MAX_NAV_WAYPOINTS = 8

/**
 * No SDK, no cost. Opens Google Maps' "Get Directions" URL
 * (https://developers.google.com/maps/documentation/urls/get-started -- free, no
 * key) with [routePoints]'s last point as the destination and up to 8
 * evenly-sampled points along the route as waypoints, so Maps' own computed
 * driving directions track the given route much more closely than handing it a
 * single destination pin would (a single pin gives Maps nothing to shape its path
 * around -- real usage showed its route could look completely different from the
 * one planned). Still not exact fidelity -- Maps computes its own turn-by-turn
 * path between waypoints, it doesn't replay the given points -- that would need a
 * paid turn-by-turn SDK. Falls back to a plain https intent (whatever handles it)
 * if Google Maps isn't installed. Shared by RouteDetailScreen (a saved route) and
 * GenerateRouteScreen (a freshly generated one) -- both just need "here's a list
 * of points, navigate through them."
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

/** Evenly picks up to [maxPoints] points spanning [points] (by index stride), so a
 * long dense route (a GPS recording, or OSRM's road-following geometry) and a
 * short sparse one (hand-tapped) both degrade gracefully to a small, well-spread
 * waypoint set. */
private fun sampleWaypoints(points: List<LatLng>, maxPoints: Int): List<LatLng> {
    if (points.size <= maxPoints) return points
    val stride = points.size.toDouble() / maxPoints
    return (0 until maxPoints).map { i -> points[(i * stride).toInt()] }
}
