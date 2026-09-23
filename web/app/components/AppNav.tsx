"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";

const steps = [
  { href: "/load", num: "1", label: "Load" },
  { href: "/edit", num: "2", label: "Edit" },
  { href: "/play", num: "3", label: "Play" },
];

export function AppNav() {
  const pathname = usePathname();
  return (
    <header
      className="border-b flex items-center h-14 px-4 gap-4 shrink-0"
      style={{ borderColor: "var(--border)", background: "var(--surface)" }}
    >
      <Link
        href="/"
        className="font-semibold tracking-tight flex items-center gap-2"
      >
        <span
          className="inline-block w-2 h-2 rounded-full"
          style={{ background: "var(--accent)" }}
        />
        GPS Playback
      </Link>
      <nav className="flex items-center gap-1 ml-4">
        {steps.map((s) => {
          const active = pathname === s.href;
          return (
            <Link
              key={s.href}
              href={s.href}
              className="px-3 py-1.5 rounded-md text-sm flex items-center gap-2 transition-colors"
              style={{
                background: active ? "var(--accent-soft)" : "transparent",
                color: active ? "var(--accent)" : "var(--muted)",
                fontWeight: active ? 600 : 500,
              }}
            >
              <span
                className="inline-flex items-center justify-center w-5 h-5 rounded-full text-[10px] font-semibold"
                style={{
                  background: active ? "var(--accent)" : "var(--border)",
                  color: active ? "white" : "var(--muted)",
                }}
              >
                {s.num}
              </span>
              {s.label}
            </Link>
          );
        })}
      </nav>
    </header>
  );
}
