/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import dev.tricked.solidverdant.data.model.Tag
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

class TrackingTimeFormattingTest {

    @Test
    fun `history stacks repeated punches that share every visible field`() {
        val first = TimeEntry(
            id = "morning",
            description = "Coding",
            userId = "user",
            start = "2026-08-20T08:00:00Z",
            end = "2026-08-20T09:54:00Z",
            projectId = "p1",
            taskId = "t1",
            tags = listOf(Tag("a"), Tag("b")),
            billable = true,
            organizationId = "org",
        )
        val other = first.copy(id = "review", description = "Review")
        val second = first.copy(
            id = "afternoon",
            start = "2026-08-20T13:36:00Z",
            end = "2026-08-20T13:39:00Z",
            tags = listOf(Tag("b"), Tag("a")),
        )

        val groups = historyEntryGroups(listOf(first, other, second))

        assertEquals(listOf(listOf("morning", "afternoon"), listOf("review")), groups.map { group -> group.map { it.id } })
    }

    @Test
    fun `history keeps punches apart when any visible field differs`() {
        val base = TimeEntry(
            id = "base",
            description = "Coding",
            userId = "user",
            start = "2026-08-20T08:00:00Z",
            end = "2026-08-20T09:00:00Z",
            projectId = "p1",
            taskId = "t1",
            tags = listOf(Tag("a")),
            billable = true,
            organizationId = "org",
        )
        val variants = listOf(
            base.copy(id = "description", description = "Coding!"),
            base.copy(id = "project", projectId = "p2"),
            base.copy(id = "task", taskId = null),
            base.copy(id = "tags", tags = listOf(Tag("a"), Tag("c"))),
            base.copy(id = "billable", billable = false),
            base.copy(id = "break", type = TimeEntryType.BREAK),
        )

        val groups = historyEntryGroups(listOf(base) + variants)

        assertEquals(listOf("base") + variants.map { it.id }, groups.map { it.single().id })
    }

    @Test
    fun `history treats a missing and an empty description as the same`() {
        val blank =
            TimeEntry(
                id = "blank",
                description = "",
                userId = "u",
                start = "2026-08-20T08:00:00Z",
                end = "2026-08-20T09:00:00Z",
                organizationId = "o",
            )
        val missing = blank.copy(id = "missing", description = null)

        assertEquals(listOf(listOf("blank", "missing")), historyEntryGroups(listOf(blank, missing)).map { group -> group.map { it.id } })
    }

    @Test
    fun `multi-day range includes both dates`() {
        val formatted = formatTimeRange(
            start = "2026-07-06T23:00:00Z",
            end = "2026-07-08T01:00:00Z",
            zone = ZoneOffset.UTC,
            locale = Locale.ENGLISH,
        )

        assertTrue(formatted, formatted.contains("6 Jul 2026"))
        assertTrue(formatted, formatted.contains("8 Jul 2026"))
        assertTrue(formatted, formatted.contains("23:00"))
        assertTrue(formatted, formatted.contains("01:00"))
    }

    @Test
    fun `range uses the account timezone for date boundaries`() {
        val formatted = formatTimeRange(
            start = "2026-07-06T23:30:00Z",
            end = "2026-07-07T00:30:00Z",
            zone = ZoneId.of("Asia/Tokyo"),
        )

        assertEquals("08:30 - 09:30", formatted)
    }

    @Test
    fun `history search compares entry starts in the account timezone`() {
        val entry = TimeEntry(
            id = "timezone-boundary",
            userId = "u",
            start = "2026-07-06T23:30:00Z",
            organizationId = "o",
        )

        assertEquals(LocalDate.of(2026, 7, 7), historyEntryStartDate(entry, ZoneId.of("Asia/Tokyo")))
        assertEquals(LocalDate.of(2026, 7, 6), historyEntryStartDate(entry, ZoneOffset.UTC))
    }

    @Test
    fun `history groups a completed multi-day entry into every overlapping account day`() {
        val entry = TimeEntry(
            id = "multi-day",
            userId = "u",
            start = "2026-07-06T23:00:00Z",
            end = "2026-07-08T01:00:00Z",
            duration = null,
            organizationId = "o",
        )

        val grouped = groupCompletedEntriesByLocalDay(
            listOf(entry),
            ZoneOffset.UTC,
            Instant.parse("2026-07-09T00:00:00Z"),
        )

        assertEquals(
            listOf(
                LocalDate.of(2026, 7, 8),
                LocalDate.of(2026, 7, 7),
                LocalDate.of(2026, 7, 6),
            ),
            grouped.keys.toList(),
        )
        assertTrue(grouped.values.all { it.single().id == entry.id })
    }
}
