package dev.maxmini.gpsplayback.core.model

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
)

/** A stop on a staged trip, in visiting order. */
@Serializable
data class RouteStop(
    val stopId: String,
    val name: String? = null,
    val point: LatLon,
)

/** The editable, in-flight representation of a chosen trip's polyline. */
@Serializable
data class EditableRoute(
    val id: String, // trip_id
    val routeId: String,
    val label: String,
    val originalPoints: List<LatLon>,
    val waypoints: List<LatLon>,
    /** The trip's stops, used for dwelling; empty for routes staged before stops were read. */
    val stops: List<RouteStop> = emptyList(),
)

@Serializable
data class JitterSettings(
    val enabled: Boolean = false,
    /** Standard deviation of the horizontal position error, in meters. */
    val sigmaMeters: Double = 4.0,
)

@Serializable
data class PlayerState(
    val routeId: String? = null, // EditableRoute.id
    val playing: Boolean = false,
    /** Distance along the polyline in meters. */
    val progressMeters: Double = 0.0,
    /** Travel speed in meters per second. The UI shows and sets it in mph. */
    val speedMps: Double = 30 * MPS_PER_MPH, // reasonable urban transit default
    val autoPan: Boolean = true,
    val jitter: JitterSettings = JitterSettings(),
    /** Pause at each of the route's stops for [dwellSec]. */
    val stopAtStops: Boolean = true,
    /** Seconds stopped at each stop. */
    val dwellSec: Int = 20,
)

const val MPS_PER_MPH = 0.44704

fun GtfsData.labelFor(trip: GtfsTrip): String {
    val route = routes.find { it.routeId == trip.routeId }
    return listOfNotNull(route?.shortName, trip.headsign ?: route?.longName)
        .filter { it.isNotBlank() }
        .joinToString(" — ")
        .ifEmpty { trip.tripId }
}

/**
 * Build an [EditableRoute] from a trip, or null if the trip has no usable shape.
 * [stops] is the trip's stop list (see `GtfsParser.readTripStops`); it may be empty.
 */
fun GtfsData.editableRouteFor(tripId: String, stops: List<RouteStop> = emptyList()): EditableRoute? {
    val trip = trips.find { it.tripId == tripId } ?: return null
    val shape = trip.shapeId?.let { shapes[it] } ?: return null
    return EditableRoute(
        id = tripId,
        routeId = trip.routeId,
        label = labelFor(trip),
        originalPoints = shape.points,
        waypoints = shape.points,
        stops = stops,
    )
}
