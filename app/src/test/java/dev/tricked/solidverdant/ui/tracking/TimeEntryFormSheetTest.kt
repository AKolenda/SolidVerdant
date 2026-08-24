/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.tricked.solidverdant.data.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimeEntryFormSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun completed_entry_start_time_opens_picker_without_dismissing_parent_sheet() {
        var dismissCount = 0
        composeRule.setContent {
            MaterialTheme {
                TimeEntryFormSheet(
                    entry = TimeEntry(
                        id = "completed-overnight",
                        userId = "user",
                        organizationId = "org",
                        start = "2026-08-21T23:30:00-06:00",
                        end = "2026-08-22T01:00:00-06:00",
                        description = "Milling",
                    ),
                    zone = ZoneId.of("America/Edmonton"),
                    suggestedStart = null,
                    projects = emptyList(),
                    tasks = emptyList(),
                    tags = emptyList(),
                    onDismiss = { dismissCount++ },
                    onSave = { _, _, _, _, _, _, _ -> },
                )
            }
        }

        composeRule.onNodeWithTag(TrackingTestTags.SHEET_START_TIME).performScrollTo().performClick()

        composeRule.onNodeWithTag(TrackingTestTags.SHEET_TIME_PICKER_CONFIRM).assertExists()
        composeRule.onNodeWithTag(TrackingTestTags.SHEET).assertExists()
        assertEquals(0, dismissCount)
    }

    @Test
    fun every_child_picker_blocks_parent_sheet_dismissal() {
        assertFalse(canDismissTimeEntryFormSheet(hasTimePicker = true, hasDatePicker = false, hasSplitPicker = false))
        assertFalse(canDismissTimeEntryFormSheet(hasTimePicker = false, hasDatePicker = true, hasSplitPicker = false))
        assertFalse(canDismissTimeEntryFormSheet(hasTimePicker = false, hasDatePicker = false, hasSplitPicker = true))
        assertTrue(canDismissTimeEntryFormSheet(hasTimePicker = false, hasDatePicker = false, hasSplitPicker = false))
    }
}
