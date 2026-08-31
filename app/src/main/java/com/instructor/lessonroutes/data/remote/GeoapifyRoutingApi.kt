package com.instructor.lessonroutes.data.remote

import android.net.Uri
import android.util.Log
import com.instructor.lessonroutes.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.IOException
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "GeoapifyRoutingApi"
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

// Genuine, live-verified fallback for a Geoapify *outage* specifically --
// see fetchRoutedPaths' own doc comment for why this is scoped narrowly
// (not used for every routing failure, only ones that look like Geoapify's
// own infrastructure having trouble). Confirmed live: a real 3-waypoint
// chain near Wollongong routed successfully via OSRM's public demo server
// in ~2-3s with a dense (900+ point), usable geometry.
private const val OSRM_URL = "https://router.project-osrm.org/route/v1/driving"
private val osrmClient = OkHttpClient.Builder()
    .callTimeout(8, TimeUnit.SECONDS)
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(8, TimeUnit.SECONDS)
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

/** Thrown specifically for a genuine routing rejection *from Geoapify itself*
 * -- a 4xx response, almost always meaning "this waypoint/combination
 * genuinely has no route" (Geoapify's own real example: "No suitable edges
 * near location") -- as opposed to every other kind of failure (a network
 * error, a timeout, a 5xx, a malformed/empty response), which look like
 * Geoapify's own infrastructure having trouble rather than a real answer
 * about the waypoints themselves. [fetchRoutedPaths] only falls back to OSRM
 * for the latter category -- see its own doc comment for why a 4xx
 * specifically should never trigger that fallback. */
private class GeoapifyRoutingRejected(message: String) : IOException(message)

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
 *
 * A real `avoid=location:lat,lon` hard-exclusion constraint exists too
 * (confirmed live). It was briefly used broadly here -- hard-avoiding every
 * roundabout in the whole search area up front, during the exploratory
 * petal-placement phase, before any chain was even known to route at all --
 * and reverted after two real regressions (a generated route reported as 0
 * minutes, then no route at all): excluding a real point works fine when
 * there's plenty of alternate road network around it, but if that point is
 * the only real way through a tight local area (Wollongong's coastline+
 * escarpment geography is an already-documented hard case for this exact
 * generator), hard-excluding it during exploratory search can make the whole
 * area unroutable rather than just detouring around it.
 *
 * [avoidLocations] (new, narrower use) is safe against that same failure
 * mode by construction: RouteGenerator.kt's `rerouteAvoidingHits`
 * only ever calls this with points from a route that's *already* known to
 * route successfully, and only ever adopts the result if it also succeeds
 * with a still-reasonable duration -- any failure or a materially worse
 * result falls straight back to the original, already-good route, never
 * replacing a working result with a broken one. Confirmed live before
 * building this: avoiding a handful of real points along an
 * already-successful route produces a small, sane detour; avoiding a point
 * that's immaterial to the route (right at an endpoint) is a harmless no-op;
 * avoiding *many* points along the same corridor can swing the result a lot
 * (one stress test: 15 points along one route's own path produced a
 * genuinely different, shorter route, not just a tweak) -- which is exactly
 * why this is only ever fed the small, specific set of points a route
 * actually hits, not a whole category's full dataset.
 *
 * **Real, live-verified fallback added for redundancy (Corey: "we need more
 * redundancies")**: on any failure that looks like Geoapify's own
 * infrastructure having trouble -- a network error, a timeout, a 5xx, an
 * empty/malformed response -- this now retries the same request against
 * OSRM's free public routing server instead of failing the whole
 * generation outright. Deliberately does NOT fall back for a plain 4xx
 * rejection (`GeoapifyRoutingRejected`): that's Geoapify correctly saying
 * "no route exists for this waypoint", and OSRM would almost certainly
 * reject the same waypoint for the same real geographic reason -- trying it
 * would just add latency to a failure RouteGenerator.kt's own shrink+rotate
 * retry logic already expects and handles as part of normal operation, not
 * an outage. The fallback route can't honor [avoidHighways]/[avoidTolls] as
 * a hard constraint (OSRM's public server rejects the equivalent `exclude`
 * parameter outright, confirmed live and already documented above) -- it
 * degrades to the same soft-scoring-only behaviour Highways had before
 * Geoapify was adopted, only while this fallback is actually in use.
 */
suspend fun fetchRoutedPaths(
    waypoints: List<LatLng>,
    avoidHighways: Boolean = false,
    avoidTolls: Boolean = false,
    avoidLocations: List<LatLng> = emptyList(),
): List<RoutedPath> {
    require(waypoints.size >= 2) { "Need at least 2 waypoints to route between" }

    return try {
        fetchGeoapifyRoutedPaths(waypoints, avoidHighways, avoidTolls, avoidLocations)
    } catch (e: GeoapifyRoutingRejected) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Geoapify routing looks like an outage (not a plain rejection) -- falling back to OSRM", e)
        fetchOsrmRoutedPaths(waypoints, avoidHighways, avoidTolls, avoidLocations)
    }
}

private suspend fun fetchGeoapifyRoutedPaths(
    waypoints: List<LatLng>,
    avoidHighways: Boolean,
    avoidTolls: Boolean,
    avoidLocations: List<LatLng>,
): List<RoutedPath> {
    return withContext(Dispatchers.IO) {
        val waypointsParam = waypoints.joinToString("|") { "${it.latitude},${it.longitude}" }
        val avoidValues = listOfNotNull("highways".takeIf { avoidHighways }, "tolls".takeIf { avoidTolls }) +
            avoidLocations.map { "location:${it.latitude},${it.longitude}" }
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
                val message = "Geoapify routing request failed: HTTP ${response.code} -- $errorBody"
                // 4xx = Geoapify's own real answer about these waypoints (see
                // GeoapifyRoutingRejected's own doc comment) -- 5xx or
                // anything else falls through to the plain IOException below,
                // which fetchRoutedPaths treats as outage-like and falls back
                // to OSRM for.
                if (response.code in 400..499) throw GeoapifyRoutingRejected(message)
                throw IOException(message)
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

/** OSRM's public demo server, used only as [fetchRoutedPaths]' own outage
 * fallback -- see its doc comment. Response shape confirmed live: GeoJSON
 * geometry (`routes[].geometry.coordinates`, `[lon, lat]` pairs, the reverse
 * of LatLng's own order) plus plain `duration`/`distance` fields, no
 * `legs[]` indexing needed for this app's purposes (same simplification
 * Geoapify's own parsing above already makes). */
private suspend fun fetchOsrmRoutedPaths(
    waypoints: List<LatLng>,
    avoidHighways: Boolean,
    avoidTolls: Boolean,
    avoidLocations: List<LatLng>,
): List<RoutedPath> {
    if (avoidHighways || avoidTolls) {
        Log.w(
            LOG_TAG,
            "OSRM fallback can't honor avoid=highways/tolls as a hard constraint (its public server " +
                "rejects the equivalent exclude parameter outright, confirmed live) -- this fallback route " +
                "may include a highway/toll road that would normally have been excluded; RouteGenerator's " +
                "own soft proximity scoring is the only thing still steering away from it while this " +
                "fallback is in use.",
        )
    }
    if (avoidLocations.isNotEmpty()) {
        Log.w(
            LOG_TAG,
            "OSRM fallback has no equivalent to Geoapify's avoid=location -- this fallback route may still " +
                "pass through ${avoidLocations.size} point(s) a caller specifically asked to avoid.",
        )
    }
    return withContext(Dispatchers.IO) {
        val coordinates = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val url = "$OSRM_URL/${Uri.encode(coordinates, ";,.-")}?overview=full&geometries=geojson"
        val request = Request.Builder().url(url).build()

        val body = osrmClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("OSRM fallback routing request failed: HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("OSRM fallback routing returned an empty body")
        }

        val json = JSONObject(body)
        if (json.optString("code") != "Ok") {
            throw IOException("OSRM fallback routing returned no route: ${json.optString("message", body)}")
        }
        val routes = json.getJSONArray("routes")
        List(routes.length()) { i ->
            val route = routes.getJSONObject(i)
            val coords = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = (0 until coords.length()).map { j ->
                val pair = coords.getJSONArray(j)
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
