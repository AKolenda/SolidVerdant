/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.EntrySyncStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** History cards: stacked duplicates, the "⋯" menu, continue and group retry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryEntryCardsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entry(id: String, start: String, end: String, description: String) = TimeEntry(
        id = id,
        description = description,
        userId = "u1",
        start = start,
        end = end,
        organizationId = "org1",
    )

    private val first = entry("dup-1", "2026-07-06T09:00:00Z", "2026-07-06T10:00:00Z", "Coding")
    private val single = entry("single", "2026-07-06T10:30:00Z", "2026-07-06T10:45:00Z", "Review")
    private val second = entry("dup-2", "2026-07-06T11:00:00Z", "2026-07-06T11:30:00Z", "Coding")
    private val third = entry("dup-3", "2026-07-06T12:00:00Z", "2026-07-06T12:10:00Z", "Coding")
    private val entries = listOf(first, single, second, third)

    private val edited = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val duplicated = mutableListOf<String>()
    private val continued = mutableListOf<String>()
    private val retried = mutableListOf<String>()

    private fun operation(entry: TimeEntry, status: EntrySyncStatus) = TimeEntryRepository.SyncOperation(
        entryId = entry.id,
        type = OutboxOpType.UPDATE,
        status = status,
        attemptCount = 1,
        error = null,
    )

    private fun setContent(syncOperations: List<TimeEntryRepository.SyncOperation> = emptyList()) {
        val state = TrackingUiState(timeEntries = entries, hasLoadedTimeEntries = true, syncOperations = syncOperations)
        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    trackingHistoryItems(
                        uiState = state,
                        groupedEntries = mapOf(LocalDate.of(2026, 7, 6) to entries),
                        onEdit = { edited += it.id },
                        onDelete = { deleted += it.id },
                        onDateClick = {},
                        onRetrySync = { retried += it.id },
                        onContinue = { continued += it.id },
                        onDuplicate = { duplicated += it.id },
                    )
                }
            }
        }
    }

    @Test
    fun `a collapsed stack is one card without per-entry time ranges`() {
        setContent()

        composeRule.onAllNodesWithTag(TrackingTestTags.ENTRY_ROW).assertCountEquals(2)
        listOf(first, second, third).forEach {
            composeRule.onNodeWithTag(TrackingTestTags.entryTimeRange(it.id), useUnmergedTree = true).assertDoesNotExist()
        }
        composeRule.onNodeWithTag(TrackingTestTags.entryTimeRange(single.id), useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the chevron expands the stack into one row per entry and collapses it again`() {
        setContent()

        composeRule.onNodeWithTag(TrackingTestTags.entryGroupToggle(first.id)).performClick()
        listOf(first, second, third).forEach {
            composeRule.onNodeWithTag(TrackingTestTags.entryTimeRange(it.id), useUnmergedTree = true).assertExists()
        }

        composeRule.onNodeWithTag(TrackingTestTags.entryGroupToggle(first.id)).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.entryTimeRange(second.id), useUnmergedTree = true).assertDoesNotExist()
        assertEquals(emptyList<String>(), edited)
    }

    @Test
    fun `tapping an expanded row edits that entry`() {
        setContent()

        composeRule.onNodeWithTag(TrackingTestTags.entryGroupToggle(first.id)).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.entryTimeRange(second.id), useUnmergedTree = true).performClick()

        assertEquals(listOf(second.id), edited)
    }

    @Test
    fun `the more menu deletes the chosen entry`() {
        setContent()

        composeRule.onNodeWithTag(TrackingTestTags.entryActionsButton(single.id)).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_DELETE_ACTION).performClick()

        assertEquals(listOf(single.id), deleted)
    }

    @Test
    fun `the more menu on an expanded row duplicates that entry`() {
        setContent()

        composeRule.onNodeWithTag(TrackingTestTags.entryGroupToggle(first.id)).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.entryActionsButton(second.id)).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_DUPLICATE_ACTION).performClick()

        assertEquals(listOf(second.id), duplicated)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun `play continues the entry`() {
        setContent()

        composeRule.onNodeWithTag(TrackingTestTags.entryContinueButton(single.id)).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.entryContinueButton(first.id)).performClick()

        assertEquals(listOf(single.id, first.id), continued)
    }

    @Test
    fun `stack retry retries only the entries that can be retried`() {
        setContent(
            listOf(
                operation(first, EntrySyncStatus.FAILED),
                operation(second, EntrySyncStatus.PENDING),
                operation(third, EntrySyncStatus.RETRYING),
            ),
        )

        composeRule.onNodeWithTag(TrackingTestTags.entryRetrySyncButton(first.id)).performClick()

        assertEquals(listOf(first.id, third.id), retried)
    }

    @Test
    fun `swiping a stack left never deletes several entries at once`() {
        setContent()

        composeRule.onAllNodesWithTag(TrackingTestTags.ENTRY_ROW)[0].performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(emptyList<String>(), deleted)
    }
}
