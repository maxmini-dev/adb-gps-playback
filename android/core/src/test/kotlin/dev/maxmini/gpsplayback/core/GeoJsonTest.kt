package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.geo.GeoJson
import dev.maxmini.gpsplayback.core.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class GeoJsonTest {
    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private val pts = listOf(LatLon(1.0, 2.0), LatLon(3.0, 4.0))

    @Test fun lineUsesLonLatOrder() {
        val coords = parse(GeoJson.line(pts))["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
        assertEquals(2.0, coords[0].jsonArray[0].jsonPrimitive.double, 0.0) // lon first
        assertEquals(1.0, coords[0].jsonArray[1].jsonPrimitive.double, 0.0)
        assertEquals("FeatureCollection", parse(GeoJson.line(pts.take(1)))["type"]!!.jsonPrimitive.content)
    }

    @Test fun verticesCarryIndex() {
        val features = parse(GeoJson.vertices(pts))["features"]!!.jsonArray
        assertEquals(2, features.size)
        assertEquals(1, features[1].jsonObject["properties"]!!.jsonObject["i"]!!.jsonPrimitive.int)
    }

    @Test fun positionBearing() {
        val props = parse(GeoJson.position(pts[0], 90.0))["properties"]!!.jsonObject
        assertEquals(90.0, props["bearing"]!!.jsonPrimitive.double, 0.0)
        assertEquals("true", props["hasBearing"]!!.jsonPrimitive.content)
        assertEquals(0, parse(GeoJson.position(null, null))["features"]!!.jsonArray.size)
    }
}
