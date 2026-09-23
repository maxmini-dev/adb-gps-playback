package dev.maxmini.gpsplayback.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.maxmini.gpsplayback.AppStore
import dev.maxmini.gpsplayback.core.gtfs.GtfsParser
import dev.maxmini.gpsplayback.core.gtfs.formatGtfsTime
import dev.maxmini.gpsplayback.core.model.GtfsData
import dev.maxmini.gpsplayback.core.model.GtfsTrip
import dev.maxmini.gpsplayback.core.schedule.ScheduleWindow
import dev.maxmini.gpsplayback.core.schedule.tripsInWindow
import dev.maxmini.gpsplayback.core.schedule.zoneOrDefault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

/** One row per route with its trips (each with the departure to show, if known). */
private data class RouteRow(val routeId: String, val title: String, val trips: List<Pair<GtfsTrip, Int?>>)

private fun routeTitle(gtfs: GtfsData, routeId: String): String {
    val r = gtfs.routes.find { it.routeId == routeId } ?: return routeId
    return listOfNotNull(r.shortName, r.longName).joinToString(" — ").ifEmpty { r.routeId }
}

private fun hasShape(gtfs: GtfsData, t: GtfsTrip) = t.shapeId?.let { gtfs.shapes[it] } != null

/** Unfiltered: every route, listing each distinct shape once (many trips share one). */
private fun allRows(gtfs: GtfsData): List<RouteRow> {
    val byRoute = gtfs.trips.filter { hasShape(gtfs, it) }.groupBy { it.routeId }
    return gtfs.routes.mapNotNull { r ->
        val trips = byRoute[r.routeId] ?: return@mapNotNull null
        RouteRow(r.routeId, routeTitle(gtfs, r.routeId), trips.distinctBy { it.shapeId }.map { it to it.firstDepartureSec })
    }
}

/** Filtered: routes with trips departing in the window, each trip listed by departure time. */
private fun windowRows(gtfs: GtfsData, window: ScheduleWindow): List<RouteRow> =
    gtfs.tripsInWindow(window)
        .filter { hasShape(gtfs, it.trip) }
        .groupBy { it.trip.routeId }
        .map { (routeId, trips) -> RouteRow(routeId, routeTitle(gtfs, routeId), trips.map { it.trip to it.departureSec }) }
        .sortedBy { it.title }

private val WINDOW_LENGTHS = listOf(30 to "30m", 60 to "1h", 120 to "2h", 240 to "4h", 1440 to "All day")
private val DATE_LABEL = DateTimeFormatter.ofPattern("EEE d MMM")

@Composable
fun LoadScreen(onStaged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gtfs by AppStore.gtfs.collectAsState()
    val staged by AppStore.routes.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var staging by remember { mutableStateOf(setOf<String>()) }

    // Schedule filter: a date plus a departure window, in the feed's timezone.
    val feedToday = remember(gtfs) { LocalDate.now(zoneOrDefault(gtfs?.timezone)) }
    var useWindow by remember { mutableStateOf(true) }
    var date by remember(feedToday) { mutableStateOf(feedToday) }
    var fromMin by remember(gtfs) {
        val now = LocalTime.now(zoneOrDefault(gtfs?.timezone))
        mutableIntStateOf((now.hour * 60 + now.minute) / 30 * 30)
    }
    var lengthMin by remember { mutableIntStateOf(60) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Keep read access so staging can re-read the zip later.
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val open: () -> InputStream = {
            context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
        }
        loading = true
        message = null
        scope.launch {
            try {
                val data = withContext(Dispatchers.IO) { GtfsParser.parse(open, feedName = displayName(context, uri)) }
                AppStore.setGtfs(data, open)
            } catch (e: Exception) {
                message = "Couldn't read feed: ${e.message ?: e}"
            } finally {
                loading = false
            }
        }
    }

    val hasTimes = remember(gtfs) { gtfs?.trips?.any { it.firstDepartureSec != null } == true }
    val windowed = useWindow && hasTimes
    val window = ScheduleWindow(date, fromMin * 60, minOf(fromMin + lengthMin, 48 * 60) * 60)
    val rows = remember(gtfs, windowed, window) {
        val g = gtfs ?: return@remember emptyList()
        if (windowed) windowRows(g, window) else allRows(g)
    }
    val shown = remember(rows, filter) {
        if (filter.isBlank()) rows else rows.filter { it.title.contains(filter, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !loading, onClick = {
                pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }) { Text("Open GTFS zip") }
            if (loading) CircularProgressIndicator()
            if (staged.isNotEmpty()) TextButton(onClick = onStaged) { Text("${staged.size} staged →") }
        }
        message?.let { Muted(it) }
        val feed = gtfs
        if (feed == null) {
            Muted("Pick a GTFS .zip. Routes without shapes.txt get a polyline synthesized from their stops.")
            return@Column
        }
        Muted("${feed.feedName ?: "Feed"} · ${feed.routes.size} routes · ${feed.trips.size} trips")

        if (hasTimes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter by schedule", Modifier.weight(1f))
                Switch(checked = useWindow, onCheckedChange = { useWindow = it })
            }
            if (useWindow) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { date = date.minusDays(1) }) { Text("◀") }
                    Text(date.format(DATE_LABEL))
                    TextButton(onClick = { date = date.plusDays(1) }) { Text("▶") }
                    if (date != feedToday) TextButton(onClick = { date = feedToday }) { Text("Today") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Departing")
                    TextButton(onClick = { fromMin = maxOf(0, fromMin - 30) }) { Text("−") }
                    Text(formatGtfsTime(fromMin * 60))
                    TextButton(onClick = { fromMin = minOf(47 * 60 + 30, fromMin + 30) }) { Text("+") }
                    Text("to ${formatGtfsTime(window.toSec)}")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    WINDOW_LENGTHS.forEach { (min, label) ->
                        val onClick: () -> Unit = {
                            lengthMin = min
                            if (min >= 1440) fromMin = 0
                        }
                        if (min == lengthMin) Button(onClick = onClick) { Text(label) }
                        else TextButton(onClick = onClick) { Text(label) }
                    }
                }
            }
        }

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter routes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (shown.isEmpty()) {
            Muted(if (windowed) "No trips depart in this window. Try another time or date." else "No routes match.")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.routeId }) { row ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { expanded = if (expanded == row.routeId) null else row.routeId }
                        .padding(vertical = 12.dp),
                ) {
                    Text(row.title)
                    Muted(if (windowed) "${row.trips.size} trips in window" else "${row.trips.size} distinct shapes")
                }
                if (expanded == row.routeId) {
                    row.trips.forEach { (trip, dep) ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            val name = trip.headsign ?: trip.tripId
                            Text(dep?.let { "${formatGtfsTime(it)}  $name" } ?: name, Modifier.weight(1f))
                            when {
                                trip.tripId in staged -> Muted("Staged")
                                trip.tripId in staging -> CircularProgressIndicator()
                                else -> TextButton(onClick = {
                                    staging = staging + trip.tripId
                                    scope.launch {
                                        message = AppStore.stageTrip(trip.tripId)
                                        staging = staging - trip.tripId
                                    }
                                }) { Text("Stage") }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
