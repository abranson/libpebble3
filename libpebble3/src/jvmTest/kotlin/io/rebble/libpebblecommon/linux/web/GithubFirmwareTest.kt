package io.rebble.libpebblecommon.linux.web

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GithubFirmwareTest {
    @Test
    fun `newer publication of old firmware does not hide latest version`() = runTest {
        val result = latestGithubFirmware("obelix_pvt") {
            "[${release("v4.30.4", published = "2026-09-30T13:57:28Z")}," +
                "${release("v4.38.4", published = "2026-09-29T19:52:19Z")}]"
        }!!
        assertEquals("v4.38.4", result.version.stringVersion)
        assertEquals("https://example.test/v4.38.4.pbz", result.url)
        assertEquals("Notes for v4.38.4", result.notes)
    }

    @Test
    fun `only stable releases with matching slotless normal firmware qualify`() = runTest {
        val result = latestGithubFirmware("obelix_pvt") {
            "[${release("v4.39.0", flags = "\"prerelease\":true,")}," +
                "${release("v4.40.0", flags = "\"draft\":true,")}," +
                "${release("v4.41.0", hardware = "asterix")}," +
                "${release("v4.42.0", asset = "normal_obelix_pvt_v4.42.0_slot0.pbz")}," +
                "${release("v4.43.0", asset = "recovery_obelix_pvt_v4.43.0.pbz")}," +
                "${release("unparseable")},${release("v4.38.4")}]"
        }
        assertEquals("v4.38.4", result?.version?.stringVersion)
    }

    @Test
    fun `versions compare numerically rather than lexically`() = runTest {
        val result = latestGithubFirmware("obelix_pvt") {
            "[${release("v4.9.9")},${release("v4.38.4")}]"
        }
        assertEquals("v4.38.4", result?.version?.stringVersion)
    }

    @Test
    fun `newest compatible version can be on a later page`() = runTest {
        val pages = mutableListOf<Int>()
        val result = latestGithubFirmware("obelix_pvt") { page ->
            pages += page
            if (page == 1) fullPage() else "[${release("v4.38.4")}]"
        }
        assertEquals(listOf(1, 2), pages)
        assertEquals("v4.38.4", result?.version?.stringVersion)
    }

    @Test
    fun `missing compatible firmware returns no candidate`() = runTest {
        assertNull(latestGithubFirmware("obelix_pvt") {
            "[${release("v4.38.4", hardware = "asterix")}]"
        })
        assertNull(latestGithubFirmware("obelix_pvt") { "[]" })
    }

    @Test
    fun `later page failure does not return partial candidate`() = runTest {
        assertFailsWith<IllegalStateException> {
            latestGithubFirmware("obelix_pvt") { page ->
                if (page == 2) error("HTTP 403")
                fullPage()
            }
        }
    }

    @Test
    fun `pagination is bounded without claiming a complete result`() = runTest {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            latestGithubFirmware("obelix_pvt") {
                calls++
                fullPage()
            }
        }
        assertEquals(10, calls)
    }

    @Test
    fun `cancellation propagates`() = runTest {
        assertFailsWith<CancellationException> {
            latestGithubFirmware("obelix_pvt") { throw CancellationException() }
        }
    }

    private fun fullPage(): String =
        List(100) { release("v4.30.4") }.joinToString(",", "[", "]")

    private fun release(
        version: String,
        hardware: String = "obelix_pvt",
        asset: String = "normal_${hardware}_${version}.pbz",
        flags: String = "",
        published: String = "2026-09-29T19:52:19Z",
    ): String = """
        {"tag_name":"$version", "published_at":"$published", $flags
         "body":"Notes for $version",
         "assets":[{"name":"$asset", "browser_download_url":"https://example.test/$version.pbz"}]}
    """.trimIndent()
}
