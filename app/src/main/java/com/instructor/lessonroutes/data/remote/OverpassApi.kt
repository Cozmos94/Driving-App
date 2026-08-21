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

// Was https://overpass.kumi.systems/api/interpreter -- confirmed live that this
// mirror is currently returning bare HTTP 500s for even a trivial sanity-check
// query, unrelated to anything in this app's own queries (a second independent
// mirror succeeded with the exact same query). Switched to the main/official
// instance, which needs an explicit Accept header or it 406s (also confirmed
// live) -- see [overpassHeaders].
private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
private const val SEARCH_RADIUS_METERS = 50

/** Overpass's main instance 406s a request with no Accept header at all --
 * confirmed live. A descriptive User-Agent is also just good etiquette for a
 * free shared community service (same posture as OsrmApi.kt/NominatimApi.kt
 * before they were replaced). */
private fun Request.Builder.overpassHeaders(): Request.Builder = this
    .header("Accept", "application/json")
    .header("User-Agent", "LessonRoutes/1.0 (personal NSW driving-instructor app)")

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
// Only used by matchRoadGeometry -- its own multi-station batched query is
// genuinely the heavy case this long a timeout was tuned for.
private val client = OkHttpClient.Builder()
    .callTimeout(150, TimeUnit.SECONDS)
    .readTimeout(150, TimeUnit.SECONDS)
    .build()

// Every other query in this file is one simple single-bbox request (their own
// [timeout:60] below is plenty) -- reusing the 150s client above for these would
// mean a slow-but-not-actually-hung Overpass response could leave a caller (e.g.
// the route generator, which needs to leave time for its own OSRM calls
// afterward within one overall deadline) waiting far longer than makes sense.
// Shares the same connection pool as `client` via newBuilder().
private val fastClient = client.newBuilder()
    .callTimeout(30, TimeUnit.SECONDS)
    .readTimeout(30, TimeUnit.SECONDS)
    .connectTimeout(15, TimeUnit.SECONDS)
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
            .overpassHeaders()
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

/** ~1.5km box around the center point, in degrees -- a fixed area around the
 * device's location rather than the actual visible map bounds, since following
 * camera pans with live re-queries would need hooking into map-idle events; a
 * reasonable simplification for now. */
private const val QUIET_ROADS_RADIUS_DEGREES = 0.015

/**
 * OSM `residential`/`living_street` roads near [center] -- the spec's own proxy for
 * "quiet roads suitable for beginners" (no free source of actual measured traffic
 * volume at street level exists, per the spec's own assessment). This is a
 * heuristic based on road classification, not measured traffic -- label it as such
 * wherever it's shown in the UI, per spec.
 */
suspend fun fetchQuietRoads(center: LatLng, radiusDegrees: Double = QUIET_ROADS_RADIUS_DEGREES): List<List<LatLng>> =
    fetchWaysByHighwayTag(center, radiusDegrees, "residential|living_street", "quiet-roads")

/**
 * Highway on/off-ramp connector roads (`motorway_link`/`trunk_link`) near
 * [center] -- the closest free proxy for "merging lanes" that exists. This is a
 * real approximation, not exact merge-lane data: OSM has no dedicated tag for a
 * merge lane, doesn't distinguish an on-ramp (merging in) from an off-ramp
 * (exiting) in one field, and doesn't tag ordinary lane-merges on non-highway
 * roads at all. Used only for the route generator's soft avoid/prefer scoring
 * (RouteGenerator.kt) -- there's no real "avoid merge lanes" routing constraint
 * available for free, same as hazards/school-zones/cameras/roundabouts.
 */
suspend fun fetchMergeLaneProxies(center: LatLng, radiusDegrees: Double = QUIET_ROADS_RADIUS_DEGREES): List<List<LatLng>> =
    fetchWaysByHighwayTag(center, radiusDegrees, "motorway_link|trunk_link", "merge-lane-proxy")

/**
 * Motorway/trunk roads near [center] -- used for the route generator's Highways
 * avoid/prefer scoring (RouteGenerator.kt) as a secondary tie-breaker. Highways
 * ->Avoid itself is now a real hard routing constraint (Geoapify's
 * `avoid=highways`, confirmed live against the API -- see GeoapifyRoutingApi.kt's
 * doc comment; an earlier OSRM-backed `exclude=motorway` attempt was rejected
 * outright by that server for every value). This data is still used for
 * Highways->Prefer, which has no real "prefer highways" constraint available.
 */
suspend fun fetchMajorRoads(center: LatLng, radiusDegrees: Double = QUIET_ROADS_RADIUS_DEGREES): List<List<LatLng>> =
    fetchWaysByHighwayTag(center, radiusDegrees, "motorway|trunk", "major-roads")

private suspend fun fetchWaysByHighwayTag(
    center: LatLng,
    radiusDegrees: Double,
    highwayTagPattern: String,
    label: String,
): List<List<LatLng>> {
    return withContext(Dispatchers.IO) {
        val south = center.latitude - radiusDegrees
        val north = center.latitude + radiusDegrees
        val west = center.longitude - radiusDegrees
        val east = center.longitude + radiusDegrees
        val query = """
            [out:json][timeout:60];
            way["highway"~"^($highwayTagPattern)${'$'}"]($south,$west,$north,$east);
            out geom;
        """.trimIndent()

        val request = Request.Builder()
            .url(OVERPASS_URL)
            .post(FormBody.Builder().add("data", query).build())
            .overpassHeaders()
            .build()

        fastClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Overpass $label request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return@use emptyList()
            parseWays(body)
        }
    }
}

/**
 * Roundabouts near [center]: proper roundabouts (`junction=roundabout` ways) plus
 * mini-roundabouts (`highway=mini_roundabout` nodes -- the small painted-circle
 * roundabouts common on local streets, just as relevant for a learner driver).
 * Each result is a "line" of one or more points (a mini-roundabout node comes
 * back as a single-point line) so the return type matches the other Overpass
 * helpers here. Used for the route generator's soft avoid/prefer scoring.
 */
suspend fun fetchRoundabouts(center: LatLng, radiusDegrees: Double = QUIET_ROADS_RADIUS_DEGREES): List<List<LatLng>> {
    return withContext(Dispatchers.IO) {
        val south = center.latitude - radiusDegrees
        val north = center.latitude + radiusDegrees
        val west = center.longitude - radiusDegrees
        val east = center.longitude + radiusDegrees
        val query = """
            [out:json][timeout:60];
            (
              way["junction"="roundabout"]($south,$west,$north,$east);
              node["highway"="mini_roundabout"]($south,$west,$north,$east);
            );
            out geom;
        """.trimIndent()

        val request = Request.Builder()
            .url(OVERPASS_URL)
            .post(FormBody.Builder().add("data", query).build())
            .overpassHeaders()
            .build()

        fastClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Overpass roundabouts request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return@use emptyList()
            parseWaysAndNodes(body)
        }
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

/** Like [parseWays], but also accepts standalone `node` elements (e.g.
 * mini-roundabouts, which OSM tags as a single point, not a way) -- each node
 * comes back as its own one-point "line" so callers get a uniform return shape
 * regardless of whether a feature was mapped as a way or a bare node. */
private fun parseWaysAndNodes(json: String): List<List<LatLng>> {
    val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
    val results = mutableListOf<List<LatLng>>()
    for (i in 0 until elements.length()) {
        val element = elements.getJSONObject(i)
        when (element.optString("type")) {
            "way" -> {
                val geometry = element.optJSONArray("geometry") ?: continue
                val wayPoints = (0 until geometry.length()).mapNotNull { j ->
                    val node = geometry.optJSONObject(j) ?: return@mapNotNull null
                    LatLng(node.getDouble("lat"), node.getDouble("lon"))
                }
                if (wayPoints.size >= 2) results.add(wayPoints)
            }
            "node" -> {
                if (element.has("lat") && element.has("lon")) {
                    results.add(listOf(LatLng(element.getDouble("lat"), element.getDouble("lon"))))
                }
            }
        }
    }
    return results
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
