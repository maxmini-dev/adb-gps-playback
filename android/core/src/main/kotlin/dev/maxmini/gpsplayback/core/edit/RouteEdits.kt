package dev.maxmini.gpsplayback.core.edit

import dev.maxmini.gpsplayback.core.model.EditableRoute
import dev.maxmini.gpsplayback.core.model.LatLon

/** A single waypoint edit, as produced by the editor map. Mirrors the web store's actions. */
sealed interface RouteEdit {
    data class Move(val index: Int, val point: LatLon) : RouteEdit
    /** Insert [point] so that it becomes waypoint [index] (between index-1 and index). */
    data class Insert(val index: Int, val point: LatLon) : RouteEdit
    data class Delete(val index: Int) : RouteEdit
    data object Reset : RouteEdit
}

/** Minimum waypoints a route keeps; deletes below this are ignored (same as web). */
const val MIN_WAYPOINTS = 2

/** Apply [edit], returning this route unchanged if the edit is out of range or not allowed. */
fun EditableRoute.applyEdit(edit: RouteEdit): EditableRoute = when (edit) {
    is RouteEdit.Move ->
        if (edit.index !in waypoints.indices) this
        else copy(waypoints = waypoints.toMutableList().also { it[edit.index] = edit.point })
    is RouteEdit.Insert ->
        if (edit.index !in 0..waypoints.size) this
        else copy(waypoints = waypoints.toMutableList().also { it.add(edit.index, edit.point) })
    is RouteEdit.Delete ->
        if (edit.index !in waypoints.indices || waypoints.size <= MIN_WAYPOINTS) this
        else copy(waypoints = waypoints.filterIndexed { i, _ -> i != edit.index })
    RouteEdit.Reset -> copy(waypoints = originalPoints)
}
