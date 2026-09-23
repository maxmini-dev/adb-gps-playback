package dev.maxmini.gpsplayback.core.geo

import dev.maxmini.gpsplayback.core.model.LatLon
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * GeoJSON strings for the map layers. Built here (not with the map SDK's
 * GeoJSON types) so they're unit tested and the map code stays thin.
 * Note GeoJSON positions are [lon, lat].
 */
object GeoJson {
    private fun position(p: LatLon): JsonArray = buildJsonArray {
        add(JsonPrimitive(p.lon))
        add(JsonPrimitive(p.lat))
    }

    private fun feature(geometry: JsonObject, properties: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("type", "Feature")
        put("geometry", geometry)
        put("properties", properties)
    }

    private fun collection(features: List<JsonObject>) = buildJsonObject {
        put("type", "FeatureCollection")
        put("features", JsonArray(features))
    }

    private fun point(p: LatLon) = buildJsonObject {
        put("type", "Point")
        put("coordinates", position(p))
    }

    /** The route polyline (empty collection if fewer than 2 points). */
    fun line(points: List<LatLon>): String {
        if (points.size < 2) return collection(emptyList()).toString()
        val geometry = buildJsonObject {
            put("type", "LineString")
            put("coordinates", JsonArray(points.map(::position)))
        }
        return feature(geometry).toString()
    }

    /** One Point feature per waypoint, with its index as property `i`. */
    fun vertices(points: List<LatLon>): String = collection(
        points.mapIndexed { i, p -> feature(point(p), buildJsonObject { put("i", i) }) },
    ).toString()

    /** The playback position with its heading as property `bearing` (0 when unknown). */
    fun position(p: LatLon?, bearingDegrees: Double?): String {
        if (p == null) return collection(emptyList()).toString()
        return feature(
            point(p),
            buildJsonObject {
                put("bearing", bearingDegrees ?: 0.0)
                put("hasBearing", bearingDegrees != null)
            },
        ).toString()
    }
}
