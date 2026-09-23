"use client";

import { useEffect, useMemo, useRef, useState } from "react";
import dynamic from "next/dynamic";
import Link from "next/link";
import { useStore } from "@/lib/store";
import {
  cumulativeDistances,
  pointAtDistance,
  polylineLengthMeters,
} from "@/lib/geo";
import type { LatLon } from "@/lib/types";

const PlayerMap = dynamic(() => import("../components/PlayerMap"), {
  ssr: false,
  loading: () => (
    <div className="flex items-center justify-center h-full text-sm text-[color:var(--muted)]">
      Loading map…
    </div>
  ),
});

async function sendFix(
  point: LatLon,
  serial: string | undefined,
): Promise<string | null> {
  try {
    const res = await fetch("/api/adb", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ lat: point.lat, lon: point.lon, serial }),
    });
    if (!res.ok) {
      const j = (await res.json().catch(() => ({}))) as {
        error?: string;
        stderr?: string;
      };
      return j.error || `HTTP ${res.status}`;
    }
    return null;
  } catch (e) {
    return e instanceof Error ? e.message : String(e);
  }
}

export default function PlayPage() {
  const routes = useStore((s) => s.routes);
  const player = useStore((s) => s.player);
  const setPlayer = useStore((s) => s.setPlayer);

  const routeIds = useMemo(() => Object.keys(routes), [routes]);
  const activeId = player.routeId ?? routeIds[0] ?? null;
  const route = activeId ? routes[activeId] : null;

  const cum = useMemo(
    () => (route ? cumulativeDistances(route.waypoints) : [0]),
    [route],
  );
  const totalMeters = useMemo(
    () => (route ? polylineLengthMeters(route.waypoints) : 0),
    [route],
  );

  const position: LatLon | null = route
    ? pointAtDistance(route.waypoints, cum, player.progressMeters)
    : null;

  const [serial, setSerial] = useState("");
  const [lastError, setLastError] = useState<string | null>(null);
  const [lastSentAt, setLastSentAt] = useState<number | null>(null);
  const rafRef = useRef<number | null>(null);
  const lastTickRef = useRef<number | null>(null);
  const lastSendRef = useRef<number>(0);
  const stateRef = useRef(player);
  stateRef.current = player;

  useEffect(() => {
    if (!player.playing || !route) return;
    lastTickRef.current = null;

    const tick = (t: number) => {
      const last = lastTickRef.current;
      lastTickRef.current = t;
      const dt = last == null ? 0 : (t - last) / 1000;

      const cur = stateRef.current;
      const speed = cur.baseSpeedMps * cur.speedMultiplier;
      let next = cur.progressMeters + speed * dt;
      if (next >= totalMeters) {
        next = totalMeters;
        setPlayer({ progressMeters: next, playing: false });
      } else {
        setPlayer({ progressMeters: next });
      }

      // Throttle adb sends to ~4/sec — the emulator geo channel doesn't need more.
      if (t - lastSendRef.current > 250) {
        lastSendRef.current = t;
        const p = pointAtDistance(route.waypoints, cum, next);
        sendFix(p, serial || undefined).then((err) => {
          setLastError(err);
          if (!err) setLastSentAt(Date.now());
        });
      }

      if (stateRef.current.playing && next < totalMeters) {
        rafRef.current = requestAnimationFrame(tick);
      }
    };
    rafRef.current = requestAnimationFrame(tick);
    return () => {
      if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [player.playing, route?.id]);

  if (!route) {
    return (
      <main className="flex-1 flex items-center justify-center p-8">
        <div className="card p-8 max-w-md text-center space-y-3">
          <div className="text-lg font-medium">No route selected</div>
          <p className="text-sm text-[color:var(--muted)]">
            Pick a staged route to start streaming GPS fixes.
          </p>
          <Link href="/edit" className="btn btn-primary">
            Choose a route →
          </Link>
        </div>
      </main>
    );
  }

  const effectiveSpeed = player.baseSpeedMps * player.speedMultiplier;
  const pct = totalMeters > 0 ? player.progressMeters / totalMeters : 0;

  return (
    <main className="flex-1 flex flex-col min-h-0">
      <div
        className="px-4 py-2.5 border-b flex flex-wrap items-center gap-3 shrink-0"
        style={{ borderColor: "var(--border)", background: "var(--surface)" }}
      >
        <select
          value={activeId ?? ""}
          onChange={(e) => {
            setPlayer({
              routeId: e.target.value,
              progressMeters: 0,
              playing: false,
            });
          }}
          className="input"
        >
          {routeIds.map((id) => (
            <option key={id} value={id}>
              {routes[id].label}
            </option>
          ))}
        </select>
        <span className="text-xs text-[color:var(--muted)] tabular-nums">
          {(totalMeters / 1000).toFixed(2)} km · {route.waypoints.length} pts
        </span>

        <div className="flex-1" />

        <label className="flex items-center gap-2 text-sm text-[color:var(--muted)]">
          <input
            type="checkbox"
            checked={player.autoPan}
            onChange={(e) => setPlayer({ autoPan: e.target.checked })}
            className="accent-[color:var(--accent)]"
          />
          Auto-pan
        </label>

        <input
          type="text"
          value={serial}
          onChange={(e) => setSerial(e.target.value)}
          placeholder="emulator-5554"
          className="input w-40"
        />

        <StatusBadge
          error={lastError}
          lastSentAt={lastSentAt}
          playing={player.playing}
        />
      </div>

      <div className="flex-1 min-h-0 relative">
        <PlayerMap
          waypoints={route.waypoints}
          position={position}
          autoPan={player.autoPan}
        />
      </div>

      <div
        className="border-t p-4 space-y-3 shrink-0"
        style={{ borderColor: "var(--border)", background: "var(--surface)" }}
      >
        <div className="flex items-center gap-3">
          <button
            onClick={() =>
              setPlayer({
                playing: !player.playing,
                progressMeters:
                  player.progressMeters >= totalMeters
                    ? 0
                    : player.progressMeters,
              })
            }
            className="btn btn-primary"
          >
            {player.playing ? <PauseIcon /> : <PlayIcon />}
            {player.playing ? "Pause" : "Play"}
          </button>
          <button
            onClick={() => setPlayer({ progressMeters: 0, playing: false })}
            className="btn"
          >
            <ResetIcon /> Reset
          </button>
          <div className="ml-2 text-sm text-[color:var(--muted)] tabular-nums">
            <span className="text-[color:var(--foreground)] font-medium">
              {(player.progressMeters / 1000).toFixed(2)}
            </span>{" "}
            / {(totalMeters / 1000).toFixed(2)} km ·{" "}
            <span className="text-[color:var(--foreground)] font-medium">
              {(pct * 100).toFixed(0)}%
            </span>
          </div>
          <div className="ml-auto text-sm text-[color:var(--muted)] tabular-nums">
            <span className="text-[color:var(--foreground)] font-medium">
              {effectiveSpeed.toFixed(1)}
            </span>{" "}
            m/s ·{" "}
            <span className="text-[color:var(--foreground)] font-medium">
              {(effectiveSpeed * 3.6).toFixed(0)}
            </span>{" "}
            km/h
          </div>
        </div>

        <input
          type="range"
          min={0}
          max={Math.max(totalMeters, 1)}
          step={1}
          value={Math.min(player.progressMeters, totalMeters)}
          onChange={(e) =>
            setPlayer({ progressMeters: Number(e.target.value) })
          }
          className="w-full"
        />

        <div className="flex flex-wrap items-center gap-4 text-sm">
          <label className="flex items-center gap-2">
            <span className="text-[color:var(--muted)]">Base speed</span>
            <input
              type="number"
              min={1}
              max={100}
              step={1}
              value={player.baseSpeedMps}
              onChange={(e) =>
                setPlayer({ baseSpeedMps: Number(e.target.value) || 1 })
              }
              className="input w-20"
            />
            <span className="text-xs text-[color:var(--muted)]">m/s</span>
          </label>
          <label className="flex items-center gap-2 flex-1 min-w-[240px]">
            <span className="text-[color:var(--muted)]">Multiplier</span>
            <span className="tabular-nums font-medium w-10 text-right">
              ×{player.speedMultiplier.toFixed(1)}
            </span>
            <input
              type="range"
              min={0.5}
              max={20}
              step={0.5}
              value={player.speedMultiplier}
              onChange={(e) =>
                setPlayer({ speedMultiplier: Number(e.target.value) })
              }
              className="flex-1"
            />
          </label>
        </div>
      </div>
    </main>
  );
}

function StatusBadge({
  error,
  lastSentAt,
  playing,
}: {
  error: string | null;
  lastSentAt: number | null;
  playing: boolean;
}) {
  if (error) {
    return (
      <span className="badge badge-danger" title={error}>
        <Dot color="var(--danger)" pulse={playing} /> adb: {truncate(error, 30)}
      </span>
    );
  }
  if (lastSentAt) {
    return (
      <span className="badge badge-success">
        <Dot color="var(--success)" pulse={playing} /> adb ok
      </span>
    );
  }
  return (
    <span className="badge">
      <Dot color="var(--muted-2)" /> adb idle
    </span>
  );
}

function Dot({ color, pulse }: { color: string; pulse?: boolean }) {
  return (
    <span
      className={`inline-block w-1.5 h-1.5 rounded-full ${pulse ? "animate-pulse" : ""}`}
      style={{ background: color }}
    />
  );
}

function truncate(s: string, n: number) {
  return s.length > n ? s.slice(0, n) + "…" : s;
}

function PlayIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
      <path d="M8 5v14l11-7z" />
    </svg>
  );
}

function PauseIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor">
      <path d="M6 5h4v14H6zM14 5h4v14h-4z" />
    </svg>
  );
}

function ResetIcon() {
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <polyline points="1 4 1 10 7 10" />
      <path d="M3.51 15a9 9 0 1 0 2.13-9.36L1 10" />
    </svg>
  );
}
