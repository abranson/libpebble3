package io.rebble.libpebblecommon.linux.web

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.copyTo
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import io.rebble.libpebblecommon.util.JvmPaths
import kotlinx.io.files.Path
import java.io.File

/**
 * Rebble appstore: the rockpool UI browses the store itself and hands the daemon a store id
 * to install (org.rockwork InstallApp) — resolve it to the latest release's pbw and download.
 */
object RebbleAppstore {
    private val logger = Logger.withTag("RebbleAppstore")
    private val httpClient by lazy { HttpClient(CIO) }
    private val json = Json { ignoreUnknownKeys = true }

    /** A downloaded pbw plus the app's UUID (needed to add it to the Rebble locker). */
    data class DownloadedApp(val path: Path, val uuid: String?)

    suspend fun downloadPbw(storeId: String): DownloadedApp? {
        val record = try {
            val response = httpClient.get("$API_URL/apps/id/$storeId")
            if (!response.status.isSuccess()) {
                logger.w { "app lookup failed for $storeId: ${response.status}" }
                return null
            }
            json.decodeFromString<AppsResponse>(response.bodyAsText()).data.firstOrNull()
        } catch (e: Exception) {
            logger.w { "app lookup failed for $storeId: ${e.message}" }
            null
        } ?: run {
            logger.w { "no app record for store id $storeId" }
            return null
        }
        val pbwUrl = record.latestRelease?.pbwFile ?: run {
            logger.w { "no pbw for store id $storeId" }
            return null
        }
        val dest = File(JvmPaths.cacheSubdir("appstore").toFile(), "$storeId.pbw")
        return try {
            val response = httpClient.get(pbwUrl)
            if (!response.status.isSuccess()) {
                logger.w { "pbw download failed: ${response.status}" }
                return null
            }
            dest.outputStream().use { response.bodyAsChannel().copyTo(it) }
            logger.d { "downloaded $pbwUrl to $dest" }
            DownloadedApp(Path(dest.absolutePath), record.uuid)
        } catch (e: Exception) {
            logger.w { "pbw download failed: ${e.message}" }
            null
        }
    }

    /**
     * Add an app to the user's Rebble locker, which is what assigns its per-user timeline
     * user_token. rockpool installs by sideloading the pbw, so without this the app is never a
     * locker member and Pebble.getTimelineToken() has no real token (falls back to sandbox).
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
            logger.w { "add-to-locker($uuid) failed: ${e.message}" }
            false
        }
    }

    private const val API_URL = "https://appstore-api.rebble.io/api/v1"
}

@Serializable
private data class AppsResponse(val data: List<AppRecord> = emptyList())

@Serializable
private data class AppRecord(
    val uuid: String? = null,
    @SerialName("latest_release") val latestRelease: AppRelease? = null,
)

@Serializable
private data class AppRelease(
    @SerialName("pbw_file") val pbwFile: String? = null,
)
