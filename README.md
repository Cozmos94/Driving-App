# LessonRoutes

Android app for a driving instructor to record, save, and follow custom driving
routes with students, **scoped to NSW, Australia only**. Zero running cost: no
Google Maps SDK, no billing account, no routing API. See `spec.md` (in this repo's
parent context) for the full design.

## Status: core build (steps 1–8) implemented, pending full test pass

Steps 1–4 (map rendering, Room layer, polyline drawing, route list → detail
navigation) are built and confirmed working. Steps 5–8 were implemented in one
larger pass and are **not yet confirmed** — see "What's implemented, untested" below
for exactly what to check. Phase 2 (NSW open-data map overlays, see `spec.md`'s
"Map overlays (Phase 2)" section) is underway: live hazards (step 9) are confirmed
working; school zones + speed cameras (step 10) are implemented as static
bundled-asset overlays and need testing; crash data + the OSM low-traffic proxy
(step 11) aren't started. Phase 2 needs a Transport for NSW API key kept out of
source control (`local.properties` → `BuildConfig`, never hardcoded or committed).

### What's implemented, untested

- **Create route** ([CreateRouteScreen.kt](app/src/main/java/com/instructor/lessonroutes/ui/routes/CreateRouteScreen.kt)):
  Tap mode (tap the map to add points) and Record mode (Start/Pause captures a live
  GPS trail via FusedLocationProvider) in one screen, a "mark last point as waypoint"
  action, and a save dialog (name + notes) that writes to Room.
- **Follow view** ([FollowScreen.kt](app/src/main/java/com/instructor/lessonroutes/ui/routes/FollowScreen.kt)):
  a selected route's polyline with a live position dot on top; camera fits the route's
  bounds once and doesn't chase the dot (deliberately, to avoid a jumpy camera).
- **Edit/delete**: long-press a route in the list for a rename/notes-edit dialog or a
  delete confirmation.
- **Open in nav app**: a `geo:` intent on the route detail screen, targeting the
  route's first point — no SDK, no cost.
- **Not built**: Settings (spec marks this optional/last) and anything from Phase 2.

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
`fallbackToDestructiveMigration()` rather than a hand-written `Migration` — a
migration has to match Room's expected SQL exactly or it crashes on upgrade, and at
this dev stage that risk wasn't worth it for two new additive tables. **Practical
effect: anyone with the app already installed loses their saved routes when they
update to this version** (uninstall/reinstall has the same effect, if you want to
force it deliberately). Worth writing a real migration before this app has real
users' data to protect.

## Build order status

1. ✅ Project + map on screen.
2. ✅ Room layer.
3. ✅ Polyline rendering.
4. ✅ Route list screen wired to Room.
5. 🔲 Tap-to-create — implemented, needs testing.
6. 🔲 Record-the-drive — implemented, needs testing.
7. 🔲 Follow view — implemented, needs testing.
8. 🔲 Polish — notes/tags/waypoints/edit/delete/nav-intent implemented and need
   testing; Settings not built (spec marks it optional/last).
9. ✅ Overlay foundation + live hazards — confirmed working (incidents + roadworks,
   tap for details).
10. 🔲 School zones + speed cameras — implemented as static bundled-asset overlays
    (see above), needs testing. Not yet a "download on a schedule" live fetch per
    the spec's original architecture note — that'd need a confirmed download
    endpoint, which we don't have (this data was manually exported).
11. Remaining overlays — crash/high-risk overlay, OSM low-traffic proxy layer. Not
    started.
