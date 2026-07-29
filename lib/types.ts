export type LatLon = { lat: number; lon: number };

export type GtfsRoute = {
  route_id: string;
  agency_id?: string;
  route_short_name?: string;
  route_long_name?: string;
  route_type?: string;
};

export type GtfsTrip = {
  trip_id: string;
  route_id: string;
  service_id?: string;
  shape_id?: string;
  trip_headsign?: string;
};

// A shape is an ordered polyline built from shapes.txt rows.
export type GtfsShape = {
  shape_id: string;
  points: LatLon[];
};

export type GtfsData = {
  routes: GtfsRoute[];
  trips: GtfsTrip[];
  shapes: Record<string, GtfsShape>;
  loadedAt: number;
  feedName?: string;
};

// The editable, in-flight representation of a chosen trip's polyline.
export type EditableRoute = {
  id: string; // trip_id
  routeId: string;
  label: string;
  originalPoints: LatLon[];
  waypoints: LatLon[];
};

export type PlayerState = {
  routeId: string | null; // EditableRoute.id
  playing: boolean;
  // Distance along the polyline in meters.
  progressMeters: number;
  // Speed in meters per second (before speedMultiplier).
  baseSpeedMps: number;
  speedMultiplier: number;
  autoPan: boolean;
};
