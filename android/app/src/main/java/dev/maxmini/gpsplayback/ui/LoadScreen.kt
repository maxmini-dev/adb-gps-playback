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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import dev.maxmini.gpsplayback.core.model.GtfsData
import dev.maxmini.gpsplayback.core.model.GtfsTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

/** One row per route: its trips that have a usable shape. */
private data class RouteRow(val routeId: String, val title: String, val trips: List<GtfsTrip>)

private fun routeTitle(gtfs: GtfsData, routeId: String): String {
    val r = gtfs.routes.find { it.routeId == routeId } ?: return routeId
    return listOfNotNull(r.shortName, r.longName).joinToString(" — ").ifEmpty { r.routeId }
}

private fun hasShape(gtfs: GtfsData, t: GtfsTrip) = t.shapeId?.let { gtfs.shapes[it] } != null

private fun rows(gtfs: GtfsData): List<RouteRow> {
    val byRoute = gtfs.trips.filter { hasShape(gtfs, it) }.groupBy { it.routeId }
    return gtfs.routes.mapNotNull { r ->
        val trips = byRoute[r.routeId] ?: return@mapNotNull null
        // Many trips share one shape; list each distinct geometry once.
        RouteRow(r.routeId, routeTitle(gtfs, r.routeId), trips.distinctBy { it.shapeId })
    }
}

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

    val allRows = remember(gtfs) { gtfs?.let(::rows) ?: emptyList() }
    val shown = remember(allRows, filter) {
        if (filter.isBlank()) allRows else allRows.filter { it.title.contains(filter, ignoreCase = true) }
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

        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter routes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (shown.isEmpty()) {
            Muted("No routes match.")
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.routeId }) { row ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { expanded = if (expanded == row.routeId) null else row.routeId }
                        .padding(vertical = 12.dp),
                ) {
                    Text(row.title)
                    Muted("${row.trips.size} distinct shapes")
                }
                if (expanded == row.routeId) {
                    row.trips.forEach { trip ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(trip.headsign ?: trip.tripId, Modifier.weight(1f))
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
