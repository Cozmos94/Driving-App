# LessonRoutes

Android app for a driving instructor to record, save, and follow custom driving
routes with students, **scoped to NSW, Australia only**. Zero running cost: no
Google Maps SDK, no billing account, no paid routing API. See `spec.md` (in this
repo's parent context) for the full design.

One nuance on "no routing API": a route you record or tap by hand is still always
exactly what you recorded/tapped — a free, keyless routing API (OSRM) is used only
to *display* it more usefully (see "Road-snapping for tap-created routes" below),
never to decide its points. The one place this app *does* use a routing engine to
actually decide a route is the separate "Plan a trip" generator (see "Trip
generator" below) — still free/keyless, just a different feature with a different
job: planning a new route to drive, not replaying one you already made.

## Status: full spec build complete, steps 5–8 + step 10 pending a test pass

Steps 1–4 and 9 are built and confirmed working (map, Room, polyline, route
list/detail, live hazards). Steps 5–8 (create/record/follow/edit/delete) and the
static overlays in step 10 (school zones, speed cameras) were implemented but not
yet re-confirmed after later changes — see "What's implemented, untested" below.
Step 11 (high-risk roads + OSM low-traffic proxy) is done, with one substitution
from the original spec: **"high-risk roads" was built as a high-traffic-volume
overlay instead of crash/black-spot data** — Corey redirected this mid-build once
we found NSW's actual crash-data open-data situation unclear, in favor of a
Traffic Volume Counts API that was confirmed to work end-to-end. See "High traffic
volume overlay" below if a real crash-data version is wanted later. Settings
(spec: optional/last) is built, minimally — see "Settings screen" below.

Phase 2 needs a Transport for NSW API key kept out of source control
(`local.properties` → `BuildConfig`, never hardcoded or committed) for everything
except school zones/speed cameras (bundled assets) and quiet roads (OSM only, no
TfNSW key needed).

### What's implemented, untested

- **Create route** ([CreateRouteScreen.kt](app/src/main/java/com/instructor/lessonroutes/ui/routes/CreateRouteScreen.kt)):
  Tap mode (tap the map to add points) and Record mode (Start/Pause captures a live
  GPS trail via FusedLocationProvider) in one screen, a "mark last point as waypoint"
  action, and a save dialog (name + notes) that writes to Room. Tap mode also has
  "Undo" (remove the last tapped point) and "Clear all" (wipe the whole in-progress
  route, with a confirmation dialog since it can't be undone) — Record mode
  deliberately doesn't get these, since a live GPS trail isn't something you "undo"
  a point at a time the same way. Tap mode's in-progress preview is road-snapped
  (see below), debounced ~600ms after the last tap so rapid tapping doesn't fire a
  request per point.
- **Follow view** ([FollowScreen.kt](app/src/main/java/com/instructor/lessonroutes/ui/routes/FollowScreen.kt)):
  a selected route's polyline with a live position dot on top; camera fits the route's
  bounds once and doesn't chase the dot (deliberately, to avoid a jumpy camera).
- **Edit/delete**: long-press a route in the list for a rename/notes-edit dialog or a
  delete confirmation.
- **Open in nav app**: on the route detail screen, opens Google Maps' "Get
  Directions" URL (`https://www.google.com/maps/dir/?api=1&destination=...&waypoints=...`,
  free, no key) with the route's end point as the destination and up to 8 points
  along the route as waypoints. These are sampled **evenly by distance, not by
  index** (`sampleWaypoints()` in `NavIntent.kt`) — an early index-based version
  could leave a long straight stretch of the route (a highway run, say) with zero
  waypoints at all, giving Maps complete freedom to substitute a totally
  different path through that gap (confirmed as a real cause of "loads a
  completely different route" reports, worse for loop/there-and-back routes
  where the waypoint budget has to cover both legs). Distance-based sampling
  makes Maps' own computed driving directions track the planned route much more
  closely than either the original destination-only version or the index-sampled
  one did. Still not exact fidelity — Maps computes its own turn-by-turn path
  between waypoints, it doesn't replay the recorded/tapped points — that would need
  a paid turn-by-turn SDK. Falls back to a plain https intent (whatever handles it)
  if Google Maps isn't installed. See `openInNavApp()` in
  [NavIntent.kt](app/src/main/java/com/instructor/lessonroutes/ui/routes/NavIntent.kt)
  (shared by the route detail screen and the trip generator, see below).
  Needs the `<queries>` block in `AndroidManifest.xml` (API 30+ package visibility) —
  without it `resolveActivity()` silently returns null even with Maps installed.
- **Trip generator ("Plan a trip")**: a second, separate way to get a route —
  generates one to actually go drive (destination + start/end time + avoid/prefer
  filters) rather than recording/tapping one by hand. See "Trip generator" below
  for the full writeup. Several real-device bug-fix rounds so far: map not
  centering/not visible, mandatory start time, search needing an explicit
  button tap, destination controls hidden behind an unchecked-by-default
  checkbox, a possible hang on Generate (now hard capped at 45s, down from an
  earlier 90s, with call volume cut ~3x), and — the confirmed root cause of
  Highways/Roundabouts/Merging-lanes failing outright — OSRM's public demo
  server rejecting the `exclude` routing parameter entirely, removed in favor
  of scoring Highways the same soft way as every other filter. Still worth
  confirming generated routes land close to the target duration in practice.
- **Student profiles**: a route can be saved against zero, one, or several student
  profiles (many-to-many — see `StudentProfile`/`RouteStudentProfileCrossRef` in
  `data/`). Pick profiles (or create a new one inline) in the save dialog when
  creating a route, or reassign them later via long-press → Edit on the route list.
- **Student Profiles screen** ([StudentProfilesScreen.kt](app/src/main/java/com/instructor/lessonroutes/ui/profiles/StudentProfilesScreen.kt)):
  the landing point for "My routes" from the live map's bottom button (the live
  map also has a separate "Plan a trip" button — see "Trip generator" below,
  a different feature) — a
  searchable list of student profiles plus a pinned "All" entry; tapping either
  navigates to the route list scoped to that profile (or unfiltered for "All").
  "+" creates a new profile directly from here. Creating a route while scoped to a
  profile pre-selects that profile in the save dialog's checklist (still editable).
  The route list and this screen have matching bottom-left toggle buttons
  ("Profiles" / "Routes") to switch between them without repeated back-presses; the
  current filter is hoisted state in `AppNavHost`, not a nav argument, so the route
  list stays a single plain destination. Both bottom bars also have a centered
  "Overview" button that returns to the live map (the app's start destination) via
  `popBackStack(LIVE_MAP, inclusive = false)` — reuses the existing instance rather
  than pushing a new one, since that screen runs continuous GPS tracking.
- **Settings** ([SettingsScreen.kt](app/src/main/java/com/instructor/lessonroutes/ui/settings/SettingsScreen.kt),
  reached via a "Settings" button on the route list's top bar): app info, data-source
  attribution, and a "clear all saved routes" action. Deliberately minimal — there's
  no genuine map-style or units toggle to offer (one tile style, Australia is
  metric-only), so this doesn't pad in fake options; see spec's "optional/last" note.

Test order suggestion: create a route in Tap mode → save → confirm it shows in the
list and its detail view → try Record mode somewhere you can actually move (or set a
mock location on an emulator) → try Follow on a saved route → try edit and delete.

## Opening the project

This was scaffolded without a Gradle wrapper jar (this dev environment has no
Java/Gradle installed to generate one). To open it:

1. Install [Android Studio](https://developer.android.com/studio) (free) if you
   don't have it.
2. **File → Open** and point it at this folder.
3. Android Studio will detect there's no wrapper and offer to create one using its
   bundled Gradle — accept that, or run `gradle wrapper` yourself once if you have
   Gradle installed locally.
4. Let it sync. First sync will download Kotlin/AGP/Compose/MapLibre/Room
   dependencies — all free, no sign-in required.
5. Run on a device or emulator (API 26+). You should see a full-screen map.

## Before you build

The versions pinned in [gradle/libs.versions.toml](gradle/libs.versions.toml) were
current as of scaffolding time. Per the spec, check for newer stable releases
(especially `maplibre`, `agp`, and `kotlin`) before relying on them long-term —
Android Studio's Gradle sync will flag anything that no longer resolves.

## Verify the tile source

`OPENFREEMAP_LIBERTY_STYLE_URL` in
[RouteMapView.kt](app/src/main/java/com/instructor/lessonroutes/ui/map/RouteMapView.kt)
points at `https://tiles.openfreemap.org/styles/liberty`. OpenFreeMap's terms and
URL can change — reconfirm both are still current before you rely on this in
production.

## Corporate network gotcha (Netskope)

If you're building this on a Novigi-managed machine, outbound HTTPS is transparently
intercepted by Netskope and re-signed with a corporate root CA. This breaks two
things independently, both already worked around in this repo:

- **Gradle/JVM** — needs `-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` in
  `org.gradle.jvmargs` (see [gradle.properties](gradle.properties)) so the JVM trusts
  whatever Windows already trusts.
- **Android emulators** — are a separate guest OS with their own cert store, so they
  don't inherit Windows' trust of the corporate root. Any HTTPS call (including
  fetching map tiles) fails with `CertPathValidatorException: Trust anchor for
  certification path not found` until you manually install the corporate root CA on
  the emulator as a user-trusted cert (Settings → Security → Install a certificate →
  CA certificate) — export it from Windows via `certmgr.msc` (Trusted Root
  Certification Authorities, search "Netskope"/"Novigi"/"goskope"). The app already
  has [network_security_config.xml](app/src/main/res/xml/network_security_config.xml)
  wired up via a debug-only `<debug-overrides>` block so debug builds trust that
  installed cert — release builds are unaffected.

This isn't specific to this app — it'll affect any networked Android dev/emulator
work on a Novigi machine.

## Phase 2 setup: Transport for NSW API key

Needed for the map overlays (school zones, live hazards, etc.) — not needed for
anything in the core app (steps 1–8). Get a free key from the
[TfNSW Open Data Hub](https://opendata.transport.nsw.gov.au), then add it to
`local.properties` (git-ignored, never committed) in the project root:

```properties
TFNSW_API_KEY=your-key-here
```

This is read into `BuildConfig.TFNSW_API_KEY` at build time (see `app/build.gradle.kts`).
No key set means an empty string — Phase 2 overlay code must treat that as
"feature off," not crash. Requests need the header `Authorization: apikey YOUR_TOKEN`
(literal word `apikey`, space, then the token).

Live hazards (incidents + roadworks) are implemented and confirmed working, fetched
from `https://api.transport.nsw.gov.au/v1/live/hazards/{incident,roadwork}/open` —
built against the API's actual Swagger/OpenAPI spec after the developer guide PDF's
documented paths (`incident-open.json` etc.) turned out to be a pre-Open-Data-Hub
naming scheme that 404s against the real gateway. See
[HazardsApi.kt](app/src/main/java/com/instructor/lessonroutes/data/remote/HazardsApi.kt).

## Static reference overlays: school zones & speed cameras

Unlike live hazards, these are bundled as static asset snapshots rather than
fetched from a confirmed live endpoint — Corey provided manual exports from the
Open Data Hub rather than us finding a documented download API for them:

- **School zones** — filtered from the full NSW "Speed Zones" dataset (a 447k-record,
  ~500MB shapefile covering every speed-zoned road segment in the state) down to the
  ~3,700 records tagged `Type == "School"`, then reprojected from the shapefile's
  Web Mercator (EPSG:3857) coordinates to plain lat/lon. See
  [`app/src/main/assets/school_zones.json`](app/src/main/assets/school_zones.json)
  (the processed output — the ~500MB source shapefile itself isn't in this repo).
- **Speed cameras** — fixed and red-light cameras, parsed from TfNSW's published CSV
  exports into [`app/src/main/assets/speed_cameras.json`](app/src/main/assets/speed_cameras.json).
  **Mobile speed camera zones are NOT included** — the only source data available for
  those lists suburb/street names with no coordinates at all, so there's nothing to
  plot without a geocoding step (out of scope: would need a geocoding API, adding
  cost/complexity/rate limits this project has otherwise avoided everywhere else).

Both JSON files are seeded into their own Room tables (`SchoolZone`, `SpeedCamera`)
once on first launch — see
[StaticDataSeeder.kt](app/src/main/java/com/instructor/lessonroutes/data/StaticDataSeeder.kt).
They render on the live map home screen always-on (no toggle, since it's a cheap
local read, not a network call): school zones as red-circle "30"/"40" speed-sign
icons (matching real AU signage), fixed cameras as 📷, red-light cameras as 🚦.
No tap-to-info on these yet (hazards have it; these don't) — same pattern would
extend easily if wanted later.

**To refresh this data** (TfNSW updates these periodically): re-export the same
files from the Open Data Hub and re-run the extraction script used to produce the
committed JSON — ask Claude to regenerate it from fresh source files rather than
hand-editing the JSON.

### Database version bump

Adding `SchoolZone`/`SpeedCamera` bumped the Room schema to version 2 with
`fallbackToDestructiveMigration()` rather than a hand-written `Migration`. Version 3
(student profiles, see below) replaced that with a real
[`Migration(2, 3)`](app/src/main/java/com/instructor/lessonroutes/data/AppDatabase.kt)
that hand-writes the `CREATE TABLE`/`CREATE INDEX` SQL for the two new tables —
purely additive, no existing table changed, so existing installs upgrade in place
without losing saved routes. If a future schema change alters an *existing* table
(not just adds new ones), write and test its migration with the same care; a
migration's SQL has to match Room's expected schema exactly or it crashes on
upgrade.

## Trip generator ("Plan a trip")

A second, separate way to get a route, alongside recording/tapping one by hand:
[GenerateRouteScreen.kt](app/src/main/java/com/instructor/lessonroutes/ui/generate/GenerateRouteScreen.kt),
reached via "Plan a trip" on the live map. Pick a destination (loop back to
wherever you start, tap the map, or search an address via free/keyless
[Nominatim](https://nominatim.openstreetmap.org) geocoding —
[NominatimApi.kt](app/src/main/java/com/instructor/lessonroutes/data/remote/NominatimApi.kt)),
a start/end time, and avoid/prefer filters, then generate.

**Start/end time is only ever used to compute a target duration** (e.g. 5pm→6pm =
60 minutes) — generation happens immediately when you tap the button, it doesn't
wait for or schedule anything around the actual clock time. **The generated route
always starts from your current location right now**, even if that's the same
place as the destination (e.g. picking a student up from their house) — it still
plans a real drive away and back, sized to the target duration.

### How generation actually works (real feature vs. best-effort)

No free routing API can plan "a route of duration X" directly, so
[RouteGenerator.kt](app/src/main/java/com/instructor/lessonroutes/data/routegen/RouteGenerator.kt)
does it as a heuristic: try a detour point at each of 4 compass bearings around the
start/destination midpoint, ask OSRM for the *actual* drive time via
[OsrmApi.kt](app/src/main/java/com/instructor/lessonroutes/data/remote/OsrmApi.kt)'s
`fetchRoutedPaths()`, and adjust the detour distance iteratively (damped, up to 3
rounds) until it converges near the target. `generateCandidateRoutes()` (the OSRM
part) and scoring-data fetching (`buildScoringData()` in `GenerateRouteScreen.kt`)
run concurrently rather than one after the other, since they're fully independent;
whichever converged candidate best matches the chosen filters wins, via
`pickBestRoute()`.

**No filter is a real hard routing constraint — all of them are best-effort**,
per `FilterPreference`'s doc comment. Highways→Avoid was originally implemented
as OSRM's own `exclude=motorway` (a genuine hard constraint), but this was
confirmed directly against the live public API to be rejected outright for
*every* value (`{"code":"InvalidValue","message":"Exclude flag combination is
not supported."}`) — this public demo server simply doesn't support `exclude`
at all, so that path failed 100% of the time it was used. Removed; Highways is
now scored the same way as everything else:

- **Hazards, construction zones, school zones, speed cameras, highways,
  roundabouts, merging lanes — all of them, both Avoid and Prefer** — are soft
  proximity scoring: the 4 candidate routes are each scored by how many
  chosen-category points/roads they pass within ~40m of, and the best-scoring
  candidate is picked. This is a genuine best-of-a-few-alternates selection,
  not a guarantee any given hazard/camera/roundabout/highway is actually
  avoided or included.
- **Roundabouts** (`OverpassApi.fetchRoundabouts` — `junction=roundabout` ways +
  `highway=mini_roundabout` nodes) and **major roads** (`fetchMajorRoads`, for
  Highways scoring, both directions) are solid free OSM data via Overpass.
- **Merging lanes** (`OverpassApi.fetchMergeLaneProxies` — `motorway_link`/
  `trunk_link` ways) are an approximation, not real merge-lane data: OSM has no
  dedicated merge-lane tag, doesn't distinguish an on-ramp from an off-ramp in one
  field, and doesn't tag ordinary lane-merges on non-highway roads at all.
- Hazards/construction zones reuse the existing `fetchOpenIncidents`/
  `fetchOpenRoadworks` (needs a TfNSW API key — those filters have no effect
  without one); school zones/speed cameras reuse the existing seeded Room tables.

A generated route can be saved (writes a normal `Route` — same schema as a
tapped/recorded one, `timestamp = null` on every point so it also gets
road-snapped for display later the same way a tap-created route does) or opened
directly in Google Maps via the same `openInNavApp()` used by the route detail
screen (extracted to
[NavIntent.kt](app/src/main/java/com/instructor/lessonroutes/ui/routes/NavIntent.kt)
so both screens share it).

## Road-snapping for tap-created routes

Tap-created routes are a handful of hand-placed points; joined with straight lines
they rarely match the real road. [OsrmApi.kt](app/src/main/java/com/instructor/lessonroutes/data/remote/OsrmApi.kt)
snaps them through [OSRM](https://router.project-osrm.org)'s free, keyless public
routing server (`GET /route/v1/driving/{lon,lat;lon,lat;...}?overview=full&geometries=geojson`)
to get a dense path that follows actual roads — same "free shared community
service, best effort, fall back to something simpler on failure" posture as the
Overpass calls elsewhere in this app.

[RoadSnappedRoute.kt](app/src/main/java/com/instructor/lessonroutes/ui/map/RoadSnappedRoute.kt)'s
`rememberDisplayRoutePoints()` is the single decision point, used by both
`RouteDetailScreen` and `FollowScreen`: **only snaps tap-created routes**, detected
by every point having a null `timestamp` (per `RoutePoint`'s own doc — set when
recorded, null when tapped). A recorded GPS trail is left exactly as recorded and
never run through OSRM — it's already dense real-road data, and rerouting it
through a driving-directions engine could "correct away" a deliberate
off-road/wrong-lane maneuver the instructor recorded on purpose (e.g. a driveway
pull-in). Falls back to the original straight-line points on any OSRM failure, so
the map never shows nothing.

**What's NOT snapped**: the *stored* `RoutePoint` rows for a tap-created route are
still exactly the points the instructor tapped (unchanged schema, unchanged
`isWaypoint` semantics) — road-snapping is purely a display-time overlay computed
fresh each time, not baked into what's saved. `openInNavApp()`'s destination point
is likewise still the first stored (tapped) point, unaffected by this.

**If `router.project-osrm.org` ever becomes unreliable for this app's needs**: it's
a public demo instance, not a guaranteed-uptime production service (though this
app's volume — an instructor tapping out a handful of routes — is tiny by its
standards). Self-hosting OSRM or switching to a paid routing API would be the next
step; `fetchRoadSnappedPath()`'s signature wouldn't need to change.

## High traffic volume overlay (step 11, substituted for crash/black-spot data)

Built against TfNSW's **Traffic Volume Counts API** — a queryable SQL-over-HTTP API
(`/v1/traffic_volume?format=json&q=<SQL>`, CARTO-style), not a fixed REST endpoint.
See [TrafficVolumeApi.kt](app/src/main/java/com/instructor/lessonroutes/data/remote/TrafficVolumeApi.kt).
The query joins `road_traffic_counts_yearly_summary` to
`road_traffic_counts_station_reference`, filtered to
`classification_type='UNCLASSIFIED'` (all vehicles), `cardinal_direction_name='BOTH'`
(both directions combined), `period='ALL DAYS'`, and `traffic_count > 20000`/day (a
common real-world "busy arterial road" threshold — tune `HIGH_VOLUME_THRESHOLD` if
needed). Confirmed against real data before building (Western Distributor, Sydney
Harbour Tunnel, Hume Highway, etc. all showed up correctly).

Each matched station is then snapped to its actual OpenStreetMap road via the free
Overpass API and rendered as a real painted line (not a marker) — see
[OverpassApi.kt](app/src/main/java/com/instructor/lessonroutes/data/remote/OverpassApi.kt)
and "Overpass gotchas" below. Stations Overpass can't match fall back to a red
strip-icon marker. Tap either to see "High Traffic Volume" in the non-modal top
banner.

**If real crash/black-spot data is wanted later instead/as well**: the spec's
original assessment was that NSW crash data is point-based (individual crash
locations), not pre-aggregated road segments — same rendering approach as here
(snap points to OSM roads via Overpass) would apply once the actual current dataset
on the Open Data Hub / data.nsw.gov.au is identified and provided.

## Quiet roads overlay (step 11, OSM low-traffic proxy)

Per spec: no free source of actual measured street-level traffic exists, so this is
a heuristic — OSM roads tagged `residential`/`living_street` render as a thin teal
line, standing in for "quiet roads suitable for beginners." **Not measured
traffic** — the tap banner ("Quiet road (estimate)") says so explicitly, per spec's
instruction to label it as such.

Simplification worth knowing about: it fetches once, in a ~1.5km box around
whatever center is available at that moment (usually the Sydney fallback, since a
real GPS fix is rarely in yet that early) — it does **not** re-query as you pan the
map to a different area. Doing that properly would mean hooking into the map's
camera-idle events and debouncing re-fetches; a reasonable next step if this
overlay needs to follow you around rather than stay anchored near the start
location.

## App name and icon

Full name **NSW Driving Instructor Route Planner**, shorthand **Route Planner**.
`strings.xml`'s `app_name` (shown under the launcher icon — kept short since
launchers truncate long labels) is the shorthand; `app_name_full` holds the full
name for reference, and the Settings screen's heading spells it out in full since
there's room there.

The launcher icon ([ic_launcher_background.xml](app/src/main/res/drawable/ic_launcher_background.xml),
[ic_launcher_foreground.xml](app/src/main/res/drawable/ic_launcher_foreground.xml))
is hand-built as vector drawables (no raster PNGs) — purple `#71286F` background,
a white winding-road-plus-waypoint-dot glyph (already there, recolor aside), and a
new yellow `#F3E10E` navigation-arrow glyph in the top right. Both foreground
shapes are positioned to stay inside the adaptive icon's ~66dp-diameter safe circle
(centered at 54,54 of the 108×108 viewport) so a circular/squircle launcher mask
doesn't clip them.

## Color theme (purple + yellow)

Brand colors per Corey: purple `#71286F` and yellow `#F3E10E` — see
[Color.kt](app/src/main/java/com/instructor/lessonroutes/ui/theme/Color.kt) and
[Theme.kt](app/src/main/java/com/instructor/lessonroutes/ui/theme/Theme.kt).
Purple is `primary` (white text/icons on it), yellow is `secondary` (dark text on
it — yellow is too light for white-on-yellow to read well), each with a soft
tinted "container" variant for filled surfaces like the FAB.

**Dynamic color (Material You) is now off by default** (`dynamicColor = false` in
`LessonRoutesTheme`) — it was on before, and on Android 12+ it derives the app's
colors from the device wallpaper, silently overriding any custom palette. Leaving
it on would have made this purple/yellow theme invisible on most modern phones.

The route polyline and waypoint-marker colors in
[RouteMapView.kt](app/src/main/java/com/instructor/lessonroutes/ui/map/RouteMapView.kt)
(`ROUTE_LINE_COLOR`, `WAYPOINT_COLOR`) were updated to match (purple line, yellow
waypoint dots) — these were already meant to track the app's theme color per their
own old comment. The Phase 2 overlay colors (hazards, high-traffic-volume, quiet
roads, live-location dot) were deliberately **left alone**: those carry semantic
meaning (e.g. red = hazard) that's unrelated to app branding, and recoloring them
to purple/yellow would make the overlays harder to tell apart at a glance.

## Overpass gotchas (worth knowing if this breaks again)

- **A plain `[highway]` filter matches footways/cycleways/paths**, not just roads —
  confirmed this the hard way (it matched a pedestrian bridge path before the actual
  road). Both `matchRoadGeometry` and `fetchQuietRoads` restrict to real
  vehicle-carrying `highway` values.
- **Client timeout must exceed the query's own `[timeout:N]`** — a real bug hit here:
  the OkHttp client was set to 30s while the query declared `[timeout:60]`, so every
  request failed with a `SocketTimeoutException` regardless of how simple the query
  was, because the client gave up before the server's own allowance ran out. Both are
  now generously matched (client 150s, query up to 120s) since a large combined query
  covering every high-volume station can legitimately take a while.
- Uses the `overpass.kumi.systems` mirror, not `overpass-api.de` — the latter
  504'd during testing. Public Overpass mirrors do go up and down; worth trying an
  alternate mirror if this stops working.

## Build order status

1. ✅ Project + map on screen.
2. ✅ Room layer.
3. ✅ Polyline rendering.
4. ✅ Route list screen wired to Room.
5. 🔲 Tap-to-create — implemented, needs testing.
6. 🔲 Record-the-drive — implemented, needs testing.
7. 🔲 Follow view — implemented, needs testing.
8. 🔲 Polish — notes/tags/waypoints/edit/delete/nav-intent implemented and need
   testing; Settings ✅ done (minimal, see above; spec marked it optional/last).
9. ✅ Overlay foundation + live hazards — confirmed working (incidents + roadworks,
   tap for details).
10. 🔲 School zones + speed cameras — implemented as static bundled-asset overlays
    (see above), needs re-confirmation after later map changes. Not yet a "download
    on a schedule" live fetch per the spec's original architecture note — that'd
    need a confirmed download endpoint, which we don't have (this data was manually
    exported).
11. ✅ Remaining overlays — done, with a substitution: high-traffic-volume overlay
    instead of crash/black-spot data (see above for why + how to swap in real crash
    data later), plus the OSM quiet-roads low-traffic proxy (see above for its
    "fetches once, doesn't follow pans" simplification).

**Everything from the spec is now built in some form.** What's left is mostly
testing/confirmation (steps 5–8, step 10) and the two known simplifications flagged
above (quiet roads not following map pans; high-volume overlay using traffic volume
rather than literal crash data) — neither is a bug, both are documented tradeoffs
made to ship a working version rather than keep iterating.
