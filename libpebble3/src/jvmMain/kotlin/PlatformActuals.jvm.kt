package io.rebble.libpebblecommon

import io.rebble.libpebblecommon.packets.PhoneAppVersion

// Report Android, not Linux: PebbleOS only enables the Pebble-Protocol music service
// (now-playing display + transport controls) when the phone's OS is Android — every other
// OS is expected to use AMS/iAP instead (music/endpoint.c: `os != RemoteOSAndroid` bails).
// We drive everything (notifications, music) over Pebble Protocol like the Android app, and
// music is the only watch behaviour gated on the reported OS, so Android is the correct fit.
//
// These actuals stay in libpebble3's jvmMain: a JVM `actual` must compile into the same module
// as its commonMain `expect`. The daemon entrypoint (fun main) lives in the libpebble3d-sailfish
// module — it has no expect/actual and only assembles the generic library into a Sailfish daemon.
actual fun getPlatform(): PhoneAppVersion.OSType = PhoneAppVersion.OSType.Android

actual fun performPlatformSpecificInit() {}
