package io.rebble.libpebblecommon.linux.web

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Authenticated Rebble account details obtained from the boot-config identity endpoint. */
data class RebbleAccountIdentity(
    val name: String,
    val email: String?,
)

/**
 * Fetches the identity for the current OAuth credential without retaining it across account
 * changes. A missing or unusable identity is represented by null.
 */
class RebbleAccountIdentityProvider private constructor(
    private val token: () -> String?,
    private val bootConfig: RebbleBootConfigProvider,
    private val fetchOverride: (suspend (String, String) -> IdentityResponse)?,
) {
    constructor(
        token: () -> String?,
        bootConfig: RebbleBootConfigProvider,
    ) : this(token, bootConfig, null)

    private val logger = Logger.withTag("RebbleAccountIdentity")
    private val httpClient = newRebbleHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(): RebbleAccountIdentity? {
        val accessToken = currentToken() ?: return null
        val identityUrl = bootConfig.get()?.config?.links?.authenticationMe ?: return null
        if (currentToken() != accessToken) return null

        val response = try {
            fetchOverride?.invoke(identityUrl, accessToken) ?: fetch(identityUrl, accessToken)
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w { "account identity fetch failed" }
            return null
        }
        if (currentToken() != accessToken || !response.isSuccess()) return null

        return try {
            val identity = json.decodeFromString<IdentityPayload>(response.body)
            if (currentToken() != accessToken) return null
            identity.name?.takeIf(String::isNotBlank)?.let { name ->
                RebbleAccountIdentity(name = name, email = identity.email?.takeIf(String::isNotBlank))
            }
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w { "account identity response was invalid" }
            null
        }
    }

    private fun currentToken(): String? = token()?.takeIf(String::isNotBlank)

    private suspend fun fetch(url: String, accessToken: String): IdentityResponse {
        val response = httpClient.get(url) { bearerAuth(accessToken) }
        return IdentityResponse(response.status.value, response.bodyAsText())
    }

    companion object {
        internal fun forTest(
            token: () -> String?,
            bootConfig: RebbleBootConfigProvider,
            fetch: suspend (String, String) -> IdentityResponse,
        ): RebbleAccountIdentityProvider = RebbleAccountIdentityProvider(token, bootConfig, fetch)
    }

    @Serializable
    private data class IdentityPayload(
        val name: String? = null,
        val email: String? = null,
    )
}

internal data class IdentityResponse(val status: Int, val body: String) {
    fun isSuccess(): Boolean = status in 200..299
}
