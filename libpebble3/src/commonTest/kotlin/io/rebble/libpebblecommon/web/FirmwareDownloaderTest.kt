/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.rebble.libpebblecommon.web

import io.ktor.http.ContentType
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirmwareDownloaderTest {
    @Test
    fun `language packs require an octet stream response`() {
        assertTrue(validLanguagePackContentType(ContentType.Application.OctetStream))
        assertTrue(validLanguagePackContentType(ContentType.parse("binary/octet-stream")))
        assertTrue(
            validLanguagePackContentType(
                ContentType.parse("application/octet-stream; charset=binary"),
            ),
        )
        assertFalse(validLanguagePackContentType(ContentType.Text.Html))
        assertFalse(validLanguagePackContentType(null))
    }
}
