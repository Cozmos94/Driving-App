package com.instructor.lessonroutes.data.routegen

import android.util.Log
import com.instructor.lessonroutes.data.remote.ReachableArea
import com.instructor.lessonroutes.data.remote.RoutedPath
import com.instructor.lessonroutes.data.remote.contains
import com.instructor.lessonroutes.data.remote.fetchReachableArea
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
 * uses supports a real avoid/prefer-zone constraint for roundabouts
 * specifically. See the doc comments on
 * OverpassApi.fetchRoundabouts/fetchMajorRoads. */
enum class FilterPreference { NONE, AVOID, PREFER }

/** Display text for the filter dropdown (GenerateRouteScreen.kt's FilterRow) --
 * NONE reads as "No Preference" there, not the bare enum name. */
fun FilterPreference.displayLabel(): String = when (this) {
    FilterPreference.NONE -> "No Preference"
    FilterPreference.AVOID -> "Avoid"
    FilterPreference.PREFER -> "Prefer"
}

data class RouteGenerationFilters(
    val incidents: FilterPreference = FilterPreference.NONE,
    val constructionZones: FilterPreference = FilterPreference.NONE,
    val schoolZones: FilterPreference = FilterPreference.NONE,
    val speedCameras: FilterPreference = FilterPreference.NONE,
    val highways: FilterPreference = FilterPreference.NONE,
    val roundabouts: FilterPreference = FilterPreference.NONE,
    val highTraffic: FilterPreference = FilterPreference.NONE,
)

/** Every filter category's display name, in the same fixed order used
 * everywhere a full set of categories is shown or reasoned about (the filter
 * list UI, [RouteGenerationFilters.summarize], and student-profile lifetime
 * coverage on the route list screen). Merging lanes removed per Corey: "it
 * just doesn't make sense" as an obstacle category -- its data (OverpassApi.
 * kt's now-deleted fetchMergeLaneProxies) was always a real approximation,
 * not real merge-lane data: OSM has no dedicated tag for one, so it proxied
 * highway on/off-ramps (motorway_link/trunk_link) instead, which doesn't
 * even distinguish merging in from exiting, or cover ordinary lane-merges on
 * non-highway roads at all. */
val ALL_FILTER_LABELS = listOf(
    "Hazards", "Construction zones", "School zones", "Speed cameras",
    "High traffic roads", "Highways", "Roundabouts",
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
        listOf(incidents, constructionZones, schoolZones, speedCameras, highTraffic, highways, roundabouts),
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
    val majorRoads: List<LatLng> = emptyList(),
    val highTraffic: List<LatLng> = emptyList(),
)

data class GeneratedRoute(
    val points: List<LatLng>,
    val durationSeconds: Double,
    val distanceMeters: Double,
    // The exact ordered waypoints (start + petal(s)/detour point + destination)
    // that produced this route via fetchRoutedPaths -- empty for a
    // GeneratedRoute built somewhere that never had a real chain (e.g.
    // TomTomNavigationScreen.kt's placeholder for a saved route, which has no
    // stored duration/distance either). Needed by [rerouteAvoidingHits] to
    // re-request the *same* route with specific points hard-avoided --
    // without this, there'd be no way to redo the routing call that produced
    // a given candidate after the fact.
    val waypointChain: List<LatLng> = emptyList(),
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
// by construction. Left at 3 here too -- this is the duration-convergence
// accuracy lever, kept separate on purpose from MAX_UNROUTABLE_RETRIES below,
// which is the one actually cut for speed this round.
//
// Bearings cut 3->2 (0/180) for speed, then REVERTED back to 3 (0/120/240)
// -- confirmed live to be a real regression, not a neutral speed trade.
// Routing has consistently finished in well under 5s in every live test this
// session (as low as 399ms) regardless of bearing count -- it was never the
// actual bottleneck (Overpass scoring was, see GenerateRouteScreen.kt). The
// 2-bearing set also put both remaining bearings on the *same* axis (0/180),
// which in constrained/coastal geography can mean both point toward the same
// kind of unroutable terrain. Live-tested directly against Geoapify at
// Corey's real 35km-radius Wollongong case: bearings 0, 120, and 180 all
// failed ("No suitable edges near location"), but bearing 240 -- present in
// the old 3-bearing set, dropped by the 2-bearing cut -- succeeded with a
// real ~80min route. With only [0, 180], that generation had zero working
// candidates; with [0, 120, 240] it has one. Restored to 3 for reliability;
// there was no real speed to trade away in the first place.
private val CANDIDATE_BEARINGS_DEGREES = listOf(0.0, 120.0, 240.0)

// Public alias so GenerateRouteScreen.kt can pass this explicitly alongside
// EXPANDED_CANDIDATE_BEARINGS_DEGREES below when choosing which to request,
// rather than needing generateCandidateRoutes' own default parameter value
// to be inspectable from outside this file.
val DEFAULT_CANDIDATE_BEARINGS_DEGREES = CANDIDATE_BEARINGS_DEGREES

// Used instead of CANDIDATE_BEARINGS_DEGREES by GenerateRouteScreen.kt when
// any category is set to Avoid -- Corey: "This app needs to completely avoid
// filtered obstacles, no matter what." A true, unconditional guarantee would
// need this app to run its own offline routing engine over a full downloaded
// road-network graph with hard node exclusions -- a genuinely enormous
// undertaking (processing/hosting regional OSM extracts, building/
// maintaining a routing graph) wildly disproportionate to this app, and it
// still couldn't invent a road that doesn't exist if a destination
// genuinely has exactly one physical way in. What this *can* do, on top of
// generateCandidateRoutes' third-party black-box routing API: try enough
// genuinely different candidate shapes that finding one that naturally
// avoids an obstacle (rather than fighting the one 3-bearing search happened
// to try) becomes realistic -- 6 evenly-spaced bearings instead of 3, run
// concurrently (see generateCandidateRoutes' own `async`/`awaitAll`), so the
// wall-clock cost is dominated by the slowest single bearing, not their sum.
val EXPANDED_CANDIDATE_BEARINGS_DEGREES = listOf(0.0, 60.0, 120.0, 180.0, 240.0, 300.0)
private const val MAX_RADIUS_ITERATIONS = 3
// How many shrink+rotate retries an unroutable petal ring gets *within* one
// outer duration-convergence iteration before that iteration gives up -- see
// refineCandidateWithinRadius's own comment on why this needs to be separate
// from MAX_RADIUS_ITERATIONS (a routing failure used to cost a whole
// convergence round, which -- with only MAX_RADIUS_ITERATIONS of those total
// -- could exhaust the entire budget on retries alone, stranding `best` on
// an early, badly-off result).
//
// Cut 3->2 for speed, then REVERTED back to 3 -- confirmed live (alongside
// the CANDIDATE_BEARINGS_DEGREES revert above) that routing itself was never
// the actual speed bottleneck (399ms-5s in every live test this session,
// Overpass scoring was the real bottleneck). Fewer retries only means less
// budget to rotate away from a bad direction or decay spokeCount down to the
// simple direct-route fallback within MAX_RADIUS_ITERATIONS's outer budget --
// a real reliability cost for a speed benefit that was never actually there.
// Worst case per bearing goes back up from x2 x 6s = 36s to
// MAX_RADIUS_ITERATIONS x 3 x 6s = 54s, but this loop already backs off
// (shrinks the radius, rotates the ring 47 degrees, narrows the spread) on
// every failed attempt, so the extra try is a genuinely different placement,
// not wasted repetition -- and it's still bounded by the independent
// ROUTING_GENERATION_TIMEOUT_MS ceiling regardless.
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
    // Overridable so GenerateRouteScreen.kt can request EXPANDED_CANDIDATE_
    // BEARINGS_DEGREES instead when any category is set to Avoid -- more
    // candidate shapes means a real chance of finding one that naturally
    // avoids an obstacle, not just whichever of 3 fixed directions happened
    // to be tried. Defaults to the fast/normal set so the common "no Avoid
    // filters set" case pays no extra cost for this.
    bearings: List<Double> = CANDIDATE_BEARINGS_DEGREES,
): List<GeneratedRoute> = coroutineScope {
    val targetSeconds = targetDurationMinutes * 60.0

    // Confirmed live as a real, root-level failure mode, distinct from every
    // other bug found this session: `start` can be close to a real road
    // (within ~1km, confirmed live -- not remote/roadless like this
    // generator's own established hard cases) yet Geoapify's routing API
    // still rejects it outright with "No suitable edges near location" if
    // it's not close enough to snap cleanly onto that road. Because `start`
    // is the one waypoint every single candidate chain shares, this fails
    // *every* bearing at once regardless of how well petals are placed --
    // no amount of petal-placement cleverness (isoline-informed or not)
    // matters if the chain's own first point can't route anywhere. A live
    // device's GPS fix can easily drift this far off a road (inside a
    // building, a wide road's far lane, dense tree cover), so this isn't
    // just a test-environment quirk. See snapStartToRoutableGround's own
    // doc comment for the fix.
    val effectiveStart = snapStartToRoutableGround(start, destination, avoidHighways)

    // Hoisted out of the `if` branch below (was a local `val` there) so the
    // expanded-bearings-failed fallback further down -- which needs to reuse
    // the exact same isoline data for its own retry, not re-fetch it -- can
    // still see it. Stays null when maxRadiusKm is null (the fallback only
    // ever runs in radius-confined mode anyway, since refineCandidate's own
    // unconstrained mode never uses this).
    var reachableArea: ReachableArea? = null

    var candidates = if (maxRadiusKm != null) {
        // start/destination logged explicitly here -- confirmed needed live:
        // the actual failing routing request is the full [start, petal(s),
        // destination] chain, not just the petal in isolation. A petal that
        // routes fine on its own (start->petal) can still make the *whole*
        // chain fail if start->petal or petal->destination doesn't, and
        // without these coordinates a live reproduction can only guess at
        // where destination actually is instead of testing the exact
        // failing request.
        Log.d(
            LOG_TAG,
            "generateCandidateRoutes: radius-confined mode, target=${targetDurationMinutes}min, " +
                "maxRadiusKm=$maxRadiusKm, bearings=${bearings.size}, avoidHighways=$avoidHighways, " +
                "start=${effectiveStart.latitude},${effectiveStart.longitude} (originally ${start.latitude},${start.longitude}), " +
                "destination=${destination.latitude},${destination.longitude}",
        )
        // Fetched ONCE per generation (shared across every bearing, since
        // they all place petals relative to the same `start`) rather than
        // per-bearing or per-retry -- confirmed live this is a real, single
        // ~2-4s call even at a 35km range. Everything downstream (spokePoints,
        // via refineCandidateWithinRadius) uses this to place petals on
        // ground actually reachable by road instead of guessing a raw
        // bearing+radius point and finding out via a failed routing call --
        // see fetchReachableArea's own doc comment for why. Null on any
        // failure (network, timeout, bad response) -- refineCandidateWithinRadius
        // falls back to the pre-existing blind-guess+shrink+rotate-retry
        // behaviour in that case, so a stuck isoline fetch degrades this
        // generation's reliability back to what it was before, not below it.
        // Self-consistency check: `start` is geometrically guaranteed to be
        // reachable from itself (it's literally the isoline's own query
        // origin) -- if it comes back as NOT contained, something about the
        // fetch/parse/decimation pipeline produced a corrupted shape, not a
        // real geographic finding. Confirmed live as a real, serious bug:
        // one generation had every single bearing fail at every retry, all
        // the way down to an 8km search radius, immediately after this
        // feature shipped -- a decimated hole ring (see ReachableArea's own
        // doc comment) had almost certainly distorted enough to wrongly
        // swallow ground right next to `start` itself. Rather than trust a
        // reachable-area result that fails this basic sanity check, discard
        // it entirely and fall back to the pre-existing blind-guess behaviour
        // -- a known-working (if less precise) baseline beats acting on data
        // that's already proven internally inconsistent.
        val fetchedArea = fetchReachableArea(effectiveStart, maxRadiusKm)
        reachableArea = fetchedArea?.takeIf { it.contains(effectiveStart) }
        Log.d(
            LOG_TAG,
            "generateCandidateRoutes: isoline reachable-area fetch " + when {
                fetchedArea == null -> "failed/timed out -- falling back to blind bearing guesses"
                reachableArea == null -> "succeeded but failed self-consistency check (start not contained in its own " +
                    "isoline) -- discarding it, falling back to blind bearing guesses"
                else -> "succeeded"
            },
        )
        retryIfEmpty {
            bearings
                .map { bearing ->
                    async {
                        refineCandidateWithinRadius(effectiveStart, destination, bearing, maxRadiusKm, targetSeconds, avoidHighways, reachableArea)
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    } else {
        val base = midpoint(effectiveStart, destination)
        val initialRadiusKm = initialRadiusKm(targetDurationMinutes)
        // start/destination logged here too now, same as the radius-confined
        // branch above -- a real, confirmed gap: without these coordinates
        // there was no way to tell, after the fact, whether a route that
        // looked like it "didn't start near" a searched starting address
        // actually used that address at all (e.g. the search box was typed
        // into but never confirmed by tapping a result) versus genuinely
        // using it but swinging the detour far from it anyway (a short
        // start/destination distance wanting a long target duration forces a
        // detour point that can orbit well away from `base`, which itself
        // can already be well away from either endpoint).
        Log.d(
            LOG_TAG,
            "generateCandidateRoutes: target=${targetDurationMinutes}min, " +
                "initialRadius=${"%.2f".format(initialRadiusKm)}km, bearings=${bearings.size}, " +
                "avoidHighways=$avoidHighways, " +
                "start=${effectiveStart.latitude},${effectiveStart.longitude}, " +
                "destination=${destination.latitude},${destination.longitude}",
        )
        retryIfEmpty {
            bearings
                .map { bearing ->
                    async {
                        refineCandidate(effectiveStart, destination, base, bearing, initialRadiusKm, targetSeconds, avoidHighways)
                    }
                }
                .awaitAll()
                .filterNotNull()
        }
    }

    // Real, confirmed regression from EXPANDED_CANDIDATE_BEARINGS_DEGREES
    // itself: live-tested at a real 10km-radius Wollongong case (coastal +
    // escarpment, already this generator's own established hard geography),
    // 4 of the 6 evenly-spread bearings genuinely had no reachable road at
    // all in that direction, while the *old* 3-bearing set happened to land
    // on 2 of the exact 2 that did work here -- more bearings doesn't just
    // fail to help in this kind of geography, it actively hurts, by putting
    // more of the search into directions with no road network at all,
    // competing for the same MAX_UNROUTABLE_RETRIES budget each. Confirmed
    // live via Corey's own report: identical setup failed outright in ~5s
    // (too fast to be ROUTING_GENERATION_TIMEOUT_MS firing -- consistent
    // with every bearing failing fast on a real "No suitable edges"
    // rejection, not a slow timeout) until moving the trip start further
    // inland, away from the dead zone, fixed it.
    //
    // Fixed the same way this file already handles every other "the fancier
    // path didn't pan out" case (the OSRM outage fallback, the isoline
    // self-consistency fallback): fall back to the plain default bearing set
    // if the expanded one found nothing, rather than failing outright. Only
    // fires when `bearings` was actually something other than the default
    // (skips the redundant retry in the common, non-Avoid-filter case).
    if (candidates.isEmpty() && bearings !== CANDIDATE_BEARINGS_DEGREES) {
        Log.w(
            LOG_TAG,
            "generateCandidateRoutes: expanded bearing set (${bearings.size}) found zero candidates -- " +
                "falling back to the default ${CANDIDATE_BEARINGS_DEGREES.size}-bearing set",
        )
        candidates = if (maxRadiusKm != null) {
            retryIfEmpty {
                CANDIDATE_BEARINGS_DEGREES
                    .map { bearing ->
                        async {
                            refineCandidateWithinRadius(effectiveStart, destination, bearing, maxRadiusKm, targetSeconds, avoidHighways, reachableArea)
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }
        } else {
            val base = midpoint(effectiveStart, destination)
            val initialRadiusKm = initialRadiusKm(targetDurationMinutes)
            retryIfEmpty {
                CANDIDATE_BEARINGS_DEGREES
                    .map { bearing ->
                        async {
                            refineCandidate(effectiveStart, destination, base, bearing, initialRadiusKm, targetSeconds, avoidHighways)
                        }
                    }
                    .awaitAll()
                    .filterNotNull()
            }
        }
    }

    Log.d(LOG_TAG, "generateCandidateRoutes: ${candidates.size} candidate route(s) from ${bearings.size} bearings")
    candidates
}

// Real, observed failure mode, distinct from a genuine geographic dead-end:
// Corey reported "the routing service didn't return a route in time" on a
// physical device, then confirmed it was "fixed after regenerating" -- a
// plain manual retry, with absolutely nothing else changed, succeeded. That
// points to a transient hiccup (a brief Geoapify slowdown, a momentary rate
// limit, one dropped connection) rather than a real "no route exists here"
// case, which wouldn't be fixed by simply asking again. Rather than make
// the instructor do that by hand every time, retry the whole bearing search
// automatically once if it comes back completely empty.
private const val MAX_GENERATION_ATTEMPTS = 2

/** Runs [block] (the whole per-bearing candidate search for one generation
 * mode) up to [MAX_GENERATION_ATTEMPTS] times, stopping as soon as one
 * attempt returns anything. This can only ever help, never hurt: a
 * genuinely-failing case (no route exists, or every attempt times out)
 * still ends up empty after the same amount of real work it would have
 * done anyway, just spread across up to two attempts instead of one -- it
 * doesn't add its own extra timeout or retry budget beyond what each
 * attempt already has, so a slow first attempt that already consumed the
 * caller's own ROUTING_GENERATION_TIMEOUT_MS budget (GenerateRouteScreen.kt)
 * simply won't get a second attempt in before that outer deadline fires,
 * same as before this existed. */
private suspend fun retryIfEmpty(block: suspend () -> List<GeneratedRoute>): List<GeneratedRoute> {
    var result = block()
    var attempt = 1
    while (result.isEmpty() && attempt < MAX_GENERATION_ATTEMPTS) {
        Log.w(
            LOG_TAG,
            "generateCandidateRoutes: attempt $attempt returned zero candidates -- retrying once " +
                "(confirmed live that a transient failure like this can succeed on a plain retry)",
        )
        attempt++
        result = block()
    }
    return result
}

// Tried in increasing order -- confirmed live that a real failing case had
// usable road within 1km in most directions, so two tiers already covers
// the realistic "close but not quite on a road" case (GPS drift, a
// manually-set test location) well. Deliberately kept to just two rather
// than searching further out: each tier costs up to a full
// GeoapifyRoutingApi client timeout (6s) in the worst case (every candidate
// in that ring fails), and this whole check sits inside
// ROUTING_GENERATION_TIMEOUT_MS's budget in GenerateRouteScreen.kt -- a
// third tier would risk this check alone consuming most of that budget
// before the real bearing search even starts. Past ~1km, "not quite on a
// road" is blurring into "genuinely remote" anyway (this generator's own
// established hard case, e.g. deep in a national park), where expanding the
// search only delays an outcome that's going to fail regardless.
private val START_SNAP_DISTANCE_TIERS_KM = listOf(0.3, 1.0)

// A full compass rose per tier -- cheap to check (each is a single 2-point
// routing call, not a full chain) and run concurrently, so testing all 8
// costs about the same wall-clock time as testing 1.
private val START_SNAP_BEARINGS_DEGREES = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0)

/**
 * Confirms [start] is close enough to a real road for Geoapify's routing API
 * to actually accept it, returning a nearby point that does route if it
 * isn't -- see [generateCandidateRoutes]'s own comment on why this matters:
 * `start` is shared by every candidate chain, so a `start` that can't route
 * at all fails the *entire* generation regardless of how well anything else
 * (petal placement, bearing choice) is done.
 *
 * Tries [start] unmodified first -- a single cheap check, and the common
 * case (a real GPS fix or address search result is normally already close
 * enough) pays no further cost. Only on failure does it search outward in
 * [START_SNAP_DISTANCE_TIERS_KM] rings, testing all
 * [START_SNAP_BEARINGS_DEGREES] at each ring concurrently and stopping at
 * the first ring where anything succeeds (returns whichever candidate in
 * that ring is closest to the original [start], to distort the intended
 * starting point as little as possible). Falls back to the original,
 * unmodified [start] if nothing within the full search succeeds -- the
 * existing per-bearing failure handling downstream surfaces that the same
 * way it always has, so this is a pure reliability improvement, never a
 * regression: it can only turn a would-be total failure into a success, not
 * the reverse.
 */
private suspend fun snapStartToRoutableGround(start: LatLng, destination: LatLng, avoidHighways: Boolean): LatLng = coroutineScope {
    suspend fun isRoutable(point: LatLng): Boolean =
        try {
            fetchRoutedPaths(listOf(point, destination), avoidHighways = avoidHighways).firstOrNull() != null
        } catch (e: Exception) {
            false
        }

    if (isRoutable(start)) return@coroutineScope start

    Log.w(
        LOG_TAG,
        "snapStartToRoutableGround: start=${start.latitude},${start.longitude} isn't directly routable to " +
            "destination=${destination.latitude},${destination.longitude} -- searching nearby for usable ground",
    )
    for (distanceKm in START_SNAP_DISTANCE_TIERS_KM) {
        val ringResults = START_SNAP_BEARINGS_DEGREES.map { bearing ->
            val candidate = offset(start, bearing, distanceKm)
            async { candidate.takeIf { isRoutable(it) } }
        }.awaitAll().filterNotNull()
        if (ringResults.isNotEmpty()) {
            // All candidates in a ring are equidistant from `start` by
            // construction, so any of them is an equally good pick -- just
            // take the first that succeeded.
            val chosen = ringResults.first()
            Log.w(
                LOG_TAG,
                "snapStartToRoutableGround: found routable ground ${"%.2f".format(distanceKm)}km from the original " +
                    "start (${chosen.latitude},${chosen.longitude}) -- using it instead",
            )
            return@coroutineScope chosen
        }
    }
    Log.e(
        LOG_TAG,
        "snapStartToRoutableGround: no routable ground found within " +
            "${"%.2f".format(START_SNAP_DISTANCE_TIERS_KM.last())}km of start -- falling back to the original, " +
            "unmodified start (generation will likely fail the same way it would have without this check)",
    )
    start
}

/** No longer called from GenerateRouteScreen.kt's main flow -- superseded by
 * [pickBestAvoidanceAwareRoute] (a strict superset: with no category set to
 * Avoid, every candidate ties at zero violations and it falls through to the
 * same Prefer/duration weighting this function used, so behavior is
 * unchanged in that case). Kept in place, not deleted, for its own
 * historical doc comment below (the real duration-scoring bug it fixed) and
 * because it's still a coherent, independently-useful/-testable function on
 * its own -- same reasoning this project already applies to CreateRouteScreen.kt/
 * FollowScreen.kt staying in place unwired.
 *
 * Picks whichever of [candidates] best matches [filters] (proximity-scored
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

// Each iteration is one more real Geoapify call (well under 1s in every live
// test, even with a double-digit avoid-location count) -- 4 comfortably fits
// alongside everything else already inside GenerateRouteScreen.kt's overall
// generation budget.
private const val MAX_AVOIDANCE_REROUTE_ITERATIONS = 4

// Safety ceiling on the *cumulative* avoid-location list across every
// iteration -- confirmed live that avoiding many points along the same
// corridor can swing a route's shape a lot (a real stress test: 15 points
// along one route's own path produced a genuinely different, shorter route,
// not just a tweak), so this exists to stop an unusually obstacle-dense area
// from growing the avoid-list without bound. Also now confirmed live as a
// real, reachable ceiling, not just a hypothetical one: a 5km-radius, 12-
// waypoint loop in dense inner-Wollongong hit this exact cap.
private const val MAX_AVOID_LOCATIONS = 40

// How many *new* hit points a single reroute attempt adds to the cumulative
// avoid-list, per round -- confirmed live as the real fix for "No path could
// be found for input": the previous version added every currently-hit point
// in one shot, which for a dense area/many-waypoint chain could mean a single
// request asking Geoapify to avoid dozens of roundabouts simultaneously,
// something it can (and did) reject outright as globally unroutable,
// discarding the whole attempt including nearby-the-start obstacles that a
// smaller ask could likely have avoided on their own. A modest batch size
// keeps each individual request's own feasibility risk low while still
// making real per-round progress (not literally one at a time, which would
// need far more than MAX_AVOIDANCE_REROUTE_ITERATIONS rounds for a dense
// case) -- combined with the closest-to-start priority order above, the
// obstacles that matter most get the smallest, most likely-to-succeed
// requests first.
private const val MAX_NEW_AVOID_LOCATIONS_PER_ITERATION = 8

/** Outcome of [rerouteAvoidingHits]: the (possibly improved) route, the
 * display-name label (see [ALL_FILTER_LABELS]) of every active Avoid
 * category that still has at least one hit on it after every reroute attempt
 * (empty means every active Avoid category was fully steered around), and
 * [remainingHitCount] -- the total number of individual points still hit
 * across every category, not just how many categories -- used by
 * [pickBestAvoidanceAwareRoute] to rank several outcomes by violation
 * severity, not just violation presence. Callers should surface a non-empty
 * [unavoidableCategories] honestly (e.g. "couldn't fully avoid: X — no
 * alternative route was possible") rather than silently presenting a route
 * that still violates a filter the instructor explicitly set to Avoid. */
data class AvoidanceOutcome(val route: GeneratedRoute, val unavoidableCategories: List<String>, val remainingHitCount: Int)

/**
 * Iteratively re-requests [route]'s own [GeneratedRoute.waypointChain], hard-avoiding
 * (Geoapify's real `avoid=location:lat,lon`, confirmed live) every point any *active
 * Avoid* category (Roundabouts, High traffic roads, Hazards, Construction zones, School
 * zones, Speed cameras -- Highways is excluded, it's already a real hard
 * constraint applied during generation itself via [avoidHighways]) actually hits on the
 * route, repeating until either nothing is left to avoid, an attempt makes no further
 * progress, or [MAX_AVOIDANCE_REROUTE_ITERATIONS]/[MAX_AVOID_LOCATIONS] is reached.
 *
 * **Avoid is now treated as a real priority over both Prefer and duration-matching, not a
 * soft nudge that backs off once it costs time** -- a direct, explicit correction from an
 * earlier version of this function (roundabouts-only, and it refused any reroute that
 * pushed duration outside [DURATION_TOLERANCE_SECONDS] of the original). Corey, after
 * testing that version: "We can't have a 'pick the closest matching candidate rather than
 * steering around a specific obstacle' approach. We literally have to steer around the
 * obstacles filtered if they have been set to avoid. That is the whole point of this app."
 * -- and, on Avoid/Prefer conflicts specifically: "we need to prioritise the avoidance over
 * the preference." This version has **no duration-based rejection at all** (only the
 * existing [MIN_PLAUSIBLE_DURATION_SECONDS] degenerate-result floor every other routing
 * result in this file already gets) -- a reroute that genuinely avoids more is always kept,
 * however much longer it makes the trip.
 *
 * This also implements "Avoid overrides Prefer" as an emergent property rather than needing
 * separate logic for it: whatever influenced a candidate's original pick, this function
 * unconditionally strips out every active Avoid category's hits from whatever chain it
 * used -- if the only way to stay near a Prefer-flagged point was through an Avoid-flagged
 * one, rerouting away from the Avoid point naturally routes away from that Prefer point too,
 * without this needing to know *why* the original route went where it did.
 *
 * Called on *every* candidate [generateCandidateRoutes] returns (see
 * [EXPANDED_CANDIDATE_BEARINGS_DEGREES], used instead of the normal, faster bearing set
 * whenever any category is Avoid), not just whichever one soft-scoring happened to favor --
 * [pickBestAvoidanceAwareRoute] then picks among the *results*, prioritizing fewest remaining
 * violations first. This is the direct fix for the "can't choose between several Prefer-
 * eligible options based on which one avoids an obstacle best" limitation this doc comment
 * used to describe as unsolved (Corey: "This app needs to completely avoid filtered
 * obstacles, no matter what"): trying several genuinely different candidate shapes up front,
 * then hard-avoiding on each independently before comparing them, means a shape that happens
 * to reach a Prefer-eligible point *without* needing an Avoid-flagged one has a real chance
 * to be found and picked, rather than this function only ever steering the one already-
 * favored (possibly worse-suited) shape. Not an unconditional guarantee -- that would need
 * this app to run its own offline routing engine over a full downloaded road-network graph
 * with hard exclusions, an enormous undertaking wildly disproportionate to this app, and it
 * still couldn't invent a road that doesn't exist if a destination genuinely has exactly one
 * physical way in -- but a real, substantially more thorough search than before, not just a
 * documented limitation left unaddressed.
 *
 * Still deliberately narrower than the broad "avoid every point in a whole category up
 * front, before any chain is even known to route at all" approach GeoapifyRoutingApi.kt's
 * own doc comment describes trying and reverting during exploratory generation (a real,
 * confirmed regression: a route reported as 0 minutes, then no route at all, when an
 * avoided point turned out to be the only way through a tight area) -- every reroute attempt
 * here starts from [route], which is already known to route successfully, so a failed or
 * degenerate attempt can always fall back to the last successful route rather than to
 * nothing at all.
 *
 * No-op (returns [route] unchanged, no network calls) if no category is both AVOID and has
 * any real scoring data, or if [route.waypointChain] has fewer than 2 points.
 */
suspend fun rerouteAvoidingHits(
    route: GeneratedRoute,
    filters: RouteGenerationFilters,
    scoringData: ScoringData,
    avoidHighways: Boolean,
): AvoidanceOutcome {
    val avoidCategories = buildList {
        if (filters.roundabouts == FilterPreference.AVOID) add("Roundabouts" to scoringData.roundabouts)
        if (filters.highTraffic == FilterPreference.AVOID) add("High traffic roads" to scoringData.highTraffic)
        if (filters.incidents == FilterPreference.AVOID) add("Hazards" to scoringData.incidents)
        if (filters.constructionZones == FilterPreference.AVOID) add("Construction zones" to scoringData.constructionZones)
        if (filters.schoolZones == FilterPreference.AVOID) add("School zones" to scoringData.schoolZones)
        if (filters.speedCameras == FilterPreference.AVOID) add("Speed cameras" to scoringData.speedCameras)
    }
    if (avoidCategories.isEmpty() || route.waypointChain.size < 2) return AvoidanceOutcome(route, emptyList(), 0)

    fun hitsByCategory(points: List<LatLng>): Map<String, List<LatLng>> =
        avoidCategories.associate { (label, categoryPoints) -> label to findNearbyHits(categoryPoints, points) }

    var current = route
    val cumulativeAvoidLocations = mutableListOf<LatLng>()
    // The most salient part of a route to an instructor is the start -- every
    // real report driving this function's design (including the one that led
    // to this specific fix) was about the very first maneuver. Prioritizing
    // avoid-locations by distance from here means a limited iteration/avoid-
    // count budget gets spent on what actually matters most first.
    val routeStart = route.points.first()

    repeat(MAX_AVOIDANCE_REROUTE_ITERATIONS) {
        val hits = hitsByCategory(current.points)
        val hitPoints = hits.values.flatten()
        if (hitPoints.isEmpty()) return AvoidanceOutcome(current, emptyList(), 0)

        // Real, confirmed bug fixed here: this used to add *every* currently-
        // hit point in one shot, every round -- live-tested via Logcat on a
        // dense-area, many-waypoint loop, that let a single reroute attempt
        // try to hard-avoid dozens of roundabouts across a whole multi-spoke
        // chain at once, which Geoapify can (and did) reject outright as
        // globally unroutable ("No path could be found for input") --
        // discarding the *entire* attempt, including obstacles near the start
        // that a smaller, more targeted request could very plausibly have
        // avoided on their own. Capping and prioritizing the batch added each
        // round means a request that turns out infeasible only costs that
        // batch, not everything.
        val newHitsThisRound = hitPoints
            .sortedBy { approxDistanceMeters(routeStart, it) }
            .take(MAX_NEW_AVOID_LOCATIONS_PER_ITERATION)
        cumulativeAvoidLocations.addAll(newHitsThisRound)
        val distinctAvoidLocations = cumulativeAvoidLocations.distinct()
        if (distinctAvoidLocations.size > MAX_AVOID_LOCATIONS) {
            Log.w(LOG_TAG, "rerouteAvoidingHits: hit the $MAX_AVOID_LOCATIONS avoid-location cap -- stopping, best effort")
            cumulativeAvoidLocations.removeAll(newHitsThisRound)
            return AvoidanceOutcome(current, hits.filterValues { it.isNotEmpty() }.keys.toList(), hitPoints.size)
        }

        val rerouted = try {
            fetchRoutedPaths(current.waypointChain, avoidHighways = avoidHighways, avoidLocations = distinctAvoidLocations)
                .firstOrNull()
        } catch (e: Exception) {
            // Back off just this round's batch (kept in `current`'s own
            // waypointChain-derived route already, nothing to lose there),
            // not the whole cumulative history -- an earlier round's already-
            // successful avoid-locations stay applied via `current`, they're
            // just not re-requested again here since `current` already
            // reflects them.
            Log.w(LOG_TAG, "rerouteAvoidingHits: reroute call failed -- keeping the best route found so far", e)
            cumulativeAvoidLocations.removeAll(newHitsThisRound)
            null
        } ?: return AvoidanceOutcome(current, hits.filterValues { it.isNotEmpty() }.keys.toList(), hitPoints.size)
        if (rerouted.durationSeconds < MIN_PLAUSIBLE_DURATION_SECONDS) {
            cumulativeAvoidLocations.removeAll(newHitsThisRound)
            return AvoidanceOutcome(current, hits.filterValues { it.isNotEmpty() }.keys.toList(), hitPoints.size)
        }

        val newRoute = GeneratedRoute(rerouted.points, rerouted.durationSeconds, rerouted.distanceMeters, current.waypointChain)
        val newHitCount = hitsByCategory(newRoute.points).values.sumOf { it.size }
        if (newHitCount >= hitPoints.size) {
            // This round's batch didn't actually reduce hits -- back off just
            // this batch and stop; a bigger/different one next round is
            // unlikely to help either if this one, built from the highest-
            // priority current violations, didn't.
            Log.w(
                LOG_TAG,
                "rerouteAvoidingHits: reroute made no progress (${hitPoints.size} -> $newHitCount hits) -- " +
                    "stopping, best effort",
            )
            cumulativeAvoidLocations.removeAll(newHitsThisRound)
            return AvoidanceOutcome(current, hits.filterValues { it.isNotEmpty() }.keys.toList(), hitPoints.size)
        }
        Log.d(LOG_TAG, "rerouteAvoidingHits: reduced hits ${hitPoints.size} -> $newHitCount, continuing")
        current = newRoute
    }

    val remaining = hitsByCategory(current.points)
    val remainingCount = remaining.values.sumOf { it.size }
    return AvoidanceOutcome(current, remaining.filterValues { it.isNotEmpty() }.keys.toList(), remainingCount)
}

/**
 * Picks the best [AvoidanceOutcome] among [outcomes] -- each already run through
 * [rerouteAvoidingHits] -- using a strict, tiered priority instead of one blended score:
 * (1) fewest remaining Avoid-category hits (ideally zero), (2) among ties, best Prefer-
 * category proximity, (3) among remaining ties, closest duration match to [targetSeconds].
 * This is the direct fix for "Avoid needs to actually beat Prefer, which needs to beat
 * duration-matching, not get blended into one number" -- see [rerouteAvoidingHits]' own doc
 * comment for the fuller context (Corey: "we need to prioritise the avoidance over the
 * preference"). Radius filtering (same semantics as [pickBestRoute]) is applied first, over
 * every outcome's own (possibly rerouted) [AvoidanceOutcome.route].
 *
 * Null if [outcomes] is empty.
 */
fun pickBestAvoidanceAwareRoute(
    outcomes: List<AvoidanceOutcome>,
    filters: RouteGenerationFilters,
    scoringData: ScoringData,
    targetSeconds: Double,
    start: LatLng? = null,
    maxRadiusKm: Double? = null,
): AvoidanceOutcome? {
    if (outcomes.isEmpty()) return null
    val pool = if (start != null && maxRadiusKm != null) {
        outcomes.filterNot { routeExceedsRadius(it.route, start, maxRadiusKm) }.ifEmpty { outcomes }
    } else {
        outcomes
    }
    // Real, confirmed regression without this: "fewest violations wins outright" had no
    // floor on how bad the winning candidate's own duration was allowed to be -- a tiny,
    // degenerate-duration candidate (e.g. 15min against a 60min target) could beat a
    // well-matched one purely by having one fewer roundabout hit, however catastrophically
    // wrong its own duration was. Corey: asked for a 1h loop, 5km radius, got a 15min route
    // back (that *still* couldn't avoid the roundabout either). Avoidance staying the
    // dominant tier is intentional and unchanged (see this function's own history above --
    // "prioritise the avoidance over the preference") -- this only adds a sanity floor
    // *before* that ranking, not a return to duration-first scoring: a candidate has to be
    // within MAX_DURATION_ERROR_RATIO of the target to be eligible for the violation-count
    // comparison at all. Falls back to the full pool if literally nothing qualifies, rather
    // than stranding the instructor with no route at all.
    val durationPlausible = pool
        .filter { abs(it.route.durationSeconds - targetSeconds) <= targetSeconds * MAX_DURATION_ERROR_RATIO }
        .ifEmpty { pool }
    val minHits = durationPlausible.minOf { it.remainingHitCount }
    val leastViolating = durationPlausible.filter { it.remainingHitCount == minHits }
    return leastViolating.maxByOrNull { preferAndDurationScore(it.route, filters, scoringData, targetSeconds) }
}

// How far a candidate's own duration may miss [targetSeconds] and still be eligible for the
// violation-count comparison in [pickBestAvoidanceAwareRoute] -- 50%, deliberately looser than
// DURATION_TOLERANCE_SECONDS' own "converged" bar (10 real minutes, too tight an absolute floor
// for a trip that could be anywhere from 20 minutes to several hours), just tight enough to
// rule out a genuinely degenerate result (a 15min route for a 60min target is 75% off, well
// outside this) while still leaving real room for an avoidance-driven detour to cost more time
// than originally planned, which is an accepted, expected trade-off, not a bug.
private const val MAX_DURATION_ERROR_RATIO = 0.5

/** Prefer-category proximity score minus duration-error, same weighting style as
 * [scoreRoute] but deliberately excluding every Avoid category -- avoidance is already
 * handled as its own, strictly higher-priority tier by [pickBestAvoidanceAwareRoute];
 * mixing it back in here would undo that priority ordering. */
private fun preferAndDurationScore(route: GeneratedRoute, filters: RouteGenerationFilters, data: ScoringData, targetSeconds: Double): Double {
    var preferScore = 0
    fun apply(preference: FilterPreference, pointsOfInterest: List<LatLng>) {
        if (preference != FilterPreference.PREFER || pointsOfInterest.isEmpty()) return
        preferScore += countNearby(pointsOfInterest, route.points)
    }
    apply(filters.incidents, data.incidents)
    apply(filters.constructionZones, data.constructionZones)
    apply(filters.schoolZones, data.schoolZones)
    apply(filters.speedCameras, data.speedCameras)
    apply(filters.roundabouts, data.roundabouts)
    apply(filters.highways, data.majorRoads)
    apply(filters.highTraffic, data.highTraffic)
    val durationErrorMinutes = abs(route.durationSeconds - targetSeconds) / 60.0
    return preferScore - durationErrorMinutes * DURATION_ERROR_WEIGHT_PER_MINUTE
}

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
            best = GeneratedRoute(routed.points, routed.durationSeconds, routed.distanceMeters, listOf(start, detourPoint, destination))
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
    reachableArea: ReachableArea?,
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
        var chain: List<LatLng> = emptyList()
        while (routed == null && attempt < MAX_UNROUTABLE_RETRIES) {
            chain = buildList {
                add(start)
                addAll(spokePoints(start, currentBearingDegrees, spokeCount, spokeRadiusKm, spreadDegrees, reachableArea))
                add(destination)
            }
            routed = try {
                fetchRoutedPaths(chain, avoidHighways = avoidHighways).firstOrNull()
            } catch (e: Exception) {
                // Full chain logged verbatim -- confirmed needed live: the
                // failing request is [start, petal(s), destination] as one
                // call, and Geoapify's error never says which waypoint (or
                // which segment between two of them) is the actual problem.
                // Without the exact coordinates that were sent, a live
                // reproduction can only guess at the petal in isolation,
                // which doesn't necessarily reproduce a failure that's really
                // about the destination or a segment, not the petal alone.
                Log.e(
                    LOG_TAG,
                    "Radius-confined routing call failed (bearing=$currentBearingDegrees, iteration=$iteration, " +
                        "attempt=$attempt, spokes=$spokeCount, spokeRadius=${"%.2f".format(spokeRadiusKm)}km, " +
                        "spread=${"%.0f".format(spreadDegrees)}deg, avoidHighways=$avoidHighways) " +
                        "chain=${chain.joinToString(" | ") { "${it.latitude},${it.longitude}" }}",
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
            best = GeneratedRoute(routed.points, routed.durationSeconds, routed.distanceMeters, chain)
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
 * whatever arc actually has roads.
 *
 * [reachableArea], when supplied, replaces the blind "always place at exactly
 * [radiusKm]" behaviour with a per-petal reachability check (see
 * [maxReachableDistanceKm]): each petal is placed at whatever distance along
 * its own bearing is actually confirmed reachable by road, capped at
 * [radiusKm] and pulled in slightly for margin, instead of guessing
 * [radiusKm] uniformly for every petal regardless of what's actually out
 * there in that direction. Falls back to the old uniform-radius behaviour
 * when null (e.g. the isoline fetch failed) -- this is a placement
 * *enhancement*, the existing shrink+rotate retry loop in
 * [refineCandidateWithinRadius] remains the fallback safety net either way. */
private fun spokePoints(
    anchor: LatLng,
    seedBearing: Double,
    count: Int,
    radiusKm: Double,
    spreadDegrees: Double = 360.0,
    reachableArea: ReachableArea? = null,
): List<LatLng> {
    if (count <= 0) return emptyList()
    fun petalAt(bearingDegrees: Double): LatLng {
        val effectiveRadiusKm = if (reachableArea != null) {
            (reachableArea.maxReachableDistanceKm(anchor, bearingDegrees, radiusKm) * PETAL_REACHABILITY_MARGIN)
                .coerceIn(MIN_PETAL_RADIUS_KM, radiusKm)
        } else {
            radiusKm
        }
        return offset(anchor, bearingDegrees, effectiveRadiusKm)
    }
    if (count == 1) return listOf(petalAt(seedBearing))
    val stepDegrees = spreadDegrees / count
    return (0 until count).map { i -> petalAt(seedBearing + i * stepDegrees) }
}

// Pulled in from the isoline's own reachable boundary, not placed exactly on
// it -- a petal placed right at the edge can still occasionally fail to route
// (isoline precision, the ring decimation in GeoapifyIsolineApi.kt, or a real
// road that ends just short of where the reachable polygon's edge falls).
private const val PETAL_REACHABILITY_MARGIN = 0.9

// Floor so a bearing that's almost entirely unreachable (e.g. it points
// straight into a harbour right next to `start`) still gets a petal a short,
// real distance out rather than one placed essentially on top of `start`
// itself (which would add nothing to the route and waste a waypoint slot).
private const val MIN_PETAL_RADIUS_KM = 1.0

// How many binary-search steps maxReachableDistanceKm takes to home in on the
// true reachable distance along a bearing -- 14 steps roughly halves a
// starting range of tens of km down to sub-10m precision, far finer than
// this needs (petal placement, not final route verification), while staying
// cheap: each step is one ReachableArea.contains() call, itself O(ring size)
// per ring (a few thousand points post-decimation, see
// GeoapifyIsolineApi.kt's DECIMATION_STRIDE) -- trivial for a phone CPU even
// at ~14 steps x a handful of rings.
private const val REACHABILITY_SEARCH_STEPS = 14

/** The largest distance (km, capped at [maxKm]) along [bearingDegrees] from
 * [anchor] that's still inside this [ReachableArea] -- used by [spokePoints]
 * to place a petal waypoint on ground actually reachable by road instead of
 * guessing blind. Binary search assumes reachability is roughly monotonic
 * outward along a single straight bearing (true for the overwhelming
 * majority of real isochrones -- the reachable area grows outward from the
 * query point; it doesn't typically have a reachable ring further out with
 * an unreachable gap closer in along the exact same ray) -- an accepted
 * approximation given this is choosing a *candidate* waypoint, not verifying
 * a final route; the routing call afterward still confirms the result is
 * real, and the existing retry logic in [refineCandidateWithinRadius] is
 * still there for the rare case this approximation is wrong. Returns 0.0 if
 * even a short distance out along this bearing isn't reachable at all. */
private fun ReachableArea.maxReachableDistanceKm(anchor: LatLng, bearingDegrees: Double, maxKm: Double): Double {
    if (maxKm <= 0.0) return 0.0
    if (!contains(offset(anchor, bearingDegrees, maxKm * 0.02))) return 0.0
    var lo = 0.0
    var hi = maxKm
    repeat(REACHABILITY_SEARCH_STEPS) {
        val mid = (lo + hi) / 2.0
        if (contains(offset(anchor, bearingDegrees, mid))) lo = mid else hi = mid
    }
    return lo
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
    apply(filters.highways, data.majorRoads)
    apply(filters.highTraffic, data.highTraffic)

    val durationErrorMinutes = abs(route.durationSeconds - targetSeconds) / 60.0
    return filterScore - durationErrorMinutes * DURATION_ERROR_WEIGHT_PER_MINUTE
}

private fun countNearby(pointsOfInterest: List<LatLng>, route: List<LatLng>): Int = findNearbyHits(pointsOfInterest, route).size

/** Which of [pointsOfInterest] fall within [PROXIMITY_METERS] of any point along
 * [routePoints] -- same proximity test [scoreRoute] uses to score a route against a
 * filter category (via [countNearby]), exposed here so a caller can identify exactly
 * which real-world points a *specific* route hits, not just how many. Used by
 * [rerouteAvoidingHits] to build a small, targeted avoid-list from only the points a
 * route actually passes near, rather than a whole category's full dataset. */
fun findNearbyHits(pointsOfInterest: List<LatLng>, routePoints: List<LatLng>): List<LatLng> =
    pointsOfInterest.filter { poi -> routePoints.any { r -> approxDistanceMeters(poi, r) < PROXIMITY_METERS } }

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
