package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.geo.cumulativeDistances
import dev.maxmini.gpsplayback.core.geo.projectStopsOntoRoute
import dev.maxmini.gpsplayback.core.gtfs.GtfsParser
import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.model.editableRouteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class StopsTest {
    @Test fun projectionIsMonotonicOnLoops() {
        // Out-and-back route: 0 → 1000 m north → back to start.
        val route = listOf(LatLon(0.0, 0.0), LatLon(0.009, 0.0), LatLon(0.0, 0.0001))
        val cum = cumulativeDistances(route)
        val stops = listOf(LatLon(0.0045, 0.0), LatLon(0.009, 0.0), LatLon(0.0045, 0.0))
        val d = projectStopsOntoRoute(route, cum, stops)
        assertEquals(500.0, d[0], 5.0)
        assertEquals(cum[1], d[1], 5.0)
        assertTrue("second pass, got ${d[2]}", d[2] > cum[1] + 400) // not snapped back to the first pass
    }

    private fun zip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name)); z.write(body.toByteArray()); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun readTripStopsInSequenceOrder() {
        val feed = zip(
            mapOf(
                "routes.txt" to "route_id,route_short_name\nR1,10\n",
                "trips.txt" to "route_id,trip_id,shape_id\nR1,T1,S1\nR1,T2,S1\n",
                "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\nS1,0,0,1\nS1,0.004,0,2\n",
                "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nA,First,0,0\nB,Middle,0.001,0\nC,Last,0.004,0\n",
                "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                    "T1,08:04:00,08:04:00,C,3\nT1,08:00:00,08:00:00,A,1\nT2,09:00:00,09:00:00,A,1\n" +
                    "T1,,,B,2\nT1,,,MISSING,4\n",
            ),
        )
        val data = GtfsParser.parse({ ByteArrayInputStream(feed) })
        val stops = GtfsParser.readTripStops({ ByteArrayInputStream(feed) }, "T1", data.stops)
        assertEquals(listOf("A", "B", "C"), stops.map { it.stopId })
        assertEquals("Middle", stops[1].name)
        assertEquals(stops, data.editableRouteFor("T1", stops)!!.stops)
    }
}

class StopJumpTest {
    private val d = doubleArrayOf(0.0, 300.0, 300.5, 900.0)

    @org.junit.Test fun nextAndPrevious() {
        val e = dev.maxmini.gpsplayback.core.playback.PlaybackEngine
        assertEquals(300.0, e.nextStopAfter(d, 0.0)!!, 0.0)
        assertEquals(900.0, e.nextStopAfter(d, 300.0)!!, 0.0) // skips a near-duplicate stop
        org.junit.Assert.assertNull(e.nextStopAfter(d, 900.0))
        assertEquals(300.5, e.previousStopBefore(d, 900.0)!!, 0.0)
        assertEquals(0.0, e.previousStopBefore(d, 300.0)!!, 0.0)
        org.junit.Assert.assertNull(e.previousStopBefore(d, 0.0))
    }
}
