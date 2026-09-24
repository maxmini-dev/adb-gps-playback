package dev.maxmini.gpsplayback.ui.map

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import dev.maxmini.gpsplayback.core.edit.RouteEdit
import dev.maxmini.gpsplayback.core.edit.ScreenPoint
import dev.maxmini.gpsplayback.core.edit.hitVertex
import dev.maxmini.gpsplayback.core.edit.insertIndexFor
import dev.maxmini.gpsplayback.core.geo.GeoJson
import dev.maxmini.gpsplayback.core.model.LatLon
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import kotlin.math.hypot

/**
 * Owns a MapLibre [MapView] showing a route, its timetable stops, its waypoints
 * (in edit mode) and the playback position. It's deliberately free of Compose; `RouteMap.kt`
 * wraps it and forwards lifecycle events and state.
 *
 * Edit gestures (when [update] is called with editable = true):
 * - drag a waypoint to move it
 * - long-press a waypoint to delete it
 * - tap near the line to insert a waypoint into the nearest segment
 * Edits are reported through the `onEdit` callback; the controller only shows a
 * live preview while dragging and otherwise renders what it's given.
 */
@SuppressLint("ClickableViewAccessibility") // Map gestures; no click semantics to expose.
class RouteMapController(context: Context) {
    val mapView = MapView(context)

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private val density = context.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())

    // Desired state, applied whenever the style is ready.
    private var routeKey: String? = null
    private var waypoints: List<LatLon> = emptyList()
    private var stops: List<LatLon> = emptyList()
    private var position: LatLon? = null
    private var bearing: Double? = null
    private var autoPan = false
    private var editable = false
    private var onEdit: (RouteEdit) -> Unit = {}

    // What has been applied, to avoid redundant work at the 4 Hz update rate.
    private var renderedWaypoints: List<LatLon>? = null
    private var renderedStops: List<LatLon>? = null
    private var renderedEditable: Boolean? = null
    private var fittedKey: String? = null
    private var pannedTo: LatLon? = null

    // Waypoint drag / long-press state.
    private var touchIndex: Int? = null
    private var dragging = false
    /** After a long-press delete, swallow the rest of the gesture. */
    private var swallowUntilUp = false
    private var downX = 0f
    private var downY = 0f
    private val longPress = Runnable {
        val i = touchIndex ?: return@Runnable
        if (dragging) return@Runnable
        touchIndex = null
        swallowUntilUp = true
        mapView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onEdit(RouteEdit.Delete(i))
    }

    private var started = false
    private var resumed = false
    private var destroyed = false

    init {
        mapView.onCreate(null)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.setTiltGesturesEnabled(false)
            m.addOnMapClickListener { ll -> onMapTap(ll) }
            m.setStyle(Style.Builder().fromJson(OSM_STYLE_JSON)) { s ->
                addLayers(s)
                style = s
                apply()
            }
        }
        mapView.setOnTouchListener { _, ev -> onTouch(ev) }
    }

    fun update(
        routeKey: String?,
        waypoints: List<LatLon>,
        stops: List<LatLon>,
        position: LatLon?,
        bearing: Double?,
        autoPan: Boolean,
        editable: Boolean,
        onEdit: (RouteEdit) -> Unit,
    ) {
        this.routeKey = routeKey
        this.waypoints = waypoints
        this.stops = stops
        this.position = position
        this.bearing = bearing
        this.autoPan = autoPan
        this.editable = editable
        this.onEdit = onEdit
        if (!editable) cancelTouch()
        apply()
    }

    private fun apply() {
        val s = style ?: return
        val m = map ?: return
        if (touchIndex == null && renderedWaypoints !== waypoints) {
            setRouteGeometry(s, waypoints)
            renderedWaypoints = waypoints
        }
        if (renderedStops !== stops) {
            s.getSourceAs<GeoJsonSource>(STOPS_SOURCE)?.setGeoJson(GeoJson.vertices(stops))
            renderedStops = stops
        }
        if (renderedEditable != editable) {
            s.getLayer(VERTICES_LAYER)?.setProperties(
                PropertyFactory.visibility(if (editable) Property.VISIBLE else Property.NONE),
            )
            renderedEditable = editable
        }
        s.getSourceAs<GeoJsonSource>(POSITION_SOURCE)?.setGeoJson(GeoJson.position(position, bearing))

        if (fittedKey != routeKey && waypoints.isNotEmpty()) {
            if (mapView.width == 0 || mapView.height == 0) {
                mapView.post { apply() } // Not laid out yet; bounds fitting needs a size.
                return
            }
            fitRoute(m)
            fittedKey = routeKey
            return
        }
        val p = position
        if (autoPan && p != null && p != pannedTo) {
            m.easeCamera(CameraUpdateFactory.newLatLng(LatLng(p.lat, p.lon)), PAN_MS)
            pannedTo = p
        }
    }

    private fun fitRoute(m: MapLibreMap) {
        val distinct = waypoints.distinct()
        if (distinct.size < 2) {
            val p = waypoints.first()
            m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(p.lat, p.lon), 15.0))
            return
        }
        val bounds = LatLngBounds.Builder().includes(distinct.map { LatLng(it.lat, it.lon) }).build()
        m.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, (40 * density).toInt()))
    }

    private fun setRouteGeometry(s: Style, points: List<LatLon>) {
        s.getSourceAs<GeoJsonSource>(ROUTE_SOURCE)?.setGeoJson(GeoJson.line(points))
        s.getSourceAs<GeoJsonSource>(VERTICES_SOURCE)?.setGeoJson(GeoJson.vertices(points))
    }

    private fun addLayers(s: Style) {
        s.addImage(ARROW_IMAGE, positionBitmap(withArrow = true))
        s.addImage(DOT_IMAGE, positionBitmap(withArrow = false))
        s.addSource(GeoJsonSource(ROUTE_SOURCE))
        s.addSource(GeoJsonSource(VERTICES_SOURCE))
        s.addSource(GeoJsonSource(STOPS_SOURCE))
        s.addSource(GeoJsonSource(POSITION_SOURCE))
        s.addLayer(
            LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
                PropertyFactory.lineColor(ROUTE_COLOR),
                PropertyFactory.lineWidth(4f),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            ),
        )
        // Timetable stops: small dark-ringed dots, under the editable waypoints.
        s.addLayer(
            CircleLayer(STOPS_LAYER, STOPS_SOURCE).withProperties(
                PropertyFactory.circleRadius(4.5f),
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleStrokeColor(STOP_COLOR),
                PropertyFactory.circleStrokeWidth(2f),
            ),
        )
        s.addLayer(
            CircleLayer(VERTICES_LAYER, VERTICES_SOURCE).withProperties(
                PropertyFactory.circleRadius(6f),
                PropertyFactory.circleColor(Color.WHITE),
                PropertyFactory.circleStrokeColor(ROUTE_COLOR),
                PropertyFactory.circleStrokeWidth(2.5f),
                PropertyFactory.visibility(Property.NONE),
            ),
        )
        s.addLayer(
            SymbolLayer(POSITION_LAYER, POSITION_SOURCE).withProperties(
                PropertyFactory.iconImage(
                    Expression.switchCase(
                        Expression.get("hasBearing"), Expression.literal(ARROW_IMAGE),
                        Expression.literal(DOT_IMAGE),
                    ),
                ),
                PropertyFactory.iconRotate(Expression.get("bearing")),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
            ),
        )
    }

    /** A blue dot with a white ring; with a north-pointing arrow when [withArrow]. */
    private fun positionBitmap(withArrow: Boolean): Bitmap {
        val size = (28 * density).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val r = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.WHITE
        c.drawCircle(r, r, r, paint)
        paint.color = POSITION_COLOR
        c.drawCircle(r, r, r - 3 * density, paint)
        if (withArrow) {
            paint.color = Color.WHITE
            val arrow = Path().apply {
                moveTo(r, size * 0.2f)
                lineTo(size * 0.72f, size * 0.74f)
                lineTo(r, size * 0.6f)
                lineTo(size * 0.28f, size * 0.74f)
                close()
            }
            c.drawPath(arrow, paint)
        }
        return bmp
    }

    // --- Edit gestures -----------------------------------------------------

    private fun screenPoints(m: MapLibreMap): List<ScreenPoint> = waypoints.map {
        val p = m.projection.toScreenLocation(LatLng(it.lat, it.lon))
        ScreenPoint(p.x.toDouble(), p.y.toDouble())
    }

    private fun latLonAt(m: MapLibreMap, x: Float, y: Float): LatLon {
        val ll = m.projection.fromScreenLocation(PointF(x, y))
        return LatLon(ll.latitude, ll.longitude)
    }

    private fun onMapTap(ll: LatLng): Boolean {
        val m = map ?: return false
        if (!editable) return false
        val tap = m.projection.toScreenLocation(ll)
        val index = insertIndexFor(
            screenPoints(m), ScreenPoint(tap.x.toDouble(), tap.y.toDouble()), INSERT_RADIUS_DP * density.toDouble(),
        ) ?: return false
        onEdit(RouteEdit.Insert(index, LatLon(ll.latitude, ll.longitude)))
        return true
    }

    /** Intercepts touches that start on a waypoint; everything else goes to the map's gestures. */
    private fun onTouch(ev: MotionEvent): Boolean {
        val m = map ?: return false
        val s = style ?: return false
        if (swallowUntilUp) {
            val a = ev.actionMasked
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) swallowUntilUp = false
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!editable) return false
                val i = hitVertex(
                    screenPoints(m), ScreenPoint(ev.x.toDouble(), ev.y.toDouble()), VERTEX_RADIUS_DP * density.toDouble(),
                ) ?: return false
                touchIndex = i
                dragging = false
                downX = ev.x
                downY = ev.y
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                mapView.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val i = touchIndex ?: return false
                if (!dragging && hypot(ev.x - downX, ev.y - downY) > touchSlop) {
                    dragging = true
                    handler.removeCallbacks(longPress)
                }
                if (dragging) {
                    val preview = waypoints.toMutableList().also { it[i] = latLonAt(m, ev.x, ev.y) }
                    setRouteGeometry(s, preview)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val i = touchIndex ?: return false
                val wasDragging = dragging
                cancelTouch()
                if (wasDragging) onEdit(RouteEdit.Move(i, latLonAt(m, ev.x, ev.y)))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touchIndex == null) return false
                cancelTouch()
                renderedWaypoints = null // Drop the drag preview.
                apply()
                return true
            }
            else -> return touchIndex != null
        }
    }

    private fun cancelTouch() {
        handler.removeCallbacks(longPress)
        touchIndex = null
        dragging = false
    }

    // --- Lifecycle, forwarded from the hosting composable ------------------

    fun onStart() {
        if (destroyed || started) return
        mapView.onStart()
        started = true
    }

    fun onResume() {
        if (destroyed || resumed) return
        onStart()
        mapView.onResume()
        resumed = true
    }

    fun onPause() {
        if (!resumed) return
        mapView.onPause()
        resumed = false
    }

    fun onStop() {
        onPause()
        if (!started) return
        mapView.onStop()
        started = false
    }

    fun onDestroy() {
        if (destroyed) return
        onStop()
        cancelTouch()
        mapView.onDestroy()
        destroyed = true
        map = null
        style = null
    }

    fun onLowMemory() = mapView.onLowMemory()

    private companion object {
        const val ROUTE_SOURCE = "route"
        const val VERTICES_SOURCE = "vertices"
        const val POSITION_SOURCE = "position"
        const val STOPS_SOURCE = "stops"
        const val STOPS_LAYER = "stops"
        const val ROUTE_LAYER = "route-line"
        const val VERTICES_LAYER = "route-vertices"
        const val POSITION_LAYER = "position"
        const val ARROW_IMAGE = "position-arrow"
        const val DOT_IMAGE = "position-dot"
        const val ROUTE_COLOR = "#3B82F6" // same blue as the web editor
        const val STOP_COLOR = "#374151"
        val POSITION_COLOR = Color.rgb(0x1E, 0x6F, 0xD9)
        const val PAN_MS = 300
        const val VERTEX_RADIUS_DP = 24.0
        const val INSERT_RADIUS_DP = 32.0
    }
}
