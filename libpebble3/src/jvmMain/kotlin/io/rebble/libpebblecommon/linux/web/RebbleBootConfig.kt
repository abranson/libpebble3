package io.rebble.libpebblecommon.linux.web

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The account's Rebble boot config — the same thing the mobile apps fetch after login. It carries
 * the per-account service URLs and tokens: the ASR voice endpoints, the users/me link (for the
 * account/dev token), and the locker endpoint (for per-app timeline tokens). Fetched once and
 * cached; every Rebble-backed daemon feature shares this one provider.
 */
class RebbleBootConfigProvider private constructor(
    private val token: () -> String?,
    private val nowMillis: () -> Long,
    private val fetchOverride: (suspend (String) -> RebbleBootConfig?)?,
) {
    constructor(token: () -> String?) : this(token, System::currentTimeMillis, null)

    private val logger = Logger.withTag("RebbleBootConfig")

    // Explicit engine: HttpClient()'s ServiceLoader discovery is fragile under native image.
    private val httpClient = newRebbleHttpClient()
    private val json = Json { ignoreUnknownKeys = true }

    private val mutex = Mutex()
    private var cached: RebbleBootConfig? = null
    private var cachedAt = 0L
    private var cachedToken: String? = null

    suspend fun get(): RebbleBootConfig? = mutex.withLock {
        val accessToken = token()?.takeIf { it.isNotEmpty() }
        if (accessToken == null) {
            clearCache()
            return null
        }
        if (cachedToken != accessToken) clearCache()

        val now = nowMillis()
        cached?.let { if (now - cachedAt < CACHE_TTL_MS) return it }
        val fetched = fetch(accessToken)
        if (token()?.takeIf { it.isNotEmpty() } != accessToken) {
            clearCache()
            return null
        }
        fetched ?: return cached // stale data is safe only for this token
        cached = fetched
        cachedAt = now
        cachedToken = accessToken
        fetched
    }

    private fun clearCache() {
        cached = null
        cachedAt = 0L
        cachedToken = null
    }

    private suspend fun fetch(accessToken: String): RebbleBootConfig? {
        fetchOverride?.let { return it(accessToken) }
        return try {
            val response = httpClient.get(BOOT_CONFIG_URL) {
                // boot.rebble.io reads the token from the query string (that's how the login
                // redirect delivered it — .../stage2/?access_token=…), not a bearer header; a
                // header-only request 400s. Also sent as a bearer, matching the mobile client.
                parameter("access_token", accessToken)
                bearerAuth(accessToken)
                parameter("locale", "en-US")
                parameter("app_version", APP_VERSION)
            }
            if (!response.status.isSuccess()) {
                logger.w { "boot config fetch returned ${response.status}" }
                return null
            }
            json.decodeFromString<RebbleBootConfig>(response.bodyAsText())
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w(e) { "boot config fetch failed: ${e.message}" }
            null
        }
    }

    companion object {
        internal fun forTest(
            token: () -> String?,
            nowMillis: () -> Long,
            fetch: suspend (String) -> RebbleBootConfig?,
        ): RebbleBootConfigProvider = RebbleBootConfigProvider(token, nowMillis, fetch)

        // The login redirect hands back boot.rebble.io/api/stage2 as the boot-config base; the
        // mobile app appends this platform/version path. Hardcoded rather than stored from login
        // because the base is stable — revisit if a future login points elsewhere.
        private const val BOOT_CONFIG_URL = "https://boot.rebble.io/api/stage2/android/v3/999"
        private const val APP_VERSION = "v9.9.9"
        private const val CACHE_TTL_MS = 24 * 60 * 60 * 1000L
    }
}

@Serializable
data class RebbleBootConfig(val config: Config) {
    @Serializable
    data class Config(
        val voice: Voice? = null,
        val links: Links? = null,
        val locker: Locker? = null,
    )

    @Serializable
    data class Voice(val languages: List<Language> = emptyList()) {
        @Serializable
        data class Language(
            val endpoint: String,
            @SerialName("four_char_locale") val fourCharLocale: String,
        )
    }

    @Serializable
    data class Links(
        @SerialName("authentication/me") val authenticationMe: String? = null,
        @SerialName("users/me") val usersMe: String? = null,
    )

    @Serializable
    data class Locker(
        @SerialName("get_endpoint") val getEndpoint: String? = null,
        @SerialName("remove_endpoint") val removeEndpoint: String? = null,
    )
}
