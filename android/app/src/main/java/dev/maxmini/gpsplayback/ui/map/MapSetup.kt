package dev.maxmini.gpsplayback.ui.map

import android.content.Context
import okhttp3.OkHttpClient
import org.maplibre.android.MapLibre
import org.maplibre.android.module.http.HttpRequestUtil

/** Base map: OpenStreetMap raster tiles, same as the web app. No API key needed. */
internal const val OSM_STYLE_JSON = """
{
  "version": 8,
  "sources": {
    "osm": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [{ "id": "osm", "type": "raster", "source": "osm" }]
}
"""

// The OSM tile usage policy requires an identifying User-Agent.
private const val USER_AGENT = "GpsPlayback/0.1 (Android; +https://github.com/maxmini-dev/adb-gps-playback)"

/** Call once from Application.onCreate, before any MapView is created. */
fun initMaps(context: Context) {
    MapLibre.getInstance(context)
    HttpRequestUtil.setOkHttpClient(
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build(),
    )
}
