package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.geo.cumulativeDistances
import dev.maxmini.gpsplayback.core.gtfs.GtfsParser
import dev.maxmini.gpsplayback.core.gtfs.ServiceCalendar
import dev.maxmini.gpsplayback.core.gtfs.formatGtfsTime
import dev.maxmini.gpsplayback.core.gtfs.parseGtfsTime
import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.schedule.ScheduleTimeline
import dev.maxmini.gpsplayback.core.schedule.ScheduleTimeline.Phase
import dev.maxmini.gpsplayback.core.schedule.ScheduleWindow
import dev.maxmini.gpsplayback.core.schedule.fillMissingTimes
import dev.maxmini.gpsplayback.core.schedule.offsetToHoldPosition
import dev.maxmini.gpsplayback.core.schedule.projectStopsOntoRoute
import dev.maxmini.gpsplayback.core.schedule.serviceSecondsAt
import dev.maxmini.gpsplayback.core.schedule.tripsInWindow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScheduleTest {
    @Test fun gtfsTimes() {
        assertEquals(8 * 3600 + 5 * 60, parseGtfsTime(" 8:05:00"))
        assertEquals(25 * 3600 + 10 * 60 + 30, parseGtfsTime("25:10:30"))
        assertNull(parseGtfsTime(""))
        assertNull(parseGtfsTime("8:65:00"))
        assertEquals("08:05", formatGtfsTime(8 * 3600 + 5 * 60))
        assertEquals("01:10+1", formatGtfsTime(25 * 3600 + 10 * 60))
        assertEquals("23:59:30-1", formatGtfsTime(-30, withSeconds = true))
    }

    @Test fun calendarWeeklyAndExceptions() {
        val weekly = ServiceCalendar.Weekly(
            setOf(java.time.DayOfWeek.MONDAY), LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30),
        )
        val cal = ServiceCalendar(
            mapOf("WK" to weekly),
            mapOf("WK" to mapOf(LocalDate.of(2026, 9, 21) to false, LocalDate.of(2026, 9, 22) to true)),
        )
        assertTrue(cal.isActive("WK", LocalDate.of(2026, 9, 14))) // Monday
        assertFalse(cal.isActive("WK", LocalDate.of(2026, 9, 15))) // Tuesday
        assertFalse(cal.isActive("WK", LocalDate.of(2026, 9, 21))) // removed Monday
        assertTrue(cal.isActive("WK", LocalDate.of(2026, 9, 22))) // added Tuesday
        assertFalse(cal.isActive("WK", LocalDate.of(2026, 10, 5))) // after end
        assertFalse(cal.isActive("OTHER", LocalDate.of(2026, 9, 14)))
        assertTrue(ServiceCalendar.EMPTY.isActive("anything", LocalDate.of(2026, 1, 1)))
    }

    @Test fun fillsNonTimepointsByDistance() {
        val pts = listOf(LatLon(0.0, 0.0), LatLon(0.001, 0.0), LatLon(0.004, 0.0))
        val (arr, dep) = fillMissingTimes(pts, listOf(null, null, 400), listOf(100, null, null))!!
        assertEquals(listOf(100, 175, 400), arr) // 1/4 of the way → 1/4 of the time
        assertEquals(listOf(100, 175, 400), dep)
        assertNull(fillMissingTimes(pts, listOf(null, null, null), listOf(null, null, 400)))
    }

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

    // Stops at 0 m, 1000 m, 2000 m; arrive/depart at 100/100, 200/200, 300/300.
    private fun timeline(minDwell: Int) = ScheduleTimeline(
        doubleArrayOf(0.0, 1000.0, 2000.0), listOf(100, 200, 300), listOf(100, 200, 300), minDwell,
    )

    @Test fun timelineKeepsDeparturesAndDwells() {
        val tl = timeline(minDwell = 20)
        assertEquals(Phase.BeforeStart, tl.phaseAt(50.0))
        assertEquals(0.0, tl.distanceAt(50.0), 0.0)
        assertEquals(Phase.Moving(0), tl.phaseAt(150.0))
        // Arrival pulled forward to 180 so departure stays at 200.
        assertEquals(180.0, tl.arrivalSec(1), 0.0)
        assertEquals(200.0, tl.departureSec(1), 0.0)
        assertEquals(Phase.AtStop(1), tl.phaseAt(190.0))
        assertEquals(1000.0, tl.distanceAt(190.0), 0.0)
        assertEquals(0.0, tl.speedAt(190.0), 0.0)
        assertEquals(1000.0 / 80, tl.speedAt(150.0), 1e-9)
        assertEquals(Phase.Finished, tl.phaseAt(300.0))
        assertEquals(2000.0, tl.distanceAt(1e6), 0.0)
        assertEquals(1, tl.nextStopAt(150.0))
    }

    @Test fun offsetHoldsPosition() {
        val tl = timeline(minDwell = 0)
        assertEquals(150.0, tl.timeAtDistance(500.0)!!, 1e-9)
        assertNull(tl.timeAtDistance(0.0))
        // At 500 m while the clock says 400 → the vehicle is 250 s late.
        assertEquals(250, offsetToHoldPosition(tl, 500.0, serviceNowSec = 400.0))
    }

    @Test fun serviceClockPicksServiceDay() {
        val zone = ZoneId.of("America/Los_Angeles")
        val at0030 = ZonedDateTime.of(2026, 9, 23, 0, 30, 0, 0, zone).toInstant().toEpochMilli()
        // A 24:20–25:00 trip (yesterday's service) → 24:30.
        assertEquals(24.5 * 3600, serviceSecondsAt(at0030, zone, 24.33 * 3600, 25.0 * 3600), 1.0)
        // A morning trip → 00:30 today.
        assertEquals(0.5 * 3600, serviceSecondsAt(at0030, zone, 8.0 * 3600, 9.0 * 3600), 1.0)
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

    private val feed = zip(
        mapOf(
            "agency.txt" to "agency_id,agency_name,agency_timezone\nA,Agency,America/New_York\n",
            "routes.txt" to "route_id,route_short_name\nR1,10\n",
            "trips.txt" to "route_id,trip_id,service_id\nR1,AM,WK\nR1,LATE,WK\nR1,SUN,WE\n",
            "calendar.txt" to "service_id,monday,tuesday,wednesday,thursday,friday,saturday,sunday,start_date,end_date\n" +
                "WK,1,1,1,1,1,0,0,20260101,20261231\nWE,0,0,0,0,0,1,1,20260101,20261231\n",
            "stops.txt" to "stop_id,stop_name,stop_lat,stop_lon\nA,First,0,0\nB,Middle,0.001,0\nC,Last,0.004,0\n",
            "stop_times.txt" to "trip_id,arrival_time,departure_time,stop_id,stop_sequence\n" +
                "AM,08:00:00,08:00:00,A,1\nAM,,,B,2\nAM,08:04:00,08:04:00,C,3\n" +
                "LATE,24:30:00,24:30:00,A,1\nLATE,24:40:00,24:40:00,C,2\n" +
                "SUN,08:10:00,08:10:00,A,1\nSUN,08:20:00,08:20:00,C,2\n",
        ),
    )

    @Test fun parserReadsSpansCalendarAndTimezone() {
        val data = GtfsParser.parse({ ByteArrayInputStream(feed) })
        assertEquals("America/New_York", data.timezone)
        val am = data.trips.first { it.tripId == "AM" }
        assertEquals(8 * 3600, am.firstDepartureSec)
        assertEquals(8 * 3600 + 240, am.lastArrivalSec)
        assertEquals("First", data.stops["A"]!!.name)

        // Wednesday 07:30–09:00 → only the weekday morning trip.
        val wed = data.tripsInWindow(ScheduleWindow(LocalDate.of(2026, 9, 23), 7 * 3600 + 1800, 9 * 3600))
        assertEquals(listOf("AM"), wed.map { it.trip.tripId })
        // Thursday 00:00–01:00 → Wednesday's 24:30 trip, shown as 00:30.
        val thuNight = data.tripsInWindow(ScheduleWindow(LocalDate.of(2026, 9, 24), 0, 3600))
        assertEquals(listOf("LATE" to 1800), thuNight.map { it.trip.tripId to it.departureSec })
        // Sunday morning → only the weekend trip.
        val sun = data.tripsInWindow(ScheduleWindow(LocalDate.of(2026, 9, 27), 8 * 3600, 9 * 3600))
        assertEquals(listOf("SUN"), sun.map { it.trip.tripId })
    }

    @Test fun readTripStopsInterpolatesNonTimepoints() {
        val data = GtfsParser.parse({ ByteArrayInputStream(feed) })
        val stops = GtfsParser.readTripStops({ ByteArrayInputStream(feed) }, "AM", data.stops)
        assertEquals(listOf("A", "B", "C"), stops.map { it.stopId })
        assertArrayEquals(
            intArrayOf(8 * 3600, 8 * 3600 + 60, 8 * 3600 + 240),
            stops.map { it.arrivalSec }.toIntArray(),
        )
        assertEquals("Middle", stops[1].name)
    }
}
