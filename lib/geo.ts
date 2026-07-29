import type { LatLon } from "./types";

const EARTH_RADIUS_M = 6371000;

function toRad(deg: number) {
  return (deg * Math.PI) / 180;
}

export function haversineMeters(a: LatLon, b: LatLon): number {
  const dLat = toRad(b.lat - a.lat);
  const dLon = toRad(b.lon - a.lon);
  const lat1 = toRad(a.lat);
  const lat2 = toRad(b.lat);
  const h =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.sqrt(h));
}

export function polylineLengthMeters(points: LatLon[]): number {
  let total = 0;
  for (let i = 1; i < points.length; i++) {
    total += haversineMeters(points[i - 1], points[i]);
  }
  return total;
}

// Cumulative distance to each vertex, useful for scrubbing.
export function cumulativeDistances(points: LatLon[]): number[] {
  const out = [0];
  for (let i = 1; i < points.length; i++) {
    out.push(out[i - 1] + haversineMeters(points[i - 1], points[i]));
  }
  return out;
}

// Interpolate a point at a given distance along the polyline.
// Linear (not great-circle) interpolation between vertices — fine for short GTFS segments.
export function pointAtDistance(
  points: LatLon[],
  cum: number[],
  meters: number,
): LatLon {
  if (points.length === 0) return { lat: 0, lon: 0 };
  if (points.length === 1) return points[0];
  const total = cum[cum.length - 1];
  if (meters <= 0) return points[0];
  if (meters >= total) return points[points.length - 1];
  // Binary search for the segment containing `meters`.
  let lo = 0;
  let hi = cum.length - 1;
  while (lo < hi - 1) {
    const mid = (lo + hi) >> 1;
    if (cum[mid] <= meters) lo = mid;
    else hi = mid;
  }
  const segLen = cum[hi] - cum[lo];
  const t = segLen === 0 ? 0 : (meters - cum[lo]) / segLen;
  return {
    lat: points[lo].lat + (points[hi].lat - points[lo].lat) * t,
    lon: points[lo].lon + (points[hi].lon - points[lo].lon) * t,
  };
}
