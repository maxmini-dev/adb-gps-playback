# Project: adb-gps-playback

Replays GTFS transit routes as mock GPS fixes on Android. There are two
independent projects side by side, and they share no code:

| Dir | What | Agent rules |
| --- | --- | --- |
| `web/` | Next.js UI that pushes fixes into an **emulator** through `adb emu geo fix` | `web/AGENTS.md` |
| `android/` | Native Kotlin/Compose app that mocks location **on the device** via test providers | `android/AGENTS.md` |

Read the `AGENTS.md` for whichever project you're working in before changing it.
Both are single-user local dev tools: no auth and no backend beyond `web/app/api/adb`.

Cross-project rules:

- **Keep behavior aligned where it overlaps.** GTFS parsing (including the
  stop-based fallback when `shapes.txt` is missing), route editing and playback
  semantics should match. When you change one side's behavior, note in the PR
  whether the other side needs the same change.
- **Don't introduce code sharing** (e.g. Kotlin/JS or a shared TS package)
  without asking. They are separate on purpose.
- Planning notes live in `docs/`. The Android roadmap is `docs/android-plan.md`;
  update its status table when you finish a phase.
