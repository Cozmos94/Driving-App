package com.instructor.lessonroutes.data.remote

import android.net.Uri
import com.instructor.lessonroutes.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val ROUTING_URL = "https://api.geoapify.com/v1/routing"

// Same reasoning as the OSRM client this replaced: callTimeout bounds the
// *entire* request (connect + write + read combined) at 6s, tighter than
// connect/read alone would give -- a single call could otherwise take up to
// connectTimeout+readTimeout worst case, which on its own could already equal
// the route generator's whole overall budget (RouteGenerator.kt), leaving no
// room for the several sequential calls one bearing's refinement can need.
private val client = OkHttpClient.Builder()
    .callTimeout(6, TimeUnit.SECONDS)
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .build()

/** One routed path between a set of waypoints, in order: the actual road-following
 * geometry plus Geoapify's own estimated duration/distance for it -- the duration
 * is what the timed-route generator (RouteGenerator.kt) uses as feedback to
 * converge on a target trip length. Same shape as the OSRM-backed version this
 * replaced, so RouteGenerator.kt/RoadSnappedRoute.kt/CreateRouteScreen.kt didn't
 * need any changes beyond the new avoid params below. */
data class RoutedPath(
    val points: List<LatLng>,
    val durationSeconds: Double,
    val distanceMeters: Double,
)

/**
 * Routes through [waypoints] in order via Geoapify's Routing API (needs
 * [BuildConfig.GEOAPIFY_API_KEY] -- see local.properties/README). Replaces the
 * previous OSRM-backed implementation, for two confirmed reasons (tested
 * directly against the live APIs, not assumed):
 *
 * 1. [avoidHighways]/[avoidTolls] are *real* hard routing constraints here --
 *    confirmed live: the same 3-waypoint test route jumped from 26.6km/28min to
 *    33.6km/37min with `avoid=highways` set, a genuine detour around motorways.
 *    OSRM's public demo server rejected an equivalent `exclude=motorway` outright
 *    for every value tried, so Highways->Avoid was only ever soft proximity
 *    scoring before (RouteGenerator.kt's `scoreRoute` still does that scoring too,
 *    as a secondary check/tie-breaker, and it's still the only lever for
 *    Highways->Prefer and for Roundabouts/Merging lanes, which have no `avoid=`
 *    equivalent here either).
 * 2. Address geocoding on the same account (GeoapifyGeocodingApi.kt) blends in
 *    the OpenAddresses dataset alongside OSM, giving noticeably better AU
 *    house-number coverage than Nominatim alone -- confirmed live against
 *    addresses that only resolved to street level on Nominatim.
 *
 * No alternatives support: confirmed directly against the live API that
 * Geoapify's Routing API has no documented (or empirically working) parameter
 * for requesting more than one route per call -- unlike OSRM's `alternatives=`
 * (which this app used for Highways/Roundabouts/Merging lanes to give scoring
 * more than one shape per bearing), there's no equivalent here. Removed rather
 * than guessed at.
 */
suspend fun fetchRoutedPaths(
    waypoints: List<LatLng>,
    avoidHighways: Boolean = false,
    avoidTolls: Boolean = false,
): List<RoutedPath> {
    require(waypoints.size >= 2) { "Need at least 2 waypoints to route between" }

    return withContext(Dispatchers.IO) {
        val waypointsParam = waypoints.joinToString("|") { "${it.latitude},${it.longitude}" }
        val avoidValues = listOfNotNull("highways".takeIf { avoidHighways }, "tolls".takeIf { avoidTolls })
        val avoidParam = if (avoidValues.isNotEmpty()) "&avoid=${avoidValues.joinToString("|")}" else ""
        val url = "$ROUTING_URL?waypoints=${Uri.encode(waypointsParam)}&mode=drive&units=metric&format=json" +
            "$avoidParam&apiKey=${BuildConfig.GEOAPIFY_API_KEY}"
        val request = Request.Builder().url(url).build()

        val body = client.newCall(request).execute().use { response ->
            // Was discarding the response body on failure -- a 400/422 from
            // Geoapify almost always carries the actual reason (bad waypoint,
            // duplicate/too-close points, unsupported avoid combination, etc.)
            // in that body, and "HTTP 400" alone gives zero way to tell which.
            // Confirmed as a real gap: a radius-confined multi-waypoint chain
            // started failing with bare "HTTP 400" and no way to diagnose why
            // without this.
            if (!response.isSuccessful) {
                val errorBody = response.body?.string()
                throw IOException("Geoapify routing request failed: HTTP ${response.code} -- $errorBody")
            }
            response.body?.string() ?: throw IOException("Geoapify routing returned an empty body")
        }

        val json = JSONObject(body)
        val results = json.optJSONArray("results")
            ?: throw IOException("Geoapify routing returned no results: ${json.optString("message", body)}")

        List(results.length()) { i ->
            val route = results.getJSONObject(i)
            // "geometry" is a MultiLineString-shaped array -- one line per leg
            // (one leg per gap between consecutive waypoints), each line an array
            // of {lat, lon} point objects. Concatenating every leg's line in
            // order gives the full route's point list; confirmed live that this
            // is a flat per-route array, not something requiring `legs[].steps[]`
            // index lookups (those are only for turn-by-turn instruction text,
            // which this app doesn't need).
            val geometryLines = route.getJSONArray("geometry")
            val points = (0 until geometryLines.length()).flatMap { lineIndex ->
                val line = geometryLines.getJSONArray(lineIndex)
                (0 until line.length()).map { pointIndex ->
                    val point = line.getJSONObject(pointIndex)
                    LatLng(point.getDouble("lat"), point.getDouble("lon"))
                }
            }
            RoutedPath(
                points = points,
                durationSeconds = route.getDouble("time"),
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
