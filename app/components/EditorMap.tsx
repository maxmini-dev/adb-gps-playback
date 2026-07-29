"use client";

import { useMemo } from "react";
import {
  MapContainer,
  Marker,
  Polyline,
  TileLayer,
  useMap,
  useMapEvents,
} from "react-leaflet";
import type { LeafletMouseEvent } from "leaflet";
import "leaflet/dist/leaflet.css";
import { waypointIcon } from "./leaflet-setup";
import { useStore } from "@/lib/store";
import type { LatLon } from "@/lib/types";

function FitBoundsOnce({ points }: { points: LatLon[] }) {
  const map = useMap();
  useMemo(() => {
    if (points.length === 0) return;
    const bounds = points.map((p) => [p.lat, p.lon] as [number, number]);
    map.fitBounds(bounds, { padding: [30, 30] });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return null;
}

// Distance from point p to segment ab, in squared lat/lon degrees.
// Cheap enough because segments are short; we only use it to pick "closest segment".
function distToSegmentSq(p: LatLon, a: LatLon, b: LatLon): number {
  const dx = b.lon - a.lon;
  const dy = b.lat - a.lat;
  const len2 = dx * dx + dy * dy;
  if (len2 === 0) {
    const ddx = p.lon - a.lon;
    const ddy = p.lat - a.lat;
    return ddx * ddx + ddy * ddy;
  }
  let t = ((p.lon - a.lon) * dx + (p.lat - a.lat) * dy) / len2;
  t = Math.max(0, Math.min(1, t));
  const px = a.lon + t * dx;
  const py = a.lat + t * dy;
  const ddx = p.lon - px;
  const ddy = p.lat - py;
  return ddx * ddx + ddy * ddy;
}

function PolylineClickInsert({ routeId }: { routeId: string }) {
  const insertWaypoint = useStore((s) => s.insertWaypoint);
  const waypoints = useStore((s) => s.routes[routeId]?.waypoints ?? []);

  useMapEvents({
    click(e: LeafletMouseEvent) {
      if (waypoints.length < 2) return;
      const p: LatLon = { lat: e.latlng.lat, lon: e.latlng.lng };
      let bestI = 1;
      let bestD = Infinity;
      for (let i = 1; i < waypoints.length; i++) {
        const d = distToSegmentSq(p, waypoints[i - 1], waypoints[i]);
        if (d < bestD) {
          bestD = d;
          bestI = i;
        }
      }
      insertWaypoint(routeId, bestI, p);
    },
  });
  return null;
}

export default function EditorMap({ routeId }: { routeId: string }) {
  const waypoints = useStore((s) => s.routes[routeId]?.waypoints ?? []);
  const updateWaypoint = useStore((s) => s.updateWaypoint);
  const deleteWaypoint = useStore((s) => s.deleteWaypoint);

  const positions = useMemo(
    () => waypoints.map((p) => [p.lat, p.lon] as [number, number]),
    [waypoints],
  );

  const center: [number, number] =
    waypoints.length > 0
      ? [waypoints[0].lat, waypoints[0].lon]
      : [0, 0];

  return (
    <MapContainer
      center={center}
      zoom={13}
      style={{ height: "100%", width: "100%" }}
    >
      <TileLayer
        attribution='&copy; <a href="https://osm.org/copyright">OpenStreetMap</a>'
        url="https://tile.openstreetmap.org/{z}/{x}/{y}.png"
      />
      <FitBoundsOnce points={waypoints} />
      <Polyline positions={positions} pathOptions={{ color: "#3b82f6", weight: 4 }} />
      <PolylineClickInsert routeId={routeId} />
      {waypoints.map((p, i) => (
        <Marker
          key={i}
          position={[p.lat, p.lon]}
          icon={waypointIcon}
          draggable
          eventHandlers={{
            dragend: (e) => {
              const ll = e.target.getLatLng();
              updateWaypoint(routeId, i, { lat: ll.lat, lon: ll.lng });
            },
            contextmenu: () => deleteWaypoint(routeId, i),
          }}
        />
      ))}
    </MapContainer>
  );
}
