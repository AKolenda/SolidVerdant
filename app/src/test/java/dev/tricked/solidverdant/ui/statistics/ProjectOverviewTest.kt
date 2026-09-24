/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The Dashboard names only its largest projects and folds the rest into "Other", chart included. */
class ProjectOverviewTest {

    private val day = LocalDate.parse("2026-07-06")

    private fun summary(projectCount: Int): StatisticsSummary {
        // Project n has n hours in total; one bucket holds every project, a second only the largest.
        val perProject = (projectCount downTo 1).map { n -> ProjectTotal("p$n", "Project $n", "#000000", n * 3600L) }
        val all =
            TrendBucket("a", day, perProject.sumOf { it.seconds }, perProject.map { ProjectSegment(it.projectId, it.colorHex, it.seconds) })
        val largest = perProject.first()
        val onlyLargest = TrendBucket("b", day.plusDays(1), 60, listOf(ProjectSegment(largest.projectId, largest.colorHex, 60)))
        return StatisticsSummary(perProject.sumOf { it.seconds }, projectCount, 0, 0, 0, perProject, listOf(all, onlyLargest))
    }

    @Test
    fun `projects beyond the limit fold into one Other total in the legend and every bar`() {
        val overview = StatisticsAggregator.projectOverview(summary(10), limit = 6)

        assertEquals(listOf("p10", "p9", "p8", "p7", "p6", "p5"), overview.top.map { it.projectId })
        assertEquals(setOf<String?>("p4", "p3", "p2", "p1"), overview.otherProjectIds)
        assertEquals((1..4).sumOf { it * 3600L }, overview.otherSeconds)

        val bar = overview.trend.first()
        assertEquals(7, bar.segments.size)
        assertTrue(bar.segments.last().isOther)
        assertEquals(overview.otherSeconds, bar.segments.last().seconds)
        assertEquals("Folding keeps each bar's total", summary(10).trend.first().seconds, bar.segments.sumOf { it.seconds })
        assertFalse("A bar without small projects gets no Other segment", overview.trend[1].segments.any { it.isOther })
    }

    @Test
    fun `a single leftover project is shown by name instead of as Other`() {
        val source = summary(7)
        val overview = StatisticsAggregator.projectOverview(source, limit = 6)

        assertFalse(overview.hasOther)
        assertEquals(7, overview.top.size)
        assertSame(source.trend, overview.trend)
    }
}
