# LessonRoutes

Android app for a driving instructor to record, save, and follow custom driving
routes with students, **scoped to NSW, Australia only**. Zero running cost: no
Google Maps SDK, no billing account, no routing API. See `spec.md` (in this repo's
parent context) for the full design.

## Status: core build (steps 1–8) implemented, pending full test pass

Steps 1–4 (map rendering, Room layer, polyline drawing, route list → detail
navigation) are built and confirmed working. Steps 5–8 were just implemented in one
larger pass and are **not yet confirmed** — see "What's implemented, untested" below
for exactly what to check. After that, there's a separate Phase 2 (steps 9–11) adding
NSW open-data map overlays (school zones, live hazards, crash/black-spot data, an
OSM-derived low-traffic proxy) — see `spec.md`'s "Map overlays (Phase 2)" section.
Phase 2 depends on a Transport for NSW API key that must be kept out of source
control (`local.properties` → `BuildConfig`, never hardcoded or committed) — not
started yet.

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

The actual networking + first overlay render isn't built yet — the exact Live
Traffic Hazards endpoint path and response shape are documented in TfNSW's
"Live Traffic NSW Developer Guide" PDF, which requires logging into your Open Data
Hub account to download. Grab that PDF (or just the relevant endpoint/response
section) and hand it over to build against real specifics instead of guessing.

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

Then, as a distinct Phase 2 (only after 1–8 are confirmed working):

9. Overlay foundation — TfNSW API key via `BuildConfig`, networking layer, prove one
   feed renders (start with live hazards, no caching needed).
10. School zones — static Speed Zones download, `SchoolZone` cache table, toggleable
    layer.
11. Remaining overlays — crash/high-risk overlay, OSM low-traffic proxy layer.
