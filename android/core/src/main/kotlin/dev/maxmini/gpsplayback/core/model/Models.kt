package dev.maxmini.gpsplayback.core.model

import dev.maxmini.gpsplayback.core.gtfs.ServiceCalendar
import dev.maxmini.gpsplayback.core.gtfs.formatGtfsTime
import kotlinx.serialization.Serializable

@Serializable
data class LatLon(val lat: Double, val lon: Double)

data class GtfsRoute(
    val routeId: String,
    val agencyId: String? = null,
    val shortName: String? = null,
    val longName: String? = null,
    val routeType: String? = null,
)

data class GtfsTrip(
    val tripId: String,
    val routeId: String,
    val serviceId: String? = null,
    val shapeId: String? = null,
    val headsign: String? = null,
    /** Scheduled departure from the first stop, seconds after service-day midnight (may exceed 24h). */
    val firstDepartureSec: Int? = null,
    /** Scheduled arrival at the last stop, same clock. */
    val lastArrivalSec: Int? = null,
)

data class GtfsStop(val stopId: String, val name: String?, val point: LatLon)

/** An ordered polyline built from shapes.txt rows (or synthesized from stops). */
data class GtfsShape(val shapeId: String, val points: List<LatLon>)

/** Parsed feed. Held in memory only — never persisted (mirrors the web app). */
data class GtfsData(
    val routes: List<GtfsRoute>,
    val trips: List<GtfsTrip>,
    val shapes: Map<String, GtfsShape>,
    val loadedAt: Long,
    val feedName: String? = null,
    val stops: Map<String, GtfsStop> = emptyMap(),
    val calendar: ServiceCalendar = ServiceCalendar.EMPTY,
    /** agency_timezone of the (first) agency, e.g. "America/Los_Angeles". */
    val timezone: String? = null,
)

/** A stop on a staged trip with its scheduled times (seconds after service-day midnight). */
@Serializable
data class ScheduledStop(
    val stopId: String,
    val name: String? = null,
    val point: LatLon,
    val arrivalSec: Int,
    val departureSec: Int,
)

/** The editable, in-flight representation of a chosen trip's polyline. */
@Serializable
data class EditableRoute(
    val id: String, // trip_id
    val routeId: String,
    val label: String,
    val originalPoints: List<LatLon>,
    val waypoints: List<LatLon>,
    /** The trip's stops and timetable; empty for routes staged before schedules existed. */
    val stops: List<ScheduledStop> = emptyList(),
    /** Timezone the schedule times are in (agency_timezone); null = device timezone. */
    val timezone: String? = null,
)

@Serializable
data class JitterSettings(
    val enabled: Boolean = false,
    /** Standard deviation of the horizontal position error, in meters. */
    val sigmaMeters: Double = 4.0,
)

@Serializable
enum class PlaybackMode {
    /** Move at baseSpeedMps × speedMultiplier, optionally dwelling at stops. */
    FIXED_SPEED,

    /** Follow the trip's timetable against the real time of day, shifted by scheduleOffsetSec. */
    SCHEDULE,
}

@Serializable
data class PlayerState(
    val routeId: String? = null, // EditableRoute.id
    val playing: Boolean = false,
    /** Distance along the polyline in meters. */
    val progressMeters: Double = 0.0,
    /** Speed in meters per second (before speedMultiplier). */
    val baseSpeedMps: Double = 15.0, // ~54 km/h — reasonable urban transit default
    val speedMultiplier: Double = 1.0,
    val autoPan: Boolean = true,
    val jitter: JitterSettings = JitterSettings(),
    val mode: PlaybackMode = PlaybackMode.FIXED_SPEED,
    /** Schedule mode: positive = running late, negative = early. */
    val scheduleOffsetSec: Int = 0,
    /** Minimum time stopped at each stop (both modes). */
    val minDwellSec: Int = 20,
    /** Fixed-speed mode: pause at each stop for minDwellSec. */
    val stopAtStops: Boolean = true,
    /**
     * Schedule mode: on the next Play, keep the vehicle where it is and derive
     * the offset from its position (set by Pause and by scrubbing).
     */
    val holdPosition: Boolean = false,
)

fun GtfsData.labelFor(trip: GtfsTrip): String {
    val route = routes.find { it.routeId == trip.routeId }
    val name = listOfNotNull(route?.shortName, trip.headsign ?: route?.longName)
        .filter { it.isNotBlank() }
        .joinToString(" — ")
        .ifEmpty { trip.tripId }
    return trip.firstDepartureSec?.let { "$name · ${formatGtfsTime(it)}" } ?: name
}

/**
 * Build an [EditableRoute] from a trip, or null if the trip has no usable shape.
 * [stops] is the trip's timetable (see `GtfsParser.readTripStops`); it may be empty.
 */
fun GtfsData.editableRouteFor(tripId: String, stops: List<ScheduledStop> = emptyList()): EditableRoute? {
    val trip = trips.find { it.tripId == tripId } ?: return null
    val shape = trip.shapeId?.let { shapes[it] } ?: return null
    return EditableRoute(
        id = tripId,
        routeId = trip.routeId,
        label = labelFor(trip),
        originalPoints = shape.points,
        waypoints = shape.points,
        stops = stops,
        timezone = timezone,
    )
}
