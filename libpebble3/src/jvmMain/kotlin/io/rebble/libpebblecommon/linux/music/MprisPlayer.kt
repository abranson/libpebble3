package io.rebble.libpebblecommon.linux.music

import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.messages.DBusSignal

/**
 * MPRIS2 player controls (dynamic proxy). Properties are read through
 * [org.freedesktop.dbus.interfaces.Properties] instead of being declared here.
 */
@Suppress("FunctionName")
@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Play()
    fun Pause()
    fun PlayPause()
    fun Next()
    fun Previous()

    /** Emitted when playback position jumps; [position] is in microseconds. */
    class Seeked(path: String, val position: Long) : DBusSignal(path, position)
}
