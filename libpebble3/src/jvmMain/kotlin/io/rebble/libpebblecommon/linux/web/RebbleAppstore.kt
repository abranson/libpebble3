package io.rebble.libpebblecommon.linux.web

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.rebble.libpebblecommon.util.JvmPaths
import io.rebble.libpebblecommon.database.entity.LockerEntryAppstoreData
import io.rebble.libpebblecommon.disk.pbw.StorePbwMetadata
import io.rebble.libpebblecommon.web.LockerEntryPlatform
import kotlinx.io.files.Path
import java.io.File
import kotlin.uuid.Uuid

/**
 * Resolve a Rebble appstore ID to the latest release's PBW and download it.
 */
object RebbleAppstore {
    private val logger = Logger.withTag("RebbleAppstore")
    private val httpClient by lazy(::newRebbleHttpClient)
    private val json = Json { ignoreUnknownKeys = true }

    /** A downloaded pbw plus the app's UUID (needed to add it to the Rebble locker). */
    data class DownloadedApp(val path: Path, val uuid: String?, val storeMetadata: StorePbwMetadata)

    suspend fun downloadPbw(storeId: String): DownloadedApp? {
        val record = lookupApp(storeId) ?: return null
        val pbwUrl = record.latestRelease?.pbwFile ?: run {
            logger.w { "no pbw for store id $storeId" }
            return null
        }
        val dest = File(JvmPaths.cacheSubdir("appstore").toFile(), "$storeId.pbw")
        return try {
            val response = httpClient.get(pbwUrl) {
                timeout {
                    requestTimeoutMillis = PBW_REQUEST_TIMEOUT_MS
                    socketTimeoutMillis = PBW_SOCKET_TIMEOUT_MS
                }
            }
            if (!response.status.isSuccess()) {
                logger.w { "pbw download failed: ${response.status}" }
                return null
            }
            dest.outputStream().use { response.bodyAsChannel().copyTo(it) }
            logger.d { "downloaded $pbwUrl to $dest" }
            DownloadedApp(Path(dest.absolutePath), record.uuid, record.storeMetadata(storeId))
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w { "pbw download failed: ${e.message}" }
            null
        }
    }

    private suspend fun lookupApp(storeId: String): AppRecord? =
        try {
            val response = httpClient.get("$API_URL/apps/id/$storeId")
            if (!response.status.isSuccess()) {
                logger.w { "app lookup failed for $storeId: ${response.status}" }
                null
            } else {
                json.decodeFromString<AppsResponse>(response.bodyAsText()).data.firstOrNull()
            }
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w { "app lookup failed for $storeId: ${e.message}" }
            null
        }

    /** Read-only search: never adds the app to a remote account or downloads a replacement PBW. */
    suspend fun findStoreMetadata(uuid: Uuid): StorePbwMetadata? =
        try {
            val response = httpClient.post(SEARCH_URL) {
                header("X-Algolia-Application-Id", "7683OW76EQ")
                // Public search-only key for the Rebble app-store index.
                header("X-Algolia-API-Key", "252f4938082b8693a8a9fc0157d1d24f")
                contentType(ContentType.Application.Json)
                setBody(buildJsonObject {
                    put("query", uuid.toString())
                    put("hitsPerPage", 10)
                    put("analytics", false)
                }.toString())
            }
            if (response.status.isSuccess()) {
                val storeId = findRebbleStoreId(response.bodyAsText(), uuid)
                val metadata = storeId?.let { lookupApp(it)?.storeMetadata(it) }
                metadata?.takeIf { it.uuid == uuid }
            } else {
                logger.w { "store UUID lookup failed: ${response.status}" }
                null
            }
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w { "store UUID lookup failed: ${e.message}" }
            null
        }

    /**
     * Add an app to the user's Rebble locker, which is what assigns its per-user timeline
     * user_token. Sideloading a PBW alone does not make the app a locker
     * member, so Pebble.getTimelineToken() otherwise falls back to sandbox.
     * Best-effort: needs a signed-in token; returns false and logs if it can't.
     */
    suspend fun addToLocker(uuid: String, token: String?): Boolean {
        if (token.isNullOrEmpty()) return false
        return try {
            val response = httpClient.put("$API_URL/locker/$uuid") { bearerAuth(token) }
            if (!response.status.isSuccess()) {
                logger.w { "add-to-locker($uuid) returned ${response.status}" }
                false
            } else {
                logger.d { "added $uuid to the Rebble locker" }
                true
            }
        } catch (e: Exception) {
            e.rethrowCancellation()
            logger.w { "add-to-locker($uuid) failed: ${e.message}" }
            false
        }
    }

    private const val API_URL = "https://appstore-api.rebble.io/api/v1"
    private const val SEARCH_URL = "https://7683ow76eq-dsn.algolia.net/1/indexes/rebble-appstore-production/query"
    private const val PBW_REQUEST_TIMEOUT_MS = 2 * 60 * 1000L
    private const val PBW_SOCKET_TIMEOUT_MS = 30 * 1000L
}

internal fun findRebbleStoreId(body: String, uuid: Uuid): String? =
    Json { ignoreUnknownKeys = true }.decodeFromString<StoreSearchResponse>(body)
        .hits.firstOrNull { runCatching { Uuid.parse(it.uuid) }.getOrNull() == uuid }
        ?.id?.takeIf { it.isNotBlank() }

@Serializable
private data class StoreSearchResponse(val hits: List<StoreSearchHit> = emptyList())

@Serializable
private data class StoreSearchHit(val id: String = "", val uuid: String = "")

@Serializable
private data class AppsResponse(val data: List<AppRecord> = emptyList())

@Serializable
internal data class AppRecord(
    val uuid: String? = null,
    @SerialName("latest_release") val latestRelease: AppRelease? = null,
    val hearts: Int = 0,
    @SerialName("developer_id") val developerId: String = "",
    val capabilities: List<String> = emptyList(),
    @SerialName("hardware_platforms") val hardwarePlatforms: List<LockerEntryPlatform> = emptyList(),
    val links: Map<String, String> = emptyMap(),
) {
    fun storeMetadata(storeId: String): StorePbwMetadata = StorePbwMetadata(
        uuid = Uuid.parse(requireNotNull(uuid) { "Store app has no UUID" }),
        appstoreData = LockerEntryAppstoreData(
            hearts = hearts,
            developerId = developerId,
            timelineEnabled = "timeline" in capabilities,
            removeLink = links["remove"].orEmpty(),
            shareLink = links["share"].orEmpty(),
            pbwLink = latestRelease?.pbwFile.orEmpty(),
            userToken = null,
            storeId = storeId,
        ),
        platforms = hardwarePlatforms,
    )
}

@Serializable
internal data class AppRelease(
    @SerialName("pbw_file") val pbwFile: String? = null,
)
