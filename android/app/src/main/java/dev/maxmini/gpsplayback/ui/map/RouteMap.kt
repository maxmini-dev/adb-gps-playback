package dev.maxmini.gpsplayback.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.maxmini.gpsplayback.core.edit.RouteEdit
import dev.maxmini.gpsplayback.core.model.LatLon

/**
 * MapLibre map of a route. Read-only with a moving position marker for Play;
 * with [editable] it shows waypoints and reports edits through [onEdit].
 * [routeKey] changing re-fits the camera to the route.
 */
@Composable
fun RouteMap(
    routeKey: String?,
    waypoints: List<LatLon>,
    modifier: Modifier = Modifier,
    stops: List<LatLon> = emptyList(),
    position: LatLon? = null,
    bearing: Double? = null,
    autoPan: Boolean = false,
    editable: Boolean = false,
    onEdit: (RouteEdit) -> Unit = {},
) {
    val context = LocalContext.current
    val controller = remember { RouteMapController(context) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnEdit by rememberUpdatedState(onEdit)

    // MapView needs the host's lifecycle forwarded to it.
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.onStart()
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
                Lifecycle.Event.ON_STOP -> controller.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // Replays events up to the current state.
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(controller) {
        onDispose { controller.onDestroy() }
    }

    AndroidView(
        factory = { controller.mapView },
        modifier = modifier,
        update = {
            controller.update(routeKey, waypoints, stops, position, bearing, autoPan, editable) { currentOnEdit(it) }
        },
    )
}
