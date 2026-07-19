package io.rebble.libpebblecommon.di

import io.rebble.libpebblecommon.calendar.PlatformCalendarActionHandler
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.classic.transport.ClassicScanner
import io.rebble.libpebblecommon.connection.bt.classic.transport.JvmClassicScanner
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.linux.music.LinuxSystemMusicControl
import io.rebble.libpebblecommon.linux.music.MprisController
import io.rebble.libpebblecommon.linux.music.MprisVolume
import io.rebble.libpebblecommon.linux.music.VolumeControl
import io.rebble.libpebblecommon.linux.notifications.FreedesktopHintMapper
import io.rebble.libpebblecommon.linux.notifications.LinuxNotificationActionHandler
import io.rebble.libpebblecommon.linux.notifications.LinuxNotificationListener
import io.rebble.libpebblecommon.linux.notifications.NotificationHintMapper
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.util.SystemGeolocation
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Generic Linux bindings — freedesktop interfaces only (BlueZ, MPRIS, org.freedesktop.Notifications),
 * so they work on any distro with a session bus.
 *
 * A specific distro layers its own on top by passing a module to
 * [io.rebble.libpebblecommon.connection.LibPebble3.create]'s `platformOverrides`; the Sailfish set
 * lives in the :libpebble3d-sailfish module's sailfishModule. Bindings here that a distro is
 * expected to improve on are marked. This module must not reference any distro-specific class —
 * that would defeat the split, and downstream daemons would drag Sailfish code into their image.
 */
actual val platformModule: Module = module {
    single {
        PhoneCapabilities(
            CommonPhoneCapabilities + setOf(
                ProtocolCapsFlag.SupportsExtendedMusicProtocol,
                ProtocolCapsFlag.SupportsTwoWayDismissal,
            )
        )
    }
    single {
        // Android, not Linux: this OS byte is what the watch reads, and PebbleOS only turns on
        // Pebble-Protocol music (now-playing + controls) for Android phones. See getPlatform().
        PlatformFlags(
            PhoneAppVersion.PlatformFlag.makeFlags(PhoneAppVersion.OSType.Android, emptyList())
        )
    }
    single { PlatformConfig(syncNotificationApps = false) }
    single {
        BlePlatformConfig(
            // Desktop: Kable/btleplug central + bluez-dbus GATT server, BLE-only.
            delayBleConnectionsAfterAppStart = false,
            delayBleDisconnections = true,
            supportsBtClassic = false,
        )
    }

    // Notifications: sniffing org.freedesktop.Notifications is portable; the hint mapping is not,
    // so a distro overrides NotificationHintMapper rather than the listener.
    singleOf(::FreedesktopHintMapper) bind NotificationHintMapper::class
    singleOf(::LinuxNotificationListener) bind NotificationListenerConnection::class
    singleOf(::LinuxNotificationActionHandler) bind PlatformNotificationActionHandler::class

    // Music: MPRIS2 works anywhere. Volume doesn't — MPRIS only has a per-player Volume, so a
    // distro with a system mixer overrides VolumeControl. MprisController is shared: it owns the
    // session-bus connection and the active-player choice, which volume control needs too.
    singleOf(::MprisController)
    singleOf(::MprisVolume) bind VolumeControl::class
    singleOf(::LinuxSystemMusicControl) bind SystemMusicControl::class

    // No portable freedesktop equivalent: calendar, contacts, calls and geolocation all differ per
    // distro (mkcal vs EDS, qtcontacts vs EDS, nemo voicecall vs oFono, geoclue 0.12 vs 2.x).
    singleOf(::JvmSystemCalendar) bind SystemCalendar::class
    singleOf(::JvmSystemContacts) bind SystemContacts::class
    singleOf(::JvmLegacyPhoneReceiver) bind LegacyPhoneReceiver::class
    singleOf(::JvmSystemGeolocation) bind SystemGeolocation::class

    // Still stubbed everywhere: no OS equivalent wired up (yet).
    singleOf(::JvmNotificationAppsSync) bind NotificationAppsSync::class
    singleOf(::JvmPlatformCalendarActionHandler) bind PlatformCalendarActionHandler::class
    singleOf(::JvmSystemCallLog) bind SystemCallLog::class
    singleOf(::JvmOtherPebbleApps) bind OtherPebbleApps::class

    // BT Classic is unsupported on desktop; the scanner yields nothing.
    singleOf(::JvmClassicScanner) bind ClassicScanner::class
}
