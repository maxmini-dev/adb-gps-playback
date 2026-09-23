package dev.maxmini.gpsplayback.core.schedule

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Seconds after service-day midnight for [epochMillis] in [zone], choosing the
 * service day (today or yesterday) that puts "now" closest to the trip's
 * [startSec, endSec] span. That way a trip scheduled 24:30–25:10 plays at
 * 00:30 local time. The service day's origin is local midnight; on DST-change
 * days GTFS technically measures from "noon minus 12h", which can differ by an
 * hour. That's acceptable for a test tool.
 */
fun serviceSecondsAt(epochMillis: Long, zone: ZoneId, startSec: Double, endSec: Double): Double {
    val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), zone)
    val midnight = now.toLocalDate().atStartOfDay(zone)
    val today = (epochMillis - midnight.toInstant().toEpochMilli()) / 1000.0
    val candidates = listOf(today, today + 86_400)
    return candidates.minBy { t ->
        when {
            t < startSec -> startSec - t
            t > endSec -> t - endSec
            else -> 0.0
        }
    }
}

/** Parse a GTFS timezone id, falling back to the device's zone. */
fun zoneOrDefault(id: String?): ZoneId =
    id?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.systemDefault()

/**
 * Schedule offset (seconds late; negative = early) that keeps the vehicle at
 * [progressMeters] right now. Used when resuming after Pause and when scrubbing
 * in schedule mode. Null when the position doesn't pin down a time (at or before
 * the first stop): keep the current offset then.
 */
fun offsetToHoldPosition(timeline: ScheduleTimeline, progressMeters: Double, serviceNowSec: Double): Int? =
    timeline.timeAtDistance(progressMeters)?.let { Math.round(serviceNowSec - it).toInt() }

/** The current service-day second for a trip running [offsetSec] late. */
fun scheduleNow(timeline: ScheduleTimeline, offsetSec: Int, epochMillis: Long, zone: ZoneId): Double =
    serviceSecondsAt(epochMillis, zone, timeline.startSec + offsetSec, timeline.endSec + offsetSec)

/** [offsetToHoldPosition] against the wall clock; keeps [currentOffsetSec] when the position doesn't pin a time. */
fun offsetToHoldPositionNow(
    timeline: ScheduleTimeline,
    progressMeters: Double,
    currentOffsetSec: Int,
    epochMillis: Long,
    zone: ZoneId,
): Int {
    val now = scheduleNow(timeline, currentOffsetSec, epochMillis, zone)
    return offsetToHoldPosition(timeline, progressMeters, now) ?: currentOffsetSec
}
