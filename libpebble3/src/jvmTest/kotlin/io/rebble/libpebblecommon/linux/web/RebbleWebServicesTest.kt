package io.rebble.libpebblecommon.linux.web

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import kotlin.test.assertNotNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import kotlin.uuid.Uuid

class RebbleWebServicesTest {
    private val uuid = Uuid.parse("12345678-1234-4abc-8def-1234567890ab")

    @Test
    fun `locker removal URL replaces exactly one UUID placeholder`() {
        assertEquals(
            "https://locker.example.test/apps/$uuid",
            lockerRemovalUrl("https://locker.example.test/apps/\$\$app_uuid\$\$", uuid),
        )
        assertNull(lockerRemovalUrl("https://locker.example.test/apps", uuid))
        assertNull(
            lockerRemovalUrl(
                "https://locker.example.test/\$\$app_uuid\$\$/\$\$app_uuid\$\$",
                uuid,
            ),
        )
    }

    @Test
    fun `account session invalidation rejects an ABA credential`() {
        var token: String? = "account-a"
        val provider = RebbleBootConfigProvider.forTest(
            token = { token },
            nowMillis = { 0L },
            fetch = { null },
        )
        val services = RebbleWebServices({ token }, provider)
        val oldAccountA = services.currentAccountSession()!!

        token = "account-b"
        services.invalidateAccountSessions()
        token = "account-a"

        assertFalse(services.isCurrentAccountSession(oldAccountA))
        assertEquals("WebAccountSession(redacted)", oldAccountA.toString())
    }

    @Test
    fun `boot config reads locker removal endpoint`() {
        val config = Json { ignoreUnknownKeys = true }.decodeFromString<RebbleBootConfig>(
            """
            {
              "config": {
                "locker": {
                  "get_endpoint": "https://locker.example.test/apps",
                  "remove_endpoint": "https://locker.example.test/apps/${'$'}${'$'}app_uuid${'$'}${'$'}"
                }
              }
            }
            """.trimIndent(),
        )

        assertEquals(
            "https://locker.example.test/apps/\$\$app_uuid\$\$",
            config.config.locker?.removeEndpoint,
        )
    }

    @Test
    fun `boot config cache never crosses account tokens or logout`() = runTest {
        var token: String? = "account-a"
        var now = 1_000L
        val fetchedTokens = mutableListOf<String>()
        val provider = RebbleBootConfigProvider.forTest(
            token = { token },
            nowMillis = { now },
            fetch = { accessToken ->
                fetchedTokens += accessToken
                bootConfig("https://locker.example.test/$accessToken")
            },
        )

        assertEquals("https://locker.example.test/account-a", provider.get()?.config?.locker?.getEndpoint)
        assertEquals("https://locker.example.test/account-a", provider.get()?.config?.locker?.getEndpoint)
        token = "account-b"
        assertEquals("https://locker.example.test/account-b", provider.get()?.config?.locker?.getEndpoint)
        token = null
        assertNull(provider.get())
        token = "account-b"
        assertEquals("https://locker.example.test/account-b", provider.get()?.config?.locker?.getEndpoint)

        assertEquals(listOf("account-a", "account-b", "account-b"), fetchedTokens)
    }

    @Test
    fun `locker fetch does not use boot config obtained after account change`() = runTest {
        var token: String? = "account-a"
        val provider = RebbleBootConfigProvider.forTest(
            token = { token },
            nowMillis = { 0L },
            fetch = {
                token = "account-b"
                bootConfig("http://127.0.0.1:1/locker")
            },
        )

        assertNull(RebbleWebServices({ token }, provider).fetchLocker())
    }

    @Test
    fun `locker removal does not use boot config obtained after account change`() = runTest {
        var token: String? = "account-a"
        val provider = RebbleBootConfigProvider.forTest(
            token = { token },
            nowMillis = { 0L },
            fetch = {
                token = "account-b"
                RebbleBootConfig(
                    config = RebbleBootConfig.Config(
                        locker = RebbleBootConfig.Locker(
                            removeEndpoint = "http://127.0.0.1:1/locker/\$\$app_uuid\$\$",
                        ),
                    ),
                )
            },
        )

        assertEquals(false, RebbleWebServices({ token }, provider).removeFromLocker(uuid))
    }

    @Test
    fun `boot config discards an in-flight response after token changes or logout`() = runTest {
        var token: String? = "account-a"
        val started = Channel<String>(Channel.UNLIMITED)
        var release = CompletableDeferred<Unit>()
        val provider = RebbleBootConfigProvider.forTest(
            token = { token },
            nowMillis = { 1_000L },
            fetch = { accessToken ->
                if (accessToken == "account-a") {
                    started.send(accessToken)
                    release.await()
                }
                bootConfig("https://locker.example.test/$accessToken")
            },
        )

        val accountA = async { provider.get() }
        assertEquals("account-a", started.receive())
        token = "account-b"
        release.complete(Unit)
        assertNull(accountA.await())

        assertEquals("https://locker.example.test/account-b", provider.get()?.config?.locker?.getEndpoint)
        token = "account-a"
        release = CompletableDeferred()
        val logout = async { provider.get() }
        assertEquals("account-a", started.receive())
        token = null
        release.complete(Unit)
        assertNull(logout.await())
        token = "account-b"
        assertEquals("https://locker.example.test/account-b", provider.get()?.config?.locker?.getEndpoint)
    }

    @Test
    fun `dev token cache never crosses account tokens or logout`() = runTest {
        var token: String? = "account-a"
        val fetchedTokens = mutableListOf<String>()
        val bootConfig = RebbleBootConfigProvider.forTest(
            token = { token },
            nowMillis = { 1_000L },
            fetch = { usersBootConfig() },
        )
        val provider = RebbleTokenProvider.forTest({ token }, bootConfig) { _, accessToken ->
            fetchedTokens += accessToken
            "dev-$accessToken"
        }

        assertEquals("dev-account-a", provider.getDevToken())
        assertEquals("dev-account-a", provider.getDevToken())
        token = "account-b"
        assertEquals("dev-account-b", provider.getDevToken())
        token = null
        assertNull(provider.getDevToken())
        token = "account-b"
        assertEquals("dev-account-b", provider.getDevToken())

        assertEquals(listOf("account-a", "account-b", "account-b"), fetchedTokens)
    }

    @Test
    fun `dev token discards an in-flight users-me response after token changes or logout`() = runTest {
        var token: String? = "account-a"
        val started = Channel<String>(Channel.UNLIMITED)
        var release = CompletableDeferred<Unit>()
        val bootConfig = RebbleBootConfigProvider.forTest(
            token = { token },
            nowMillis = { 1_000L },
            fetch = { usersBootConfig() },
        )
        val provider = RebbleTokenProvider.forTest({ token }, bootConfig) { _, accessToken ->
            if (accessToken == "account-a") {
                started.send(accessToken)
                release.await()
            }
            "dev-$accessToken"
        }

        val accountA = async { provider.getDevToken() }
        assertEquals("account-a", started.receive())
        token = "account-b"
        release.complete(Unit)
        assertNull(accountA.await())

        assertEquals("dev-account-b", provider.getDevToken())
        token = "account-a"
        release = CompletableDeferred()
        val logout = async { provider.getDevToken() }
        assertEquals("account-a", started.receive())
        token = null
        release.complete(Unit)
        assertNull(logout.await())
        token = "account-b"
        assertEquals("dev-account-b", provider.getDevToken())
    }

    @Test
    fun `dev token fetch preserves coroutine cancellation`() = runTest {
        val bootConfig = RebbleBootConfigProvider.forTest(
            token = { "account-a" },
            nowMillis = { 1_000L },
            fetch = { usersBootConfig() },
        )
        val provider = RebbleTokenProvider.forTest({ "account-a" }, bootConfig) { _, _ ->
            throw CancellationException("cancelled")
        }

        assertFailsWith<CancellationException> { provider.getDevToken() }
    }

    @Test
    fun `locker fetch combines every page with the captured bearer`() = runTest {
        val other = "22345678-1234-4abc-8def-1234567890ab"
        withLockerServer(
            pages = { path ->
                200 to when (path) {
                    "/locker" -> lockerPage(uuid.toString(), "/page2")
                    "/page2" -> lockerPage(other, null)
                    else -> error("unexpected path $path")
                }
            },
        ) { services, requests ->
            val snapshot = assertNotNull(services.fetchLocker())
            assertEquals(listOf(uuid.toString(), other), snapshot.locker.applications.map { it.uuid })
            assertNull(snapshot.locker.nextPageURL)
            assertEquals(listOf("/locker", "/page2"), requests.map { it.first })
            assertEquals(listOf("Bearer account-a", "Bearer account-a"), requests.map { it.second })
        }
    }

    @Test
    fun `query-only pagination keeps the locker endpoint path`() = runTest {
        withLockerServer(
            pages = { path ->
                200 to lockerPage(uuid.toString(), if (path == "/locker") "?page=2" else null)
            },
        ) { services, requests ->
            assertNotNull(services.fetchLocker())
            assertEquals(listOf("/locker", "/locker?page=2"), requests.map { it.first })
        }
    }

    @Test
    fun `failed or malformed later page never returns a partial locker`() = runTest {
        for (failure in listOf(503 to "unavailable", 200 to "not json")) {
            withLockerServer(
                pages = { path -> if (path == "/locker") 200 to lockerPage(uuid.toString(), "/page2") else failure },
            ) { services, requests ->
                assertNull(services.fetchLocker())
                assertEquals(2, requests.size)
            }
        }
    }

    @Test
    fun `cyclic and foreign pagination links fail without another request`() = runTest {
        for (next in listOf("/locker", "http://localhost:1/steal-bearer")) {
            withLockerServer(pages = { 200 to lockerPage(uuid.toString(), next) }) { services, requests ->
                assertNull(services.fetchLocker())
                assertEquals(1, requests.size)
            }
        }
    }

    @Test
    fun `account switch on later page discards the whole locker`() = runTest {
        var token = "account-a"
        withLockerServer(
            token = { token },
            pages = { path ->
                if (path == "/locker") 200 to lockerPage(uuid.toString(), "/page2") else {
                    token = "account-b"
                    200 to lockerPage(uuid.toString(), null)
                }
            },
        ) { services, requests ->
            assertNull(services.fetchLocker())
            assertEquals(2, requests.size)
            assertEquals("Bearer account-a", requests.last().second)
        }
    }

    private suspend fun withLockerServer(
        token: () -> String = { "account-a" },
        pages: (String) -> Pair<Int, String>,
        test: suspend (RebbleWebServices, List<Pair<String, String?>>) -> Unit,
    ) {
        val requests = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String?>>())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.toString()
            requests += path to exchange.requestHeaders.getFirst("Authorization")
            val (status, body) = pages(path)
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        val client = HttpClient(CIO)
        try {
            val provider = RebbleBootConfigProvider.forTest(
                token = token, nowMillis = { 0L },
                fetch = { bootConfig("http://127.0.0.1:${server.address.port}/locker") },
            )
            test(RebbleWebServices(token, provider, client), requests)
        } finally {
            client.close()
            server.stop(0)
        }
    }

    private fun lockerPage(id: String, next: String?): String = buildJsonObject {
        val entry = Json.parseToJsonElement("""
            {"id":"store-id","uuid":"$id","title":"Face","type":"watchface",
             "category":"","hearts":0,"is_configurable":false,"is_timeline_enabled":true,
             "links":{"remove":"","href":"","share":""},
             "developer":{"id":"dev","name":"Developer","contact_email":""},
             "hardware_platforms":[],"companions":{"android":null,"ios":null},
             "compatibility":{"ios":{"supported":false},"android":{"supported":false},
               "aplite":{"supported":false,"firmware":{"major":4}},
               "basalt":{"supported":false,"firmware":{"major":4}},
               "chalk":{"supported":false,"firmware":{"major":4}},
               "diorite":{"supported":false,"firmware":{"major":4}},
               "emery":{"supported":false,"firmware":{"major":4}}}}
        """.trimIndent())
        put("applications", JsonArray(listOf(entry)))
        if (next != null) put("nextPageURL", next)
    }.toString()

    private fun bootConfig(lockerEndpoint: String) = RebbleBootConfig(
        config = RebbleBootConfig.Config(
            locker = RebbleBootConfig.Locker(getEndpoint = lockerEndpoint),
        ),
    )

    private fun usersBootConfig() = RebbleBootConfig(
        config = RebbleBootConfig.Config(
            links = RebbleBootConfig.Links(usersMe = "https://users.example.test/me"),
        ),
    )
}
