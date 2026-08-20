package com.instructor.lessonroutes.data.remote

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.maplibre.android.geometry.LatLng
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GeocodeResult(val label: String, val location: LatLng)

private const val NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"

// Roughly covers NSW plus a margin (lon ~139-154.5, lat ~-27 to -38.5) -- a HARD
// restriction (bounded=1 below), not just a ranking bias. A soft bias
// (bounded=0) was tried first and confirmed as a real bug: with countrycodes=au
// but no hard geographic restriction, Nominatim can and did rank a same-named
// street/suburb in a completely different Australian state above the intended
// NSW result (e.g. "Campbelltown" also exists in South Australia) when the
// exact address wasn't well-matched -- silently sending the trip generator
// thousands of km in the wrong direction with no error. The margin beyond
// NSW's actual border keeps genuinely-near-border addresses resolving.
private const val NSW_VIEWBOX = "139.0,-27.0,154.5,-38.5"

private val client = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(10, TimeUnit.SECONDS)
    .build()

/**
 * Free, keyless address search via OpenStreetMap's Nominatim geocoder -- same
 * "free shared community service, best effort" posture as Overpass/OSRM elsewhere
 * in this app (see OverpassApi.kt, OsrmApi.kt). Nominatim's usage policy requires
 * a descriptive User-Agent (no key, but identify the app) and asks for roughly one
 * request per second at most -- fine for this app's light, one-search-at-a-time
 * usage. Throws on failure so callers can show "couldn't search right now".
 */
suspend fun searchAddress(query: String): List<GeocodeResult> {
    if (query.isBlank()) return emptyList()

    return withContext(Dispatchers.IO) {
        // dedupe=0: Nominatim's default deduplication has been seen to collapse a
        // more specific numbered-address result together with a less specific
        // street-level one, which can be why searching a full address like
        // "5 Mcdonell St, ..." sometimes only surfaces "Mcdonell St, ...". This
        // doesn't fix genuine OSM data gaps (a lot of AU house numbers simply
        // aren't mapped outside dense areas -- that's a real data-completeness
        // limit, not a query bug), but stops this app from making it worse.
        val url = "$NOMINATIM_URL?format=json&limit=5&countrycodes=au&dedupe=0" +
            "&viewbox=$NSW_VIEWBOX&bounded=1&q=${Uri.encode(query)}"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "LessonRoutes/1.0 (personal NSW driving-instructor app)")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Nominatim search failed: HTTP ${response.code}")
            val body = response.body?.string() ?: return@use emptyList()
            parseResults(body)
        }
    }
}

private fun parseResults(json: String): List<GeocodeResult> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val label = item.optString("display_name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        // Nominatim returns lat/lon as JSON strings, not numbers -- org.json's
        // getDouble() parses a numeric string fine, so this isn't a special case.
        GeocodeResult(label = label, location = LatLng(item.getDouble("lat"), item.getDouble("lon")))
    }
}
