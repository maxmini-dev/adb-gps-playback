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

/** An ordered polyline built from shapes.txt rows (or synthesized from stops). */
data class GtfsShape(val shapeId: String, val points: List<LatLon>)

/** Parsed feed. Held in memory only — never persisted (mirrors the web app). */
data class GtfsData(
    val routes: List<GtfsRoute>,
    val trips: List<GtfsTrip>,
    val shapes: Map<String, GtfsShape>,
    val loadedAt: Long,
    val feedName: String? = null,
)

/** The editable, in-flight representation of a chosen trip's polyline. */
@Serializable
data class EditableRoute(
    val id: String, // trip_id
    val routeId: String,
    val label: String,
    val originalPoints: List<LatLon>,
    val waypoints: List<LatLon>,
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
    /** Speed in meters per second (before speedMultiplier). */
    val baseSpeedMps: Double = 15.0, // ~54 km/h — reasonable urban transit default
    val speedMultiplier: Double = 1.0,
    val autoPan: Boolean = true,
    val jitter: JitterSettings = JitterSettings(),
)

fun GtfsData.labelFor(trip: GtfsTrip): String {
    val route = routes.find { it.routeId == trip.routeId }
    return listOfNotNull(route?.shortName, trip.headsign ?: route?.longName)
        .filter { it.isNotBlank() }
        .joinToString(" — ")
        .ifEmpty { trip.tripId }
}

/** Build an [EditableRoute] from a trip, or null if the trip has no usable shape. */
fun GtfsData.editableRouteFor(tripId: String): EditableRoute? {
    val trip = trips.find { it.tripId == tripId } ?: return null
    val shape = trip.shapeId?.let { shapes[it] } ?: return null
    return EditableRoute(
        id = tripId,
        routeId = trip.routeId,
        label = labelFor(trip),
        originalPoints = shape.points,
        waypoints = shape.points,
    )
}
