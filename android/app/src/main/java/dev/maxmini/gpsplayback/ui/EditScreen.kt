package dev.maxmini.gpsplayback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.maxmini.gpsplayback.AppStore
import dev.maxmini.gpsplayback.core.edit.RouteEdit
import dev.maxmini.gpsplayback.core.geo.polylineLengthMeters
import dev.maxmini.gpsplayback.playback.PlaybackService
import dev.maxmini.gpsplayback.ui.map.RouteMap

@Composable
fun EditScreen(onNeedRoute: () -> Unit, onPlay: () -> Unit) {
    val context = LocalContext.current
    val routes by AppStore.routes.collectAsState()
    var selectedId by rememberSaveable { mutableStateOf(AppStore.player.value.routeId) }
    val route = selectedId?.let { routes[it] } ?: routes.values.firstOrNull()

    if (route == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Section("No staged routes yet") {
                Muted("Load a GTFS feed and stage a few trips first.")
                Button(onClick = onNeedRoute) { Text("Load a feed →") }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RoutePicker(route.label, routes.values.map { it.id to it.label }) { selectedId = it }
            Muted(
                "%.2f km · %d pts%s".format(
                    polylineLengthMeters(route.waypoints) / 1000,
                    route.waypoints.size,
                    if (route.waypoints != route.originalPoints) " · edited" else "",
                ),
            )
            Muted("Drag a point to move it · tap the line to add one · long-press a point to delete it")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {
                    AppStore.setPlayer { it.copy(routeId = route.id, progressMeters = 0.0, playing = false) }
                    onPlay()
                }) { Text("Play this route") }
                OutlinedButton(
                    enabled = route.waypoints != route.originalPoints,
                    onClick = { AppStore.editRoute(route.id, RouteEdit.Reset) },
                ) { Text("Reset") }
                TextButton(onClick = {
                    if (AppStore.player.value.routeId == route.id) PlaybackService.stop(context)
                    AppStore.removeRoute(route.id)
                    selectedId = null
                }) { Text("Remove") }
            }
        }
        RouteMap(
            routeKey = route.id,
            waypoints = route.waypoints,
            editable = true,
            onEdit = { AppStore.editRoute(route.id, it) },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}
