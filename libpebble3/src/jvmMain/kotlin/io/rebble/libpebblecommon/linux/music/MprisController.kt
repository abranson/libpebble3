package io.rebble.libpebblecommon.linux.music

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.music.PlaybackState
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.PlayerInfo
import io.rebble.libpebblecommon.music.RepeatType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.handlers.AbstractPropertiesChangedHandler
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Tracks MPRIS2 players on the session D-Bus and exposes the "active" player's state (a playing
 * player wins, otherwise the last active one sticks). All state is confined to a single-threaded
 * IO dispatcher; signal callbacks only hop onto it. The whole thing degrades to a null state and
 * retries when there is no session bus (e.g. build container).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MprisController(private val scope: LibPebbleCoroutineScope) {
    private val logger = Logger.withTag("MprisController")
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)

    private val _state = MutableStateFlow<PlaybackStatus?>(null)
    /** Active player's state. [PlaybackStatus.volume] is the player's own MPRIS volume. */
    val state: StateFlow<PlaybackStatus?> = _state.asStateFlow()

    private val _activeVolumePercent = MutableStateFlow<Int?>(null)
    /** Active player's MPRIS volume, or null when there is none or it publishes no Volume. */
    val activeVolumePercent: StateFlow<Int?> = _activeVolumePercent.asStateFlow()

    private var connection: DBusConnection? = null
    private val players = LinkedHashMap<String, PlayerState>() // bus name -> state
    private var activeBusName: String? = null
    private var changeCounter = 0L

    private class PlayerState(val busName: String) {
        var owner: String? = null // unique name, for matching signal senders
        var identity: String? = null
        var playback: PlaybackState = PlaybackState.Paused
        var track: MusicTrack? = null
        var positionMs: Long = 0
        var rate: Float = 1f
        var shuffle: Boolean = false
        var repeat: RepeatType = RepeatType.Off
        /** MPRIS Volume as a percent, or null if the player doesn't publish one. */
        var volumePercent: Int? = null
        var lastChanged: Long = 0
    }

    fun start() {
        scope.launch(dispatcher) {
            while (currentCoroutineContext().isActive) {
                val conn = connectOrNull()
                if (conn == null) {
                    delay(RETRY_DELAY)
                    continue
                }
                try {
                    connection = conn
                    setup(conn)
                    // dbus-java doesn't surface disconnects here; poll for them.
                    while (conn.isConnected) delay(RETRY_DELAY)
                    logger.w { "session bus connection lost; reconnecting" }
                } catch (e: Exception) {
                    logger.w("MPRIS setup failed; will reconnect", e)
                } finally {
                    connection = null
                    players.clear()
                    activeBusName = null
                    publish()
                    runCatching { conn.disconnect() }
                }
                delay(RETRY_DELAY)
            }
        }
    }

    fun play() = withActivePlayer("Play") { it.Play() }
    fun pause() = withActivePlayer("Pause") { it.Pause() }
    fun playPause() = withActivePlayer("PlayPause") { it.PlayPause() }
    fun next() = withActivePlayer("Next") { it.Next() }
    fun previous() = withActivePlayer("Previous") { it.Previous() }

    private fun connectOrNull(): DBusConnection? = try {
        DBusConnectionBuilder.forSessionBus().withShared(false).build()
    } catch (e: Exception) {
        logger.w { "session bus unavailable (${e.message}); retrying in $RETRY_DELAY" }
        null
    }

    private fun setup(conn: DBusConnection) {
        conn.addSigHandler(DBus.NameOwnerChanged::class.java) { sig ->
            if (!sig.name.startsWith(MPRIS_PREFIX)) return@addSigHandler
            scope.launch(dispatcher) {
                if (connection != conn) return@launch
                if (sig.newOwner.isNullOrEmpty()) {
                    if (players.remove(sig.name) != null) {
                        logger.i { "player gone: ${sig.name}" }
                        publish()
                    }
                } else {
                    logger.i { "player appeared: ${sig.name}" }
                    addOrRefreshPlayer(conn, sig.name, sig.newOwner)
                }
            }
        }

        val propsHandler = object : AbstractPropertiesChangedHandler() {
            override fun handle(signal: Properties.PropertiesChanged) {
                if (signal.path != MPRIS_PATH || signal.interfaceName != PLAYER_IFACE) return
                val source = signal.source
                scope.launch(dispatcher) {
                    if (connection != conn) return@launch
                    val player = players.values.firstOrNull { it.owner == source } ?: return@launch
                    refreshPlayer(conn, player)
                    publish()
                }
            }
        }
        conn.addSigHandler(propsHandler.implementationClass, propsHandler)

        conn.addSigHandler(MprisPlayer.Seeked::class.java) { sig ->
            val source = sig.source
            scope.launch(dispatcher) {
                if (connection != conn) return@launch
                val player = players.values.firstOrNull { it.owner == source } ?: return@launch
                player.positionMs = sig.position / 1000
                touch(player)
                publish()
            }
        }

        // Signal handlers are registered before the initial scan so no player slips through.
        val dbus = conn.getRemoteObject(DBUS_BUS_NAME, DBUS_PATH, DBus::class.java)
        dbus.ListNames()
            .filter { it.startsWith(MPRIS_PREFIX) }
            .forEach { name ->
                val owner = runCatching { dbus.GetNameOwner(name) }.getOrNull()
                addOrRefreshPlayer(conn, name, owner)
            }
    }

    private fun addOrRefreshPlayer(conn: DBusConnection, busName: String, owner: String?) {
        val player = players.getOrPut(busName) { PlayerState(busName) }
        owner?.let { player.owner = it }
        refreshPlayer(conn, player)
        publish()
    }

    private fun refreshPlayer(conn: DBusConnection, player: PlayerState) {
        try {
            val props = conn.getRemoteObject(player.busName, MPRIS_PATH, Properties::class.java)
            if (player.identity == null) {
                player.identity = runCatching {
                    props.Get<String>(ROOT_IFACE, "Identity")
                }.getOrNull()
            }
            applyProperties(player, props.GetAll(PLAYER_IFACE))
            touch(player)
        } catch (e: Exception) {
            logger.w { "failed to read state of ${player.busName}: ${e.message}" }
        }
    }

    private fun applyProperties(player: PlayerState, props: Map<String, Variant<*>>) {
        (props["PlaybackStatus"]?.value as? String)?.let {
            // MPRIS "Stopped" has no protocol equivalent; treat it as paused.
            player.playback = if (it == "Playing") PlaybackState.Playing else PlaybackState.Paused
        }
        (props["Rate"]?.value as? Number)?.let { player.rate = it.toFloat() }
        (props["Shuffle"]?.value as? Boolean)?.let { player.shuffle = it }
        (props["LoopStatus"]?.value as? String)?.let {
            player.repeat = when (it) {
                "Track" -> RepeatType.One
                "Playlist" -> RepeatType.All
                else -> RepeatType.Off
            }
        }
        (props["Position"]?.value as? Number)?.let { player.positionMs = it.toLong() / 1000 }
        // MPRIS Volume is 0.0-1.0, and optional — players may exceed 1.0, so clamp.
        (props["Volume"]?.value as? Number)?.let {
            player.volumePercent = (it.toDouble() * 100).roundToInt().coerceIn(0, 100)
        }
        (props["Metadata"]?.value as? Map<*, *>)?.let { player.track = parseTrack(it) }
    }

    private fun parseTrack(metadata: Map<*, *>): MusicTrack? {
        fun value(key: String): Any? {
            var v = metadata[key]
            while (v is Variant<*>) v = v.value
            return v
        }

        val title = value("xesam:title") as? String
        val artist = when (val a = value("xesam:artist")) {
            is String -> a
            is Collection<*> -> a.filterIsInstance<String>().joinToString(", ").ifEmpty { null }
            is Array<*> -> a.filterIsInstance<String>().joinToString(", ").ifEmpty { null }
            else -> null
        }
        val album = value("xesam:album") as? String
        if (title == null && artist == null && album == null) return null
        return MusicTrack(
            title = title,
            artist = artist,
            album = album,
            length = ((value("mpris:length") as? Number)?.toLong() ?: 0L).microseconds,
            trackNumber = (value("xesam:trackNumber") as? Number)?.toInt()?.takeIf { it > 0 },
        )
    }

    private fun touch(player: PlayerState) {
        player.lastChanged = ++changeCounter
    }

    private fun publish() {
        val active = players.values
            .filter { it.playback == PlaybackState.Playing }
            .maxByOrNull { it.lastChanged }
            ?: activeBusName?.let { players[it] } // a paused active player sticks
            ?: players.values.maxByOrNull { it.lastChanged }
        if (activeBusName != active?.busName) {
            logger.i { "active player: ${active?.busName ?: "none"} (${players.size} known)" }
        }
        activeBusName = active?.busName
        _activeVolumePercent.value = active?.volumePercent
        _state.value = active?.toStatus()
    }

    /** Set the active player's MPRIS volume. No-op if there is no active player. */
    fun setVolumePercent(percent: Int) {
        val clamped = percent.coerceIn(0, 100)
        withActivePlayerProps("SetVolume") { props ->
            props.Set(PLAYER_IFACE, "Volume", clamped / 100.0)
        }
    }

    private fun PlayerState.toStatus() = PlaybackStatus(
        playerInfo = PlayerInfo(
            packageId = busName,
            name = identity ?: busName.removePrefix(MPRIS_PREFIX),
        ),
        playbackState = playback,
        currentTrack = track,
        playbackPositionMs = positionMs,
        playbackRate = if (playback == PlaybackState.Playing) rate else 0f,
        shuffle = shuffle,
        repeat = repeat,
        // The player's own volume. A platform with a system mixer (Sailfish's PulseAudio) binds a
        // VolumeControl that overrides this in LinuxSystemMusicControl.
        volume = volumePercent ?: 0,
    )

    private fun withActivePlayer(action: String, block: (MprisPlayer) -> Unit) =
        withActiveBusName(action) { conn, busName ->
            block(conn.getRemoteObject(busName, MPRIS_PATH, MprisPlayer::class.java))
        }

    private fun withActivePlayerProps(action: String, block: (Properties) -> Unit) =
        withActiveBusName(action) { conn, busName ->
            block(conn.getRemoteObject(busName, MPRIS_PATH, Properties::class.java))
        }

    private fun withActiveBusName(action: String, block: (DBusConnection, String) -> Unit) {
        scope.launch(dispatcher) {
            val conn = connection
            val busName = activeBusName
            if (conn == null || busName == null) {
                logger.w { "$action ignored: no active MPRIS player" }
                return@launch
            }
            try {
                block(conn, busName)
            } catch (e: Exception) {
                logger.w("$action on $busName failed", e)
            }
        }
    }

    companion object {
        private const val MPRIS_PREFIX = "org.mpris.MediaPlayer2."
        private const val MPRIS_PATH = "/org/mpris/MediaPlayer2"
        private const val ROOT_IFACE = "org.mpris.MediaPlayer2"
        private const val PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
        private const val DBUS_BUS_NAME = "org.freedesktop.DBus"
        private const val DBUS_PATH = "/org/freedesktop/DBus"
        private val RETRY_DELAY = 30.seconds
    }
}
