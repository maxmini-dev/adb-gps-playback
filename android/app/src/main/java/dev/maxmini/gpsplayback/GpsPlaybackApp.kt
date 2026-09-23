package dev.maxmini.gpsplayback

import android.app.Application

class GpsPlaybackApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppStore.init(this)
    }
}
