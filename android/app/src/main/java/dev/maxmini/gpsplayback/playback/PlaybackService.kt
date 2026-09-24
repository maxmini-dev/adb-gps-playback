package dev.maxmini.gpsplayback.playback

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.maxmini.gpsplayback.AppStore
import dev.maxmini.gpsplayback.R
import dev.maxmini.gpsplayback.core.model.EditableRoute
import dev.maxmini.gpsplayback.core.model.MPS_PER_MPH
import dev.maxmini.gpsplayback.core.model.PlayerState
import dev.maxmini.gpsplayback.core.playback.PlaybackEngine
import dev.maxmini.gpsplayback.mock.MockLocationSink
import dev.maxmini.gpsplayback.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the playback clock and the mock providers, so
 * playback keeps running while the user is in another app. The UI and the
 * notification talk to it only through [play], [pause] and [stop] intents; the
 * shared state lives in [AppStore].
 *
 * While paused the service keeps re-sending the current position, so the
 * device stays pinned there instead of falling back to real GPS.
 */
class PlaybackService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var sink: MockLocationSink? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val route = AppStore.activeRoute() ?: return stopAll()
                AppStore.setPlayer { p -> startState(route, p) }
                if (!ensureRunning()) return START_NOT_STICKY
            }
            ACTION_PAUSE -> {
                AppStore.setPlayer { it.copy(playing = false) }
                if (loop == null) return stopAll()
            }
            ACTION_STOP -> return stopAll()
            else -> return stopAll() // Restarted by the system with no intent: don't resume.
        }
        updateNotification()
        return START_NOT_STICKY
    }

    /** Start foreground + mock providers + tick loop. Returns false if it failed and stopped. */
    private fun ensureRunning(): Boolean {
        if (loop != null) return true
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            // Typically: location permission missing (API 34+), or started from background.
            fail("Couldn't start foreground service: ${e.message}")
            return false
        }

        val settings = AppStore.settings.value
        val newSink = MockLocationSink(this, settings.mockFused) { msg ->
            AppStore.setStatus { it.copy(lastError = msg) }
        }
        try {
            newSink.start()
        } catch (e: SecurityException) {
            fail("Not the selected mock location app — see Setup. (${e.message})")
            return false
        }
        sink = newSink
        AppStore.setStatus { it.copy(serviceRunning = true, lastError = null) }
        loop = scope.launch { tickLoop(newSink, settings.tickMs) }
        return true
    }

    private suspend fun CoroutineScope.tickLoop(sink: MockLocationSink, tickMs: Long) {
        var engine: PlaybackEngine? = null
        var engineFor: EditableRoute? = null
        var last = SystemClock.elapsedRealtime()
        var lastNotif = 0L
        var wasPlaying = AppStore.player.value.playing
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - last) / 1000.0
            last = now

            val route = AppStore.activeRoute()
            if (route == null) {
                stopAll()
                return
            }
            // Rebuild when the route or its geometry changes (e.g. edited while playing).
            val prev = engineFor
            if (engine == null || prev == null || prev.id != route.id || prev.waypoints !== route.waypoints) {
                engine = PlaybackEngine(route.waypoints, route.stops)
                engineFor = route
            }

            val before = AppStore.player.value
            val next = engine.advance(before, dt)
            if (next != before) AppStore.setPlayer(persist = false) { next }

            try {
                sink.push(engine.fixFor(next, dt))
                AppStore.setStatus { it.copy(lastSentAt = System.currentTimeMillis()) }
            } catch (e: RuntimeException) {
                // SecurityException if the mock app selection was revoked mid-run.
                AppStore.setStatus { it.copy(lastError = e.message ?: e.toString()) }
            }

            if (wasPlaying && !next.playing) AppStore.save() // Reached the end or paused.
            wasPlaying = next.playing
            if (now - lastNotif >= 1000) {
                lastNotif = now
                updateNotification()
            }
            delay(tickMs)
        }
    }

    private fun fail(message: String) {
        AppStore.setPlayer { it.copy(playing = false) }
        AppStore.setStatus { it.copy(lastError = message) }
        stopAll()
    }

    private fun stopAll(): Int {
        loop?.cancel()
        loop = null
        sink?.stop()
        sink = null
        AppStore.setPlayer { it.copy(playing = false) }
        AppStore.setStatus { it.copy(serviceRunning = false) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Leaving test providers registered would pin the device to the last fix.
        sink?.stop()
        sink = null
        scope.cancel()
        AppStore.setStatus { it.copy(serviceRunning = false) }
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // Checked via areNotificationsEnabled().
    private fun updateNotification() {
        if (loop == null) return
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return
        runCatching { nm.notify(NOTIFICATION_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val route = AppStore.activeRoute()
        val player = AppStore.player.value
        val total = route?.let { totalMeters(it) } ?: 0.0
        val pct = if (total > 0) (player.progressMeters / total).coerceIn(0.0, 1.0) else 0.0
        val mph = player.speedMps / MPS_PER_MPH

        val toggle = if (player.playing) {
            NotificationCompat.Action(R.drawable.ic_pause, "Pause", servicePending(ACTION_PAUSE))
        } else {
            NotificationCompat.Action(R.drawable.ic_play, "Play", servicePending(ACTION_PLAY))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_location)
            .setContentTitle(route?.label ?: getString(R.string.app_name))
            .setContentText(
                "%s · %.2f / %.2f km · %.0f mph".format(
                    if (player.playing) "Playing" else "Paused",
                    player.progressMeters / 1000, total / 1000, mph,
                ),
            )
            .setProgress(1000, (pct * 1000).toInt(), false)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(toggle)
            .addAction(NotificationCompat.Action(R.drawable.ic_stop, "Stop", servicePending(ACTION_STOP)))
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun servicePending(action: String): PendingIntent = PendingIntent.getService(
        this, action.hashCode(),
        Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW)
                .apply { description = getString(R.string.notif_channel_desc) },
        )
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        const val ACTION_PLAY = "dev.maxmini.gpsplayback.PLAY"
        const val ACTION_PAUSE = "dev.maxmini.gpsplayback.PAUSE"
        const val ACTION_STOP = "dev.maxmini.gpsplayback.STOP"

        private fun totalMeters(route: EditableRoute) =
            dev.maxmini.gpsplayback.core.geo.polylineLengthMeters(route.waypoints)

        /** Player state for pressing Play on [route]: restart if parked at the end. */
        private fun startState(route: EditableRoute, p: PlayerState): PlayerState {
            val atEnd = p.routeId == route.id && p.progressMeters >= totalMeters(route)
            return p.copy(routeId = route.id, playing = true, progressMeters = if (atEnd) 0.0 else p.progressMeters)
        }

        fun play(context: Context) = ContextCompat.startForegroundService(
            context, Intent(context, PlaybackService::class.java).setAction(ACTION_PLAY),
        )

        fun pause(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_PAUSE))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, PlaybackService::class.java).setAction(ACTION_STOP))
        }
    }
}
