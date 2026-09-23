package dev.maxmini.gpsplayback.ui

import android.content.Context
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

private fun displayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

private fun parse(context: Context, uri: Uri): GtfsData = GtfsParser.parse(
    openZip = {
        context.contentResolver.openInputStream(uri) ?: throw FileNotFoundException(uri.toString())
    },
    feedName = displayName(context, uri),
)

/** One row per route: its trips that have a usable shape. */
private data class RouteRow(val routeId: String, val title: String, val trips: List<GtfsTrip>)

private fun rows(gtfs: GtfsData): List<RouteRow> {
    val tripsByRoute = gtfs.trips.filter { t -> t.shapeId?.let { gtfs.shapes[it] } != null }.groupBy { it.routeId }
    return gtfs.routes.mapNotNull { r ->
        val trips = tripsByRoute[r.routeId] ?: return@mapNotNull null
        val title = listOfNotNull(r.shortName, r.longName).joinToString(" — ").ifEmpty { r.routeId }
        // Many trips share one shape; list each distinct geometry once.
        RouteRow(r.routeId, title, trips.distinctBy { it.shapeId })
    }
}

@Composable
fun LoadScreen(onStaged: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val gtfs by AppStore.gtfs.collectAsState()
    val staged by AppStore.routes.collectAsState()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        error = null
        scope.launch {
            try {
                AppStore.setGtfs(withContext(Dispatchers.IO) { parse(context, uri) })
            } catch (e: Exception) {
                error = "Couldn't read feed: ${e.message ?: e}"
            } finally {
                loading = false
            }
        }
    }

    val allRows = remember(gtfs) { gtfs?.let(::rows) ?: emptyList() }
    val shown = remember(allRows, filter) {
        if (filter.isBlank()) allRows else allRows.filter { it.title.contains(filter, ignoreCase = true) }
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = !loading, onClick = {
                pick.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
            }) { Text("Open GTFS zip") }
            if (loading) CircularProgressIndicator()
            if (staged.isNotEmpty()) TextButton(onClick = onStaged) { Text("${staged.size} staged →") }
        }
        error?.let { ErrorText(it) }
        val feed = gtfs
        if (feed == null) {
            Muted("Pick a GTFS .zip. Routes without shapes.txt get a polyline synthesized from their stops.")
            return@Column
        }
        Muted("${feed.feedName ?: "Feed"} · ${allRows.size} routes · ${feed.trips.size} trips")
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text("Filter routes") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
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
                            if (trip.tripId in staged) {
                                Muted("Staged")
                            } else {
                                TextButton(onClick = { AppStore.addRouteFromTrip(trip.tripId) }) { Text("Stage") }
                            }
                        }
                    }
                }
                HorizontalDivider()
            }
        }
    }
}
