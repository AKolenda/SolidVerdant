/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.e2e.robots

import android.icu.text.DateFormat
import android.icu.text.DisplayContext
import android.icu.util.TimeZone
import androidx.compose.material3.DatePickerDefaults
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.test.espresso.Espresso
import dev.tricked.solidverdant.e2e.TestTags
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale

/**
 * Robot for the Track screen. High-level actions/assertions matched on stable testTags plus entry
 * data (descriptions are user data, not localized chrome, so text matching them is stable).
 */
class TrackRobot(composeRule: ComposeTestRule) : Robot(composeRule) {

    /** Wait until the Track screen's history list is present (app finished launching + logged in). */
    fun waitForHistory(): TrackRobot = apply {
        waitUntilTagExists(TestTags.TRACK_HISTORY_LIST)
    }

    /** Assert a history entry with [description] is shown, waiting for background refresh/sync. */
    fun assertEntryVisible(description: String): TrackRobot = apply {
        val matcher = hasText(description, substring = true)
        // The row may not exist yet while the initial pull or an optimistic update is committing.
        // Poll the lazy container itself: a plain text wait cannot discover an uncomposed row.
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            runCatching {
                firstNodeWithTag(TestTags.TRACK_HISTORY_LIST).performScrollToNode(matcher)
            }.isSuccess
        }
        waitUntilTextExists(description)
        composeRule.onAllNodes(matcher, useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
    }

    fun assertHistoryEntryVisible(): TrackRobot = apply {
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            runCatching { scrollHistoryTo(TestTags.TRACK_ENTRY_ROW) }.isSuccess
        }
        firstNodeWithTag(TestTags.TRACK_ENTRY_ROW).assertIsDisplayed()
    }

    /** Start the next entry from the start-timer sheet behind the + button. */
    fun tapStart(): TrackRobot = apply {
        openStartTimerSheet()
        waitUntilEnabledTagExists(TestTags.TRACK_START_BUTTON)
        firstEnabledNodeWithTag(TestTags.TRACK_START_BUTTON)
            .assertIsDisplayed()
            .performClick()
    }

    /** Stop the running timer docked at the bottom of Time Tracker. */
    fun tapStop(): TrackRobot = apply {
        waitUntilEnabledTagExists(TestTags.TRACK_STOP_BUTTON)
        firstEnabledNodeWithTag(TestTags.TRACK_STOP_BUTTON)
            .assertIsDisplayed()
            .performClick()
    }

    /** Unfold the idle + button and choose Timer; the sheet holds the next entry's fields. */
    fun openStartTimerSheet(): TrackRobot = apply {
        if (nodesWithTag(TestTags.TRACK_START_TIMER_SHEET).fetchSemanticsNodes().isNotEmpty()) return@apply
        waitUntilEnabledTagExists(TestTags.TRACK_TIMER_FAB)
        firstEnabledNodeWithTag(TestTags.TRACK_TIMER_FAB).performClick()
        waitUntilEnabledTagExists(TestTags.TRACK_START_TIMER_ACTION)
        firstEnabledNodeWithTag(TestTags.TRACK_START_TIMER_ACTION).performClick()
        waitUntilTagExists(TestTags.TRACK_START_TIMER_SHEET)
    }

    fun tapRefresh(): TrackRobot = apply {
        waitUntilEnabledTagExists(TestTags.TRACK_REFRESH_BUTTON)
        firstEnabledNodeWithTag(TestTags.TRACK_REFRESH_BUTTON).performClick()
    }

    /** Add a manual entry: + adds one directly while a timer runs; idle, it unfolds into Manual. */
    fun openAddEntry(): TrackRobot = apply {
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            nodesWithTag(TestTags.TRACK_ADD_ENTRY_BUTTON).fetchSemanticsNodes().isNotEmpty() ||
                nodesWithTag(TestTags.TRACK_TIMER_FAB).fetchSemanticsNodes().isNotEmpty()
        }
        if (nodesWithTag(TestTags.TRACK_ADD_ENTRY_BUTTON).fetchSemanticsNodes().isEmpty()) {
            firstEnabledNodeWithTag(TestTags.TRACK_TIMER_FAB).performClick()
        }
        waitUntilEnabledTagExists(TestTags.TRACK_ADD_ENTRY_BUTTON)
        firstEnabledNodeWithTag(TestTags.TRACK_ADD_ENTRY_BUTTON).performClick()
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_SAVE_BUTTON)
    }

    fun openSyncDetails(): TrackRobot = apply {
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            runCatching { scrollHistoryTo(TestTags.TRACK_SYNC_DETAILS_BUTTON) }.isSuccess
        }
        waitUntilEnabledTagExists(TestTags.TRACK_SYNC_DETAILS_BUTTON)
        firstEnabledNodeWithTag(TestTags.TRACK_SYNC_DETAILS_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.waitForIdle()
        waitUntilTagExists(TestTags.SYNC_STATUS_SCREEN)
    }

    fun closeSyncDetails(): TrackRobot = apply {
        waitUntilEnabledTagExists(TestTags.SYNC_STATUS_BACK_BUTTON)
        firstEnabledNodeWithTag(TestTags.SYNC_STATUS_BACK_BUTTON).performClick()
        waitUntilTagExists(TestTags.TRACK_HISTORY_LIST)
    }

    fun assertStopButtonVisible(timeoutMs: Long = DEFAULT_TIMEOUT_MS): TrackRobot = apply {
        waitUntilTagExists(TestTags.TRACK_STOP_BUTTON, timeoutMs)
        firstNodeWithTag(TestTags.TRACK_STOP_BUTTON).assertIsDisplayed()
    }

    /** A running timer must not expose a second start action: no start sheet and no Timer choice. */
    fun assertStartButtonGone(): TrackRobot = apply {
        waitUntilTagIsGone(TestTags.TRACK_START_BUTTON)
        waitUntilTagIsGone(TestTags.TRACK_TIMER_FAB)
    }

    fun openSettings(): TrackRobot = apply {
        composeRule.openMenuDestination(TestTags.NAV_SETTINGS)
        waitUntilTagExists(TestTags.SETTINGS_LOGOUT_BUTTON)
    }

    fun assertLiveUpdateSettingVisible(): TrackRobot = apply {
        waitUntilTagExists(TestTags.SETTINGS_LIVE_UPDATE_SWITCH)
        firstNodeWithTag(TestTags.SETTINGS_LIVE_UPDATE_SWITCH).performScrollTo().assertIsDisplayed()
    }

    fun logout(): TrackRobot = apply {
        firstNodeWithTag(TestTags.SETTINGS_LOGOUT_BUTTON).performScrollTo().performClick()
    }

    fun assertLoginVisible(): TrackRobot = apply {
        waitUntilTagExists(TestTags.LOGIN_BUTTON)
        firstNodeWithTag(TestTags.LOGIN_BUTTON).assertIsDisplayed()
    }

    /** Idle Time Tracker: the + button that unfolds into Timer and Manual is back. */
    fun assertStartButtonVisible(): TrackRobot = apply {
        waitUntilTagExists(TestTags.TRACK_TIMER_FAB)
        firstNodeWithTag(TestTags.TRACK_TIMER_FAB).assertIsDisplayed()
    }

    /** "Continue last entry" sits in the start-timer sheet, under the next entry's fields. */
    fun tapContinueLastEntry(): TrackRobot = apply {
        openStartTimerSheet()
        waitUntilEnabledTagExists(TestTags.TRACK_CONTINUE_BUTTON)
        firstEnabledNodeWithTag(TestTags.TRACK_CONTINUE_BUTTON).performScrollTo().assertIsDisplayed().performClick()
    }

    /** Open the edit sheet for the first (newest) visible single-entry row. */
    fun tapFirstEntryEdit(): TrackRobot = apply {
        scrollHistoryTo(TestTags.TRACK_ENTRY_EDIT_BUTTON)
        waitUntilTagExists(TestTags.TRACK_ENTRY_EDIT_BUTTON)
        firstNodeWithTag(TestTags.TRACK_ENTRY_EDIT_BUTTON).assertIsDisplayed().performClick()
        waitUntilTagExists(TestTags.TRACK_SHEET_SAVE_BUTTON)
    }

    /** History rows delete with a swipe to the left, confirmed in the delete prompt. */
    fun tapFirstEntryDelete(): TrackRobot = apply {
        scrollHistoryTo(TestTags.TRACK_ENTRY_ROW)
        waitUntilTagExists(TestTags.TRACK_ENTRY_ROW)
        firstNodeWithTag(TestTags.TRACK_ENTRY_ROW).assertIsDisplayed().performTouchInput { swipeLeft() }
        waitUntilEnabledTagExists(TestTags.TRACK_DELETE_CONFIRM)
        firstEnabledNodeWithTag(TestTags.TRACK_DELETE_CONFIRM).performClick()
        waitUntilTagIsGone(TestTags.TRACK_DELETE_CONFIRM)
    }

    /** True while search is closed: the header shows its search button and no search field. */
    fun isHistorySearchHidden(): Boolean {
        waitUntilTagExists(TestTags.TRACK_SEARCH_BUTTON)
        return nodesWithTag(TestTags.TRACK_FILTER_SEARCH_FIELD).fetchSemanticsNodes().isEmpty()
    }

    /** Open the search bar from the header's search button. */
    fun openHistorySearch(): TrackRobot = apply {
        if (nodesWithTag(TestTags.TRACK_FILTER_SEARCH_FIELD).fetchSemanticsNodes().isNotEmpty()) return@apply
        waitUntilEnabledTagExists(TestTags.TRACK_SEARCH_BUTTON)
        firstEnabledNodeWithTag(TestTags.TRACK_SEARCH_BUTTON).performClick()
        waitUntilTagExists(TestTags.TRACK_FILTER_SEARCH_FIELD)
    }

    /** Open the search options sheet from the search bar, opening search first when needed. */
    fun openHistoryFilters(): TrackRobot = apply {
        openHistorySearch()
        waitUntilEnabledTagExists(TestTags.TRACK_FILTER_OPEN_BUTTON)
        firstEnabledNodeWithTag(TestTags.TRACK_FILTER_OPEN_BUTTON).performClick()
        waitUntilTagExists(TestTags.TRACK_FILTER_SEARCH_FIELD)
        waitUntilTagExists(TestTags.TRACK_FILTER_CLOSE_BUTTON)
    }

    fun enterHistorySearch(text: String): TrackRobot = apply {
        firstNodeWithTag(TestTags.TRACK_FILTER_SEARCH_FIELD).performTextInput(text)
    }

    /** Close the options sheet with Done; the search bar and its query stay. */
    fun closeHistoryFilters(): TrackRobot = apply {
        firstEnabledNodeWithTag(TestTags.TRACK_FILTER_CLOSE_BUTTON).performClick()
        waitUntilTagIsGone(TestTags.TRACK_FILTER_CLOSE_BUTTON)
        waitUntilTagExists(TestTags.TRACK_FILTER_SEARCH_FIELD)
        waitUntilEnabledTagExists(TestTags.TRACK_FILTER_OPEN_BUTTON)
    }

    fun assertHistorySearch(text: String): TrackRobot = apply {
        firstNodeWithTag(TestTags.TRACK_FILTER_SEARCH_FIELD).assertTextContains(text)
    }

    fun duplicateOpenEntry(): TrackRobot = apply {
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_DUPLICATE_BUTTON)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_DUPLICATE_BUTTON).performScrollTo().performClick()
    }

    fun tapSplitAndConfirm(): TrackRobot = apply {
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_SPLIT_BUTTON)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_SPLIT_BUTTON).performScrollTo().performClick()
        waitUntilTagExists(TestTags.TRACK_SHEET_SPLIT_TIME_PICKER)
        waitUntilEnabledTagExists(TestTags.TRACK_SHEET_TIME_PICKER_CONFIRM)
        firstEnabledNodeWithTag(TestTags.TRACK_SHEET_TIME_PICKER_CONFIRM).performClick()
        waitUntilTagIsGone(TestTags.TRACK_SHEET_SPLIT_TIME_PICKER)
    }

    fun assertEditSettingsVisible(): TrackRobot = apply {
        listOf(
            TestTags.TRACK_SHEET_START_DATE,
            TestTags.TRACK_SHEET_END_DATE,
            TestTags.TRACK_SHEET_START_TIME,
            TestTags.TRACK_SHEET_END_TIME,
            TestTags.TRACK_SHEET_DURATION_FIELD,
            TestTags.TRACK_SHEET_DESCRIPTION_FIELD,
            TestTags.TRACK_SHEET_PROJECT_TASK_SELECTOR,
            TestTags.TRACK_SHEET_BILLABLE,
            TestTags.TRACK_SHEET_SAVE_BUTTON,
            TestTags.TRACK_SHEET_CANCEL_BUTTON,
        ).forEach { tag ->
            waitUntilSheetTagExists(tag)
            firstSheetNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    fun assertRunningEditSettingsVisible(): TrackRobot = apply {
        listOf(
            TestTags.TRACK_SHEET_START_DATE,
            TestTags.TRACK_SHEET_START_TIME,
            TestTags.TRACK_SHEET_SAVE_BUTTON,
            TestTags.TRACK_SHEET_CANCEL_BUTTON,
        ).forEach { tag ->
            waitUntilSheetTagExists(tag)
            firstSheetNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    /** Replace the description in the open edit sheet. */
    fun replaceSheetDescription(text: String): TrackRobot = apply {
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_DESCRIPTION_FIELD)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_DESCRIPTION_FIELD).performScrollTo().performTextClearance()
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_DESCRIPTION_FIELD).performTextInput(text)
    }

    fun selectSheetProjectTask(projectName: String, taskName: String): TrackRobot = apply {
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_PROJECT_TASK_SELECTOR).performScrollTo().performClick()
        waitUntilTagExists(TestTags.TRACK_PROJECT_TASK_LIST)
        val projectMatcher = hasText(projectName, substring = false)
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            runCatching {
                firstNodeWithTag(TestTags.TRACK_PROJECT_TASK_LIST).performScrollToNode(projectMatcher)
                composeRule.onAllNodes(projectMatcher, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        composeRule.onAllNodes(projectMatcher, useUnmergedTree = true).onFirst().performClick()
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_TASK_SELECTOR)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_TASK_SELECTOR).performScrollTo().performClick()
        waitUntilTagExists(TestTags.TRACK_TASK_LIST)
        val taskMatcher = hasText(taskName, substring = false)
        // Wait for the target to be composed by scrolling the lazy task picker rather than
        // clicking a node that may not exist on small emulators.
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            runCatching {
                firstNodeWithTag(TestTags.TRACK_TASK_LIST).performScrollToNode(taskMatcher)
                composeRule.onAllNodes(taskMatcher, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        composeRule.onAllNodes(taskMatcher, useUnmergedTree = true).onFirst().performClick()
        waitUntilTagExists(TestTags.TRACK_SHEET_SAVE_BUTTON)
    }

    fun selectSheetTag(tagId: String): TrackRobot = apply {
        val tag = TestTags.trackSheetTagChip(tagId)
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_TAGS_SELECTOR)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_TAGS_SELECTOR).performScrollTo().performClick()
        waitUntilTagExists(TestTags.TRACK_SHEET_TAGS_LIST)
        firstNodeWithTag(TestTags.TRACK_SHEET_TAGS_LIST).performScrollToNode(hasTestTag(tag))
        waitUntilTagExists(tag)
        firstNodeWithTag(tag).assertIsDisplayed().performClick()
        firstNodeWithTag(TestTags.TRACK_SHEET_TAGS_CLOSE).performClick()
    }

    fun toggleSheetBillable(): TrackRobot = apply {
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_BILLABLE)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_BILLABLE).performScrollTo().performClick()
    }

    fun replaceSheetDuration(minutes: String): TrackRobot = apply {
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_DURATION_FIELD)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_DURATION_FIELD).performScrollTo().performTextClearance()
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_DURATION_FIELD).performTextInput(minutes)
    }

    fun assertSheetSaveDisabled(): TrackRobot = apply {
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_SAVE_BUTTON)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_SAVE_BUTTON).assertIsNotEnabled()
    }

    fun assertValidationText(text: String): TrackRobot = apply {
        waitUntilTextExists(text)
        // The validation banner follows the catalogue controls in the scrollable sheet and can
        // be composed but still be below the viewport on the small API-29 emulator.
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_VALIDATION_BANNER)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_VALIDATION_BANNER)
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onAllNodes(hasText(text, substring = true), useUnmergedTree = true)
            .onFirst()
            .assertIsDisplayed()
    }

    fun assertEntryTimeRangeContains(entryId: String, startText: String, endText: String): TrackRobot = apply {
        val rangeMatcher = hasTestTag(TestTags.trackEntryTimeRange(entryId))
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            runCatching {
                firstNodeWithTag(TestTags.TRACK_HISTORY_LIST).performScrollToNode(rangeMatcher)
                composeRule.onAllNodes(rangeMatcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        composeRule.onAllNodes(rangeMatcher, useUnmergedTree = true)
            .onFirst()
            .assertTextContains(startText, substring = true)
            .assertTextContains(endText, substring = true)
            .assertIsDisplayed()
    }

    fun tapSheetCancel(): TrackRobot = apply {
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_CANCEL_BUTTON)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_CANCEL_BUTTON).performScrollTo().performClick()
        waitUntilTagIsGone(TestTags.TRACK_SHEET_SAVE_BUTTON)
    }

    fun changeSheetEndDate(date: LocalDate): TrackRobot = apply {
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_END_DATE).performScrollTo().performClick()
        waitUntilTagExists(TestTags.ENTRY_DATE_PICKER)
        val dayLabel = datePickerDayLabel(date)
        val day = hasText(dayLabel) and hasAnyAncestor(hasTestTag(TestTags.ENTRY_DATE_PICKER))
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodes(day, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(day, useUnmergedTree = true).onFirst().performClick()
        firstEnabledNodeWithTag(TestTags.ENTRY_DATE_PICKER_CONFIRM).performClick()
        waitUntilTagExists(TestTags.TRACK_SHEET_SAVE_BUTTON)
    }

    fun changeSheetStartDate(date: LocalDate): TrackRobot = apply {
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_START_DATE).performScrollTo().performClick()
        waitUntilTagExists(TestTags.ENTRY_DATE_PICKER)
        val dayLabel = datePickerDayLabel(date)
        val day = hasText(dayLabel) and hasAnyAncestor(hasTestTag(TestTags.ENTRY_DATE_PICKER))
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodes(day, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodes(day, useUnmergedTree = true).onFirst().performClick()
        firstEnabledNodeWithTag(TestTags.ENTRY_DATE_PICKER_CONFIRM).performClick()
        waitUntilTagExists(TestTags.TRACK_SHEET_SAVE_BUTTON)
    }

    fun saveSheet(): TrackRobot = apply {
        // The IME from typing can cover the save button; dismiss it before clicking.
        Espresso.closeSoftKeyboard()
        waitUntilSheetTagExists(TestTags.TRACK_SHEET_SAVE_BUTTON)
        firstSheetNodeWithTag(TestTags.TRACK_SHEET_SAVE_BUTTON)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        // Wait until the sheet is gone so later text assertions match history rows, not the
        // sheet's own fields.
        composeRule.waitUntil(DEFAULT_TIMEOUT_MS) {
            composeRule.onAllNodes(hasTestTag(TestTags.TRACK_SHEET_SAVE_BUTTON), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    /** Wait until no history row shows [description] (e.g. after delete). */
    fun waitUntilEntryGone(description: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): TrackRobot = apply {
        composeRule.waitUntil(timeoutMs) {
            composeRule.onAllNodes(hasText(description, substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty()
        }
    }

    /** Tap the snackbar action with [label] (localized text resolved by the caller). */
    fun tapSnackbarAction(label: String): TrackRobot = apply {
        waitUntilTextExists(label)
        composeRule.onAllNodes(hasText(label), useUnmergedTree = true).onFirst().performClick()
    }

    private fun scrollHistoryTo(tag: String) {
        scrollHistoryTo(hasTestTag(tag))
    }

    private fun scrollHistoryTo(matcher: SemanticsMatcher) {
        firstNodeWithTag(TestTags.TRACK_HISTORY_LIST).performScrollToNode(matcher)
    }

    private fun waitUntilSheetTagExists(tag: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
        composeRule.waitUntil(timeoutMs) {
            sheetNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun firstSheetNodeWithTag(tag: String) = sheetNodesWithTag(tag).onFirst()

    private fun sheetNodesWithTag(tag: String) = composeRule.onAllNodes(
        hasTestTag(tag) and hasAnyAncestor(hasTestTag(TestTags.TRACK_SHEET)),
        useUnmergedTree = true,
    )
}

/** Match the localized accessibility label emitted by Material3's DatePicker day semantics. */
private fun datePickerDayLabel(date: LocalDate): String {
    val formatter = DateFormat.getInstanceForSkeleton(
        DatePickerDefaults.YearMonthWeekdayDaySkeleton,
        Locale.getDefault(),
    ).apply {
        setContext(DisplayContext.CAPITALIZATION_FOR_STANDALONE)
        timeZone = TimeZone.GMT_ZONE
    }
    return formatter.format(Date(date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()))
}
