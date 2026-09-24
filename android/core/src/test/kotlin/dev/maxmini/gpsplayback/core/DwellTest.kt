package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.model.PlayerState
import dev.maxmini.gpsplayback.core.model.RouteStop
import dev.maxmini.gpsplayback.core.playback.PlaybackEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwellTest {
    // ~1112 m due north, with a stop at the start, the middle (~556 m) and the end.
    private val route = listOf(LatLon(0.0, 0.0), LatLon(0.01, 0.0))
    private val stops = listOf(
        RouteStop("A", null, LatLon(0.0, 0.0)),
        RouteStop("B", null, LatLon(0.005, 0.0)),
        RouteStop("C", null, LatLon(0.01, 0.0)),
    )

    private fun run(engine: PlaybackEngine, s0: PlayerState, seconds: Int): List<PlayerState> {
        var s = s0
        return (1..seconds).map { s = engine.advance(s, 1.0); s }
    }

    @Test fun fixedSpeedStopsAtIntermediateStop() {
        val engine = PlaybackEngine(route, stops)
        val mid = engine.stopDistances[1]
        val states = run(engine, PlayerState(playing = true, speedMps = 100.0, dwellSec = 10), 25)
        // Reaches the middle stop after ~5.6 s, holds there for 10 s, then moves on.
        assertEquals(mid, states[6].progressMeters, 1e-6)
        assertEquals(mid, states[14].progressMeters, 1e-6)
        assertTrue(states[16].progressMeters > mid)
        assertFalse(states.last().playing) // reached the end, no dwell at the terminus
    }

    @Test fun fixedSpeedWithoutStopsDoesNotDwell() {
        val engine = PlaybackEngine(route, stops)
        val s = engine.advance(PlayerState(playing = true, speedMps = 100.0, stopAtStops = false), 7.0)
        assertEquals(700.0, s.progressMeters, 1e-6)
    }

    @Test fun dwellReportsZeroSpeed() {
        val engine = PlaybackEngine(route, stops)
        var s = PlayerState(playing = true, speedMps = 100.0, dwellSec = 10)
        repeat(7) { s = engine.advance(s, 1.0) }
        assertTrue(engine.dwelling)
        assertEquals(0.0, engine.fixFor(s, 1.0).speedMps, 0.0)
    }
}
