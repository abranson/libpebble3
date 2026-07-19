package io.rebble.libpebblecommon.linux.music

import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.music.PlaybackStatus
import io.rebble.libpebblecommon.music.SystemMusicControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Linux media integration: now-playing state and transport controls over MPRIS2 on the session bus
 * ([MprisController]), volume through the injected [VolumeControl].
 *
 * Never throws on construction — with no session bus or no players it just reports null.
 */
class LinuxSystemMusicControl(
    private val scope: LibPebbleCoroutineScope,
    private val mpris: MprisController,
    private val volume: VolumeControl,
) : SystemMusicControl {

    init {
        mpris.start()
        // Baseline volume so the watch's volume bar starts out roughly right.
        scope.launch(Dispatchers.IO) { volume.refresh() }
    }

    override val playbackState: StateFlow<PlaybackStatus?> =
        combine(mpris.state, volume.volumePercent) { status, percent ->
            // A platform VolumeControl reports the device volume and wins; falling back to the
            // player's own MPRIS volume already in status.
            status?.copy(volume = percent ?: status.volume)
        }.stateIn(scope, SharingStarted.Eagerly, null)

    override fun play() = mpris.play()
    override fun pause() = mpris.pause()
    override fun playPause() = mpris.playPause()
    override fun nextTrack() = mpris.next()
    override fun previousTrack() = mpris.previous()

    override fun volumeDown() {
        scope.launch(Dispatchers.IO) { volume.step(-1) }
    }

    override fun volumeUp() {
        scope.launch(Dispatchers.IO) { volume.step(+1) }
    }
}
