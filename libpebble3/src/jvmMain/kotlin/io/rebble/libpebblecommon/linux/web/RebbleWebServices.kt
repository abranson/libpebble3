package io.rebble.libpebblecommon.linux.web

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.web.LockerModel
import io.rebble.libpebblecommon.web.LockerModelWrapper
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * WebServices backed by public endpoints; everything not implemented yet stays stubbed.
 * Firmware checks: Rebble's cohorts API for classic Pebble hardware (no auth needed; the
 * Rebble token from the UI's setOAuthToken is attached when present), GitHub releases of
 * coredevices/PebbleOS for Core Devices hardware (cohorts doesn't know those; the official
 * app uses Memfault, which needs a private token).
 */
class RebbleWebServices(
    private val token: () -> String?,
    private val bootConfig: RebbleBootConfigProvider,
) : WebServices {
    private val logger = Logger.withTag("RebbleWebServices")

    // Explicit engine: HttpClient()'s ServiceLoader discovery is fragile under native image.
    private val httpClient = HttpClient(CIO)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The user's Rebble locker (their installed apps), whose entries carry the per-app timeline
     * `user_token` that backs Pebble.getTimelineToken(). The endpoint comes from boot config;
     * driven on demand by requestLockerSync(), not automatically. Returns null (leaving timeline
     * tokens on the emulator fallback) if not signed in or the fetch/parse fails.
     */
    override suspend fun fetchLocker(): LockerModelWrapper? {
        val url = bootConfig.get()?.config?.locker?.getEndpoint ?: run {
            logger.d { "no locker endpoint in boot config" }
            return null
        }
        val bearer = token()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val response = httpClient.get(url) { bearerAuth(bearer) }
            if (!response.status.isSuccess()) {
                logger.w { "locker fetch returned ${response.status}" }
                return null
            }
            val model = json.decodeFromString<LockerModel>(response.bodyAsText())
            LockerModelWrapper(locker = model, failedToFetchUuids = emptySet())
        } catch (e: Exception) {
            logger.w(e) { "locker fetch failed: ${e.message}" }
            null
        }
    }
    override suspend fun removeFromLocker(id: Uuid): Boolean = false

    /**
     * Timeline token for an app not in the locker (rockpool installs by sideloading the pbw, so
     * store apps grabbed there aren't locker members). Rebble's timeline-sync mints a sandbox
     * token per app, keyed on the account's bearer — same endpoint the old C++ rockpool used.
     */
    override suspend fun getSandboxTimelineToken(uuid: Uuid): String? {
        val bearer = token()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            val response = httpClient.get("$SANDBOX_TOKEN_URL/$uuid") { bearerAuth(bearer) }
            if (!response.status.isSuccess()) {
                logger.w { "sandbox token for $uuid returned ${response.status}" }
                return null
            }
            json.decodeFromString<SandboxTokenResponse>(response.bodyAsText()).token
        } catch (e: Exception) {
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
        val response = try {
            httpClient.get("$PEBBLEOS_RELEASES_URL/latest")
        } catch (e: Exception) {
            logger.w { "GitHub release request failed: ${e.message}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }
        if (!response.status.isSuccess()) {
            logger.w { "GitHub release request failed: ${response.status}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }
        val release = try {
            json.decodeFromString<GithubRelease>(response.bodyAsText())
        } catch (e: Exception) {
            logger.w { "couldn't parse GitHub release: ${e.message}" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("Failed to check for PebbleOS update")
        }
        // The slot-less pbz carries manifests for both slots; FirmwareUpdater picks the one
        // matching the watch's inactive slot.
        val assetName = "normal_${watch.platform.revision}_${release.tagName}.pbz"
        val asset = release.assets.firstOrNull { it.name == assetName } ?: run {
            logger.w { "release ${release.tagName} has no $assetName" }
            return FirmwareUpdateCheckResult.UpdateCheckFailed("No firmware found for ${watch.platform.revision}")
        }
        val latest = FirmwareVersion.from(
            tag = release.tagName,
            isRecovery = false,
            gitHash = "",
            timestamp = runCatching { Instant.parse(release.publishedAt) }
                .getOrDefault(Instant.fromEpochSeconds(0)),
            isDualSlot = false,
            isSlot0 = false,
        ) ?: return FirmwareUpdateCheckResult.UpdateCheckFailed("Couldn't parse firmware version")
        return result(watch, latest, asset.url, release.body.orEmpty())
    }

    /**
     * Exercises the full HTTPS + JSON path (ktor CIO sockets, TLS, serialization) so the
     * native-image tracing agent records its reflective internals — a real firmware check
     * needs a connected watch, which the tracing container doesn't have.
     */
    suspend fun selfTest() {
        val response = httpClient.get("$PEBBLEOS_RELEASES_URL/latest")
        val release = json.decodeFromString<GithubRelease>(response.bodyAsText())
        logger.i { "selfTest: ${response.status}, latest PebbleOS ${release.tagName}" }
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

    private fun FirmwareVersion.numeric(): Int = patch + minor * 1_000 + major * 1_000_000

    companion object {
        private const val COHORTS_URL = "https://cohorts.rebble.io"
        private const val PEBBLEOS_RELEASES_URL =
            "https://api.github.com/repos/coredevices/PebbleOS/releases"

        // Sent as the app version; cohorts rejects requests without plausible values.
        private const val APP_VERSION = "4.4.2"

        private const val SANDBOX_TOKEN_URL = "https://timeline-sync.rebble.io/v1/tokens/sandbox"
    }
}

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
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private data class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
)
