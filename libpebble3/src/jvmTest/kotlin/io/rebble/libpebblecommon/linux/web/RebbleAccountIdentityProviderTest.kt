package io.rebble.libpebblecommon.linux.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RebbleAccountIdentityProviderTest {
    @Test
    fun `boot config decodes nullable authentication me link`() {
        val config = Json.decodeFromString<RebbleBootConfig>(
            """{"config":{"links":{"authentication/me":"https://identity.example.test/me"}}}""",
        )

        assertEquals("https://identity.example.test/me", config.config.links?.authenticationMe)
        assertNull(Json.decodeFromString<RebbleBootConfig>("""{"config":{}}""").config.links?.authenticationMe)
    }

    @Test
    fun `returns name and optional email with current bearer`() = runTest {
        val provider = RebbleAccountIdentityProvider.forTest(
            token = { "account-a" },
            bootConfig = identityBootConfig({ "account-a" }),
        ) { url, bearer ->
            assertEquals("https://identity.example.test/me", url)
            assertEquals("account-a", bearer)
            IdentityResponse(200, """{"name":"Ada Lovelace","email":"ada@example.test"}""")
        }

        assertEquals(
            RebbleAccountIdentity("Ada Lovelace", "ada@example.test"),
            provider.get(),
        )
    }

    @Test
    fun `missing identity link and invalid identity responses return null`() = runTest {
        val missingLink = RebbleAccountIdentityProvider.forTest(
            token = { "account-a" },
            bootConfig = RebbleBootConfigProvider.forTest({ "account-a" }, { 0L }) {
                RebbleBootConfig(RebbleBootConfig.Config())
            },
        ) { _, _ -> error("must not fetch without an identity link") }
        assertNull(missingLink.get())

        listOf(
            IdentityResponse(403, "{}"),
            IdentityResponse(200, "not json"),
            IdentityResponse(200, "{}"),
            IdentityResponse(200, """{"name":"   ","email":"x@example.test"}"""),
        ).forEach { response ->
            val provider = RebbleAccountIdentityProvider.forTest(
                token = { "account-a" },
                bootConfig = identityBootConfig({ "account-a" }),
            ) { _, _ -> response }
            assertNull(provider.get())
        }
    }

    @Test
    fun `blank email is optional`() = runTest {
        val provider = RebbleAccountIdentityProvider.forTest(
            token = { "account-a" },
            bootConfig = identityBootConfig({ "account-a" }),
        ) { _, _ -> IdentityResponse(200, """{"name":"Ada","email":" "}""") }

        assertEquals(RebbleAccountIdentity("Ada", null), provider.get())
    }

    @Test
    fun `discards identity when token changes while request is in flight`() = runTest {
        var token: String? = "account-a"
        val started = Channel<String>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val provider = RebbleAccountIdentityProvider.forTest(
            token = { token },
            bootConfig = identityBootConfig({ token }),
        ) { _, bearer ->
            started.send(bearer)
            release.await()
            IdentityResponse(200, """{"name":"Ada"}""")
        }

        val result = async { provider.get() }
        assertEquals("account-a", started.receive())
        token = "account-b"
        release.complete(Unit)
        assertNull(result.await())
    }

    @Test
    fun `preserves coroutine cancellation`() = runTest {
        val provider = RebbleAccountIdentityProvider.forTest(
            token = { "account-a" },
            bootConfig = identityBootConfig({ "account-a" }),
        ) { _, _ -> throw CancellationException("cancelled") }

        assertFailsWith<CancellationException> { provider.get() }
    }

    private fun identityBootConfig(token: () -> String?) = RebbleBootConfigProvider.forTest(
        token = token,
        nowMillis = { 0L },
        fetch = {
            RebbleBootConfig(
                RebbleBootConfig.Config(
                    links = RebbleBootConfig.Links(
                        authenticationMe = "https://identity.example.test/me",
                    ),
                ),
            )
        },
    )
}
