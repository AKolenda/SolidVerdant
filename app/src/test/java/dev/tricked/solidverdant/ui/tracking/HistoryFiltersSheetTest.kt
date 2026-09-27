/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.ui.components.FilterPickerTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset

/** The grouped search options: a date choice, full-width catalogue rows and status switches. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoryFiltersSheetTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var filter by mutableStateOf(HistoryFilter(query = "deburr"))

    private val projects = listOf(
        Project(id = "mill", name = "Precision milling", color = "#336699"),
        Project(id = "office", name = "Internal work", color = "#663399"),
    )

    private fun setSheet() {
        composeRule.setContent {
            MaterialTheme {
                HistoryFiltersSheet(
                    filter = filter,
                    uiState = TrackingUiState(projects = projects, zone = ZoneOffset.UTC),
                    onChange = { filter = it },
                    onDismiss = {},
                )
            }
        }
    }

    @Test
    fun date_choice_sets_the_range_and_any_time_clears_it() {
        setSheet()

        composeRule.onNodeWithText("Today").performClick()
        val today = LocalDate.now(ZoneOffset.UTC)
        assertEquals(today, filter.startDate)
        assertEquals(today, filter.endDate)

        composeRule.onNodeWithText("Any time").performClick()
        assertNull(filter.startDate)
        assertNull(filter.endDate)
    }

    @Test
    fun project_row_opens_a_searchable_picker_and_shows_the_choice() {
        setSheet()

        composeRule.onNodeWithText("Project").performClick()
        composeRule.onNodeWithTag("filter_picker_search").performTextInput("milling")
        composeRule.onNodeWithTag(FilterPickerTestTags.option("office")).assertDoesNotExist()
        composeRule.onNodeWithTag(FilterPickerTestTags.option("mill")).performClick()

        assertEquals("mill", filter.projectId)
        composeRule.onNodeWithText("Precision milling").assertExists()
        assertEquals("deburr", filter.query)
    }

    @Test
    fun status_switches_and_billable_choice_update_the_filter() {
        setSheet()

        composeRule.onNodeWithText("Billable").performScrollTo().performClick()
        composeRule.onNodeWithText("Needs categorization").performScrollTo().performClick()
        composeRule.onNodeWithText("Without description").performScrollTo().performClick()

        assertEquals(true, filter.billable)
        assertTrue(filter.needsCategorization)
        assertTrue(filter.missingDescriptionOnly)
    }
}
