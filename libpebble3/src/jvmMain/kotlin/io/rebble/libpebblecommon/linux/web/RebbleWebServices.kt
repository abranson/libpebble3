package io.rebble.libpebblecommon.linux.web

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.Url
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.WebAccountSession
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.web.LockerModel
import io.rebble.libpebblecommon.web.LockerModelWrapper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicLong
import java.net.URI
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * WebServices backed by public endpoints; everything not implemented yet stays stubbed.
 * Firmware checks: Rebble's cohorts API for classic Pebble hardware (no auth needed; the
 * Rebble token from the UI's setOAuthToken is attached when present), GitHub releases of
 * coredevices/PebbleOS for Core Devices hardware (cohorts doesn't know those; the official
 * app uses Memfault, which needs a private token).
 */
class RebbleWebServices private constructor(
    private val token: () -> String?,
    private val bootConfig: RebbleBootConfigProvider,
    private val httpClientProvider: Lazy<HttpClient>,
) : WebServices {
    constructor(
        token: () -> String?,
        bootConfig: RebbleBootConfigProvider,
    ) : this(token, bootConfig, lazy(::newRebbleHttpClient))

    internal constructor(
        token: () -> String?,
        bootConfig: RebbleBootConfigProvider,
        httpClient: HttpClient,
    ) : this(token, bootConfig, lazyOf(httpClient))

    private val logger = Logger.withTag("RebbleWebServices")

    // Explicit engine: HttpClient()'s ServiceLoader discovery is fragile under native image.
    private val httpClient by httpClientProvider
    private val json = Json { ignoreUnknownKeys = true }
    private val accountGeneration = AtomicLong(0)

    override fun usesAccountSessions(): Boolean = true

    override fun currentAccountSession(): WebAccountSession? =
        accountToken()?.let { RebbleAccountSession(it, accountGeneration.get()) }

    override fun isCurrentAccountSession(session: WebAccountSession): Boolean =
        session is RebbleAccountSession &&
            accountToken() == session.credential &&
            accountGeneration.get() == session.generation

    override fun invalidateAccountSessions() {
        accountGeneration.incrementAndGet()
    }

    /**
     * The user's Rebble locker (their installed apps), whose entries carry the per-app timeline
     * `user_token` that backs Pebble.getTimelineToken(). The endpoint comes from boot config;
     * driven on demand by requestLockerSync(), not automatically. Returns null (leaving timeline
     * tokens on the emulator fallback) if not signed in or the fetch/parse fails.
     */
    override suspend fun fetchLocker(): LockerModelWrapper? =
        currentAccountSession()?.let { fetchLocker(it) }

    override suspend fun fetchLocker(session: WebAccountSession): LockerModelWrapper? {
        if (session !is RebbleAccountSession) return null
        val bearer = session.credential
        if (!isCurrentAccountSession(session)) return null
        val config = bootConfig.get()
        if (!isCurrentAccountSession(session)) return null
        val url = config?.config?.locker?.getEndpoint ?: run {
            logger.d { "no locker endpoint in boot config" }
            return null
        }
        return try {
            val origin = Url(url)
            var pageUrl = url
            val visited = mutableSetOf<String>()
            val applications = mutableListOf<io.rebble.libpebblecommon.web.LockerEntry>()
            while (true) {
                // Never apply a partial listing, loop forever, or forward the bearer to a
                // different origin supplied by a pagination link.
                val page = Url(pageUrl)
                require(page.protocol == origin.protocol && page.host == origin.host && page.port == origin.port)
                require(visited.size < MAX_LOCKER_PAGES && visited.add(page.toString()))
                if (!isCurrentAccountSession(session)) return null
                val response = httpClient.get(page) { bearerAuth(bearer) }
                if (!isCurrentAccountSession(session)) return null
                if (!response.status.isSuccess()) {
                    logger.w { "locker fetch returned ${response.status}" }
                    return null
                }
                val model = json.decodeFromString<LockerModel>(response.bodyAsText())
                if (!isCurrentAccountSession(session)) return null
                applications += model.applications
                val next = model.nextPageURL?.takeIf(String::isNotBlank) ?: break
                pageUrl = if (next.startsWith("?")) {
                    // URI.resolve drops the last path segment for a query-only reference.
                    pageUrl.substringBefore('#').substringBefore('?') + next
                } else {
                    URI(pageUrl).resolve(next).toString()
                }
            }
            LockerModelWrapper(
                locker = LockerModel(applications.distinctBy { it.uuid.lowercase() }),
                failedToFetchUuids = emptySet(),
            )
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w(e) { "locker fetch failed: ${e.message}" }
            null
        }
    }

    override suspend fun removeFromLocker(id: Uuid): Boolean =
        currentAccountSession()?.let { removeFromLocker(id, it) } ?: false

    override suspend fun removeFromLocker(id: Uuid, session: WebAccountSession): Boolean {
        if (session !is RebbleAccountSession) return false
        val bearer = session.credential
        if (!isCurrentAccountSession(session)) return false
        val config = bootConfig.get()
        if (!isCurrentAccountSession(session)) return false
        val template = config?.config?.locker?.removeEndpoint ?: run {
            logger.d { "no locker removal endpoint in boot config" }
            return false
        }
        val url = lockerRemovalUrl(template, id) ?: run {
            logger.w { "locker removal endpoint has an invalid UUID placeholder" }
            return false
        }
        return try {
            val response = httpClient.delete(url) { bearerAuth(bearer) }
            if (!isCurrentAccountSession(session)) return false
            if (!response.status.isSuccess()) {
                logger.w { "locker removal returned ${response.status}" }
                false
            } else {
                true
            }
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w(e) { "locker removal failed: ${e.message}" }
            false
        }
    }

    /**
     * Timeline token for an app not in the locker. Rebble's timeline-sync
     * mints a sandbox token per app, keyed on the account bearer.
     */
    override suspend fun getSandboxTimelineToken(uuid: Uuid): String? {
        val session = currentAccountSession() ?: return null
        return getSandboxTimelineToken(uuid, session)
    }

    override suspend fun getSandboxTimelineToken(
        uuid: Uuid,
        session: WebAccountSession,
    ): String? {
        if (session !is RebbleAccountSession) return null
        val bearer = session.credential
        if (!isCurrentAccountSession(session)) return null
        return try {
            val response = httpClient.get("$SANDBOX_TOKEN_URL/$uuid") { bearerAuth(bearer) }
            if (!isCurrentAccountSession(session)) return null
            if (!response.status.isSuccess()) {
                logger.w { "sandbox token for $uuid returned ${response.status}" }
                return null
            }
            val body = response.bodyAsText()
            if (!isCurrentAccountSession(session)) return null
            val sandboxToken = json.decodeFromString<SandboxTokenResponse>(body).token
            if (!isCurrentAccountSession(session)) return null
            sandboxToken
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w(e) { "sandbox token fetch failed: ${e.message}" }
            null
        }
    }

    override fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo) {}
    override fun uploadAnalyticsHeartbeat(payload: ByteArray, watchInfo: WatchInfo) {}

    override suspend fun checkForFirmwareUpdate(watch: WatchInfo, force: Boolean): FirmwareUpdateCheckResult =
        if (watch.platform.isCoreDevicesHardware()) {
            checkGithubReleases(watch)
        } else {
            checkCohorts(watch)
        }

    private suspend fun checkCohorts(watch: WatchInfo): FirmwareUpdateCheckResult {
        val response = try {
            httpClient.get("$COHORTS_URL/cohort") {
                token()?.takeIf { it.isNotEmpty() }?.let { bearerAuth(it) }
                parameter("select", "fw")
                parameter("hardware", watch.platform.revision)
                parameter("mobilePlatform", "android")
                parameter("mobileVersion", APP_VERSION)
                parameter("mobileHardware", "android")
                parameter("pebbleAppVersion", APP_VERSION)
            }
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w { "cohorts request failed: ${e.message}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }
        if (!response.status.isSuccess()) {
            logger.w { "cohorts request failed: ${response.status}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }
        val normalFw = try {
            json.decodeFromString<CohortsResponse>(response.bodyAsText()).fw?.normal
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w { "couldn't parse cohorts response: ${e.message}" }
            null
        } ?: return FirmwareUpdateCheckResult.UpdateCheckFailed("No firmware found for ${watch.platform.revision}")
        val latest = FirmwareVersion.from(
            tag = normalFw.friendlyVersion,
            isRecovery = false,
            gitHash = "",
            timestamp = Instant.fromEpochSeconds(normalFw.timestamp),
            isDualSlot = false,
            isSlot0 = false,
        ) ?: return FirmwareUpdateCheckResult.UpdateCheckFailed("Couldn't parse firmware version")
        return result(watch, latest, normalFw.url, normalFw.notes.orEmpty())
    }

    private suspend fun checkGithubReleases(watch: WatchInfo): FirmwareUpdateCheckResult {
        val candidate = try {
            fetchGithubFirmware(watch.platform.revision)
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w { "GitHub release check failed: ${e.message}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        } ?: return FirmwareUpdateCheckResult.UpdateCheckFailed("No firmware found for ${watch.platform.revision}")
        return result(watch, candidate.version, candidate.url, candidate.notes)
    }

    private suspend fun fetchGithubFirmware(revision: String): FirmwareUpdateCheckResult.FoundUpdate? =
        latestGithubFirmware(revision) { page ->
            val response = httpClient.get(PEBBLEOS_RELEASES_URL) {
                parameter("per_page", GITHUB_RELEASES_PAGE_SIZE)
                parameter("page", page)
            }
            check(response.status.isSuccess()) { "GitHub release request failed: ${response.status}" }
            response.bodyAsText()
        }

    /**
     * Exercises the full HTTPS + JSON path (ktor CIO sockets, TLS, serialization) so the
     * native-image tracing agent records its reflective internals — a real firmware check
     * needs a connected watch, which the tracing container doesn't have.
     */
    suspend fun selfTest() {
        val candidate = checkNotNull(fetchGithubFirmware("asterix"))
        logger.i { "selfTest: latest PebbleOS ${candidate.version.stringVersion}" }
    }

    private fun result(
        watch: WatchInfo,
        latest: FirmwareVersion,
        url: String,
        notes: String,
    ): FirmwareUpdateCheckResult {
        logger.d { "latest=${latest.stringVersion} running=${watch.runningFwVersion.stringVersion}" }
        // Compare version numbers only: FirmwareVersion.compareTo falls back to timestamps
        // for equal versions, and the release's publish time never matches the firmware's
        // build time — same-version watches would see a phantom update forever.
        val newer = latest.numeric() > watch.runningFwVersion.numeric()
        return if (watch.runningFwVersion.isRecovery || newer) {
            FirmwareUpdateCheckResult.FoundUpdate(version = latest, url = url, notes = notes)
        } else {
            FirmwareUpdateCheckResult.FoundNoUpdate
        }
    }

    private fun accountToken(): String? = token()?.takeIf(String::isNotBlank)

    companion object {
        private const val MAX_LOCKER_PAGES = 1_000
        private const val COHORTS_URL = "https://cohorts.rebble.io"
        private const val PEBBLEOS_RELEASES_URL =
            "https://api.github.com/repos/coredevices/PebbleOS/releases"

        // Sent as the app version; cohorts rejects requests without plausible values.
        private const val APP_VERSION = "4.4.2"

        private const val SANDBOX_TOKEN_URL = "https://timeline-sync.rebble.io/v1/tokens/sandbox"
    }
}

private class RebbleAccountSession(
    val credential: String,
    val generation: Long,
) : WebAccountSession {
    override fun toString(): String = "WebAccountSession(redacted)"
}

internal fun lockerRemovalUrl(template: String, id: Uuid): String? {
    val first = template.indexOf(LOCKER_UUID_PLACEHOLDER)
    if (first < 0 || first != template.lastIndexOf(LOCKER_UUID_PLACEHOLDER)) return null
    return template.replace(LOCKER_UUID_PLACEHOLDER, id.toString())
}

private const val LOCKER_UUID_PLACEHOLDER = "\$\$app_uuid\$\$"

@Serializable
private data class SandboxTokenResponse(val token: String? = null)

/** Hardware whose firmware lives in coredevices/PebbleOS releases, not Rebble cohorts. */
private fun WatchHardwarePlatform.isCoreDevicesHardware(): Boolean =
    revision.startsWith("asterix") || revision.startsWith("obelix") ||
        revision.startsWith("getafix")

@Serializable
private data class CohortsResponse(val fw: CohortsFirmwareType? = null)

@Serializable
private data class CohortsFirmwareType(val normal: CohortsFirmware? = null)

@Serializable
private data class CohortsFirmware(
    val url: String,
    val friendlyVersion: String,
    val timestamp: Long,
    val notes: String? = null,
)

@Serializable
private data class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    @SerialName("published_at") val publishedAt: String = "",
    val body: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)

private const val GITHUB_RELEASES_PAGE_SIZE = 100
private const val MAX_GITHUB_RELEASE_PAGES = 10

private fun FirmwareVersion.numeric(): Int = patch + minor * 1_000 + major * 1_000_000

internal suspend fun latestGithubFirmware(
    revision: String,
    fetchPage: suspend (Int) -> String,
): FirmwareUpdateCheckResult.FoundUpdate? {
    val json = Json { ignoreUnknownKeys = true }
    var latest: FirmwareUpdateCheckResult.FoundUpdate? = null
    // GitHub does not order releases by firmware version: a maintenance
    // release on an older branch can replace /latest. Inspect every page before
    // returning a candidate, rather than treating a partial check as definitive.
    for (page in 1..MAX_GITHUB_RELEASE_PAGES) {
        val releases = json.decodeFromString<List<GithubRelease>>(fetchPage(page))
        for (release in releases) {
            if (release.draft || release.prerelease) continue
            // The slot-less bundle contains both slots; the updater selects the inactive one.
            val assetName = "normal_${revision}_${release.tagName}.pbz"
            val asset = release.assets.firstOrNull { it.name == assetName } ?: continue
            val version = FirmwareVersion.from(
                tag = release.tagName,
                isRecovery = false,
                gitHash = "",
                timestamp = runCatching { Instant.parse(release.publishedAt) }
                    .getOrDefault(Instant.fromEpochSeconds(0)),
                isDualSlot = false,
                isSlot0 = false,
            ) ?: continue
            if (latest == null || version.numeric() > latest.version.numeric()) {
                latest = FirmwareUpdateCheckResult.FoundUpdate(
                    version = version,
                    url = asset.url,
                    notes = release.body.orEmpty(),
                )
            }
        }
        if (releases.size < GITHUB_RELEASES_PAGE_SIZE) return latest
    }
    error("GitHub release pagination limit exceeded")
}
