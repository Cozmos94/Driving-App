# LessonRoutes

Android app for a driving instructor to record, save, and follow custom driving
routes with students, **scoped to NSW, Australia only**. Zero running cost: no
Google Maps SDK, no billing account, no routing API. See `spec.md` (in this repo's
parent context) for the full design.

## Status: Step 1 of 8 (core) done — map on screen

This scaffold gets a MapLibre map rendering with free OpenFreeMap vector tiles,
inside a Compose `AndroidView`. Nothing else is wired up yet — no Room, no
location, no route drawing. That's steps 2–8, followed by a separate Phase 2
(steps 9–11) adding NSW open-data map overlays (school zones, live hazards,
crash/black-spot data, an OSM-derived low-traffic proxy) — see `spec.md`'s
"Map overlays (Phase 2)" section. Phase 2 depends on a Transport for NSW API key
that must be kept out of source control (`local.properties` → `BuildConfig`, never
hardcoded or committed).

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

## Next steps (per spec's build order)

2. Room layer — `Route` / `RoutePoint` entities, DAO, database.
3. Draw a hard-coded route as a polyline overlay — validates points → line.
4. Route list screen wired to Room.
5. Tap-to-create waypoints (no location permission needed).
6. Record-the-drive via FusedLocationProvider.
7. Follow view — live position against a selected route.
8. Polish: notes, tags, waypoint markers, edit/delete, external-nav `geo:` intent,
   settings.

Then, as a distinct Phase 2 (only after 1–8 work end to end):

9. Overlay foundation — TfNSW API key via `BuildConfig`, networking layer, prove one
   feed renders (start with live hazards, no caching needed).
10. School zones — static Speed Zones download, `SchoolZone` cache table, toggleable
    layer.
11. Remaining overlays — crash/high-risk overlay, OSM low-traffic proxy layer.
