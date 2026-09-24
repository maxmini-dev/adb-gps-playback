# adb-gps-playback

Replay GTFS transit routes as simulated GPS on Android. The repo holds two
independent apps:

| | [`web/`](web/README.md) | [`android/`](android/README.md) |
| --- | --- | --- |
| Runs on | Your computer (Next.js) | The Android device itself (Kotlin + Compose) |
| Target | Android **emulator** | Real device **or** emulator |
| Mechanism | `adb emu geo fix` | `LocationManager` test providers (+ Play Services mock mode) |
| Keeps playing in background | n/a (browser tab drives it) | Yes, foreground service with notification controls |
| Route editor | Leaflet: drag, click to insert, right-click to delete | MapLibre: drag, tap line to insert, long-press to delete |
| Stops | — | Waits a set time at each of the trip's stops (toggle) |
| Extras | — | Bearing/speed/accuracy, GPS jitter |

Both load a GTFS `.zip`, let you stage trips (building polylines from stops when
`shapes.txt` is missing), and play them back at a configurable speed.

- Web app: `cd web && npm install && npm run dev`. See [web/README.md](web/README.md).
- Android app: `cd android && ./gradlew :app:installDebug`. See [android/README.md](android/README.md).
- Android roadmap: [docs/android-plan.md](docs/android-plan.md).
