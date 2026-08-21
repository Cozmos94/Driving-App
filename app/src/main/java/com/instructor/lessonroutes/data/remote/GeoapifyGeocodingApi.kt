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
 */
suspend fun searchAddress(query: String): List<GeocodeResult> {
    if (query.isBlank()) return emptyList()

    return withContext(Dispatchers.IO) {
        val url = "$GEOCODE_URL?text=${Uri.encode(query)}&filter=$NSW_RECT_FILTER&limit=5&format=json" +
            "&apiKey=${BuildConfig.GEOAPIFY_API_KEY}"
        val request = Request.Builder().url(url).build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Geoapify geocode search failed: HTTP ${response.code}")
            val body = response.body?.string() ?: return@use emptyList()
            parseResults(body)
        }
    }
}

private fun parseResults(json: String): List<GeocodeResult> {
    // format=json gives {"results":[...]}, not a bare array (unlike Nominatim's
    // response shape this replaced) -- confirmed live.
    val array = org.json.JSONObject(json).optJSONArray("results") ?: JSONArray()
    return (0 until array.length()).mapNotNull { i ->
        val item = array.optJSONObject(i) ?: return@mapNotNull null
        val label = item.optString("formatted").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        GeocodeResult(label = label, location = LatLng(item.getDouble("lat"), item.getDouble("lon")))
    }
}
