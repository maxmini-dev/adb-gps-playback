"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect } from "react";
import { useStore } from "@/lib/store";
import { THEMES, applyTheme, type ThemeId } from "@/lib/theme";

const steps = [
  { href: "/load", num: "1", label: "Load" },
  { href: "/edit", num: "2", label: "Edit" },
  { href: "/play", num: "3", label: "Play" },
];

export function AppNav() {
  const pathname = usePathname();
  const theme = useStore((s) => s.theme);
  const setTheme = useStore((s) => s.setTheme);
  useEffect(() => applyTheme(theme), [theme]);

  return (
    <header
      className="app-nav border-b flex items-center h-14 px-4 gap-4 shrink-0"
      style={{
        borderColor: "var(--nav-border)",
        background: "var(--nav-bg)",
        color: "var(--nav-fg)",
      }}
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
                background: active ? "var(--nav-active-bg)" : "transparent",
                color: active ? "var(--nav-active-fg)" : "var(--nav-muted)",
                fontWeight: active ? 600 : 500,
              }}
            >
              <span
                className="inline-flex items-center justify-center w-5 h-5 rounded-full text-[10px] font-semibold"
                style={{
                  background: active ? "var(--accent)" : "var(--nav-border)",
                  color: active ? "var(--accent-contrast)" : "var(--nav-muted)",
                }}
              >
                {s.num}
              </span>
              {s.label}
            </Link>
          );
        })}
      </nav>
      <label className="ml-auto flex items-center gap-2 text-xs text-[color:var(--nav-muted)]">
        Theme
        <select
          className="input py-1 text-xs"
          value={theme}
          onChange={(e) => setTheme(e.target.value as ThemeId)}
        >
          {THEMES.map((t) => (
            <option key={t.id} value={t.id}>
              {t.label}
            </option>
          ))}
        </select>
      </label>
    </header>
  );
}
