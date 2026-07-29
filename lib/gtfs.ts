import JSZip from "jszip";
import Papa from "papaparse";
import type { GtfsData, GtfsRoute, GtfsShape, GtfsTrip, LatLon } from "./types";

async function readCsv<T = Record<string, string>>(
  zip: JSZip,
  name: string,
): Promise<T[]> {
  const file = zip.file(name);
  if (!file) return [];
  const text = await file.async("string");
  const parsed = Papa.parse<T>(text, {
    header: true,
    skipEmptyLines: true,
    dynamicTyping: false,
  });
  return parsed.data.filter((r) => r && Object.keys(r).length > 0);
}

export async function parseGtfsZip(file: File): Promise<GtfsData> {
  const buf = await file.arrayBuffer();
  const zip = await JSZip.loadAsync(buf);

  const [routesRaw, tripsRaw, shapesRaw, stopsRaw, stopTimesRaw] =
    await Promise.all([
      readCsv<Record<string, string>>(zip, "routes.txt"),
      readCsv<Record<string, string>>(zip, "trips.txt"),
      readCsv<Record<string, string>>(zip, "shapes.txt"),
      readCsv<Record<string, string>>(zip, "stops.txt"),
      readCsv<Record<string, string>>(zip, "stop_times.txt"),
    ]);

  const routes: GtfsRoute[] = routesRaw.map((r) => ({
    route_id: r.route_id,
    agency_id: r.agency_id,
    route_short_name: r.route_short_name,
    route_long_name: r.route_long_name,
    route_type: r.route_type,
  }));

  const trips: GtfsTrip[] = tripsRaw.map((t) => ({
    trip_id: t.trip_id,
    route_id: t.route_id,
    service_id: t.service_id,
    shape_id: t.shape_id || undefined,
    trip_headsign: t.trip_headsign,
  }));

  // Group shape rows by shape_id and sort by shape_pt_sequence.
  const shapeBuckets = new Map<
    string,
    { seq: number; point: LatLon }[]
  >();
  for (const row of shapesRaw) {
    const id = row.shape_id;
    if (!id) continue;
    const lat = parseFloat(row.shape_pt_lat);
    const lon = parseFloat(row.shape_pt_lon);
    const seq = parseInt(row.shape_pt_sequence ?? "0", 10);
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;
    if (!shapeBuckets.has(id)) shapeBuckets.set(id, []);
    shapeBuckets.get(id)!.push({ seq, point: { lat, lon } });
  }
  const shapes: Record<string, GtfsShape> = {};
  for (const [shape_id, rows] of shapeBuckets) {
    rows.sort((a, b) => a.seq - b.seq);
    if (rows.length >= 2) {
      shapes[shape_id] = { shape_id, points: rows.map((r) => r.point) };
    }
  }

  // Fallback: synthesize shapes from stops for trips that don't have one.
  // This is common — GTFS shapes.txt is optional, and many feeds omit it.
  const stopById = new Map<string, LatLon>();
  for (const s of stopsRaw) {
    const lat = parseFloat(s.stop_lat);
    const lon = parseFloat(s.stop_lon);
    if (Number.isFinite(lat) && Number.isFinite(lon)) {
      stopById.set(s.stop_id, { lat, lon });
    }
  }

  const stopTimesByTrip = new Map<string, { seq: number; stop_id: string }[]>();
  for (const st of stopTimesRaw) {
    const trip_id = st.trip_id;
    if (!trip_id) continue;
    const seq = parseInt(st.stop_sequence ?? "0", 10);
    if (!stopTimesByTrip.has(trip_id)) stopTimesByTrip.set(trip_id, []);
    stopTimesByTrip.get(trip_id)!.push({ seq, stop_id: st.stop_id });
  }

  for (const trip of trips) {
    const hasShape = trip.shape_id && shapes[trip.shape_id];
    if (hasShape) continue;
    const sts = stopTimesByTrip.get(trip.trip_id);
    if (!sts || sts.length < 2) continue;
    sts.sort((a, b) => a.seq - b.seq);
    const points = sts
      .map((s) => stopById.get(s.stop_id))
      .filter((p): p is LatLon => !!p);
    if (points.length < 2) continue;
    const synthId = `__trip_${trip.trip_id}`;
    shapes[synthId] = { shape_id: synthId, points };
    trip.shape_id = synthId;
  }

  return {
    routes,
    trips,
    shapes,
    loadedAt: Date.now(),
    feedName: file.name,
  };
}
