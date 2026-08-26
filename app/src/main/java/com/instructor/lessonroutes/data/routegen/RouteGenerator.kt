package com.instructor.lessonroutes.data.routegen

import android.util.Log
import com.instructor.lessonroutes.data.remote.RoutedPath
import com.instructor.lessonroutes.data.remote.fetchRoutedPaths
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.maplibre.android.geometry.LatLng
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.sin

private const val LOG_TAG = "RouteGenerator"

/** NONE = no preference, AVOID = steer away from it, PREFER = try to include it.
 * Highways->Avoid is a real hard routing constraint now (Geoapify's
 * `avoid=highways`, confirmed live -- see GeoapifyRoutingApi.kt's doc comment
 * for the before/after numbers). Every other category, and Highways->Prefer
 * (there's no "prefer highways" equivalent constraint), is still soft
 * proximity scoring against candidate routes -- no free routing API this app
 * uses supports a real avoid/prefer-zone constraint for roundabouts or merge
 * lanes specifically. See the doc comments on
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
// actual reported durations afterward, so this only needs to be in the right
// ballpark, not accurate. Used regardless of Highways->Avoid now -- that's a
// real routing constraint (Geoapify's avoid=highways) rather than something
// this initial guess needs to work around, unlike before.
private const val AVG_SPEED_KMH = 40.0
// Bearings run in parallel, but each bearing's refinement rounds are inherently
// sequential (each depends on the previous round's response) -- that per-
// bearing round-trip chain, not the bearing count, is the dominant latency cost.
// 3 directions x up to 3 rounds = up to 9 routing calls per generation (was
// 4x3=12, 8x4=32 originally). Tightening DURATION_TOLERANCE_SECONDS below
// (Corey: "lower the tolerance... within 10 minutes") means the common case
// is more likely to need its extra round to actually land inside tolerance,
// not just converge on the first guess -- accepted trade-off, since accuracy
// was explicitly asked for over speed here.
//
// MAX_RADIUS_ITERATIONS was cut to 2 for speed in an earlier session, but that
// leaves only ONE correction step: round 1 measures the initial guess, round 2
// applies one ratio-scaled correction (clamped to at most 2.5x) and whatever
// that produces is final, converged or not. Confirmed as a real cause of a
// badly-off result (target 2h16m, generated 3h19m, no radius cap involved) --
// AVG_SPEED_KMH's flat 40km/h assumption can be far from a real route's actual
// speed, so the single correction can itself overshoot (or still undershoot)
// by a lot with no further round to walk it back. Restored to 3 so a bad
// single correction gets a second chance to converge instead of being final
// by construction.
private val CANDIDATE_BEARINGS_DEGREES = listOf(0.0, 120.0, 240.0)
private const val MAX_RADIUS_ITERATIONS = 3
// How many shrink+rotate retries an unroutable petal ring gets *within* one
// outer duration-convergence iteration before that iteration gives up -- see
// refineCandidateWithinRadius's own comment on why this needs to be separate
// from MAX_RADIUS_ITERATIONS (a routing failure used to cost a whole
// convergence round, which -- with only 3 of those total -- could exhaust the
// entire budget on retries alone, stranding `best` on an early, badly-off
// result).
private const val MAX_UNROUTABLE_RETRIES = 3
// Absolute, not a percentage of target -- Corey: "lower the tolerance of
// generated route times to be within 10 minutes of the time frame set by the
// user". A ratio-based tolerance (the old 25%) meant a short trip's
// acceptable window was tiny in absolute minutes while a long trip's was
// huge -- a flat 10-minute window instead means the same real-world slack
// regardless of target length, matching what was actually asked for.
private const val DURATION_TOLERANCE_SECONDS = 10 * 60.0
// A real, confirmed bug report: a 1-hour target generated a route reported
// as 0 minutes. Root cause not pinned down live (several attempts to
// reproduce it -- including deliberately avoiding a location right at a
// bearing's own detour/via point, the newest thing this generator does --
// all came back with sane, non-degenerate durations against the real
// Geoapify endpoint), but whatever the actual trigger is, a routed response
// this short can never legitimately be "the" answer for a real target
// duration -- `best`'s own tracking (see its comment above) would otherwise
// happily accept a single degenerate round as the final result if every
// other round for that bearing failed outright, since any real number beats
// the initial Double.MAX_VALUE. Treated the same as a routing failure below
// (shrink+retry) rather than accepted, regardless of mechanism.
private const val MIN_PLAUSIBLE_DURATION_SECONDS = 60.0
// Floor for the petal ring's spread angle (see spokePoints' own doc comment) --
// narrowed from a full 360 when a full-circle ring keeps failing to route, but
// not narrowed all the way to a sliver: petals need some real angular spacing
// between them for the "several directions, forces backtracking" shape this
// mode is built for to still mean anything.
private const val MIN_SPREAD_DEGREES = 60.0
private const val PROXIMITY_METERS = 40.0
private const val KM_PER_DEGREE_LAT = 111.32

// Radius-confined generation (see refineCandidateWithinRadius) chains
// start + this many "petal" waypoints + destination in one routing call --
// confirmed live against Geoapify's actual API that even 18 waypoints in one
// request comes back in ~1.5s with sensible geometry, so this is a
// comfortable ceiling chosen for request/response size, not a real API
// limit that was hit.
private const val MAX_SPOKES = 12

/**
 * How large an area (in degrees, for an Overpass bbox query) the generator might
 * range over for [targetDurationMinutes] -- callers use this to fetch scoring
 * data (school zones, cameras, roundabouts, etc.) for a generous-enough area once
 * up front, rather than re-querying per candidate route.
 */
fun estimateSearchRadiusDegrees(targetDurationMinutes: Int): Double {
    // Generous headroom over the initial guess: iterative refinement can grow the
    // radius a fair bit if the first guess undershoots the target duration. A
    // slightly larger scoring-data search area never hurts, it's just a bbox for
    // fetching points of interest, not the actual candidate radius.
    return (initialRadiusKm(targetDurationMinutes) * 3.0) / KM_PER_DEGREE_LAT
}

private fun initialRadiusKm(targetDurationMinutes: Int): Double {
    // A there-and-back trip covers the detour distance roughly twice (out + back),
    // so split the time budget accordingly.
    return (AVG_SPEED_KMH * targetDurationMinutes / 60.0) / 2.5
}

/**
 * Generates candidate routes from [start] to [destination] aiming for
 * [targetDurationMinutes] of driving -- one candidate per compass bearing tried.
 * Doesn't score/pick a winner itself; pair with [pickBestRoute] once filter
 * scoring data is ready. Deliberately split into two functions rather than one
 * combined call so a caller can fetch scoring data (Overpass/TfNSW/Room, all
 * independent of this) *concurrently* with this instead of waiting for one then
 * the other -- Overpass in particular is a heavily loaded shared community
 * server, and needlessly serializing two already-independent slow operations
 * was eating into the same overall time budget for no reason.
 *
 * No free routing API can plan "a route of duration X" directly, so both modes
 * below are heuristics that ask the routing API for actual drive time and
 * iteratively adjust something about the route to converge on the target:
 *
 * - **No radius cap** ([maxRadiusKm] null): try a single detour point at each
 *   compass bearing around the start/destination midpoint, adjusting how far out
 *   that one point sits (up to [MAX_RADIUS_ITERATIONS] times) -- see
 *   [refineCandidate].
 * - **Radius cap set**: the radius is a hard spatial boundary the whole trip must
 *   stay inside, not a ceiling that duration is allowed to fall short of --
 *   confirmed directly by Corey after an earlier version treated it as the
 *   latter (a real bug report: 1.5h target, 10km radius, generated 34min, "the
 *   route needs to stay within the radius... hitting the radius barrier does
 *   not mean the route has to then go to the destination and finish"). Once a
 *   single detour point is maxed out at [maxRadiusKm], there's nothing left to
 *   stretch on that knob -- so this mode instead chains *multiple* waypoints
 *   ("petals"), each at the full [maxRadiusKm] from [start], adjusting how many
 *   there are rather than how far out any one of them reaches. See
 *   [refineCandidateWithinRadius]. Confirmed live against Geoapify's routing API
 *   that a many-waypoint chain like this both works and produces a genuinely
 *   long, realistic duration from a small radius (an 8km-radius, 6-petal test
 *   chain came back as an 85-minute, 84km route) -- not guessed at.
 *
 * @param avoidHighways Passed straight through to the routing API as a real
 * `avoid=highways` constraint (confirmed live -- see GeoapifyRoutingApi.kt's
 * doc comment) -- unlike the previous OSRM-backed implementation, which
 * couldn't enforce this at all and relied purely on post-hoc proximity scoring
 * plus a shorter-implied-distance nudge.
 * @param maxRadiusKm Hard ceiling on how far from [start] the trip may range, if
 * set -- an instructor-chosen boundary (e.g. "don't take my student more than
 * 10km from home"). Duration is still the real target either way; this changes
 * *how* the generator tries to hit it, not whether it keeps trying once the
 * boundary binds.
 * Returns every candidate that converged; empty if every attempt failed outright
 * (e.g. no network).
 */
suspend fun generateCandidateRoutes(
    start: LatLng,
    destination: LatLng,
    targetDurationMinutes: Int,
    avoidHighways: Boolean = false,
    maxRadiusKm: Double? = null,
): List<GeneratedRoute> = coroutineScope {
    val targetSeconds = targetDurationMinutes * 60.0

    val candidates = if (maxRadiusKm != null) {
        Log.d(
            LOG_TAG,
            "generateCandidateRoutes: radius-confined mode, target=${targetDurationMinutes}min, " +
                "maxRadiusKm=$maxRadiusKm, bearings=${CANDIDATE_BEARINGS_DEGREES.size}, avoidHighways=$avoidHighways",
        )
        CANDIDATE_BEARINGS_DEGREES
            .map { bearing ->
                async {
                    refineCandidateWithinRadius(start, destination, bearing, maxRadiusKm, targetSeconds, avoidHighways)
                }
            }
            .awaitAll()
            .filterNotNull()
    } else {
        val base = midpoint(start, destination)
        val initialRadiusKm = initialRadiusKm(targetDurationMinutes)
        Log.d(
            LOG_TAG,
            "generateCandidateRoutes: target=${targetDurationMinutes}min, " +
                "initialRadius=${"%.2f".format(initialRadiusKm)}km, bearings=${CANDIDATE_BEARINGS_DEGREES.size}, " +
                "avoidHighways=$avoidHighways",
        )
        CANDIDATE_BEARINGS_DEGREES
            .map { bearing ->
                async {
                    refineCandidate(start, destination, base, bearing, initialRadiusKm, targetSeconds, avoidHighways)
                }
            }
            .awaitAll()
            .filterNotNull()
    }

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

/** Returns the converged (or best-effort) route for this bearing, or null if
 * every attempt at this bearing failed outright. Only used when there's no
 * radius cap -- see [refineCandidateWithinRadius] for that case, which needs a
 * different knob to turn once a single detour point's reach is maxed out. */
private suspend fun refineCandidate(
    start: LatLng,
    destination: LatLng,
    base: LatLng,
    bearingDegrees: Double,
    initialRadiusKm: Double,
    targetSeconds: Double,
    avoidHighways: Boolean,
): GeneratedRoute? {
    var radiusKm = initialRadiusKm
    var best: GeneratedRoute? = null
    // Tracked separately from `best` -- see this function's own bug history:
    // `best` used to just be overwritten every round regardless of whether
    // that round was actually closer to target than a previous one. If a
    // later round landed worse and then the round after *that* failed
    // outright (network error, bad request, etc.), `best` was left holding
    // the worse round's result instead of the closest one actually seen
    // across the whole loop -- a real, confirmed contributor to a generated
    // route landing wildly short of target even though an earlier round had
    // gotten close. Now `best` only updates when a round is a genuine
    // improvement.
    var bestErrorSeconds = Double.MAX_VALUE
    var converged = false
    repeat(MAX_RADIUS_ITERATIONS) { iteration ->
        if (converged) return@repeat // already converged -- skip remaining rounds without another call
        val detourPoint = offset(base, bearingDegrees, radiusKm)
        val routed = try {
            fetchRoutedPaths(listOf(start, detourPoint, destination), avoidHighways = avoidHighways).firstOrNull()
        } catch (e: Exception) {
            // Was silently swallowed before -- logged now since this is the one
            // place a routing failure (network, rate limit, no route found,
            // etc.) would otherwise leave zero trace of what actually went wrong.
            Log.e(LOG_TAG, "Routing call failed (bearing=$bearingDegrees, iteration=$iteration, radius=${"%.2f".format(radiusKm)}km)", e)
            null
        }
        if (routed == null) {
            // This detour point didn't route at all (e.g. nothing driveable
            // nearby) -- shrink and retry rather than hitting the exact same
            // failing point again next iteration.
            radiusKm *= 0.7
            return@repeat
        }
        if (routed.durationSeconds < MIN_PLAUSIBLE_DURATION_SECONDS) {
            // See MIN_PLAUSIBLE_DURATION_SECONDS' own comment -- a real bug
            // report, mechanism not pinned down. Treated like routed == null
            // above (shrink and retry) rather than ever accepted as `best`.
            Log.e(
                LOG_TAG,
                "refineCandidate: rejecting implausibly short duration=${routed.durationSeconds}s " +
                    "(bearing=$bearingDegrees, iteration=$iteration, radius=${"%.2f".format(radiusKm)}km)",
            )
            radiusKm *= 0.7
            return@repeat
        }
        val ratio = targetSeconds / routed.durationSeconds.coerceAtLeast(1.0)
        val errorSeconds = abs(routed.durationSeconds - targetSeconds)
        if (errorSeconds < bestErrorSeconds) {
            best = GeneratedRoute(routed.points, routed.durationSeconds, routed.distanceMeters)
            bestErrorSeconds = errorSeconds
        }
        // Visibility into each round's actual result -- previously only a
        // failure logged anything at all, so a badly-converged (but
        // technically successful) result left zero trace of *why* it landed
        // where it did. See MAX_RADIUS_ITERATIONS' own doc comment for the
        // real mismatch this helped diagnose.
        Log.d(
            LOG_TAG,
            "refineCandidate: bearing=$bearingDegrees iteration=$iteration radius=${"%.2f".format(radiusKm)}km " +
                "duration=${"%.1f".format(routed.durationSeconds / 60.0)}min " +
                "target=${"%.1f".format(targetSeconds / 60.0)}min ratio=${"%.2f".format(ratio)}",
        )
        if (errorSeconds < DURATION_TOLERANCE_SECONDS) {
            converged = true
        } else {
            // Damped adjustment (not a straight multiply) so a wildly-off first
            // guess doesn't overshoot into an even-worse radius next round.
            radiusKm *= ratio.coerceIn(0.4, 2.5)
        }
    }

    return best
}

/** Returns the converged (or best-effort) route for this bearing when a radius
 * cap is active, or null if every attempt failed outright.
 *
 * Chains [start], then [spokeCount] "petal" waypoints evenly spaced around a
 * full circle starting at [bearingDegrees] (see [spokePoints]), then
 * [destination] -- every petal sits at the *full* [maxRadiusKm] from [start]
 * (there's no reason to use less of the allowed area), so the only knob left to
 * tune against the target duration is how many petals there are, not how far
 * out any single one reaches. A route with several petals in different
 * directions from [start] routinely has to backtrack through the same nearby
 * roads to get from one petal to the next -- which is exactly the "may need to
 * drive over the same roads to stay within the radius" behaviour this was
 * built for, produced by the geometry itself rather than anything explicitly
 * forcing a repeat.
 *
 * [spokeCount] starts from a direct estimate (total distance implied by
 * [targetSeconds] at [AVG_SPEED_KMH], divided by one petal's out-and-back
 * distance) rather than iterating up from zero -- same reasoning as
 * [initialRadiusKm] for the unconstrained case, just applied to a petal count
 * instead of a single radius. That total-distance estimate now subtracts the
 * mandatory [start]->[destination] leg's own straight-line distance first --
 * confirmed as a real, systematic overshoot bug when [destination] isn't
 * close to [start] (a real point-to-point trip, not a loop back to start):
 * the old estimate assumed *all* of the target distance had to come from the
 * petals, then added the start->destination leg on top of that regardless,
 * double-counting whatever distance that leg itself covers. Corey report: 110
 * min target, 30km radius, generated 168min (also flagged as exceeding the
 * radius -- petals sitting at the full 30km from start, connected by a real
 * road path rather than a straight line, easily bulge past that circle en
 * route to a destination that's a meaningful fraction of the radius away).
 * The same target at 35km radius came out accurate -- not because this bug
 * wasn't present, just because the destination leg was a smaller fraction of
 * a bigger radius, so the double-counting mattered less. This is a
 * straight-line estimate (the real road distance will usually be a bit
 * more), same approximation level as the rest of this initial guess -- it
 * only needs to be in the right ballpark, the convergence loop below still
 * corrects from there. */
private suspend fun refineCandidateWithinRadius(
    start: LatLng,
    destination: LatLng,
    bearingDegrees: Double,
    maxRadiusKm: Double,
    targetSeconds: Double,
    avoidHighways: Boolean,
): GeneratedRoute? {
    val directLegKm = distanceKm(start, destination)
    val totalDistanceNeededKm = (AVG_SPEED_KMH * (targetSeconds / 3600.0) - directLegKm).coerceAtLeast(0.0)
    var spokeCount = ceil(totalDistanceNeededKm / (2.0 * maxRadiusKm)).toInt().coerceIn(0, MAX_SPOKES)
    // Pulled in slightly (not maxRadiusKm itself) only when a petal lands
    // somewhere unroutable (water, no road access) -- see the routed == null
    // branch below. Otherwise stays at the full allowed radius every round.
    var spokeRadiusKm = maxRadiusKm
    // Also rotated (not just shrunk) on a failed round -- confirmed live that
    // a failure here is Geoapify's real "No suitable edges near location"
    // (one petal landed in water/a park/etc.), not a transient error. Shrinking
    // radius alone keeps every petal pointed in the exact same directions,
    // which does nothing if what's blocking a petal is still within the
    // smaller radius too (a real risk for any start point near coastline or a
    // large park -- confirmed as a real, reproduced case, not hypothetical).
    // Rotating the whole ring by an offset that isn't a clean fraction of 360
    // (so it doesn't just relabel the same directions) gives every retry an
    // actual chance to land the blocked petal somewhere routable instead of
    // repeating the same doomed direction closer to start each time.
    var currentBearingDegrees = bearingDegrees
    // How wide a fan the petals are spread across -- starts at a full circle
    // (spokePoints' own default) and only narrows in reaction to repeated
    // full-ring failures (see the attempt-retry loop below). Confirmed real
    // via Corey's Wollongong report: a coastal-city start with an escarpment/
    // national park on the other side has whole arcs of the compass with no
    // road network at all, so evenly-spaced petals around the *full* circle
    // kept landing at least one petal in a dead zone regardless of how the
    // ring was rotated (target 106min/77min, 30km radius, generated ~3h+ both
    // times -- Logcat showed the overwhelming majority of routing attempts,
    // across many different rotations, failing with Geoapify's "No suitable
    // edges near location", not just one unlucky petal).
    var spreadDegrees = 360.0
    var best: GeneratedRoute? = null
    // See refineCandidate's own comment on the exact same bug -- `best` used
    // to be unconditionally overwritten every round, so a later, worse round
    // could clobber an earlier, better one, and if the round after *that*
    // then failed outright, `best` was left holding the worse result. Real,
    // confirmed contributor to a generated route landing at ~1 minute for a
    // 1-2 hour target: a degenerate 0-petal (direct start-to-destination,
    // skipping the detour loop entirely) round can measure as a short
    // duration, and if the *next* round (retrying with petals again) then
    // hit a routing failure, that short direct-route result was what got
    // returned as "best" even though it was never actually the closest one.
    var bestErrorSeconds = Double.MAX_VALUE
    var converged = false
    repeat(MAX_RADIUS_ITERATIONS) { iteration ->
        if (converged) return@repeat
        // A routing failure (an unroutable petal) used to shrink+rotate and
        // then immediately hand control back to the *outer* loop, burning one
        // of only MAX_RADIUS_ITERATIONS=3 precious duration-convergence
        // rounds on what's really just "try again with a nudged ring" --
        // confirmed as a real cause of a route landing well short of target
        // (Corey report: 70min target, 30km radius, generated 41min, with
        // Geoapify's "No suitable edges near location" recurring in Logcat).
        // Walking through it: spokeCount starts at 1 (a direct estimate from
        // maxRadiusKm), round 1 succeeds at 41min, ratio-adjustment bumps
        // spokeCount to 2 for round 2 -- but if *that* round's 2-petal ring
        // hits an unroutable petal, the old code spent round 2 shrinking/
        // rotating and round 3 (the last one) either also failed or, even if
        // it succeeded, measured a worse-converged result than round 1's --
        // leaving round 1's 41min as `best` with the convergence budget
        // already exhausted. Fixed: retry an unroutable ring several times
        // *inside* one outer iteration (shrinking+rotating each attempt, same
        // as before) before giving up on it, so a single bad petal placement
        // doesn't cost an entire duration-convergence round.
        var routed: RoutedPath? = null
        var attempt = 0
        while (routed == null && attempt < MAX_UNROUTABLE_RETRIES) {
            val chain = buildList {
                add(start)
                addAll(spokePoints(start, currentBearingDegrees, spokeCount, spokeRadiusKm, spreadDegrees))
                add(destination)
            }
            routed = try {
                fetchRoutedPaths(chain, avoidHighways = avoidHighways).firstOrNull()
            } catch (e: Exception) {
                Log.e(
                    LOG_TAG,
                    "Radius-confined routing call failed (bearing=$currentBearingDegrees, iteration=$iteration, " +
                        "attempt=$attempt, spokes=$spokeCount, spokeRadius=${"%.2f".format(spokeRadiusKm)}km, " +
                        "spread=${"%.0f".format(spreadDegrees)}deg)",
                    e,
                )
                null
            }
            // See MIN_PLAUSIBLE_DURATION_SECONDS' own comment -- treated the
            // same as an outright routing failure below (reset to null so the
            // while condition retries), never accepted as a real result.
            if (routed != null && routed.durationSeconds < MIN_PLAUSIBLE_DURATION_SECONDS) {
                Log.e(
                    LOG_TAG,
                    "refineCandidateWithinRadius: rejecting implausibly short duration=${routed.durationSeconds}s " +
                        "(bearing=$currentBearingDegrees, iteration=$iteration, attempt=$attempt, spokes=$spokeCount)",
                )
                routed = null
            }
            if (routed == null) {
                // At least one petal likely landed somewhere unroutable --
                // shrink *and* rotate the whole ring (see
                // currentBearingDegrees' own comment above) and retry, still
                // within this same outer iteration. Also narrow the spread
                // (see spreadDegrees' own comment) -- shrinking/rotating alone
                // still spaces petals across the full circle, which does
                // nothing if the blocked arc (ocean, escarpment, etc.) is
                // wider than the gap rotation alone can dodge.
                spokeRadiusKm *= 0.85
                currentBearingDegrees += 47.0
                spreadDegrees = (spreadDegrees * 0.6).coerceAtLeast(MIN_SPREAD_DEGREES)
                attempt++
            }
        }
        if (routed == null) {
            // Every retry at this spokeCount was unroutable -- rather than
            // silently returning to the outer loop with nothing to show for
            // this whole iteration (still stuck at the same spokeCount next
            // time), back off by one petal as a last resort so the next
            // outer iteration has an actual chance to land somewhere
            // routable instead of repeating the exact same doomed count.
            if (spokeCount > 0) spokeCount -= 1
            return@repeat
        }
        val ratio = targetSeconds / routed.durationSeconds.coerceAtLeast(1.0)
        val errorSeconds = abs(routed.durationSeconds - targetSeconds)
        if (errorSeconds < bestErrorSeconds) {
            best = GeneratedRoute(routed.points, routed.durationSeconds, routed.distanceMeters)
            bestErrorSeconds = errorSeconds
        }
        Log.d(
            LOG_TAG,
            "refineCandidateWithinRadius: bearing=$currentBearingDegrees iteration=$iteration spokes=$spokeCount " +
                "spokeRadius=${"%.2f".format(spokeRadiusKm)}km spread=${"%.0f".format(spreadDegrees)}deg " +
                "duration=${"%.1f".format(routed.durationSeconds / 60.0)}min " +
                "target=${"%.1f".format(targetSeconds / 60.0)}min ratio=${"%.2f".format(ratio)}",
        )
        if (errorSeconds < DURATION_TOLERANCE_SECONDS) {
            converged = true
        } else if (spokeCount == 0) {
            // spokeCount * ratio can never climb back up from zero (0 * any
            // ratio is still 0) -- if the direct start-to-destination leg
            // alone already missed the target without converging, the next
            // thing to try is exactly one petal, not "0 forever".
            spokeCount = 1
        } else {
            // The knob here is petal *count*, not reach -- reach is already
            // maxed out at maxRadiusKm every round.
            spokeCount = round(spokeCount * ratio).toInt().coerceIn(0, MAX_SPOKES)
        }
    }

    return best
}

/** [count] points spread [spreadDegrees] apart in total starting at
 * [seedBearing], each [radiusKm] from [anchor] -- the "petals" of a
 * radius-confined loop (see [refineCandidateWithinRadius]). Empty for
 * [count] <= 0 (no petals needed -- the direct start-to-destination leg alone
 * already covers the target duration).
 *
 * [spreadDegrees] defaults to a full 360 -- exactly the original behaviour
 * (petals evenly wrapped around the whole compass). [refineCandidateWithinRadius]
 * narrows it below 360 when a full-circle ring keeps failing to route: a start
 * point near a coastline or a mountain escarpment (a real, common NSW shape --
 * Wollongong confirmed live: ocean on one side, the Illawarra escarpment/
 * national park on the other) can have whole arcs of the compass with no road
 * network at all within [radiusKm], no matter how the ring is rotated -- evenly
 * spacing petals around the *full* circle then guarantees some of them land in
 * that dead zone. A narrower fan gives every petal a real chance to land within
 * whatever arc actually has roads. */
private fun spokePoints(anchor: LatLng, seedBearing: Double, count: Int, radiusKm: Double, spreadDegrees: Double = 360.0): List<LatLng> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(offset(anchor, seedBearing, radiusKm))
    val stepDegrees = spreadDegrees / count
    return (0 until count).map { i -> offset(anchor, seedBearing + i * stepDegrees, radiusKm) }
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
 * the routing API's geometry is dense enough (confirmed live: ~29m average
 * vertex spacing on a real test route) that this is close enough for scoring
 * purposes. */
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

/** Public wrapper on [approxDistanceMeters] so callers (see
 * GenerateRouteScreen.kt's Overpass scoring-radius sizing) can reason about
 * real-world distance without duplicating the equirectangular approximation. */
fun distanceKm(a: LatLng, b: LatLng): Double = approxDistanceMeters(a, b) / 1000.0

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
