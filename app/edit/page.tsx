"use client";

import { useMemo, useState } from "react";
import dynamic from "next/dynamic";
import Link from "next/link";
import { useStore } from "@/lib/store";
import { polylineLengthMeters } from "@/lib/geo";

const EditorMap = dynamic(() => import("../components/EditorMap"), {
  ssr: false,
  loading: () => (
    <div className="flex items-center justify-center h-full text-sm text-[color:var(--muted)]">
      Loading map…
    </div>
  ),
});

function Hint({
  dot,
  action,
  target,
  result,
}: {
  dot: string;
  action: string;
  target: string;
  result: string;
}) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span
        className="inline-block w-2 h-2 rounded-full shrink-0"
        style={{ background: dot }}
      />
      <span>
        <span className="font-semibold text-[color:var(--foreground)]">
          {action}
        </span>{" "}
        <span className="text-[color:var(--muted)]">{target}</span>{" "}
        <span className="text-[color:var(--muted)]">{result}</span>
      </span>
    </span>
  );
}

export default function EditPage() {
  const routes = useStore((s) => s.routes);
  const removeRoute = useStore((s) => s.removeRoute);
  const resetRoute = useStore((s) => s.resetRoute);
  const setPlayer = useStore((s) => s.setPlayer);

  const routeIds = useMemo(() => Object.keys(routes), [routes]);
  const [selected, setSelected] = useState<string | null>(
    routeIds[0] ?? null,
  );

  const current = selected ? routes[selected] : null;
  const lengthKm = current
    ? polylineLengthMeters(current.waypoints) / 1000
    : 0;

  if (routeIds.length === 0) {
    return (
      <main className="flex-1 flex items-center justify-center p-8">
        <div className="card p-8 max-w-md text-center space-y-3">
          <div className="text-lg font-medium">No staged routes yet</div>
          <p className="text-sm text-[color:var(--muted)]">
            Head back to step 1 to load a GTFS feed and stage a few trips.
          </p>
          <Link href="/load" className="btn btn-primary">
            Load GTFS →
          </Link>
        </div>
      </main>
    );
  }

  return (
    <main className="flex-1 flex overflow-hidden min-h-0">
      <aside
        className="w-72 shrink-0 overflow-auto p-3 space-y-2 border-r"
        style={{ borderColor: "var(--border)", background: "var(--surface)" }}
      >
        <div className="text-xs uppercase tracking-wide text-[color:var(--muted)] px-1 pb-1">
          Staged routes · {routeIds.length}
        </div>
        {routeIds.map((id) => {
          const r = routes[id];
          const active = id === selected;
          return (
            <div
              key={id}
              onClick={() => setSelected(id)}
              className="p-3 rounded-lg cursor-pointer transition-all border"
              style={{
                borderColor: active
                  ? "var(--accent)"
                  : "var(--border)",
                background: active
                  ? "var(--accent-soft)"
                  : "var(--surface)",
              }}
            >
              <div
                className="text-sm font-medium truncate"
                style={{
                  color: active ? "var(--accent)" : "var(--foreground)",
                }}
              >
                {r.label}
              </div>
              <div className="text-xs text-[color:var(--muted)] mt-0.5">
                {r.waypoints.length} points
              </div>
              {active && (
                <div className="flex gap-2 mt-3">
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      resetRoute(id);
                    }}
                    className="btn btn-sm"
                  >
                    Reset
                  </button>
                  <button
                    onClick={(e) => {
                      e.stopPropagation();
                      removeRoute(id);
                      setSelected(null);
                    }}
                    className="btn btn-sm btn-danger"
                  >
                    Remove
                  </button>
                </div>
              )}
            </div>
          );
        })}
      </aside>

      <section className="flex-1 flex flex-col min-w-0">
        {current ? (
          <>
            <div
              className="px-4 py-2.5 border-b flex items-center gap-3 shrink-0"
              style={{
                borderColor: "var(--border)",
                background: "var(--surface)",
              }}
            >
              <div>
                <div className="text-sm font-medium">{current.label}</div>
                <div className="text-xs text-[color:var(--muted)] tabular-nums">
                  {current.waypoints.length} points · {lengthKm.toFixed(2)} km
                </div>
              </div>
              <div className="flex-1" />
              <Link
                href="/play"
                onClick={() =>
                  setPlayer({
                    routeId: current.id,
                    progressMeters: 0,
                    playing: false,
                  })
                }
                className="btn btn-primary btn-sm"
              >
                Play this →
              </Link>
            </div>

            <div
              className="px-4 py-2 border-b flex flex-wrap items-center gap-x-5 gap-y-1.5 text-xs shrink-0"
              style={{
                borderColor: "var(--border)",
                background:
                  "color-mix(in oklab, var(--surface) 96%, var(--foreground) 4%)",
              }}
            >
              <span className="uppercase tracking-wide font-semibold text-[color:var(--muted)] text-[10px]">
                How to edit
              </span>
              <Hint
                dot="var(--accent)"
                action="Drag"
                target="a point"
                result="to move it"
              />
              <Hint
                dot="var(--accent)"
                action="Click"
                target="the line"
                result="to insert a new point"
              />
              <Hint
                dot="var(--danger)"
                action="Right-click"
                target="a point"
                result="to delete it"
              />
              <span className="text-[color:var(--muted-2)] hidden lg:inline">
                Tip: use <kbd className="kbd">Reset</kbd> in the sidebar to
                undo all edits for this route.
              </span>
            </div>

            <div className="flex-1 min-h-0">
              <EditorMap key={current.id} routeId={current.id} />
            </div>
          </>
        ) : (
          <div className="flex-1 flex items-center justify-center text-sm text-[color:var(--muted)]">
            Select a route from the sidebar
          </div>
        )}
      </section>
    </main>
  );
}
