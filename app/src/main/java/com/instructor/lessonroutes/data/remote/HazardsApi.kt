package com.instructor.lessonroutes.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

data class Hazard(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val advice: String?,
)

private const val INCIDENT_OPEN_URL = "https://api.transport.nsw.gov.au/v1/live/hazards/incident-open.json"

private val client = OkHttpClient()

/**
 * Phase 2 step 9 (overlay foundation): the simplest live feed to prove the pipe
 * works end to end -- currently open (active) traffic incidents. No caching, per
 * spec ("live datasets... fetch fresh per session; do not persist").
 *
 * Returns an empty list if [apiKey] is blank so Phase 2 features degrade gracefully
 * with no key configured, rather than firing a request guaranteed to fail auth.
 * Throws on a network/HTTP/parse failure -- callers should catch and treat that as
 * "couldn't load hazards right now" rather than crashing.
 */
suspend fun fetchOpenIncidents(apiKey: String): List<Hazard> {
    if (apiKey.isBlank()) return emptyList()

    return withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(INCIDENT_OPEN_URL)
            // Literal word "apikey", then a space, then the token -- per TfNSW Open
            // Data Hub convention (not spelled out in the hazards developer guide
            // itself, which doesn't cover authentication).
            .header("Authorization", "apikey $apiKey")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("TfNSW hazards request failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: return@use emptyList()
            parseHazards(body)
        }
    }
}

private fun parseHazards(json: String): List<Hazard> {
    val features = JSONObject(json).optJSONArray("features") ?: return emptyList()
    val hazards = mutableListOf<Hazard>()
    for (i in 0 until features.length()) {
        val feature = features.getJSONObject(i)
        val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
        if (coordinates.length() < 2) continue
        // GeoJSON coordinate order is [longitude, latitude] -- confirmed against a
        // real sample response in the developer guide; its own prose description of
        // this (which says latitude first) has a typo.
        val longitude = coordinates.getDouble(0)
        val latitude = coordinates.getDouble(1)

        val properties = feature.optJSONObject("properties")
        val title = properties?.optString("displayName")?.takeIf { it.isNotBlank() }
            ?: properties?.optString("mainCategory")?.takeIf { it.isNotBlank() }
            ?: "Incident"
        val advice = properties?.optString("adviceA")?.takeIf { it.isNotBlank() }

        hazards.add(
            Hazard(
                id = feature.optLong("id"),
                latitude = latitude,
                longitude = longitude,
                title = title,
                advice = advice,
            ),
        )
    }
    return hazards
}
