package dev.maxmini.gpsplayback.core.gtfs

/**
 * Parse a GTFS time ("H:MM:SS" or "HH:MM:SS") into seconds after service-day
 * midnight. Hours may be ≥ 24 for trips running past midnight. Returns null for
 * blank or malformed values (non-timepoint stops may leave times empty).
 */
fun parseGtfsTime(s: String): Int? {
    val parts = s.trim().split(':')
    if (parts.size != 3) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val sec = parts[2].toIntOrNull() ?: return null
    if (h < 0 || m !in 0..59 || sec !in 0..59) return null
    return h * 3600 + m * 60 + sec
}

/** "08:05", or "00:30+1" for times past midnight of the service day. */
fun formatGtfsTime(sec: Int, withSeconds: Boolean = false): String {
    val day = Math.floorDiv(sec, 86_400)
    val rem = Math.floorMod(sec, 86_400)
    val base = if (withSeconds) {
        "%02d:%02d:%02d".format(rem / 3600, rem % 3600 / 60, rem % 60)
    } else {
        "%02d:%02d".format(rem / 3600, rem % 3600 / 60)
    }
    return when {
        day == 0 -> base
        day > 0 -> "$base+$day"
        else -> "$base$day"
    }
}
