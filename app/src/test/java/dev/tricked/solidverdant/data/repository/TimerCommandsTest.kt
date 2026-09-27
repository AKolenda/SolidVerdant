/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.local.db.AppDatabase
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.local.db.SyncState
import dev.tricked.solidverdant.data.local.db.toEntity
import dev.tricked.solidverdant.data.model.Membership
import dev.tricked.solidverdant.data.model.Organization
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.User
import dev.tricked.solidverdant.data.remote.FakeRemoteDataSource
import dev.tricked.solidverdant.sync.StartPayload
import dev.tricked.solidverdant.util.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.Instant

/** Notification, tile and widget start/stop go through Room + the outbox. */
@RunWith(RobolectricTestRunner::class)
class TimerCommandsTest {
    private lateinit var db: AppDatabase
    private lateinit var repository: TimeEntryRepository
    private lateinit var authRepository: AuthRepository
    private lateinit var commands: TimerCommands
    private val json = Json { encodeDefaults = true }
    private var syncRequests = 0
    private var serverActive: Result<TimeEntry?> = Result.success(null)

    private val organization = Organization("org1", "Org", "EUR")
    private val membership = Membership("member-1", "member", organization)
    private val user = User("user-1", "User", "user@example.invalid")
    private val account = TimerCommands.Account(organization.id, membership.id, user.id)

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = TimeEntryRepository(
            db.timeEntryDao(),
            db.catalogDao(),
            db.outboxDao(),
            db.syncMetaDao(),
            FakeRemoteDataSource(),
            object : Clock {
                override fun nowMs() = 1_000L
            },
            json,
            db,
        )
        authRepository = mockk(relaxed = true) {
            coEvery { getActiveTimeEntry() } answers { serverActive }
        }
        val settings = mockk<SettingsDataStore> {
            every { getCachedAuth() } returns SettingsDataStore.CachedAuth(user, listOf(membership), membership.id)
        }
        commands = TimerCommands(authRepository, repository, settings) { syncRequests += 1 }
    }

    @After fun teardown() = db.close()

    private suspend fun ops() = db.outboxDao().peekAll().map { it.opType to it.timeEntryId }

    private fun serverTimer(id: String = "server-1", start: String = "2026-08-10T08:00:00Z") =
        TimeEntry(id = id, userId = user.id, start = start, organizationId = organization.id, description = "server timer")

    @Test fun notification_stop_while_the_start_is_still_queued_stops_the_local_timer() = runTest {
        val local = repository.startEntry(organization.id, membership.id, user.id, null, null, "offline", emptyList())
        serverActive = Result.success(null) // the server has not heard of the timer yet

        val result = commands.stop(organization.id, Instant.parse(local.start)).getOrThrow()

        assertTrue(result is TimerCommands.StopResult.Stopped)
        assertEquals(listOf(OutboxOpType.START to local.id, OutboxOpType.STOP to local.id), ops())
        assertNotNull(db.timeEntryDao().getById(local.id)?.end)
        coVerify(exactly = 0) { authRepository.getActiveTimeEntry() }
        assertEquals(1, syncRequests)
    }

    @Test fun widget_stop_of_a_queued_local_timer_needs_no_network() = runTest {
        val local = repository.startEntry(organization.id, membership.id, user.id, null, null, "offline", emptyList())
        serverActive = Result.failure(IOException("offline"))

        assertTrue(commands.stop(null, null).getOrThrow() is TimerCommands.StopResult.Stopped)
        assertEquals(OutboxOpType.STOP to local.id, ops().last())
    }

    @Test fun stop_caches_a_server_timer_room_did_not_know_and_queues_its_stop() = runTest {
        serverActive = Result.success(serverTimer())

        assertTrue(commands.stop(null, null).getOrThrow() is TimerCommands.StopResult.Stopped)

        val row = requireNotNull(db.timeEntryDao().getById("server-1"))
        assertNotNull(row.end)
        assertEquals(SyncState.PENDING, row.syncState)
        assertEquals(listOf(OutboxOpType.STOP to "server-1"), ops())
        coVerify(exactly = 0) { authRepository.stopTimeEntry(any(), any(), any(), any(), any()) }
    }

    @Test fun stop_offline_with_no_known_timer_fails_instead_of_pretending_nothing_runs() = runTest {
        serverActive = Result.failure(IOException("offline"))

        assertTrue(commands.stop(null, null).isFailure)
        assertTrue(ops().isEmpty())
    }

    @Test fun stale_notification_action_for_a_replaced_timer_is_ignored() = runTest {
        serverActive = Result.success(serverTimer(id = "replacement", start = "2026-08-10T09:00:00Z"))

        val result = commands.stop(organization.id, Instant.parse("2026-08-10T08:00:00Z")).getOrThrow()

        assertEquals(TimerCommands.StopResult.NotTheExpectedTimer, result)
        assertTrue(ops().isEmpty())
    }

    @Test fun widget_stop_prefers_the_servers_timer_over_a_stale_synced_copy() = runTest {
        val stale = serverTimer(id = "stopped-on-web", start = "2026-08-10T07:00:00Z")
        db.timeEntryDao().upsert(stale.toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        serverActive = Result.success(serverTimer(id = "running-now"))

        commands.stop(null, null).getOrThrow()

        assertEquals(listOf(OutboxOpType.STOP to "running-now"), ops())
    }

    @Test fun start_adopts_the_timer_already_running_on_the_server() = runTest {
        serverActive = Result.success(serverTimer())

        val result = commands.start(account, "project", null, "new")

        assertTrue(result is TimerCommands.StartResult.AlreadyRunning)
        assertEquals("server-1", result.entry.id)
        assertEquals(SyncState.SYNCED, db.timeEntryDao().getById("server-1")?.syncState)
        assertTrue("No second timer may be queued", ops().isEmpty())
    }

    @Test fun start_offline_writes_room_and_queues_the_start() = runTest {
        serverActive = Result.failure(IOException("offline"))

        val result = commands.start(account, "project", "task", "offline start", listOf("tag"))

        assertTrue(result is TimerCommands.StartResult.Started)
        val op = db.outboxDao().peekAll().single()
        assertEquals(OutboxOpType.START, op.opType)
        val payload = json.decodeFromString<StartPayload>(op.payloadJson)
        assertEquals(
            listOf("project", "task", "offline start", membership.id),
            listOf(payload.projectId, payload.taskId, payload.description, payload.memberId),
        )
        assertEquals(listOf("tag"), payload.tagIds)
        assertEquals(1, syncRequests)
    }

    @Test fun resume_after_a_local_pause_starts_a_new_timer_while_the_server_still_runs_the_old_one() = runTest {
        val running = serverTimer()
        db.timeEntryDao().upsert(running.toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        repository.stopEntry(running, user.id) // paused locally, STOP still queued
        serverActive = Result.success(running)

        val result = commands.start(account, null, null, "resumed")

        assertTrue(result is TimerCommands.StartResult.Started)
        assertEquals(listOf(OutboxOpType.STOP, OutboxOpType.START), db.outboxDao().peekAll().map { it.opType })
    }

    @Test fun start_reuses_the_running_room_timer() = runTest {
        val local = repository.startEntry(organization.id, membership.id, user.id, null, null, "running", emptyList())

        val result = commands.start(account, null, null, "second")

        assertTrue(result is TimerCommands.StartResult.AlreadyRunning)
        assertEquals(local.id, result.entry.id)
        assertEquals(1, db.outboxDao().peekAll().size)
    }
}
