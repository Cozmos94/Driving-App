package com.instructor.lessonroutes.data.remote

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.io.IOException

data class HighVolumeRoad(
    val stationKey: Long,
    val roadName: String,
    val latitude: Double,
    val longitude: Double,
    val year: Int,
    val trafficCount: Int,
    /** The real road segment matched via Overpass (see OverpassApi.kt) -- null if
     * no nearby road way was found (or Overpass matching wasn't attempted/failed),
     * in which case callers should fall back to a plain marker at the point. */
    val geometry: List<LatLng>? = null,
)

private const val TRAFFIC_VOLUME_URL = "https://api.transport.nsw.gov.au/v1/traffic_volume"

/** Vehicles/day threshold for "high volume" -- a commonly used cutoff for a busy
 * arterial road in Australian traffic planning. Easy to tune. */
private const val HIGH_VOLUME_THRESHOLD = 20000

private val client = OkHttpClient()

/**
 * Roads carrying more than [HIGH_VOLUME_THRESHOLD] vehicles/day, from TfNSW's
 * Traffic Volume Counts API -- a queryable SQL-over-HTTP API (CARTO-style), not a
 * fixed REST endpoint; the query itself does the filtering/joining. Confirmed
 * against real data before building this, not guessed.
 *
 * Dedupes to one row (the most recent year) per counting station -- the raw query
 * returns the same station multiple times across different years' summaries.
 */
suspend fun fetchHighVolumeRoads(apiKey: String): List<HighVolumeRoad> {
    if (apiKey.isBlank()) return emptyList()

    return withContext(Dispatchers.IO) {
        val query = """
            SELECT s.station_key, s.road_name, s.wgs84_latitude, s.wgs84_longitude, y.year, y.traffic_count
            FROM road_traffic_counts_yearly_summary y
            JOIN road_traffic_counts_station_reference s ON y.station_key = s.station_key
            WHERE y.classification_type = 'UNCLASSIFIED'
              AND y.cardinal_direction_name = 'BOTH'
              AND y.period = 'ALL DAYS'
              AND y.traffic_count > $HIGH_VOLUME_THRESHOLD
              AND s.publish = true
            ORDER BY y.traffic_count DESC
            LIMIT 1000
        """.trimIndent()

        val url = "$TRAFFIC_VOLUME_URL?format=json&q=${Uri.encode(query)}"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "apikey $apiKey")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Traffic volume request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return@use emptyList()
            parseHighVolumeRoads(body)
        }
    }
}

private fun parseHighVolumeRoads(json: String): List<HighVolumeRoad> {
    val rows = JSONObject(json).optJSONArray("rows") ?: return emptyList()
    val byStation = LinkedHashMap<Long, HighVolumeRoad>()
    for (i in 0 until rows.length()) {
        val row = rows.getJSONObject(i)
        val stationKey = row.optLong("station_key", -1L)
        if (stationKey == -1L) continue
        val year = row.optInt("year")
        val existing = byStation[stationKey]
        if (existing != null && existing.year >= year) continue // keep the most recent year per station
        byStation[stationKey] = HighVolumeRoad(
            stationKey = stationKey,
            roadName = row.optString("road_name").ifBlank { "Unnamed road" },
            latitude = row.getDouble("wgs84_latitude"),
            longitude = row.getDouble("wgs84_longitude"),
            year = year,
            trafficCount = row.optInt("traffic_count"),
        )
    }
    return byStation.values.toList()
}
