package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.model.PlaybackMode
import dev.maxmini.gpsplayback.core.model.PlayerState
import dev.maxmini.gpsplayback.core.model.ScheduledStop
import dev.maxmini.gpsplayback.core.playback.PlaybackEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DwellTest {
    // ~1112 m due north, with a stop at the start, the middle (~556 m) and the end.
    private val route = listOf(LatLon(0.0, 0.0), LatLon(0.01, 0.0))
    private val stops = listOf(
        ScheduledStop("A", null, LatLon(0.0, 0.0), 1000, 1000),
        ScheduledStop("B", null, LatLon(0.005, 0.0), 1060, 1060),
        ScheduledStop("C", null, LatLon(0.01, 0.0), 1120, 1120),
    )

    private fun run(engine: PlaybackEngine, s0: PlayerState, seconds: Int): List<PlayerState> {
        var s = s0
        return (1..seconds).map { s = engine.advance(s, 1.0); s }
    }

    @Test fun fixedSpeedStopsAtIntermediateStop() {
        val engine = PlaybackEngine(route, stops)
        val mid = engine.stopDistances[1]
        val states = run(engine, PlayerState(playing = true, baseSpeedMps = 100.0, minDwellSec = 10), 25)
        // Reaches the middle stop after ~5.6 s, holds there for 10 s, then moves on.
        assertEquals(mid, states[6].progressMeters, 1e-6)
        assertEquals(mid, states[14].progressMeters, 1e-6)
        assertTrue(states[16].progressMeters > mid)
        assertFalse(states.last().playing) // reached the end, no dwell at the terminus
    }

    @Test fun fixedSpeedWithoutStopsDoesNotDwell() {
        val engine = PlaybackEngine(route, stops)
        val s = engine.advance(PlayerState(playing = true, baseSpeedMps = 100.0, stopAtStops = false), 7.0)
        assertEquals(700.0, s.progressMeters, 1e-6)
    }

    @Test fun dwellReportsZeroSpeed() {
        val engine = PlaybackEngine(route, stops)
        var s = PlayerState(playing = true, baseSpeedMps = 100.0, minDwellSec = 10)
        repeat(7) { s = engine.advance(s, 1.0) }
        assertTrue(engine.dwelling)
        assertEquals(0.0, engine.fixFor(s, 1.0).speedMps, 0.0)
    }

    @Test fun scheduleModeFollowsClockAndOffset() {
        val engine = PlaybackEngine(route, stops)
        val tl = engine.timeline(minDwellSec = 0)!!
        val s = PlayerState(playing = true, mode = PlaybackMode.SCHEDULE, minDwellSec = 0)
        // 30 s after departure → halfway to B.
        val onTime = engine.advance(s, 0.25, serviceNowSec = 1030.0)
        assertEquals(tl.distance(1) / 2, onTime.progressMeters, 1.0)
        // Same clock, 30 s late → still at the start.
        val late = engine.advance(s.copy(scheduleOffsetSec = 30), 0.25, serviceNowSec = 1030.0)
        assertEquals(0.0, late.progressMeters, 1e-6)
        // 30 s early → already at B.
        val early = engine.advance(s.copy(scheduleOffsetSec = -30), 0.25, serviceNowSec = 1030.0)
        assertEquals(tl.distance(1), early.progressMeters, 1.0)
        // Past the last arrival → finished.
        assertFalse(engine.advance(s, 0.25, serviceNowSec = 2000.0).playing)
    }

    @Test fun scheduleModeWithoutClockFallsBackToFixedSpeed() {
        val engine = PlaybackEngine(route, stops)
        val s = PlayerState(playing = true, mode = PlaybackMode.SCHEDULE, baseSpeedMps = 10.0, stopAtStops = false)
        assertEquals(20.0, engine.advance(s, 2.0).progressMeters, 1e-6)
    }
}
