/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import coredev.BlobDatabase
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineResetTest {
    @Test
    fun `reset clears only timeline databases in protocol order`() = runTest {
        val cleared = mutableListOf<BlobDatabase>()
        val marked = mutableListOf<BlobDatabase>()

        val complete = resetTimelineDatabases(
            clear = {
                cleared += it
                BlobResponse.Success()
            },
            reconcileCleared = { marked += it },
        )

        val timeline = listOf(
            BlobDatabase.Pin,
            BlobDatabase.Reminder,
            BlobDatabase.Notification,
        )
        assertTrue(complete)
        assertEquals(timeline, cleared)
        assertEquals(timeline, marked)
    }

    @Test
    fun `failed database keeps its marker while successful clears are invalidated`() = runTest {
        val marked = mutableListOf<BlobDatabase>()

        val complete = resetTimelineDatabases(
            clear = {
                if (it == BlobDatabase.Reminder) {
                    BlobResponse.GeneralFailure()
                } else {
                    BlobResponse.Success()
                }
            },
            reconcileCleared = { marked += it },
        )

        assertFalse(complete)
        assertEquals(listOf(BlobDatabase.Pin, BlobDatabase.Notification), marked)
    }

    @Test
    fun `transport failure does not prevent later database recovery`() = runTest {
        val marked = mutableListOf<BlobDatabase>()

        val complete = resetTimelineDatabases(
            clear = {
                if (it == BlobDatabase.Reminder) error("connection lost")
                BlobResponse.Success()
            },
            reconcileCleared = { marked += it },
        )

        assertFalse(complete)
        assertEquals(listOf(BlobDatabase.Pin, BlobDatabase.Notification), marked)
    }

    @Test
    fun `marker recovery failure does not prevent later database recovery`() = runTest {
        val cleared = mutableListOf<BlobDatabase>()
        val reconciled = mutableListOf<BlobDatabase>()

        val complete = resetTimelineDatabases(
            clear = {
                cleared += it
                BlobResponse.Success()
            },
            reconcileCleared = {
                if (it == BlobDatabase.Pin) error("database unavailable")
                reconciled += it
            },
        )

        assertFalse(complete)
        assertEquals(
            listOf(BlobDatabase.Pin, BlobDatabase.Reminder, BlobDatabase.Notification),
            cleared,
        )
        assertEquals(
            listOf(BlobDatabase.Reminder, BlobDatabase.Notification),
            reconciled,
        )
    }
}
