package dev.maxmini.gpsplayback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.maxmini.gpsplayback.AppStore
import dev.maxmini.gpsplayback.core.geo.bearingAtDistance
import dev.maxmini.gpsplayback.core.geo.cumulativeDistances
import dev.maxmini.gpsplayback.core.geo.pointAtDistance
import dev.maxmini.gpsplayback.core.playback.PlaybackEngine
import dev.maxmini.gpsplayback.playback.PlaybackService
import dev.maxmini.gpsplayback.ui.map.RouteMap
import java.text.DateFormat
import java.util.Date

private val MULTIPLIERS = listOf(0.5, 1.0, 2.0, 5.0, 10.0, 20.0)

@Composable
fun PlayScreen(onNeedRoute: () -> Unit) {
    val context = LocalContext.current
    val routes by AppStore.routes.collectAsState()
    val player by AppStore.player.collectAsState()
    val status by AppStore.status.collectAsState()

    val route = player.routeId?.let { routes[it] } ?: routes.values.firstOrNull()
    if (route == null) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Section("No route staged") {
                Muted("Load a GTFS feed and stage a trip to start streaming mock fixes.")
                Button(onClick = onNeedRoute) { Text("Load a feed →") }
            }
        }
        return
    }

    val cum = remember(route.waypoints) { cumulativeDistances(route.waypoints) }
    val total = cum.last()
    val position = pointAtDistance(route.waypoints, cum, player.progressMeters)
    val bearing = bearingAtDistance(route.waypoints, cum, player.progressMeters)

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)) {
            RoutePicker(route.label, routes.values.map { it.id to it.label }) { id ->
                if (id != route.id) {
                    PlaybackService.stop(context)
                    AppStore.setPlayer { it.copy(routeId = id, progressMeters = 0.0, playing = false) }
                }
            }
        }
        RouteMap(
            routeKey = route.id,
            waypoints = route.waypoints,
            position = position,
            bearing = bearing,
            autoPan = player.autoPan,
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
        Column(
            Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (player.playing) {
                    Button(onClick = { PlaybackService.pause(context) }) { Text("Pause") }
                } else {
                    Button(onClick = { PlaybackService.play(context) }) { Text("Play") }
                }
                OutlinedButton(enabled = status.serviceRunning, onClick = { PlaybackService.stop(context) }) {
                    Text("Stop mocking")
                }
            }
            StatusLine(status.serviceRunning, status.lastError, status.lastSentAt)

            Section("Position") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep map centered on position", Modifier.weight(1f))
                    Switch(checked = player.autoPan, onCheckedChange = { v -> AppStore.setPlayer { it.copy(autoPan = v) } })
                }
                Muted(
                    "%.6f, %.6f · %.2f / %.2f km · %d pts".format(
                        position.lat, position.lon, player.progressMeters / 1000, total / 1000, route.waypoints.size,
                    ),
                )
                Slider(
                    value = player.progressMeters.toFloat().coerceAtMost(total.toFloat()),
                    onValueChange = { v -> AppStore.setPlayer(persist = false) { it.copy(progressMeters = v.toDouble()) } },
                    onValueChangeFinished = { AppStore.save() },
                    valueRange = 0f..maxOf(total.toFloat(), 1f),
                )
            }

            Section("Speed") {
                val kmh = PlaybackEngine.effectiveSpeed(player) * 3.6
                Muted("Base %.1f m/s × %s = %.0f km/h".format(player.baseSpeedMps, fmtMultiplier(player.speedMultiplier), kmh))
                Slider(
                    value = player.baseSpeedMps.toFloat(),
                    onValueChange = { v -> AppStore.setPlayer(persist = false) { it.copy(baseSpeedMps = v.toDouble()) } },
                    onValueChangeFinished = { AppStore.save() },
                    valueRange = 1f..40f,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    MULTIPLIERS.forEach { m ->
                        val selected = m == player.speedMultiplier
                        val onClick = { AppStore.setPlayer { it.copy(speedMultiplier = m) } }
                        if (selected) Button(onClick = onClick) { Text(fmtMultiplier(m)) }
                        else TextButton(onClick = onClick) { Text(fmtMultiplier(m)) }
                    }
                }
            }

            Section("GPS jitter") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Simulate receiver noise")
                        Muted("Slowly drifting error, σ = %.0f m per axis".format(player.jitter.sigmaMeters))
                    }
                    Switch(
                        checked = player.jitter.enabled,
                        onCheckedChange = { v -> AppStore.setPlayer { it.copy(jitter = it.jitter.copy(enabled = v)) } },
                    )
                }
                Slider(
                    enabled = player.jitter.enabled,
                    value = player.jitter.sigmaMeters.toFloat(),
                    onValueChange = { v ->
                        AppStore.setPlayer(persist = false) { it.copy(jitter = it.jitter.copy(sigmaMeters = v.toDouble())) }
                    },
                    onValueChangeFinished = { AppStore.save() },
                    valueRange = 1f..30f,
                )
            }
        }
    }
}

private fun fmtMultiplier(m: Double) = if (m % 1.0 == 0.0) "${m.toInt()}×" else "$m×"

@Composable
private fun StatusLine(running: Boolean, error: String?, lastSentAt: Long?) {
    when {
        error != null -> ErrorText(error)
        running && lastSentAt != null ->
            Muted("Mocking · last fix ${DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(lastSentAt))}")
        running -> Muted("Starting…")
        else -> Muted("Idle — the device is using its real location.")
    }
}
