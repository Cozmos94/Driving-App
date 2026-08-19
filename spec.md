# Driving Lesson Route App — Specification

An Android app for a driving instructor to create, save, and follow custom driving
routes with their students. The instructor maps out routes they know are suitable
(quiet roads for beginners, roundabout practice, hill starts, etc.) and can pull up
any saved route during a lesson.

**Scope: New South Wales, Australia only.** The app is deliberately dedicated to NSW
instructors. The saved-routes core would work anywhere, but the map-overlay feature
(Phase 2) depends on Transport for NSW open data, which is NSW-specific. Supporting
other states/countries is an explicit non-goal — each would need its own equivalent
open-data sources, and we are not building that abstraction.

## Core constraint: everything free

This app is designed to run at **zero cost with no billing account and no credit
card**. That requirement drives every technology choice below. Do not introduce any
paid API or any SDK that requires a Google Cloud billing account.

Two facts shape the design:

1. **Google Maps was rejected on purpose.** Even though the native Android map
   display SKU is technically $0, the Maps SDK for Android requires an enabled
   billing account (a card on file). We avoid that entirely by using the
   OpenStreetMap stack, which needs no key, no account, and no card.

2. **We avoid routing APIs by recording the drive.** The expensive part of any maps
   app is asking a server to compute a road-by-road path. We sidestep it: the
   instructor records their GPS trail while driving the route once. That produces a
   dense sequence of on-road coordinates for free. Displaying a route is then just
   drawing a line through stored points — never a routing API call.

## Tech stack

- **Language:** Kotlin
- **UI:** Jetpack Compose, with the map embedded via `AndroidView` (the map SDKs are
  View-based, so wrap the `MapView` in an `AndroidView` composable).
- **Map rendering:** MapLibre Native (open-source, vector tiles, no key). Fallback
  option: osmdroid (raster tiles, even simpler) if MapLibre integration proves
  fiddly.
- **Map tiles:** a free, keyless vector tile source such as OpenFreeMap. Verify the
  current tile/style URL and terms at build time; if unavailable, fall back to
  osmdroid with standard OSM raster tiles (fine for this low volume).
- **Location:** FusedLocationProvider (`play-services-location`). It's free and far
  more accurate/battery-friendly than the platform `LocationManager`. It does pull in
  a Google Play Services library, but involves no billing account — if a 100%
  Google-free build is wanted, substitute `LocationManager`.
- **Persistence:** Room (local SQLite). Fully offline, free.
- **Min SDK:** API 26 (Android 8.0) is a reasonable floor; adjust as needed.

### Suggested Gradle dependencies

Pull the latest stable version of each — do not pin to the versions implied here
without checking:

- MapLibre: `org.maplibre.gl:android-sdk` (latest stable)
- (fallback) osmdroid: `org.osmdroid:osmdroid-android`
- Room: `androidx.room:room-runtime`, `androidx.room:room-ktx`, and the
  `androidx.room:room-compiler` via KSP
- Location: `com.google.android.gms:play-services-location`
- Compose: the current Compose BOM plus `androidx.activity:activity-compose`
- Coroutines: `org.jetbrains.kotlinx:kotlinx-coroutines-android`

## Data model (Room)

Two entities.

**Route**
- `id: Long` (primary key, auto-generate)
- `name: String`
- `description: String?`
- `notes: String?` — free text, e.g. "good for roundabout practice, avoid 3pm school zone"
- `dateCreated: Long` (epoch millis)
- `tag: String?` — optional, e.g. student level or skill focus

**RoutePoint**
- `id: Long` (primary key, auto-generate)
- `routeId: Long` (foreign key → Route.id, indexed, cascade delete)
- `latitude: Double`
- `longitude: Double`
- `sequenceOrder: Int` — preserves point order along the route
- `timestamp: Long?` — set when recorded, null when tapped
- `isWaypoint: Boolean` — marks a meaningful stop (e.g. "parallel park here"),
  default false

A route is simply an ordered list of points plus metadata. A DAO exposes: list all
routes, get a route with its points, insert/update/delete a route, and bulk-insert
points.

## Screens

1. **Route list** — all saved routes. Tap to open, long-press for edit/delete. Empty
   state prompts recording the first route.
2. **Create route** — a map with two modes:
   - *Record:* start / pause / stop capturing the live GPS trail while driving.
   - *Tap:* place waypoints by hand on the map.
   Save with a name and notes. Show the accumulating path live as a polyline.
3. **Route detail** — the full route drawn on the map, its notes, and actions:
   "Follow" and "Open in nav app".
4. **Follow / active view** — the route highlighted with the live position dot moving
   along it, so the instructor sees where they are relative to the planned path.

Settings (map style, units) is optional and can come last.

## Navigation decision (important)

Do **not** build in-app turn-by-turn voice navigation. That capability is a licensed,
paid product and is explicitly out of scope. The instructor already knows the route;
they need to *see* the planned line and their position on it, which the "Follow" view
provides. If spoken directions are ever wanted for a leg, launch an external nav app
via a `geo:` intent — no cost, no SDK.

## Map overlays (Phase 2)

The app targets instructors who are new to an area, so alongside their own saved
routes it overlays safety-relevant context on the map: school zones, roadworks, live
hazards, high-risk roads, and an approximation of quiet/low-traffic streets. All of it
is sourced free from NSW open data.

**Build this only after the core route save/follow flow works end to end.** It is a
separate subsystem (fetch → cache → render as map layers) and must not block or
complicate the core build. It does not change the routes/points Room schema; any
caching it needs lives in its own tables.

Feasibility by layer, honestly rated:

- **School zones — solid, free.** Transport for NSW publishes a Speed Zones dataset
  that includes school speed zones. This is static reference data: download
  periodically and cache locally. This is the highest-value overlay for the target
  user.
- **Roadworks / construction + live hazards — solid, free.** The Live Traffic Hazards
  API returns incidents, roadworks, fires, floods and major events as GeoJSON with GPS
  coordinates. Live data: fetch on demand / refresh, no need to persist.
- **High-risk roads — possible, more work.** NSW publishes historical crash/black-spot
  data as open government data. It is historical and statistical (not a live feed), so
  it must be downloaded and processed into a risk/heatmap overlay. Verify the exact
  current dataset before relying on it.
- **Low-traffic areas — approximation only.** Real-time traffic volume is not
  available free. Use OpenStreetMap road classification as a proxy: `residential` and
  `living_street` roads stand in for "quiet roads for beginners," queried free via the
  Overpass API. This is a heuristic, not measured traffic — label it as such in the UI.

### Overlay architecture notes

- Static datasets (school/speed zones, crash data) → download on a schedule, cache in
  their own Room tables (e.g. `SchoolZone`), render from cache. This keeps the app
  usable offline and avoids re-downloading.
- Live datasets (hazards/roadworks) → fetch fresh per session with a short in-memory
  cache; do not persist.
- Render each overlay as its own toggleable map layer so the instructor can turn
  clutter on/off.
- **API key handling:** the Transport for NSW key must NOT be committed to the repo or
  hardcoded in source. Keep it in `local.properties` (git-ignored) and expose it via
  `BuildConfig`, or inject it another secure way. Authenticate by sending the HTTP
  header `Authorization: apikey YOUR_TOKEN` (note the literal word `apikey` and a
  space before the token).
- **Licensing:** TfNSW open data is free for any purpose but carries attribution
  requirements. If the app is published, include the required attribution.

## Suggested build order

Scaffold in this sequence so there's something runnable early:

1. **Project + map on screen.** New Compose project, add MapLibre, get a map
   rendering with free tiles inside an `AndroidView`. Prove the tile source works.
2. **Room layer.** Add the two entities, DAO, and database. Seed a fake route and
   confirm it reads back.
3. **Display a route.** Draw a hard-coded / seeded route as a polyline overlay on the
   map. This validates the core "points → line" rendering.
4. **Route list screen.** Wire the list to Room; tap a route to open its detail view
   with the polyline.
5. **Tap-to-create.** Add waypoints by tapping the map, save to Room. This is the
   simplest creation path and needs no location permission.
6. **Record-the-drive.** Add location permission handling and FusedLocationProvider;
   capture a live trail with start/pause/stop and save it.
7. **Follow view.** Show live position against a selected route.
8. **Polish.** Notes, tags, waypoint markers, delete/edit, external-nav intent,
   settings.

Steps 1–8 above are the core app. The map overlays (see "Map overlays (Phase 2)") come
after, as a distinct phase:

9. **Overlay foundation.** Wire up the Transport for NSW API key (via `BuildConfig`),
   add a networking layer, and prove one feed renders — start with live hazards, since
   it needs no caching.
10. **School zones.** Add the static Speed Zones download + `SchoolZone` cache table +
    a toggleable overlay layer.
11. **Remaining overlays.** Crash/high-risk overlay (processed from historical data)
    and the OSM low-traffic proxy, each as its own toggleable layer.

## Out of scope (non-goals)

- In-app turn-by-turn voice navigation.
- Any account system, login, or cloud sync (everything is local to the device).
- Multi-user / student accounts.
- Any paid or billing-gated API.
- Support for states/countries outside NSW. The overlay data is NSW-specific by
  design; no multi-region abstraction is built.

## Cost notes

- Development and running the app: $0.
- Publishing to Google Play: a one-time $25 developer registration fee. Not needed if
  the app is only sideloaded onto the instructor's own phone.
- No per-use or monthly costs anywhere in this design.
- Transport for NSW Open Data Hub: free account, free API key. No usage charge for this
  app's volume.

## Appendix: NSW open data sources

All free, via the Transport for NSW Open Data Hub (free account + free API key already
obtained). Confirm exact resource paths and current terms in the Hub catalogue at build
time — the base references below are stable, but specific sub-resource URLs can change.

- **Open Data Hub portal:** https://opendata.transport.nsw.gov.au — dataset catalogue,
  API key management, and the developer user guide for authentication.
- **Live Traffic Hazards API** (incidents, roadworks, fires, floods, major events; live
  GeoJSON): base at `https://api.transport.nsw.gov.au/v1/live/hazards/`. Covers the
  roadworks/construction and live-hazard overlays.
- **Speed Zones dataset** (includes school speed zones): static reference data on the
  Hub; download and cache. Covers the school-zone overlay.
- **Crash / black-spot data:** historical NSW road crash open data (identify the exact
  current dataset on the Hub or data.nsw.gov.au). Download and process into the
  high-risk overlay.
- **OpenStreetMap Overpass API** (not TfNSW): free query endpoint for road
  classifications (`highway=residential`, `highway=living_street`) used as the
  low-traffic proxy.

Auth reminder: send `Authorization: apikey YOUR_TOKEN` on TfNSW API requests. Keep the
token out of source control (git-ignored `local.properties` → `BuildConfig`).

---

## Addendum: what actually shipped vs. this spec

This spec was written before development started. By the end of the build, a few
things diverged from what's written above — see `CLAUDE.md` and `README.md` for the
full story:

- **"High-risk roads" was built as a high-traffic-volume overlay**, not crash/
  black-spot data — the actual current crash dataset was never identified, and a
  Traffic Volume Counts API was found and confirmed working instead.
- **The app also gained a live "Google Maps style" home screen** (`LiveMapScreen`)
  that follows the device's position continuously, with hazards/traffic-volume/
  school-zones/cameras always visible — this wasn't in the original screen list but
  was added based on real usage feedback during the build ("they need to be able to
  drive and have the map navigate with them").
- **Speed cameras (fixed + red-light) were added** as a bonus static overlay, beyond
  the four overlay categories originally scoped — provided as manual data exports
  partway through the build.
- **High-volume roads and quiet roads render as real painted road geometry**
  (snapped to actual OpenStreetMap road shapes via the Overpass API), not just
  markers — an enhancement beyond the original "toggleable layer" description.
