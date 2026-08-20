package io.rebble.libpebblecommon.web

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.locker.getLockerPBWCacheDirectory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class FirmwareDownloader(
    private val httpClient: HttpClient,
    private val appContext: AppContext,
) {
    private val logger = Logger.withTag("FirmwareDownloader")
    private val fwDir = getLockerPBWCacheDirectory(appContext)

    suspend fun downloadFirmware(url: String, type: String): Path? {
        return download(url, type)
    }

    /**
     * Language packs historically required an octet-stream response.  Keep that narrow check
     * here so a successful captive-portal or login HTML response is never uploaded to a watch.
     */
    internal suspend fun downloadLanguagePack(url: String): Path? {
        return download(url, "pbl", ::validLanguagePackResponse)
    }

    private suspend fun download(
        url: String,
        type: String,
        validateResponse: ((HttpResponse) -> Boolean)? = null,
    ): Path? {
        logger.d { "downloadFirmware: $url" }
        return withTimeoutOr(30.seconds, {
            val path = Path(fwDir, "download.$type")
            SystemFileSystem.delete(path, mustExist = false)
            val response = try {
                httpClient.get(url)
            } catch (e: IOException) {
                logger.w(e) { "Error downloading fw: ${e.message}" }
                return@withTimeoutOr null
            }
            if (!response.status.isSuccess()) {
                logger.w("http call failed: $response")
                return@withTimeoutOr null
            }
            if (validateResponse != null && !validateResponse(response)) {
                logger.w { "unexpected response type for .$type download" }
                return@withTimeoutOr null
            }
            SystemFileSystem.sink(path).use { sink ->
                response.bodyAsChannel().readRemaining().transferTo(sink)
            }
            logger.v { "Downloaded firmware to $path" }
            path
        }) {
            logger.w { "downloadFirmware timed out" }
            null
        }
    }
}

internal fun validLanguagePackContentType(contentType: ContentType?): Boolean =
    contentType?.withoutParameters() in LANGUAGE_PACK_CONTENT_TYPES

private fun validLanguagePackResponse(response: HttpResponse): Boolean =
    validLanguagePackContentType(response.contentType())

private val LANGUAGE_PACK_CONTENT_TYPES = setOf(
    ContentType.Application.OctetStream,
    ContentType.parse("binary/octet-stream"),
)

expect fun getFirmwareDownloadDirectory(context: AppContext): Path

suspend fun <T> withTimeoutOr(timeout: Duration, block: suspend CoroutineScope.() -> T, onTimeout: () -> T): T {
    return try {
        withTimeout(timeout, block)
    } catch (e: TimeoutCancellationException) {
        onTimeout()
    }
}
