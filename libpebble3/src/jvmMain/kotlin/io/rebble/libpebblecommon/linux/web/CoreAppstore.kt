package io.rebble.libpebblecommon.linux.web

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.rebble.libpebblecommon.database.entity.LockerEntryAppstoreData
import io.rebble.libpebblecommon.disk.pbw.StorePbwMetadata
import io.rebble.libpebblecommon.web.CORE_STORE_ID_PREFIX
import io.rebble.libpebblecommon.web.LockerEntryPlatform
import io.rebble.libpebblecommon.web.LockerEntryPlatformImages
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import kotlin.uuid.Uuid

private const val CORE_STORE_API = "https://appstore-api.repebble.com/api"
private const val REBBLE_STORE_API = "https://appstore-api.rebble.io/api"

/** A catalogue identifier scoped to the store that issued it. */
class AppstoreListingId private constructor(val appId: String, val core: Boolean) {
    val value: String get() = if (core) CORE_STORE_ID_PREFIX + appId else appId
    val lookupUrl: String get() = "${if (core) CORE_STORE_API else REBBLE_STORE_API}/v1/apps/id/$appId"

    companion object {
        private val appIdPattern = Regex("[0-9a-fA-F]{24}")

        fun parse(value: String): AppstoreListingId? {
            val core = value.startsWith(CORE_STORE_ID_PREFIX)
            val id = value.removePrefix(CORE_STORE_ID_PREFIX)
            if (!appIdPattern.matches(id)) return null
            return AppstoreListingId(if (core) id.lowercase() else id, core)
        }
    }
}

/** Public catalogue and package reads. Core account credentials are unnecessary. */
object CoreAppstore {
    private val client by lazy(::newRebbleHttpClient)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun downloadPbw(id: AppstoreListingId, hardware: String, destination: File): StorePbwMetadata? {
        require(id.core)
        val lookup = client.get(id.lookupUrl) { parameter("hardware", hardware) }
        check(lookup.status.isSuccess()) { "Core store lookup returned ${lookup.status}" }
        val metadata = decodeMetadata(lookup.bodyAsText(), id) ?: return null

        client.downloadPbwFile(metadata.appstoreData.pbwLink, destination)
        return metadata
    }

    fun lookupUrl(uuid: Uuid): String = "$CORE_STORE_API/v1/apps/uuid/$uuid"

    fun decodeMetadata(body: String, id: AppstoreListingId): StorePbwMetadata? {
        val app = json.decodeFromString<AppsResponse>(body).data.orEmpty().firstOrNull { it?.id == id.appId }
            ?: return null
        val uuid = app.uuid?.let { Uuid.parse(it) } ?: return null
        val pbwUrl = app.latestRelease?.pbwFile ?: return null
        val url = runCatching { URI(pbwUrl) }.getOrNull() ?: return null
        if (url.scheme != "https" || url.host == null || url.userInfo != null) return null

        return StorePbwMetadata(
            uuid = uuid,
            appstoreData = LockerEntryAppstoreData(
                hearts = app.hearts ?: 0,
                developerId = app.developerId.orEmpty(),
                timelineEnabled = "timeline" in app.capabilities.orEmpty(),
                removeLink = "",
                shareLink = app.links?.get("share").orEmpty(),
                pbwLink = pbwUrl,
                userToken = null,
                sourceLink = CORE_STORE_API,
                storeId = id.value,
            ),
            platforms = app.hardwarePlatforms.orEmpty().mapNotNull { platform ->
                val name = platform?.name ?: return@mapNotNull null
                val images = platform.images
                LockerEntryPlatform(
                    sdkVersion = "",
                    pebbleProcessInfoFlags = 0,
                    name = name,
                    description = platform.description.orEmpty(),
                    images = LockerEntryPlatformImages(
                        icon = images?.get("icon").orEmpty(),
                        list = images?.get("list").orEmpty(),
                        screenshot = images?.get("screenshot").orEmpty(),
                    ),
                )
            },
            releaseVersion = app.latestRelease.version?.takeIf { it.isNotBlank() },
        )
    }

    @Serializable
    private data class AppsResponse(val data: List<AppRecord?>? = null)

    @Serializable
    private data class AppRecord(
        val id: String? = null,
        val uuid: String? = null,
        val hearts: Int? = null,
        @SerialName("developer_id") val developerId: String? = null,
        val capabilities: List<String?>? = null,
        val links: Map<String, String?>? = null,
        @SerialName("latest_release") val latestRelease: AppRelease? = null,
        @SerialName("hardware_platforms") val hardwarePlatforms: List<HardwarePlatform?>? = null,
    )

    @Serializable
    private data class AppRelease(
        @SerialName("pbw_file") val pbwFile: String? = null,
        val version: String? = null,
    )

    @Serializable
    private data class HardwarePlatform(
        val name: String? = null,
        val description: String? = null,
        val images: Map<String, String?>? = null,
    )
}
