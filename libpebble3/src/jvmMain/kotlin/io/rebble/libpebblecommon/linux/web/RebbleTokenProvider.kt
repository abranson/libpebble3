package io.rebble.libpebblecommon.linux.web

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.rebble.libpebblecommon.connection.TokenProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The account's stable "dev token", used by JsTokenUtil to derive Pebble.getAccountToken() —
 * md5(devToken + developerId/appUuid + salt). It's the Rebble account's user id, read from the
 * users/me link in boot config with the OAuth token, then cached. Returns null when not signed in
 * (getAccountToken then surfaces as an empty string, matching the reference).
 */
class RebbleTokenProvider private constructor(
    private val token: () -> String?,
    private val bootConfig: RebbleBootConfigProvider,
    private val fetchOverride: (suspend (String, String) -> String?)?,
) : TokenProvider {
    constructor(
        token: () -> String?,
        bootConfig: RebbleBootConfigProvider,
    ) : this(token, bootConfig, null)

    private val logger = Logger.withTag("RebbleTokenProvider")

    private val httpClient = newRebbleHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()
    private var cachedId: String? = null
    private var cachedToken: String? = null

    override suspend fun getDevToken(): String? = mutex.withLock {
        val accessToken = currentToken() ?: run {
            clearCache()
            return null
        }
        if (cachedToken != accessToken) clearCache()
        cachedId?.let { return it }

        val usersMeUrl = bootConfig.get()?.config?.links?.usersMe ?: run {
            logger.w { "no users/me link in boot config" }
            return null
        }
        if (currentToken() != accessToken) {
            clearCache()
            return null
        }
        val id = fetchUserId(usersMeUrl, accessToken) ?: return null
        if (currentToken() != accessToken) {
            clearCache()
            return null
        }
        cachedId = id
        cachedToken = accessToken
        id
    }

    private fun currentToken(): String? = token()?.takeIf { it.isNotEmpty() }

    private fun clearCache() {
        cachedId = null
        cachedToken = null
    }

    private suspend fun fetchUserId(url: String, accessToken: String): String? {
        fetchOverride?.let { return it(url, accessToken) }
        return try {
            val response = httpClient.get(url) { bearerAuth(accessToken) }
            if (!response.status.isSuccess()) {
                logger.w { "users/me returned ${response.status}" }
                null
            } else {
                json.decodeFromString<UsersMeResponse>(response.bodyAsText()).users.firstOrNull()?.id
            }
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w(e) { "users/me fetch failed: ${e.message}" }
            null
        }
    }

    companion object {
        internal fun forTest(
            token: () -> String?,
            bootConfig: RebbleBootConfigProvider,
            fetch: suspend (String, String) -> String?,
        ): RebbleTokenProvider = RebbleTokenProvider(token, bootConfig, fetch)
    }

    @Serializable
    private data class UsersMeResponse(val users: List<User> = emptyList()) {
        @Serializable
        data class User(val id: String)
    }
}
