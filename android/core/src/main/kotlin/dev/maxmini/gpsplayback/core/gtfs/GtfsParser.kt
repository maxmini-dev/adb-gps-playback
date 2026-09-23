package dev.maxmini.gpsplayback.core.gtfs

import dev.maxmini.gpsplayback.core.model.GtfsData
import dev.maxmini.gpsplayback.core.model.GtfsRoute
import dev.maxmini.gpsplayback.core.model.GtfsShape
import dev.maxmini.gpsplayback.core.model.GtfsStop
import dev.maxmini.gpsplayback.core.model.GtfsTrip
import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.model.ScheduledStop
import dev.maxmini.gpsplayback.core.schedule.fillMissingTimes
import java.io.InputStream
import java.time.LocalDate
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

/**
 * Parses a GTFS zip into [GtfsData].
 *
 * [openZip] must return a fresh stream over the zip each time it's called. The
 * parser streams entries instead of loading the archive into memory. The
 * (often huge) stop_times.txt is read in a second pass, keeping only each
 * trip's first departure / last arrival (for schedule filtering) plus full stop
 * lists for trips that need a stop-based fallback shape. A staged trip's full
 * timetable is read on demand with [readTripStops].
 */
object GtfsParser {
    private const val SYNTH_PREFIX = "__trip_"
    private const val NO_TIME = Int.MIN_VALUE

    fun parse(
        openZip: () -> InputStream,
        feedName: String? = null,
        now: Long = System.currentTimeMillis(),
    ): GtfsData {
        val routes = ArrayList<GtfsRoute>()
        val trips = ArrayList<GtfsTrip>()
        val shapeBuckets = HashMap<String, MutableList<Pair<Int, LatLon>>>()
        val stopById = HashMap<String, GtfsStop>()
        val weekly = HashMap<String, ServiceCalendar.Weekly>()
        val exceptions = HashMap<String, HashMap<LocalDate, Boolean>>()
        var timezone: String? = null

        val pass1 = setOf(
            "agency.txt", "routes.txt", "trips.txt", "shapes.txt", "stops.txt", "calendar.txt", "calendar_dates.txt",
        )
        forEachEntry(openZip, pass1) { name, reader ->
            when (name) {
                "agency.txt" -> forEachCsvRecord(reader) { get ->
                    if (timezone == null) timezone = get("agency_timezone").ifEmpty { null }
                }
                "calendar.txt" -> forEachCsvRecord(reader) { get ->
                    val id = get("service_id")
                    val w = ServiceCalendar.weeklyFrom(get)
                    if (id.isNotEmpty() && w != null) weekly[id] = w
                }
                "calendar_dates.txt" -> forEachCsvRecord(reader) { get ->
                    val id = get("service_id")
                    val date = ServiceCalendar.parseDate(get("date"))
                    val type = get("exception_type")
                    if (id.isNotEmpty() && date != null && (type == "1" || type == "2")) {
                        exceptions.getOrPut(id) { HashMap() }[date] = type == "1"
                    }
                }
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

        // Pass 2: stop_times.txt — per-trip first departure / last arrival, plus
        // full stop lists only for trips that need a fallback shape.
        val span = HashMap<String, IntArray>() // [minSeq, depAtMin, maxSeq, arrAtMax]
        val stopTimesByTrip = HashMap<String, MutableList<Pair<Int, String>>>()
        forEachEntry(openZip, setOf("stop_times.txt")) { _, reader ->
            forEachCsvRecord(reader) { get ->
                val tripId = get("trip_id")
                if (tripId.isEmpty()) return@forEachCsvRecord
                val seq = get("stop_sequence").toIntOrNull() ?: 0
                val arr = parseGtfsTime(get("arrival_time"))
                val dep = parseGtfsTime(get("departure_time")) ?: arr
                val s = span[tripId]
                if (s == null) {
                    span[tripId] = intArrayOf(seq, dep ?: NO_TIME, seq, arr ?: dep ?: NO_TIME)
                } else {
                    if (seq < s[0]) { s[0] = seq; s[1] = dep ?: NO_TIME }
                    if (seq > s[2]) { s[2] = seq; s[3] = arr ?: dep ?: NO_TIME }
                }
                if (tripId in needFallback) {
                    stopTimesByTrip.getOrPut(tripId) { ArrayList() } += seq to get("stop_id")
                }
            }
        }

        val finalTrips = trips.map { trip ->
            val s = span[trip.tripId]
            var t = if (s == null) trip else trip.copy(
                firstDepartureSec = s[1].takeIf { it != NO_TIME },
                lastArrivalSec = s[3].takeIf { it != NO_TIME },
            )
            // Fallback: synthesize shapes from stops for trips that don't have one.
            // This is common — GTFS shapes.txt is optional, and many feeds omit it.
            if (t.tripId in needFallback) {
                val sts = stopTimesByTrip[t.tripId]
                if (sts != null) {
                    sts.sortBy { it.first }
                    val points = sts.mapNotNull { stopById[it.second]?.point }
                    if (points.size >= 2) {
                        val synthId = SYNTH_PREFIX + t.tripId
                        shapes[synthId] = GtfsShape(synthId, points)
                        t = t.copy(shapeId = synthId)
                    }
                }
            }
            t
        }

        return GtfsData(
            routes = routes,
            trips = finalTrips,
            shapes = shapes,
            loadedAt = now,
            feedName = feedName,
            stops = stopById,
            calendar = ServiceCalendar(weekly, exceptions),
            timezone = timezone,
        )
    }

    /**
     * Read one trip's timetable from stop_times.txt (another streaming pass).
     * Missing times at non-timepoint stops are interpolated by distance. Returns
     * an empty list if the trip has fewer than two known stops or its first /
     * last stop has no time.
     */
    fun readTripStops(openZip: () -> InputStream, tripId: String, stops: Map<String, GtfsStop>): List<ScheduledStop> {
        data class Row(val seq: Int, val stopId: String, val arr: Int?, val dep: Int?)
        val rows = ArrayList<Row>()
        forEachEntry(openZip, setOf("stop_times.txt")) { _, reader ->
            forEachCsvRecord(reader) { get ->
                if (get("trip_id") == tripId) {
                    val arr = parseGtfsTime(get("arrival_time"))
                    val dep = parseGtfsTime(get("departure_time"))
                    rows += Row(get("stop_sequence").toIntOrNull() ?: 0, get("stop_id"), arr ?: dep, dep ?: arr)
                }
            }
        }
        rows.sortBy { it.seq }
        val known = rows.mapNotNull { r -> stops[r.stopId]?.let { r to it } }
        if (known.size < 2) return emptyList()
        val filled = fillMissingTimes(
            points = known.map { it.second.point },
            arrivals = known.map { it.first.arr },
            departures = known.map { it.first.dep },
        ) ?: return emptyList()
        return known.mapIndexed { i, (_, stop) ->
            ScheduledStop(stop.stopId, stop.name, stop.point, filled.first[i], filled.second[i])
        }
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
