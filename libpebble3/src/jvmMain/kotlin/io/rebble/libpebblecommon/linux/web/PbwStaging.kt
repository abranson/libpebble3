package io.rebble.libpebblecommon.linux.web

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

const val MAX_PBW_INSTALL_BYTES = 32L * 1024L * 1024L

fun stagePbw(input: InputStream, destination: File) {
    val temporary = File.createTempFile("pbw-", ".part", destination.absoluteFile.parentFile)
    try {
        temporary.outputStream().use { output ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = MAX_PBW_INSTALL_BYTES
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(count <= remaining) { "PBW exceeds the install size limit" }
                output.write(buffer, 0, count)
                remaining -= count
            }
        }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temporary.delete()
    }
}

suspend fun HttpClient.downloadPbwFile(url: String, destination: File) {
    prepareGet(url) {
        timeout {
            requestTimeoutMillis = 120_000L
            socketTimeoutMillis = 30_000L
        }
    }.execute { response ->
        check(response.status.isSuccess()) { "Store package download returned ${response.status}" }
        withContext(Dispatchers.IO) {
            response.bodyAsChannel().toInputStream().use { stagePbw(it, destination) }
        }
    }
}
