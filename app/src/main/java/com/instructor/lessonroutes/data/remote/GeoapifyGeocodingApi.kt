package com.instructor.lessonroutes.data.remote

import android.net.Uri
import com.instructor.lessonroutes.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.maplibre.android.geometry.LatLng
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class GeocodeResult(val label: String, val location: LatLng)

private const val GEOCODE_URL = "https://api.geoapify.com/v1/geocode/search"

// Roughly covers NSW plus a margin (lon ~139-154.5, lat ~-27 to -38.5) -- a HARD
// geographic restriction, not just a ranking bias, for the same reason this
// mattered on the previous (Nominatim-backed) implementation of this function:
// a same-named street/suburb in a different Australian state (e.g.
// "Campbelltown" also exists in South Australia) could otherwise outrank the
// intended NSW result when the exact address wasn't well-matched, silently
// sending the trip generator thousands of km in the wrong direction. Geoapify's
// `filter=rect:` takes (west,south,east,north) -- confirmed live, the docs'
// own examples were inconsistent about comma vs pipe separators here. The
// margin beyond NSW's actual border keeps genuinely-near-border addresses
// resolving.
private const val NSW_RECT_FILTER = "rect:139.0,-38.5,154.5,-27.0"

private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/**
 * Address search via Geoapify's Geocoding API (needs [BuildConfig.GEOAPIFY_API_KEY]
 * -- see local.properties/README). Replaces the previous Nominatim (OpenStreetMap)
 * -backed implementation: confirmed live that Geoapify blends in the OpenAddresses
 * dataset alongside OSM, resolving several AU addresses with real house numbers
 * that Nominatim could only match down to street level (e.g. "48 Queen Street,
 * Campbelltown" -- Nominatim had no house_number for it at all; Geoapify returned
 * "46-48 Queen Street" from its openaddresses source). Not a guarantee every
 * address resolves -- coverage gaps can still exist -- but a real, confirmed
 * improvement over OSM-only data for this specific complaint.
 *
 * @param biasLocation Soft-preferences results near this point (Geoapify's real
 * `bias=proximity:lon,lat`, confirmed live) -- a genuine, confirmed bug, not
 * theoretical: a live test of "1 evans st, wol" (a real Corey report -- typing
 * that far into "1 Evans St, Wollongong" showed nothing) returned 5 results,
 * *all* Victoria/Queensland, zero NSW -- every one discarded by the NSW-only
 * filter below, leaving the search looking empty despite the correct NSW match
 * existing (it just ranked outside Geoapify's own top 5 for that short,
 * genuinely ambiguous prefix without more context). The same query with
 * [biasLocation] set to a real NSW point came back with all 5 results as NSW,
 * the correct Wollongong address first -- confirmed live, not assumed.
 */
suspend fun searchAddress(query: String, biasLocation: LatLng? = null): List<GeocodeResult> {
    if (query.isBlank()) return emptyList()

    return withContext(Dispatchers.IO) {
        val biasParam = biasLocation?.let { "&bias=proximity:${it.longitude},${it.latitude}" } ?: ""
        val url = "$GEOCODE_URL?text=${Uri.encode(query)}&filter=$NSW_RECT_FILTER$biasParam&limit=5&format=json" +
            "&apiKey=${BuildConfig.GEOAPIFY_API_KEY}"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Geoapify geocode search failed: HTTP ${response.code}")
            val body = response.body?.string() ?: return@use emptyList()
            val results = parseResults(body)
            // Geoapify's own bias=proximity (above) is a soft relevance nudge,
            // blended with text-match quality -- confirmed live it can still
            // rank a strongly-matching far-away result (e.g. a Sydney street)
            // ahead of a more loosely-matching nearby one (e.g. the same street
            // name in Dapto), which is a real Corey report, not theoretical.
            // Re-sorting by actual straight-line distance here guarantees the
            // nearest match always comes first regardless of how Geoapify
            // itself weighted relevance.
            if (biasLocation != null) {
                results.sortedBy { distanceMeters(biasLocation, it.location) }
            } else {
                results
            }
        }
    }
}

// Straight-line (Haversine) distance in meters. Good enough for ranking a
// handful of nearby-ish geocode results -- not meant for anything requiring
// true geodesic precision.
private fun distanceMeters(a: LatLng, b: LatLng): Double {
    val earthRadiusMeters = 6_371_000.0
    val lat1 = Math.toRadians(a.latitude)
    val lat2 = Math.toRadians(b.latitude)
    val deltaLat = Math.toRadians(b.latitude - a.latitude)
    val deltaLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(deltaLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(deltaLon / 2).pow(2)
    return 2 * earthRadiusMeters * asin(sqrt(h))
}

// NSW_RECT_FILTER above only bounds a lat/lon rectangle -- it's a real box,
// not NSW's actual state border, so it still lets through genuine
// Victoria/ACT/South Australia/Queensland addresses that happen to fall
// inside that rectangle (confirmed live: e.g. "Wodonga VIC" -- just across
// the Murray from Albury NSW -- geocodes fine and sits well inside the box).
// Geoapify's own response carries a real state_code field per result
// (confirmed live: "NSW" for a real NSW address, "VIC" for Wodonga) --
// filtering on that is an actual state-boundary check, not another
// approximate box, so this is the real fix for "restrict to NSW addresses
// only" rather than the rect filter's rough margin.
private const val NSW_STATE_CODE = "NSW"

private fun parseResults(json: String): List<GeocodeResult> {
    // format=json gives {"results":[...]}, not a bare array (unlike Nominatim's
    // response shape this replaced) -- confirmed live.
    val array = org.json.JSONObject(json).optJSONArray("results") ?: JSONArray()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        // Excludes a result with no state_code at all, not just a wrong one --
        // can't confirm it's actually NSW either way, and "NSW addresses only"
        // means erring toward excluding an unconfirmed result over letting a
        // possibly-interstate one through.
        if (!item.optString("state_code").equals(NSW_STATE_CODE, ignoreCase = true)) return@mapNotNull null
        val label = item.optString("formatted").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        GeocodeResult(label = label, location = LatLng(item.getDouble("lat"), item.getDouble("lon")))
    }
}
