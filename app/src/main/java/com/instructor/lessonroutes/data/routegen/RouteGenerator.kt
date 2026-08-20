package com.instructor.lessonroutes.data.routegen

import android.util.Log
import com.instructor.lessonroutes.data.remote.fetchRoutedPaths
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
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
    val highTraffic: FilterPreference = FilterPreference.NONE,
)

/** Every filter category's display name, in the same fixed order used
 * everywhere a full set of categories is shown or reasoned about (the filter
 * list UI, [RouteGenerationFilters.summarize], and student-profile lifetime
 * coverage on the route list screen). */
val ALL_FILTER_LABELS = listOf(
    "Hazards", "Construction zones", "School zones", "Speed cameras",
    "High traffic roads", "Highways", "Roundabouts", "Merging lanes",
)

/** Which categories were set to Avoid vs Prefer, as display-name lists (see
 * [ALL_FILTER_LABELS] for the fixed category order) -- both empty if every
 * category was left at NONE. [avoidCsv]/[preferCsv] are the comma-joined form
 * stored on [com.instructor.lessonroutes.data.Route.avoidFilters]/
 * [com.instructor.lessonroutes.data.Route.preferFilters] when a generated
 * route is saved, so the detail screen and student-profile coverage summary
 * can render a structured list without needing to persist all 8 categories'
 * raw enum values. */
data class FilterSummary(val avoid: List<String>, val prefer: List<String>) {
    val isEmpty: Boolean get() = avoid.isEmpty() && prefer.isEmpty()
    val avoidCsv: String? get() = avoid.joinToString(", ").ifBlank { null }
    val preferCsv: String? get() = prefer.joinToString(", ").ifBlank { null }
}

fun RouteGenerationFilters.summarize(): FilterSummary {
    val labeled = ALL_FILTER_LABELS.zip(
        listOf(incidents, constructionZones, schoolZones, speedCameras, highTraffic, highways, roundabouts, mergingLanes),
    )
    return FilterSummary(
        avoid = labeled.filter { it.second == FilterPreference.AVOID }.map { it.first },
        prefer = labeled.filter { it.second == FilterPreference.PREFER }.map { it.first },
    )
}

/** Splits a comma-joined [avoidCsv]/[preferCsv]-style string (as stored on
 * [com.instructor.lessonroutes.data.Route]) back into a display list -- empty
 * (not a list with one blank entry) for a null/blank input. */
fun String?.toFilterList(): List<String> =
    this?.split(", ")?.filter { it.isNotBlank() } ?: emptyList()

/** Effective Avoid/Prefer lists for a saved [com.instructor.lessonroutes.data.
 * Route] -- reads the structured avoidFilters/preferFilters columns when
 * present, but falls back to parsing the deprecated generationFilters
 * paragraph ("Avoid: X, Y. Prefer: Z.") for routes saved before the schema
 * v4->v5 split introduced those columns. Without this fallback, any route
 * saved during that earlier window would show (and count toward student
 * coverage) as if it had no filters at all -- its filter data isn't lost, just
 * stuck in the old field, so this recovers it instead of stranding it. */
fun com.instructor.lessonroutes.data.Route.effectiveFilterSummary(): FilterSummary {
    if (avoidFilters != null || preferFilters != null) {
        return FilterSummary(avoidFilters.toFilterList(), preferFilters.toFilterList())
    }
    val legacy = generationFilters ?: return FilterSummary(emptyList(), emptyList())
    fun extract(sectionLabel: String): List<String> =
        Regex("$sectionLabel: ([^.]+)\\.").find(legacy)?.groupValues?.get(1)?.split(", ") ?: emptyList()
    return FilterSummary(avoid = extract("Avoid"), prefer = extract("Prefer"))
}

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
    val highTraffic: List<LatLng> = emptyList(),
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
// Used instead of AVG_SPEED_KMH when avoiding highways: since OSRM's public
// server can't be told to actually exclude motorways (see FilterPreference's
// doc comment), "avoid highways" can only work by choosing a *shorter* target
// distance in the first place -- one plausibly drivable on local roads without
// needing a highway's speed advantage to cover it in the time budget. A route
// generated assuming 40kmh will often be far enough that OSRM's fastest-path
// default hops onto a highway regardless of scoring afterward; assuming a
// slower local-roads speed keeps the implied distance shorter.
private const val SUBURBAN_AVG_SPEED_KMH = 25.0
// Bearings run in parallel, but each bearing's refinement rounds are inherently
// sequential (each depends on the previous round's OSRM response) -- that per-
// bearing round-trip chain, not the bearing count, is the dominant latency cost.
// 3 directions x up to 2 rounds = up to 6 OSRM calls per generation (was 4x3=12,
// 8x4=32 originally) -- cut further for speed (target: well under 10s typical)
// at some cost to candidate diversity/duration precision. A looser tolerance
// (25%, was 15%) means the *common* case converges in a single round instead of
// needing a second one, which matters more for wall-clock time than the round
// cap itself.
private val CANDIDATE_BEARINGS_DEGREES = listOf(0.0, 120.0, 240.0)
private const val MAX_RADIUS_ITERATIONS = 2
private const val DURATION_TOLERANCE_RATIO = 0.25
// Separate, tighter bound for the alternatives=true call specifically (see its
// use in refineCandidate) -- asking OSRM to search for more than one path is
// real extra graph-search work and can run slower than a normal request; without
// its own limit, one slow bearing's alternatives call could consume the entire
// overall generation timeout by itself, cancelling every bearing's work
// (confirmed as a real cause of "times out with no route at all").
private const val ALTERNATIVES_TIMEOUT_MS = 5_000L
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
    // radius a fair bit if the first guess undershoots the target duration. Uses
    // the general (not suburban) speed assumption regardless of filters -- a
    // slightly larger scoring-data search area never hurts, it's just a bbox for
    // fetching points of interest, not the actual candidate radius.
    return (initialRadiusKm(targetDurationMinutes, avoidHighways = false) * 3.0) / KM_PER_DEGREE_LAT
}

private fun initialRadiusKm(targetDurationMinutes: Int, avoidHighways: Boolean): Double {
    val avgSpeedKmh = if (avoidHighways) SUBURBAN_AVG_SPEED_KMH else AVG_SPEED_KMH
    // A there-and-back trip covers the detour distance roughly twice (out + back),
    // so split the time budget accordingly.
    return (avgSpeedKmh * targetDurationMinutes / 60.0) / 2.5
}

/**
 * Generates candidate routes from [start] to [destination] (pass the same point
 * for both to plan a loop that returns to where it began) aiming for
 * [targetDurationMinutes] of driving -- one or more candidates per compass
 * bearing tried, whichever converged (see [fetchAlternatives]). Doesn't score/
 * pick a winner itself; pair with [pickBestRoute] once filter scoring data is
 * ready. Deliberately split into two functions rather than one combined call so
 * a caller can fetch scoring data (Overpass/TfNSW/Room, all independent of this)
 * *concurrently* with this instead of waiting for one then the other -- Overpass
 * in particular is a heavily loaded shared community server, and needlessly
 * serializing two already-independent slow operations was eating into the same
 * overall time budget for no reason.
 *
 * No free routing API can plan "a route of duration X" directly, so this is a
 * heuristic: try a detour point at each compass bearing around the start/
 * destination midpoint, ask OSRM for its actual drive time, and adjust the detour
 * distance iteratively (up to [MAX_RADIUS_ITERATIONS] times) until it converges
 * near the target.
 *
 * @param avoidHighways Assumes a slower local-roads speed for the initial detour
 * distance guess instead of the general assumption -- since OSRM can't actually
 * be told to exclude motorways (see [FilterPreference]'s doc comment), the only
 * lever available is keeping the implied trip short enough that a highway's
 * speed advantage isn't needed to cover it in the time budget; a longer implied
 * distance all but guarantees OSRM's fastest-path default reaches for one anyway.
 * @param fetchAlternatives When true, asks OSRM for alternate paths at each
 * bearing's final (converged) detour point too, not just its single default
 * route -- gives [pickBestRoute] more than one shape per bearing to choose
 * between, which matters for filters OSRM can't be told to route around
 * directly (Highways, Roundabouts, Merging lanes): without this, scoring can
 * only rank bearings against each other, never find a meaningfully different
 * path for the *same* bearing.
 * @param maxRadiusKm Caps how far from [base] the detour point can be, if set --
 * an instructor-chosen ceiling (e.g. "don't take my student more than 20km from
 * home"), independent of and taking priority over [targetDurationMinutes]'s own
 * detour-distance guess: duration convergence can undershoot its target if
 * hitting it would need a wider detour than this allows, which is the intended
 * trade-off (the radius is a hard-ish ceiling, duration is still just a
 * best-effort target either way -- see this whole function's doc comment).
 *
 * Returns every candidate that converged; empty if every attempt failed outright
 * (e.g. no network).
 */
suspend fun generateCandidateRoutes(
    start: LatLng,
    destination: LatLng,
    targetDurationMinutes: Int,
    avoidHighways: Boolean = false,
    fetchAlternatives: Boolean = false,
    maxRadiusKm: Double? = null,
): List<GeneratedRoute> = coroutineScope {
    val base = midpoint(start, destination)
    val targetSeconds = targetDurationMinutes * 60.0
    val initialRadiusKm = initialRadiusKm(targetDurationMinutes, avoidHighways)
        .let { if (maxRadiusKm != null) minOf(it, maxRadiusKm) else it }
    Log.d(
        LOG_TAG,
        "generateCandidateRoutes: target=${targetDurationMinutes}min, " +
            "initialRadius=${"%.2f".format(initialRadiusKm)}km, bearings=${CANDIDATE_BEARINGS_DEGREES.size}, " +
            "avoidHighways=$avoidHighways, fetchAlternatives=$fetchAlternatives, maxRadiusKm=$maxRadiusKm",
    )

    val candidates = CANDIDATE_BEARINGS_DEGREES
        .map { bearing ->
            async {
                refineCandidate(start, destination, base, bearing, initialRadiusKm, targetSeconds, fetchAlternatives, maxRadiusKm)
            }
        }
        .awaitAll()
        .flatten()

    Log.d(LOG_TAG, "generateCandidateRoutes: ${candidates.size} candidate route(s) from ${CANDIDATE_BEARINGS_DEGREES.size} bearings")
    candidates
}

/** Picks whichever of [candidates] best matches [filters] (proximity-scored
 * against [scoringData]) *and* how close its own actual duration is to
 * [targetSeconds] -- null if [candidates] is empty. Duration-closeness was
 * previously not part of this decision at all: each bearing's own refinement
 * loop tries to converge on the target, but the final pick across bearings was
 * scored purely on filters, so a candidate miles off the target duration could
 * still win outright (or, with no filters set at all, every candidate scores
 * equally and the pick was effectively arbitrary -- whichever bearing happened
 * to come first). Confirmed as a real cause of "generated route's duration
 * doesn't match what I asked for," not just normal convergence tolerance.
 *
 * @param start Anchor for [maxRadiusKm] -- "radius" means distance from where
 * the trip actually starts, not from generateCandidateRoutes' internal
 * start/destination midpoint (that midpoint is just where the detour-bearing
 * search is centered; the actual route's furthest point from the *start* can
 * be well beyond it, especially once [destination] itself is far from
 * [start]). If [maxRadiusKm] is set, candidates that ever travel farther than
 * it from [start] ([routeExceedsRadius]) are excluded when at least one
 * candidate *does* conform -- if literally none do (e.g. the chosen
 * destination itself is farther than the radius allows, so no route between
 * them can possibly stay within it), falls back to every candidate rather
 * than returning null outright; callers should check [routeExceedsRadius] on
 * the result themselves to warn the instructor when that happened. Duration/
 * filter clamping in [generateCandidateRoutes] already biases candidates
 * toward the radius during generation -- this is the hard backstop for when
 * that bias alone wasn't enough.
 *
 * Pure/non-suspending: all the (potentially slow) network fetching already
 * happened to produce both arguments. */
fun pickBestRoute(
    candidates: List<GeneratedRoute>,
    filters: RouteGenerationFilters,
    scoringData: ScoringData,
    targetSeconds: Double,
    start: LatLng? = null,
    maxRadiusKm: Double? = null,
): GeneratedRoute? {
    val pool = if (start != null && maxRadiusKm != null) {
        candidates.filterNot { routeExceedsRadius(it, start, maxRadiusKm) }.ifEmpty { candidates }
    } else {
        candidates
    }
    return pool.maxByOrNull { scoreRoute(it, filters, scoringData, targetSeconds) }
}

/** True if any point along [route] is farther than [maxRadiusKm] from
 * [anchor] -- see [pickBestRoute]'s doc comment for how this is used both to
 * filter candidates and, by the caller, to warn when even the best available
 * one couldn't stay within the radius. */
fun routeExceedsRadius(route: GeneratedRoute, anchor: LatLng, maxRadiusKm: Double): Boolean =
    route.points.any { approxDistanceMeters(anchor, it) / 1000.0 > maxRadiusKm }

/** Returns the primary (converged, or best-effort) route for this bearing, plus
 * any alternates OSRM offers at that same final detour point if
 * [fetchAlternatives] is true -- empty if every attempt at this bearing failed
 * outright. */
private suspend fun refineCandidate(
    start: LatLng,
    destination: LatLng,
    base: LatLng,
    bearingDegrees: Double,
    initialRadiusKm: Double,
    targetSeconds: Double,
    fetchAlternatives: Boolean,
    maxRadiusKm: Double?,
): List<GeneratedRoute> {
    var radiusKm = initialRadiusKm
    var best: GeneratedRoute? = null
    var bestDetourPoint: LatLng? = null
    var converged = false
    repeat(MAX_RADIUS_ITERATIONS) { iteration ->
        if (converged) return@repeat // already converged -- skip remaining rounds without another call
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
        bestDetourPoint = detourPoint
        best = GeneratedRoute(routed.points, routed.durationSeconds, routed.distanceMeters)
        val ratio = targetSeconds / routed.durationSeconds.coerceAtLeast(1.0)
        if (abs(1.0 - ratio) < DURATION_TOLERANCE_RATIO) {
            converged = true
        } else {
            // Damped adjustment (not a straight multiply) so a wildly-off first
            // guess doesn't overshoot into an even-worse radius next round.
            radiusKm *= ratio.coerceIn(0.4, 2.5)
        }
        // Re-clamped every round, not just on the initial guess -- duration
        // convergence above can otherwise push radiusKm back out past the
        // instructor's chosen ceiling on a later iteration.
        if (maxRadiusKm != null) radiusKm = minOf(radiusKm, maxRadiusKm)
    }

    val primary = best ?: return emptyList()
    if (!fetchAlternatives) return listOf(primary)

    // Bounded on its own, separate from the overall generation timeout: an
    // alternatives=true request asks OSRM to search for more than one path,
    // which is real extra graph-search work and can run noticeably slower than
    // a normal request. Without its own limit, one slow bearing's alternatives
    // call could consume the *entire* overall timeout budget by itself and
    // cancel every bearing's work, including ones that had already succeeded --
    // confirmed as a real cause of "times out with no route at all" when
    // Highways/Roundabouts/Merging lanes was set. Falls back to just the
    // primary route rather than failing this bearing outright.
    val alternates = try {
        withTimeoutOrNull(ALTERNATIVES_TIMEOUT_MS) {
            fetchRoutedPaths(listOf(start, bestDetourPoint!!, destination), alternatives = true)
                .drop(1) // first result duplicates `primary`, already included
                .map { GeneratedRoute(it.points, it.durationSeconds, it.distanceMeters) }
        } ?: emptyList()
    } catch (e: Exception) {
        Log.e(LOG_TAG, "OSRM alternatives call failed (bearing=$bearingDegrees)", e)
        emptyList()
    }
    return listOf(primary) + alternates
}

/** Duration-closeness is weighted heavily enough to dominate typical filter
 * hit counts (usually single/low-double-digit proximity hits), so it's the
 * primary driver of which candidate wins -- filters remain a real tie-breaker
 * among candidates that converged similarly well, rather than the only thing
 * that mattered. */
private const val DURATION_ERROR_WEIGHT_PER_MINUTE = 10.0

private fun scoreRoute(route: GeneratedRoute, filters: RouteGenerationFilters, data: ScoringData, targetSeconds: Double): Double {
    var filterScore = 0
    fun apply(preference: FilterPreference, pointsOfInterest: List<LatLng>) {
        if (preference == FilterPreference.NONE || pointsOfInterest.isEmpty()) return
        val hits = countNearby(pointsOfInterest, route.points)
        filterScore += if (preference == FilterPreference.AVOID) -hits else hits
    }
    apply(filters.incidents, data.incidents)
    apply(filters.constructionZones, data.constructionZones)
    apply(filters.schoolZones, data.schoolZones)
    apply(filters.speedCameras, data.speedCameras)
    apply(filters.roundabouts, data.roundabouts)
    apply(filters.mergingLanes, data.mergeLaneProxies)
    apply(filters.highways, data.majorRoads)
    apply(filters.highTraffic, data.highTraffic)

    val durationErrorMinutes = abs(route.durationSeconds - targetSeconds) / 60.0
    return filterScore - durationErrorMinutes * DURATION_ERROR_WEIGHT_PER_MINUTE
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
