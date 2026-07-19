package io.rebble.libpebblecommon.linux.music

import kotlinx.coroutines.flow.StateFlow

/**
 * System volume, abstracted because MPRIS has no *stepping* — only an optional absolute Volume
 * property on the player, which not every player implements and which changes the player rather
 * than the device. A platform with a real mixer API (Sailfish's PulseAudio MainVolume2) should
 * bind its own; [MprisVolume] is the fallback.
 *
 * Implementations block and are called on [kotlinx.coroutines.Dispatchers.IO].
 */
interface VolumeControl {
    /** Last known volume in percent, or null while unknown. */
    val volumePercent: StateFlow<Int?>

    /** Read the current volume into [volumePercent]. */
    fun refresh()

    /** Step the volume; [delta] is a signed count of user-perceptible steps, normally ±1. */
    fun step(delta: Int)
}

/**
 * Fallback volume control: drives the active MPRIS player's own Volume property. Only affects that
 * player, not the device, and does nothing for players that don't publish Volume.
 */
class MprisVolume(private val mpris: MprisController) : VolumeControl {
    override val volumePercent: StateFlow<Int?> = mpris.activeVolumePercent

    /** MPRIS pushes Volume via PropertiesChanged, so there is nothing to poll. */
    override fun refresh() = Unit

    override fun step(delta: Int) {
        val current = mpris.activeVolumePercent.value ?: return
        mpris.setVolumePercent(current + delta * STEP_PERCENT)
    }

    private companion object {
        private const val STEP_PERCENT = 10
    }
}
