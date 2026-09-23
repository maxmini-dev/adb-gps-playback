// Theme ids map to `:root[data-theme="…"]` blocks in app/globals.css.
// "default" sets no attribute and follows the OS light/dark preference.
export const THEMES = [
  { id: "default", label: "Default" },
  { id: "console", label: "Night Ops" },
  { id: "signage", label: "Wayfinding" },
] as const;

export type ThemeId = (typeof THEMES)[number]["id"];

export function applyTheme(theme: ThemeId) {
  const root = document.documentElement;
  if (theme === "default") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", theme);
}

// Runs in <head> before first paint so a saved theme doesn't flash the
// default palette. Reads the same localStorage entry the Zustand store
// persists to (key "gps-playback", shape { state: { theme } }).
export const themeInitScript = `(function(){try{var s=JSON.parse(localStorage.getItem("gps-playback")||"{}");var t=s&&s.state&&s.state.theme;if(t&&t!=="default")document.documentElement.setAttribute("data-theme",t)}catch(e){}})()`;
