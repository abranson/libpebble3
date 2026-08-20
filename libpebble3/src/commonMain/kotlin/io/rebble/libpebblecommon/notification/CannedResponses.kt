/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.rebble.libpebblecommon.notification

private const val MAX_CANNED_RESPONSE_BYTES = 512

/**
 * Trim canned replies to fit the firmware's 512-byte serialized limit.
 *
 * The protocol joins responses with NUL separators and UTF-8 encodes them.
 * Keep the configured order, dropping entries from the end when necessary.
 */
internal fun trimCannedResponses(responses: List<String>): List<String> {
    var result = responses
    while (result.isNotEmpty() &&
        result.joinToString("\u0000").encodeToByteArray().size > MAX_CANNED_RESPONSE_BYTES
    ) {
        result = result.dropLast(1)
    }
    return result
}
