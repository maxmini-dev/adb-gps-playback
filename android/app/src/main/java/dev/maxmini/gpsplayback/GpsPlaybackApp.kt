package dev.maxmini.gpsplayback

import android.app.Application
import dev.maxmini.gpsplayback.ui.map.initMaps

class GpsPlaybackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)
        initMaps(this)
    }
}
