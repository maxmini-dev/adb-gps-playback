package dev.maxmini.gpsplayback

import android.content.Context
import android.util.Log
import dev.maxmini.gpsplayback.core.edit.RouteEdit
import dev.maxmini.gpsplayback.core.edit.applyEdit
import dev.maxmini.gpsplayback.core.model.EditableRoute
import dev.maxmini.gpsplayback.core.model.GtfsData
import dev.maxmini.gpsplayback.core.model.PlayerState
import dev.maxmini.gpsplayback.core.gtfs.GtfsParser
import dev.maxmini.gpsplayback.core.model.editableRouteFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executors

@Serializable
data class Settings(
    /** Also drive Play Services' fused provider (what Google Maps etc. read). */
    val mockFused: Boolean = true,
    /** How often the service pushes a fix, in milliseconds. */
    val tickMs: Long = 250,
)

/** Transient status for the UI; never persisted. */
data class PlaybackStatus(
    val serviceRunning: Boolean = false,
    val lastError: String? = null,
    val lastSentAt: Long? = null,
)

/**
 * App-wide state, the Android counterpart of the web app's Zustand store.
 * Only routes, player and settings are persisted — raw GTFS stays in memory.
 */
object AppStore {
    private const val TAG = "AppStore"

    @Serializable
    private data class Persisted(
        val routes: Map<String, EditableRoute> = emptyMap(),
        val player: PlayerState = PlayerState(),
        val settings: Settings = Settings(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var file: File

    private val _gtfs = MutableStateFlow<GtfsData?>(null)
    /** Re-opens the loaded feed's zip, for reading a trip's timetable when staging. */
    private var gtfsSource: (() -> InputStream)? = null
    private val _routes = MutableStateFlow<Map<String, EditableRoute>>(emptyMap())
    private val _player = MutableStateFlow(PlayerState())
    private val _settings = MutableStateFlow(Settings())
    private val _status = MutableStateFlow(PlaybackStatus())

    val gtfs: StateFlow<GtfsData?> = _gtfs.asStateFlow()
    val routes: StateFlow<Map<String, EditableRoute>> = _routes.asStateFlow()
    val player: StateFlow<PlayerState> = _player.asStateFlow()
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    val status: StateFlow<PlaybackStatus> = _status.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "state.json")
        val loaded = runCatching { json.decodeFromString(Persisted.serializer(), file.readText()) }
            .onFailure { if (file.exists()) Log.w(TAG, "Ignoring unreadable state", it) }
            .getOrNull() ?: return
        _routes.value = loaded.routes
        // Never resume in the playing state after a process restart.
        _player.value = loaded.player.copy(playing = false)
        _settings.value = loaded.settings
    }

    /** The route the player is on, defaulting to the first staged one. */
    fun activeRoute(): EditableRoute? {
        val routes = _routes.value
        return _player.value.routeId?.let { routes[it] } ?: routes.values.firstOrNull()
    }

    fun setGtfs(data: GtfsData?, source: (() -> InputStream)?) {
        _gtfs.value = data
        gtfsSource = source
    }

    /**
     * Stage a trip, reading its timetable from the feed (a streaming pass over
     * stop_times.txt, so this runs on the IO dispatcher). Returns a message if
     * something went wrong or the trip was staged without a timetable.
     */
    suspend fun stageTrip(tripId: String): String? {
        val data = _gtfs.value ?: return "No feed loaded."
        val source = gtfsSource
        var warning: String? = null
        val stops = if (source == null) {
            emptyList()
        } else {
            try {
                withContext(Dispatchers.IO) { GtfsParser.readTripStops(source, tripId, data.stops) }
            } catch (e: Exception) {
                warning = "Couldn't read the timetable (${e.message}); staged without it."
                emptyList()
            }
        }
        val route = data.editableRouteFor(tripId, stops) ?: return "This trip has no shape to follow."
        _routes.update { it + (route.id to route) }
        save()
        return warning ?: if (stops.size < 2) "Staged without a timetable: schedule mode won't be available." else null
    }

    /** Apply a waypoint edit (move / insert / delete / reset) to a staged route. */
    fun editRoute(id: String, edit: RouteEdit) {
        var changed = false
        _routes.update { routes ->
            val route = routes[id] ?: return@update routes
            val next = route.applyEdit(edit)
            if (next === route) return@update routes
            changed = true
            routes + (id to next)
        }
        if (changed) save()
    }

    fun removeRoute(id: String) {
        _routes.update { it - id }
        _player.update {
            if (it.routeId == id) it.copy(routeId = null, playing = false, progressMeters = 0.0) else it
        }
        save()
    }

    /**
     * Patch player state. The playback service ticks this several times a second
     * with [persist] = false and persists on pause/stop instead.
     */
    fun setPlayer(persist: Boolean = true, patch: (PlayerState) -> PlayerState) {
        _player.update(patch)
        if (persist) save()
    }

    fun setSettings(patch: (Settings) -> Settings) {
        _settings.update(patch)
        save()
    }

    fun setStatus(patch: (PlaybackStatus) -> PlaybackStatus) {
        _status.update(patch)
    }

    fun save() {
        val snapshot = Persisted(_routes.value, _player.value, _settings.value)
        io.execute {
            runCatching {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(json.encodeToString(Persisted.serializer(), snapshot))
                tmp.renameTo(file)
            }.onFailure { Log.w(TAG, "Failed to save state", it) }
        }
    }
}
