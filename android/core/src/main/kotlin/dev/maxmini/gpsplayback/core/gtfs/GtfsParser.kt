package dev.maxmini.gpsplayback.core.gtfs

import dev.maxmini.gpsplayback.core.model.GtfsData
import dev.maxmini.gpsplayback.core.model.GtfsRoute
import dev.maxmini.gpsplayback.core.model.GtfsShape
import dev.maxmini.gpsplayback.core.model.GtfsStop
import dev.maxmini.gpsplayback.core.model.GtfsTrip
import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.model.RouteStop
import java.io.InputStream
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Parses a GTFS zip into [GtfsData].
 *
 * [openZip] must return a fresh stream over the zip each time it's called. The
 * parser streams entries instead of loading the archive into memory, and makes
 * a second pass over the zip only when some trips need a stop-based fallback
 * shape — so the (often huge) stop_times.txt is only read when necessary, and
 * then only rows for those trips are kept. A staged trip's stop list is read on
 * demand with [readTripStops].
 */
object GtfsParser {
    private const val SYNTH_PREFIX = "__trip_"

    fun parse(
        openZip: () -> InputStream,
        feedName: String? = null,
        now: Long = System.currentTimeMillis(),
    ): GtfsData {
        val routes = ArrayList<GtfsRoute>()
        val trips = ArrayList<GtfsTrip>()
        val shapeBuckets = HashMap<String, MutableList<Pair<Int, LatLon>>>()
        val stopById = HashMap<String, GtfsStop>()

        forEachEntry(openZip, setOf("routes.txt", "trips.txt", "shapes.txt", "stops.txt")) { name, reader ->
            when (name) {
                "routes.txt" -> forEachCsvRecord(reader) { get ->
                    routes += GtfsRoute(
                        routeId = get("route_id"),
                        agencyId = get("agency_id").ifEmpty { null },
                        shortName = get("route_short_name").ifEmpty { null },
                        longName = get("route_long_name").ifEmpty { null },
                        routeType = get("route_type").ifEmpty { null },
                    )
                }
                "trips.txt" -> forEachCsvRecord(reader) { get ->
                    trips += GtfsTrip(
                        tripId = get("trip_id"),
                        routeId = get("route_id"),
                        serviceId = get("service_id").ifEmpty { null },
                        shapeId = get("shape_id").ifEmpty { null },
                        headsign = get("trip_headsign").ifEmpty { null },
                    )
                }
                "shapes.txt" -> forEachCsvRecord(reader) { get ->
                    val id = get("shape_id")
                    val lat = get("shape_pt_lat").toDoubleOrNull()
                    val lon = get("shape_pt_lon").toDoubleOrNull()
                    if (id.isNotEmpty() && lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                        val seq = get("shape_pt_sequence").toIntOrNull() ?: 0
                        shapeBuckets.getOrPut(id) { ArrayList() } += seq to LatLon(lat, lon)
                    }
                }
                "stops.txt" -> forEachCsvRecord(reader) { get ->
                    val lat = get("stop_lat").toDoubleOrNull()
                    val lon = get("stop_lon").toDoubleOrNull()
                    if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                        val id = get("stop_id")
                        stopById[id] = GtfsStop(id, get("stop_name").ifEmpty { null }, LatLon(lat, lon))
                    }
                }
            }
        }

        val shapes = HashMap<String, GtfsShape>()
        for ((id, rows) in shapeBuckets) {
            if (rows.size < 2) continue
            rows.sortBy { it.first }
            shapes[id] = GtfsShape(id, rows.map { it.second })
        }

        // Fallback: synthesize shapes from stops for trips that don't have one.
        // This is common — GTFS shapes.txt is optional, and many feeds omit it.
        val needFallback = trips
            .filter { t -> t.shapeId == null || shapes[t.shapeId] == null }
            .mapTo(HashSet()) { it.tripId }

        val finalTrips = if (needFallback.isEmpty()) {
            trips
        } else {
            val stopTimesByTrip = HashMap<String, MutableList<Pair<Int, String>>>()
            forEachEntry(openZip, setOf("stop_times.txt")) { _, reader ->
                forEachCsvRecord(reader) { get ->
                    val tripId = get("trip_id")
                    if (tripId in needFallback) {
                        val seq = get("stop_sequence").toIntOrNull() ?: 0
                        stopTimesByTrip.getOrPut(tripId) { ArrayList() } += seq to get("stop_id")
                    }
                }
            }
            trips.map { trip ->
                if (trip.tripId !in needFallback) return@map trip
                val sts = stopTimesByTrip[trip.tripId] ?: return@map trip
                sts.sortBy { it.first }
                val points = sts.mapNotNull { stopById[it.second]?.point }
                if (points.size < 2) return@map trip
                val synthId = SYNTH_PREFIX + trip.tripId
                shapes[synthId] = GtfsShape(synthId, points)
                trip.copy(shapeId = synthId)
            }
        }

        return GtfsData(
            routes = routes,
            trips = finalTrips,
            shapes = shapes,
            loadedAt = now,
            feedName = feedName,
            stops = stopById,
        )
    }

    /**
     * Read one trip's stops, in visiting order, from stop_times.txt (another
     * streaming pass). Stops missing from stops.txt are skipped.
     */
    fun readTripStops(openZip: () -> InputStream, tripId: String, stops: Map<String, GtfsStop>): List<RouteStop> {
        val rows = ArrayList<Pair<Int, String>>()
        forEachEntry(openZip, setOf("stop_times.txt")) { _, reader ->
            forEachCsvRecord(reader) { get ->
                if (get("trip_id") == tripId) rows += (get("stop_sequence").toIntOrNull() ?: 0) to get("stop_id")
            }
        }
        rows.sortBy { it.first }
        return rows.mapNotNull { (_, id) -> stops[id]?.let { RouteStop(it.stopId, it.name, it.point) } }
    }

    /**
     * Call [block] for each zip entry whose file name is in [names]. Entries are
     * matched by base name so feeds zipped inside a top-level folder still work.
     */
    private inline fun forEachEntry(
        openZip: () -> InputStream,
        names: Set<String>,
        block: (name: String, reader: InputStreamReader) -> Unit,
    ) {
        ZipInputStream(openZip().buffered()).use { zip ->
            val seen = HashSet<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (!entry.isDirectory && name in names && seen.add(name)) {
                    // Don't close this reader: that would close the ZipInputStream.
                    block(name, InputStreamReader(zip, Charsets.UTF_8))
                }
                if (seen.size == names.size) break
            }
        }
    }
}
