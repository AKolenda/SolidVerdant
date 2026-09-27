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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import dev.tricked.solidverdant.data.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The docked running timer: swiping it up opens the running entry's details. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ActiveTimerBarTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val running = TimeEntry(
        id = "running",
        description = "Deburring",
        userId = "u1",
        start = "2026-09-24T13:00:00Z",
        organizationId = "org1",
    )

    private var detailsOpened = 0
    private var stops = 0

    private fun setBar() {
        composeRule.setContent {
            MaterialTheme {
                ActiveTimerBar(
                    uiState = TrackingUiState(isTracking = true, currentTimeEntry = running, editingDescription = "Deburring"),
                    elapsedSeconds = 125,
                    onStop = { stops++ },
                    onPause = {},
                    onResume = {},
                    onEditActiveEntry = { detailsOpened++ },
                )
            }
        }
    }

    @Test
    fun swiping_the_bar_up_opens_the_running_entry_details() {
        setBar()

        composeRule.onNodeWithTag(TrackingTestTags.ACTIVE_TIMER_BAR).performTouchInput { swipeUp() }
        composeRule.waitForIdle()

        assertEquals(1, detailsOpened)
        assertEquals(0, stops)
    }

    @Test
    fun swiping_the_bar_down_does_not_open_details() {
        setBar()

        composeRule.onNodeWithTag(TrackingTestTags.ACTIVE_TIMER_BAR).performTouchInput { swipeDown() }
        composeRule.waitForIdle()

        assertEquals(0, detailsOpened)
    }

    @Test
    fun tapping_the_details_still_opens_them_and_stop_still_stops() {
        setBar()

        composeRule.onNodeWithTag(TrackingTestTags.EDIT_ACTIVE_ENTRY).performClick()
        composeRule.onNodeWithTag(TrackingTestTags.STOP_BUTTON).performClick()

        assertEquals(1, detailsOpened)
        assertEquals(1, stops)
    }

    @Test
    fun running_details_controls_show_the_clock_and_stop_the_timer() {
        composeRule.setContent {
            MaterialTheme {
                RunningTimerControls(
                    elapsedSeconds = 3_725,
                    isPaused = false,
                    enabled = true,
                    onPause = {},
                    onResume = {},
                    onStop = { stops++ },
                )
            }
        }

        composeRule.onNodeWithTag(TrackingTestTags.RUNNING_TIMER_STOP).performClick()

        assertEquals(1, stops)
    }
}
