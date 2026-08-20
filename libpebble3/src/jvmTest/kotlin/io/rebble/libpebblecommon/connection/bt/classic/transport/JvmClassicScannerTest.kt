package io.rebble.libpebblecommon.connection.bt.classic.transport

import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmClassicScannerTest {
    @Test
    fun mergesPartialBluezPropertiesBeforeClassifyingPebble() {
        val state = BluezClassicDeviceState(address = "D0:81:0A:D4:D7:CD")

        state.merge(mapOf("Name" to Variant("Pebble D7CD")))
        assertFalse(state.isClassicPebble())

        state.merge(
            mapOf(
                "Class" to Variant(0x5A0204),
                "RSSI" to Variant(-47),
            )
        )

        assertTrue(state.isClassicPebble())
        assertEquals(-47, state.rssi)
    }

    @Test
    fun rejectsLeOrUnrelatedDevicesEvenWithPartialProperties() {
        val lePebble = BluezClassicDeviceState()
        lePebble.merge(
            mapOf(
                "Name" to Variant("Pebble D7CD"),
                "Class" to Variant(0),
            )
        )
        assertFalse(lePebble.isClassicPebble())

        val otherClassicDevice = BluezClassicDeviceState()
        otherClassicDevice.merge(
            mapOf(
                "Name" to Variant("Headphones D7CD"),
                "Class" to Variant(0x240404),
            )
        )
        assertFalse(otherClassicDevice.isClassicPebble())
    }

    @Test
    fun acceptsTheExactPebbleSdpServiceWithoutClassMetadata() {
        val state = BluezClassicDeviceState()
        state.merge(
            mapOf(
                "Name" to Variant("Pebble D7CD"),
                "UUIDs" to Variant(arrayOf("00000000-DECA-FADE-DECA-DEAFDECACAFF")),
            )
        )

        assertTrue(state.isClassicPebble())
        state.invalidate(listOf("UUIDs"))
        assertFalse(state.isClassicPebble())
    }

    @Test
    fun usesAliasOnlyUntilBluezSuppliesAName() {
        val state = BluezClassicDeviceState()
        state.merge(
            mapOf(
                "Alias" to Variant("Pebble D7CD"),
                "Class" to Variant(0x5A0204),
            )
        )

        assertTrue(state.isClassicPebble())

        state.merge(mapOf("Name" to Variant("Unknown device")))
        assertFalse(state.isClassicPebble())
    }

    @Test
    fun extractsOnlyBluetoothAddressesFromDevicePaths() {
        assertEquals(
            "D0:81:0A:D4:D7:CD",
            addressFromClassicDevicePath("/org/bluez/hci1/dev_D0_81_0A_D4_D7_CD"),
        )
        assertEquals(null, addressFromClassicDevicePath("/org/bluez/hci1/dev_D0_81_0A_D4_D7"))
        assertEquals(null, addressFromClassicDevicePath("/org/bluez/hci1/dev_D0_81_0A_D4_D7_CD/service0001"))
    }
}
