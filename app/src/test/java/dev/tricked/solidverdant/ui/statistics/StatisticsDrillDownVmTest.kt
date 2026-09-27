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
import dev.tricked.solidverdant.data.model.OrganizationMember
import dev.tricked.solidverdant.data.model.TimeEntriesResponse
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.remote.FakeRemoteDataSource
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.TemporalPolicyProvider
import dev.tricked.solidverdant.util.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneId

/**
 * Drill-down lists: an Estimates row lists every entry on its project (all dates, every member the
 * account may see, Room's copies first), and a range list opened early fills in when the server's
 * entries arrive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class StatisticsDrillDownVmTest {

    private lateinit var db: AppDatabase
    private lateinit var authDataStore: AuthDataStore
    private lateinit var authRepository: AuthRepository
    private lateinit var timeEntryRepository: TimeEntryRepository
    private lateinit var temporalPolicyProvider: TemporalPolicyProvider
    private val dispatcher = StandardTestDispatcher()
    private val viewModels = mutableListOf<StatisticsViewModel>()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val estimate = EstimateProgress(id = "job", name = "Job 42", colorHex = "#336699", estimatedSeconds = 3600, spentSeconds = 7200)

    @Before
    fun setup() {
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        authDataStore = AuthDataStore(context)
        temporalPolicyProvider = TemporalPolicyProvider(dev.tricked.solidverdant.data.local.SettingsDataStore(context))
        authRepository = mockk(relaxed = true)
        coEvery { authRepository.getCurrentMembership() } returns null
        coEvery { authRepository.getTimeEntries(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(TimeEntriesResponse(data = emptyList()))
        coEvery { authRepository.getMembers(any()) } returns Result.success(emptyList())
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
        clock = object : Clock {
            override fun nowMs() = 1_000_000L
        },
    ).also { viewModels += it }

    /** A completed [hours]-long entry on [projectId] that starts at 09:00 local [daysAgo] days ago. */
    private fun entry(id: String, daysAgo: Long = 0, hours: Long = 1, userId: String = "u1", projectId: String? = "job") = TimeEntry(
        id = id,
        userId = userId,
        start = today.minusDays(daysAgo).atTime(9, 0).atZone(zone).toInstant().toString(),
        end = today.minusDays(daysAgo).atTime(9, 0).atZone(zone).plusHours(hours).toInstant().toString(),
        projectId = projectId,
        organizationId = "org1",
    )

    private suspend fun seedMembership() {
        db.catalogDao().upsertOrganizations(listOf(OrganizationEntity(id = "org1", name = "Acme", currency = "USD")))
        db.catalogDao().upsertMemberships(listOf(MembershipEntity(id = "m1", role = "manager", organizationId = "org1")))
        authDataStore.saveCurrentMembershipId("m1")
    }

    private fun forbidden(): HttpException = HttpException(Response.error<Any>(403, "{}".toResponseBody()))

    private suspend fun StatisticsViewModel.openedDrillDown(predicate: (DrillDownUiState) -> Boolean): DrillDownUiState {
        dispatcher.scheduler.advanceUntilIdle()
        return drillDown.first { it != null && !it.isLoading && predicate(it) }!!
    }

    @Test
    fun an_estimate_lists_every_members_entries_on_the_project_across_all_dates() = runTest(dispatcher.scheduler) {
        seedMembership()
        coEvery { authRepository.getAllProjectTimeEntries("org1", "job", null) } returns Result.success(
            listOf(
                entry("mine-old", daysAgo = 200, hours = 2),
                entry("sylvain", daysAgo = 40, hours = 3, userId = "u2"),
                entry("edited-here", daysAgo = 1),
            ),
        )
        coEvery { authRepository.getMembers("org1") } returns
            Result.success(listOf(OrganizationMember("m1", "u1", "Alex"), OrganizationMember("m2", "u2", "Sylvain")))
        // Room's copy wins: stretched to four hours on this phone and not yet synced.
        db.timeEntryDao().upsert(entry("edited-here", daysAgo = 1, hours = 4).toEntity(updatedAt = 1L, syncState = SyncState.PENDING))
        val vm = viewModel()
        vm.uiState.first { !it.isLoading }

        vm.openEstimateDrillDown(estimate)
        val state = vm.openedDrillDown { !it.isRefreshing }

        assertEquals(listOf("edited-here", "sylvain", "mine-old"), state.rows.map { it.entryId })
        assertEquals((4 + 3 + 2) * 3600L, state.totalSeconds)
        assertEquals(listOf("Alex", "Sylvain", "Alex"), state.rows.map { it.memberName })
        assertFalse(state.ownEntriesOnly)
        assertFalse(state.loadFailed)
    }

    @Test
    fun a_role_without_access_to_others_time_gets_its_own_entries_and_a_note() = runTest(dispatcher.scheduler) {
        seedMembership()
        coEvery { authRepository.getAllProjectTimeEntries("org1", "job", null) } returns Result.failure(forbidden())
        coEvery { authRepository.getAllProjectTimeEntries("org1", "job", "m1") } returns
            Result.success(listOf(entry("mine", daysAgo = 90, hours = 2)))
        val vm = viewModel()
        vm.uiState.first { !it.isLoading }

        vm.openEstimateDrillDown(estimate)
        val state = vm.openedDrillDown { !it.isRefreshing }

        assertEquals(listOf("mine"), state.rows.map { it.entryId })
        assertTrue(state.ownEntriesOnly)
        assertEquals(listOf<String?>(null), state.rows.map { it.memberName })
    }

    @Test
    fun an_unreachable_server_lists_this_phones_entries_and_retry_fetches_again() = runTest(dispatcher.scheduler) {
        seedMembership()
        coEvery { authRepository.getAllProjectTimeEntries("org1", "job", null) } returns Result.failure(IOException("offline"))
        db.timeEntryDao().upsert(entry("cached", daysAgo = 3).toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        db.timeEntryDao().upsert(entry("other-job", projectId = "else").toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        val vm = viewModel()
        vm.uiState.first { !it.isLoading }

        vm.openEstimateDrillDown(estimate)
        val offline = vm.openedDrillDown { it.loadFailed }
        assertEquals(listOf("cached"), offline.rows.map { it.entryId })
        coVerify(exactly = 0) { authRepository.getAllProjectTimeEntries("org1", "job", "m1") }

        coEvery { authRepository.getAllProjectTimeEntries("org1", "job", null) } returns
            Result.success(listOf(entry("cached", daysAgo = 3), entry("server-only", daysAgo = 400)))
        vm.retryDrillDown()
        val online = vm.openedDrillDown { !it.loadFailed && !it.isRefreshing }
        assertEquals(listOf("cached", "server-only"), online.rows.map { it.entryId })
    }

    @Test
    fun a_range_list_opened_before_the_server_answers_fills_in_when_it_does() = runTest(dispatcher.scheduler) {
        seedMembership()
        val serverAnswer = CompletableDeferred<Unit>()
        coEvery { authRepository.getTimeEntries(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            serverAnswer.await()
            Result.success(TimeEntriesResponse(data = listOf(entry("server-only"), entry("cached"))))
        }
        db.timeEntryDao().upsert(entry("cached").toEntity(updatedAt = 1L, syncState = SyncState.SYNCED))
        val vm = viewModel()
        vm.uiState.first { !it.isLoading }

        vm.openProjectDrillDown("job", "Job 42", "#336699")
        val early = vm.openedDrillDown { true }
        assertEquals(listOf("cached"), early.rows.map { it.entryId })
        assertTrue(early.isRefreshing)

        serverAnswer.complete(Unit)
        val filled = vm.openedDrillDown { !it.isRefreshing }
        assertEquals(setOf("cached", "server-only"), filled.rows.map { it.entryId }.toSet())
    }
}
