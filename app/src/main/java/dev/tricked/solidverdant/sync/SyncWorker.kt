/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.sync

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.room.withTransaction
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dev.tricked.solidverdant.data.local.db.AppDatabase
import dev.tricked.solidverdant.data.local.db.OutboxDao
import dev.tricked.solidverdant.data.local.db.OutboxEntity
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.local.db.RateLimitMarker
import dev.tricked.solidverdant.data.local.db.SyncMetaDao
import dev.tricked.solidverdant.data.local.db.SyncState
import dev.tricked.solidverdant.data.local.db.TimeEntryDao
import dev.tricked.solidverdant.data.local.db.toEntity
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.remote.RemoteDataSource
import dev.tricked.solidverdant.data.remote.SolidtimeTimestamps
import dev.tricked.solidverdant.data.remote.TimeEntriesQuery
import dev.tricked.solidverdant.data.repository.SoftDeleteCommitter
import dev.tricked.solidverdant.domain.time.parseTimeEntryInstant
import dev.tricked.solidverdant.util.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs

@HiltWorker
@Suppress("LongParameterList", "TooManyFunctions", "LargeClass")
class SyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val outboxDao: OutboxDao,
    private val timeEntryDao: TimeEntryDao,
    private val syncMetaDao: SyncMetaDao,
    private val database: AppDatabase,
    private val remote: RemoteDataSource,
    private val json: Json,
    private val clock: Clock,
    private val syncStatus: SyncStatusReporter,
    private val followUp: SyncFollowUpScheduler,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = drainMutex.withLock {
        doWorkLocked()
    }

    /** Per-run bookkeeping shared by the drain loop and outcome handling. */
    private class Drain {
        /** Entries whose creating op was dead-lettered this run; their dependants can never succeed. */
        val failedEntryIds = mutableSetOf<String>()

        /**
         * Entries with an operation that failed transiently (or waits for a rate limit) this run.
         * Every later operation for the same entry is held back so the retry replays them in
         * order: a retried STOP must never land after a later UPDATE that corrected the end.
         */
        val deferredEntryIds = mutableSetOf<String>()

        /**
         * The active timer is account-wide. Once a START or STOP is held back, a later START could
         * only race it (the server rejects a second running timer, or the old one keeps running),
         * so later STARTs wait for the next run too.
         */
        var timerChainBlocked = false

        /**
         * The ops list is a snapshot taken once at the top of the run, so after a START/CREATE
         * rekeys an entry (local- id -> server id) later snapshot entries still hold the dead local
         * id. Apply the mapping to each op immediately before it is processed.
         */
        val rekeyed = mutableMapOf<String, String>()

        /** Organizations with at least one op genuinely flushed to the server this run. */
        val pushedOrgs = mutableSetOf<String>()
        var retryResult: Result? = null

        /**
         * Set when continuing would only repeat the same failure for every remaining op: the
         * per-user rate limit is closed, or the session needs sign-in. The run stops sending.
         */
        var halted = false

        /** Seconds the server asked us to wait (Retry-After), when a rate limit halted the run. */
        var rateLimitedForSeconds: Long? = null

        fun defer(op: OutboxEntity) {
            deferredEntryIds += op.timeEntryId
            if (op.opType == OutboxOpType.START || op.opType == OutboxOpType.STOP) timerChainBlocked = true
        }
    }

    private suspend fun doWorkLocked(): Result {
        syncStatus.set(SyncStatus.Syncing)
        // Drain across all organizations: a single background worker is responsible for flushing
        // the whole outbox, and each op already carries its own organizationId for the API call.
        // (The per-org filtering in observeSyncOperations is only for scoping the UI display.)
        // Dead-lettered ops are excluded so permanently-failed work is never re-attempted.
        // First queue the DELETE of any soft delete whose undo window died with its ViewModel, so
        // it syncs in this run instead of staying hidden locally while the server keeps the entry.
        runCatching { SoftDeleteCommitter(timeEntryDao, outboxDao, database, json, clock).commitOrphans() }
            .onFailure { error ->
                if (error is CancellationException) throw error
                Timber.w(error, "Could not commit orphaned soft deletes")
            }
        val ops = outboxDao.peekPending() // id ASC
        val conflictIndexes = loadConflictIndexes(ops)
        val drain = Drain()
        for (rawOp in ops) {
            // A conflict can delete queued operations for the entry. Re-read before acting so a
            // stale snapshot from the initial drain cannot write after the conflict was saved.
            val stored = outboxDao.getById(rawOp.id) ?: continue
            val op = stored.rekeyedWith(drain.rekeyed)
            if (op.timeEntryId in drain.failedEntryIds) continue
            if (op.timeEntryId in drain.deferredEntryIds) {
                // Held back behind an earlier operation for the same entry that will be retried.
                if (op.opType == OutboxOpType.START || op.opType == OutboxOpType.STOP) drain.timerChainBlocked = true
                continue
            }
            if (op.opType == OutboxOpType.START && drain.timerChainBlocked) {
                drain.defer(op)
                drain.retryResult = Result.retry()
                continue
            }
            handleOutcome(op, process(op, conflictIndexes), conflictIndexes, drain)
            if (drain.halted) break
        }
        // Stamp the push moment for every org that had at least one op reach the server, even when
        // another op still needs a retry: the successful ops genuinely flushed. stampPush touches
        // only lastPushAtMs, never the pull timestamp a concurrent refresh may have written.
        val pushedAt = clock.nowMs()
        drain.pushedOrgs.forEach { orgId -> syncMetaDao.stampPush(orgId, pushedAt) }
        // A dead-letter raised earlier in this drain must stay visible even when another op
        // still needs a retry; only a clean drain returns the banner to idle.
        if (syncStatus.status.value !is SyncStatus.Error) syncStatus.set(SyncStatus.Idle)
        drain.rateLimitedForSeconds?.let { seconds ->
            // WorkManager's own backoff ignores Retry-After (and grows to hours because a rate
            // limit never spends attempts). Schedule the next run for when the window reopens.
            val delayMs = seconds.coerceIn(MIN_RATE_LIMIT_WAIT_SECONDS, MAX_RATE_LIMIT_WAIT_SECONDS) * MILLIS_PER_SECOND
            return runCatching { followUp.scheduleFollowUp(delayMs) }
                .fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })
        }
        return drain.retryResult ?: Result.success()
    }

    /**
     * Whether a transient failure has had a fair chance. A count alone dead-lettered everything
     * after ~8 minutes of outage (five WorkManager backoffs); an operation now also keeps retrying
     * until [RETRY_WINDOW_MS] has passed since it was queued.
     */
    private fun retryBudgetExhausted(op: OutboxEntity, attempts: Int): Boolean =
        attempts >= MAX_ATTEMPTS && clock.nowMs() - op.createdAtMs >= RETRY_WINDOW_MS

    private suspend fun handleOutcome(op: OutboxEntity, outcome: Outcome, conflictIndexes: Map<String, ConflictIndex>, drain: Drain) {
        when (outcome) {
            is Outcome.Success -> {
                outboxDao.delete(op)
                if (outcome.pushed) drain.pushedOrgs += op.organizationId
                // Later operations of this run compare against what the server now holds (our own
                // write), not the pre-run copy.
                outcome.server?.let { (conflictIndexes[op.organizationId] as? ConflictIndex.Ready)?.remember(it) }
                outcome.rekeyedTo?.let { drain.rekeyed[op.timeEntryId] = it }
            }
            Outcome.Retry -> {
                val attempts = op.attemptCount + 1
                if (retryBudgetExhausted(op, attempts)) {
                    // Transient retries exhausted -> move to dead-letter and keep draining.
                    deadLetter(op, "Sync failed after $attempts attempts", drain.failedEntryIds)
                } else {
                    outboxDao.update(
                        op.copy(
                            attemptCount = attempts,
                            lastError = "Temporary server or network error; retry scheduled",
                        ),
                    )
                    // Don't abort the whole drain on one transient failure: keep flushing the
                    // remaining independent entries and only ask WorkManager to retry this run.
                    drain.retryResult = Result.retry()
                    drain.defer(op)
                }
            }
            is Outcome.RateLimited -> {
                // Solidtime's per-user API limit is a temporary one-minute window. Do not
                // consume the operation's terminal retry budget: a healthy queued change must
                // not become a permanent sync failure merely because the window stayed closed.
                outboxDao.update(op.copy(lastError = RateLimitMarker.encode(outcome.retryAfterSeconds)))
                drain.defer(op)
                // The limit is per user, so every further request in this run would be refused
                // too (and keep the window closed). Stop and come back when the server says.
                drain.halted = true
                drain.rateLimitedForSeconds = outcome.retryAfterSeconds ?: DEFAULT_RATE_LIMIT_WAIT_SECONDS
            }
            Outcome.AuthRequired -> {
                // The session expired or its refresh failed. Nothing is wrong with the change
                // itself: keep it (and its retry budget) until the account is signed in again.
                drain.defer(op)
                drain.retryResult = Result.retry()
                drain.halted = true
            }
            Outcome.Fail -> {
                // Server rejected the change: this will never succeed, so dead-letter it now.
                deadLetter(op, "Server rejected this change", drain.failedEntryIds)
                syncStatus.set(SyncStatus.Error("A change could not be synced"))
            }
            Outcome.Superseded -> {
                // Stale or already-applied work; drop it rather than reverting newer state.
                outboxDao.delete(op)
            }
        }
    }

    /** Rewrite [OutboxEntity.timeEntryId] through the in-run rekey map, following chained hops. */
    private fun OutboxEntity.rekeyedWith(rekeyed: Map<String, String>): OutboxEntity {
        var id = timeEntryId
        var next = rekeyed[id]
        while (next != null && next != id) {
            id = next
            next = rekeyed[id]
        }
        return if (id == timeEntryId) this else copy(timeEntryId = id)
    }

    /**
     * Move [op] to the terminal dead-letter state. When a CREATE/START that never obtained a
     * server id fails permanently, its dependent ops (STOP/UPDATE/DELETE still keyed by the
     * `local-` id) can never succeed either, so the whole chain is dead-lettered together.
     */
    private suspend fun deadLetter(op: OutboxEntity, error: String, failedEntryIds: MutableSet<String>) {
        val isUnresolvedCreate =
            (op.opType == OutboxOpType.START || op.opType == OutboxOpType.CREATE) &&
                op.timeEntryId.startsWith("local-")
        if (isUnresolvedCreate) {
            failedEntryIds += op.timeEntryId
            outboxDao.deadLetterByEntryId(op.timeEntryId, error)
        } else {
            // op.timeEntryId already reflects any in-run rekey (see rekeyedWith above), so this
            // write-back can't clobber a rekeyed id back to a retired local- id.
            outboxDao.update(op.copy(attemptCount = op.attemptCount + 1, lastError = error, deadLettered = true))
        }
    }

    private sealed class Outcome {
        /**
         * [server] is the authoritative entry after a write that reached the server (null when the
         * op was resolved locally, e.g. by capturing a conflict). [rekeyedTo] is set only when a
         * START/CREATE reconciled a local- id to a new server id.
         */
        data class Success(val server: TimeEntry? = null, val rekeyedTo: String? = null, val pushed: Boolean = server != null) : Outcome()
        data object Retry : Outcome()
        data class RateLimited(val retryAfterSeconds: Long?) : Outcome()

        /** HTTP 401 after the authenticator gave up: wait for sign-in, never a rejection. */
        data object AuthRequired : Outcome()
        data object Fail : Outcome()

        /** Stale or already-applied work; drop without touching the server. */
        data object Superseded : Outcome()
    }

    private suspend fun process(op: OutboxEntity, conflictIndexes: Map<String, ConflictIndex>): Outcome = try {
        if (timeEntryDao.getById(op.timeEntryId)?.syncState == SyncState.CONFLICT && op.opType != OutboxOpType.STOP) {
            // The conflict owns the row until Review resolves it. A queued STOP survives: time
            // capture must never be blocked, or the server timer keeps running.
            outboxDao.deleteNonStopByTimeEntryId(op.timeEntryId)
            Outcome.Superseded
        } else {
            checkConflict(op, conflictIndexes) ?: processOperation(op)
        }
    } catch (e: HttpException) {
        if (e.code() == HTTP_NOT_FOUND && op.opType in CONFLICT_CHECK_OPS && op.baseSnapshotJson != null) {
            markConflict(op.timeEntryId, ConflictSnapshot.DELETED_MARKER)
            Outcome.Success()
        } else {
            classify(e)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "Outbox op ${op.id} failed")
        classify(e)
    }

    private suspend fun checkConflict(op: OutboxEntity, conflictIndexes: Map<String, ConflictIndex>): Outcome? {
        if (op.opType !in CONFLICT_CHECK_OPS || op.baseSnapshotJson == null) return null
        val baseSnapshotJson = op.baseSnapshotJson
        return when (val index = conflictIndexes[op.organizationId]) {
            is ConflictIndex.Failed -> when {
                index.rateLimited -> Outcome.RateLimited(index.retryAfterSeconds)
                index.authRequired -> Outcome.AuthRequired
                else -> Outcome.Retry
            }
            null -> Outcome.Retry
            is ConflictIndex.Ready -> {
                val server = index.entries[op.timeEntryId]
                if (server == null) {
                    markConflict(op.timeEntryId, ConflictSnapshot.DELETED_MARKER)
                    Outcome.Success()
                } else {
                    val base = json.decodeFromString<ConflictSnapshot>(baseSnapshotJson)
                    if (base.matches(server.toConflictSnapshot())) {
                        null
                    } else {
                        markConflict(op.timeEntryId, json.encodeToString(server))
                        Outcome.Success()
                    }
                }
            }
        }
    }

    private suspend fun processOperation(op: OutboxEntity): Outcome = when (op.opType) {
        OutboxOpType.START -> processStart(op)
        OutboxOpType.CREATE -> processCreate(op)
        OutboxOpType.STOP -> processStop(op)
        OutboxOpType.UPDATE -> processUpdate(op)
        OutboxOpType.DELETE -> processDelete(op)
    }

    private suspend fun processStart(op: OutboxEntity): Outcome.Success {
        val payload = json.decodeFromString<StartPayload>(op.payloadJson)
        // Adopt an active entry only when it is *this* START whose response was lost: same user,
        // organization and start instant. The active endpoint is account-wide, so any other
        // running timer (started on the web, the tile or another device) is not ours to take
        // over; queued STOP/UPDATE operations would otherwise rewrite it.
        // A failed lookup is not evidence that the account is idle: posting in that window can
        // duplicate a timer whose first response was lost.
        val expectedStart = payload.start.ifBlank { timeEntryDao.getById(op.timeEntryId)?.start.orEmpty() }
        val adopted = remote.getActiveTimeEntry().getOrThrow()?.takeIf {
            it.userId == payload.userId &&
                it.organizationId == op.organizationId &&
                sameInstant(it.start, expectedStart)
        }
        val server = adopted ?: remote.startTimeEntry(
            op.organizationId,
            payload.memberId,
            payload.userId,
            payload.projectId,
            payload.taskId,
            payload.description,
            startTime = payload.start,
            tagIds = payload.tagIds,
            billable = payload.billable,
        ).getOrThrow()
        return reconcile(op, server, fallbackTagIds = payload.tagIds)
    }

    private suspend fun processCreate(op: OutboxEntity): Outcome.Success {
        val queuedPayload = json.decodeFromString<CreatePayload>(op.payloadJson)
        val matchingBeforeWrite = matchingCreatedEntries(op.organizationId, queuedPayload)
        val payload = if (queuedPayload.recoveryBaselineEntryIds == null) {
            queuedPayload.copy(recoveryBaselineEntryIds = matchingBeforeWrite.map { it.id }).also { prepared ->
                // Persist the baseline before the POST. If the server commits but the response is
                // lost (or the process dies), the next worker can distinguish that new entry from
                // identical entries that predated this operation, including Duplicate's source.
                outboxDao.update(op.copy(payloadJson = json.encodeToString(prepared)))
            }
        } else {
            queuedPayload
        }
        val adopted = queuedPayload.recoveryBaselineEntryIds?.let { baseline ->
            matchingBeforeWrite.firstOrNull { it.id !in baseline }
        }
        val server = adopted ?: run {
            val local = TimeEntry(
                id = op.timeEntryId,
                userId = payload.userId,
                organizationId = op.organizationId,
                start = payload.start,
                end = payload.end,
                description = payload.description,
                projectId = payload.projectId,
                taskId = payload.taskId,
                billable = payload.billable,
                type = payload.type,
            )
            remote.createTimeEntry(
                op.organizationId,
                payload.memberId,
                payload.userId,
                local,
                payload.tagIds,
            ).getOrThrow()
        }
        return reconcile(op, server, fallbackTagIds = payload.tagIds)
    }

    private suspend fun processStop(op: OutboxEntity): Outcome {
        val payload = json.decodeFromString<StopPayload>(op.payloadJson)
        // A STOP only carries an end, so it skips the history-based content check. It must still
        // never overwrite a stop made elsewhere: the local row may be a stale "running" copy of an
        // entry that was already stopped on the web, and PUTting our end would replace the real
        // one. Only stop what the server still reports as this user's running timer.
        val active = remote.getActiveTimeEntry().getOrThrow()
        if (active?.id != op.timeEntryId) return stoppedElsewhere(op)
        val server = remote.stopTimeEntry(
            op.organizationId,
            op.timeEntryId,
            payload.userId,
            payload.start,
            endTime = payload.end,
        ).getOrThrow()
        val current = timeEntryDao.getById(op.timeEntryId)
        if (current?.syncState == SyncState.CONFLICT) {
            // The user-confirmed stop must reach the server without silently choosing either side
            // of an unrelated metadata conflict. Preserve the local side and refresh the server
            // recovery copy with its now-completed state for the later conflict decision.
            timeEntryDao.upsert(
                current.copy(
                    end = server.end,
                    duration = server.duration,
                    updatedAt = clock.nowMs(),
                    conflictServerJson = json.encodeToString(server),
                ),
            )
            return Outcome.Success(server)
        }
        val unresolvedMetadata = outboxDao.getUpdatesBeforeStop(op.timeEntryId, op.id)
        if (current != null && unresolvedMetadata.isNotEmpty()) {
            // A failed metadata UPDATE remains authoritative locally even if STOP succeeds.
            // Advance its interval so retry cannot restart the timer and a pull cannot erase the
            // user's description or catalogue selections.
            database.withTransaction {
                unresolvedMetadata.forEach { updateOperation ->
                    val updatePayload = json.decodeFromString<UpdatePayload>(updateOperation.payloadJson)
                    outboxDao.update(
                        updateOperation.copy(
                            payloadJson = json.encodeToString(updatePayload.copy(start = server.start, end = server.end)),
                        ),
                    )
                }
                outboxDao.rebaseOthersForEntry(op.timeEntryId, op.id, json.encodeToString(server.toConflictSnapshot()))
                timeEntryDao.upsert(
                    current.copy(
                        start = server.start,
                        end = server.end,
                        duration = server.duration,
                        updatedAt = clock.nowMs(),
                        syncState = SyncState.PENDING,
                    ),
                )
            }
        } else {
            persistSynced(op, server)
        }
        return Outcome.Success(server)
    }

    /**
     * The server no longer runs the entry this STOP targets: it was stopped (or deleted) by
     * another client. Keep the server's end instead of overwriting it. With nothing else queued
     * for the row, hand it back to pulls so the next refresh adopts the authoritative end (or
     * removes a deleted entry); later queued edits keep the row pending and face their own
     * conflict check. A conflicted row stays conflicted for Review.
     */
    private suspend fun stoppedElsewhere(op: OutboxEntity): Outcome {
        database.withTransaction {
            val row = timeEntryDao.getById(op.timeEntryId) ?: return@withTransaction
            if (row.syncState == SyncState.PENDING && !row.pendingDelete && outboxDao.countOthersForEntry(op.timeEntryId, op.id) == 0) {
                timeEntryDao.upsert(row.copy(syncState = SyncState.SYNCED, updatedAt = clock.nowMs()))
            }
        }
        Timber.i("Dropped a queued stop for an entry that is no longer running on the server")
        return Outcome.Superseded
    }

    private suspend fun processUpdate(op: OutboxEntity): Outcome {
        // A newer queued UPDATE replaces the same full content and a DELETE removes the row.
        if (outboxDao.countNewerContentMutations(op.timeEntryId, op.id) > 0) return Outcome.Superseded
        val payload = json.decodeFromString<UpdatePayload>(op.payloadJson)
        val entry = TimeEntry(
            id = op.timeEntryId,
            description = payload.description,
            userId = payload.userId,
            start = payload.start,
            end = payload.end,
            projectId = payload.projectId,
            taskId = payload.taskId,
            billable = payload.billable,
            organizationId = op.organizationId,
            type = payload.type,
        )
        val server = remote.updateTimeEntry(op.organizationId, entry, payload.tagIds).getOrThrow()
        database.withTransaction {
            // This UPDATE carried the entry's complete state, so older parked writes are obsolete:
            // reviving one later ("Retry all") would revert this edit.
            outboxDao.deleteParkedWritesBefore(op.timeEntryId, op.id, includeStops = payload.end != null)
            persistSynced(op, server, payload.tagIds)
        }
        return Outcome.Success(server)
    }

    private suspend fun processDelete(op: OutboxEntity): Outcome.Success {
        if (op.timeEntryId.startsWith("local-")) return Outcome.Success()
        remote.deleteTimeEntry(op.organizationId, op.timeEntryId).getOrThrow()
        database.withTransaction {
            timeEntryDao.clearTagRefs(op.timeEntryId)
            timeEntryDao.deleteById(op.timeEntryId)
            // Nothing else can apply to an entry that no longer exists.
            outboxDao.deleteByTimeEntryId(op.timeEntryId)
        }
        return Outcome.Success(pushed = true)
    }

    private suspend fun markConflict(entryId: String, serverJson: String) {
        database.withTransaction {
            val local = timeEntryDao.getById(entryId) ?: return@withTransaction
            timeEntryDao.upsert(local.copy(syncState = SyncState.CONFLICT, conflictServerJson = serverJson))
            // Review now owns the entry's content. A queued STOP still applies: stopping is the one
            // mutation allowed on a conflicted row.
            outboxDao.deleteNonStopByTimeEntryId(entryId)
        }
    }

    private suspend fun loadConflictIndexes(ops: List<OutboxEntity>): Map<String, ConflictIndex> {
        val byOrganization = ops
            .filter { it.opType in CONFLICT_CHECK_OPS && it.baseSnapshotJson != null }
            .groupBy { it.organizationId }
        var halted: ConflictIndex.Failed? = null
        return byOrganization.mapValues { (organizationId, organizationOps) ->
            // The rate limit and the session are per user: once one fetch hits either, fetching the
            // other organizations would only fail the same way (and keep the window closed).
            halted?.let { return@mapValues it }
            runCatching {
                val memberId = memberIdFor(organizationId, ops.filter { it.organizationId == organizationId })
                val entries = mutableMapOf<String, TimeEntry>()
                conflictWindows(organizationOps).forEach { (start, end) ->
                    fetchCompleteWindow(organizationId, memberId, start, end).associateByTo(entries) { it.id }
                }
                ConflictIndex.Ready(entries)
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                Timber.w(error, "Could not fetch conflict comparison data")
                val http = error as? HttpException
                ConflictIndex.Failed(
                    rateLimited = http?.code() == HTTP_TOO_MANY_REQUESTS,
                    retryAfterSeconds = http?.takeIf { it.code() == HTTP_TOO_MANY_REQUESTS }?.retryAfterSeconds(),
                    authRequired = http?.code() == HTTP_UNAUTHORIZED,
                ).also { failed -> if (failed.rateLimited || failed.authRequired) halted = failed }
            }
        }
    }

    /**
     * Every server entry in one window, or an exception. "Missing from the result" is read as
     * "deleted on the server", so a scan that stopped at the safety cap must never be trusted as
     * complete: it would mark older entries deleted and Keep mine would re-create duplicates.
     */
    private suspend fun fetchCompleteWindow(organizationId: String, memberId: String, start: String?, end: String?): List<TimeEntry> {
        val entries = mutableListOf<TimeEntry>()
        var offset = 0
        while (offset < MAX_PAGE_SCAN) {
            val response = remote.getTimeEntries(
                TimeEntriesQuery(
                    organizationId = organizationId,
                    memberId = memberId,
                    limit = PAGE_SIZE,
                    offset = offset,
                    onlyFullDates = false,
                    start = start,
                    end = end,
                ),
            ).getOrThrow()
            entries += response.data
            if (response.data.isEmpty() ||
                response.data.size < PAGE_SIZE ||
                offset + response.data.size >= (response.meta?.total ?: Int.MAX_VALUE)
            ) {
                return entries
            }
            offset += response.data.size
        }
        throw IOException("Conflict comparison window exceeded the scan limit")
    }

    /**
     * The organization's member id, needed by the history filter. Queued START/CREATE payloads
     * carry it; otherwise the Room membership cache (refreshed by pulls, cleared with the
     * account) answers. Only a cold cache pays for the 1 + N membership/organization requests,
     * and their result is cached for the next run.
     */
    private suspend fun memberIdFor(organizationId: String, ops: List<OutboxEntity>): String {
        val payloadMember = ops.firstNotNullOfOrNull { op ->
            when (op.opType) {
                OutboxOpType.START -> runCatching {
                    json.decodeFromString<StartPayload>(op.payloadJson).memberId
                }.getOrNull()
                OutboxOpType.CREATE -> runCatching {
                    json.decodeFromString<CreatePayload>(op.payloadJson).memberId
                }.getOrNull()
                else -> null
            }
        }
        if (!payloadMember.isNullOrBlank()) return payloadMember
        database.catalogDao().getMembershipForOrganization(organizationId)?.let { return it.id }
        val memberships = remote.getMyMemberships().getOrThrow()
        database.catalogDao().upsertMemberships(memberships.map { it.toEntity() })
        return memberships.firstOrNull { it.organizationId == organizationId }?.id
            ?: throw IOException("No membership available for queued sync")
    }

    /**
     * Windows of +/- one day around each queued entry (its last server-acked start and its local
     * start), merged where they overlap. Solidtime filters both bounds by start time, so this
     * finds the entries unless another client moved them by more than a day. The previous single
     * window stretched to "now" and re-downloaded the whole history since the oldest edit.
     */
    private suspend fun conflictWindows(ops: List<OutboxEntity>): List<Pair<String?, String?>> {
        val padding = Duration.ofDays(1).toMillis()
        var unknownPosition = false
        val ranges = buildList {
            ops.forEach { op ->
                val instants = buildList {
                    runCatching { json.decodeFromString<ConflictSnapshot>(op.baseSnapshotJson!!) }.getOrNull()?.startMs?.let(::add)
                    timeEntryDao.getById(op.timeEntryId)?.start?.let(::parseTimeEntryInstant)?.toEpochMilli()?.let(::add)
                }
                if (instants.isEmpty()) unknownPosition = true
                instants.forEach { add(it - padding to it + padding) }
            }
        }
        // Without any timestamp the only complete answer is the unbounded history.
        if (unknownPosition || ranges.isEmpty()) return listOf(null to null)
        val merged = mutableListOf<Pair<Long, Long>>()
        ranges.sortedBy { it.first }.forEach { range ->
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.second) {
                merged[merged.lastIndex] = last.first to maxOf(last.second, range.second)
            } else {
                merged += range
            }
        }

        // Solidtime's time-entry filters require `Y-m-dTH:i:sZ` exactly. Instant.toString()
        // includes a fractional component whenever the device clock has non-zero milliseconds,
        // causing the conflict preflight GET to fail validation before UPDATE/DELETE can run.
        fun wholeSecondUtc(epochMs: Long) = Instant.ofEpochMilli(epochMs)
            .truncatedTo(ChronoUnit.SECONDS)
            .toString()
        return merged.map { (start, end) -> wholeSecondUtc(start) to wholeSecondUtc(end) }
    }

    /** Return every server entry that could be this CREATE, scanning the bounded timestamp window. */
    private suspend fun matchingCreatedEntries(orgId: String, p: CreatePayload): List<TimeEntry> {
        val matches = mutableListOf<TimeEntry>()
        val payloadStart = parseTimeEntryInstant(p.start)
        val payloadEnd = parseTimeEntryInstant(p.end)
        var offset = 0
        while (offset < MAX_PAGE_SCAN) {
            val response = remote.getTimeEntries(
                TimeEntriesQuery(
                    organizationId = orgId,
                    memberId = p.memberId,
                    limit = PAGE_SIZE,
                    offset = offset,
                    onlyFullDates = false,
                    start = SolidtimeTimestamps.utc(p.start),
                    end = SolidtimeTimestamps.utc(p.end),
                ),
            ).getOrThrow()
            matches += response.data.filter { entry ->
                parseTimeEntryInstant(entry.start) == payloadStart &&
                    entry.end?.let(::parseTimeEntryInstant) == payloadEnd &&
                    entry.projectId == p.projectId &&
                    entry.taskId == p.taskId &&
                    (entry.description ?: "") == p.description
            }
            if (response.data.isEmpty() ||
                response.data.size < PAGE_SIZE ||
                offset + response.data.size >= (response.meta?.total ?: Int.MAX_VALUE)
            ) {
                break
            }
            offset += response.data.size
        }
        return matches
    }

    /**
     * Land a START/CREATE reply: move the optimistic `local-` row and every dependent outbox op to
     * the server id. When later operations for the entry are still queued (or parked), the local
     * row keeps describing the user's newer state and stays PENDING; adopting the reply would make
     * a queued edit vanish until it synced, and a failed edit could then be lost. Only the last
     * operation lets the authoritative copy replace the row as SYNCED.
     */
    private suspend fun reconcile(op: OutboxEntity, server: TimeEntry, fallbackTagIds: List<String>? = null): Outcome.Success {
        val localId = op.timeEntryId
        var rekeyedTo: String? = null
        database.withTransaction {
            val local = timeEntryDao.getById(localId)
            val localTagIds = timeEntryDao.tagIdsFor(localId)
            if (localId != server.id) {
                // The authoritative row may already have arrived through a pull while this local
                // START/CREATE was waiting. TimeEntryDao.rekey merges that collision safely; then
                // every dependent outbox operation moves to the authoritative id atomically.
                timeEntryDao.rekey(localId, server.id)
                outboxDao.rekeyReferences(localId, server.id)
                rekeyedTo = server.id
            }
            if (local != null && outboxDao.countOthersForEntry(server.id, op.id) > 0) {
                timeEntryDao.upsert(local.copy(id = server.id, updatedAt = clock.nowMs(), syncState = SyncState.PENDING))
                timeEntryDao.replaceTagRefs(server.id, localTagIds)
                outboxDao.rebaseOthersForEntry(server.id, op.id, json.encodeToString(server.toConflictSnapshot()))
            } else {
                // The user may have soft-deleted this entry while its START/CREATE was in flight;
                // the row must stay hidden until the delete commits.
                val pendingDelete = timeEntryDao.getById(server.id)?.pendingDelete ?: false
                timeEntryDao.upsert(
                    server.toEntity(updatedAt = clock.nowMs(), syncState = SyncState.SYNCED, pendingDelete = pendingDelete),
                )
                // Preserve the server's authoritative tag set; only fall back to the queued tags
                // when the server returned none (avoids clobbering a server-side tag merge).
                val tagIds = server.tags.map { it.id }.ifEmpty { fallbackTagIds.orEmpty() }
                timeEntryDao.replaceTagRefs(server.id, tagIds)
            }
        }
        return Outcome.Success(server = server, rekeyedTo = rekeyedTo)
    }

    /**
     * Land a STOP/UPDATE reply. While other operations for the entry remain, the local row keeps
     * the user's newer state as PENDING (and their bases advance to this reply); otherwise the
     * authoritative copy replaces it as SYNCED.
     */
    private suspend fun persistSynced(op: OutboxEntity, server: TimeEntry, fallbackTagIds: List<String>? = null) {
        database.withTransaction {
            val current = timeEntryDao.getById(server.id)
            if (current != null && outboxDao.countOthersForEntry(server.id, op.id) > 0) {
                if (current.syncState != SyncState.PENDING) {
                    timeEntryDao.upsert(current.copy(syncState = SyncState.PENDING, updatedAt = clock.nowMs()))
                }
                outboxDao.rebaseOthersForEntry(server.id, op.id, json.encodeToString(server.toConflictSnapshot()))
            } else {
                timeEntryDao.upsert(server.toEntity(updatedAt = clock.nowMs(), syncState = SyncState.SYNCED))
                val tagIds = server.tags.map { it.id }.ifEmpty { fallbackTagIds.orEmpty() }
                timeEntryDao.replaceTagRefs(server.id, tagIds)
            }
        }
    }

    private fun classify(e: Exception): Outcome = when {
        e is IOException -> Outcome.Retry
        // A 401 reaches us only after TokenAuthenticator could not refresh (network trouble or a
        // revoked session). The change is fine; it needs a working session, not a dead-letter.
        e is HttpException && e.code() == HTTP_UNAUTHORIZED -> Outcome.AuthRequired
        e is HttpException && e.code() == HTTP_REQUEST_TIMEOUT -> Outcome.Retry
        e is HttpException && e.code() == HTTP_TOO_MANY_REQUESTS -> Outcome.RateLimited(e.retryAfterSeconds())
        e is HttpException && e.code() >= HTTP_SERVER_ERROR_START -> Outcome.Retry
        else -> Outcome.Fail
    }

    /** Numeric Retry-After only; an HTTP-date form is treated as unknown. */
    private fun HttpException.retryAfterSeconds(): Long? = response()?.headers()?.get("Retry-After")?.trim()?.toLongOrNull()

    private fun sameInstant(a: String, b: String): Boolean {
        val first = parseTimeEntryInstant(a) ?: return false
        val second = parseTimeEntryInstant(b) ?: return false
        return abs(first.toEpochMilli() - second.toEpochMilli()) <= START_MATCH_TOLERANCE_MS
    }

    companion object {
        private const val PAGE_SIZE = 250
        private const val MAX_PAGE_SCAN = 15_000
        private const val HTTP_UNAUTHORIZED = 401
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_REQUEST_TIMEOUT = 408
        private const val HTTP_TOO_MANY_REQUESTS = 429
        private const val HTTP_SERVER_ERROR_START = 500
        private const val MILLIS_PER_SECOND = 1_000L

        /** Used when a 429 carries no numeric Retry-After; Solidtime's window is one minute. */
        const val DEFAULT_RATE_LIMIT_WAIT_SECONDS = 60L
        const val MIN_RATE_LIMIT_WAIT_SECONDS = 5L
        const val MAX_RATE_LIMIT_WAIT_SECONDS = 15 * 60L

        /** Solidtime stores whole seconds; allow for rounding of our own echoed start. */
        private const val START_MATCH_TOLERANCE_MS = 1_000L

        // STOP is intentionally excluded: its wire request only sets `end`, so it cannot overwrite
        // server-side metadata and must remain available even when the history endpoint used for
        // content conflict checks is temporarily unavailable. It checks the active timer instead.
        private val CONFLICT_CHECK_OPS = setOf(OutboxOpType.UPDATE, OutboxOpType.DELETE)

        /** Minimum transient retries before an op may move to the dead-letter state... */
        const val MAX_ATTEMPTS = 8

        /** ...and it must also have been retrying for at least this long since it was queued. */
        const val RETRY_WINDOW_MS = 24 * 60 * 60 * 1_000L

        /** WorkManager normally serializes this unique work, but a restart/cancellation race can
         * still construct two workers in one process. Never POST the same outbox snapshot twice. */
        private val drainMutex = Mutex()

        /** True while a worker in this process is draining the outbox (see [SyncScheduler.requestSync]). */
        fun isDraining(): Boolean = drainMutex.isLocked
    }
}

private sealed class ConflictIndex {
    data class Ready(val entries: MutableMap<String, TimeEntry>) : ConflictIndex() {
        fun remember(server: TimeEntry) {
            entries[server.id] = server
        }
    }
    data class Failed(val rateLimited: Boolean, val retryAfterSeconds: Long? = null, val authRequired: Boolean = false) : ConflictIndex()
}

private fun TimeEntry.toConflictSnapshot(): ConflictSnapshot = ConflictSnapshot.of(
    start = start,
    end = end,
    description = description,
    projectId = projectId,
    taskId = taskId,
    billable = billable,
    tagIds = tags.map { it.id },
    type = type,
)
