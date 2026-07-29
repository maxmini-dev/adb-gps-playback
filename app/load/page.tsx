"use client";

import { useState } from "react";
import Link from "next/link";
import { useStore } from "@/lib/store";
import { parseGtfsZip } from "@/lib/gtfs";

export default function LoadPage() {
  const gtfs = useStore((s) => s.gtfs);
  const setGtfs = useStore((s) => s.setGtfs);
  const addRouteFromTrip = useStore((s) => s.addRouteFromTrip);
  const stagedRoutes = useStore((s) => s.routes);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [routeFilter, setRouteFilter] = useState<string>("");
  const [dragOver, setDragOver] = useState(false);

  async function onFile(f: File) {
    setLoading(true);
    setError(null);
    try {
      const data = await parseGtfsZip(f);
      setGtfs(data);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  const filteredRoutes = gtfs
    ? gtfs.routes
        .filter((r) => {
          if (!routeFilter) return true;
          const q = routeFilter.toLowerCase();
          return (
            r.route_short_name?.toLowerCase().includes(q) ||
            r.route_long_name?.toLowerCase().includes(q) ||
            r.route_id.toLowerCase().includes(q)
          );
        })
        .slice(0, 200)
    : [];

  const stagedCount = Object.keys(stagedRoutes).length;

  return (
    <main className="flex-1 overflow-auto">
      <div className="max-w-4xl mx-auto p-6 space-y-6">
        <header>
          <h1 className="text-2xl font-semibold tracking-tight">Load GTFS</h1>
          <p className="text-sm text-[color:var(--muted)] mt-1">
            Drop a feed archive and stage the trips you want to replay.
          </p>
        </header>

        <label
          onDragOver={(e) => {
            e.preventDefault();
            setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={(e) => {
            e.preventDefault();
            setDragOver(false);
            const f = e.dataTransfer.files?.[0];
            if (f) onFile(f);
          }}
          className="block cursor-pointer rounded-xl p-8 text-center transition-colors"
          style={{
            border: `2px dashed ${dragOver ? "var(--accent)" : "var(--border-strong)"}`,
            background: dragOver ? "var(--accent-soft)" : "var(--surface)",
          }}
        >
          <input
            type="file"
            accept=".zip"
            className="hidden"
            onChange={(e) => {
              const f = e.target.files?.[0];
              if (f) onFile(f);
            }}
          />
          <div className="flex flex-col items-center gap-2">
            <div
              className="w-10 h-10 rounded-full flex items-center justify-center"
              style={{
                background: "var(--accent-soft)",
                color: "var(--accent)",
              }}
            >
              <svg
                width="20"
                height="20"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="17 8 12 3 7 8" />
                <line x1="12" y1="3" x2="12" y2="15" />
              </svg>
            </div>
            <div className="font-medium">Drop or click to select a GTFS .zip</div>
            <div className="text-xs text-[color:var(--muted)]">
              Files stay in your browser — nothing is uploaded.
            </div>
            {loading && (
              <div className="text-sm text-[color:var(--accent)] mt-1">
                Parsing…
              </div>
            )}
            {error && (
              <div className="text-sm text-[color:var(--danger)] mt-1">
                {error}
              </div>
            )}
          </div>
        </label>

        {gtfs && (
          <section className="space-y-4">
            <div className="card p-4 flex flex-wrap items-center gap-3 text-sm">
              <span className="badge badge-accent">
                {gtfs.feedName ?? "Feed"}
              </span>
              <Stat label="Routes" value={gtfs.routes.length} />
              <Stat label="Trips" value={gtfs.trips.length} />
              <Stat label="Shapes" value={Object.keys(gtfs.shapes).length} />
              {stagedCount > 0 && (
                <>
                  <div className="flex-1" />
                  <span className="text-[color:var(--muted)]">
                    {stagedCount} staged
                  </span>
                  <Link href="/edit" className="btn btn-primary btn-sm">
                    Continue to edit →
                  </Link>
                </>
              )}
            </div>

            <input
              type="text"
              placeholder="Filter routes by name or ID…"
              value={routeFilter}
              onChange={(e) => setRouteFilter(e.target.value)}
              className="input w-full"
            />

            <div className="card divide-y" style={{ borderColor: "var(--border)" }}>
              {filteredRoutes.map((r) => {
                const trips = gtfs.trips.filter(
                  (t) => t.route_id === r.route_id && t.shape_id,
                );
                return (
                  <details key={r.route_id} className="group p-3">
                    <summary className="flex items-center gap-3 cursor-pointer list-none">
                      <svg
                        width="12"
                        height="12"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        className="transition-transform group-open:rotate-90 text-[color:var(--muted)]"
                      >
                        <polyline points="9 18 15 12 9 6" />
                      </svg>
                      <span className="font-mono text-xs px-1.5 py-0.5 rounded bg-[color:var(--accent-soft)] text-[color:var(--accent)]">
                        {r.route_short_name ?? r.route_id}
                      </span>
                      <span className="truncate">
                        {r.route_long_name ?? (
                          <span className="text-[color:var(--muted)]">
                            (no long name)
                          </span>
                        )}
                      </span>
                      <span className="badge ml-auto">
                        {trips.length} trip{trips.length === 1 ? "" : "s"}
                      </span>
                    </summary>
                    <ul className="mt-3 space-y-1 max-h-72 overflow-auto pr-1">
                      {trips.slice(0, 100).map((t) => {
                        const staged = !!stagedRoutes[t.trip_id];
                        return (
                          <li
                            key={t.trip_id}
                            className="flex items-center gap-2 text-sm py-1 px-2 rounded hover:bg-[color:var(--accent-soft)]/40"
                          >
                            <span className="truncate flex-1">
                              {t.trip_headsign ?? (
                                <span className="text-[color:var(--muted)]">
                                  {t.trip_id}
                                </span>
                              )}
                              <span className="text-xs text-[color:var(--muted-2)] ml-2 font-mono">
                                {t.trip_id}
                              </span>
                            </span>
                            <button
                              disabled={staged}
                              onClick={() => addRouteFromTrip(t.trip_id)}
                              className={
                                staged ? "btn btn-sm" : "btn btn-primary btn-sm"
                              }
                            >
                              {staged ? "✓ Staged" : "Stage"}
                            </button>
                          </li>
                        );
                      })}
                    </ul>
                  </details>
                );
              })}
            </div>
          </section>
        )}
      </div>
    </main>
  );
}

function Stat({ label, value }: { label: string; value: number | string }) {
  return (
    <div className="flex items-baseline gap-1.5">
      <span className="font-semibold tabular-nums">{value}</span>
      <span className="text-xs text-[color:var(--muted)]">{label}</span>
    </div>
  );
}
