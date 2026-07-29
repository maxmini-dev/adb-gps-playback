import { NextResponse } from "next/server";
import { spawn } from "node:child_process";

export const runtime = "nodejs";

type Body = {
  lat: number;
  lon: number;
  serial?: string; // optional emulator serial for `adb -s <serial>`
};

function resolveAdb(): string {
  return process.env.ADB_PATH || "adb";
}

function runAdb(args: string[]): Promise<{ code: number; stdout: string; stderr: string }> {
  return new Promise((resolve, reject) => {
    const proc = spawn(resolveAdb(), args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    proc.stdout.on("data", (d) => (stdout += d.toString()));
    proc.stderr.on("data", (d) => (stderr += d.toString()));
    proc.on("error", reject);
    proc.on("close", (code) => resolve({ code: code ?? -1, stdout, stderr }));
  });
}

export async function POST(req: Request) {
  let body: Body;
  try {
    body = await req.json();
  } catch {
    return NextResponse.json({ error: "invalid json" }, { status: 400 });
  }
  const { lat, lon, serial } = body;
  if (typeof lat !== "number" || typeof lon !== "number") {
    return NextResponse.json({ error: "lat and lon required" }, { status: 400 });
  }

  // `adb emu geo fix` takes longitude first, then latitude.
  const args: string[] = [];
  if (serial) args.push("-s", serial);
  args.push("emu", "geo", "fix", String(lon), String(lat));

  try {
    const { code, stderr } = await runAdb(args);
    if (code !== 0) {
      return NextResponse.json(
        { error: `adb exited ${code}`, stderr: stderr.trim() },
        { status: 500 },
      );
    }
    return NextResponse.json({ ok: true });
  } catch (e) {
    return NextResponse.json(
      {
        error:
          e instanceof Error ? e.message : String(e),
        hint: "Set ADB_PATH env var to the full path of your `adb` binary if it's not on PATH.",
      },
      { status: 500 },
    );
  }
}

export async function GET() {
  // Lightweight health check — try `adb devices`.
  try {
    const { code, stdout, stderr } = await runAdb(["devices"]);
    return NextResponse.json({
      adb: resolveAdb(),
      code,
      stdout: stdout.trim(),
      stderr: stderr.trim(),
    });
  } catch (e) {
    return NextResponse.json(
      {
        adb: resolveAdb(),
        error: e instanceof Error ? e.message : String(e),
      },
      { status: 500 },
    );
  }
}
