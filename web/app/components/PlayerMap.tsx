"use client";

import { useEffect, useMemo, useRef } from "react";
import {
  MapContainer,
  Marker,
  Polyline,
  TileLayer,
  useMap,
} from "react-leaflet";
import "leaflet/dist/leaflet.css";
import { positionIcon } from "./leaflet-setup";
import type { LatLon } from "@/lib/types";

function FitBoundsOnce({ points }: { points: LatLon[] }) {
  const map = useMap();
  const done = useRef(false);
  useEffect(() => {
    if (done.current || points.length === 0) return;
    done.current = true;
    map.fitBounds(
      points.map((p) => [p.lat, p.lon] as [number, number]),
      { padding: [30, 30] },
    );
  }, [map, points]);
  return null;
}

function AutoPan({
  position,
  enabled,
}: {
  position: LatLon;
  enabled: boolean;
}) {
  const map = useMap();
  useEffect(() => {
    if (!enabled) return;
    map.panTo([position.lat, position.lon], { animate: true });
  }, [position.lat, position.lon, enabled, map]);
  return null;
}

export default function PlayerMap({
  waypoints,
  position,
  autoPan,
}: {
  waypoints: LatLon[];
  position: LatLon | null;
  autoPan: boolean;
}) {
  const positions = useMemo(
    () => waypoints.map((p) => [p.lat, p.lon] as [number, number]),
    [waypoints],
  );

  const center: [number, number] =
    waypoints.length > 0 ? [waypoints[0].lat, waypoints[0].lon] : [0, 0];

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
      {position && (
        <>
          <Marker position={[position.lat, position.lon]} icon={positionIcon} />
          <AutoPan position={position} enabled={autoPan} />
        </>
      )}
    </MapContainer>
  );
}
