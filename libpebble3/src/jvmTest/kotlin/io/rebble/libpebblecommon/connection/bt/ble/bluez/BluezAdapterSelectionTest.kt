package io.rebble.libpebblecommon.connection.bt.ble.bluez

import org.bluez.exceptions.BluezDoesNotExistException
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.types.Variant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
    fun buildsDevicePathOnSelectedAdapter() {
        assertTrue(
            bluezDevicePath("/org/bluez/hci1", "d0:81:0a:d4:d7:cd") ==
                "/org/bluez/hci1/dev_D0_81_0A_D4_D7_CD"
        )
        assertTrue(
            bluezDevicePath("/org/bluez/hci0", "D0:81:0A:D4:D7:CD") ==
                "/org/bluez/hci0/dev_D0_81_0A_D4_D7_CD"
        )
    }

    @Test
    fun rejectsUnsafeDevicePathInputs() {
        assertTrue(bluezDevicePath("/org/bluez/hci1/device", "D0:81:0A:D4:D7:CD") == null)
        assertTrue(bluezDevicePath("/org/bluez/hci1", "D0:81:0A:D4:D7") == null)
        assertTrue(bluezDevicePath("/org/bluez/hci1", "../../D0:81:0A:D4:D7:CD") == null)
    }

    @Test
    fun recognizesTypedWrappedAndVerifiedAlreadyAbsentErrors() {
        assertTrue(isBluezDoesNotExist(BluezDoesNotExistException("already absent")))
        assertTrue(
            isBluezDoesNotExist(
                IllegalStateException("wrapped", BluezDoesNotExistException("already absent")),
            ),
        )
        assertFalse(isBluezDoesNotExist(DBusExecutionException("other failure")))
        assertTrue(
            preparedBondRemovalSucceededAfterError(
                DBusExecutionException("undeclared remote error"),
                pairedAfterFailure = false,
            ),
        )
        assertFalse(
            preparedBondRemovalSucceededAfterError(
                DBusExecutionException("real failure"),
                pairedAfterFailure = true,
            ),
        )
        assertFalse(
            preparedBondRemovalSucceededAfterError(
                DBusExecutionException("verification unavailable"),
                pairedAfterFailure = null,
            ),
        )
    }

    @Test
    fun usesHci0WhenItIsTheOnlyAdapter() {
        val adapters = listOf(
            LinuxBluezAdapter("/org/bluez/hci0", "00:11:22:33:44:55", powered = true),
        )

        assertTrue(selectAutomaticBluezAdapter(adapters) == "/org/bluez/hci0")
    }

    @Test
    fun usesFirstPoweredAdapterByDefault() {
        val adapters = listOf(
            LinuxBluezAdapter("/org/bluez/hci0", "00:11:22:33:44:55", powered = true),
            LinuxBluezAdapter("/org/bluez/hci1", "00:11:22:33:44:55", powered = true),
        )

        assertTrue(selectAutomaticBluezAdapter(adapters) == "/org/bluez/hci0")
    }

    @Test
    fun skipsAnUnpoweredFirstAdapter() {
        val adapters = listOf(
            LinuxBluezAdapter("/org/bluez/hci0", "00:11:22:33:44:55", powered = false),
            LinuxBluezAdapter("/org/bluez/hci1", "00:11:22:33:44:55", powered = true),
        )

        assertTrue(selectAutomaticBluezAdapter(adapters) == "/org/bluez/hci1")
    }

    @Test
    fun parsesOnlyDeviceObjectsOwnedByTheSelectedAdapter() {
        val path = "/org/bluez/hci1/dev_D0_81_0A_D4_D7_CD"
        val properties = mapOf(
            "Address" to Variant("d0:81:0a:d4:d7:cd"),
            "Adapter" to Variant(DBusPath("/org/bluez/hci1")),
            "Name" to Variant("Pebble D7CD"),
            "Paired" to Variant(true),
            "UUIDs" to Variant(arrayOf("00000000-deca-fade-deca-deafdecacaff")),
            "Class" to Variant(0x5a0204),
        )

        val device = bluezDeviceFromManagedObject(path, "/org/bluez/hci1", properties)

        assertEquals("D0:81:0A:D4:D7:CD", device?.address)
        assertEquals("Pebble D7CD", device?.name)
        assertEquals(true, device?.paired)
        assertEquals(0x5a0204, device?.bluetoothClass)
        assertNull(bluezDeviceFromManagedObject(path, "/org/bluez/hci0", properties))
        assertNull(
            bluezDeviceFromManagedObject(
                path,
                "/org/bluez/hci1",
                properties + ("Adapter" to Variant(DBusPath("/org/bluez/hci0"))),
            )
        )
        assertNull(
            bluezDeviceFromManagedObject(
                path,
                "/org/bluez/hci1",
                properties - "Adapter",
            )
        )
    }
}
