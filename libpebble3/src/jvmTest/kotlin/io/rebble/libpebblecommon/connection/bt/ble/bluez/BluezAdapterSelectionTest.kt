package io.rebble.libpebblecommon.connection.bt.ble.bluez

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BluezAdapterSelectionTest {
    @Test
    fun acceptsOnlyExactAdapterPaths() {
        assertTrue(isValidBluezAdapterPath("/org/bluez/hci1"))
        assertTrue(isValidBluezAdapterPath("/org/bluez/hci12"))
        assertFalse(isValidBluezAdapterPath("hci1"))
        assertFalse(isValidBluezAdapterPath("/org/bluez/hci1/device"))
        assertFalse(isValidBluezAdapterPath("/org/bluez/hci-1"))
    }

    @Test
    fun acceptsObjectsOnlyBelowSelectedAdapter() {
        val watch = "/org/bluez/hci1/dev_D0_81_0A_D4_D7_CD"

        assertTrue(isObjectOnBluezAdapter(watch, "/org/bluez/hci1"))
        assertTrue(isObjectOnBluezAdapter("$watch/service0001", "/org/bluez/hci1"))
        assertFalse(isObjectOnBluezAdapter(watch, "/org/bluez/hci0"))
        assertFalse(isObjectOnBluezAdapter("/org/bluez/hci10/dev_D0", "/org/bluez/hci1"))
        assertFalse(isObjectOnBluezAdapter("/org/bluez/hci1", "/org/bluez/hci1"))
    }

    @Test
    fun usesHci0WhenItIsTheOnlyAdapter() {
        val adapters = listOf(
            BluezAdapterCandidate("/org/bluez/hci0", "00:11:22:33:44:55", powered = true),
        )

        assertTrue(selectAutomaticBluezAdapter(adapters) == "/org/bluez/hci0")
    }

    @Test
    fun prefersLaterVirtualAdapterForDuplicateControllerAddress() {
        val adapters = listOf(
            BluezAdapterCandidate("/org/bluez/hci0", "00:11:22:33:44:55", powered = true),
            BluezAdapterCandidate("/org/bluez/hci1", "00:11:22:33:44:55", powered = true),
        )

        assertTrue(selectAutomaticBluezAdapter(adapters) == "/org/bluez/hci1")
    }

    @Test
    fun doesNotFallBackToPhysicalDuplicateWhenVirtualAdapterIsUnpowered() {
        val adapters = listOf(
            BluezAdapterCandidate("/org/bluez/hci0", "00:11:22:33:44:55", powered = true),
            BluezAdapterCandidate("/org/bluez/hci1", "00:11:22:33:44:55", powered = false),
        )

        assertTrue(selectAutomaticBluezAdapter(adapters) == "/org/bluez/hci1")
    }
}
