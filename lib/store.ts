"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";
import type {
  EditableRoute,
  GtfsData,
  LatLon,
  PlayerState,
} from "./types";

type State = {
  gtfs: GtfsData | null;
  routes: Record<string, EditableRoute>;
  player: PlayerState;
};

type Actions = {
  setGtfs: (data: GtfsData | null) => void;
  addRouteFromTrip: (tripId: string) => void;
  removeRoute: (id: string) => void;
  updateWaypoint: (id: string, index: number, point: LatLon) => void;
  insertWaypoint: (id: string, index: number, point: LatLon) => void;
  deleteWaypoint: (id: string, index: number) => void;
  resetRoute: (id: string) => void;
  setPlayer: (patch: Partial<PlayerState>) => void;
};

const initialPlayer: PlayerState = {
  routeId: null,
  playing: false,
  progressMeters: 0,
  baseSpeedMps: 15, // ~54 km/h — reasonable urban transit default
  speedMultiplier: 1,
  autoPan: true,
};

export const useStore = create<State & Actions>()(
  persist(
    (set, get) => ({
      gtfs: null,
      routes: {},
      player: initialPlayer,

      setGtfs: (data) => set({ gtfs: data }),

      addRouteFromTrip: (tripId) => {
        const gtfs = get().gtfs;
        if (!gtfs) return;
        const trip = gtfs.trips.find((t) => t.trip_id === tripId);
        if (!trip || !trip.shape_id) return;
        const shape = gtfs.shapes[trip.shape_id];
        if (!shape) return;
        const route = gtfs.routes.find((r) => r.route_id === trip.route_id);
        const label =
          [
            route?.route_short_name,
            trip.trip_headsign ?? route?.route_long_name,
          ]
            .filter(Boolean)
            .join(" — ") || tripId;
        const entry: EditableRoute = {
          id: tripId,
          routeId: trip.route_id,
          label,
          originalPoints: shape.points.map((p) => ({ ...p })),
          waypoints: shape.points.map((p) => ({ ...p })),
        };
        set((s) => ({ routes: { ...s.routes, [tripId]: entry } }));
      },

      removeRoute: (id) =>
        set((s) => {
          const next = { ...s.routes };
          delete next[id];
          const player =
            s.player.routeId === id
              ? { ...s.player, routeId: null, playing: false, progressMeters: 0 }
              : s.player;
          return { routes: next, player };
        }),

      updateWaypoint: (id, index, point) =>
        set((s) => {
          const r = s.routes[id];
          if (!r) return s;
          const waypoints = r.waypoints.slice();
          waypoints[index] = point;
          return { routes: { ...s.routes, [id]: { ...r, waypoints } } };
        }),

      insertWaypoint: (id, index, point) =>
        set((s) => {
          const r = s.routes[id];
          if (!r) return s;
          const waypoints = r.waypoints.slice();
          waypoints.splice(index, 0, point);
          return { routes: { ...s.routes, [id]: { ...r, waypoints } } };
        }),

      deleteWaypoint: (id, index) =>
        set((s) => {
          const r = s.routes[id];
          if (!r) return s;
          if (r.waypoints.length <= 2) return s;
          const waypoints = r.waypoints.filter((_, i) => i !== index);
          return { routes: { ...s.routes, [id]: { ...r, waypoints } } };
        }),

      resetRoute: (id) =>
        set((s) => {
          const r = s.routes[id];
          if (!r) return s;
          return {
            routes: {
              ...s.routes,
              [id]: { ...r, waypoints: r.originalPoints.map((p) => ({ ...p })) },
            },
          };
        }),

      setPlayer: (patch) => set((s) => ({ player: { ...s.player, ...patch } })),
    }),
    {
      name: "gps-playback",
      // Persist routes and player but not the (potentially large) raw GTFS.
      partialize: (s) => ({ routes: s.routes, player: s.player }),
    },
  ),
);
