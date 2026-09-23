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
- **GTFS shapes are optional.** Keep the stop-based fallback in
  `GtfsParser` (it mirrors `web/lib/gtfs.ts`). Stream `stop_times.txt` and only
  read it when some trip needs the fallback, because it can be hundreds of MB.
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
