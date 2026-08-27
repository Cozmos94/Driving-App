package com.instructor.lessonroutes.data.remote

import android.util.Log
import com.instructor.lessonroutes.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "GeoapifyIsolineApi"
private const val ISOLINE_URL = "https://api.geoapify.com/v1/isoline"

/** A drive-reachable area boundary (Geoapify's Isoline API, `type=distance`,
 * confirmed live) -- [exteriorRings] are the outer boundary/boundaries of what's
 * actually reachable by road within the queried range (normally just one, but a
 * genuinely disconnected road network could produce more than one blob).
 * [holeRings] are real gaps *inside* that boundary that genuinely aren't
 * reachable (a harbour, a park with no through-road, etc.) -- parsed and kept
 * for completeness, but deliberately NOT used by [contains] any more (see its
 * own doc comment for the real, confirmed bug that caused).
 *
 * Ring points are decimated (see [DECIMATION_STRIDE]) -- Geoapify's raw response
 * carries far more precision than placing a handful of petal waypoints needs
 * (confirmed live: a single 35km-radius exterior ring came back with ~27,000
 * points). Full precision would cost real CPU for [contains]/
 * [RouteGenerator.maxReachableDistanceKm]'s binary search with no practical
 * benefit at the petal-placement scale this is used for. */
data class ReachableArea(val exteriorRings: List<List<LatLng>>, val holeRings: List<List<LatLng>>)

// Confirmed live: even a 35km-radius isoline query answered in ~4s, a 15km one
// in ~2s (both against the real API, real key). 12s leaves real margin above
// that without letting a genuinely stuck request eat too much of the overall
// 30s generation ceiling (RouteGenerator.kt falls back to its pre-existing
// blind-guess+retry behaviour on any failure here, so a slow/failed isoline
// fetch degrades gracefully rather than failing the whole generation).
private val client = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .callTimeout(12, TimeUnit.SECONDS)
    .build()

// Keeps ring sizes down to a low-thousands range regardless of how dense
// Geoapify's raw response is -- confirmed live that stride 15 against a
// ~27,000-point ring still leaves a plenty-dense (~1,800 point) boundary for
// petal-placement purposes, which only need to resolve reachability to
// roughly petal-spacing precision, not the isoline's own native resolution.
private const val DECIMATION_STRIDE = 15

/**
 * Fetches the real drive-reachable area within [rangeKm] of [center] --
 * confirmed live against the real Geoapify API (both a 35km and a 15km test
 * range returned real MultiPolygon boundaries in well under this client's own
 * timeout). This is what [com.instructor.lessonroutes.data.routegen.
 * RouteGenerator] uses to place petal waypoints on ground that's actually
 * reachable by road, instead of a blind bearing+radius geometric guess that can
 * land in the ocean or up an escarpment with no nearby road at all -- Geoapify's
 * *routing* API then rejects that outright with "No suitable edges near
 * location" (confirmed live, the actual root cause of most of this generator's
 * unroutable-petal retries -- see RouteGenerator.kt's own history of
 * shrink+rotate retry logic for that failure mode).
 *
 * Returns null on any failure (bad response, timeout, network error, or a
 * response with no usable polygon) rather than throwing -- this is a
 * reliability *enhancement* over blind guessing, not a new hard dependency, so
 * callers are expected to fall back to the old behaviour rather than treat a
 * null here as fatal to the whole generation.
 */
suspend fun fetchReachableArea(center: LatLng, rangeKm: Double): ReachableArea? = withContext(Dispatchers.IO) {
    try {
        val rangeMeters = (rangeKm * 1000.0).toInt().coerceAtLeast(1)
        val url = "$ISOLINE_URL?lat=${center.latitude}&lon=${center.longitude}" +
            "&type=distance&mode=drive&range=$rangeMeters&apiKey=${BuildConfig.GEOAPIFY_API_KEY}"
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.w(LOG_TAG, "Isoline fetch failed: HTTP ${response.code} ${response.body?.string()}")
                return@withContext null
            }
            val body = response.body?.string()
            if (body == null) {
                Log.w(LOG_TAG, "Isoline fetch returned an empty body")
                return@withContext null
            }
            parseIsoline(body)
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Isoline fetch failed", e)
        null
    }
}

private fun parseIsoline(json: String): ReachableArea? {
    val features = JSONObject(json).optJSONArray("features") ?: return null
    val exteriorRings = mutableListOf<List<LatLng>>()
    val holeRings = mutableListOf<List<LatLng>>()
    for (f in 0 until features.length()) {
        val geometry = features.optJSONObject(f)?.optJSONObject("geometry") ?: continue
        val coordinates = geometry.optJSONArray("coordinates") ?: continue
        when (geometry.optString("type")) {
            "Polygon" -> parsePolygon(coordinates, exteriorRings, holeRings)
            "MultiPolygon" -> for (p in 0 until coordinates.length()) {
                coordinates.optJSONArray(p)?.let { parsePolygon(it, exteriorRings, holeRings) }
            }
        }
    }
    if (exteriorRings.isEmpty()) return null
    return ReachableArea(exteriorRings, holeRings)
}

/** [polygonRings] is one GeoJSON polygon's own ring list -- ring 0 is the
 * exterior boundary, any further rings are holes within it (standard GeoJSON
 * Polygon structure). */
private fun parsePolygon(polygonRings: JSONArray, exteriorRings: MutableList<List<LatLng>>, holeRings: MutableList<List<LatLng>>) {
    for (r in 0 until polygonRings.length()) {
        val ring = parseRing(polygonRings.optJSONArray(r) ?: continue)
        if (ring.size < 3) continue
        if (r == 0) exteriorRings.add(ring) else holeRings.add(ring)
    }
}

private fun parseRing(ringCoords: JSONArray): List<LatLng> {
    val points = mutableListOf<LatLng>()
    var i = 0
    while (i < ringCoords.length()) {
        val pt = ringCoords.optJSONArray(i)
        // GeoJSON is [lon, lat], the reverse of LatLng's own (lat, lon) order.
        if (pt != null && pt.length() >= 2) points.add(LatLng(pt.getDouble(1), pt.getDouble(0)))
        i += DECIMATION_STRIDE
    }
    return points
}

/** Standard ray-casting point-in-polygon test against a single ring, treating
 * lon/lat as flat x/y -- fine at the scale this is used at, the same
 * approximation RouteGenerator.kt already uses throughout for distance/bearing
 * math. */
private fun isInsideRing(point: LatLng, ring: List<LatLng>): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val pi = ring[i]
        val pj = ring[j]
        if ((pi.latitude > point.latitude) != (pj.latitude > point.latitude)) {
            val crossingLongitude = (pj.longitude - pi.longitude) *
                (point.latitude - pi.latitude) / (pj.latitude - pi.latitude) + pi.longitude
            if (point.longitude < crossingLongitude) inside = !inside
        }
        j = i
    }
    return inside
}

/** True if [point] is inside at least one exterior boundary of this
 * [ReachableArea].
 *
 * Deliberately does NOT also exclude points inside [holeRings] -- confirmed
 * live to be a real, serious bug: a fixed-stride decimation pass (see
 * [DECIMATION_STRIDE]) applied to a hole ring near the query origin can
 * distort that hole's shape enough to wrongly swallow genuinely-reachable
 * ground, including (confirmed live, a real generation that failed every
 * single petal placement even after shrinking all the way down to 8km) the
 * *start point itself* -- geometrically guaranteed to be reachable, since
 * it's literally where the isoline was queried from. Excluding holes trades
 * a small precision cost (a petal can occasionally land inside a genuinely
 * unreachable enclave, e.g. a park with no through-road) for eliminating an
 * entire class of false-negative that poisoned every bearing at once --
 * asymmetric risk, since a false "reachable" here is still caught by the
 * real routing call afterward (and the existing shrink+rotate retry loop in
 * RouteGenerator.kt), while a false "unreachable" silently discards
 * perfectly good ground with nothing left to catch it. */
fun ReachableArea.contains(point: LatLng): Boolean = exteriorRings.any { isInsideRing(point, it) }
