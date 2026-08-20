package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.WatchConfig
import io.rebble.libpebblecommon.asFlow
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.connection.WebAccountSession
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.database.dao.FakeLockerEntryDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryAppstoreData
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.web.LockerModelWrapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

class JsTokenUtilTest {
    @Test
    fun signedOutAccountCannotReadPersistedLockerToken() = runTest {
        val uuid = Uuid.random()
        var daoReads = 0
        val webServices = SessionWebServices(token = null)
        val util = JsTokenUtil(
            tokenProvider = NullTokenProvider,
            lockerEntryDao = FakeLockerEntryDao {
                daoReads += 1
                entry(uuid, "stale-token")
            },
            watchConfigFlow = WatchConfig(emulateRemoteTimeline = false).asFlow(),
            webServices = webServices,
        )

        assertEquals(null, util.getTimelineToken(uuid))
        assertEquals(0, daoReads)
        assertEquals(0, webServices.sandboxCalls)
    }

    @Test
    fun invalidatedSessionRejectsTokenReadAcrossAccountAba() = runTest {
        val uuid = Uuid.random()
        val queryEntered = CompletableDeferred<Unit>()
        val releaseQuery = CompletableDeferred<Unit>()
        val webServices = SessionWebServices(token = "account-a")
        val util = JsTokenUtil(
            tokenProvider = NullTokenProvider,
            lockerEntryDao = FakeLockerEntryDao {
                queryEntered.complete(Unit)
                releaseQuery.await()
                entry(uuid, "stale-account-a-token")
            },
            watchConfigFlow = WatchConfig(emulateRemoteTimeline = false).asFlow(),
            webServices = webServices,
        )

        val token = async { util.getTimelineToken(uuid) }
        queryEntered.await()
        // The credential can become account A again before the read completes. The opaque web
        // generation still has to reject the token captured before that transition.
        webServices.invalidateAccountSessions()
        releaseQuery.complete(Unit)

        assertEquals(null, token.await())
        assertEquals(0, webServices.sandboxCalls)
    }

    private fun entry(uuid: Uuid, userToken: String) = LockerEntry(
        id = uuid,
        version = "1.0",
        title = "Test",
        type = "watchapp",
        developerName = "Test",
        configurable = false,
        pbwVersionCode = "1",
        appstoreData = LockerEntryAppstoreData(
            hearts = 0,
            developerId = "developer",
            timelineEnabled = true,
            removeLink = "",
            shareLink = "",
            pbwLink = "",
            userToken = userToken,
        ),
        platforms = emptyList(),
    )

    private data object NullTokenProvider : TokenProvider {
        override suspend fun getDevToken(): String? = null
    }

    private class SessionWebServices(var token: String?) : WebServices {
        private data class Session(val token: String, val generation: Long) : WebAccountSession

        private var generation = 0L
        var sandboxCalls = 0
            private set

        override fun usesAccountSessions(): Boolean = true

        override fun currentAccountSession(): WebAccountSession? =
            token?.let { Session(it, generation) }

        override fun isCurrentAccountSession(session: WebAccountSession): Boolean =
            session is Session && token == session.token && generation == session.generation

        override fun invalidateAccountSessions() {
            generation += 1
        }

        override suspend fun getSandboxTimelineToken(uuid: Uuid): String? {
            sandboxCalls += 1
            return "sandbox-token"
        }

        override suspend fun fetchLocker(): LockerModelWrapper? = null

        override suspend fun removeFromLocker(id: Uuid): Boolean = false

        override suspend fun checkForFirmwareUpdate(
            watch: WatchInfo,
            force: Boolean,
        ): FirmwareUpdateCheckResult = FirmwareUpdateCheckResult.FoundNoUpdate

        override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {}

        override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) {}
    }
}
