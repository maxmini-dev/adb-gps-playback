package dev.maxmini.gpsplayback.ui

import kotlin.math.abs

/** "On time", "3 min 20 s late", "45 s early". */
fun formatOffset(offsetSec: Int): String {
    if (offsetSec == 0) return "On time"
    val a = abs(offsetSec)
    val m = a / 60
    val s = a % 60
    val amount = when {
        m == 0 -> "$s s"
        s == 0 -> "$m min"
        else -> "$m min $s s"
    }
    return amount + if (offsetSec > 0) " late" else " early"
}

/** "2:05" for a duration in seconds (or "1:02:05" past an hour). */
fun formatDuration(sec: Int): String {
    val a = maxOf(sec, 0)
    val h = a / 3600
    val m = a % 3600 / 60
    val s = a % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
