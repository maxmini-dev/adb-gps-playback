package dev.maxmini.gpsplayback.core.schedule

import dev.maxmini.gpsplayback.core.model.GtfsData
import dev.maxmini.gpsplayback.core.model.GtfsTrip
import java.time.LocalDate

/** Departures on [date] between [fromSec] and [toSec] (seconds after that day's midnight). */
data class ScheduleWindow(val date: LocalDate, val fromSec: Int, val toSec: Int)

/**
 * A trip that departs inside a window. [departureSec] is on the window date's
 * clock: a trip of yesterday's service departing at 25:10 shows as 01:10.
 */
data class WindowTrip(val trip: GtfsTrip, val departureSec: Int)

/**
 * Trips whose service runs on the window's date (per calendar.txt /
 * calendar_dates.txt) and whose first departure falls in the window, sorted by
 * departure. Includes the previous service day's after-midnight trips.
 */
fun GtfsData.tripsInWindow(window: ScheduleWindow): List<WindowTrip> {
    val out = ArrayList<WindowTrip>()
    val yesterday = window.date.minusDays(1)
    for (trip in trips) {
        val dep = trip.firstDepartureSec ?: continue
        if (dep in window.fromSec..window.toSec && calendar.isActive(trip.serviceId, window.date)) {
            out += WindowTrip(trip, dep)
        } else if (dep >= 86_400 && dep - 86_400 in window.fromSec..window.toSec &&
            calendar.isActive(trip.serviceId, yesterday)
        ) {
            out += WindowTrip(trip, dep - 86_400)
        }
    }
    out.sortBy { it.departureSec }
    return out
}
