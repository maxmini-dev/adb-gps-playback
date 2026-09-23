package dev.maxmini.gpsplayback.core.playback

import dev.maxmini.gpsplayback.core.geo.bearingAtDistance
import dev.maxmini.gpsplayback.core.geo.cumulativeDistances
import dev.maxmini.gpsplayback.core.geo.offsetMeters
import dev.maxmini.gpsplayback.core.geo.pointAtDistance
import dev.maxmini.gpsplayback.core.model.JitterSettings
import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.model.PlaybackMode
import dev.maxmini.gpsplayback.core.model.PlayerState
import dev.maxmini.gpsplayback.core.model.ScheduledStop
import dev.maxmini.gpsplayback.core.schedule.ScheduleTimeline
import dev.maxmini.gpsplayback.core.schedule.projectStopsOntoRoute
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

/** One mock fix: everything the Android sink needs to build a `Location`. */
data class Fix(
    val position: LatLon,
    /** Degrees clockwise from north, or null if the route has no direction. */
    val bearingDegrees: Double?,
    val speedMps: Double,
    val accuracyMeters: Double,
)

/**
 * Pure playback logic, with no Android or clock dependencies so it can be unit
 * tested. The service owns the clock: it calls [advance] once per tick with the
 * real elapsed time (and, in schedule mode, the current service-day second).
 *
 * Two modes (see [PlaybackMode]):
 * - fixed speed: move at base speed × multiplier, optionally dwelling
 *   `minDwellSec` at each stop (dwell time is also scaled by the multiplier);
 * - schedule: position is a pure function of (now − offset) on the trip's
 *   [ScheduleTimeline], including dwells.
 */
class PlaybackEngine(
    private val waypoints: List<LatLon>,
    stops: List<ScheduledStop> = emptyList(),
    private val random: Random = Random.Default,
) {
    private val cum = cumulativeDistances(waypoints)
    val totalMeters: Double = cum.last()

    /** Meters along the route of each stop (follows edited geometry). */
    val stopDistances: DoubleArray = projectStopsOntoRoute(waypoints, cum, stops.map { it.point })
    private val stopArrivals = stops.map { it.arrivalSec }
    private val stopDepartures = stops.map { it.departureSec }

    private var timelineDwell: Int? = null
    private var cachedTimeline: ScheduleTimeline? = null

    /** The timetable as motion over time, or null if the route has no usable schedule. */
    fun timeline(minDwellSec: Int): ScheduleTimeline? {
        if (stopDistances.size < 2) return null
        if (timelineDwell != minDwellSec) {
            cachedTimeline = ScheduleTimeline(stopDistances, stopArrivals, stopDepartures, minDwellSec)
            timelineDwell = minDwellSec
        }
        return cachedTimeline
    }

    // Speed reported with the next fix; null = the nominal fixed speed.
    private var speedOverride: Double? = null

    // Fixed-speed dwell tracking (transient, not persisted).
    private var dwellRemaining = 0.0
    private var dwelledStop = -1
    private var lastOut: Double? = null

    // Jitter is a random walk (first-order Gauss-Markov process) rather than
    // independent noise per fix: real GPS error drifts slowly instead of jumping.
    private var errNorth = 0.0
    private var errEast = 0.0

    /** True while the vehicle is stopped at a stop (fixed-speed dwell or scheduled dwell). */
    var dwelling: Boolean = false
        private set

    /**
     * Advance [state] by [dtSeconds]. In schedule mode [serviceNowSec] (seconds
     * after service-day midnight, see `serviceSecondsAt`) is required; without it
     * or without a schedule, the engine falls back to fixed speed.
     */
    fun advance(state: PlayerState, dtSeconds: Double, serviceNowSec: Double? = null): PlayerState {
        if (!state.playing) {
            speedOverride = 0.0
            dwelling = false
            return state
        }
        val tl = if (state.mode == PlaybackMode.SCHEDULE && serviceNowSec != null) timeline(state.minDwellSec) else null
        return if (tl != null) advanceSchedule(state, tl, serviceNowSec!!) else advanceFixed(state, dtSeconds)
    }

    private fun advanceSchedule(state: PlayerState, tl: ScheduleTimeline, serviceNowSec: Double): PlayerState {
        val s = serviceNowSec - state.scheduleOffsetSec
        val phase = tl.phaseAt(s)
        speedOverride = tl.speedAt(s)
        dwelling = phase is ScheduleTimeline.Phase.AtStop || phase is ScheduleTimeline.Phase.BeforeStart
        val progress = tl.distanceAt(s)
        lastOut = progress
        return if (phase is ScheduleTimeline.Phase.Finished) {
            state.copy(progressMeters = progress, playing = false)
        } else {
            state.copy(progressMeters = progress)
        }
    }

    private fun advanceFixed(state: PlayerState, dtSeconds: Double): PlayerState {
        val base = state.baseSpeedMps
        val useStops = state.stopAtStops && stopDistances.isNotEmpty() && state.minDwellSec > 0
        var p = state.progressMeters
        val last = lastOut
        if (last == null || abs(p - last) > 1e-6) {
            // First tick, or the user scrubbed: don't dwell at a stop we're already at/past.
            dwellRemaining = 0.0
            dwelledStop = stopDistances.indexOfLast { it <= p + STOP_EPS_M }
        }
        var tau = dtSeconds * state.speedMultiplier // scaled time budget for this tick
        while (tau > 1e-9) {
            if (dwellRemaining > 0) {
                val use = minOf(tau, dwellRemaining)
                dwellRemaining -= use
                tau -= use
                continue
            }
            if (base <= 0 || p >= totalMeters) break
            val nextStop = if (useStops) dwelledStop + 1 else stopDistances.size
            val target = if (nextStop < stopDistances.size) minOf(stopDistances[nextStop], totalMeters) else totalMeters
            val travel = base * tau
            if (p + travel >= target) {
                tau -= (target - p) / base
                p = target
                if (nextStop < stopDistances.size) {
                    dwelledStop = nextStop
                    if (target < totalMeters) dwellRemaining = state.minDwellSec.toDouble()
                } else {
                    break
                }
            } else {
                p += travel
                tau = 0.0
            }
        }
        dwelling = dwellRemaining > 0
        speedOverride = if (dwelling) 0.0 else null
        lastOut = p
        return if (p >= totalMeters) {
            dwelling = false
            state.copy(progressMeters = totalMeters, playing = false)
        } else {
            state.copy(progressMeters = p)
        }
    }

    /** The fix to send for [state]. [dtSeconds] drives how far the jitter walk moves. */
    fun fixFor(state: PlayerState, dtSeconds: Double): Fix {
        val truePos = pointAtDistance(waypoints, cum, state.progressMeters)
        val speed = if (state.playing) speedOverride ?: effectiveSpeed(state) else 0.0
        val bearing = bearingAtDistance(waypoints, cum, state.progressMeters)
        val jitter = state.jitter
        if (!jitter.enabled || jitter.sigmaMeters <= 0) {
            errNorth = 0.0
            errEast = 0.0
            return Fix(truePos, bearing, speed, accuracyMeters = BASE_ACCURACY_M)
        }
        stepJitter(jitter, dtSeconds)
        val pos = offsetMeters(truePos, errNorth, errEast)
        // Report an accuracy consistent with the injected error, as a real
        // receiver would: roughly the 68% radius, never better than the base.
        val errMag = sqrt(errNorth * errNorth + errEast * errEast)
        val accuracy = maxOf(BASE_ACCURACY_M, jitter.sigmaMeters * 1.5, errMag)
        return Fix(pos, bearing, speed, accuracy)
    }

    private fun stepJitter(jitter: JitterSettings, dtSeconds: Double) {
        val dt = abs(dtSeconds)
        val a = exp(-dt / JITTER_CORRELATION_S)
        val noise = jitter.sigmaMeters * sqrt(1 - a * a)
        errNorth = a * errNorth + noise * gaussian()
        errEast = a * errEast + noise * gaussian()
    }

    private fun gaussian(): Double {
        // Box-Muller.
        var u = 0.0
        while (u == 0.0) u = random.nextDouble()
        val v = random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * kotlin.math.cos(2 * Math.PI * v)
    }

    companion object {
        const val BASE_ACCURACY_M = 3.0
        const val JITTER_CORRELATION_S = 10.0
        private const val STOP_EPS_M = 0.5

        fun effectiveSpeed(state: PlayerState) = state.baseSpeedMps * state.speedMultiplier
    }
}
