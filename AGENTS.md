<!-- BEGIN:nextjs-agent-rules -->
# This is NOT the Next.js you know

This version has breaking changes — APIs, conventions, and file structure may all differ from your training data. Read the relevant guide in `node_modules/next/dist/docs/` before writing any code. Heed deprecation notices.
<!-- END:nextjs-agent-rules -->

# Project: adb-gps-playback

Web UI that replays GTFS routes as GPS fixes into a running Android emulator
via `adb emu geo fix`. Single-user local dev tool — no auth, no persistence
beyond `localStorage`, no multi-tenant concerns.

## Data flow

```
GTFS zip → lib/gtfs.ts (parse) → Zustand store → EditorMap / PlayerMap
                                              ↓
                              playback loop (rAF) → POST /api/adb → spawn adb
```

- `lib/store.ts` (Zustand) holds `gtfs`, `routes` (staged/editable), and `player`.
- Only `routes` and `player` are persisted to `localStorage` — raw GTFS stays in memory.
- `app/api/adb/route.ts` resolves adb via `ADB_PATH` env or PATH. It's the
  only server-side surface; everything else is client components.

## Ground rules

- **Leaflet must never SSR.** Import map components via `next/dynamic({ ssr: false })`.
  `L`/react-leaflet touch `window` at import time.
- **`adb emu geo fix` takes `<lon> <lat>`** in that order — the reverse of nearly
  every other GPS API. Do not "fix" this.
- **Keep dependencies minimal.** No shadcn, no icon libraries, no UI kits.
  Shared styles live in `app/globals.css` as plain `.btn` / `.input` / `.card`
  / `.badge` classes plus CSS custom-property design tokens.
- **No tests exist yet.** If you add logic to `lib/geo.ts` or `lib/gtfs.ts`,
  consider a lightweight test; do not scaffold a test framework without asking.
- **GTFS shapes are optional.** If `shapes.txt` is missing/empty or a trip has
  no `shape_id`, `parseGtfsZip` synthesizes a polyline from `stops.txt` +
  `stop_times.txt`. Preserve this fallback.
- **Playback throttling:** the rAF loop advances state every frame but only
  POSTs to `/api/adb` ~4×/sec. Don't remove the throttle — the emulator geo
  channel doesn't need more and it prevents backpressure.

## Commands

- `npm run dev` — dev server (Turbopack)
- `npm run build` — production build; use this to check TypeScript
- `npm run lint` — ESLint

## When adding a feature

1. If it touches map interaction, put the code in `app/components/EditorMap.tsx`
   or `PlayerMap.tsx`. Don't split further unless it grows past ~200 lines.
2. If it touches persisted state, extend the Zustand store rather than
   introducing a second state mechanism.
3. Update the README architecture diagram if you add a new module or
   data-flow edge.
