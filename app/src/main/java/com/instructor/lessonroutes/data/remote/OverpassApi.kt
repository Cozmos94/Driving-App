package com.instructor.lessonroutes.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.cos
import kotlin.math.hypot

private const val OVERPASS_URL = "https://overpass.kumi.systems/api/interpreter"
private const val SEARCH_RADIUS_METERS = 50

// Real vehicle-carrying road types only -- a plain [highway] filter also matches
// footways/cycleways/paths/steps, which are common right next to a traffic-count
// station on a major road and would otherwise get matched instead of the road itself.
private val ROAD_HIGHWAY_TYPES = listOf(
    "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified",
    "residential", "living_street",
    "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
)

// Must exceed the query's own [timeout:120] below -- a shorter client timeout was
// killing the connection before Overpass even had the time we told it it had.
private val client = OkHttpClient.Builder()
    .callTimeout(150, TimeUnit.SECONDS)
    .readTimeout(150, TimeUnit.SECONDS)
    .build()

/**
 * Matches each point to the nearest real OpenStreetMap road ("way") within
 * [SEARCH_RADIUS_METERS], via the free/keyless Overpass API (confirmed against a
 * real high-volume station before building this -- a plain `[highway]` filter
 * matched a pedestrian cycleway instead of the actual road, hence the type
 * restriction). Returns each input point's matched way geometry, keyed by its
 * index in [points]; a point with no nearby match found is simply absent from the
 * result map, and callers should fall back to something else for it.
 *
 * Issues ONE combined query for all points rather than one per point, both for
 * Overpass's shared-resource etiquette and because matching afterwards in Kotlin
 * (nearest returned way per point) is just as correct as tagging each query clause.
 */
suspend fun matchRoadGeometry(points: List<LatLng>): Map<Int, List<LatLng>> {
    if (points.isEmpty()) return emptyMap()

    return withContext(Dispatchers.IO) {
        val highwayFilter = ROAD_HIGHWAY_TYPES.joinToString("|")
        val clauses = points.joinToString("\n") {
            "way(around:$SEARCH_RADIUS_METERS,${it.latitude},${it.longitude})[highway~\"^($highwayFilter)\$\"];"
        }
        // Every high-volume station gets its own `around` clause in one query, so
        // this can genuinely take a while with a large batch -- give the server (and
        // the client above) real headroom rather than tuning both down to "usually enough".
        val query = "[out:json][timeout:120];\n($clauses\n);\nout geom;"

        val request = Request.Builder()
            .url(OVERPASS_URL)
            .post(FormBody.Builder().add("data", query).build())
            .build()

        val ways = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Overpass request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return@use emptyList()
            parseWays(body)
        }

        if (ways.isEmpty()) return@withContext emptyMap()

        val result = mutableMapOf<Int, List<LatLng>>()
        points.forEachIndexed { index, point ->
            val nearest = ways.minByOrNull { way -> distanceToPolylineMeters(point, way) }
            if (nearest != null) result[index] = nearest
        }
        result
    }
}

private fun parseWays(json: String): List<List<LatLng>> {
    val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
    val ways = mutableListOf<List<LatLng>>()
    for (i in 0 until elements.length()) {
        val element = elements.getJSONObject(i)
        if (element.optString("type") != "way") continue
        val geometry = element.optJSONArray("geometry") ?: continue
        val wayPoints = (0 until geometry.length()).mapNotNull { j ->
            val node = geometry.optJSONObject(j) ?: return@mapNotNull null
            LatLng(node.getDouble("lat"), node.getDouble("lon"))
        }
        if (wayPoints.size >= 2) ways.add(wayPoints)
    }
    return ways
}

/**
 * Rough distance in meters from [point] to the nearest segment of [polyline], via
 * an equirectangular approximation -- fine at this scale (tens of meters), no need
 * for a full geodesic calculation.
 */
private fun distanceToPolylineMeters(point: LatLng, polyline: List<LatLng>): Double {
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(point.latitude))

    fun toMeters(p: LatLng) = (p.longitude * metersPerDegreeLon) to (p.latitude * metersPerDegreeLat)

    val (px, py) = toMeters(point)
    var minDistance = Double.MAX_VALUE
    for (i in 0 until polyline.size - 1) {
        val (ax, ay) = toMeters(polyline[i])
        val (bx, by) = toMeters(polyline[i + 1])
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0.0) {
            0.0
        } else {
            (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0.0, 1.0)
        }
        val distance = hypot(px - (ax + t * dx), py - (ay + t * dy))
        if (distance < minDistance) minDistance = distance
    }
    return minDistance
}
