/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.local.db.AppDatabase
import dev.tricked.solidverdant.data.local.db.SyncState
import dev.tricked.solidverdant.data.local.db.toEntity
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.remote.FakeRemoteDataSource
import dev.tricked.solidverdant.util.Clock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import java.time.YearMonth
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class TimeEntryRepositoryReadTest {
    private lateinit var db: AppDatabase
    private lateinit var remote: FakeRemoteDataSource
    private lateinit var repo: TimeEntryRepository
    private val clock = object : Clock {
        var t = 1000L
        override fun nowMs() = t
    }

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        remote = FakeRemoteDataSource()
        repo = TimeEntryRepository(
            db.timeEntryDao(),
            db.catalogDao(),
            db.outboxDao(),
            db.syncMetaDao(),
            remote,
            clock,
            Json { encodeDefaults = true },
            db,
        )
    }

    @After fun teardown() = db.close()

    private fun srv(id: String) = TimeEntry(
        id = id,
        userId = "u",
        start = "2026-01-01T09:00:00Z",
        end = "2026-01-01T10:00:00Z",
        organizationId = "org1",
    )

    @Test fun refresh_upserts_remote_entries_into_room() = runTest {
        remote.entries = listOf(srv("a"), srv("b"))
        repo.refreshAll("org1", "member1")
        val observed = repo.observeTimeEntries("org1").first()
        assertEquals(setOf("a", "b"), observed.map { it.id }.toSet())
    }

    @Test fun refresh_adopts_a_server_active_entry_into_room() = runTest {
        val serverActive = srv("server-active").copy(end = null, duration = null)
        remote.entries = listOf(serverActive)

        assertTrue(repo.refreshAll("org1", "member1").isSuccess)

        val stored = requireNotNull(db.timeEntryDao().getById(serverActive.id))
        assertEquals(SyncState.SYNCED, stored.syncState)
        assertEquals(serverActive.id, repo.observeActiveEntry("org1").first()?.id)
    }

    @Test fun refresh_replaces_a_cached_active_entry_with_the_server_stop() = runTest {
        val cachedActive = srv("server-active").copy(end = null, duration = null)
        val serverStopped = cachedActive.copy(end = "2026-01-01T10:15:00Z", duration = 4500)
        db.timeEntryDao().upsert(cachedActive.toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        remote.entries = listOf(serverStopped)

        assertTrue(repo.refreshAll("org1", "member1").isSuccess)

        val stored = requireNotNull(db.timeEntryDao().getById(serverStopped.id))
        assertEquals(serverStopped.end, stored.end)
        assertEquals(serverStopped.duration, stored.duration)
        assertEquals(SyncState.SYNCED, stored.syncState)
        assertNull(repo.observeActiveEntry("org1").first())
    }

    @Test fun refresh_does_not_resurrect_a_locally_stopped_pending_entry() = runTest {
        val serverActive = srv("server-active").copy(end = null, duration = null)
        val localStopped = serverActive.copy(end = "2026-01-01T10:05:00Z", duration = 3900)
        db.timeEntryDao().upsert(localStopped.toEntity(updatedAt = 2L, syncState = SyncState.PENDING))
        remote.entries = listOf(serverActive)

        assertTrue(repo.refreshAll("org1", "member1").isSuccess)

        val stored = requireNotNull(db.timeEntryDao().getById(localStopped.id))
        assertEquals(localStopped.end, stored.end)
        assertEquals(localStopped.duration, stored.duration)
        assertEquals(SyncState.PENDING, stored.syncState)
        assertNull(repo.observeActiveEntry("org1").first())
    }

    @Test fun refresh_does_not_clobber_newer_pending_local_edit() = runTest {
        // Local edit made at t=5000 (newer)
        db.timeEntryDao().upsert(srv("a").copy(description = "local").toEntity(updatedAt = 5000L, syncState = SyncState.PENDING))
        // Server version is older (t=1000)
        remote.entries = listOf(srv("a").copy(description = "server"))
        clock.t = 1000L
        repo.refreshAll("org1", "member1")
        assertEquals("local", repo.observeTimeEntries("org1").first().first { it.id == "a" }.description)
    }

    @Test fun intermittent_refresh_failure_keeps_the_cached_history_intact() = runTest {
        val cached = srv("cached").copy(description = "offline copy")
        db.timeEntryDao().upsert(cached.toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        remote.timeEntriesQueryValidator = { IOException("network disappeared") }

        val result = repo.refreshAll("org1", "member1")

        assertTrue(result.isFailure)
        assertEquals("offline copy", repo.observeTimeEntries("org1").first().single().description)
    }

    @Test fun refresh_applies_server_edit_when_pulls_share_a_timestamp() = runTest {
        remote.entries = listOf(srv("a").copy(description = "before"))
        repo.refreshAll("org1", "member1")

        // A fast second pull can start in the same millisecond as the first pull completed. That
        // cached server row must not be mistaken for a newer local write.
        remote.entries = listOf(srv("a").copy(description = "after"))
        repo.refreshAll("org1", "member1")

        assertEquals("after", repo.observeTimeEntries("org1").first().single().description)
    }

    @Test fun refresh_propagates_cancellation_during_a_network_call() = runTest {
        remote.projectsGate = CompletableDeferred()

        val failure = runCatching {
            withTimeout(100L) { repo.refreshAll("org1", "member1") }
        }.exceptionOrNull()

        assertTrue(failure is TimeoutCancellationException)
    }

    @Test fun month_load_reaches_back_only_for_entries_crossing_into_the_month() = runTest {
        repo.loadMonth("org1", "member1", YearMonth.of(2026, 7), ZoneId.of("Europe/Amsterdam"))

        val query = requireNotNull(remote.lastTimeEntriesQuery)
        // Two days before 1 July, local midnight in Amsterdam (UTC+2): enough for an entry that
        // crosses midnight into the month without re-downloading the previous month, which the
        // calendar prefetches on its own.
        assertEquals("2026-06-28T22:00:00Z", query.start)
        assertEquals("2026-07-31T22:00:00Z", query.end)
    }

    @Test fun month_load_keeps_an_entry_that_started_the_evening_before() = runTest {
        remote.entries = listOf(
            srv("overnight").copy(start = "2026-06-30T20:00:00Z", end = "2026-07-01T02:00:00Z"),
        )

        repo.loadMonth("org1", "member1", YearMonth.of(2026, 7), ZoneId.of("Europe/Amsterdam"))

        assertEquals(listOf("overnight"), repo.observeTimeEntries("org1").first().map { it.id })
    }

    @Test fun month_load_waits_out_a_rate_limit_and_retries() = runTest {
        var calls = 0
        remote.entries = listOf(srv("after-wait"))
        remote.timeEntriesQueryValidator = {
            calls++
            if (calls == 1) rateLimited(retryAfterSeconds = 2) else null
        }

        repo.loadMonth("org1", "member1", YearMonth.of(2026, 7), ZoneId.of("UTC"))

        assertEquals(2, calls)
        assertEquals(listOf("after-wait"), repo.observeTimeEntries("org1").first().map { it.id })
    }

    private fun rateLimited(retryAfterSeconds: Int): retrofit2.HttpException {
        val raw = okhttp3.Response.Builder()
            .code(429)
            .message("Too Many Requests")
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("https://time.example/api").build())
            .header("Retry-After", retryAfterSeconds.toString())
            .build()
        val body = "".toResponseBody()
        return retrofit2.HttpException(retrofit2.Response.error<Any>(body, raw))
    }

    @Test fun tombstoning_accepts_server_id_sets_larger_than_sqlite_bind_limit() = runTest {
        val missing = srv("missing")
        db.timeEntryDao().upsert(missing.toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))

        db.timeEntryDao().tombstoneMissing(
            orgId = "org1",
            rangeStart = "2026-01-01T00:00:00Z",
            rangeEnd = "2026-01-02T00:00:00Z",
            serverIds = (1..1_200).map { "server-$it" },
        )

        assertNull(db.timeEntryDao().getById(missing.id))
    }
}
