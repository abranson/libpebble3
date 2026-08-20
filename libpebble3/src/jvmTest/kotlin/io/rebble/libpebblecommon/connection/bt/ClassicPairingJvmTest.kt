package io.rebble.libpebblecommon.connection.bt

import io.rebble.libpebblecommon.connection.asPebbleBtClassicIdentifier
import io.rebble.libpebblecommon.connection.isCanonicalBluetoothAddress
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class ClassicPairingJvmTest {
    @Test
    fun classicIdentifierNormalizesAddressesForBluezLookup() {
        val identifier = "d0:81:0a:d4:d7:cd".asPebbleBtClassicIdentifier()

        assertEquals("D0:81:0A:D4:D7:CD", identifier.asString)
        assertEquals(identifier, "D0:81:0A:D4:D7:CD".asPebbleBtClassicIdentifier())
    }

    @Test
    fun classicIdentifierRejectsMalformedAddresses() {
        assertFailsWith<IllegalArgumentException> {
            "D0:81:0A:D4:D7".asPebbleBtClassicIdentifier()
        }
        assertFailsWith<IllegalArgumentException> {
            "D0:81:0A:D4:D7:CG".asPebbleBtClassicIdentifier()
        }
    }

    @Test
    fun canonicalAddressValidationRequiresUppercaseMac() {
        assertTrue(isCanonicalBluetoothAddress("D0:81:0A:D4:D7:CD"))
        assertFalse(isCanonicalBluetoothAddress("d0:81:0a:d4:d7:cd"))
        assertFalse(isCanonicalBluetoothAddress("D0:81:0A:D4:D7"))
    }

    @Test
    fun classicBondStateUsesTheSharedPairingValues() {
        assertEquals(LEConstants.BOND_BONDED, classicBondState(true))
        assertEquals(LEConstants.BOND_NONE, classicBondState(false))
        assertEquals(LEConstants.BOND_NONE, classicBondState(null))
    }
}
