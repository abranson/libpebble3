package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.connection.bt.ble.bluez.LinuxBluezDevice
import io.rebble.libpebblecommon.database.entity.TransportType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BondedWatchSeederJvmTest {
    private fun device(
        address: String,
        name: String?,
        uuids: List<String>,
        bluetoothClass: Int? = null,
        paired: Boolean = true,
    ) = LinuxBluezDevice(
        path = "/org/bluez/hci1/dev_${address.replace(':', '_')}",
        address = address,
        name = name,
        paired = paired,
        uuids = uuids,
        bluetoothClass = bluetoothClass,
    )

    @Test
    fun classifiesThePebbleClassicServiceBeforeGenericDualModeMetadata() {
        assertEquals(
            TransportType.BluetoothClassic,
            classifyJvmBondedPebble(
                name = "Pebble Time D7CD",
                uuids = listOf(
                    "0000FED9-0000-1000-8000-00805F9B34FB",
                    "00000000-DECA-FADE-DECA-DEAFDECACAFF",
                ),
                bluetoothClass = 0x5a0204,
            ),
        )
    }

    @Test
    fun classifiesLeOnlyAndClassicFallbackDevices() {
        assertEquals(
            TransportType.BluetoothLe,
            classifyJvmBondedPebble(
                name = "Pebble Time Le D7CD",
                uuids = listOf("0000fed9-0000-1000-8000-00805f9b34fb"),
                bluetoothClass = 0,
            ),
        )
        assertEquals(
            TransportType.BluetoothClassic,
            classifyJvmBondedPebble(
                name = "Pebble D7CD",
                uuids = emptyList(),
                bluetoothClass = 0x5a0204,
            ),
        )
    }

    @Test
    fun rejectsUnrelatedAndAmbiguousBondedDevices() {
        assertNull(
            classifyJvmBondedPebble(
                name = "Pebble Index D7CD",
                uuids = listOf("00000000-deca-fade-deca-deafdecacaff"),
                bluetoothClass = 0x5a0204,
            ),
        )
        assertNull(
            classifyJvmBondedPebble(
                name = "Pebble D7CD",
                uuids = emptyList(),
                bluetoothClass = 0,
            ),
        )
    }

    @Test
    fun mergesSeparateLeAndClassicIdentitiesDeterministically() {
        val le = device(
            address = "D0:81:0A:D4:D7:CD",
            name = "Pebble Time Le D7CD",
            uuids = listOf("0000fed9-0000-1000-8000-00805f9b34fb"),
        )
        val classic = device(
            address = "11:22:33:44:55:66",
            name = "Pebble Time D7CD",
            uuids = listOf("00000000-deca-fade-deca-deafdecacaff"),
            bluetoothClass = 0x5a0204,
        )

        val expected = listOf(
            BondedWatchCandidate(
                identity = "name:pebble time d7cd",
                name = "Pebble Time D7CD",
                addresses = mapOf(
                    TransportType.BluetoothLe to "D0:81:0A:D4:D7:CD",
                    TransportType.BluetoothClassic to "11:22:33:44:55:66",
                ),
                aliases = 1,
            ),
        )
        assertEquals(expected, planJvmBondedWatchCandidates(listOf(le, classic)))
        assertEquals(expected, planJvmBondedWatchCandidates(listOf(classic, le)))
    }

    @Test
    fun leServiceDoesNotBecomeClassicFromMisreportedDeviceClass() {
        val candidate = planJvmBondedWatchCandidates(
            listOf(
                device(
                    address = "D0:81:0A:D4:D7:CD",
                    name = "Pebble Time D7CD",
                    uuids = listOf("0000fed9-0000-1000-8000-00805f9b34fb"),
                    bluetoothClass = 0x5a0204,
                ),
            ),
        ).single()

        assertEquals(
            mapOf(TransportType.BluetoothLe to "D0:81:0A:D4:D7:CD"),
            candidate.addresses,
        )
    }

    @Test
    fun ignoresUnpairedMalformedAndUnidentifiableSnapshots() {
        val devices = listOf(
            device(
                address = "D0:81:0A:D4:D7:CD",
                name = "Pebble Time D7CD",
                uuids = listOf("0000fed9-0000-1000-8000-00805f9b34fb"),
                paired = false,
            ),
            device(
                address = "not-an-address",
                name = "Pebble Time D7CD",
                uuids = listOf("0000fed9-0000-1000-8000-00805f9b34fb"),
            ),
            device(
                address = "11:22:33:44:55:66",
                name = "Headphones",
                uuids = listOf("00000000-deca-fade-deca-deafdecacaff"),
            ),
        )

        assertEquals(emptyList(), planJvmBondedWatchCandidates(devices))
    }

    @Test
    fun removalResolvesBothAliasesFromEitherPrimaryDeterministically() {
        val le = device(
            address = "D0:81:0A:D4:D7:CD",
            name = "Pebble Time Le D7CD",
            uuids = listOf("0000fed9-0000-1000-8000-00805f9b34fb"),
        )
        val classic = device(
            address = "11:22:33:44:55:66",
            name = "Pebble Time D7CD",
            uuids = listOf("00000000-deca-fade-deca-deafdecacaff"),
        )
        val expected = listOf("11:22:33:44:55:66", "D0:81:0A:D4:D7:CD")

        assertEquals(
            expected,
            planJvmBondedWatchRemoval(listOf(le, classic), classic.name!!, classic.address),
        )
        assertEquals(
            expected,
            planJvmBondedWatchRemoval(listOf(classic, le), le.name!!, le.address),
        )
    }

    @Test
    fun removalExcludesUnpairedMalformedAndUnrelatedDevices() {
        val primary = "11:22:33:44:55:66"
        val devices = listOf(
            device(
                address = "D0:81:0A:D4:D7:CD",
                name = "Pebble Time Le D7CD",
                uuids = emptyList(),
            ),
            device(
                address = "22:33:44:55:66:77",
                name = "Pebble Time A1B2",
                uuids = listOf("00000000-deca-fade-deca-deafdecacaff"),
            ),
            device(
                address = "33:44:55:66:77:88",
                name = "Pebble Time D7CD",
                uuids = listOf("00000000-deca-fade-deca-deafdecacaff"),
                paired = false,
            ),
            device(
                address = "not-an-address",
                name = "Pebble Time D7CD",
                uuids = emptyList(),
            ),
            device(
                address = "44:55:66:77:88:99",
                name = "Headphones D7CD",
                uuids = emptyList(),
            ),
            device(
                address = "55:66:77:88:99:AA",
                name = "Pebble Time D7CD",
                uuids = emptyList(),
                bluetoothClass = null,
            ),
        )

        assertEquals(
            listOf(primary, "D0:81:0A:D4:D7:CD"),
            planJvmBondedWatchRemoval(devices, "  PEBBLE   TIME D7CD  ", primary.lowercase()),
        )
    }

    @Test
    fun removalKeepsOneAnchorForACombinedOrAlreadyMissingDevice() {
        val address = "11:22:33:44:55:66"
        val combined = device(
            address = address,
            name = "Pebble Time D7CD",
            uuids = listOf(
                "0000fed9-0000-1000-8000-00805f9b34fb",
                "00000000-deca-fade-deca-deafdecacaff",
            ),
        )

        assertEquals(
            listOf(address),
            planJvmBondedWatchRemoval(listOf(combined), combined.name!!, address),
        )
        assertEquals(
            listOf(address),
            planJvmBondedWatchRemoval(emptyList(), "Renamed watch", address),
        )
        assertEquals(
            emptyList(),
            planJvmBondedWatchRemoval(emptyList(), "Pebble Time D7CD", "invalid"),
        )
    }
}
