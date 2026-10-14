package io.rebble.libpebblecommon.contacts

import androidx.compose.ui.graphics.ImageBitmap
import androidx.paging.PagingSource
import io.rebble.libpebblecommon.database.dao.ContactDao
import io.rebble.libpebblecommon.database.dao.ContactWithCount
import io.rebble.libpebblecommon.database.entity.ContactEntity
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class PhoneContactsSyncerTest {
    @Test
    fun providerFailurePreservesProjectionAndLaterEventRecovers() = runTest {
        val provider = FakeSystemContacts(
            mutableListOf(
                listOf(SystemContact(name = "Old", key = "old")),
                IllegalStateException("provider unavailable"),
                listOf(SystemContact(name = "New", key = "new")),
            ),
        )
        val dao = FakeContactDao()
        val syncer = syncer(provider, dao)

        syncer.init()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(contact("old", "Old")), dao.contacts)

        provider.changes.emit(Unit)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(contact("old", "Old")), dao.contacts)

        provider.changes.emit(Unit)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(listOf(contact("new", "New")), dao.contacts)
    }

    @Test
    fun emptyProviderResultDeletesPreviousProjection() = runTest {
        val provider = FakeSystemContacts(
            mutableListOf(
                listOf(SystemContact(name = "Old", key = "old")),
                emptyList<SystemContact>(),
            ),
        )
        val dao = FakeContactDao()
        val syncer = syncer(provider, dao)

        syncer.init()
        advanceTimeBy(1_000)
        runCurrent()
        provider.changes.emit(Unit)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(emptyList(), dao.contacts)
    }

    @Test
    fun providerCancellationStopsTheSyncCollector() = runTest {
        val provider = FakeSystemContacts(
            mutableListOf(
                listOf(SystemContact(name = "Old", key = "old")),
                CancellationException("cancelled"),
                listOf(SystemContact(name = "New", key = "new")),
            ),
        )
        val dao = FakeContactDao()
        val syncer = syncer(provider, dao)

        syncer.init()
        advanceTimeBy(1_000)
        runCurrent()
        provider.changes.emit(Unit)
        advanceTimeBy(5_000)
        runCurrent()
        provider.changes.emit(Unit)
        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(2, provider.readCount)
        assertEquals(listOf(contact("old", "Old")), dao.contacts)
    }

    private fun TestScope.syncer(provider: FakeSystemContacts, dao: FakeContactDao) =
        PhoneContactsSyncer(
            LibPebbleCoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)),
            provider,
            dao,
        )
}

private class FakeSystemContacts(private val results: MutableList<Any>) : SystemContacts {
    val changes = MutableSharedFlow<Unit>()
    var readCount = 0
        private set

    override fun registerForContactsChanges(): Flow<Unit> = changes

    @Suppress("UNCHECKED_CAST")
    override suspend fun getContacts(): List<SystemContact> {
        readCount++
        return when (val result = results.removeFirst()) {
            is Throwable -> throw result
            else -> result as List<SystemContact>
        }
    }

    override fun hasPermission() = true

    override suspend fun getContactImage(lookupKey: String): ImageBitmap? = null
}

private class FakeContactDao : ContactDao {
    val contacts = mutableListOf<ContactEntity>()

    override suspend fun insertOrUpdate(watch: ContactEntity) {
        contacts.removeAll { it.lookupKey == watch.lookupKey }
        contacts.add(watch)
    }

    override fun getContactsWithCountFlow(searchTerm: String?, onlyNotified: Boolean): PagingSource<Int, ContactWithCount> =
        error("unused in syncer tests")

    override fun getContactWithCountFlow(id: String): Flow<ContactWithCount?> = emptyFlow()

    override suspend fun getContacts(): List<ContactEntity> = contacts.toList()

    override suspend fun getContact(key: String): ContactEntity? = contacts.find { it.lookupKey == key }

    override suspend fun delete(key: String) {
        contacts.removeAll { it.lookupKey == key }
    }

    override suspend fun updateContactState(
        key: String,
        muteState: io.rebble.libpebblecommon.database.entity.MuteState,
        vibePatternName: String?,
    ) = Unit
}

private fun contact(key: String, name: String) = SystemContact(name = name, key = key).asEntity()
