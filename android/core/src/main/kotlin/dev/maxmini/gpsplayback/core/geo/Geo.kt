package dev.maxmini.gpsplayback.core.geo

import dev.maxmini.gpsplayback.core.model.LatLon
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

const val EARTH_RADIUS_M = 6_371_000.0

private fun toRad(deg: Double) = Math.toRadians(deg)

fun haversineMeters(a: LatLon, b: LatLon): Double {
    val dLat = toRad(b.lat - a.lat)
    val dLon = toRad(b.lon - a.lon)
    val lat1 = toRad(a.lat)
    val lat2 = toRad(b.lat)
    val h = sin(dLat / 2).let { it * it } +
        cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_M * asin(sqrt(h))
}

fun polylineLengthMeters(points: List<LatLon>): Double =
    cumulativeDistances(points).last()

/** Cumulative distance to each vertex, useful for scrubbing. Always non-empty. */
fun cumulativeDistances(points: List<LatLon>): DoubleArray {
    val out = DoubleArray(maxOf(points.size, 1))
    for (i in 1 until points.size) {
        out[i] = out[i - 1] + haversineMeters(points[i - 1], points[i])
    }
    return out
}

/** Initial great-circle bearing from [a] to [b], in degrees clockwise from north [0, 360). */
fun bearingDegrees(a: LatLon, b: LatLon): Double {
    val lat1 = toRad(a.lat)
    val lat2 = toRad(b.lat)
    val dLon = toRad(b.lon - a.lon)
    val y = sin(dLon) * cos(lat2)
    val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

/** Index `lo` of the segment [lo, lo+1] containing [meters]. Requires points.size >= 2. */
private fun segmentIndex(cum: DoubleArray, meters: Double): Int {
    var lo = 0
    var hi = cum.size - 1
    while (lo < hi - 1) {
        val mid = (lo + hi) ushr 1
        if (cum[mid] <= meters) lo = mid else hi = mid
    }
    return lo
}

/**
 * Interpolate a point at a given distance along the polyline.
 * Linear (not great-circle) interpolation between vertices — fine for short GTFS segments.
 */
fun pointAtDistance(points: List<LatLon>, cum: DoubleArray, meters: Double): LatLon {
    if (points.isEmpty()) return LatLon(0.0, 0.0)
    if (points.size == 1) return points[0]
    val total = cum.last()
    if (meters <= 0) return points.first()
    if (meters >= total) return points.last()
    val lo = segmentIndex(cum, meters)
    val hi = lo + 1
    val segLen = cum[hi] - cum[lo]
    val t = if (segLen == 0.0) 0.0 else (meters - cum[lo]) / segLen
    return LatLon(
        lat = points[lo].lat + (points[hi].lat - points[lo].lat) * t,
        lon = points[lo].lon + (points[hi].lon - points[lo].lon) * t,
    )
}

/**
 * Bearing of the segment the given distance falls in. Zero-length segments are
 * skipped so a duplicated vertex doesn't make the heading snap to north.
 * Returns null if the polyline has no non-degenerate segment.
 */
fun bearingAtDistance(points: List<LatLon>, cum: DoubleArray, meters: Double): Double? {
    if (points.size < 2) return null
    val clamped = meters.coerceIn(0.0, cum.last())
    val start = segmentIndex(cum, clamped)
    // Look forward first, then backward, for a segment with non-zero length.
    for (i in start until points.size - 1) {
        if (cum[i + 1] > cum[i]) return bearingDegrees(points[i], points[i + 1])
    }
    for (i in start - 1 downTo 0) {
        if (cum[i + 1] > cum[i]) return bearingDegrees(points[i], points[i + 1])
    }
    return null
}

/** Move [p] by the given north/east offsets in meters (flat-earth approximation). */
fun offsetMeters(p: LatLon, northMeters: Double, eastMeters: Double): LatLon {
    val dLat = Math.toDegrees(northMeters / EARTH_RADIUS_M)
    val dLon = Math.toDegrees(eastMeters / (EARTH_RADIUS_M * cos(toRad(p.lat))))
    return LatLon(p.lat + dLat, p.lon + dLon)
}
