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

// Roughly covers NSW (lon ~140.9-153.7, lat ~-28.1 to -37.6) -- a soft bias
// (bounded=0 below), not a hard restriction, since a destination just over the
// border should still resolve.
private const val NSW_VIEWBOX = "140.9,-28.1,153.7,-37.6"

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
        val url = "$NOMINATIM_URL?format=json&limit=5&countrycodes=au" +
            "&viewbox=$NSW_VIEWBOX&bounded=0&q=${Uri.encode(query)}"
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
