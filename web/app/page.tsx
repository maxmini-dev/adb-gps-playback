import Link from "next/link";

export default function Home() {
  return (
    <main className="flex-1 flex items-center justify-center p-8">
      <div className="max-w-2xl w-full space-y-8">
        <div>
          <h1 className="text-3xl font-semibold tracking-tight">
            GPS Playback
          </h1>
          <p className="text-[color:var(--muted)] mt-2">
            Load a GTFS feed, edit route polylines, and stream simulated GPS
            fixes to a running Android emulator via{" "}
            <code className="font-mono text-sm px-1 py-0.5 rounded bg-[color:var(--accent-soft)] text-[color:var(--accent)]">
              adb emu geo fix
            </code>
            .
          </p>
        </div>

        <ol className="space-y-3">
          <Step
            href="/load"
            step="1"
            title="Load GTFS"
            body="Drop a GTFS .zip and stage trips you want to replay."
          />
          <Step
            href="/edit"
            step="2"
            title="Edit routes"
            body="Drag waypoints, click a segment to insert, right-click to delete."
          />
          <Step
            href="/play"
            step="3"
            title="Play"
            body="Scrub, adjust speed, and stream fixes to your emulator."
          />
        </ol>

        <div className="card p-4 text-xs text-[color:var(--muted)] space-y-1">
          <div className="font-medium text-[color:var(--foreground)]">
            Setup
          </div>
          <div>
            Ensure <code className="font-mono">adb</code> is on your PATH, or
            set <code className="font-mono">ADB_PATH</code> to the full path
            (e.g.{" "}
            <code className="font-mono">
              ~/Library/Android/sdk/platform-tools/adb
            </code>
            ). Start your Android emulator, then check{" "}
            <code className="font-mono">GET /api/adb</code> to confirm the
            server can reach it.
          </div>
        </div>
      </div>
    </main>
  );
}

function Step({
  href,
  step,
  title,
  body,
}: {
  href: string;
  step: string;
  title: string;
  body: string;
}) {
  return (
    <li>
      <Link
        href={href}
        className="card flex gap-4 p-4 hover:border-[color:var(--border-strong)] hover:shadow-sm transition-all"
      >
        <div
          className="text-xl font-semibold w-8 h-8 shrink-0 flex items-center justify-center rounded-full"
          style={{
            background: "var(--accent-soft)",
            color: "var(--accent)",
          }}
        >
          {step}
        </div>
        <div>
          <div className="font-medium">{title}</div>
          <div className="text-sm text-[color:var(--muted)] mt-0.5">
            {body}
          </div>
        </div>
      </Link>
    </li>
  );
}
