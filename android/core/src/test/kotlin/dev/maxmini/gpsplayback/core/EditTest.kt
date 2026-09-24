package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.edit.RouteEdit
import dev.maxmini.gpsplayback.core.edit.ScreenPoint
import dev.maxmini.gpsplayback.core.edit.applyEdit
import dev.maxmini.gpsplayback.core.edit.distanceToSegment
import dev.maxmini.gpsplayback.core.edit.hitVertex
import dev.maxmini.gpsplayback.core.edit.insertIndexFor
import dev.maxmini.gpsplayback.core.model.EditableRoute
import dev.maxmini.gpsplayback.core.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class EditTest {
    private val a = LatLon(0.0, 0.0)
    private val b = LatLon(1.0, 1.0)
    private val c = LatLon(2.0, 2.0)
    private val x = LatLon(9.0, 9.0)
    private val route = EditableRoute("t", "r", "label", listOf(a, b, c), listOf(a, b, c))

    @Test fun moveInsertDeleteReset() {
        assertEquals(listOf(a, x, c), route.applyEdit(RouteEdit.Move(1, x)).waypoints)
        assertEquals(listOf(a, x, b, c), route.applyEdit(RouteEdit.Insert(1, x)).waypoints)
        assertEquals(listOf(a, b, c, x), route.applyEdit(RouteEdit.Insert(3, x)).waypoints)
        assertEquals(listOf(a, c), route.applyEdit(RouteEdit.Delete(1)).waypoints)
        val edited = route.applyEdit(RouteEdit.Move(0, x))
        assertEquals(listOf(a, b, c), edited.applyEdit(RouteEdit.Reset).waypoints)
    }

    @Test fun invalidEditsAreNoOps() {
        assertSame(route, route.applyEdit(RouteEdit.Move(3, x)))
        assertSame(route, route.applyEdit(RouteEdit.Insert(4, x)))
        assertSame(route, route.applyEdit(RouteEdit.Delete(-1)))
        val two = route.applyEdit(RouteEdit.Delete(1))
        assertSame(two, two.applyEdit(RouteEdit.Delete(0))) // keeps at least 2 points
    }

    private val screen = listOf(ScreenPoint(0.0, 0.0), ScreenPoint(100.0, 0.0), ScreenPoint(100.0, 100.0))

    @Test fun segmentDistance() {
        assertEquals(10.0, distanceToSegment(ScreenPoint(50.0, 10.0), screen[0], screen[1]), 1e-9)
        assertEquals(5.0, distanceToSegment(ScreenPoint(-3.0, 4.0), screen[0], screen[1]), 1e-9) // clamps to end
        assertEquals(5.0, distanceToSegment(ScreenPoint(3.0, 4.0), screen[0], screen[0]), 1e-9) // degenerate
    }

    @Test fun vertexHit() {
        assertEquals(1, hitVertex(screen, ScreenPoint(95.0, 3.0), 10.0))
        assertNull(hitVertex(screen, ScreenPoint(50.0, 0.0), 10.0))
    }

    @Test fun insertIndexMatchesNearestSegment() {
        assertEquals(1, insertIndexFor(screen, ScreenPoint(50.0, 5.0)))
        assertEquals(2, insertIndexFor(screen, ScreenPoint(105.0, 50.0)))
        assertNull(insertIndexFor(screen, ScreenPoint(50.0, 60.0), maxDistance = 20.0))
        assertNull(insertIndexFor(screen.take(1), ScreenPoint(0.0, 0.0)))
    }
}
