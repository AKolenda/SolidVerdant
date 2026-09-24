/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.export.CsvExporter
import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.local.db.AppDatabase
import dev.tricked.solidverdant.data.local.db.MembershipEntity
import dev.tricked.solidverdant.data.local.db.OrganizationEntity
import dev.tricked.solidverdant.data.local.db.SyncState
import dev.tricked.solidverdant.data.local.db.toEntity
import dev.tricked.solidverdant.data.model.TimeEntriesResponse
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.remote.FakeRemoteDataSource
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.TemporalPolicyProvider
import dev.tricked.solidverdant.util.Clock
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Dashboard requests only the bounded window it shows, reuses that response for the cache TTL
 * (Retry / pull to refresh bypasses it), and overlays Room so offline creates, edits and deletions
 * appear without waiting for the next fetch.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatisticsRemoteCacheVmTest {

    private lateinit var db: AppDatabase
    private lateinit var authDataStore: AuthDataStore
    private lateinit var authRepository: AuthRepository
    private lateinit var timeEntryRepository: TimeEntryRepository
    private lateinit var temporalPolicyProvider: TemporalPolicyProvider
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<StatisticsViewModel>()

    private val clock = object : Clock {
        var now = 1_000_000L
        override fun nowMs() = now
    }

    // No cached auth is seeded, so the policy falls back to the device zone.
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private var serverEntries = emptyList<TimeEntry>()
    private val requestedStarts = mutableListOf<String?>()

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        authDataStore = AuthDataStore(context)
        temporalPolicyProvider = TemporalPolicyProvider(dev.tricked.solidverdant.data.local.SettingsDataStore(context))
        authRepository = mockk(relaxed = true)
        coEvery { authRepository.getCurrentMembership() } returns null
        coEvery { authRepository.getTimeEntries(any(), any(), any(), any(), any(), any(), any()) } answers {
            synchronized(requestedStarts) { requestedStarts += arg<String?>(5) }
            Result.success(TimeEntriesResponse(data = serverEntries))
        }
        timeEntryRepository = TimeEntryRepository(
            db.timeEntryDao(),
            db.catalogDao(),
            db.outboxDao(),
            db.syncMetaDao(),
            FakeRemoteDataSource(),
            object : Clock {
                override fun nowMs() = 1L
            },
            Json { encodeDefaults = true },
            db,
        )
    }

    @After
    fun teardown() {
        viewModels.forEach { it.cancelScopeForTest() }
        dispatcher.scheduler.advanceUntilIdle()
        db.close()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    private fun viewModel() = StatisticsViewModel(
        authRepository = authRepository,
        timeEntryRepository = timeEntryRepository,
        csvExporter = mockk<CsvExporter>(relaxed = true),
        authDataStore = authDataStore,
        catalogDao = db.catalogDao(),
        temporalPolicyProvider = temporalPolicyProvider,
        clock = clock,
    ).also { viewModels += it }

    private fun requests(): Int = synchronized(requestedStarts) { requestedStarts.size }

    /** A completed entry of [hours] starting at 09:00 local today (inside [StatRange.ThisWeek]). */
    private fun entry(id: String, hours: Long = 1) = TimeEntry(
        id = id,
        userId = "m1",
        start = today.atTime(9, 0).atZone(zone).toInstant().toString(),
        end = today.atTime(9, 0).atZone(zone).plusHours(hours).toInstant().toString(),
        duration = (hours * 3600).toInt(),
        organizationId = "org1",
    )

    private suspend fun seedMembership() {
        db.catalogDao().upsertOrganizations(listOf(OrganizationEntity(id = "org1", name = "Acme", currency = "USD")))
        db.catalogDao().upsertMemberships(listOf(MembershipEntity(id = "m1", role = "member", organizationId = "org1")))
        authDataStore.saveCurrentMembershipId("m1")
    }

    private suspend fun StatisticsViewModel.settled(predicate: (StatisticsUiState) -> Boolean = { true }): StatisticsUiState {
        dispatcher.scheduler.advanceUntilIdle()
        return uiState.first { !it.isLoading && !it.isRefreshing && it.rangeStart != null && predicate(it) }
    }

    @Test
    fun fetch_is_bounded_to_the_shown_periods_instead_of_the_whole_history() = runTest(dispatcher.scheduler) {
        seedMembership()
        val vm = viewModel()

        val state = vm.settled()

        val previousStart = previousPeriod(state.rangeStart!!..state.rangeEnd!!).start
        val expected = previousStart.minusDays(STATISTICS_CARRY_IN_DAYS).atStartOfDay(zone).toInstant().toString()
        assertEquals(listOf(expected), synchronized(requestedStarts) { requestedStarts.toList() })
    }

    @Test
    fun room_edits_creates_and_deletions_override_the_fetched_snapshot() = runTest(dispatcher.scheduler) {
        seedMembership()
        serverEntries = listOf(entry("srv-edit"), entry("srv-delete"), entry("srv-history"))
        db.timeEntryDao().upsert(entry("srv-edit").toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        db.timeEntryDao().upsert(entry("srv-delete").toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        val vm = viewModel()
        assertEquals(3 * 3600L, vm.settled().summary.totalSeconds)

        // Offline: stretch one entry to three hours, delete another and log a new one.
        timeEntryRepository.updateEntry(entry("srv-edit", hours = 3), emptyList())
        timeEntryRepository.deleteEntry(entry("srv-delete"))
        timeEntryRepository.createCompletedEntry(
            organizationId = "org1",
            memberId = "m1",
            userId = "m1",
            description = "offline",
            projectId = null,
            taskId = null,
            tagIds = emptyList(),
            billable = false,
            start = entry("x").start,
            end = entry("x", hours = 2).end!!,
        )

        // 3h edited + 1h server-only history + 2h created; the deleted hour is gone.
        val state = vm.settled { it.summary.totalSeconds == 6 * 3600L }
        assertEquals(3, state.summary.entryCount)
        assertEquals("No refetch is needed to show local changes", 1, requests())
    }

    @Test
    fun a_synced_deletion_does_not_reappear_from_the_cached_snapshot() = runTest(dispatcher.scheduler) {
        seedMembership()
        serverEntries = listOf(entry("srv-1"), entry("srv-2"))
        db.timeEntryDao().upsert(entry("srv-2").toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        val vm = viewModel()
        assertEquals(2, vm.settled().summary.entryCount)

        // The deletion has synced: the sync worker removed the row, the cached snapshot still has it.
        db.timeEntryDao().deleteById("srv-2")

        assertEquals(1, vm.settled { it.summary.entryCount == 1 }.summary.entryCount)
    }

    @Test
    fun cached_window_is_reused_within_the_ttl_and_refetched_after_it() = runTest(dispatcher.scheduler) {
        seedMembership()
        serverEntries = listOf(entry("srv-1"))
        val vm = viewModel()
        vm.settled()
        assertEquals(1, requests())

        vm.setRange(StatRange.LastWeek)
        vm.settled { it.range == StatRange.LastWeek }
        vm.setRange(StatRange.ThisWeek)
        vm.settled { it.range == StatRange.ThisWeek }
        assertEquals("Last week is fetched once; this week comes from the cache", 2, requests())

        clock.now += STATISTICS_CACHE_TTL_MS
        vm.setRange(StatRange.LastWeek)
        vm.settled { it.range == StatRange.LastWeek }
        assertEquals("An expired window is fetched again", 3, requests())
    }

    @Test
    fun other_projects_drill_down_lists_the_folded_projects_from_server_and_room() = runTest(dispatcher.scheduler) {
        seedMembership()
        serverEntries = listOf(entry("srv-small").copy(projectId = "small"), entry("srv-big").copy(projectId = "big"))
        db.timeEntryDao().upsert(entry("room-small").copy(projectId = "small").toEntity(updatedAt = 1L, syncState = SyncState.PENDING))
        val vm = viewModel()
        vm.settled { it.summary.entryCount == 3 }

        vm.openOtherProjectsDrillDown(setOf("small"))
        dispatcher.scheduler.advanceUntilIdle()

        val drillDown = vm.drillDown.first { it != null && !it.isLoading }!!
        assertEquals(setOf("srv-small", "room-small"), drillDown.rows.map { it.entryId }.toSet())
    }

    @Test
    fun refresh_bypasses_the_ttl() = runTest(dispatcher.scheduler) {
        seedMembership()
        serverEntries = listOf(entry("srv-1"))
        val vm = viewModel()
        vm.settled()

        serverEntries = listOf(entry("srv-1"), entry("srv-2"))
        vm.refresh()

        val state = vm.settled { it.summary.entryCount == 2 }
        assertEquals(2, requests())
        assertTrue(!state.refreshFailed)
    }
}
