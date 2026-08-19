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
  - Generation is a heuristic (`RouteGenerator.kt`): try a detour point at 8
    bearings around the start/destination midpoint, ask OSRM for the actual drive
    time, iteratively adjust the detour distance to converge on the target
    duration, then pick whichever converged candidate best matches the chosen
    filters.
  - Filters are honest about real-vs-soft: only Highways→Avoid is a real OSRM
    routing constraint (`exclude=motorway`); everything else (hazards,
    construction, school zones, cameras, roundabouts, merging lanes, and
    Highways→Prefer) is proximity-scored best-of-8-candidates, since no free
    routing API supports true avoid/prefer-zone routing.
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

1. **Test the trip generator first** — it's the newest, least-proven piece:
   whether generated routes land close to the target duration in practice,
   whether the OSRM call volume (up to ~32 calls per generation, concurrent) is
   acceptably fast on the free public server, and whether the Material3
   `TimePicker` API used in `GenerateRouteScreen.kt` actually matches this
   project's pinned Compose BOM (2024.09.03) — that's the one piece of this
   feature never verified against real library docs, just written from general
   Material3 API familiarity.
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
