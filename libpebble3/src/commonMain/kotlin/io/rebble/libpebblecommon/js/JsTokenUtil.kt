package io.rebble.libpebblecommon.js

import io.ktor.utils.io.core.toByteArray
import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.PKJSToken
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.database.entity.LockerEntryDao
import io.rebble.libpebblecommon.services.WatchInfo
import okio.Buffer
import kotlin.uuid.Uuid

class JsTokenUtil(
    private val tokenProvider: TokenProvider,
    private val lockerEntryDao: LockerEntryDao,
    private val watchConfigFlow: WatchConfigFlow = WatchConfig().asFlow(),
    private val webServices: WebServices? = null,
): PKJSToken {
    companion object {
        private const val ACCOUNT_TOKEN_SALT =
            "MMIxeUT[G9/U#(7V67O^EuADSw,{\$C;B}`>|-nlrQCs|t|k=P_!*LETm,RKc,BG*'"
    }

    private fun md5Digest(input: String): String {
        val data = Buffer()
        data.write(input.toByteArray())
        return data.md5().hex().lowercase()
    }

    private suspend fun generateToken(uuid: Uuid, developerId: String?, seed: String): String {
        val unhashed = buildString {
            append(seed)
            append(developerId ?: uuid.toString().uppercase())
            append(ACCOUNT_TOKEN_SALT)
        }
        return md5Digest(unhashed)
    }

    suspend fun getWatchToken(uuid: Uuid, developerId: String?, watchInfo: WatchInfo): String {
        val serial = watchInfo.serial
        return generateToken(uuid, developerId, serial)
    }

    override suspend fun getAccountToken(appUuid: Uuid): String? {
        val session = webServices?.currentAccountSession()
        if (webServices?.usesAccountSessions() == true && session == null) return null
        if (session != null && !webServices.isCurrentAccountSession(session)) return null
        val devToken = tokenProvider.getDevToken() ?: return null
        if (session != null && !webServices.isCurrentAccountSession(session)) return null
        val devId = lockerEntryDao.getEntry(appUuid)?.appstoreData?.developerId
        if (session != null && !webServices.isCurrentAccountSession(session)) return null
        val token = generateToken(appUuid, devId ?: appUuid.toString().uppercase(), devToken)
        if (session != null && !webServices.isCurrentAccountSession(session)) return null
        return token
    }

    suspend fun getTimelineToken(uuid: Uuid): String? {
        // Locker apps carry their own user_token; anything else (e.g. sideloaded) falls back to a
        // sandbox token, then to the emulator dummy if remote-timeline emulation is on.
        val session = webServices?.currentAccountSession()
        val fallback = if (watchConfigFlow.value.emulateRemoteTimeline) {
            "emulated-dummy-token"
        } else {
            null
        }
        if (webServices?.usesAccountSessions() == true && session == null) return fallback
        if (session != null && !webServices.isCurrentAccountSession(session)) return fallback
        val realToken = lockerEntryDao.getEntry(uuid)?.appstoreData?.userToken
        if (session != null && !webServices.isCurrentAccountSession(session)) return fallback
        if (realToken != null) return realToken
        val sandbox = when {
            session != null -> webServices.getSandboxTimelineToken(uuid, session)
            else -> webServices?.getSandboxTimelineToken(uuid)
        }
        if (session != null && !webServices.isCurrentAccountSession(session)) return fallback
        return sandbox ?: fallback
    }

    suspend fun getSandboxTimelineToken(uuid: Uuid): String? =
        webServices?.getSandboxTimelineToken(uuid)
}
