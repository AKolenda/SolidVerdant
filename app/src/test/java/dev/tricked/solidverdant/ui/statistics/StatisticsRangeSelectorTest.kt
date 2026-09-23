/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatisticsRangeSelectorTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var selected: StatRange = StatRange.ThisWeek

    private fun setControls(initial: StatRange) {
        selected = initial
        composeRule.setContent {
            var range by remember { mutableStateOf(initial) }
            MaterialTheme {
                StatRangeControls(
                    range = range,
                    onSelect = {
                        range = it
                        selected = it
                    },
                )
            }
        }
    }

    @Test
    fun periodThenOffsetSelectsThePreviousPreset() {
        setControls(StatRange.ThisWeek)

        composeRule.onNodeWithTag(StatRangeTestTags.period(StatPeriod.Month)).performClick()
        assertEquals(StatRange.ThisMonth, selected)

        composeRule.onNodeWithTag(StatRangeTestTags.offset(StatOffset.Previous)).performClick()
        assertEquals(StatRange.PreviousMonth, selected)
        composeRule.onNodeWithTag(StatRangeTestTags.period(StatPeriod.Month)).assertIsSelected()
        composeRule.onNodeWithTag(StatRangeTestTags.offset(StatOffset.Previous)).assertIsSelected()
    }

    @Test
    fun switchingPeriodKeepsThePreviousOffset() {
        setControls(StatRange.LastWeek)

        composeRule.onNodeWithTag(StatRangeTestTags.period(StatPeriod.Half)).performClick()
        assertEquals(StatRange.PreviousHalfMonth, selected)

        composeRule.onNodeWithTag(StatRangeTestTags.period(StatPeriod.Day)).performClick()
        assertEquals(StatRange.Yesterday, selected)
    }

    @Test
    fun customHidesTheOffsetControlAndLeavingItStartsAtCurrent() {
        setControls(StatRange.Custom(LocalDate.parse("2026-06-01"), LocalDate.parse("2026-06-20")))

        composeRule.onNodeWithTag(StatRangeTestTags.period(StatPeriod.Custom)).assertIsSelected()
        composeRule.onNodeWithTag(StatRangeTestTags.offset(StatOffset.Current)).assertDoesNotExist()

        composeRule.onNodeWithTag(StatRangeTestTags.period(StatPeriod.Week)).performClick()
        assertEquals(StatRange.ThisWeek, selected)
        composeRule.onNodeWithTag(StatRangeTestTags.offset(StatOffset.Current)).assertIsSelected()
    }

    @Test
    fun customSegmentOpensThePickerWithoutChangingTheRange() {
        setControls(StatRange.ThisWeek)

        composeRule.onNodeWithTag(StatRangeTestTags.period(StatPeriod.Custom)).performClick()

        composeRule.onNode(isDialog()).assertExists()
        assertEquals(StatRange.ThisWeek, selected)
        composeRule.onNodeWithTag(StatRangeTestTags.period(StatPeriod.Week)).assertIsSelected()
    }
}
