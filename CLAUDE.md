# LessonRoutes — session handoff

Orientation for a fresh Claude Code session picking this project up. Read
`spec.md` (original design) and `README.md` (build status, data sources, known
gotchas) for full detail — this file is the short version plus pointers.

## Obstacle filters: dropdown UI, Merging lanes removed

`GenerateRouteScreen.kt`'s FilterRow changed from two Avoid/Prefer chips to a
single dropdown per obstacle (No Preference / Avoid / Prefer, default No
Preference — Hazards still defaults to Avoid). Same `RouteGenerationFilters`/
`FilterPreference` model underneath, just a different picker UI (plain
`Box` + `DropdownMenu`, matching `RadiusPicker`'s own established pattern
above it in the same file — not `ExposedDropdownMenuBox`, which that
composable's own doc comment already flags as version-fragile). `FilterRow`'s
displayed value/dropdown options are colored via `FilterPreference.
displayColor()` (red/green/black), carrying over the old chips' red/green cue
now that there's no per-option fill to color.

**Merging lanes deleted as a category entirely** (Corey: "it just doesn't
make sense") — removed from `RouteGenerationFilters`, `ScoringData`,
`ALL_FILTER_LABELS`, all scoring/avoidance functions, and
`OverpassApi.fetchMergeLaneProxies` (the underlying data fetch, now deleted,
not just unwired). Only Roundabouts and Highways remain as Overpass-backed
categories — the "paired concurrency" dance `buildScoringData` used to need
(round 1: Roundabouts+Merging lanes, round 2: Highways, to stay under
Overpass's confirmed ~2 concurrent-request limit) simplified to both firing
concurrently, since 2 is already the safe number.

## ⚠️ REAL DEADLINE: TomTom ends support for SDK 2.4.x on 2 Nov 2026

Corey got a direct email from TomTom (31-Aug-2026): NavSDK 2.5 is now live,
"premium map display" becomes the default renderer, and support for 2.4 and
earlier ends 2 Nov 2026. This project is currently pinned to `tomtomSdk =
"2.4.2"` (see the section right below this one) specifically *because* 2.5.3
broke map rendering — so this deadline forces a real decision, not an
optional cleanup.

**What's been checked against TomTom's own docs (all fetched live, not
guessed)**:
- 2.5.3 is still the current latest release as of 31-Aug-2026 (checked their
  Artifactory directly) — no newer patch exists yet that might already fix
  our issue.
- "Premium map display as default" is a one-line dependency swap
  (`map-display-compose-standard` → `map-display-compose-premium`) — not
  itself the cause of our bug, since we hit the blank-map bug while still on
  `-standard`.
- TomTom's own 2.5.3 release notes mention fixing "a map view that was never
  cleanly shut down could leave the map permanently blank" and "early map
  readiness timing in applications showing multiple map views" — suspiciously
  close to our exact symptom, meaning this is a known *category* of bug
  they're actively iterating on in this version line, but evidently their fix
  doesn't (yet) cover whatever variant we're hitting.
- The "step-by-step migration guide" Corey found is the **v1→v2** migration
  (`1.26.x` → `2.x`) — a transition this project already completed long ago.
  It says nothing about 2.4→2.5, the standard/premium display swap, blank
  maps, or MapLibre. Don't re-read it expecting new information here.
- No public TomTom doc addresses apps that *also* depend directly on
  `org.maplibre.gl:android-sdk` (this project's own situation, see below).

**Investigation done, root cause still NOT conclusively identified**: the two
competing theories are (a) a build-time native-library packaging conflict —
this project's own direct `org.maplibre.gl:android-sdk` dependency and
TomTom's bundled copy might produce a same-named `libmaplibre.so` that
Gradle's packaging merges/picks between regardless of runtime screen order —
vs. (b) TomTom's 2.5.x renderer is simply broken in this environment on its
own, unrelated to this project's MapLibre dependency at all. The screen-order
isolation test (see below) doesn't distinguish these, since our own
dependency is still present in the build either way. The test that WOULD
distinguish them — a throwaway branch stripping the `org.maplibre.gl:
android-sdk` dependency entirely (stubbing the screens that need it just
enough to compile) and checking whether TomTom's map then renders in true
isolation — was scoped and proposed but **explicitly not carried out**
(Corey: "let's just avoid the update for now and continue").

**If/when this gets picked back up, a full removal of this project's own
MapLibre dependency (rebuilding every map screen on TomTom's own map instead)
was scoped and is a real option, but a large one** — `RouteMapView.kt` is 918
lines used across 6 files (`LiveMapScreen`, `GenerateRouteScreen`,
`CreateRouteScreen`, `FollowScreen`, `RouteDetailScreen`, and
`TomTomNavigationScreen`'s own `FallbackLiveMap`), and implements hazard/
school-zone/speed-camera/traffic-volume/quiet-road overlays via low-level
MapLibre vector-tile APIs (`GeoJsonSource` + `SymbolLayer`/`LineLayer`/
`CircleLayer`/`FillLayer`, with runtime-drawn bitmap icons) plus a draggable
radius-circle overlay and tap-hit-testing against all of it. TomTom's Compose
map API only exposes higher-level composables (`Marker`, `Markers`,
`Polyline`, `CurrentLocationMarker`, `Traffic`) — no confirmed equivalent to
raw GeoJSON/custom-layer sources at the Compose layer (TomTom's *non*-Compose
`StyleController`/`Layer` classes might support it — genuinely unconfirmed,
would need real investigation before committing to this path). Also worth
noting: `RouteMapView` is currently the fallback shown *inside*
`TomTomNavigationScreen.kt` itself when TomTom's own navigation fails to
start — removing it entirely removes that safety net too, an important
wrinkle for whoever picks this up.

**Current decision (Corey, 31-Aug-2026): defer.** Stay on `tomtomSdk =
"2.4.2"`, do not re-bump and do not start the MapLibre-removal work, until
this is explicitly revisited — ideally well before the 2 Nov 2026 deadline,
since neither path (waiting for a TomTom patch, or the full removal rewrite)
is fast to execute from a standing start that close to the date.

## Most recent (superseded by the deadline section above): TomTom SDK reverted to 2.4.2 — 2.5.3 broke map rendering entirely

This supersedes the "TomTom Navigation SDK integration" section's own
version-bump narrative below (still worth reading for the crash-diagnosis
history — just not its conclusion).

**Confirmed via live on-device testing**: bumping `tomtomSdk` 2.4.2 → 2.5.3 (to
try to fix a native crash, see below) did NOT fix the crash — a fresh
tombstone at 2.5.3 crashed in the exact same `mbgl::android::MapRenderer::
update()` frame as before the bump — and additionally broke map rendering
completely: the nav screen showed a flat background with zero roads/
buildings/land. Confirmed this wasn't a style-specific issue (swapped
`StandardStyles.TomTomOrbisMaps.DRIVING` for `.BROWSING` live, still blank)
and not a load failure (`MapStyleState.loadStyle()` — wrapped in try/catch
specifically for this, see `TomTomNavigationScreen.kt` — reported success
both times). Also confirmed still blank when navigating straight from a
fresh app launch with no other map screen opened first (an isolation test to
rule out cross-contamination from this app's *other* MapLibre-based screens
having been open first). Reverted `tomtomSdk` back to `2.4.2` in
`gradle/libs.versions.toml` — confirmed live this restores full rendering.

**Leading theory** (not proven, but well-supported, and not fully ruled out
by the isolation test above since TomTom's own bundled native library is
loaded regardless of whether *this app's* other MapLibre screens were opened
first): this project uniquely depends directly on `org.maplibre.gl:
android-sdk` (used by every non-nav map screen, `RouteMapView.kt`) *in
addition to* TomTom's own bundled/embedded native MapLibre copy (confirmed
earlier that TomTom's map rendering is built on MapLibre — a tombstone showed
`libmaplibre.so` inside `base.apk`, called from TomTom's own `mbgl::android::
MapRenderer`). If 2.5.3 bundles a different native MapLibre build than 2.4.2
did, two different native builds of the same engine coexisting in one process
(regardless of load order) would plausibly explain both the rendering
breakage and the crash — and would explain why TomTom's own example app
(also on 2.5.3, confirmed live against their public GitHub repo) doesn't hit
either problem, since it doesn't separately depend on MapLibre itself.

**Current tradeoff, accepted for now (Corey's explicit call)**: 2.4.2 still
has the original native crash (`mbgl::android::MapRenderer`, SIGABRT/SIGSEGV,
recurring at ~10 minutes of continuous navigation in real device testing) —
fix the map-rendering regression first (done, via the revert) and leave the
rarer crash as a known, accepted issue rather than keep chasing it blind.
Real crash evidence (tombstones, Logcat traces) is already gathered if this
needs picking back up — see "TomTom Navigation SDK integration" below for
the full crash-diagnosis history.

**Do not bump `tomtomSdk` again without on-device retesting BOTH the crash
and full map rendering (roads/buildings/land, not just "does it compile /
does routing still work")** — 2.5.3 looked completely fine by every other
check; only actually looking at the rendered map caught this.

## Latest session recap (read this first)

**This section supersedes everything below it up to "What this is"**,
including the "Geoapify replaces..." and "real bugs found" narrative that used
to be the first thing in this file -- that older material is still fine for
background on Overpass/Geoapify history, but this session's work (UI theme,
radius-generation algorithm rewrite, and a long TomTom Navigation SDK
integration saga) is far more current and, in several places, directly
contradicts it (e.g. "Open in nav app" no longer exists; the radius/duration
relationship was fundamentally wrong before this session and has been rebuilt).

### In-progress right now: TomTom Navigation SDK integration

**Why**: Google Maps' "Open in nav app" hand-off (waypoint-based Directions)
can't faithfully replay a route that deliberately loops/backtracks -- Maps
always recomputes its own optimized path between at most ~8-10 waypoints,
which silently collapsed a real generated 3h19m route down to 1h21m once
opened in Maps. TomTom's Navigation SDK has a genuine "reconstruct route from
an already-computed polyline" mechanism (`supportingPoints`/
`ReconstructionMode`) with no documented waypoint cap, unlike Mapbox (needs a
card even for the free tier -- Corey said no) and Google's own Navigation SDK
(25-waypoint hard cap, also needs GCP billing enabled = a card, no way around
it). TomTom's free tier needs no card.

**Current state**: a throwaway spike screen,
`app/src/main/java/com/instructor/lessonroutes/ui/navspike/TomTomNavSpikeScreen.kt`
(reachable via Settings → "TomTom nav spike", a temporary debug button --
delete this whole screen/button/nav-destination once the spike question is
answered either way), reconstructs a hardcoded 6-petal backtracking test loop
and attempts to start TomTom guidance. **Gradle now compiles successfully**
after a long chain of wrong-guess fixes (see below) -- **not yet confirmed
that the spike actually runs/guides correctly on-device**, since Corey's
testing has all been on the *main app's* "Plan a trip" flow (which still uses
Geoapify for generation, unrelated to TomTom -- see below) rather than this
spike screen specifically. **Next step: get Corey to actually open Settings →
"TomTom nav spike" and report what happens.**

**TomTom is NOT wired into the real "Navigate" button yet.** The main app's
"Navigate" button (on `GenerateRouteScreen.kt`, was "Open in nav app") still
shows a plain custom live-tracking view (`RouteMapView` with the generated
route drawn + a live position dot, zoomed in and following via
`followLiveLocation`/`focusZoom`) -- built *before* the TomTom work started, as
an interim replacement for the abandoned Google Maps hand-off. Wiring TomTom's
real guidance into that button is future work, only worth doing once the spike
proves the reconstruction approach actually works well.

**Hard-won TomTom Gradle/SDK facts** (worth a lot to not have to
rediscover -- their docs are inconsistent and their examples repo is
archived; the only reliable source was browsing
`repositories.tomtom.com/artifactory/maven` directly, which is publicly
browsable without login even though downloading some paths needs auth, and
the real Dokka API reference at
`developer.tomtom.com/assets/downloads/tomtom-sdks/android/api-reference/2.4.2/index.html`):

- TomTom moved their whole developer portal to `my.tomtom.com` recently --
  `developer.tomtom.com`/`docs.tomtom.com` guide pages are stale/inconsistent
  post-migration. The Dokka API reference URL above is still reliable.
- `repositories.tomtom.com` (the Maven repo host) uses a **separate SSO
  system** from `my.tomtom.com` -- Corey's `my.tomtom.com` account doesn't
  work there, and he has no way to sign up for it. **Turned out not to
  matter**: the "complete" SDK flavor (see `missingDimensionStrategy` in
  `app/build.gradle.kts`) downloads fine with zero repo credentials for every
  artifact actually needed. `settings.gradle.kts` still has an optional
  credentials block (reads `TOMTOM_REPO_USERNAME`/`TOMTOM_REPO_IDENTITY_TOKEN`
  from `local.properties` if present, skipped entirely otherwise) in case a
  future artifact does need it.
- **compileSdk must be 35** (bumped from 34 -- a hard TomTom requirement).
  `targetSdk` deliberately left at 34.
- Needs `ndk { abiFilters += listOf("arm64-v8a", "x86_64") }` and
  `missingDimensionStrategy("tomtom-sdk-version", "complete")` in
  `defaultConfig`.
- **The `com.tomtom.sdk.navigation:*` module family is versioned on a
  completely independent scheme from the rest of the SDK.** Everything else
  (`init`, `common:configuration`, `location:provider-simulation`,
  `routing:route-planner`) is on the "2.4.2" umbrella version
  (`tomtomSdk` in `gradle/libs.versions.toml`). `navigation-online` and
  `navigation-android` are on their own 0.x/1.x scheme (up to 1.26.8 as of
  this session) -- **do not** pin either of those to "2.4.2", it doesn't
  exist and Artifactory returns a misleading **401** (not 404) for that
  nonexistent version path, which reads exactly like a real permissions
  problem and sent this session down a dead-end SSO rabbit hole before being
  found out. **The fix**: depend on the plain `com.tomtom.sdk.navigation:
  navigation` artifact (no `-android` suffix, same naming pattern as
  `com.tomtom.sdk:init`) -- it genuinely does publish real "2.4.2" releases
  and its own POM depends only on other 2.4.2 artifacts, avoiding the whole
  version-mismatch cascade. Mixing the two version families caused a chain of
  "Duplicate class" dex-merge errors (first `org.sensoris.types.*`, then a
  *third*, totally unrelated version scheme `com.tomtom.navigation.internal:
  navigation-drivingassistance-model:33.1.0`) that would have kept recurring
  indefinitely if pursued by excluding one colliding module at a time instead
  of fixing the actual mismatch.
- `navigation-android-complete` (tempting name-match with the "complete"
  flavor) is a **red herring** -- it's a bundle of supporting services
  (adas/hazards/traffic/vehicle/data-management), not the actual navigation
  guidance engine (`TomTomNavigation`/`NavigationOptions`/`RoutePlan`).
- `buildSdkConfiguration()` is a **top-level function** in
  `com.tomtom.sdk.common.configuration` (module `com.tomtom.sdk.common:
  configuration`, not pulled in transitively by anything else -- needs its
  own explicit dependency), **not** a member of `TomTomSdk` as the docs'
  code-snippet styling implies. The overload used in the spike needs only
  `context`/`apiKey`, no telemetry-consent callback.
- `createRoutePlanner()` is a genuine Kotlin **extension function** on
  `TomTomSdk` (`fun TomTomSdk.createRoutePlanner(): RoutePlanner`, declared in
  `com.tomtom.sdk.init`) -- Kotlin requires importing extension functions
  explicitly even when called via `TomTomSdk.createRoutePlanner()` receiver
  syntax; easy to forget.
- `Itinerary`/`RouteLegOptions`/`RoutePlanningOptions` live in
  `com.tomtom.sdk.routing.options` (not the bare `com.tomtom.sdk.routing`
  package). `ReconstructionMode` is one level deeper still, in
  `com.tomtom.sdk.routing.options.calculation`.
  `RoutePlanningCallback`/`RoutePlanningResponse`/`RoutingFailure`/
  `RoutePlanner` *are* in the bare `com.tomtom.sdk.routing` package.
- `Route`'s total distance/duration are **not** `route.distance`/
  `route.duration` -- they're nested under `route.summary.length` (type
  `Distance`) and `route.summary.travelTime` (type `Duration`). The spike
  just displays these via their own `toString()` rather than chasing the
  exact meters/seconds accessor.
- `NavigationOptions`/`RoutePlan`/`TomTomNavigation` are all in the plain
  `com.tomtom.sdk.navigation` package (the same "navigation" artifact
  mentioned above). `NavigationOptions(activeRoutePlan: RoutePlan)`,
  `RoutePlan(route: Route, routePlanningOptions: RoutePlanningOptions)`,
  `TomTomNavigation.start(NavigationOptions)`/`.stop()` are genuine interface
  members, no import surprises there.

### Route generator: radius is now a hard spatial boundary, not a duration ceiling

Real, confirmed-by-Corey bug: the old design let duration silently undershoot
once a radius cap was hit ("the intended trade-off", per the old code
comment) -- wrong. Corey: *"the route needs to stay within the radius...
hitting the radius barrier does not mean the route has to then go to the
destination and finish."* `RouteGenerator.kt` was rewritten:

- **No radius set**: unchanged single-detour-point convergence
  (`refineCandidate`).
- **Radius set**: `refineCandidateWithinRadius` chains `start` + several
  "petal" waypoints (each at the *full* radius from start, evenly spaced
  around a circle) + `destination`, tuning **petal count** against the target
  duration instead of a single point's reach (which is already maxed out at
  the cap). Several petals in different directions naturally forces
  backtracking through nearby roads between them -- satisfying "drive over
  the same roads if it has to" via the geometry itself, not an explicit rule.
  Confirmed live against Geoapify that an 8km-radius, 6-petal chain produces a
  genuinely realistic 85min/84km route, and that 18-waypoint chains resolve
  fine in ~1.5s (well within `MAX_SPOKES = 12`).

**Two real bugs found and fixed since that rewrite, both via actual Corey
bug reports, not hypothetical**:

1. **`best` was unconditionally overwritten every round** in both
   `refineCandidate` and `refineCandidateWithinRadius`, regardless of whether
   that round actually landed closer to target than a previous one. If a
   later, worse round (e.g. a degenerate 0-petal direct route, which is short
   by construction) ran right before the *next* round then failed outright,
   the worse result is what got returned as "best" -- not the actual closest
   one seen. Fixed: both functions now track `bestErrorSeconds` and only
   replace `best` on genuine improvement.
2. **A single unroutable petal failed the whole chain, and retrying only
   shrank the radius, never changed direction.** Geoapify's real error
   (confirmed by fixing `GeoapifyRoutingApi.kt` to stop discarding the
   response body on failure -- it only ever logged bare "HTTP 400" before,
   now includes Geoapify's actual message) is `"No suitable edges near
   location"` -- a genuine "this waypoint has no nearby road" (water, a park,
   etc.), not a real lat/lon-order bug despite what that message's own text
   suggests. Shrinking radius by 15% and retrying the *same* bearing
   directions does nothing if whatever's blocking a petal (e.g. a harbour
   near the start point) is still within the smaller radius too -- confirmed
   as the actual cause of routes landing at ~1 minute regardless of target
   (every petaled attempt kept failing in the same doomed direction, leaving
   only the short direct-route fallback to ever succeed). Fixed: failed
   rounds now also rotate the whole petal ring by 47° (not a clean fraction
   of 360, so it doesn't just relabel the same directions), giving each retry
   an actual chance to dodge the blocked direction.

**Not yet confirmed working end-to-end on-device** -- this was mid-retest
when the session paused for context. If Corey reports another bad result,
check Logcat tag `RouteGenerator` first (heavily instrumented: every round
logs radius/spokes/duration/target/ratio) before guessing further.

### UI/theme, in final-as-of-this-session state

- **Color palette** (`Color.kt`): `#023E8A` selected buttons + reused as the
  border for Generate Route/Plan a Trip/Student Profiles buttons (which are
  **white fill + `#023E8A` border**, not filled -- went through several
  iterations: filled `#0096C7` → filled `#00B4D8` → filled `#90E0EF` → current
  white+border). `#0077B6` unselected buttons. Background/surface is **plain
  white** (`BackgroundWhite`, went through `#CAF0F8` → `#90E0EF` → white).
  `#ADE8F4` (`ClockAccentCyan`) is a leftover fallback for tertiary-family M3
  roles Corey hasn't specified -- **not** used for the clock anymore (next
  point). `#03045E` (`BorderNavy`) is border colour + button font, black is
  used for all other text on the white background. A real M3 gotcha fixed
  along the way: `surfaceTint` was never set explicitly (defaults to
  `primary`), so elevated surfaces (dialogs, cards) were blending toward
  primary blue instead of showing the literal flat hex -- fixed with
  `surfaceTint = Color.Transparent` in `Theme.kt`.
- **The clock (TimePicker)** deliberately does *not* use the app's custom
  theme -- `AppTimePickerDialog` in `GenerateRouteScreen.kt` wraps it in a
  fresh `MaterialTheme` using `dynamicLightColorScheme`/
  `dynamicDarkColorScheme` (Android 12+ Material You, derived from the
  device's actual wallpaper) when available, falling back to the M3 baseline
  only pre-Android-12. Plain baseline colors alone are NOT "device default"
  despite looking like a reasonable default -- they're a purple/pink-seeded
  demo palette baked into Compose Material3, confirmed as the real source of
  an earlier "the AM/PM selector is pink" report. The AM/PM period-selector
  colors are further remapped from the (Material-You-typically-pink) tertiary
  role to primary/surface/outline specifically, since Material You
  deliberately makes tertiary a different hue family from primary by design.
- **"Loop back to where I start" removed** -- destination is now always a
  real place (map tap or address search); `loopBackToStart` and all its
  branches are gone from `GenerateRouteScreen.kt`.
- **A splash/launch screen** now exists
  (`app/src/main/java/com/instructor/lessonroutes/ui/splash/SplashScreen.kt`),
  shown by `AppNavHost.kt` during the one-time static-data-seed gate (the
  app's actual "just opened, still loading" moment). Reconstructs Corey's
  supplied SVG design (green background, hand-drawn squiggle, flag/pennant,
  yellow L-plate, bottom fade, title+subtitle) directly via Compose `Canvas`
  draw calls (paths/transforms traced 1:1 from the SVG), not a raster/vector
  asset -- scales to any real screen size via scale-to-fit. Two known
  simplifications: text position is approximated (SVG's baseline-based x/y
  vs Compose `drawText`'s top-left-based positioning), and the SVG's
  requested Inter font falls back to the platform default sans-serif (not
  bundled in this app).
- **Address search restricted to real NSW addresses** via Geoapify's
  per-result `state_code` field (confirmed live: `"NSW"` vs `"VIC"`), not
  just the existing `NSW_RECT_FILTER` bounding-box (which only approximates
  NSW's border and let genuine interstate addresses like Wodonga VIC through)
  in `GeoapifyGeocodingApi.kt`.

### Other changes this session

- **Bold labels + minor UI polish on "Plan a trip"**: "Generated"/
  "Destination"/"Trip time"/"Duration"/"Optional Filters" bolded (label word
  only, via `buildAnnotatedString`, where the line also carries a value).
  "Set End time" (was "Pick an end time...") is bold red, shown as a
  full-width line below the Start/End time row, disappearing once an end time
  is picked. The Avoid/Prefer legend is three separate lines
  (`FilterLegendLine` helper) with the term bolded to `Medium` weight (a step
  lighter than section headers).
- **Auto-generated route description**: the Save dialog's Description field
  now pre-fills with a summary (duration/distance + Avoid/Prefer categories
  used, `buildAutoDescription()` in `GenerateRouteScreen.kt`), fully editable
  before saving. Wired to `Route.description`, a column that already existed
  in the schema but was never actually used anywhere -- no migration needed.
  Also now shown on `RouteDetailScreen` (previously only showed `notes`).
- **"Navigate"** (was "Open in nav app") swaps the whole "Plan a trip" screen
  to a live-tracking view: the exact `generatedRoute` polyline via
  `RouteMapView`, zoomed in (`focusZoom = 16.0`, a new `RouteMapView` param)
  and panning to follow the live position (`followLiveLocation = true`,
  reusing `currentLocation` this screen already tracks -- no second location
  listener). Exits via a Close button or system back (`BackHandler`). Not
  persisted -- Save is still separate. See the TomTom section above for
  where this is headed next.

## Old recap (superseded by the above, kept for background only)

Everything below this point up to "What this is" happened in one long session
after the "Current status"/"Trip generator" narrative further down was last
written -- that narrative is still worth reading for the trip generator's core
design and its round-by-round bug history, but treat anything in it about
OpenFreeMap/Nominatim/OSRM specifically as **superseded**, not current.

### The big architectural change: Geoapify replaces OpenFreeMap + Nominatim + OSRM

Map tiles, address geocoding, and trip-generator routing all moved to
**Geoapify** (needs `GEOAPIFY_API_KEY` in `local.properties` -- see README's
"Required setup" section, now first in that file). A deliberate upgrade, not a
lateral swap, confirmed live against the real APIs before any code was written:

- **Routing** ([GeoapifyRoutingApi.kt](app/src/main/java/com/instructor/lessonroutes/data/remote/GeoapifyRoutingApi.kt),
  replaces the deleted OsrmApi.kt): `avoid=highways`/`avoid=tolls` are *real*
  hard routing constraints -- confirmed live, a test route went from
  26.6km/28min to 33.6km/37min with it set, a genuine detour around motorways.
  Fixes Highways→Avoid, which used to be soft-scoring-only because OSRM's
  public server rejected an equivalent `exclude=motorway` outright. No
  alternatives support (confirmed live Geoapify has no equivalent to OSRM's
  `alternatives=true`) -- removed from RouteGenerator.kt rather than guessed at.
- **Geocoding** ([GeoapifyGeocodingApi.kt](app/src/main/java/com/instructor/lessonroutes/data/remote/GeoapifyGeocodingApi.kt),
  replaces the deleted NominatimApi.kt): blends in the OpenAddresses dataset
  alongside OSM -- confirmed live resolving real house numbers (e.g. "48 Queen
  Street, Campbelltown") that Nominatim could only match to street level.
- **Map tiles** ([RouteMapView.kt](app/src/main/java/com/instructor/lessonroutes/ui/map/RouteMapView.kt)'s
  `GEOAPIFY_STYLE_URL`): Geoapify's `osm-liberty` vector style, visually
  equivalent to OpenFreeMap's `liberty` style used before.

**Real gotcha already hit once, will bite a fresh checkout too**:
`local.properties` is git-ignored *by design* and never travels via git push/
pull. Adding `GEOAPIFY_API_KEY` there in one checkout does not propagate to any
other -- this caused "the map has disappeared from every screen" after Corey
pulled the code change without also adding the key to his own build's
`local.properties`. `RouteMapView.kt` now fails loud (a clear on-screen message)
instead of silently blanking if the key's missing, which is how that got
diagnosed -- but a *fresh* checkout will hit this every time until the key's
added there too. Don't forget this yourself either.

### Real bugs found via actual live-device testing/live-API verification this session

- **Duration-scoring bug (real, now fixed)**: `pickBestRoute` used to pick
  whichever candidate matched Avoid/Prefer filters best, *completely ignoring*
  how close each candidate's own duration was to the target -- with filters
  tied (or none set), the pick was effectively arbitrary regardless of
  duration fit. Confirmed via a real report (target 1h43m, generated 1h15m --
  37% off, outside the 25% convergence tolerance, meaning some other candidate
  was almost certainly closer but lost for no reason). Fixed: `scoreRoute` now
  subtracts a heavily-weighted duration-error term
  (`DURATION_ERROR_WEIGHT_PER_MINUTE = 10.0`), making duration-closeness the
  primary driver and filters a real but secondary tie-breaker.
  **Not fully resolved as of this handoff**: after this fix shipped, Corey
  reported *another* bad mismatch (target 82min, generated 37min) on a run
  that also hit the Overpass timeout bug below -- unclear yet whether that was
  a symptom of the Overpass failure or a separate issue. **Needs a clean
  retest** (no filters timing out) before concluding the duration fix itself
  is insuffient.
- **Overpass endpoint broken + too-tight scoring timeout (real, now fixed)**:
  the app's configured Overpass server (`overpass.kumi.systems`) was
  confirmed live to be returning bare HTTP 500s for even a trivial query,
  unrelated to this app's own queries. Switched to the main/official instance
  (`overpass-api.de`), which needs an explicit `Accept` header or it 406s
  (also confirmed live) -- see `overpassHeaders()` in
  [OverpassApi.kt](app/src/main/java/com/instructor/lessonroutes/data/remote/OverpassApi.kt).
  Separately, even once pointed at a working server, Roundabouts/Merging
  lanes/Highways scoring queries genuinely take 9-12+ seconds at this screen's
  real search radius (confirmed live) -- longer than the 8s per-category
  timeout added a few rounds earlier, so they were being marked "couldn't load
  data" on every run. Fixed with a 20s timeout specifically for these three
  (`OVERPASS_SCORING_FETCH_TIMEOUT_MS`) plus a capped ~17km search radius for
  them (`OVERPASS_SCORING_RADIUS_CAP_DEGREES`), both in
  [GenerateRouteScreen.kt](app/src/main/java/com/instructor/lessonroutes/ui/generate/GenerateRouteScreen.kt).
  **Not yet re-tested on-device.**
- **Address search race condition (real, now fixed)**: the live-search
  `LaunchedEffect` cancels the *coroutine* on every keystroke, but
  `searchAddress()`'s underlying OkHttp call is a synchronous, non-
  cancellation-aware blocking call that isn't actually interrupted by that --
  it can complete (successfully) *after* a newer search already displayed its
  result, silently overwriting it with a less-specific match. This is why
  house numbers appeared on an emulator (fast/uniform network, rarely
  reorders) but not a real phone (variable mobile latency, reorders more
  often) with the *same build and same data*. Fixed by tagging each search
  with the exact query it was for and discarding a stale result if
  `searchQuery` moved on before it returned.
- **Radius circle re-zooming on every change (real, now fixed)**: the "fit
  camera to the radius circle" effect in RouteMapView.kt re-ran (and re-
  zoomed) on every radius dropdown change, not just when the circle first
  appeared. Now guarded to fire once (`hasFitRadiusCircle`), matching the
  existing `hasAppliedFocusPoint` pattern just above it -- later radius
  changes just resize the circle in place. **Not yet re-tested on-device.**

### Other changes this session (not bug fixes -- features/requests)

- **Trip radius cap**: new "Set radius" dropdown (5km steps to 200km) in
  GenerateRouteScreen.kt, shown as a real circle overlay on the map
  (`radiusCircleCenter`/`radiusCircleKm` in RouteMapView.kt, a 64-point
  polygon approximating a true geo-circle). `maxRadiusKm` is enforced two
  ways in RouteGenerator.kt: biases candidate generation toward it, and
  `routeExceedsRadius`/`pickBestRoute` hard-filter to conforming candidates
  when at least one exists -- if literally none can (the destination itself
  is farther than the radius allows), falls back to the least-bad candidate
  and surfaces a warning rather than failing outright. The radius is anchored
  to the trip's **start** location, not the generator's internal start/
  destination midpoint (that midpoint is just where the detour-bearing search
  is centered, not what "how far from me" should mean).
- **Structured Avoid/Prefer storage + student coverage tracking**: `Route`
  gained `avoidFilters`/`preferFilters` (Room v5, replacing an even-shorter-
  lived single-paragraph `generationFilters` column from v4 -- see
  `effectiveFilterSummary()` in RouteGenerator.kt for the fallback that
  recovers data from routes saved during that brief v4 window). RouteDetailScreen
  shows these as small pill badges instead of a paragraph. RouteListScreen,
  when scoped to one student profile, shows **"Obstacles covered: ... /
  Obstacles yet to cover: ..."** (bold labels, on separate lines) computed
  from every generated route ever saved for that student -- "covered" means
  Prefer was set for that category in at least one of their routes; Avoid or
  never-set both count as "yet to cover". Always shows *something* once
  scoped to a profile (even a plain "no generated routes saved yet" line) --
  it used to just show nothing at all when there was no data, indistinguishable
  from being broken.
- **Nav flow**: the route list's "+" now opens the trip generator
  (destination/filters/radius) instead of the old tap-to-draw/GPS-record
  screen, which didn't match how routes actually get planned anymore.
  `CreateRouteScreen.kt`'s code is left in place, just unwired from
  `AppNavHost.kt` -- revive it there if that flow is ever wanted again. The
  live map's button to reach the student-profile picker is now labeled
  "Student Profiles" (was "My routes", which didn't match where it actually
  led).
- **Generation UX**: a non-dismissible modal "Generating your route... this
  can take up to a minute" dialog replaces the old inline spinner (easy to
  miss once scrolled past, and generation can take up to 45s). The overall
  generation timeout is 45s (was 20s). A `dataWarning` now surfaces even on a
  *successful* generation if an active filter's own scoring data never
  loaded -- previously only shown when generation failed outright, so a
  filter silently having zero effect (nothing to score against) looked
  identical to it genuinely trying and failing to find a better route.
- **Branding**: app icon rebuilt from Corey-supplied SVGs (a two-tone gold/
  yellow road, a white/red nav dart, a rotated yellow L-plate badge, mint-
  green background) -- see `ic_launcher_foreground.xml`'s own doc comment for
  the coordinate-conversion approach. App renamed "Lesson Route Planner"
  (`app_name` in `strings.xml`, with an embedded `\n` so it renders as two
  lines under the launcher icon on launchers that honor it -- not guaranteed
  universally). **UI theme switched from black/white to a light grass-green
  Material3 palette** (`Color.kt`/`Theme.kt`) -- deliberately *not* applied to
  RouteMapView's own route-line/waypoint colors, which stay black/white, since
  a green route line would blend into a map's own grass/park-colored areas.
- **Recurring own mistake, now with a saved memory about it**: literal `--`
  inside XML comments (used as a prose em-dash out of habit) is invalid XML
  and broke `parseDebugLocalresources`/`compileDebugKotlin` multiple times
  across several files this session, including twice in the same file right
  after being "fixed". Treat this as a hard rule while *writing* any XML
  comment in this project, not a proofread-afterward step.

### Not yet tested / open as of this handoff

1. The Overpass endpoint + timeout fix (roundabouts/merging lanes/highways
   scoring) -- pushed, not yet re-tested on-device.
2. The radius-circle-only-fits-once fix -- pushed, not yet re-tested.
3. The duration-scoring mismatch Corey reported *after* the duration-weighting
   fix was already live (target 82min, generated 37min) -- needs a clean
   retest once (1) above is confirmed working, since that run also hit the
   Overpass timeout bug and it's unclear if the two are related.
4. Geoapify routing/geocoding's real on-device behavior beyond what's been
   directly confirmed so far (the missing-map-tiles bug is fixed and
   confirmed; avoid=highways' real-world effect and geocoding's house-number
   improvement were verified via direct API calls, not yet explicitly
   confirmed through the app's own UI on-device).

## What this is

An Android app (Kotlin, Jetpack Compose, MapLibre + Geoapify tiles, Room) for a
NSW driving instructor to record/save/follow custom routes with students, plus a
live map home screen showing hazards, high-traffic-volume roads, school zones,
speed cameras, and a "quiet roads" heuristic — all from free NSW open data. Zero
running cost, no billing account, no login/cloud sync (Geoapify above is the one
exception to "no billing account" — a free-tier account with no card required,
not literally zero-account). Package:
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
  the full name). Launcher icon (hand-built vector drawables, no PNGs) is
  **grass-green `#7CB342` background + white road glyph + a half-red/half-white
  compass-needle icon top-right** (no background circle behind it -- see next
  bullet). Was purple bg + yellow nav-arrow originally; Corey later asked for
  this green/compass-needle version instead, and separately asked for the
  in-app UI theme (next bullet) to become black/white -- **the icon palette and
  the UI theme are deliberately independent now, not tied together** the way
  the original purple/yellow choice tracked both.
  - The compass needle replaced an earlier waypoint-dot marker that used to sit
    in roughly the same spot and visually collided with it ("the white circle
    sitting behind the nav icon") -- removed rather than repositioned, since
    the road glyph alone reads as "roads" fine on its own.
- ✅ **"Overview" button**: centered in the bottom bar of both the route list and
  Student Profiles screens (between the Profiles/Routes toggle and the "+" FAB) —
  returns to the live map via `popBackStack(LIVE_MAP, inclusive = false)`, reusing
  the existing instance rather than restarting its continuous GPS tracking.
- ✅ **Black/white theme** (was purple `#71286F`/yellow `#F3E10E` originally,
  Corey later asked for stark black-and-white instead): see `Color.kt`/`Theme.kt`.
  `primary` is "whichever is the opposite extreme from the background" (black
  in light mode, white in dark mode), `secondary` is mid-gray (a literal
  white-on-white secondary, e.g. a filled FAB in light mode, would have no
  visible edge with no border). Dynamic (Material You) color stays **off by
  default** (unrelated to this swap, already off from the original purple/
  yellow work) — it silently overrides any custom palette with wallpaper-derived
  colors on Android 12+. Route line + waypoint dots in `RouteMapView.kt` were
  recolored to match (black line, white dots -- the waypoint layer's *stroke*
  color also had to switch from white to black, or a white-on-white dot would
  be invisible against light map tiles); Phase 2 overlay colors (hazards,
  traffic volume, quiet roads) were deliberately left alone since they carry
  semantic meaning, not branding.
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
  - **Fifth-round bug (found via a generated route that took the instructor
    "north to Sydney" instead of the intended inland-west loop when opened in
    Google Maps)**: `NavIntent.kt`'s `sampleWaypoints()` sampled evenly by
    *index*, not distance. An OSRM polyline isn't uniformly spaced -- far more
    vertices on curvy roads, far fewer on a long straight stretch -- so
    index-based sampling could leave an entire straight section with zero
    waypoints, handing Google Maps free rein to substitute a completely
    different path through that gap. Worse for loop/there-and-back routes,
    where the 8-waypoint budget has to cover both legs. Fixed: sample evenly
    by *cumulative distance* along the route instead. Shared by
    `RouteDetailScreen` too, so this also improves fidelity for saved routes,
    not just generated ones.
  - **Sixth round (UX request, not a bug)**: the "Generated: ... /
    Regenerate / Open in nav app / Save" block, added in the fourth round
    right after the map, was still inside the scrollable content column, so
    it scrolled away once the instructor scrolled down into
    Destination/Time/Filters. Moved it to be a fixed sibling between the map
    and the scrollable column (same fixed-region-plus-weighted-scroll pattern
    as the map itself) so it now stays visible no matter how far down the
    rest of the screen is scrolled.
  - **No filter is a hard routing constraint** -- all of them, Highways
    included, are proximity-scored best-of-N-candidates (N tuned down over
    time for speed, currently 3 -- see the eighth-round entry below).
    Highways->Avoid was
    originally OSRM's `exclude=motorway` (a real constraint), but OSRM's
    public demo server rejects `exclude` outright for every value (confirmed
    directly against the live API) -- removed entirely, see `OsrmApi.kt`'s
    doc comment.
  - New Overpass queries: `fetchRoundabouts` (solid — real OSM tags) and
    `fetchMergeLaneProxies`/`fetchMajorRoads` (the former is a known
    approximation — see its doc comment).
  - **Seventh round: real ANR-style freeze with Highways/Roundabouts/Merging
    lanes** (not just a slow/failed generation — the whole UI became
    unresponsive). Root cause: `pickBestRoute()`'s nested proximity-comparison
    loop (every scoring point x every route point) is genuine CPU work, but it
    ran directly inside `scope.launch { }` from `rememberCoroutineScope()`,
    whose default dispatcher is Main -- so it was blocking the UI thread
    outright. Fixed with `withContext(Dispatchers.Default) { pickBestRoute(...) }`.
    Also reduced the actual data volume driving that loop: `fetchMajorRoads`/
    `fetchMergeLaneProxies` return **every vertex of every matching road** from
    Overpass, which for a wide search area can be thousands of points --
    `sampleForScoring()` (new, in `GenerateRouteScreen.kt`) caps this to ~4
    representative points per way, since a 40m proximity check never needed
    the full vertex list. Address search results are now tappable directly
    (the whole `ListItem`, via `Modifier.clickable`) instead of needing a
    separate "Use this address" button below each.
  - **Eighth round: too slow (~30s), wanted under 10s.** Bearings run in
    parallel, but each bearing's own refinement rounds are inherently
    sequential (each depends on the previous round's OSRM response) -- that
    per-bearing round-trip chain, not the bearing count, is the dominant
    latency cost. Tuned down for speed at some cost to candidate diversity/
    duration precision: bearings 4→3 (`0.0, 120.0, 240.0`), max refinement
    rounds 3→2, duration tolerance 15%→25% (a looser tolerance means the
    *common* case converges in a single round instead of needing a second
    one, which matters more for wall-clock time than the round cap itself).
    Overall timeout 45s→20s and its UI message updated to match. **Not yet
    confirmed on-device whether this actually lands under 10s** -- if it's
    still too slow, the next lever is probably fewer bearings still (down to
    2) before touching anything else, since the round-trip chain length is
    already near its practical floor.
  - **Ninth round**: Corey confirmed the speed tuning worked, but flagged that
    Highways→Avoid still put him on a highway when suburban roads clearly
    could have covered the same distance. Root cause: OSRM always computes
    the *fastest* route by default, and scoring-after-the-fact can only pick
    the least-bad of whatever candidates OSRM already generated -- if every
    candidate already used a highway (likely, since OSRM defaults to it when
    available and the implied trip distance was long enough to want one),
    scoring has nothing better to pick from. Two new levers, both in
    `RouteGenerator.kt`: (1) `avoidHighways` now assumes a slower 25km/h
    local-roads speed (vs the usual 40km/h) for the initial detour-distance
    guess when Highways→Avoid is set, keeping the implied trip short enough
    that a highway's speed advantage isn't needed to cover it -- a longer
    implied distance all but guarantees OSRM reaches for one anyway. (2) New
    `fetchAlternatives` param on `generateCandidateRoutes`/`refineCandidate`:
    when Highways/Roundabouts/Merging lanes is set, each bearing also asks
    OSRM for alternate paths (`alternatives=true`) at its converged detour
    point, giving `pickBestRoute` more than one shape per bearing to choose
    from -- previously it could only rank bearings against each other, never
    find a different path for the *same* bearing. This adds one extra
    sequential OSRM call per bearing, so these three filters are a bit slower
    than the rest by design (traded off against the eighth round's speed
    work, deliberately, since result quality matters more for these three).
    Also added a **new "High traffic roads" filter**, reusing the existing
    TfNSW Traffic Volume Counts API data (`fetchHighVolumeRoads`) already used
    for the live map's overlay -- just the station points, not the
    Overpass-matched road geometry (only needed for on-map rendering, not
    proximity scoring). **None of this has been tested on-device yet.**
  - **Tenth round: confirmed timeout with Roundabouts→Avoid + Merging lanes→
    Prefer.** Root cause: the ninth round's `fetchAlternatives` call had no
    timeout of its own -- it only shared the overall 20s budget via
    `withTimeoutOrNull` in `GenerateRouteScreen.kt`. An `alternatives=true`
    request is real extra graph-search work for OSRM and can run noticeably
    slower than normal; without its own bound, one slow bearing's alternatives
    call could consume the *entire* 20s by itself and cancel every bearing's
    work, including ones that had already succeeded. Also: `OsrmApi.kt`'s
    client `connectTimeout`/`readTimeout` (10s each) meant a *single* normal
    OSRM call could already take up to 20s worst case -- equal to the entire
    generation budget on its own, before any alternates call. Fixed both:
    added `ALTERNATIVES_TIMEOUT_MS` (5s) around just the alternatives call in
    `refineCandidate` (falls back to the primary route alone if it's slow, not
    a total failure), and tightened `OsrmApi.kt`'s client to `callTimeout(6s)`
    (bounds the entire request regardless of which phase is slow) plus
    matching 6s connect/read. Worst case per bearing is now ~17s (2 rounds x
    6s + 1 alternates x 5s), fitting under the 20s overall ceiling with
    margin. **Also answered directly for Corey: generation does NOT use any
    Google Maps API** -- it's OSRM (routing) + Overpass (OSM scoring data) +
    TfNSW (hazards/traffic-volume/school-zones), all free/keyless (TfNSW needs
    a free API key) and unrelated to Google. Google Maps is only involved in
    the separate "Open in nav app" hand-off button. **None of this has been
    tested on-device yet either.**
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

**Current, as of this handoff (see "Latest session recap" at the top for full
detail) -- these supersede everything numbered below, which is all from a much
older session:**

A. Get Corey to actually try Settings → "TomTom nav spike" and report what
   happens (build succeeds now; never confirmed the spike runs/guides
   correctly on a device). This is the actual open question the whole TomTom
   detour exists to answer -- does reconstruction handle a backtracking loop
   well, or collapse it like Google Maps did.
B. Confirm the radius-as-hard-boundary rewrite + the two bug fixes on top of
   it (best-tracking, bearing rotation) actually produce a correct-duration
   route on a real retest -- was mid-retest when this session paused. Logcat
   tag `RouteGenerator` first if it's still wrong.
C. Once B is solid and A confirms TomTom's reconstruction is sound, wire
   TomTom's real guidance into the actual "Navigate" button (currently a
   plain custom live-tracking view, see recap) -- replacing the hardcoded
   spike test route with the real `generatedRoute` from `GenerateRouteScreen`.
D. Delete the spike screen/nav-destination/Settings button once C is done (or
   once the spike proves TomTom isn't viable and a different path is chosen).

---

**Older, from a previous session -- likely stale, re-verify before acting:**

0. **Immediate/pending, as of this handoff**: the tenth-round fix (commit
   `4c99efa` — `ALTERNATIVES_TIMEOUT_MS = 5_000L` bounding the OSRM
   alternates call in `RouteGenerator.kt`'s `refineCandidate()`, plus
   tightening `OsrmApi.kt`'s client to `callTimeout(6s)`/6s connect/read) has
   **not yet been tested on-device**. Corey explicitly chose "test current fix
   first" over cutting bearings further (to 2) or considering a paid routing
   API, when asked which lever to pull next if it's still too slow/unreliable
   after this. Whatever Corey reports back — still slow, still timing out on
   Roundabouts/Merging-lanes, or working — is the next thing to act on; don't
   re-guess at further speed/reliability tuning before hearing that result.
   Also untested since the same session: the black/white theme swap and the
   grass-green/compass-needle icon (commit `db20dad`) — worth a glance on-device
   too, purely visual, low risk of a real bug but never actually seen rendered.
1. **Test the trip generator again** — the first real-device pass found and fixed
   several bugs (map centering/visibility, mandatory start time, search UX, and
   a possible-hang on Generate — see the status entry above for detail). Still
   needs a full pass to confirm: whether generated routes land close to the
   target duration in practice, whether the 20s hard timeout is ever actually
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
