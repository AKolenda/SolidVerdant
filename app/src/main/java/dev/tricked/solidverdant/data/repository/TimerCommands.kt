/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.repository

import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.domain.time.parseTimeEntryInstant
import dev.tricked.solidverdant.sync.SyncTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Start and stop commands for surfaces outside the app UI (notification actions, the Quick
 * Settings tile, the home-screen widget).
 *
 * They used to call the API directly, bypassing Room and the outbox: a notification Stop while
 * the timer's START was still queued found nothing on the server and went idle (the queued START
 * later started a server timer nobody stopped), a tile Start was invisible to Track until the
 * next pull, and the widget Stop hid the notification when Room did not know the server's timer.
 * Every mutation here is an optimistic Room write plus an outbox operation through
 * [TimeEntryRepository], followed by a sync request. The server is only *read*, to find a
 * running timer Room does not know yet.
 */
@Singleton
class TimerCommands @Inject constructor(
    private val authRepository: AuthRepository,
    private val timeEntryRepository: TimeEntryRepository,
    private val settingsDataStore: SettingsDataStore,
    private val syncTrigger: SyncTrigger,
) {
    /** Who a surface acts for. [memberId] is the organization membership id START needs. */
    data class Account(val organizationId: String, val memberId: String, val userId: String)

    sealed interface StartResult {
        val entry: TimeEntry

        /** A new timer was written to Room and its START queued. */
        data class Started(override val entry: TimeEntry) : StartResult

        /** A timer was already running (locally or on the server); nothing new was started. */
        data class AlreadyRunning(override val entry: TimeEntry) : StartResult
    }

    sealed interface StopResult {
        /** The timer was stopped locally and its STOP queued (or it was already stopped locally). */
        data class Stopped(val entry: TimeEntry) : StopResult

        /** Neither Room nor the server has a running timer for the account. */
        data object NothingRunning : StopResult

        /** The running timer is not the one the surface showed (a stale action): left alone. */
        data object NotTheExpectedTimer : StopResult
    }

    /**
     * The signed-in account from the offline auth cache, falling back to the network. Null when
     * signed out or no membership is known.
     */
    suspend fun currentAccount(): Account? {
        settingsDataStore.getCachedAuth()?.let { cached ->
            val membership = cached.memberships.firstOrNull { it.id == cached.currentMembershipId } ?: cached.memberships.firstOrNull()
            if (membership != null) return Account(membership.organizationId, membership.id, cached.user.id)
        }
        val membership = authRepository.getCurrentMembership() ?: return null
        val user = authRepository.getCurrentUser().getOrNull() ?: return null
        return Account(membership.organizationId, membership.id, user.id)
    }

    /**
     * Start a timer, unless one is already running: this user's running timer in Room wins, then
     * the account's server timer (cached into Room so Track shows it). A failed server lookup does
     * not block an offline start; the queued START still refuses to take over another timer.
     */
    suspend fun start(
        account: Account,
        projectId: String?,
        taskId: String?,
        description: String,
        tagIds: List<String> = emptyList(),
        billable: Boolean = false,
    ): StartResult {
        timeEntryRepository.localActiveEntry(account.organizationId, account.userId)?.let { return StartResult.AlreadyRunning(it) }
        val serverActive = serverActiveTimer()?.getOrNull()?.takeIf { it.userId == account.userId && !isBeingStoppedLocally(it) }
        if (serverActive != null) {
            val cached = timeEntryRepository.adoptServerEntry(serverActive) ?: serverActive
            return StartResult.AlreadyRunning(cached)
        }
        val started = timeEntryRepository.startEntry(
            organizationId = account.organizationId,
            memberId = account.memberId,
            userId = account.userId,
            projectId = projectId,
            taskId = taskId,
            description = description,
            tagIds = tagIds,
            billable = billable,
        )
        syncTrigger.requestSync()
        return StartResult.Started(started)
    }

    /**
     * Stop the account's running timer. With [expectedStart] (a notification action carries the
     * start it was built for) only that timer is stopped. Room answers first, so a timer whose
     * START is still queued stops offline; otherwise the server's timer is cached into Room and
     * stopped through the outbox. Without [expectedStart] (tile, widget) the server's timer wins
     * over a Room copy that has nothing queued, since that copy may be stale.
     */
    suspend fun stop(organizationId: String?, expectedStart: Instant?): Result<StopResult> = try {
        val account = currentAccount() ?: error("Not signed in")
        val orgId = organizationId ?: account.organizationId
        val local = timeEntryRepository.localActiveEntry(orgId, account.userId)
            ?.takeIf { expectedStart == null || sameSecond(it.start, expectedStart) }
        val result = if (local != null && (expectedStart != null || timeEntryRepository.hasPendingSync(local.id))) {
            // A notification action names its timer; a queued local change is the user's latest
            // intent. Either way the stop needs no network.
            stopLocally(local, account.userId)
        } else {
            val lookup = serverActiveTimer()
            val server = lookup?.getOrNull()
            when {
                lookup == null || lookup.isFailure ->
                    local?.let { stopLocally(it, account.userId) } ?: throw (lookup?.exceptionOrNull() ?: ServerUnreachable())
                server == null || server.userId != account.userId -> StopResult.NothingRunning
                expectedStart != null && !sameSecond(server.start, expectedStart) -> StopResult.NotTheExpectedTimer
                else -> stopLocally(timeEntryRepository.adoptServerEntry(server) ?: server, account.userId)
            }
        }
        Result.success(result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * This account's running timer that exists only locally so far (its START or another change
     * is still queued), which the server's active endpoint cannot report yet.
     */
    suspend fun queuedLocalTimer(): TimeEntry? {
        val account = currentAccount() ?: return null
        return timeEntryRepository.localActiveEntry(account.organizationId, account.userId)
            ?.takeIf { timeEntryRepository.hasPendingSync(it.id) }
    }

    private suspend fun stopLocally(entry: TimeEntry, userId: String): StopResult {
        timeEntryRepository.stopEntry(entry, userId)
        syncTrigger.requestSync()
        return StopResult.Stopped(entry)
    }

    /**
     * The server still reports a timer this device already stopped (or deleted) while the STOP
     * waits in the outbox. Treating it as running would resurrect it in the UI.
     */
    private suspend fun isBeingStoppedLocally(server: TimeEntry): Boolean = timeEntryRepository.isStoppingLocally(server.id)

    /** The account-wide active timer, or null when the lookup timed out. */
    private suspend fun serverActiveTimer(): Result<TimeEntry?>? = withTimeoutOrNull(SERVER_LOOKUP_TIMEOUT_MS) {
        authRepository.getActiveTimeEntry()
    }

    private fun sameSecond(start: String, expected: Instant): Boolean =
        parseTimeEntryInstant(start)?.toEpochMilli()?.let { it / MILLIS_PER_SECOND == expected.toEpochMilli() / MILLIS_PER_SECOND } ?: false

    private class ServerUnreachable : IllegalStateException("The active timer could not be checked")

    private companion object {
        /** Surfaces must answer quickly; an unanswered lookup is treated like being offline. */
        const val SERVER_LOOKUP_TIMEOUT_MS = 5_000L
        const val MILLIS_PER_SECOND = 1_000L
    }
}
