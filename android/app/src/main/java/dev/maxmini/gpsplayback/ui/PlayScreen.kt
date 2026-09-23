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
import androidx.compose.material3.MaterialTheme
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
import dev.maxmini.gpsplayback.core.model.PlayerState
import dev.maxmini.gpsplayback.core.playback.PlaybackEngine
import dev.maxmini.gpsplayback.playback.PlaybackService
import dev.maxmini.gpsplayback.ui.map.RouteMap
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

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
    val stopPoints = remember(route.stops) { route.stops.map { it.point } }
    val stopDistances = remember(route.waypoints, route.stops) {
        PlaybackEngine(route.waypoints, route.stops).stopDistances
    }
    val hasStops = stopDistances.isNotEmpty()

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
            stops = stopPoints,
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

            AlongRoute(player.progressMeters, total, stopDistances)

            SpeedPanel(player, hasStops)
            JitterPanel(player)

            Section("Position") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Keep map centered on position", Modifier.weight(1f))
                    Switch(checked = player.autoPan, onCheckedChange = { v -> AppStore.setPlayer { it.copy(autoPan = v) } })
                }
                Muted("%.6f, %.6f · %d pts".format(position.lat, position.lon, route.waypoints.size))
            }
        }
    }
}

/**
 * Jump anywhere along the route: percentage slider, quick presets, and
 * previous/next stop. Works while playing too; the service picks up the new
 * position on its next tick.
 */
@Composable
private fun AlongRoute(progressMeters: Double, total: Double, stopDistances: DoubleArray) {
    fun seek(meters: Double, persist: Boolean = true) =
        AppStore.setPlayer(persist) { it.copy(progressMeters = meters.coerceIn(0.0, total)) }
    val fraction = if (total > 0) (progressMeters / total).coerceIn(0.0, 1.0) else 0.0

    Section("Along route") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("%.1f%%".format(fraction * 100), style = MaterialTheme.typography.headlineSmall)
            Text(
                "  %.2f / %.2f km".format(progressMeters / 1000, total / 1000),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Slider(
            value = fraction.toFloat(),
            onValueChange = { f -> seek(f * total, persist = false) },
            onValueChangeFinished = { AppStore.save() },
            valueRange = 0f..1f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0, 25, 50, 75, 100).forEach { pct ->
                TextButton(onClick = { seek(total * pct / 100) }) { Text("$pct%") }
            }
        }
        if (stopDistances.isNotEmpty()) {
            val prev = PlaybackEngine.previousStopBefore(stopDistances, progressMeters)
            val next = PlaybackEngine.nextStopAfter(stopDistances, progressMeters)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = prev != null, onClick = { prev?.let { seek(it) } }) { Text("◀ Prev stop") }
                OutlinedButton(enabled = next != null, onClick = { next?.let { seek(it) } }) { Text("Next stop ▶") }
            }
        }
    }
}

@Composable
private fun SpeedPanel(player: PlayerState, hasStops: Boolean) {
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
                val onClick = { AppStore.setPlayer { it.copy(speedMultiplier = m) } }
                if (m == player.speedMultiplier) Button(onClick = onClick) { Text(fmtMultiplier(m)) }
                else TextButton(onClick = onClick) { Text(fmtMultiplier(m)) }
            }
        }
        if (hasStops) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stop at each stop", Modifier.weight(1f))
                Switch(checked = player.stopAtStops, onCheckedChange = { v -> AppStore.setPlayer { it.copy(stopAtStops = v) } })
            }
            if (player.stopAtStops) DwellSlider(player)
        } else {
            Muted("No stops for this route. Re-stage the trip from Load to stop along the way.")
        }
    }
}

@Composable
private fun DwellSlider(player: PlayerState) {
    Muted("Time at each stop: ${player.dwellSec} s (scaled by the multiplier)")
    Slider(
        value = player.dwellSec.toFloat(),
        onValueChange = { v -> AppStore.setPlayer(persist = false) { it.copy(dwellSec = (v / 5).roundToInt() * 5) } },
        onValueChangeFinished = { AppStore.save() },
        valueRange = 0f..120f,
        steps = 120 / 5 - 1,
    )
}

@Composable
private fun JitterPanel(player: PlayerState) {
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
