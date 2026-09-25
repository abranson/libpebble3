package io.rebble.libpebblecommon.locker

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.ErrorTracker
import io.rebble.libpebblecommon.connection.UserFacingError
import io.rebble.libpebblecommon.connection.WebAccountSession
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.database.Database
import kotlin.uuid.Uuid

/** Account-scoped removal, shared by the public and compatibility application APIs. */
internal class LockerAppRemoval(
    database: Database,
    private val accountMutations: LockerAccountMutationCoordinator,
    private val webServices: WebServices,
    private val errorTracker: ErrorTracker,
    private val deleteCachedApp: (Uuid) -> Unit,
    private val requestLockerSync: () -> Unit,
) {
    private val lockerEntryDao = database.lockerEntryDao()
    private val timelinePinDao = database.timelinePinDao()
    private val timelineReminderDao = database.timelineReminderDao()
    private val logger = Logger.withTag("Locker")

    suspend fun removeApp(id: Uuid): Boolean {
        var removedSideloaded = false
        var storeLinkedSideload = false
        val remoteSession = accountMutations.mutateWithSession(
            webSession = webServices::currentAccountSession,
        ) { capturedSession ->
            val lockerEntry = lockerEntryDao.getEntry(id)
            if (lockerEntry == null) {
                logger.e { "removeApp: not found: $id" }
                return@mutateWithSession null
            }
            if (lockerEntry.systemApp) {
                logger.w { "removeApp: refusing to remove system app: $id" }
                return@mutateWithSession null
            }
            logger.d { "Deleting app ${lockerEntry.id} (${lockerEntry.title})" }
            // Store PBWs may be imported locally before being added to the account locker.
            // Removing just that local override would let the following sync restore the app.
            storeLinkedSideload = lockerEntry.sideloaded &&
                !lockerEntry.appstoreData?.storeId.isNullOrBlank() &&
                capturedSession.webSession != null
            if (lockerEntry.sideloaded && !storeLinkedSideload) {
                lockerEntryDao.markForDeletionWithTimeline(id, timelinePinDao, timelineReminderDao)
                deleteCachedApp(id)
                removedSideloaded = true
                null
            } else {
                capturedSession
            }
        }
        if (!removedSideloaded && remoteSession == null) return false

        if (remoteSession != null) {
            // Use the exact bearer captured with the local locker generation. A token switch may
            // let the old request finish remotely, but it must never target the replacement
            // account or commit into its local locker.
            val removedRemotely = when (val webSession = remoteSession.webSession) {
                null -> if (webServices.usesAccountSessions()) false else webServices.removeFromLocker(id)
                else -> if (storeLinkedSideload) {
                    removeStoreLinkedSideload(id, webSession)
                } else {
                    webServices.removeFromLocker(id, webSession)
                }
            }
            if (!removedRemotely) {
                logger.w { "Failed to remove from remote locker" }
                errorTracker.reportError(UserFacingError.FailedToRemovePbwFromLocker("Failed to remove app from remote locker"))
                return false
            }
            val committed = accountMutations.commitIfCurrent(
                session = remoteSession,
                isWebSessionCurrent = webServices::isCurrentAccountSession,
            ) {
                val current = lockerEntryDao.getEntry(id)
                when {
                    current == null -> true // A concurrent same-session remove already committed.
                    current.systemApp || current.sideloaded != storeLinkedSideload -> false
                    else -> {
                        lockerEntryDao.markForDeletionWithTimeline(
                            id,
                            timelinePinDao,
                            timelineReminderDao,
                        )
                        deleteCachedApp(id)
                        true
                    }
                }
            }
            if (!committed) {
                logger.w { "Account changed before remote locker removal could commit locally" }
                return false
            }
        }

        if (removedSideloaded) {
            logger.d { "Requesting locker sync after removing sideloaded app" }
            // If it was sideloaded, trigger a resync (in case the same app is in the locker).
            requestLockerSync()
        }
        return true
    }

    private suspend fun removeStoreLinkedSideload(id: Uuid, session: WebAccountSession): Boolean {
        // Store association alone does not imply remote membership: adding to the locker is
        // best-effort, and an imported PBW may never have been in this account's locker.
        val snapshot = webServices.fetchLocker(session) ?: return false
        if (!webServices.isCurrentAccountSession(session)) return false
        if (id in snapshot.failedToFetchUuids) return false
        if (snapshot.locker.applications.any { it.uuid.equals(id.toString(), ignoreCase = true) }) {
            return webServices.removeFromLocker(id, session)
        }
        // A partial listing cannot prove absence. Keep the local app on a failed/incomplete
        // lookup instead of reporting success and having a later sync bring it back.
        return snapshot.locker.nextPageURL.isNullOrBlank()
    }
}
