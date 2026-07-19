package io.rebble.libpebblecommon.connection.bt.ble.bluez

import co.touchlab.kermit.Logger
import org.bluez.Agent1
import org.bluez.AgentManager1
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.types.UInt16
import org.freedesktop.dbus.types.UInt32

/**
 * A "NoInputNoOutput" BlueZ pairing agent that auto-accepts everything (Just Works). The watch
 * drives BLE pairing; we just need an agent present so BlueZ doesn't reject the request for lack
 * of one.
 */
internal class JustWorksAgent(private val path: String) : Agent1 {
    override fun getObjectPath(): String = path

    override fun Release() {}
    override fun RequestPinCode(device: DBusPath): String = "0000"
    override fun DisplayPinCode(device: DBusPath, pincode: String) {}
    override fun RequestPasskey(device: DBusPath): UInt32 = UInt32(0)
    override fun DisplayPasskey(device: DBusPath, passkey: UInt32, entered: UInt16) {}
    override fun RequestConfirmation(device: DBusPath, passkey: UInt32) {} // accept
    override fun RequestAuthorization(device: DBusPath) {} // accept
    override fun AuthorizeService(device: DBusPath, uuid: String) {} // accept
    override fun Cancel() {}
}

internal object BluezAgent {
    private val logger = Logger.withTag("BluezAgent")
    private const val AGENT_PATH = "/io/rebble/libpebble3/agent"

    @Volatile
    private var registered = false

    /** Export + register our auto-accept agent as the default agent. Idempotent. */
    @Synchronized
    fun ensureRegistered(): Boolean {
        if (registered) return true
        val conn = BluezManager.connection ?: return false
        return try {
            conn.exportObject(JustWorksAgent(AGENT_PATH))
            val agentManager = conn.getRemoteObject(
                "org.bluez", "/org/bluez", AgentManager1::class.java, false
            )
            agentManager.RegisterAgent(DBusPath(AGENT_PATH), "NoInputNoOutput")
            agentManager.RequestDefaultAgent(DBusPath(AGENT_PATH))
            registered = true
            logger.d("registered default pairing agent")
            true
        } catch (e: Throwable) {
            logger.e("failed to register pairing agent", e)
            false
        }
    }
}
