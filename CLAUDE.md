# LessonRoutes — session handoff

Orientation for a fresh Claude Code session picking this project up. Read
`spec.md` (original design) and `README.md` (build status, data sources, known
gotchas) for full detail — this file is the short version plus pointers.

## What this is

An Android app (Kotlin, Jetpack Compose, MapLibre + OpenFreeMap tiles, Room) for a
NSW driving instructor to record/save/follow custom routes with students, plus a
live map home screen showing hazards, high-traffic-volume roads, school zones,
speed cameras, and a "quiet roads" heuristic — all from free NSW open data. Zero
running cost, no billing account, no login/cloud sync. Package:
`com.instructor.lessonroutes`. Repo: https://github.com/Cozmos94/Driving-App

## Current status (as of this handoff)

Everything in `spec.md` is built in some form, plus a post-spec addition (student
profiles). Specifically:

- ✅ **Confirmed working**: map rendering, Room layer, polyline drawing, route
  list/detail navigation, live hazards (incidents + roadworks, tap for info),
  school zones + speed cameras, MapLibre attribution UI tidied up.
- 🔲 **Built but not re-confirmed** after later changes: create route (tap +
  record mode), follow view, edit/delete, notes/tags/waypoints, and the new
  student-profiles work below. Worth a test pass (Corey does this on a personal
  device, not the Novigi-managed one — no Netskope involved).
- ✅ **Student profiles (new, post-spec)**: a route can be linked to zero, one, or
  several student profiles (many-to-many via `RouteStudentProfileCrossRef`). Create
  a profile inline from the save-route dialog or the edit-route dialog, or directly
  via "+" on the new **Student Profiles screen** (`StudentProfilesScreen.kt`) — a
  searchable list of profiles plus a pinned "All" entry, now the landing point for
  "My routes" from the live map (a separate "Plan a trip" button leads to the trip
  generator instead — a different feature, see below). Tapping a profile (or "All") opens the route
  list scoped to it; the route list and this screen have matching bottom-left
  toggle buttons ("Profiles" / "Routes") to switch between them. The current filter
  is hoisted state in `AppNavHost` (`routeListFilter`), not a nav argument — kept
  `ROUTE_LIST` as a single plain destination on purpose. Creating a route while
  scoped to a profile pre-selects it in the save dialog. Bumped Room to schema v3
  with a real hand-written `Migration(2, 3)` (see `AppDatabase.kt`) instead of
  another destructive fallback — first real migration in this project, worth
  double-checking on upgrade from a v2 install if anything seems off.
- ✅ **App name + icon**: full name "NSW Driving Instructor Route Planner",
  shorthand "Route Planner" (`app_name` in `strings.xml` — shown under the
  launcher icon, kept short; `app_name_full` + the Settings screen heading carry
  the full name). Launcher icon (hand-built vector drawables, no PNGs) is now
  purple background + white road glyph + a new yellow nav-arrow glyph top-right.
- ✅ **"Overview" button**: centered in the bottom bar of both the route list and
  Student Profiles screens (between the Profiles/Routes toggle and the "+" FAB) —
  returns to the live map via `popBackStack(LIVE_MAP, inclusive = false)`, reusing
  the existing instance rather than restarting its continuous GPS tracking.
- ✅ **Purple/yellow theme** (`#71286F` purple, `#F3E10E` yellow — Corey's brand
  colors): see `Color.kt`/`Theme.kt`. Also turned dynamic (Material You) color
  **off by default** — it was silently overriding any custom palette with
  wallpaper-derived colors on Android 12+, which would have made this invisible on
  most phones. Route line + waypoint dots in `RouteMapView.kt` were recolored to
  match (they already tracked the theme color per their own old comment); Phase 2
  overlay colors (hazards, traffic volume, quiet roads) were deliberately left
  alone since they carry semantic meaning, not branding.
- ✅ **Tap-created routes are now road-snapped for display**: `OsrmApi.kt` calls
  OSRM's free public routing server (no key) to turn a tap route's sparse points
  into a path that follows real roads, both live while tapping
  (`CreateRouteScreen.kt`, debounced ~600ms) and when viewing a saved route
  (`RoadSnappedRoute.kt`'s `rememberDisplayRoutePoints()`, used by
  `RouteDetailScreen`/`FollowScreen`). Only applies to tap-created routes (detected
  by every point having a null `timestamp`) — recorded GPS trails are left exactly
  as recorded. Falls back to straight lines on any OSRM failure. **The stored
  `RoutePoint` rows are unchanged** — this is purely a display-time computation,
  not baked into what's saved.
- ✅ **"Open in nav app" now passes waypoints, not just a destination**: real
  usage showed Maps' own computed route could look "completely different" from
  the planned one when only given a single destination pin. Now uses Google
  Maps' free "Get Directions" URL API with the route's end point as destination
  and up to 8 evenly-sampled points along the route as waypoints, so Maps' path
  tracks the plan much more closely (still not exact — Maps still computes its
  own turn-by-turn between waypoints). `<queries>` block in `AndroidManifest.xml`
  updated to match (added an https/BROWSABLE entry for the fallback path).
  `openInNavApp()` lives in `NavIntent.kt` now (extracted so the trip generator
  below can share it too).
- ✅ **Trip generator: first real-device bugs found and fixed** (map defaulted to
  Sydney instead of centering on the device, the whole map was invisible/
  unclickable for setting a destination, start time was mandatory, address
  search needed an explicit tap of "Search", and Generate could spin forever
  with no error):
  - **Map defaulted to Sydney / not centered on device**: this screen tracks
    location itself, but `RouteMapView` still ran its *own* internal
    permission-request + device-location-centering flow by default
    (`centerOnDeviceLocation` defaults to `true`) — the two raced, and the
    screen's own state usually won, leaving `RouteMapView`'s internal
    "has permission" check stuck on a stale value from first composition, so
    its centering never fired. Fixed by adding a new `focusPoint: LatLng?`
    param to `RouteMapView` (moves the camera there once, driven by a caller
    that already has its own location — no redundant permission flow) and
    passing `centerOnDeviceLocation = false` from `GenerateRouteScreen`.
  - **Map wasn't there to tap at all**: a real Compose layout bug — the map
    `Box` had `weight(1f)` next to an *unweighted* `verticalScroll` content
    `Column` below it. An unweighted scrolling column measures to its full
    content height (nothing bounds it), which can starve a weighted sibling
    down to near-zero height. Fixed by giving the map a fixed height (260dp)
    and moving `weight(1f)` onto the scrollable content column instead — the
    correct/standard pattern for "fixed region + scrollable region filling
    the rest".
  - **Generate could spin forever**: two compounding causes. (1) No overall
    ceiling on the whole generate flow — now wrapped in
    `withTimeoutOrNull(90_000)`, guaranteeing termination with a clear error
    either way. (2) The new roundabout/merge-lane/major-road Overpass queries
    were reusing `OverpassApi.kt`'s existing shared client, which has a
    **150-second** timeout tuned for a completely different, much heavier
    query (`matchRoadGeometry`'s multi-station batch) — likely the real
    "waited 3 minutes, nothing happened" culprit. Added a separate
    `fastClient` (30s) for the lighter single-bbox queries.
  - **Start time is now optional** (defaults to "now", computed fresh at both
    display time and at the moment Generate is tapped — not memoized, since
    "now" needs to actually mean now).
  - **Address search is now live-as-you-type** (500ms debounce via a
    `LaunchedEffect(searchQuery)`, replacing the old explicit "Search" button)
    — a `lastAppliedResultLabel` guard avoids an immediate pointless re-search
    right after picking a result (which sets the field to that result's own
    label text).
  - **"My routes" button lost its color**: was accidentally changed to
    `OutlinedButton` (transparent, purple text) instead of a filled `Button`
    when the live map's single button became two — now both are filled.
  - **Second-round bug**: the address search box and tap-to-set-destination
    were both hidden/disabled while "Loop back to where I start" stayed
    checked (its default) — by design, but with no visible reason why, so it
    read as "the address box disappeared" and "tapping the map is broken".
    Fixed by making the destination controls (search box, tap-hint) always
    visible/active, and having any interaction with them (a map tap, picking
    a search result) automatically uncheck "loop back to start" itself —
    there's no separate step to remember anymore.
  - **Third-round: Generate still spins with no result.** My 90s timeout from
    the previous round is a mathematical guarantee of *some* outcome (result
    or error) by 90s, so either the wait genuinely felt endless (plausible --
    90s is a long time for a spinner) or the build under test predated that
    fix. Rather than re-guess blindly: cut candidate bearings 8→4 and
    refinement rounds 4→3 (up to 32 OSRM calls per generation down to up to
    12 -- OSRM's free public server is shared and rate-limit-prone, and heavy
    concurrent load from one client is a real suspect), shortened the overall
    timeout 90s→45s to match, added a visible "this can take up to 45s"
    message while generating, and — important for next time this needs
    debugging — added `Log.e`/`Log.d` in `RouteGenerator.kt` (previously,
    a per-candidate OSRM failure was silently swallowed with zero trace).
    **If it still doesn't work after this**: check Logcat filtered to tag
    `RouteGenerator` for what's actually failing, and confirm whether OSRM
    is reachable at all from the test device by checking whether tap-mode
    road-snapping (a much simpler, single OSRM call, see "Road-snapping"
    above) visibly makes tap-created lines follow roads -- if that's *also*
    silently falling back to straight lines, it points to OSRM connectivity
    being broken from that device/network entirely, not a trip-generator-
    specific bug.
  - **Fourth-round, root cause confirmed**: generation worked with hazards/
    construction/school-zones/cameras filters (TfNSW + Room, no Overpass, no
    OSRM params), but consistently failed with Highways and/or Roundabouts/
    Merging lanes. Tested directly against the live APIs (not guessed):
    OSRM's public demo server rejects the `exclude` parameter *outright, for
    every value* (`{"code":"InvalidValue","message":"Exclude flag
    combination is not supported."}`) -- so Highways->Avoid, which used
    `exclude=motorway` as a real hard constraint, failed 100% of the time by
    design. Removed `exclude` support from `OsrmApi.fetchRoutedPaths()`
    entirely; Highways is now soft proximity scoring like every other
    category (`fetchMajorRoads` data, scored both directions) -- **no filter
    in this app is a hard routing constraint anymore**, all of them are
    best-effort. Also split `RouteGenerator`'s combined
    generate-then-score into `generateCandidateRoutes()` +
    `pickBestRoute()`, run *concurrently* from `GenerateRouteScreen` (via
    `async`) instead of sequentially, since Overpass scoring-data fetches and
    OSRM candidate generation are fully independent and were needlessly
    adding their wait times together. Also moved the "Generated: ...
    / Regenerate / Open in nav app / Save" block to the top of the
    scrollable content (right after the map) instead of the bottom, below
    the Filters section -- it was easy to miss entirely without scrolling
    past everything else first, which is likely why "no option to navigate"
    was reported even after generation apparently succeeded (the map at top
    did show the route).
- 🔲 **NEW, UNTESTED: Trip generator ("Plan a trip")** — a second, separate way to
  get a route: generates one to actually go drive (destination + start/end time +
  avoid/prefer filters), rather than recording/tapping one by hand. This is the
  biggest, riskiest addition this session — genuinely untested, and the one most
  likely to need debugging/tuning. See README's "Trip generator" section for the
  full design, and the "Key files" entries below. Headline points:
  - Start/end time only computes a target duration (e.g. 5pm→6pm = 60 min) —
    generation runs immediately, it doesn't wait for or schedule around the clock.
  - Destination can be "loop back to start", a map tap, or an address search
    (new: free/keyless Nominatim geocoding, `NominatimApi.kt`).
  - Generation is a heuristic (`RouteGenerator.kt`): try a detour point at 4
    bearings around the start/destination midpoint, ask OSRM for the actual drive
    time, iteratively adjust the detour distance to converge on the target
    duration, then pick whichever converged candidate best matches the chosen
    filters. `generateCandidateRoutes()` (OSRM) and scoring-data fetching
    (`buildScoringData()` in `GenerateRouteScreen.kt`, Overpass/TfNSW/Room) run
    concurrently, combined via `pickBestRoute()`.
  - **No filter is a hard routing constraint** -- all of them, Highways
    included, are proximity-scored best-of-4-candidates. Highways->Avoid was
    originally OSRM's `exclude=motorway` (a real constraint), but OSRM's
    public demo server rejects `exclude` outright for every value (confirmed
    directly against the live API) -- removed entirely, see `OsrmApi.kt`'s
    doc comment.
  - New Overpass queries: `fetchRoundabouts` (solid — real OSM tags) and
    `fetchMergeLaneProxies`/`fetchMajorRoads` (the former is a known
    approximation — see its doc comment).
- ✅ **Phase 2 done**, with two known, documented simplifications (not bugs):
  - High-traffic-volume overlay substitutes for "high-risk roads" (crash data) —
    the real crash dataset was never identified; a Traffic Volume Counts API was
    found and confirmed instead. Both high-volume roads and quiet roads render as
    real painted OpenStreetMap road geometry (snapped via the Overpass API), not
    just markers.
  - Quiet roads (OSM low-traffic proxy) fetch once in a fixed box near wherever
    the device was when the app started — they don't re-query as you pan the map
    elsewhere.
- ✅ Settings screen (minimal — attribution + clear-routes; no fake toggles for
  things this app genuinely has no options for, like map style or units).

## Key files

- `app/src/main/java/com/instructor/lessonroutes/ui/map/RouteMapView.kt` — the
  shared map surface every screen uses. All overlay rendering, tap-hit-testing,
  and MapLibre lifecycle bridging lives here. This file has grown large; read it
  before adding another overlay type.
- `app/src/main/java/com/instructor/lessonroutes/ui/map/LiveMapScreen.kt` — the
  app's home screen (live-following map + all the "ambient" overlays).
- `app/src/main/java/com/instructor/lessonroutes/data/remote/` — `HazardsApi.kt`,
  `TrafficVolumeApi.kt`, `OverpassApi.kt`. All hand-verified against real API
  responses before being built, not guessed at — see README's "Overpass gotchas"
  section before touching these.
- `app/src/main/java/com/instructor/lessonroutes/data/` — Room entities/DAOs.
  Schema is at version 3 with a real `Migration(2, 3)` (student profiles) — see
  README's "Database version bump" note before adding another entity, and match
  Room's expected SQL exactly if you hand-write another migration.
- `app/src/main/java/com/instructor/lessonroutes/data/StudentProfile.kt`,
  `RouteStudentProfileCrossRef.kt`, `RouteWithProfiles.kt`, `StudentProfileDao.kt` —
  the student-profile feature's data layer (many-to-many with `Route`).
- `app/src/main/java/com/instructor/lessonroutes/ui/routes/ProfilePicker.kt` — the
  shared multi-select-plus-inline-create UI used by both the save-route and
  edit-route dialogs.
- `app/src/main/java/com/instructor/lessonroutes/ui/profiles/StudentProfilesScreen.kt` —
  the searchable profile-picker landing screen; see `AppNavHost.kt` for how
  `routeListFilter` gets threaded from here into `RouteListScreen`/`CreateRouteScreen`.
- `app/src/main/java/com/instructor/lessonroutes/data/remote/OsrmApi.kt`,
  `app/src/main/java/com/instructor/lessonroutes/ui/map/RoadSnappedRoute.kt` — the
  tap-route road-snapping feature (free OSRM public server, display-only, see
  status above). `OsrmApi.kt`'s `fetchRoutedPaths()` (duration/exclude/alternatives
  aware) is also what the trip generator below builds on.
- `app/src/main/java/com/instructor/lessonroutes/data/routegen/RouteGenerator.kt` —
  the trip generator's core algorithm (candidate generation + duration
  convergence + filter scoring). Read its doc comments before changing the
  bearing/radius search or the scoring weights.
- `app/src/main/java/com/instructor/lessonroutes/ui/generate/GenerateRouteScreen.kt` —
  the "Plan a trip" screen: destination picker, time pickers, filter UI,
  generate/regenerate/save/open-in-nav.
- `app/src/main/java/com/instructor/lessonroutes/data/remote/NominatimApi.kt` —
  free/keyless address search (OpenStreetMap's Nominatim), used by the trip
  generator's destination search.
- `app/src/main/java/com/instructor/lessonroutes/ui/routes/NavIntent.kt` — the
  shared `openInNavApp()` (extracted from `RouteDetailScreen.kt` so the trip
  generator can reuse it too).
- `app/src/main/assets/school_zones.json`, `speed_cameras.json` — processed
  static data snapshots (the original ~500MB source shapefile isn't in the repo).

## Environment gotchas (read before debugging something that looks impossible)

- **If building on a Novigi-managed machine**: corporate TLS interception
  (Netskope) breaks Gradle and Android emulator networking in non-obvious ways.
  Full writeup + fixes in `README.md`'s "Corporate network gotcha" section.
- **Overpass API**: client timeout must exceed the query's own `[timeout:N]` —
  got this wrong once already (30s client vs 60s query = silent, confusing
  failures). A plain `[highway]` filter matches footways/cycleways, not just
  roads. See README's "Overpass gotchas" section.
- **`Modifier.weight()`/`.align()` inside Row/Column/Box**: never add an explicit
  import for these — they're member extensions resolved via the scope receiver,
  and an explicit import binds to an unrelated internal symbol instead, causing a
  confusing compile error. Hit this bug twice already this build.
- No Gradle wrapper jar was ever committed — Android Studio generates it on
  first open (Gradle tool window → Tasks → build setup → `wrapper`).

## If you don't have a Transport for NSW API key

Core app (steps 1–8) doesn't need one. Live hazards + traffic volume need
`TFNSW_API_KEY` in `local.properties` (git-ignored, never commit it) — get a free
one from https://opendata.transport.nsw.gov.au. School zones/cameras (bundled
assets) and quiet roads (OSM only) don't need it.

## Likely next steps

1. **Test the trip generator again** — the first real-device pass found and fixed
   several bugs (map centering/visibility, mandatory start time, search UX, and
   a possible-hang on Generate — see the status entry above for detail). Still
   needs a full pass to confirm: whether generated routes land close to the
   target duration in practice, whether the 90s hard timeout is ever actually
   hit in normal use (if so, the individual timeouts feeding into it may need
   tuning), and whether the Material3 `TimePicker` API in
   `GenerateRouteScreen.kt` renders correctly (compiled fine against this
   project's pinned Compose BOM 2024.09.03, but never visually confirmed).
2. Confirm steps 5–8 (create/record/follow/edit/delete), the student-profile
   picker/filter, the Student Profiles screen (search, "+", the Profiles/Routes
   toggle, Undo/Clear-all in Tap mode), the purple/yellow theme, and tap-route
   road-snapping all work end to end on-device — test pass hasn't happened yet.
3. Quiet roads deliberately still only fetch once at startup (confirmed as desired
   behavior, not a bug — don't "fix" this without checking first).
4. If real crash/black-spot data turns up: same Overpass-snapping approach as
   `TrafficVolumeApi.kt`/`OverpassApi.kt` would apply (confirmed as the right
   approach when that data is identified).
5. Student profiles currently have just a name. If Corey wants more per-student
   detail (skill level, notes, contact info), extend the `StudentProfile` entity —
   remember to bump the Room version and write another real `Migration` (v3 → v4)
   rather than reaching for `fallbackToDestructiveMigration()` again.
