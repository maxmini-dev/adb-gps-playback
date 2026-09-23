package dev.maxmini.gpsplayback.core.playback

import dev.maxmini.gpsplayback.core.geo.bearingAtDistance
import dev.maxmini.gpsplayback.core.geo.cumulativeDistances
import dev.maxmini.gpsplayback.core.geo.offsetMeters
import dev.maxmini.gpsplayback.core.geo.pointAtDistance
import dev.maxmini.gpsplayback.core.model.JitterSettings
import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.model.PlayerState
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
 * tested. The service owns the clock and calls [advance] once per tick with the
 * real elapsed time, so an irregular tick rate never changes playback speed.
 */
class PlaybackEngine(
    private val waypoints: List<LatLon>,
    private val random: Random = Random.Default,
) {
    private val cum = cumulativeDistances(waypoints)
    val totalMeters: Double = cum.last()

    // Jitter is a random walk (first-order Gauss-Markov process) rather than
    // independent noise per fix: real GPS error drifts slowly instead of jumping.
    private var errNorth = 0.0
    private var errEast = 0.0

    /** Advance [state] by [dtSeconds]. Stops playing when the end is reached. */
    fun advance(state: PlayerState, dtSeconds: Double): PlayerState {
        if (!state.playing) return state
        val next = state.progressMeters + effectiveSpeed(state) * dtSeconds
        return if (next >= totalMeters) {
            state.copy(progressMeters = totalMeters, playing = false)
        } else {
            state.copy(progressMeters = next)
        }
    }

    /** The fix to send for [state]. [dtSeconds] drives how far the jitter walk moves. */
    fun fixFor(state: PlayerState, dtSeconds: Double): Fix {
        val truePos = pointAtDistance(waypoints, cum, state.progressMeters)
        val speed = if (state.playing) effectiveSpeed(state) else 0.0
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

        fun effectiveSpeed(state: PlayerState) = state.baseSpeedMps * state.speedMultiplier
    }
}
