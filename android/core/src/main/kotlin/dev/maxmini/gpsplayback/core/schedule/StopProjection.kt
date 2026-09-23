package dev.maxmini.gpsplayback.core.schedule

import dev.maxmini.gpsplayback.core.geo.EARTH_RADIUS_M
import dev.maxmini.gpsplayback.core.model.LatLon
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Distance along the route polyline of each stop, in stop order. Distances are
 * non-decreasing: each stop is searched for only after the previous one, so
 * loops and out-and-back routes don't snap a stop to the wrong pass. Among the
 * remaining segments it takes the earliest one within [slackMeters] of the
 * closest, which prefers "the next time the route passes here".
 *
 * Recomputed whenever the route is edited, so stops follow edited geometry.
 */
fun projectStopsOntoRoute(
    points: List<LatLon>,
    cum: DoubleArray,
    stops: List<LatLon>,
    slackMeters: Double = 20.0,
): DoubleArray {
    val out = DoubleArray(stops.size)
    if (points.size < 2) return out
    var fromSeg = 0
    var fromDist = 0.0
    for ((k, stop) in stops.withIndex()) {
        val cosLat = cos(Math.toRadians(stop.lat))
        fun x(p: LatLon) = Math.toRadians(p.lon) * EARTH_RADIUS_M * cosLat
        fun y(p: LatLon) = Math.toRadians(p.lat) * EARTH_RADIUS_M
        val sx = x(stop)
        val sy = y(stop)

        val dists = DoubleArray(points.size - 1)
        val along = DoubleArray(points.size - 1)
        var best = Double.POSITIVE_INFINITY
        for (i in fromSeg until points.size - 1) {
            val ax = x(points[i]); val ay = y(points[i])
            val bx = x(points[i + 1]); val by = y(points[i + 1])
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            var t = if (len2 == 0.0) 0.0 else (((sx - ax) * dx + (sy - ay) * dy) / len2).coerceIn(0.0, 1.0)
            var d = cum[i] + t * (cum[i + 1] - cum[i])
            if (d < fromDist) { // Don't go backwards within the starting segment.
                d = fromDist
                t = if (cum[i + 1] > cum[i]) (d - cum[i]) / (cum[i + 1] - cum[i]) else 0.0
            }
            val px = ax + t * dx; val py = ay + t * dy
            dists[i] = sqrt((sx - px) * (sx - px) + (sy - py) * (sy - py))
            along[i] = d
            if (dists[i] < best) best = dists[i]
        }
        var chosen = fromSeg
        for (i in fromSeg until points.size - 1) {
            if (dists[i] <= best + slackMeters) { chosen = i; break }
        }
        out[k] = along[chosen]
        fromSeg = chosen
        fromDist = along[chosen]
    }
    return out
}
