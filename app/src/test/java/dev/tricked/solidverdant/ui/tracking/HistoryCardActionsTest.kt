/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import dev.tricked.solidverdant.data.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** History card actions: stacks swipe away as a whole, ⋯ offers Continue, and review checks show on the card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryCardActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun entry(id: String, start: String, end: String) = TimeEntry(
        id = id,
        description = "Standup",
        userId = "u1",
        start = start,
        end = end,
        organizationId = "org1",
    )

    private val first = entry("e1", "2026-07-06T09:00:00Z", "2026-07-06T09:30:00Z")
    private val second = entry("e2", "2026-07-06T13:00:00Z", "2026-07-06T13:30:00Z")

    private val deleted = mutableListOf<String>()
    private val deletedStacks = mutableListOf<List<String>>()
    private val continued = mutableListOf<String>()

    private fun setContent(entries: List<TimeEntry>, reviewIssues: Map<String, Set<EntryReviewIssue>> = emptyMap()) {
        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    trackingHistoryItems(
                        uiState = TrackingUiState(timeEntries = entries, hasLoadedTimeEntries = true),
                        groupedEntries = mapOf(LocalDate.of(2026, 7, 6) to entries),
                        onEdit = {},
                        onDelete = { deleted += it.id },
                        onDateClick = {},
                        onContinue = { continued += it.id },
                        onDeleteStack = { stack -> deletedStacks += stack.map { it.id } },
                        reviewIssues = reviewIssues,
                    )
                }
            }
        }
    }

    @Test
    fun swiping_a_stack_left_asks_to_delete_every_entry_in_it() {
        setContent(listOf(second, first))

        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_ROW).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()

        assertEquals(listOf(listOf("e2", "e1")), deletedStacks.map { it.sortedDescending() })
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun the_entry_menu_offers_continue_first() {
        setContent(listOf(first))

        composeRule.onNodeWithTag(TrackingTestTags.entryActionsButton("e1")).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_CONTINUE_ACTION).performClick()

        assertEquals(listOf("e1"), continued)
    }

    @Test
    fun review_checks_show_on_the_card() {
        setContent(listOf(first), reviewIssues = mapOf("e1" to setOf(EntryReviewIssue.NO_PROJECT, EntryReviewIssue.OVERLAP)))

        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_REVIEW_ISSUES, useUnmergedTree = true)
            .assertTextEquals("No project · Overlaps another entry")
    }

    @Test
    fun a_card_without_review_issues_has_no_warning_line() {
        setContent(listOf(first))

        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_REVIEW_ISSUES, useUnmergedTree = true).assertDoesNotExist()
    }
}
