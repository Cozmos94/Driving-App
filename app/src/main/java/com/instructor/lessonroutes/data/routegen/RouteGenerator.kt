package com.instructor.lessonroutes.data.routegen

import android.util.Log
import com.instructor.lessonroutes.data.remote.fetchRoutedPaths
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val LOG_TAG = "RouteGenerator"

/** NONE = no preference, AVOID = steer away from it, PREFER = try to include it.
 * Every category here (including Highways) is soft proximity scoring against
 * candidate routes, not a real routing constraint -- no free routing API
 * supports true avoid/prefer-zone routing. (Highways->Avoid was originally
 * implemented as OSRM's own `exclude=motorway`, a genuine hard constraint, but
 * OSRM's public demo server rejects that parameter outright for every value --
 * confirmed directly against the live API, not assumed -- so it's scored the
 * same soft way as everything else now.) See the doc comments on
 * OverpassApi.fetchRoundabouts/fetchMergeLaneProxies/fetchMajorRoads. */
enum class FilterPreference { NONE, AVOID, PREFER }

data class RouteGenerationFilters(
    val incidents: FilterPreference = FilterPreference.NONE,
    val constructionZones: FilterPreference = FilterPreference.NONE,
    val schoolZones: FilterPreference = FilterPreference.NONE,
    val speedCameras: FilterPreference = FilterPreference.NONE,
    val highways: FilterPreference = FilterPreference.NONE,
    val roundabouts: FilterPreference = FilterPreference.NONE,
    val mergingLanes: FilterPreference = FilterPreference.NONE,
)

/** Every point-of-interest list the generator scores candidate routes against --
 * callers only need to populate the categories that are actually AVOID/PREFER in
 * [RouteGenerationFilters] (fetching the rest is wasted work), everything else can
 * stay the empty-list default. */
data class ScoringData(
    val incidents: List<LatLng> = emptyList(),
    val constructionZones: List<LatLng> = emptyList(),
    val schoolZones: List<LatLng> = emptyList(),
    val speedCameras: List<LatLng> = emptyList(),
    val roundabouts: List<LatLng> = emptyList(),
    val mergeLaneProxies: List<LatLng> = emptyList(),
    val majorRoads: List<LatLng> = emptyList(),
)

data class GeneratedRoute(
    val points: List<LatLng>,
    val durationSeconds: Double,
    val distanceMeters: Double,
)

// Rough urban-driving assumption for the initial distance guess -- refined by
// actual OSRM-reported durations afterward, so this only needs to be in the right
// ballpark, not accurate.
private const val AVG_SPEED_KMH = 40.0
// 4 directions x up to 3 refinement rounds = up to 12 OSRM calls per generation
// (was 8 x 4 = 32) -- halving-plus the concurrent request volume against OSRM's
// free public demo server, which is shared and rate-limit-prone; heavy concurrent
// load from one client is a real suspect for slow/stuck generation in practice.
private val CANDIDATE_BEARINGS_DEGREES = listOf(0.0, 90.0, 180.0, 270.0)
private const val MAX_RADIUS_ITERATIONS = 3
private const val DURATION_TOLERANCE_RATIO = 0.15
private const val PROXIMITY_METERS = 40.0
private const val KM_PER_DEGREE_LAT = 111.32

/**
 * How large an area (in degrees, for an Overpass bbox query) the generator might
 * range over for [targetDurationMinutes] -- callers use this to fetch scoring
 * data (school zones, cameras, roundabouts, etc.) for a generous-enough area once
 * up front, rather than re-querying per candidate route.
 */
fun estimateSearchRadiusDegrees(targetDurationMinutes: Int): Double {
    // Generous headroom over the initial guess: iterative refinement can grow the
    // radius a fair bit if the first guess undershoots the target duration.
    return (initialRadiusKm(targetDurationMinutes) * 3.0) / KM_PER_DEGREE_LAT
}

private fun initialRadiusKm(targetDurationMinutes: Int): Double =
    // A there-and-back trip covers the detour distance roughly twice (out + back),
    // so split the time budget accordingly.
    (AVG_SPEED_KMH * targetDurationMinutes / 60.0) / 2.5

/**
 * Generates candidate routes from [start] to [destination] (pass the same point
 * for both to plan a loop that returns to where it began) aiming for
 * [targetDurationMinutes] of driving -- one candidate per compass bearing tried,
 * whichever converged. Doesn't score/pick a winner itself; pair with
 * [pickBestRoute] once filter scoring data is ready. Deliberately split into two
 * functions rather than one combined call so a caller can fetch scoring data
 * (Overpass/TfNSW/Room, all independent of this) *concurrently* with this
 * instead of waiting for one then the other -- Overpass in particular is a
 * heavily loaded shared community server, and needlessly serializing two
 * already-independent slow operations was eating into the same overall time
 * budget for no reason.
 *
 * No free routing API can plan "a route of duration X" directly, so this is a
 * heuristic: try a detour point at each compass bearing around the start/
 * destination midpoint, ask OSRM for its actual drive time, and adjust the detour
 * distance iteratively (up to [MAX_RADIUS_ITERATIONS] times) until it converges
 * near the target.
 *
 * Returns every candidate that converged; empty if every attempt failed outright
 * (e.g. no network).
 */
suspend fun generateCandidateRoutes(
    start: LatLng,
    destination: LatLng,
    targetDurationMinutes: Int,
): List<GeneratedRoute> = coroutineScope {
    val base = midpoint(start, destination)
    val targetSeconds = targetDurationMinutes * 60.0
    val initialRadiusKm = initialRadiusKm(targetDurationMinutes)
    Log.d(
        LOG_TAG,
        "generateCandidateRoutes: target=${targetDurationMinutes}min, " +
            "initialRadius=${"%.2f".format(initialRadiusKm)}km, bearings=${CANDIDATE_BEARINGS_DEGREES.size}",
    )

    val candidates = CANDIDATE_BEARINGS_DEGREES
        .map { bearing -> async { refineCandidate(start, destination, base, bearing, initialRadiusKm, targetSeconds) } }
        .awaitAll()
        .filterNotNull()

    Log.d(LOG_TAG, "generateCandidateRoutes: ${candidates.size}/${CANDIDATE_BEARINGS_DEGREES.size} candidates converged")
    candidates
}

/** Picks whichever of [candidates] best matches [filters], proximity-scored
 * against [scoringData] -- null if [candidates] is empty. Pure/non-suspending:
 * all the (potentially slow) network fetching already happened to produce both
 * arguments. */
fun pickBestRoute(candidates: List<GeneratedRoute>, filters: RouteGenerationFilters, scoringData: ScoringData): GeneratedRoute? =
    candidates.maxByOrNull { scoreRoute(it.points, filters, scoringData) }

private suspend fun refineCandidate(
    start: LatLng,
    destination: LatLng,
    base: LatLng,
    bearingDegrees: Double,
    initialRadiusKm: Double,
    targetSeconds: Double,
): GeneratedRoute? {
    var radiusKm = initialRadiusKm
    var best: GeneratedRoute? = null
    repeat(MAX_RADIUS_ITERATIONS) { iteration ->
        val detourPoint = offset(base, bearingDegrees, radiusKm)
        val routed = try {
            fetchRoutedPaths(listOf(start, detourPoint, destination)).firstOrNull()
        } catch (e: Exception) {
            // Was silently swallowed before -- logged now since this is the one
            // place an OSRM failure (network, rate limit, no route found, etc.)
            // would otherwise leave zero trace of what actually went wrong.
            Log.e(LOG_TAG, "OSRM call failed (bearing=$bearingDegrees, iteration=$iteration, radius=${"%.2f".format(radiusKm)}km)", e)
            null
        }
        if (routed == null) {
            // This detour point didn't route at all (e.g. nothing driveable
            // nearby) -- shrink and retry rather than hitting the exact same
            // failing point again next iteration.
            radiusKm *= 0.7
            return@repeat
        }
        best = GeneratedRoute(routed.points, routed.durationSeconds, routed.distanceMeters)
        val ratio = targetSeconds / routed.durationSeconds.coerceAtLeast(1.0)
        if (abs(1.0 - ratio) < DURATION_TOLERANCE_RATIO) return best
        // Damped adjustment (not a straight multiply) so a wildly-off first guess
        // doesn't overshoot into an even-worse radius next iteration.
        radiusKm *= ratio.coerceIn(0.4, 2.5)
    }
    return best
}

private fun scoreRoute(route: List<LatLng>, filters: RouteGenerationFilters, data: ScoringData): Int {
    var score = 0
    fun apply(preference: FilterPreference, pointsOfInterest: List<LatLng>) {
        if (preference == FilterPreference.NONE || pointsOfInterest.isEmpty()) return
        val hits = countNearby(pointsOfInterest, route)
        score += if (preference == FilterPreference.AVOID) -hits else hits
    }
    apply(filters.incidents, data.incidents)
    apply(filters.constructionZones, data.constructionZones)
    apply(filters.schoolZones, data.schoolZones)
    apply(filters.speedCameras, data.speedCameras)
    apply(filters.roundabouts, data.roundabouts)
    apply(filters.mergingLanes, data.mergeLaneProxies)
    apply(filters.highways, data.majorRoads)
    return score
}

private fun countNearby(pointsOfInterest: List<LatLng>, route: List<LatLng>): Int =
    pointsOfInterest.count { poi -> route.any { r -> approxDistanceMeters(poi, r) < PROXIMITY_METERS } }

/** Equirectangular approximation -- fine at this scale (tens of meters), same
 * approach as OverpassApi.kt's distanceToPolylineMeters. Checks distance to the
 * nearest route *vertex* rather than the nearest segment -- an approximation, but
 * OSRM's `overview=full` geometry is dense enough (vertices every ~10-50m) that
 * this is close enough for scoring purposes. */
private fun approxDistanceMeters(a: LatLng, b: LatLng): Double {
    val metersPerDegreeLat = 111_320.0
    val metersPerDegreeLon = 111_320.0 * cos(Math.toRadians(a.latitude))
    val dx = (b.longitude - a.longitude) * metersPerDegreeLon
    val dy = (b.latitude - a.latitude) * metersPerDegreeLat
    return hypot(dx, dy)
}

/** Public so callers can center their own scoring-data fetch (see
 * GenerateRouteScreen.kt) on the same point [generateCandidateRoutes] itself
 * searches around. */
fun midpoint(a: LatLng, b: LatLng): LatLng =
    LatLng((a.latitude + b.latitude) / 2.0, (a.longitude + b.longitude) / 2.0)

/** Offsets [point] by [distanceKm] along compass [bearingDegrees] (0 = north,
 * 90 = east), via a flat-earth approximation -- fine at the few-km scale this is
 * used at. */
private fun offset(point: LatLng, bearingDegrees: Double, distanceKm: Double): LatLng {
    val kmPerDegreeLon = KM_PER_DEGREE_LAT * cos(Math.toRadians(point.latitude))
    val bearingRad = Math.toRadians(bearingDegrees)
    val dLat = (distanceKm * cos(bearingRad)) / KM_PER_DEGREE_LAT
    val dLon = (distanceKm * sin(bearingRad)) / kmPerDegreeLon
    return LatLng(point.latitude + dLat, point.longitude + dLon)
}
