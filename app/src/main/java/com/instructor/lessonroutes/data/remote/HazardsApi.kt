package com.instructor.lessonroutes.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

enum class HazardCategory { INCIDENT, ROADWORK }

data class Hazard(
    val id: Long,
    val category: HazardCategory,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val advice: String?,
)

private const val BASE_URL = "https://api.transport.nsw.gov.au/v1/live/hazards"

private val client = OkHttpClient()

/**
 * Phase 2 step 9 (overlay foundation): currently-open (active) traffic incidents.
 * No caching, per spec ("live datasets... fetch fresh per session; do not persist").
 */
suspend fun fetchOpenIncidents(apiKey: String): List<Hazard> =
    fetchHazards(apiKey, "$BASE_URL/incident/open", HazardCategory.INCIDENT)

/** Construction zones / roadworks, same live feed family as incidents. */
suspend fun fetchOpenRoadworks(apiKey: String): List<Hazard> =
    fetchHazards(apiKey, "$BASE_URL/roadwork/open", HazardCategory.ROADWORK)

/**
 * Returns an empty list if [apiKey] is blank so Phase 2 features degrade gracefully
 * with no key configured, rather than firing a request guaranteed to fail auth.
 * Throws on a network/HTTP/parse failure -- callers should catch and treat that as
 * "couldn't load hazards right now" rather than crashing.
 */
private suspend fun fetchHazards(apiKey: String, url: String, category: HazardCategory): List<Hazard> {
    if (apiKey.isBlank()) return emptyList()

    return withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            // Literal word "apikey", then a space, then the token -- confirmed via the
            // API's actual Swagger/OpenAPI spec (securityDefinitions.APIKey.description:
            // "Expected Format: apikey [TOKEN]"), not just the developer guide PDF,
            // which doesn't cover authentication at all.
            .header("Authorization", "apikey $apiKey")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("TfNSW hazards request failed: HTTP ${response.code} ($url)")
            }
            val body = response.body?.string() ?: return@use emptyList()
            parseHazards(body, category)
        }
    }
}

private fun parseHazards(json: String, category: HazardCategory): List<Hazard> {
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
            ?: category.name.lowercase().replaceFirstChar { it.uppercase() }
        val advice = properties?.optString("adviceA")?.takeIf { it.isNotBlank() }

        hazards.add(
            Hazard(
                id = feature.optLong("id"),
                category = category,
                latitude = latitude,
                longitude = longitude,
                title = title,
                advice = advice,
            ),
        )
    }
    return hazards
}
