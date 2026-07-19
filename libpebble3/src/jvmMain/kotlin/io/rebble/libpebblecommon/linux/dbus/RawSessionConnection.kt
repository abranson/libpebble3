package io.rebble.libpebblecommon.linux.dbus

import co.touchlab.kermit.Logger
import org.freedesktop.dbus.connections.transports.AbstractTransport
import org.freedesktop.dbus.connections.transports.TransportBuilder
import org.freedesktop.dbus.messages.Error
import org.freedesktop.dbus.messages.Message
import org.freedesktop.dbus.messages.MethodReturn
import org.freedesktop.dbus.types.UInt32

/**
 * Raw session-bus connection for the two things dbus-java's high-level connection can't do:
 * becoming a bus monitor (its dispatcher would try to reply to sniffed method calls, which the
 * bus kills monitors for), and calling methods on arbitrary interfaces without pre-declared
 * Java interface classes (needed for lipstick's free-form x-nemo remote actions).
 */
class RawSessionConnection private constructor(
    private val transport: AbstractTransport,
) : AutoCloseable {
    private val factory = transport.messageFactory

    /** Fire-and-forget method call. */
    fun call(
        destination: String,
        path: String,
        iface: String,
        member: String,
        signature: String? = null,
        vararg args: Any,
    ) {
        val call = factory.createMethodCall(
            destination, path, iface, member, NO_REPLY_EXPECTED, signature, *args
        )
        transport.writeMessage(call)
    }

    /** Method call that waits for its reply. Returns the reply, or null on error reply. */
    fun callWithReply(
        destination: String,
        path: String,
        iface: String,
        member: String,
        signature: String? = null,
        vararg args: Any,
    ): MethodReturn? {
        val call = factory.createMethodCall(destination, path, iface, member, 0, signature, *args)
        transport.writeMessage(call)
        while (true) {
            val msg = transport.readMessage() ?: continue
            when {
                msg is MethodReturn && msg.replySerial == call.serial -> return msg
                msg is Error && msg.replySerial == call.serial -> {
                    logger.w { "$iface.$member failed: ${msg.name}" }
                    return null
                }
            }
        }
    }

    /**
     * Turn this connection into a read-only bus monitor for the given match rules. Falls back to
     * legacy eavesdrop match rules if the bus doesn't support org.freedesktop.DBus.Monitoring.
     * After this, only [read] may be used.
     */
    fun startMonitoring(rules: List<String>): Boolean {
        val reply = callWithReply(
            DBUS_SERVICE, DBUS_PATH, "org.freedesktop.DBus.Monitoring", "BecomeMonitor",
            "asu", rules.toTypedArray(), UInt32(0),
        )
        if (reply != null) return true
        logger.w { "BecomeMonitor unavailable, falling back to eavesdrop match rules" }
        return rules.all { rule ->
            callWithReply(
                DBUS_SERVICE, DBUS_PATH, DBUS_SERVICE, "AddMatch",
                "s", "$rule,eavesdrop='true'",
            ) != null
        }
    }

    /** Blocking read of the next message. */
    fun read(): Message? = transport.readMessage()

    override fun close() {
        runCatching { transport.close() }
    }

    companion object {
        private val logger = Logger.withTag("RawSessionConnection")
        private const val DBUS_SERVICE = "org.freedesktop.DBus"
        private const val DBUS_PATH = "/org/freedesktop/DBus"
        private const val NO_REPLY_EXPECTED: Byte = 0x01

        /** Connect + Hello. Returns null if there is no session bus. */
        fun connect(): RawSessionConnection? {
            val address = System.getenv("DBUS_SESSION_BUS_ADDRESS")
            if (address.isNullOrBlank()) {
                logger.w { "DBUS_SESSION_BUS_ADDRESS not set" }
                return null
            }
            return try {
                val transport = TransportBuilder.create(address).build()
                transport.connect()
                val conn = RawSessionConnection(transport)
                conn.callWithReply(DBUS_SERVICE, DBUS_PATH, DBUS_SERVICE, "Hello")
                    ?: run { conn.close(); return null }
                conn
            } catch (e: Exception) {
                logger.e("failed to connect to session bus", e)
                null
            }
        }
    }
}
