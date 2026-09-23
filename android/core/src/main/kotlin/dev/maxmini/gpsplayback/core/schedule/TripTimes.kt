package dev.maxmini.gpsplayback.core.schedule

import dev.maxmini.gpsplayback.core.geo.haversineMeters
import dev.maxmini.gpsplayback.core.model.LatLon

/**
 * Fill arrival/departure times for non-timepoint stops (GTFS lets them be blank)
 * by interpolating linearly by straight-line distance between the surrounding
 * known times. Also makes times non-decreasing. Returns null if the first or
 * last stop has no time.
 */
fun fillMissingTimes(
    points: List<LatLon>,
    arrivals: List<Int?>,
    departures: List<Int?>,
): Pair<List<Int>, List<Int>>? {
    val n = points.size
    require(arrivals.size == n && departures.size == n)
    if (n == 0) return null
    val arr = arrivals.toMutableList()
    val dep = departures.toMutableList()
    for (i in 0 until n) {
        if (arr[i] == null) arr[i] = dep[i]
        if (dep[i] == null) dep[i] = arr[i]
    }
    if (dep[0] == null || arr[n - 1] == null) return null

    val cum = DoubleArray(n)
    for (i in 1 until n) cum[i] = cum[i - 1] + haversineMeters(points[i - 1], points[i])

    var prevKnown = 0
    for (i in 1 until n) {
        if (arr[i] == null) continue
        // Interpolate stops strictly between prevKnown and i.
        val t0 = dep[prevKnown]!!
        val t1 = arr[i]!!
        val span = cum[i] - cum[prevKnown]
        for (j in prevKnown + 1 until i) {
            val f = if (span > 0) (cum[j] - cum[prevKnown]) / span else (j - prevKnown).toDouble() / (i - prevKnown)
            val t = (t0 + (t1 - t0) * f).toInt()
            arr[j] = t
            dep[j] = t
        }
        prevKnown = i
    }

    // Enforce monotonic times (bad feeds occasionally go backwards).
    var last = Int.MIN_VALUE
    val outArr = ArrayList<Int>(n)
    val outDep = ArrayList<Int>(n)
    for (i in 0 until n) {
        val a = maxOf(arr[i]!!, last)
        val d = maxOf(dep[i]!!, a)
        outArr += a
        outDep += d
        last = d
    }
    return outArr to outDep
}
