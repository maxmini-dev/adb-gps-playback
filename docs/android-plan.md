# Android app plan

The goal is a native Android version of the web app. Instead of shelling out
to `adb emu geo fix`, it mocks location **on the device**, so it works on real
phones and keeps playing while another app is in front.

## Decisions

| Question | Decision |
| --- | --- |
| Stack | Fully native: Kotlin + Jetpack Compose (Material 3) |
| Relationship to web app | Separate apps side by side (`web/`, `android/`) that share no code |
| Extras over the web app | Bearing / speed / accuracy on each fix · notification controls · GPS jitter |
| Distribution | Sideload / personal: debug-signed APK, no Play policy work |
| minSdk / targetSdk | 26 / 36 |
| Mock mechanism | `LocationManager` test providers for `gps` + `network` + `fused` (API 31+), plus optional `FusedLocationProviderClient.setMockMode` (default on) |
| Background playback | Foreground service, `foregroundServiceType="location"` |
| Tick rate | 250 ms (matches the web app's ~4 Hz throttle), advanced by real elapsed time |
| Jitter model | First-order Gauss–Markov random walk (τ = 10 s), σ configurable; reported accuracy ≈ 1.5σ |
| Persistence | Routes + player + settings as JSON in `filesDir/state.json`; raw GTFS in memory only (same as web) |
| GTFS parsing | Streaming zip + CSV. `stop_times.txt` is read in a second pass only if some trip needs the stop-based fallback shape |
| Logic placement | Pure-Kotlin `:core` module (no Android imports) so it's unit tested on a plain JVM |
| Mock detection (`Location.isMock`) | Out of scope: it can't be hidden without root/Xposed |

## How mocking works

1. The user selects the app under *Developer options → Select mock location
   app*. The manifest declares `ACCESS_MOCK_LOCATION`. Until the app is
   selected, the `addTestProvider` calls throw `SecurityException`. The Setup
   screen detects this through `AppOpsManager.OPSTR_MOCK_LOCATION`.
2. `MockLocationSink.start()` removes any stale test providers, then adds and
   enables `gps`, `network` and `fused`, and turns on Play Services mock mode.
3. On each tick the service calls `PlaybackEngine.advance()`, then `fixFor()`,
   then `MockLocationSink.push()`. The pushed `Location` carries lat/lon,
   bearing, speed, accuracy, `time` and `elapsedRealtimeNanos`.
4. While paused, the service keeps pushing the current position so the device
   doesn't fall back to real GPS.
5. On stop, on destroy or on failure, the test providers are removed and mock
   mode is turned off.

Permissions: `ACCESS_FINE_LOCATION` (the location FGS type requires it on API
34+), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`, and `POST_NOTIFICATIONS` (33+).

## Phases and status

| # | Phase | Status |
| --- | --- | --- |
| 0 | Split repo into `web/` + `android/`, Gradle scaffold, docs | ✅ Done |
| 1 | Prove mocking on a device: Setup screen with checklist + **Send test fix** | 🟡 Code written. **Needs a check on a real device and an emulator**, with and without the fused toggle, in Google Maps |
| 2 | `:core` engine (geo port, bearing, jitter) + `PlaybackService` + notification controls | 🟡 Code written. `:core` has 16 passing unit tests; the service hasn't been run yet |
| 3 | GTFS import (SAF picker, streaming parser) + staging + persistence | 🟡 Code written. Parser is unit tested; UI not yet run |
| 4 | Play screen: controls + MapLibre map with route, heading-arrow position marker, auto-pan | 🟡 Code written. The map controller type-checks against the real Android + MapLibre classes; not yet run |
| 5 | Editor screen: drag waypoint, tap near the line to insert, long-press to delete, reset | 🟡 Code written. Edit ops and hit testing are unit tested (24 `:core` tests total); gestures not yet tried on a device |
| 6 | Polish: battery-optimization exemption prompt, tick-rate setting in UI, a real launcher icon | ⬜ Not started |

The first commit was written in an environment without access to Google's
Maven repository, so **`:app` has not been compiled yet**. Also, the AGP,
AndroidX, Compose BOM and Play Services versions in `gradle/libs.versions.toml`
are best guesses. Open `android/` in Android Studio, let it bump versions if
sync complains, and fix any compile errors before moving on to phase 4.

## Map (phases 4–5)

- **Library:** MapLibre Native (`org.maplibre.gl:android-sdk`, from Maven
  Central) with an inline OpenStreetMap raster style. There's no API key, and
  an OkHttp interceptor sets an identifying User-Agent, as the OSM tile policy
  requires.
- **Layers:** route line, waypoint circles (edit mode only), and a position
  symbol rotated to the bearing (a dot when the bearing is unknown). All are
  GeoJSON sources fed by strings from `:core`'s `GeoJson`.
- **Editing:** a touch listener on the `MapView` takes over any gesture that
  starts within 24 dp of a waypoint. Dragging moves the point, long-pressing
  deletes it, and everything else falls through to normal map panning and
  zooming. A tap within 32 dp of the line inserts a point into the nearest
  segment.
- **Differences from the web editor:**
  - Deleting is a long-press instead of a right-click.
  - Inserting only happens for taps near the line; the web app inserts
    wherever you click. That avoids stray points from taps on a touchscreen.
    Worth porting the proximity check to the web app too, if wanted.

## Open decisions
- **Route exchange with the web app.** Not planned. If it's wanted later, a
  JSON export/import that matches the web store's `routes` shape would let you
  copy edited routes between the two apps.
