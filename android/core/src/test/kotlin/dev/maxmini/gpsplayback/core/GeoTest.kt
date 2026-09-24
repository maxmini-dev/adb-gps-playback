package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.geo.bearingAtDistance
import dev.maxmini.gpsplayback.core.geo.bearingDegrees
import dev.maxmini.gpsplayback.core.geo.cumulativeDistances
import dev.maxmini.gpsplayback.core.geo.haversineMeters
import dev.maxmini.gpsplayback.core.geo.offsetMeters
import dev.maxmini.gpsplayback.core.geo.pointAtDistance
import dev.maxmini.gpsplayback.core.geo.polylineLengthMeters
import dev.maxmini.gpsplayback.core.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GeoTest {
    // ~111.2 km per degree of latitude.
    private val a = LatLon(0.0, 0.0)
    private val b = LatLon(0.01, 0.0)
    private val c = LatLon(0.01, 0.01)

    @Test fun haversineOneHundredthDegreeLatitude() {
        assertEquals(1111.95, haversineMeters(a, b), 0.1)
    }

    @Test fun cumulativeAndLength() {
        val cum = cumulativeDistances(listOf(a, b, c))
        assertEquals(0.0, cum[0], 0.0)
        assertEquals(1111.95, cum[1], 0.1)
        assertEquals(cum[2], polylineLengthMeters(listOf(a, b, c)), 1e-9)
        assertEquals(0.0, polylineLengthMeters(emptyList()), 0.0)
    }

    @Test fun pointAtDistanceInterpolatesAndClamps() {
        val pts = listOf(a, b, c)
        val cum = cumulativeDistances(pts)
        assertEquals(a, pointAtDistance(pts, cum, -5.0))
        assertEquals(c, pointAtDistance(pts, cum, 1e9))
        val mid = pointAtDistance(pts, cum, cum[1] / 2)
        assertEquals(0.005, mid.lat, 1e-9)
        assertEquals(0.0, mid.lon, 1e-9)
    }

    @Test fun bearings() {
        assertEquals(0.0, bearingDegrees(a, b), 1e-6)
        assertEquals(90.0, bearingDegrees(b, c), 0.01)
        assertEquals(180.0, bearingDegrees(b, a), 1e-6)
        assertEquals(270.0, bearingDegrees(c, b), 0.01)
    }

    @Test fun bearingAtDistanceSkipsDuplicateVertices() {
        val pts = listOf(a, a, b, b)
        val cum = cumulativeDistances(pts)
        assertEquals(0.0, bearingAtDistance(pts, cum, 0.0)!!, 1e-6)
        assertEquals(0.0, bearingAtDistance(pts, cum, cum.last())!!, 1e-6)
        assertNull(bearingAtDistance(listOf(a, a), cumulativeDistances(listOf(a, a)), 0.0))
    }

    @Test fun offsetMetersRoundTrips() {
        val p = LatLon(45.0, -122.0)
        val q = offsetMeters(p, northMeters = 30.0, eastMeters = 40.0)
        assertEquals(50.0, haversineMeters(p, q), 0.05)
    }
}
