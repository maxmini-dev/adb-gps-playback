package dev.maxmini.gpsplayback.core.schedule

/**
 * A trip's motion over schedule time: where the vehicle is (meters along the
 * route) at any schedule second, with a minimum dwell at every intermediate stop.
 *
 * Minimum dwell keeps the scheduled **departure** and arrives earlier instead
 * (never before leaving the previous stop), because departures are what
 * rider-facing apps compare against. Feeds often give arrival == departure.
 */
class ScheduleTimeline(
    /** Meters along the route of each stop (non-decreasing). */
    private val distances: DoubleArray,
    arrivals: List<Int>,
    departures: List<Int>,
    minDwellSec: Int,
) {
    private val n = distances.size
    private val arr = DoubleArray(n)
    private val dep = DoubleArray(n)

    init {
        require(n >= 2 && arrivals.size == n && departures.size == n)
        for (i in 0 until n) {
            dep[i] = departures[i].toDouble()
            arr[i] = arrivals[i].toDouble()
        }
        for (i in 1 until n) {
            val floor = dep[i - 1]
            if (i < n - 1) arr[i] = minOf(arr[i], dep[i] - minDwellSec)
            arr[i] = arr[i].coerceIn(floor, maxOf(floor, dep[i]))
            dep[i] = maxOf(dep[i], arr[i])
        }
    }

    val startSec: Double get() = dep[0]
    val endSec: Double get() = arr[n - 1]
    val stopCount: Int get() = n

    fun arrivalSec(i: Int) = arr[i]
    fun departureSec(i: Int) = dep[i]
    fun distance(i: Int) = distances[i]

    sealed interface Phase {
        data object BeforeStart : Phase
        data class AtStop(val index: Int) : Phase
        /** Travelling from stop [from] to stop from + 1. */
        data class Moving(val from: Int) : Phase
        data object Finished : Phase
    }

    fun phaseAt(s: Double): Phase {
        if (s < dep[0]) return Phase.BeforeStart
        if (s >= arr[n - 1]) return Phase.Finished
        for (i in 0 until n - 1) {
            if (s >= arr[i] && s < dep[i]) return Phase.AtStop(i)
            if (s >= dep[i] && s < arr[i + 1]) return Phase.Moving(i)
        }
        return Phase.Finished
    }

    fun distanceAt(s: Double): Double = when (val p = phaseAt(s)) {
        Phase.BeforeStart -> distances[0]
        Phase.Finished -> distances[n - 1]
        is Phase.AtStop -> distances[p.index]
        is Phase.Moving -> {
            val i = p.from
            val f = (s - dep[i]) / (arr[i + 1] - dep[i])
            distances[i] + (distances[i + 1] - distances[i]) * f
        }
    }

    fun speedAt(s: Double): Double = when (val p = phaseAt(s)) {
        is Phase.Moving -> {
            val i = p.from
            (distances[i + 1] - distances[i]) / maxOf(arr[i + 1] - dep[i], 1.0)
        }
        else -> 0.0
    }

    /**
     * Earliest schedule time at which the vehicle reaches [meters]. Null at or
     * before the first stop, where the vehicle is simply waiting to depart.
     */
    fun timeAtDistance(meters: Double): Double? {
        if (meters <= distances[0]) return null
        if (meters >= distances[n - 1]) return arr[n - 1]
        for (i in 0 until n - 1) {
            if (meters <= distances[i + 1]) {
                if (meters == distances[i + 1]) return arr[i + 1]
                val span = distances[i + 1] - distances[i]
                val f = if (span > 0) (meters - distances[i]) / span else 1.0
                return dep[i] + (arr[i + 1] - dep[i]) * f
            }
        }
        return arr[n - 1]
    }

    /** Index of the next stop the vehicle will arrive at, or null when finished. */
    fun nextStopAt(s: Double): Int? = when (val p = phaseAt(s)) {
        Phase.BeforeStart -> 0
        is Phase.AtStop -> p.index
        is Phase.Moving -> p.from + 1
        Phase.Finished -> null
    }
}
