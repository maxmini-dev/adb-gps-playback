package dev.maxmini.gpsplayback.mock

import android.app.AppOpsManager
import android.content.Context
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Process
import android.os.SystemClock
import com.google.android.gms.location.LocationServices
import dev.maxmini.gpsplayback.core.playback.Fix

/**
 * Pushes fixes into the platform location stack via test providers — the
 * on-device replacement for `adb emu geo fix`. Requires this app to be chosen
 * under Developer options → Select mock location app; otherwise [start] throws
 * [SecurityException].
 *
 * All of gps, network and (API 31+) fused are mocked so real fixes don't leak
 * in between fake ones. Optionally also drives Play Services' fused provider,
 * which is what most apps (Google Maps included) actually read.
 */
class MockLocationSink(
    context: Context,
    private val mockFused: Boolean,
    private val onError: (String) -> Unit,
) {
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val fused = LocationServices.getFusedLocationProviderClient(context)
    private val active = mutableListOf<String>()

    private val candidates = buildList {
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
    }

    /** @throws SecurityException if this app isn't the selected mock location app. */
    fun start() {
        for (provider in candidates) {
            // A previous run may have crashed without cleaning up.
            runCatching { lm.removeTestProvider(provider) }
            try {
                addTestProvider(provider)
                lm.setTestProviderEnabled(provider, true)
                active += provider
            } catch (e: IllegalArgumentException) {
                // Some devices refuse particular providers; mock the rest.
                onError("Can't mock $provider: ${e.message}")
            }
        }
        if (mockFused) {
            fused.setMockMode(true).addOnFailureListener { onError("Fused mock mode: ${it.message}") }
        }
    }

    fun push(fix: Fix) {
        for (provider in active) {
            lm.setTestProviderLocation(provider, toLocation(provider, fix))
        }
        if (mockFused) {
            fused.setMockLocation(toLocation("fused", fix))
                .addOnFailureListener { onError("Fused mock location: ${it.message}") }
        }
    }

    fun stop() {
        for (provider in active) runCatching { lm.removeTestProvider(provider) }
        active.clear()
        if (mockFused) runCatching { fused.setMockMode(false) }
    }

    private fun addTestProvider(provider: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lm.addTestProvider(
                provider,
                ProviderProperties.Builder()
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .build(),
            )
        } else {
            @Suppress("DEPRECATION")
            lm.addTestProvider(
                provider,
                /* requiresNetwork = */ false,
                /* requiresSatellite = */ false,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                Criteria.POWER_LOW,
                Criteria.ACCURACY_FINE,
            )
        }
    }

    // time, elapsedRealtimeNanos and accuracy are mandatory: the platform
    // rejects "incomplete" locations with IllegalArgumentException.
    private fun toLocation(provider: String, fix: Fix) = Location(provider).apply {
        latitude = fix.position.lat
        longitude = fix.position.lon
        altitude = 0.0
        accuracy = fix.accuracyMeters.toFloat()
        speed = fix.speedMps.toFloat()
        fix.bearingDegrees?.let { bearing = it.toFloat() }
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        verticalAccuracyMeters = accuracy * 1.5f
        speedAccuracyMetersPerSecond = 0.5f
        if (fix.bearingDegrees != null) bearingAccuracyDegrees = 5f
    }

    companion object {
        /** Whether this app is currently selected as the mock location app. */
        fun isSelectedMockApp(context: Context): Boolean {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName,
                )
            }
            return mode == AppOpsManager.MODE_ALLOWED
        }
    }
}
