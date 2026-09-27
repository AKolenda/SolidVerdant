/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.repository

import androidx.room.withTransaction
import dev.tricked.solidverdant.data.local.db.AppDatabase
import dev.tricked.solidverdant.data.local.db.OutboxDao
import dev.tricked.solidverdant.data.local.db.OutboxEntity
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.local.db.SyncState
import dev.tricked.solidverdant.data.local.db.TimeEntryDao
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.isLocalTimeEntryId
import dev.tricked.solidverdant.sync.ConflictSnapshot
import dev.tricked.solidverdant.util.Clock
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.util.UUID

/**
 * SV-027 base-snapshot capture for a STOP/UPDATE/DELETE enqueue, applied inside the same
 * transaction that enqueues the op, before the local mutation is written. Rules, in order:
 * 1. a queued op for this entry already carries a base -> reuse the oldest such base (an
 *    offline STOP->UPDATE chain shares the pre-stop base);
 * 2. else a queued START/CREATE exists for the entry -> null (born locally, nothing on the
 *    server to diverge from);
 * 3. else -> snapshot the entity's pre-mutation content (the last server-acked content).
 */
internal suspend fun captureBaseSnapshot(timeEntryDao: TimeEntryDao, outboxDao: OutboxDao, json: Json, entryId: String): String? =
    outboxDao.oldestBaseSnapshot(entryId)
        ?: if (outboxDao.hasPendingCreateOrStart(entryId)) {
            null
        } else {
            timeEntryDao.getById(entryId)?.let { current ->
                json.encodeToString(
                    ConflictSnapshot.of(
                        start = current.start,
                        end = current.end,
                        description = current.description,
                        projectId = current.projectId,
                        taskId = current.taskId,
                        billable = current.billable,
                        tagIds = timeEntryDao.tagIdsFor(entryId),
                        type = current.type,
                    ),
                )
            }
        }

/**
 * Step 2 of the SV-019 soft delete, shared by [TimeEntryRepository.commitDelete] and the sweeps
 * that commit deletes whose undo window was lost with its ViewModel (process death, screen closed).
 */
internal class SoftDeleteCommitter(
    private val timeEntryDao: TimeEntryDao,
    private val outboxDao: OutboxDao,
    private val database: AppDatabase,
    private val json: Json,
    private val clock: Clock,
) {
    /**
     * SV-008: for an entry that never reached the server (`local-` id) there is nothing to tell
     * the server to delete; cancel the entry's own queued START/CREATE and dependants and drop the
     * row. A server DELETE queued instead would race the still-queued START/CREATE and resurrect
     * the entry. A server-owned row stays soft-deleted and gets a DELETE op for the worker.
     */
    suspend fun commit(entry: TimeEntry, resolveCurrentId: suspend (TimeEntry) -> String?) {
        val now = clock.nowMs()
        database.withTransaction {
            // The undo window is long enough for a START/CREATE to reconcile, so the snapshot's
            // local id may now belong to a server-owned row that needs a real DELETE.
            val targetId = resolveCurrentId(entry) ?: entry.id
            if (timeEntryDao.getById(targetId)?.syncState == SyncState.CONFLICT) return@withTransaction
            if (isLocalTimeEntryId(targetId)) {
                timeEntryDao.clearTagRefs(targetId)
                timeEntryDao.deleteById(targetId)
                outboxDao.deleteByTimeEntryId(targetId)
            } else {
                if (outboxDao.hasPendingDelete(targetId)) return@withTransaction
                // softDeleteLocal already flipped the row to PENDING, but its content fields are
                // still the last server-acked ones, which is what the base must describe.
                val base = captureBaseSnapshot(timeEntryDao, outboxDao, json, targetId)
                outboxDao.insert(
                    OutboxEntity(
                        opType = OutboxOpType.DELETE,
                        organizationId = entry.organizationId,
                        timeEntryId = targetId,
                        createdAtMs = now,
                        clientId = UUID.randomUUID().toString(),
                        payloadJson = "{}",
                        baseSnapshotJson = base,
                    ),
                )
            }
        }
    }

    /**
     * Commit every soft delete older than [olderThanMs] that never got its DELETE. The age keeps
     * the sweep clear of a live undo window. Returns how many deletes were committed.
     */
    suspend fun commitOrphans(olderThanMs: Long = ORPHANED_SOFT_DELETE_AGE_MS): Int {
        val orphans = timeEntryDao.findUncommittedSoftDeletes(cutoffMs = clock.nowMs() - olderThanMs)
        orphans.forEach { row ->
            commit(TimeEntry(id = row.id, userId = row.userId, start = row.start, organizationId = row.organizationId)) { it.id }
        }
        if (orphans.isNotEmpty()) Timber.i("Committed %d soft deletes whose undo window was lost", orphans.size)
        return orphans.size
    }

    companion object {
        /** Far beyond the Track undo window (5 s), so a delete that can still be undone is left alone. */
        const val ORPHANED_SOFT_DELETE_AGE_MS = 60_000L
    }
}
