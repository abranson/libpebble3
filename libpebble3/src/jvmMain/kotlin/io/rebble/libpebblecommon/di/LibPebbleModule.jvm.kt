package io.rebble.libpebblecommon.di

import io.rebble.libpebblecommon.calendar.PlatformCalendarActionHandler
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.LegacyPhoneReceiver
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.classic.pebble.BtClassicConnector
import io.rebble.libpebblecommon.connection.bt.classic.transport.ClassicScanner
import io.rebble.libpebblecommon.connection.bt.classic.transport.JvmBtClassicConnector
import io.rebble.libpebblecommon.connection.bt.classic.transport.JvmClassicScanner
import io.rebble.libpebblecommon.connection.bt.classic.transport.LinuxRfcommSocketFactory
import io.rebble.libpebblecommon.connection.bt.classic.transport.UnavailableLinuxRfcommSocketFactory
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.linux.music.LinuxSystemMusicControl
import io.rebble.libpebblecommon.linux.InactiveLinuxDeviceActivity
import io.rebble.libpebblecommon.linux.LinuxDeviceActivity
import io.rebble.libpebblecommon.linux.music.MprisController
import io.rebble.libpebblecommon.linux.music.MprisVolume
import io.rebble.libpebblecommon.linux.music.VolumeControl
import io.rebble.libpebblecommon.linux.notifications.FreedesktopHintMapper
import io.rebble.libpebblecommon.linux.notifications.FreedesktopNotificationBackend
import io.rebble.libpebblecommon.linux.notifications.LinuxNotificationBackend
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
import org.koin.core.module.dsl.scopedOf
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Generic Linux bindings — freedesktop interfaces only (BlueZ, MPRIS, org.freedesktop.Notifications),
 * so they work on any distro with a session bus.
 *
 * A specific distro layers its own bindings on top through
 * [io.rebble.libpebblecommon.connection.LibPebble3.create]'s
 * `platformOverrides`. This module must not reference a distro-specific class.
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

    // Notifications: generic Linux uses the freedesktop session bus. A distro
    // can override only LinuxNotificationBackend while retaining libpebble3's
    // pin, replacement, appearance, mute, and watch-action processing.
    single { InactiveLinuxDeviceActivity } bind LinuxDeviceActivity::class
    singleOf(::FreedesktopHintMapper) bind NotificationHintMapper::class
    singleOf(::FreedesktopNotificationBackend) bind LinuxNotificationBackend::class
    singleOf(::LinuxNotificationListener) bind NotificationListenerConnection::class
    singleOf(::LinuxNotificationActionHandler) bind PlatformNotificationActionHandler::class

    // Music: MPRIS2 works anywhere. Volume doesn't — MPRIS only has a per-player Volume, so a
    // distro with a system mixer overrides VolumeControl. MprisController is shared: it owns the
    // session-bus connection and the active-player choice, which volume control needs too.
    singleOf(::MprisController)
    singleOf(::MprisVolume) bind VolumeControl::class
    singleOf(::LinuxSystemMusicControl) bind SystemMusicControl::class

    // No portable freedesktop equivalent: calendar, contacts, calls and
    // geolocation all use distro-specific services.
    singleOf(::JvmSystemCalendar) bind SystemCalendar::class
    singleOf(::JvmSystemContacts) bind SystemContacts::class
    singleOf(::JvmLegacyPhoneReceiver) bind LegacyPhoneReceiver::class
    singleOf(::JvmSystemGeolocation) bind SystemGeolocation::class

    // Still stubbed everywhere: no OS equivalent wired up (yet).
    singleOf(::JvmNotificationAppsSync) bind NotificationAppsSync::class
    singleOf(::JvmPlatformCalendarActionHandler) bind PlatformCalendarActionHandler::class
    singleOf(::JvmSystemCallLog) bind SystemCallLog::class
    singleOf(::JvmOtherPebbleApps) bind OtherPebbleApps::class

    // BlueZ provides Classic discovery; direct RFCOMM is supplied through the factory below.
    singleOf(::JvmClassicScanner) bind ClassicScanner::class

    // Generic Linux currently has no direct outbound RFCOMM implementation. A
    // platform can override this factory when it supplies one.
    single<LinuxRfcommSocketFactory> { UnavailableLinuxRfcommSocketFactory }
    scope<ConnectionScope> {
        scopedOf(::JvmBtClassicConnector) bind BtClassicConnector::class
    }
}
