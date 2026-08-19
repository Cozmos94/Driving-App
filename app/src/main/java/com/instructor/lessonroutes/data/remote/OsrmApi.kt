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

private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/**
 * Snaps a sparse list of tapped waypoints to the real road network via OSRM's free
 * public routing server -- no key, no account, same "free shared community
 * service, best effort" posture as the Overpass API used elsewhere in this app
 * (see OverpassApi.kt). Returns a dense list of coordinates that follows actual
 * roads between each waypoint in order; throws on any failure so callers can fall
 * back to drawing straight lines between the original points instead.
 *
 * https://router.project-osrm.org is a public demo instance meant for light,
 * non-commercial use -- not a guaranteed-uptime production service, but more than
 * enough for this app's volume (an instructor tapping out a handful of routes). If
 * that ever stops being true, self-hosting OSRM or a paid routing API would be the
 * next step; this function's signature wouldn't need to change.
 */
suspend fun fetchRoadSnappedPath(waypoints: List<LatLng>): List<LatLng> {
    require(waypoints.size >= 2) { "Need at least 2 waypoints to route between" }

    return withContext(Dispatchers.IO) {
        val coords = waypoints.joinToString(";") { "${it.longitude},${it.latitude}" }
        val request = Request.Builder()
            .url("$OSRM_URL/$coords?overview=full&geometries=geojson")
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OSRM request failed: HTTP ${response.code}")
            response.body?.string() ?: throw IOException("OSRM returned an empty body")
        }

        val json = JSONObject(body)
        if (json.optString("code") != "Ok") {
            throw IOException("OSRM returned: ${json.optString("code", "unknown error")}")
        }

        val coordinates = json.getJSONArray("routes")
            .getJSONObject(0)
            .getJSONObject("geometry")
            .getJSONArray("coordinates")

        List(coordinates.length()) { i ->
            val pair = coordinates.getJSONArray(i)
            // GeoJSON coordinates are [lon, lat], the opposite order to LatLng.
            LatLng(pair.getDouble(1), pair.getDouble(0))
        }
    }
}
