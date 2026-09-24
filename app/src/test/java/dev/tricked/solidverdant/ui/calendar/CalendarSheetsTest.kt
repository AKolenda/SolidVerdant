/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.tricked.solidverdant.data.calendar.DeviceCalendar
import dev.tricked.solidverdant.data.local.db.OutboxOpType
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.repository.TimeEntryRepository
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneOffset

/**
 * The calendar's sheets in the app's grouped layout. Automation drives them by the same tags as
 * before the restyle, so these pin that each tag still sits on the node that acts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarSheetsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val completed = TimeEntry(
        id = "done",
        userId = "u",
        organizationId = "org",
        start = "2026-08-06T09:00:00Z",
        end = "2026-08-06T11:00:00Z",
        description = "Design review",
    )
    private val actions = mutableListOf<String>()

    private fun setActionsSheet(entry: TimeEntry, canContinue: Boolean, syncStatus: TimeEntryRepository.EntrySyncStatus? = null) {
        composeRule.setContent {
            MaterialTheme {
                CalendarEntryActionsSheet(
                    entry = entry,
                    project = null,
                    task = null,
                    client = null,
                    syncOperation = syncStatus?.let {
                        TimeEntryRepository.SyncOperation(entry.id, OutboxOpType.UPDATE, it, attemptCount = 1, error = null)
                    },
                    onDismiss = {},
                    onContinue = if (canContinue) ({ actions += "continue" }) else null,
                    onEdit = { actions += "edit" },
                    onDuplicate = { actions += "duplicate" },
                    onSplit = { actions += "split" },
                    onStop = { actions += "stop" },
                    onDelete = { actions += "delete" },
                    onRetrySync = { actions += "retry" },
                    onDiscardFailedSync = { actions += "discard" },
                    onOpenSyncCenter = { actions += "sync-center" },
                )
            }
        }
    }

    private fun click(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()

    @Test
    fun a_finished_entry_offers_continue_edit_duplicate_split_and_delete() {
        setActionsSheet(completed, canContinue = true)

        composeRule.onNodeWithTag(CalendarTestTags.ENTRY_ACTIONS).assertExists()
        composeRule.onNodeWithText("Design review").assertExists()
        listOf(
            CalendarTestTags.CONTINUE_ENTRY,
            CalendarTestTags.EDIT_ENTRY,
            CalendarTestTags.DUPLICATE_ENTRY,
            CalendarTestTags.SPLIT_ENTRY,
            CalendarTestTags.DELETE_ENTRY,
        ).forEach(::click)

        assertEquals(listOf("continue", "edit", "duplicate", "split", "delete"), actions)
        composeRule.onNodeWithTag(CalendarTestTags.STOP_ENTRY).assertDoesNotExist()
    }

    @Test
    fun without_continue_a_running_entry_offers_its_start_time_and_stop() {
        setActionsSheet(completed.copy(end = null), canContinue = false)

        composeRule.onNodeWithTag(CalendarTestTags.CONTINUE_ENTRY).assertDoesNotExist()
        composeRule.onNodeWithTag(CalendarTestTags.DUPLICATE_ENTRY).assertDoesNotExist()
        click(CalendarTestTags.EDIT_START_TIME)
        click(CalendarTestTags.STOP_ENTRY)

        assertEquals(listOf("edit", "stop"), actions)
    }

    @Test
    fun a_failed_change_shows_its_state_with_retry_and_discard() {
        setActionsSheet(completed, canContinue = false, syncStatus = TimeEntryRepository.EntrySyncStatus.FAILED)

        composeRule.onNodeWithTag(CalendarTestTags.SYNC_STATUS).assertExists()
        click(CalendarTestTags.SYNC_RETRY)
        click(CalendarTestTags.SYNC_DISCARD)

        assertEquals(listOf("retry", "discard"), actions)
    }

    @Test
    fun settings_use_segments_for_size_and_snap_and_an_hour_list_for_visible_hours() {
        var settings by mutableStateOf(CalendarGridSettings())
        composeRule.setContent {
            MaterialTheme {
                CalendarSettingsSheet(
                    settings = settings,
                    onSettingsChanged = { transform -> settings = transform(settings).normalized() },
                    onDismiss = {},
                )
            }
        }

        click(CalendarTestTags.settingsOption(CalendarTestTags.SETTINGS_SNAP, "30"))
        click(CalendarTestTags.SETTINGS_DENSITY_SPACIOUS)
        click(CalendarTestTags.SETTINGS_START)
        click(CalendarTestTags.settingsOption(CalendarTestTags.SETTINGS_START, "8"))

        assertEquals(CalendarGridSettings(snapMinutes = 30, startHour = 8, density = CalendarGridDensity.SPACIOUS), settings)
        composeRule.onNodeWithTag(CalendarTestTags.settingsOption(CalendarTestTags.SETTINGS_SNAP, "30"), useUnmergedTree = true)
            .assertIsSelected()
        composeRule.onNodeWithTag(CalendarTestTags.settingsValue(CalendarTestTags.SETTINGS_START), useUnmergedTree = true)
            .assertTextEquals("08:00")
    }

    @Test
    fun split_confirms_the_midpoint_as_a_utc_timestamp() {
        var splitAt: String? = null
        composeRule.setContent {
            MaterialTheme {
                CalendarSplitDialog(entry = completed, zone = ZoneOffset.UTC, onDismiss = {}, onConfirm = { splitAt = it })
            }
        }

        // The dialog's buttons sit outside its scrolling content.
        composeRule.onNodeWithTag(CalendarTestTags.SPLIT_CONFIRM).performClick()

        assertEquals("2026-08-06T10:00:00Z", splitAt)
    }

    @Test
    fun each_device_calendar_is_a_toggle_row() {
        val toggled = mutableListOf<String>()
        composeRule.setContent {
            MaterialTheme {
                CalendarOverlayControls(
                    state = CalendarUiState(
                        overlayEnabled = true,
                        hasCalendarPermission = true,
                        availableCalendars = listOf(
                            DeviceCalendar("1", "Work", "alice@example.com", null),
                            DeviceCalendar("2", "Family", "alice@example.com", null),
                        ),
                        selectedCalendarIds = setOf("1"),
                    ),
                    showRationale = true,
                    onToggleOverlay = {},
                    onRequestPermission = {},
                    onOpenAppSettings = {},
                    onToggleCalendar = { toggled += it },
                    onRetry = {},
                )
            }
        }

        composeRule.onNodeWithText("Family").performClick()

        assertEquals(listOf("2"), toggled)
    }
}
