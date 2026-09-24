/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The shared lazy filter pickers and the full-screen date range. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilterPickersTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val options = (1..300).map { FilterOption("p$it", "Project $it") }

    @Test
    fun multi_select_toggles_in_place_and_clears() {
        var selected by mutableStateOf(emptySet<String>())
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                MultiSelectFilterPicker(
                    title = "Projects",
                    options = options,
                    selected = selected,
                    onChange = { selected = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithTag(FilterPickerTestTags.option("p1")).performClick()
        composeRule.onNodeWithTag(FilterPickerTestTags.option("p2")).performClick()
        assertEquals(setOf("p1", "p2"), selected)
        assertEquals(false, dismissed)

        composeRule.onNodeWithTag(FilterPickerTestTags.option("p1")).performClick()
        assertEquals(setOf("p2"), selected)

        composeRule.onNodeWithTag(FilterPickerTestTags.CLEAR_SELECTION).performClick()
        assertTrue(selected.isEmpty())
    }

    @Test
    fun a_large_catalogue_only_composes_the_visible_rows() {
        composeRule.setContent {
            MaterialTheme {
                MultiSelectFilterPicker(title = "Projects", options = options, selected = emptySet(), onChange = {}, onDismiss = {})
            }
        }

        composeRule.onNodeWithTag(FilterPickerTestTags.option("p1")).assertExists()
        composeRule.onNodeWithTag(FilterPickerTestTags.option("p300")).assertDoesNotExist()
    }

    @Test
    fun single_select_picks_and_closes() {
        var picked: String? = "p5"
        var dismissed = false
        composeRule.setContent {
            MaterialTheme {
                SingleSelectFilterPicker(
                    title = "Project",
                    options = options,
                    selectedId = picked,
                    onSelect = { picked = it },
                    onDismiss = { dismissed = true },
                )
            }
        }

        composeRule.onNodeWithTag(FilterPickerTestTags.option("p3")).performClick()

        assertEquals("p3", picked)
        assertTrue(dismissed)
    }

    @Test
    fun date_range_applies_the_chosen_range() {
        var applied: Pair<LocalDate, LocalDate>? = null
        composeRule.setContent {
            MaterialTheme {
                DateRangePickerDialog(
                    initialStart = LocalDate.of(2026, 9, 14),
                    initialEnd = LocalDate.of(2026, 9, 18),
                    onDismiss = {},
                    onConfirm = { start, end -> applied = start to end },
                )
            }
        }

        composeRule.onNodeWithTag(FilterPickerTestTags.DATE_RANGE_APPLY).assertIsEnabled().performClick()

        assertEquals(LocalDate.of(2026, 9, 14) to LocalDate.of(2026, 9, 18), applied)
    }

    @Test
    fun date_range_cannot_apply_without_a_range() {
        composeRule.setContent {
            MaterialTheme {
                DateRangePickerDialog(initialStart = null, initialEnd = null, onDismiss = {}, onConfirm = { _, _ -> })
            }
        }

        composeRule.onNodeWithTag(FilterPickerTestTags.DATE_RANGE_APPLY).assertIsNotEnabled()
    }
}
