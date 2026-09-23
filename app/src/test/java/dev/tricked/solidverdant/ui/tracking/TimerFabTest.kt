/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The new-entry button: idle it unfolds into Manual and Timer; running it adds a manual entry. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerFabTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var timerStarts = 0
    private var manualAdds = 0

    private fun setFab(timerActive: Boolean) {
        composeRule.setContent {
            var expanded by remember { mutableStateOf(false) }
            MaterialTheme {
                TimerFab(
                    timerActive = timerActive,
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    onStartTimer = { timerStarts++ },
                    onAddManual = { manualAdds++ },
                )
            }
        }
    }

    @Test
    fun idle_button_unfolds_into_timer_and_manual_then_folds_after_a_choice() {
        setFab(timerActive = false)

        composeRule.onNodeWithTag(TrackingTestTags.START_TIMER_ACTION).assertDoesNotExist()
        composeRule.onNodeWithTag(TrackingTestTags.TIMER_FAB).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.ADD_ENTRY_BUTTON).assertExists()
        composeRule.onNodeWithTag(TrackingTestTags.START_TIMER_ACTION).performClick()

        assertEquals(1, timerStarts)
        assertEquals(0, manualAdds)
        composeRule.onNodeWithTag(TrackingTestTags.START_TIMER_ACTION).assertDoesNotExist()
        composeRule.onNodeWithTag(TrackingTestTags.TIMER_FAB).assertExists()
    }

    @Test
    fun idle_manual_choice_opens_a_manual_entry() {
        setFab(timerActive = false)

        composeRule.onNodeWithTag(TrackingTestTags.TIMER_FAB).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.ADD_ENTRY_BUTTON).performClick()

        assertEquals(0, timerStarts)
        assertEquals(1, manualAdds)
    }

    @Test
    fun running_timer_offers_no_second_start_and_adds_manual_entries_directly() {
        setFab(timerActive = true)

        composeRule.onNodeWithTag(TrackingTestTags.TIMER_FAB).assertDoesNotExist()
        composeRule.onNodeWithTag(TrackingTestTags.ADD_ENTRY_BUTTON).performClick()

        assertEquals(0, timerStarts)
        assertEquals(1, manualAdds)
    }
}
