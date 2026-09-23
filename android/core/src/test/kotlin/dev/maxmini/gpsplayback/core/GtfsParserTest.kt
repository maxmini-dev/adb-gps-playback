package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.gtfs.GtfsParser
import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.model.editableRouteFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GtfsParserTest {
    private fun zip(files: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, body) in files) {
                z.putNextEntry(ZipEntry(name))
                z.write(body.toByteArray())
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val routes = "route_id,route_short_name,route_long_name\nR1,10,Downtown\n"
    private val stops = "stop_id,stop_lat,stop_lon\nA,1.0,2.0\nB,1.1,2.1\nC,1.2,2.2\n"

    @Test fun usesShapesSortedBySequence() {
        var opens = 0
        val bytes = zip(
            mapOf(
                "routes.txt" to routes,
                "trips.txt" to "route_id,trip_id,shape_id,trip_headsign\nR1,T1,SH1,North\n",
                "shapes.txt" to "shape_id,shape_pt_lat,shape_pt_lon,shape_pt_sequence\n" +
                    "SH1,5,6,2\nSH1,3,4,1\nSH1,bad,6,3\n",
            ),
        )
        val data = GtfsParser.parse({ opens++; ByteArrayInputStream(bytes) }, now = 42)
        assertEquals(listOf(LatLon(3.0, 4.0), LatLon(5.0, 6.0)), data.shapes["SH1"]!!.points)
        assertEquals(2, opens) // pass 1 + the stop_times pass
        val route = data.editableRouteFor("T1")!!
        assertEquals("10 — North", route.label)
        assertEquals(42L, data.loadedAt)
    }

    @Test fun synthesizesShapeFromStopsWhenMissing() {
        val bytes = zip(
            mapOf(
                // Nested folder + stop_times before trips: order and layout must not matter.
                "feed/stop_times.txt" to "trip_id,stop_id,stop_sequence\n" +
                    "T1,C,3\nT1,A,1\nT1,B,2\nT2,A,1\n",
                "feed/routes.txt" to routes,
                "feed/trips.txt" to "route_id,trip_id,shape_id\nR1,T1,\nR1,T2,MISSING\n",
                "feed/stops.txt" to stops,
            ),
        )
        val data = GtfsParser.parse({ ByteArrayInputStream(bytes) })
        val t1 = data.trips.first { it.tripId == "T1" }
        assertEquals("__trip_T1", t1.shapeId)
        assertEquals(
            listOf(LatLon(1.0, 2.0), LatLon(1.1, 2.1), LatLon(1.2, 2.2)),
            data.shapes["__trip_T1"]!!.points,
        )
        // T2 has only one stop → no synthesized shape, keeps its dangling id.
        assertNull(data.editableRouteFor("T2"))
        assertEquals("10 — Downtown", data.editableRouteFor("T1")!!.label)
    }
}
