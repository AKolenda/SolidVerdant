/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.tricked.solidverdant.data.local.AuthDataStore
import dev.tricked.solidverdant.data.local.SettingsDataStore
import dev.tricked.solidverdant.data.local.db.AppDatabase
import dev.tricked.solidverdant.data.local.db.SyncState
import dev.tricked.solidverdant.data.local.db.toEntity
import dev.tricked.solidverdant.data.model.TimeEntriesMeta
import dev.tricked.solidverdant.data.model.TimeEntriesResponse
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.remote.FakeRemoteDataSource
import dev.tricked.solidverdant.data.repository.AuthRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.domain.time.TemporalPolicyProvider
import dev.tricked.solidverdant.sync.SyncTrigger
import dev.tricked.solidverdant.ui.components.EditTimeEntryTestTags
import dev.tricked.solidverdant.ui.templates.ManageTemplatesViewModel
import dev.tricked.solidverdant.util.Clock
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Go to date from the Time Tracker history: the day header opens the picker, and the list lands on
 * the picked day, whether that day is already loaded or needs a window of older history.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class TrackingScreenJumpTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.now(zone)
    private val target = today.minusDays(400)

    private fun entry(id: String, date: LocalDate, hour: Int) = TimeEntry(
        id = id,
        description = "Entry $id",
        userId = "u1",
        start = date.atTime(hour, 0).atZone(zone).toInstant().toString(),
        end = date.atTime(hour, 30).atZone(zone).toInstant().toString(),
        organizationId = "org1",
    )

    /** Three entries a day for [count] days back from [newest], newest first as the API pages. */
    private fun days(newest: LocalDate, count: Int, prefix: String) = (0 until count).flatMap { day ->
        val date = newest.minusDays(day.toLong())
        (0 until 3).map { slot -> entry("$prefix-$day-$slot", date, 15 - slot) }
    }

    private fun label(date: LocalDate) = formatHistoryDayLabel(date, context, zone, Locale.getDefault())

    /** Waits for [done]; querying the tree lets the list's off-thread build land in between. */
    private fun waitUntilSettled(done: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTag(TrackingTestTags.HISTORY_LIST).fetchSemanticsNodes()
            done()
        }
        repeat(10) {
            composeRule.mainClock.advanceTimeBy(100)
            composeRule.waitForIdle()
        }
    }

    private fun waitForHeader(date: LocalDate, done: () -> Boolean = { true }) = waitUntilSettled {
        composeRule.onAllNodesWithText(label(date)).fetchSemanticsNodes().isNotEmpty() && done()
    }

    @Test
    fun theDayHeaderOpensThePickerAndAWindowOfOlderHistoryScrollsToTheDay() {
        val window = days(target.plusDays(60), 121, "old")
        var state by mutableStateOf(TrackingUiState(timeEntries = days(today, 100, "recent"), hasLoadedTimeEntries = true))
        val jumps = mutableListOf<LocalDate>()
        composeRule.setContent {
            Screen(state, onJumpToDate = { jumps += it }, onJumpConsumed = { state = state.copy(historyJumpDate = null) })
        }
        waitForHeader(today)
        composeRule.onNodeWithText(label(today)).performClick()
        composeRule.onNodeWithTag(EditTimeEntryTestTags.DATE_PICKER_CONFIRM).performClick()
        composeRule.waitForIdle()
        assertEquals(listOf(today), jumps)

        // What TrackingViewModel.jumpToHistoryDate publishes: the search, then the found window.
        state = state.copy(isLoadingMoreTimeEntries = true, historyJumpTarget = target, historyJumpProgress = 0f)
        composeRule.waitForIdle()
        state = state.copy(
            timeEntries = window,
            canLoadNewerHistory = true,
            isLoadingMoreTimeEntries = false,
            historyJumpTarget = null,
            historyJumpProgress = null,
            historyJumpDate = target,
        )
        waitForHeader(target) { state.historyJumpDate == null }

        composeRule.onNodeWithText(label(target)).assertIsDisplayed()
    }

    @Test
    fun theViewModelsJumpLandsOnALoadedDayAndOnAnOlderOne() {
        // Three entries a day for 1000 days; the newest 250 are on the phone.
        val history = days(today, 1000, "h")
        // The page above the older window waits until the test lets it through.
        val newerPage = CompletableDeferred<Unit>()
        val authRepository = mockk<AuthRepository>(relaxed = true)
        coEvery { authRepository.getActiveTimeEntry() } returns Result.success(null)
        coEvery { authRepository.getTimeEntries(any(), any(), any(), any(), any(), any(), any()) } coAnswers {
            val limit = arg<Int>(2)
            val offset = arg<Int>(3)
            val end = arg<String?>(6)?.let(Instant::parse)
            if (end == null && limit == MAX_PAGE && offset in 1..NEWER_PAGE_MAX_OFFSET) newerPage.await()
            val matching = history.filter { end == null || Instant.parse(it.start) <= end }
            Result.success(TimeEntriesResponse(data = matching.drop(offset).take(limit), meta = TimeEntriesMeta(total = matching.size)))
        }
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        runBlocking {
            history.take(250).forEach { db.timeEntryDao().upsert(it.toEntity(updatedAt = 1L, syncState = SyncState.SYNCED)) }
        }
        val vm = viewModel(authRepository, db)
        vm.observeLocalData("org1", "m1")
        composeRule.setContent {
            val state by vm.uiState.collectAsState()
            Screen(
                state,
                onJumpToDate = vm::jumpToHistoryDate,
                onJumpConsumed = vm::consumeHistoryJump,
                onLoadMore = vm::loadMoreTimeEntries,
                onLoadNewer = vm::loadNewerTimeEntries,
            )
        }
        waitForHeader(today) { vm.uiState.value.timeEntries.size >= MAX_PAGE && !vm.uiState.value.isLoadingMoreTimeEntries }
        // A little scrolling first, as anyone does before reaching for a date.
        composeRule.onNodeWithTag(TrackingTestTags.HISTORY_LIST).performTouchInput { swipeUp() }
        composeRule.onNodeWithTag(TrackingTestTags.HISTORY_LIST).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        // A day already loaded: the jump fetches the newest page again, the entries on screen.
        val loadedDay = today.minusDays(40)
        vm.jumpToHistoryDate(loadedDay)
        waitForHeader(loadedDay) { vm.uiState.value.historyJumpDate == null && !vm.uiState.value.isLoadingMoreTimeEntries }
        composeRule.onNodeWithText(label(loadedDay)).assertIsDisplayed()

        // A day further back than anything loaded.
        vm.jumpToHistoryDate(target)
        waitForHeader(target) { vm.uiState.value.historyJumpDate == null && !vm.uiState.value.isLoadingMoreTimeEntries }
        composeRule.onNodeWithText(label(target)).assertIsDisplayed()

        // Scrolling up to the top of that window loads the newer page above it; the same rows stay on screen.
        composeRule.onNodeWithTag(TrackingTestTags.HISTORY_LIST).performScrollToIndex(60)
        composeRule.waitForIdle()
        val before = visibleEntries()
        assertTrue(before.isNotEmpty())
        assertTrue(vm.uiState.value.isLoadingMoreTimeEntries)
        newerPage.complete(Unit)
        waitUntilSettled { vm.uiState.value.timeEntries.size >= 2 * MAX_PAGE && !vm.uiState.value.isLoadingMoreTimeEntries }
        assertEquals(before, visibleEntries())

        vm.cancelScopeForTest()
        db.close()
    }

    private fun viewModel(authRepository: AuthRepository, db: AppDatabase): TrackingViewModel {
        val settings = SettingsDataStore(context)
        val clock = object : Clock {
            override fun nowMs() = System.currentTimeMillis()
        }
        return TrackingViewModel(
            authRepository = authRepository,
            settingsDataStore = settings,
            timeEntryRepository = TimeEntryRepository(
                db.timeEntryDao(),
                db.catalogDao(),
                db.outboxDao(),
                db.syncMetaDao(),
                FakeRemoteDataSource(),
                clock,
                Json { encodeDefaults = true },
                db,
            ),
            syncTrigger = SyncTrigger {},
            temporalPolicyProvider = TemporalPolicyProvider(settings),
            context = context,
            clock = clock,
        )
    }

    /** Descriptions of the entries drawn inside the history list's bounds, top to bottom. */
    private fun visibleEntries(): List<String> {
        val list = composeRule.onNodeWithTag(TrackingTestTags.HISTORY_LIST).fetchSemanticsNode().boundsInRoot
        return composeRule.onAllNodes(hasText("Entry ", substring = true), useUnmergedTree = true).fetchSemanticsNodes()
            .filter { node ->
                node.boundsInRoot.height > 0f && node.boundsInRoot.top >= list.top && node.boundsInRoot.bottom <= list.bottom
            }
            .sortedBy { it.boundsInRoot.top }
            .flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
    }

    @Composable
    private fun Screen(
        state: TrackingUiState,
        onJumpToDate: (LocalDate) -> Unit,
        onJumpConsumed: () -> Unit,
        onLoadMore: () -> Unit = {},
        onLoadNewer: () -> Unit = {},
    ) {
        val templates = remember {
            ManageTemplatesViewModel(mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true), AuthDataStore(context))
        }
        MaterialTheme {
            TrackingScreen(
                user = null,
                currentMembership = null,
                uiState = state,
                autoClearEntryFieldsAfterStop = false,
                longTimerHours = 10,
                editActiveEntryRequested = false,
                onEditActiveEntryConsumed = {},
                onRefresh = {},
                onStartTracking = {},
                onStopTracking = {},
                onPauseTracking = {},
                onResumeTracking = {},
                onDescriptionChange = {},
                onProjectChange = {},
                onTaskChange = {},
                onResetEntryFields = {},
                onTagsChange = {},
                onBillableChange = {},
                onUpdatePastEntry = { _, _, _, _, _, _, _, _ -> },
                onCreateEntry = { _, _, _, _, _, _, _ -> },
                onDeleteEntry = {},
                onDuplicateEntry = {},
                onSplitEntry = { _, _ -> },
                onEntryToEditConsumed = {},
                onUndoDelete = {},
                onRetrySync = {},
                onRetrySyncEntry = {},
                onOpenSyncCenter = {},
                onLoadMoreEntries = onLoadMore,
                onLoadNewerEntries = onLoadNewer,
                onJumpToDate = onJumpToDate,
                onHistoryJumpConsumed = onJumpConsumed,
                templateViewModel = templates,
            )
        }
    }

    private companion object {
        const val MAX_PAGE = 500

        // The search lands on an entry of the day 400 days (1200 entries) back and the window
        // starts 250 entries above it, so the page above the window starts before this offset.
        const val NEWER_PAGE_MAX_OFFSET = 460
    }
}
