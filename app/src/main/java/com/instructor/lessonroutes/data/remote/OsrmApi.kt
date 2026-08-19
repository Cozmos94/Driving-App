package com.instructor.lessonroutes.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val OSRM_URL = "https://router.project-osrm.org/route/v1/driving"

// callTimeout bounds the *entire* request (connect + write + read combined) at
// 6s, tighter than connect/read alone would give -- a single call could
// otherwise take up to connectTimeout+readTimeout (20s) worst case, which on
// its own was already equal to the route generator's entire 20s overall budget
// (RouteGenerator.kt), leaving no room for the several sequential calls one
// bearing's refinement can need.
private val client = OkHttpClient.Builder()
    .callTimeout(6, TimeUnit.SECONDS)
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .build()

/** One routed path between a set of waypoints, in order: the actual road-following
 * geometry plus OSRM's own estimated duration/distance for it -- the duration is
 * what the timed-route generator (RouteGenerator.kt) uses as feedback to converge
 * on a target trip length. */
data class RoutedPath(
    val points: List<LatLng>,
    val durationSeconds: Double,
    val distanceMeters: Double,
)

/**
 * Routes through [waypoints] in order via OSRM's free public routing server -- no
 * key, no account, same "free shared community service, best effort" posture as
 * the Overpass API used elsewhere in this app (see OverpassApi.kt). Returns one
 * result normally, or a few alternatives if [alternatives] is true and OSRM finds
 * more than one reasonable path (not guaranteed -- falls back to just the one best
 * route if it doesn't).
 *
 * No `exclude=` param support -- this was tried for a real hard "avoid highways"
 * routing constraint, but confirmed directly against the live API that this
 * public demo server rejects the `exclude` parameter outright for every value
 * (`{"code":"InvalidValue","message":"Exclude flag combination is not
 * supported."}`), so every request using it failed 100% of the time. Highways
 * avoidance is soft proximity scoring now, same as everything else in
 * RouteGenerator.kt -- no free routing API this app uses supports a real
 * avoid-zone/avoid-class constraint.
 *
 * https://router.project-osrm.org is a public demo instance meant for light,
 * non-commercial use -- not a guaranteed-uptime production service, but more than
 * enough for this app's volume. If that ever stops being true, self-hosting OSRM
 * or a paid routing API would be the next step; this function's signature
 * wouldn't need to change.
 */
suspend fun fetchRoutedPaths(
    waypoints: List<LatLng>,
    alternatives: Boolean = false,
): List<RoutedPath> {
    require(waypoints.size >= 2) { "Need at least 2 waypoints to route between" }

    return withContext(Dispatchers.IO) {
        val coords = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val params = buildString {
            append("?overview=full&geometries=geojson")
            if (alternatives) append("&alternatives=true")
        }
        val request = Request.Builder().url("$OSRM_URL/$coords$params").build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OSRM request failed: HTTP ${response.code}")
            response.body?.string() ?: throw IOException("OSRM returned an empty body")
        }

        val json = JSONObject(body)
        if (json.optString("code") != "Ok") {
            throw IOException("OSRM returned: ${json.optString("code", "unknown error")}")
        }

        val routes = json.getJSONArray("routes")
        List(routes.length()) { i ->
            val route = routes.getJSONObject(i)
            val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = List(coordinates.length()) { j ->
                val pair = coordinates.getJSONArray(j)
                // GeoJSON coordinates are [lon, lat], the opposite order to LatLng.
                LatLng(pair.getDouble(1), pair.getDouble(0))
            }
            RoutedPath(
                points = points,
                durationSeconds = route.getDouble("duration"),
                distanceMeters = route.getDouble("distance"),
            )
        }
    }
}

/**
 * Snaps a sparse list of waypoints (e.g. tapped-to-place-a-route points) to the
 * real road network -- just the geometry, no duration needed. Thin wrapper over
 * [fetchRoutedPaths] for the tap-preview/display-snapping use (see
 * RoadSnappedRoute.kt, CreateRouteScreen.kt).
 */
suspend fun fetchRoadSnappedPath(waypoints: List<LatLng>): List<LatLng> =
    fetchRoutedPaths(waypoints).first().points
