/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.TemporalPolicyProvider
import dev.tricked.solidverdant.service.TimeTrackingNotificationService
import dev.tricked.solidverdant.sync.SyncTrigger
import dev.tricked.solidverdant.util.Clock
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The running entry's details and the editing fields Stop and Resume commit: saves after the timer
 * ended, edits kept through Stop and Pause → Resume, deleting the running timer, one-shot editor
 * requests, and the Calendar's Continue while paused.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class TrackingViewModelRunningEntryTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var dispatcher: TestDispatcher
    private lateinit var settings: SettingsDataStore
    private val viewModels = mutableListOf<TrackingViewModel>()
    private val clock = object : Clock {
        override fun nowMs() = 1_000L
    }

    private val active = TimeEntry(
        id = "active",
        userId = "user",
        organizationId = "org",
        start = "2026-08-10T08:00:00Z",
        description = "Precision setup",
        projectId = "project-1",
        taskId = "task-1",
    )

    @Before
    fun setUp() {
        dispatcher = UnconfinedTestDispatcher(TestCoroutineScheduler())
        kotlinx.coroutines.Dispatchers.setMain(dispatcher)
        context.getSharedPreferences("immediate_ui_cache", Context.MODE_PRIVATE).edit().clear().commit()
        settings = SettingsDataStore(context)
    }

    @After
    fun tearDown() {
        val jobs = viewModels.mapNotNull { it.cancelScopeForTest() }
        viewModels.clear()
        dispatcher.scheduler.advanceUntilIdle()
        kotlinx.coroutines.runBlocking { jobs.forEach { it.join() } }
        shadowOf(Looper.getMainLooper()).idle()
        context.getSharedPreferences("immediate_ui_cache", Context.MODE_PRIVATE).edit().clear().commit()
        kotlinx.coroutines.Dispatchers.resetMain()
    }

    @Test
    fun saving_the_running_details_after_the_timer_stopped_keeps_its_recorded_end() = runTest(dispatcher.scheduler) {
        val stoppedEnd = "2026-08-10T09:15:00Z"
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        coEvery { repository.isEntryRunning("active") } returns false
        every { repository.observeTimeEntries("org") } returns flowOf(listOf(active.copy(end = stoppedEnd, duration = 4_500)))
        cacheActiveEntry()
        val viewModel = viewModel(repository)

        // The sheet was opened while the entry ran, so it still holds the running copy.
        viewModel.updatePastTimeEntry(
            timeEntry = active,
            description = "Renamed after stopping",
            projectId = active.projectId,
            taskId = active.taskId,
            tags = emptyList(),
            billable = false,
            start = active.start,
            end = null,
        )
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) {
            repository.updateEntry(
                match { it.id == "active" && it.end == stoppedEnd && it.description == "Renamed after stopping" },
                emptyList(),
            )
        }
        coVerify(exactly = 0) { repository.updateEntry(match { it.end == null }, any()) }
        dispose(viewModel)
    }

    @Test
    fun a_start_after_the_recorded_end_of_a_stopped_timer_is_refused() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        coEvery { repository.isEntryRunning("active") } returns false
        every { repository.observeTimeEntries("org") } returns flowOf(listOf(active.copy(end = "2026-08-10T09:00:00Z")))
        cacheActiveEntry()
        val viewModel = viewModel(repository)

        viewModel.updatePastTimeEntry(active, "x", null, null, emptyList(), false, start = "2026-08-10T09:30:00Z", end = null)
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 0) { repository.updateEntry(any(), any()) }
        assertEquals(
            context.getString(dev.tricked.solidverdant.R.string.running_entry_ended_start_after_end),
            viewModel.uiState.value.error,
        )
        assertFalse(viewModel.uiState.value.isLoading)
        dispose(viewModel)
    }

    @Test
    fun saving_the_running_details_while_it_runs_keeps_it_running() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        coEvery { repository.isEntryRunning("active") } returns true
        cacheActiveEntry()
        val viewModel = viewModel(repository)

        viewModel.updatePastTimeEntry(active, "Still running", active.projectId, active.taskId, emptyList(), false, active.start, null)
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) { repository.updateEntry(match { it.end == null && it.description == "Still running" }, emptyList()) }
        dispose(viewModel)
    }

    @Test
    fun stop_after_saving_the_running_details_commits_the_saved_fields() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        coEvery { repository.isEntryRunning("active") } returns true
        cacheActiveEntry()
        val viewModel = viewModel(repository)

        viewModel.updatePastTimeEntry(active, "Saved in details", "project-2", null, listOf("tag-1"), true, active.start, null)
        dispatcher.scheduler.runCurrent()
        assertEquals("Saved in details", viewModel.uiState.value.editingDescription)

        viewModel.stopTimeEntry()
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) {
            repository.stopEntryWithEdits(
                entry = active,
                userId = "user",
                editedEntry = match {
                    it.description == "Saved in details" && it.projectId == "project-2" && it.taskId == null && it.billable
                },
                tagIds = listOf("tag-1"),
            )
        }
        dispose(viewModel)
    }

    @Test
    fun an_edit_to_the_running_row_reaches_the_editing_fields() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        val activeRow = MutableStateFlow<TimeEntry?>(active)
        val entries = MutableStateFlow(listOf(active))
        every { repository.observeTimeEntries("org") } returns entries
        every { repository.observeConflicts("org") } returns flowOf(emptyList())
        every { repository.observeProjects("org") } returns flowOf(emptyList())
        every { repository.observeTasks("org") } returns flowOf(emptyList())
        every { repository.observeTags("org") } returns flowOf(emptyList())
        every { repository.observeClients("org") } returns flowOf(emptyList())
        every { repository.observeActiveEntry("org") } returns activeRow
        every { repository.observeSyncOperations("org") } returns flowOf(emptyList())
        cacheActiveEntry()
        val viewModel = viewModel(repository)
        viewModel.observeLocalData("org", "member")
        viewModel.uiState.first { it.hasLoadedTimeEntries && it.currentTimeEntry?.id == "active" }

        // Saved elsewhere (Calendar, a pull): the same running row, new fields.
        val edited = active.copy(description = "Edited in Calendar", projectId = "project-9", tags = listOf(Tag("tag-9")))
        entries.value = listOf(edited)
        activeRow.value = edited

        viewModel.uiState.first { it.editingDescription == "Edited in Calendar" }
        assertEquals("project-9", viewModel.uiState.value.editingProjectId)
        assertEquals(listOf("tag-9"), viewModel.uiState.value.editingTags)
        dispose(viewModel)
    }

    @Test
    fun pause_from_the_details_saves_their_fields_and_resumes_with_them() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        coEvery { repository.startEntry(any(), any(), any(), any(), any(), any(), any()) } returns
            active.copy(id = "resumed", start = "2026-08-10T10:00:00Z")
        cacheActiveEntry()
        val viewModel = viewModel(repository)
        val edits = RunningEntryEdits(
            description = "Typed in details",
            projectId = "project-2",
            taskId = null,
            tagIds = listOf("tag-1"),
            billable = true,
            start = active.start,
        )

        viewModel.pauseTimeEntry(edits)
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) {
            repository.stopEntryWithEdits(
                entry = active,
                userId = "user",
                editedEntry = match { it.description == "Typed in details" && it.projectId == "project-2" && it.billable },
                tagIds = listOf("tag-1"),
            )
        }
        coVerify(exactly = 0) { repository.stopEntry(any(), any()) }
        assertTrue(viewModel.uiState.value.isPaused)
        assertEquals("Typed in details", viewModel.uiState.value.editingDescription)

        viewModel.resumeTimeEntry("org", "member", "user")
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) {
            // The resumed timer keeps the details' billable choice too.
            repository.startEntry("org", "member", "user", "project-2", null, "Typed in details", listOf("tag-1"), billable = true)
        }
        dispose(viewModel)
    }

    @Test
    fun stop_from_the_details_saves_a_changed_start_before_stopping() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        cacheActiveEntry()
        val viewModel = viewModel(repository)
        val newStart = "2026-08-10T07:30:00Z"

        viewModel.stopTimeEntry(
            RunningEntryEdits("Typed", active.projectId, active.taskId, emptyList(), false, start = newStart),
        )
        dispatcher.scheduler.runCurrent()

        coVerify(exactly = 1) {
            repository.updateEntry(match { it.start == newStart && it.end == null && it.description == "Typed" }, emptyList())
        }
        coVerify(exactly = 1) {
            repository.stopEntryWithEdits(match { it.start == newStart }, "user", match { it.description == "Typed" }, emptyList())
        }
        assertFalse(viewModel.uiState.value.isTracking)
        dispose(viewModel)
    }

    @Test
    fun deleting_the_running_timer_ends_it_and_the_poll_does_not_bring_it_back() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        val authRepository = mockk<AuthRepository>(relaxed = true)
        // The server keeps the timer until the deferred DELETE syncs.
        coEvery { authRepository.getActiveTimeEntry() } returns Result.success(active)
        coEvery { repository.undoDelete(any(), any()) } returns true
        cacheActiveEntry()
        val viewModel = viewModel(repository, authRepository)

        viewModel.deleteTimeEntry(active.id)
        dispatcher.scheduler.runCurrent()
        // The delete finishes after the notification and widget are updated.
        viewModel.uiState.first { !it.isLoading }

        assertFalse(viewModel.uiState.value.isTracking)
        assertNull(viewModel.uiState.value.currentTimeEntry)
        assertEquals(0L, viewModel.elapsedSeconds.value)

        viewModel.onAppForegrounded("org", "member", refreshAll = false)
        dispatcher.scheduler.runCurrent()
        assertFalse(viewModel.uiState.value.isTracking)
        assertNull(viewModel.uiState.value.currentTimeEntry)

        // Undo within the window: the poll may show it again.
        viewModel.undoDelete(active)
        dispatcher.scheduler.runCurrent()
        viewModel.onAppForegrounded("org", "member", refreshAll = false)
        dispatcher.scheduler.runCurrent()
        assertEquals("active", viewModel.uiState.value.currentTimeEntry?.id)
        assertTrue(viewModel.uiState.value.isTracking)
        dispose(viewModel)
    }

    @Test
    fun an_unchanged_active_poll_does_not_restart_the_notification() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        val authRepository = mockk<AuthRepository>(relaxed = true)
        coEvery { authRepository.getActiveTimeEntry() } returns Result.success(active)
        cacheActiveEntry()
        val viewModel = viewModel(repository, authRepository)
        val application = shadowOf(context as Application)
        while (application.nextStartedService != null) Unit

        viewModel.onAppForegrounded("org", "member", refreshAll = false)
        dispatcher.scheduler.runCurrent()
        // Three more 10-second polls with the same timer.
        repeat(3) {
            dispatcher.scheduler.advanceTimeBy(ACTIVE_POLL_MS)
            dispatcher.scheduler.runCurrent()
        }

        var trackingStarts = 0
        while (true) {
            val intent = application.nextStartedService ?: break
            if (intent.action == TimeTrackingNotificationService.ACTION_START_TRACKING) trackingStarts++
        }
        assertEquals(1, trackingStarts)
        dispose(viewModel)
    }

    @Test
    fun calendar_duplicate_leaves_no_editor_request_and_a_tracker_request_expires() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        coEvery { repository.duplicateEntry("done", "member") } returns
            Result.success(active.copy(id = "copy", end = "2026-08-10T09:00:00Z"))
        val viewModel = viewModel(repository)
        viewModel.observeLocalData("org", "member")

        viewModel.duplicateTimeEntry("done", openEditor = false)
        dispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.entryToEditId)

        viewModel.duplicateTimeEntry("done")
        dispatcher.scheduler.runCurrent()
        assertEquals("copy", viewModel.uiState.value.entryToEditId)

        dispatcher.scheduler.advanceTimeBy(ENTRY_TO_EDIT_TTL_MS + 1)
        dispatcher.scheduler.runCurrent()
        assertNull(viewModel.uiState.value.entryToEditId)
        dispose(viewModel)
    }

    @Test
    fun continue_is_refused_while_a_timer_is_paused_and_keeps_its_fields() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        cacheActiveEntry()
        val viewModel = viewModel(repository)
        viewModel.pauseTimeEntry()
        dispatcher.scheduler.runCurrent()
        assertTrue(viewModel.uiState.value.isPaused)

        val started = viewModel.continueEntry(
            entry = active.copy(id = "older", description = "Other work", projectId = "project-7"),
            organizationId = "org",
            memberId = "member",
            userId = "user",
        )
        dispatcher.scheduler.runCurrent()

        assertFalse(started)
        assertEquals("Precision setup", viewModel.uiState.value.editingDescription)
        assertEquals("project-1", viewModel.uiState.value.editingProjectId)
        coVerify(exactly = 0) { repository.startEntry(any(), any(), any(), any(), any(), any(), any()) }
        dispose(viewModel)
    }

    @Test
    fun continue_starts_a_timer_with_the_entry_fields_when_idle() = runTest(dispatcher.scheduler) {
        val repository = mockk<TimeEntryRepository>(relaxed = true)
        coEvery { repository.startEntry(any(), any(), any(), any(), any(), any(), any()) } returns active
        val viewModel = viewModel(repository)

        val started = viewModel.continueEntry(active.copy(end = "2026-08-10T09:00:00Z"), "org", "member", "user")
        dispatcher.scheduler.runCurrent()

        assertTrue(started)
        coVerify(exactly = 1) { repository.startEntry("org", "member", "user", "project-1", "task-1", "Precision setup", emptyList()) }
        dispose(viewModel)
    }

    private fun viewModel(repository: TimeEntryRepository, authRepository: AuthRepository = mockk(relaxed = true)): TrackingViewModel =
        TrackingViewModel(
            authRepository = authRepository,
            settingsDataStore = settings,
            timeEntryRepository = repository,
            syncTrigger = SyncTrigger {},
            temporalPolicyProvider = TemporalPolicyProvider(settings),
            context = context,
            clock = clock,
        ).also { viewModels += it }

    private suspend fun dispose(viewModel: TrackingViewModel) {
        val scopeJob = viewModel.cancelScopeForTest()
        dispatcher.scheduler.advanceUntilIdle()
        scopeJob?.join()
        viewModels.remove(viewModel)
    }

    private fun cacheActiveEntry() {
        settings.cacheTrackingState(
            SettingsDataStore.CachedTrackingState(
                organizationId = "org",
                timeEntries = listOf(active),
                projects = emptyList(),
                clients = emptyList(),
                tasks = emptyList(),
                tags = emptyList(),
                activeEntry = active,
            ),
        )
    }

    private companion object {
        const val ACTIVE_POLL_MS = 10_000L
        const val ENTRY_TO_EDIT_TTL_MS = 10_000L
    }
}
