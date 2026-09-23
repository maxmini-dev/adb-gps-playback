package dev.maxmini.gpsplayback.core.gtfs

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Which service_ids run on which dates, from calendar.txt + calendar_dates.txt. */
class ServiceCalendar(
    private val weekly: Map<String, Weekly>,
    /** service_id → date → true (added, exception_type 1) / false (removed, 2). */
    private val exceptions: Map<String, Map<LocalDate, Boolean>>,
) {
    data class Weekly(val days: Set<DayOfWeek>, val start: LocalDate, val end: LocalDate)

    /** True if the feed has no calendar at all, in which case every service counts as running. */
    val isEmpty: Boolean get() = weekly.isEmpty() && exceptions.isEmpty()

    fun isActive(serviceId: String?, date: LocalDate): Boolean {
        if (isEmpty || serviceId == null) return true
        exceptions[serviceId]?.get(date)?.let { return it }
        val w = weekly[serviceId] ?: return false
        return date.dayOfWeek in w.days && !date.isBefore(w.start) && !date.isAfter(w.end)
    }

    companion object {
        val EMPTY = ServiceCalendar(emptyMap(), emptyMap())
        private val DATE = DateTimeFormatter.BASIC_ISO_DATE // yyyyMMdd

        fun parseDate(s: String): LocalDate? = runCatching { LocalDate.parse(s.trim(), DATE) }.getOrNull()

        private val DAY_COLUMNS = listOf(
            "monday" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY,
            "wednesday" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY,
            "friday" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sunday" to DayOfWeek.SUNDAY,
        )

        /** Build a [Weekly] from a calendar.txt record, or null if its dates are malformed. */
        fun weeklyFrom(get: (String) -> String): Weekly? {
            val start = parseDate(get("start_date")) ?: return null
            val end = parseDate(get("end_date")) ?: return null
            val days = DAY_COLUMNS.filter { (col, _) -> get(col) == "1" }.mapTo(HashSet()) { it.second }
            return Weekly(days, start, end)
        }
    }
}
