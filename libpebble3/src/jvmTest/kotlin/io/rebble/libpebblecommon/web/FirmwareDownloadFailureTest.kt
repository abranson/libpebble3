package io.rebble.libpebblecommon.web

import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.locker.getLockerPBWCacheDirectory
import java.net.InetSocketAddress
import java.nio.channels.UnresolvedAddressException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FirmwareDownloadFailureTest {
    @Test
    fun `DNS failure leaves connection scope usable and allows a successful retry`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val bytes = "firmware fixture".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = HttpClient(CIO)
        var fail = true
        client.plugin(HttpSend).intercept { request ->
            if (fail) throw UnresolvedAddressException()
            execute(request)
        }
        val type = "test-${UUID.randomUUID()}"
        val path = Path(getLockerPBWCacheDirectory(AppContext()), "download.$type")
        try {
            val downloader = FirmwareDownloader(client, AppContext())
            val url = "http://127.0.0.1:${server.address.port}/"
            assertNull(downloader.downloadFirmware(url, type))
            assertTrue(coroutineContext.isActive)
            assertFalse(SystemFileSystem.exists(path))
            fail = false
            assertNotNull(downloader.downloadFirmware(url, type))
            assertEquals("firmware fixture", java.io.File(path.toString()).readText())
        } finally {
            client.close()
            server.stop(0)
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    @Test
    fun `cancelled download propagates cancellation and removes stale output`() = runBlocking {
        val client = HttpClient(CIO)
        client.plugin(HttpSend).intercept { throw CancellationException("cancel fixture") }
        val type = "test-${UUID.randomUUID()}"
        val path = Path(getLockerPBWCacheDirectory(AppContext()), "download.$type")
        java.io.File(path.toString()).writeText("stale download")
        try {
            assertFailsWith<CancellationException> {
                FirmwareDownloader(client, AppContext()).downloadFirmware("http://127.0.0.1/", type)
            }
            assertFalse(SystemFileSystem.exists(path))
        } finally {
            client.close()
            SystemFileSystem.delete(path, mustExist = false)
        }
    }

    @Test
    fun `truncated response is not returned as firmware`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            exchange.sendResponseHeaders(200, 100)
            try {
                exchange.responseBody.write(byteArrayOf(1, 2, 3))
                exchange.responseBody.close()
            } catch (_: java.io.IOException) {
                exchange.close()
            }
        }
        server.start()
        val client = HttpClient(CIO)
        val type = "test-${UUID.randomUUID()}"
        val path = Path(getLockerPBWCacheDirectory(AppContext()), "download.$type")
        try {
            val result = FirmwareDownloader(client, AppContext()).downloadFirmware(
                "http://127.0.0.1:${server.address.port}/", type,
            )
            assertNull(result)
            assertTrue(coroutineContext.isActive)
            assertFalse(SystemFileSystem.exists(path))
        } finally {
            client.close()
            server.stop(0)
            SystemFileSystem.delete(path, mustExist = false)
        }
    }
}
