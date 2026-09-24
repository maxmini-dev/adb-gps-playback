package dev.maxmini.gpsplayback.core.edit

import kotlin.math.sqrt

/**
 * Screen-space hit testing for the editor. Coordinates are in pixels, so
 * thresholds behave the same at every zoom level. The Android layer projects
 * waypoints to the screen and passes them here.
 */
data class ScreenPoint(val x: Double, val y: Double)

private fun dist(a: ScreenPoint, b: ScreenPoint): Double {
    val dx = a.x - b.x
    val dy = a.y - b.y
    return sqrt(dx * dx + dy * dy)
}

/** Distance from [p] to segment [a]-[b]. */
fun distanceToSegment(p: ScreenPoint, a: ScreenPoint, b: ScreenPoint): Double {
    val dx = b.x - a.x
    val dy = b.y - a.y
    val len2 = dx * dx + dy * dy
    if (len2 == 0.0) return dist(p, a)
    val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
    return dist(p, ScreenPoint(a.x + t * dx, a.y + t * dy))
}

/** Index of the vertex nearest [p] within [maxDistance], or null. Ties go to the later vertex (drawn on top). */
fun hitVertex(points: List<ScreenPoint>, p: ScreenPoint, maxDistance: Double): Int? {
    var best: Int? = null
    var bestD = maxDistance
    for (i in points.indices) {
        val d = dist(points[i], p)
        if (d <= bestD) {
            best = i
            bestD = d
        }
    }
    return best
}

/**
 * Where to insert a new waypoint for a tap at [p]: the index `i` of the nearest
 * segment's end vertex (so the point lands between i-1 and i), matching the web
 * editor. Returns null if there's no segment within [maxDistance].
 */
fun insertIndexFor(points: List<ScreenPoint>, p: ScreenPoint, maxDistance: Double = Double.POSITIVE_INFINITY): Int? {
    if (points.size < 2) return null
    var best: Int? = null
    var bestD = maxDistance
    for (i in 1 until points.size) {
        val d = distanceToSegment(p, points[i - 1], points[i])
        if (d < bestD) {
            best = i
            bestD = d
        }
    }
    return best
}
