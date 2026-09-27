/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import dev.tricked.solidverdant.data.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** History rows: tap edits, swipe left deletes, swipe right continues (only while idle). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistorySwipeActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val entry = TimeEntry(
        id = "e1",
        description = "Design review",
        userId = "u1",
        start = "2026-07-06T09:00:00Z",
        end = "2026-07-06T10:00:00Z",
        duration = 3600,
        organizationId = "org1",
    )

    private val edited = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val continued = mutableListOf<String>()

    private fun setContent(canContinue: Boolean) {
        composeRule.setContent {
            MaterialTheme {
                LazyColumn {
                    trackingHistoryItems(
                        uiState = TrackingUiState(timeEntries = listOf(entry), hasLoadedTimeEntries = true),
                        groupedEntries = mapOf(LocalDate.of(2026, 7, 6) to listOf(entry)),
                        onEdit = { edited += it.id },
                        onDelete = { deleted += it.id },
                        onDateClick = {},
                        onContinue = if (canContinue) ({ continued += it.id }) else null,
                    )
                }
            }
        }
    }

    @Test
    fun tapping_a_row_edits_it() {
        setContent(canContinue = true)
        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_EDIT_BUTTON).performClick()
        assertEquals(listOf("e1"), edited)
    }

    @Test
    fun swiping_left_deletes_the_entry() {
        setContent(canContinue = true)
        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_ROW).performTouchInput { swipeLeft() }
        composeRule.waitForIdle()
        assertEquals(listOf("e1"), deleted)
        assertEquals(emptyList<String>(), continued)
    }

    @Test
    fun swiping_right_continues_the_entry_when_idle() {
        setContent(canContinue = true)
        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_ROW).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertEquals(listOf("e1"), continued)
        assertEquals(emptyList<String>(), deleted)
    }

    @Test
    fun swiping_right_does_nothing_while_a_timer_runs() {
        setContent(canContinue = false)
        composeRule.onNodeWithTag(TrackingTestTags.ENTRY_ROW).performTouchInput { swipeRight() }
        composeRule.waitForIdle()
        assertEquals(emptyList<String>(), continued)
    }

    @Test
    fun swipe_actions_are_exposed_to_accessibility_services() {
        setContent(canContinue = true)
        val actions = composeRule.onNodeWithTag(TrackingTestTags.ENTRY_ROW)
            .fetchSemanticsNode()
            .let { node ->
                generateSequence(node) { it.parent }
                    .firstNotNullOfOrNull { it.config.getOrNull(SemanticsActions.CustomActions) }
            }
            .orEmpty()
            .map { it.label }
        assertEquals(listOf("Delete", "Resume"), actions)
    }
}
