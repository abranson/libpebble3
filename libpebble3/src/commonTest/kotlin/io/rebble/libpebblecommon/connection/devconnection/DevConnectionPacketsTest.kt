package io.rebble.libpebblecommon.connection.devconnection

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal class DevConnectionPacketsTest {
    @Test
    fun phoneInfoRequestIsRecognizedAndHasLegacyReply() {
        assertEquals(ClientMessageType.PhoneInfo, ClientMessageType.fromValue(0x06))
        assertContentEquals(
            byteArrayOf(ServerMessageType.PhoneInfo.value) + PhoneInfoMessage.INFO.encodeToByteArray(),
            PhoneInfoMessage().toBytes().toByteArray()
        )
    }

    @Test
    fun phoneAppLogUsesUtf8ByteLength() {
        val message = "Pebble \uD83E\uDEA8"

        assertContentEquals(
            byteArrayOf(ServerMessageType.PhoneAppLog.value) + message.encodeToByteArray(),
            PhoneAppLogMessage(message).toBytes().toByteArray()
        )
    }
}
