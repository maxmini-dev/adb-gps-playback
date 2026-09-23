package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.geo.haversineMeters
import dev.maxmini.gpsplayback.core.model.JitterSettings
import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.model.PlayerState
import dev.maxmini.gpsplayback.core.playback.PlaybackEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PlaybackEngineTest {
    private val route = listOf(LatLon(0.0, 0.0), LatLon(0.01, 0.0)) // ~1112 m due north

    @Test fun advancesBySpeedTimesMultiplier() {
        val engine = PlaybackEngine(route)
        val s = PlayerState(playing = true, baseSpeedMps = 10.0, speedMultiplier = 2.0)
        assertEquals(50.0, engine.advance(s, 2.5).progressMeters, 1e-9)
        assertEquals(s.copy(playing = false), engine.advance(s.copy(playing = false), 10.0))
    }

    @Test fun stopsAtEnd() {
        val engine = PlaybackEngine(route)
        val end = engine.advance(PlayerState(playing = true, progressMeters = 1100.0), 10.0)
        assertFalse(end.playing)
        assertEquals(engine.totalMeters, end.progressMeters, 1e-9)
    }

    @Test fun fixHasBearingSpeedAndAccuracy() {
        val engine = PlaybackEngine(route)
        val fix = engine.fixFor(PlayerState(playing = true, progressMeters = 556.0, baseSpeedMps = 12.0), 0.25)
        assertEquals(0.0, fix.bearingDegrees!!, 1e-6)
        assertEquals(12.0, fix.speedMps, 0.0)
        assertEquals(PlaybackEngine.BASE_ACCURACY_M, fix.accuracyMeters, 0.0)
        assertEquals(0.005, fix.position.lat, 1e-4)
        // Paused → speed 0 so apps don't think we're moving.
        assertEquals(0.0, engine.fixFor(PlayerState(progressMeters = 556.0), 0.25).speedMps, 0.0)
    }

    @Test fun jitterStaysNearTruthAndReportsAccuracy() {
        val engine = PlaybackEngine(route, random = Random(1234))
        val s = PlayerState(progressMeters = 556.0, jitter = JitterSettings(enabled = true, sigmaMeters = 5.0))
        val truth = PlaybackEngine(route).fixFor(s.copy(jitter = JitterSettings()), 0.25).position
        var sumSq = 0.0
        val n = 4000
        repeat(n) {
            val fix = engine.fixFor(s, 0.25)
            val err = haversineMeters(truth, fix.position)
            assertTrue("error $err too large", err < 5.0 * 6)
            assertTrue(fix.accuracyMeters >= 7.5)
            sumSq += err * err
        }
        // 2-D error with per-axis sigma 5 m → RMS ≈ 7.07 m (loose bound; walk is correlated).
        val rms = kotlin.math.sqrt(sumSq / n)
        assertTrue("rms $rms", rms in 3.0..11.0)
    }
}
