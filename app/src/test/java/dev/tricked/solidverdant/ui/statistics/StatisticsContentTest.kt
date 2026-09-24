/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.ui.statistics.charts.StackedBarChartTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatisticsContentTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val projects =
        listOf(Project(id = "p1", name = "Alpha", color = "#FF0000"), Project(id = "p2", name = "Beta", color = "#00FF00"))
    private val start = LocalDate.parse("2026-07-06")
    private val end = LocalDate.parse("2026-07-08")

    private fun entry(id: String, startIso: String, seconds: Int, projectId: String) =
        TimeEntry(id = id, userId = "u", start = startIso, duration = seconds, projectId = projectId, organizationId = "o")

    private val state: StatisticsUiState
        get() {
            val summary = StatisticsAggregator.compute(
                entries = listOf(
                    entry("1", "2026-07-06T09:00:00Z", 3600, "p1"),
                    entry("2", "2026-07-07T09:00:00Z", 1800, "p2"),
                ),
                projects = projects,
                rangeStart = start,
                rangeEnd = end,
                zone = ZoneId.of("UTC"),
                granularity = TrendGranularity.DAY,
                firstDayOfWeek = DayOfWeek.MONDAY,
            )
            return StatisticsUiState(
                isLoading = false,
                summary = summary,
                catalog = StatCatalog(projects = projects),
                rangeStart = start,
                rangeEnd = end,
            )
        }

    @Test
    fun barsAndProjectRowsReportTheTappedSlice() {
        val buckets = mutableListOf<TrendBucket>()
        val projectsTapped = mutableListOf<ProjectTotal>()
        composeRule.setContent {
            MaterialTheme {
                StatisticsContent(
                    state = state,
                    exporting = false,
                    onRangeChange = {},
                    onFiltersChange = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onExport = {},
                    onProjectClick = { projectsTapped += it },
                    onBucketClick = { buckets += it },
                )
            }
        }

        composeRule.onNodeWithTag(DashboardTestTags.TOTAL).assertTextEquals("1h 30m")
        composeRule.onNodeWithTag(StackedBarChartTestTags.bar(1)).performScrollTo().performClick()
        assertEquals(listOf(LocalDate.parse("2026-07-07")), buckets.map { it.startDate })

        composeRule.onNodeWithTag(DashboardTestTags.LIST).performScrollToNode(hasTestTag(DashboardTestTags.projectRow("p2")))
        composeRule.onNodeWithTag(DashboardTestTags.projectRow("p2")).performClick()
        assertEquals(listOf("p2"), projectsTapped.map { it.projectId })
        composeRule.onNodeWithTag(DashboardTestTags.OTHER_PROJECTS_ROW).assertDoesNotExist()
    }

    @Test
    fun smallerProjectsFoldIntoOneOtherRowThatOpensTheirEntries() {
        val many = (1..10).map { Project(id = "p$it", name = "Project $it", color = "#336699") }
        val summary = StatisticsAggregator.compute(
            // Project n gets n hours, so p10..p5 are the top six and p4..p1 fold into "Other".
            entries = many.mapIndexed { i, project -> entry("e$i", "2026-07-06T00:00:00Z", (i + 1) * 3600, project.id) },
            projects = many,
            rangeStart = start,
            rangeEnd = end,
            zone = ZoneId.of("UTC"),
            granularity = TrendGranularity.DAY,
            firstDayOfWeek = DayOfWeek.MONDAY,
        )
        val othersTapped = mutableListOf<Set<String?>>()
        composeRule.setContent {
            MaterialTheme {
                StatisticsContent(
                    state = state.copy(summary = summary, catalog = StatCatalog(projects = many)),
                    exporting = false,
                    onRangeChange = {},
                    onFiltersChange = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onExport = {},
                    onProjectClick = {},
                    onBucketClick = {},
                    onOtherProjectsClick = { othersTapped += it },
                )
            }
        }

        val list = composeRule.onNodeWithTag(DashboardTestTags.LIST)
        list.performScrollToNode(hasTestTag(DashboardTestTags.projectRow("p5")))
        list.performScrollToNode(hasTestTag(DashboardTestTags.OTHER_PROJECTS_ROW))
        composeRule.onNodeWithTag(DashboardTestTags.projectRow("p4")).assertDoesNotExist()
        composeRule.onNodeWithTag(DashboardTestTags.OTHER_PROJECTS_ROW).assert(hasContentDescription("4 other projects", substring = true))
        composeRule.onNodeWithTag(DashboardTestTags.OTHER_PROJECTS_ROW).performClick()
        assertEquals(listOf(setOf<String?>("p1", "p2", "p3", "p4")), othersTapped)
    }

    @Test
    fun failedRefreshOffersRetryAboveTheCachedCards() {
        var retries = 0
        composeRule.setContent {
            MaterialTheme {
                StatisticsContent(
                    state = state.copy(refreshFailed = true),
                    exporting = false,
                    onRangeChange = {},
                    onFiltersChange = {},
                    onClearFilters = {},
                    onRefresh = { retries++ },
                    onExport = {},
                    onProjectClick = {},
                    onBucketClick = {},
                )
            }
        }

        composeRule.onNodeWithText("Retry").performClick()
        assertEquals(1, retries)
        composeRule.onNodeWithTag(DashboardTestTags.TOTAL).assertTextEquals("1h 30m")
    }

    @Test
    fun emptyRangeShowsNoChartOrBreakdown() {
        composeRule.setContent {
            MaterialTheme {
                StatisticsContent(
                    state = state.copy(summary = StatisticsUiState.EMPTY_SUMMARY, isEmpty = true),
                    exporting = false,
                    onRangeChange = {},
                    onFiltersChange = {},
                    onClearFilters = {},
                    onRefresh = {},
                    onExport = {},
                    onProjectClick = {},
                    onBucketClick = {},
                )
            }
        }

        composeRule.onNodeWithTag(DashboardTestTags.TOTAL).assertTextEquals("0m")
        composeRule.onNodeWithTag(StackedBarChartTestTags.CHART).assertDoesNotExist()
        composeRule.onNodeWithTag(DashboardTestTags.projectRow("p1")).assertDoesNotExist()
    }
}
