# Android app (`android/`)

All commands and paths below are relative to `android/`.

Native Kotlin + Jetpack Compose app. It replays staged GTFS routes as mock
locations **on the device itself**: it registers test providers with
`LocationManager` (and optionally Play Services' fused client) rather than going
through adb. Sideload-only personal tool, so there is no Play Store policy work.

## Modules

- `:core`: pure Kotlin/JVM, **no Android imports allowed**. Holds the geo math,
  GTFS parsing, the models and `PlaybackEngine`. It exists so the logic can be
  unit tested on a plain JVM without the Android SDK. Put new logic here
  whenever it doesn't need Android APIs.
- `:app`: the Android layer.
  - `AppStore`: the Zustand-equivalent singleton (StateFlows). It persists
    routes, player and settings to `filesDir/state.json`. Raw GTFS stays in
    memory only.
  - `mock/MockLocationSink`: test-provider setup, pushing fixes, cleanup.
  - `playback/PlaybackService`: the foreground service (`type=location`) that
    owns the clock, the tick loop and the notification controls.
  - `ui/map/`: MapLibre. `RouteMapController` owns the `MapView` and edit
    gestures and has **no Compose imports**, so it can be type-checked on its
    own. `RouteMap` is the thin Compose wrapper around it.
  - `ui/`: Compose screens.

## Ground rules

- **Playback runs in `PlaybackService`, never in the UI.** Mocking has to keep
  going while another app is in front. The UI only sends play, pause and stop
  intents and observes `AppStore`.
- **Always clean up test providers** (`MockLocationSink.stop()`) on stop, on
  destroy and on failure. A leaked provider pins the device to the last fix.
- **Every `Location` needs `time`, `elapsedRealtimeNanos` and `accuracy`.**
  Without them the platform throws `IllegalArgumentException`.
- **Mock gps, network and fused together.** Otherwise real fixes interleave
  with the fake ones.
- **Advance by real elapsed time** (`SystemClock.elapsedRealtime` deltas), not
  by assuming the tick interval. The default tick is 250 ms, matching the web
  app's ~4 Hz throttle.
- **Stops come from the staged trip** (`GtfsParser.readTripStops`, read at
  stage time) and are projected onto the route by `projectStopsOntoRoute`
  every time the engine is built, so they follow edits. Dwell (`dwellSec`) is
  scaled by the speed multiplier, and there's no dwell at the terminus.
- **No schedule adherence for now.** Timetable-following, ahead/behind offsets
  and filtering trips by date/time were tried and removed as not worth the
  complexity (see `docs/android-plan.md`). Ask before bringing them back.
- **GTFS shapes are optional.** Keep the stop-based fallback in
  `GtfsParser` (it mirrors `web/lib/gtfs.ts`). Stream `stop_times.txt` and only
  read it when some trip needs the fallback, because it can be hundreds of MB.
- **The map renders what it's given.** Edit gestures emit `RouteEdit`s, and
  `AppStore.editRoute` applies them. The controller only draws a live preview
  while dragging. Layer data is GeoJSON built in `:core` (`GeoJson`), and hit
  testing is in `:core` too (`edit/HitTest.kt`), so both are unit tested.
- **MapView lifecycle must be forwarded** (start / resume / pause / stop / destroy).
  `RouteMap` does this. Don't create a `MapView` anywhere else.
- **OSM tiles need an identifying User-Agent** (`MapSetup.kt`). Keep it if you
  change the HTTP client.
- **Keep dependencies minimal.** No icon libraries or UI kits beyond Material 3,
  no navigation library, no DI framework. Icons are hand-written vector
  drawables in `res/drawable`.
- Tests: JUnit 4 in `core/src/test`. Add tests there for any logic you add to `:core`.

## Commands

- `./gradlew :core:test`: unit tests. Needs only a JDK, no Android SDK.
- `./gradlew :app:assembleDebug`: build the APK (needs the Android SDK; set `sdk.dir` in `local.properties`).
- `./gradlew :app:installDebug`: install on a connected device.
- `./gradlew :app:lint`: Android lint.

Versions live in `gradle/libs.versions.toml`.
