package dev.maxmini.gpsplayback.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.maxmini.gpsplayback.AppStore
import dev.maxmini.gpsplayback.R
import dev.maxmini.gpsplayback.core.geo.bearingAtDistance
import dev.maxmini.gpsplayback.core.geo.cumulativeDistances
import dev.maxmini.gpsplayback.core.geo.pointAtDistance
import dev.maxmini.gpsplayback.core.model.MPS_PER_MPH
import dev.maxmini.gpsplayback.core.model.PlayerState
import dev.maxmini.gpsplayback.core.playback.PlaybackEngine
import dev.maxmini.gpsplayback.playback.PlaybackService
import dev.maxmini.gpsplayback.ui.map.RouteMap
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

private const val MIN_MPH = 1
private const val MAX_MPH = 90

@OptIn(ExperimentalMaterial3Api::class)
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

    val sheetState = rememberBottomSheetScaffoldState()
    val density = LocalDensity.current
    // Peek height is measured from the peek content so it survives font scaling.
    var peekHeight by remember { mutableStateOf(0.dp) }
    var pickerHeight by remember { mutableStateOf(0.dp) }

    BottomSheetScaffold(
        scaffoldState = sheetState,
        sheetPeekHeight = peekHeight,
        sheetDragHandle = null,
        sheetContent = {
            Column(
                Modifier.fillMaxWidth().onSizeChanged { peekHeight = with(density) { it.height.toDp() } },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BottomSheetDefaults.DragHandle()
                TransportBar(player.playing, status.serviceRunning, player.progressMeters, total, stopDistances)
                Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                    StatusLine(status.serviceRunning, status.lastError, status.lastSentAt)
                }
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AlongRoute(player.progressMeters, total)

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
        },
    ) { padding ->
        // The map fills the screen under the route picker and the sheet. Its
        // insets keep the fitted route and the auto-panned position centered in
        // the part left visible between them.
        Box(Modifier.fillMaxSize().padding(padding)) {
            RouteMap(
                routeKey = route.id,
                waypoints = route.waypoints,
                stops = stopPoints,
                position = position,
                bearing = bearing,
                autoPan = player.autoPan,
                insetTop = pickerHeight,
                insetBottom = peekHeight,
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                Modifier.fillMaxWidth()
                    .onSizeChanged { pickerHeight = with(density) { it.height.toDp() } }
                    .padding(16.dp),
            ) {
                Surface(shape = ButtonDefaults.outlinedShape, shadowElevation = 3.dp) {
                    RoutePicker(route.label, routes.values.map { it.id to it.label }) { id ->
                        if (id != route.id) {
                            PlaybackService.stop(context)
                            AppStore.setPlayer { it.copy(routeId = id, progressMeters = 0.0, playing = false) }
                        }
                    }
                }
            }
        }
    }
}

private fun seek(meters: Double, total: Double, persist: Boolean = true) =
    AppStore.setPlayer(persist) { it.copy(progressMeters = meters.coerceIn(0.0, total)) }

/**
 * Always-visible controls in the collapsed sheet: previous stop, play/pause,
 * stop mocking, next stop. The stop buttons are disabled when the route has no
 * stops or there's no stop in that direction.
 */
@Composable
private fun TransportBar(
    playing: Boolean,
    serviceRunning: Boolean,
    progressMeters: Double,
    total: Double,
    stopDistances: DoubleArray,
) {
    val context = LocalContext.current
    val prev = PlaybackEngine.previousStopBefore(stopDistances, progressMeters)
    val next = PlaybackEngine.nextStopAfter(stopDistances, progressMeters)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(enabled = prev != null, onClick = { prev?.let { seek(it, total) } }) {
            Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = "Previous stop")
        }
        FilledIconButton(
            onClick = { if (playing) PlaybackService.pause(context) else PlaybackService.play(context) },
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = if (playing) "Pause" else "Play",
            )
        }
        IconButton(enabled = serviceRunning, onClick = { PlaybackService.stop(context) }) {
            Icon(painterResource(R.drawable.ic_stop), contentDescription = "Stop mocking")
        }
        IconButton(enabled = next != null, onClick = { next?.let { seek(it, total) } }) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = "Next stop")
        }
    }
}

/**
 * Jump anywhere along the route: percentage slider and quick presets.
 * Previous/next stop live in [TransportBar]. Works while playing too; the
 * service picks up the new position on its next tick.
 */
@Composable
private fun AlongRoute(progressMeters: Double, total: Double) {
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
            onValueChange = { f -> seek(f * total, total, persist = false) },
            onValueChangeFinished = { AppStore.save() },
            valueRange = 0f..1f,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(0, 25, 50, 75, 100).forEach { pct ->
                TextButton(onClick = { seek(total * pct / 100, total) }) { Text("$pct%") }
            }
        }
    }
}

@Composable
private fun SpeedPanel(player: PlayerState, hasStops: Boolean) {
    Section("Speed") {
        val mph = (player.speedMps / MPS_PER_MPH).roundToInt()
        Muted("$mph mph")
        Slider(
            value = mph.toFloat(),
            onValueChange = { v ->
                AppStore.setPlayer(persist = false) { it.copy(speedMps = v.roundToInt() * MPS_PER_MPH) }
            },
            onValueChangeFinished = { AppStore.save() },
            valueRange = MIN_MPH.toFloat()..MAX_MPH.toFloat(),
            steps = MAX_MPH - MIN_MPH - 1,
        )
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
    Muted("Time at each stop: ${player.dwellSec} s")
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
