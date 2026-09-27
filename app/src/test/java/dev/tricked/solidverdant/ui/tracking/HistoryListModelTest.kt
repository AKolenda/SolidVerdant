/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class HistoryListModelTest {

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 9, 23)
    private val now = Instant.parse("2026-09-23T12:00:00Z")

    private fun entry(id: String, start: String, end: String, description: String = id, type: TimeEntryType = TimeEntryType.WORK) =
        TimeEntry(
            id = id,
            description = description,
            userId = "u",
            start = start,
            end = end,
            organizationId = "o",
            type = type,
        )

    private val tuesdayA = entry("a", "2026-09-22T09:00:00Z", "2026-09-22T10:00:00Z", description = "Coding")
    private val tuesdayB = entry("b", "2026-09-22T11:00:00Z", "2026-09-22T11:30:00Z", description = "Coding")
    private val tuesdayC = entry("c", "2026-09-22T12:00:00Z", "2026-09-22T12:15:00Z")
    private val monday = entry("d", "2026-09-21T08:00:00Z", "2026-09-21T10:00:00Z")
    private val sunday = entry("e", "2026-09-20T08:00:00Z", "2026-09-20T08:45:00Z")
    private val olderSunday = entry("f", "2026-09-06T08:00:00Z", "2026-09-06T09:00:00Z")

    private val days = mapOf(
        LocalDate.of(2026, 9, 22) to listOf(tuesdayA, tuesdayC, tuesdayB),
        LocalDate.of(2026, 9, 21) to listOf(monday),
        LocalDate.of(2026, 9, 20) to listOf(sunday),
        LocalDate.of(2026, 9, 6) to listOf(olderSunday),
    )

    private fun build(firstDayOfWeek: DayOfWeek, input: Map<LocalDate, List<TimeEntry>> = days) =
        buildHistoryListItems(input, firstDayOfWeek, today, zone, now)

    private fun describe(items: List<HistoryListItem>): List<String> = items.map { item ->
        when (item) {
            is HistoryListItem.Week -> "week ${item.start}..${item.end} ${item.label} ${item.totalSeconds}"
            is HistoryListItem.Header -> "day ${item.day.date} ${item.day.totalSeconds}"
            is HistoryListItem.Group -> "group ${item.entries.joinToString("+") { it.id }} ${item.totalSeconds}"
        }
    }

    @Test
    fun `weeks starting on Monday split Sunday from the following Monday`() {
        assertEquals(
            listOf(
                "week 2026-09-21..2026-09-27 THIS_WEEK 13500",
                "day 2026-09-22 6300",
                "group a+b 5400",
                "group c 900",
                "day 2026-09-21 7200",
                "group d 7200",
                "week 2026-09-14..2026-09-20 LAST_WEEK 2700",
                "day 2026-09-20 2700",
                "group e 2700",
                "week 2026-08-31..2026-09-06 DATE_RANGE 3600",
                "day 2026-09-06 3600",
                "group f 3600",
            ),
            describe(build(DayOfWeek.MONDAY)),
        )
    }

    @Test
    fun `weeks starting on Sunday keep Sunday with the days after it`() {
        assertEquals(
            listOf(
                "week 2026-09-20..2026-09-26 THIS_WEEK 16200",
                "day 2026-09-22 6300",
                "group a+b 5400",
                "group c 900",
                "day 2026-09-21 7200",
                "group d 7200",
                "day 2026-09-20 2700",
                "group e 2700",
                "week 2026-09-06..2026-09-12 DATE_RANGE 3600",
                "day 2026-09-06 3600",
                "group f 3600",
            ),
            describe(build(DayOfWeek.SUNDAY)),
        )
    }

    @Test
    fun `days are ordered newest first regardless of map order`() {
        val reversed = days.entries.reversed().associate { it.toPair() }

        assertEquals(describe(build(DayOfWeek.MONDAY)), describe(build(DayOfWeek.MONDAY, reversed)))
    }

    @Test
    fun `a multi-day entry counts only its share of each day and breaks count as zero`() {
        val overnight = entry("night", "2026-09-21T22:00:00Z", "2026-09-22T02:00:00Z")
        val lunch = entry("lunch", "2026-09-22T12:00:00Z", "2026-09-22T13:00:00Z", type = TimeEntryType.BREAK)
        val items = build(
            DayOfWeek.MONDAY,
            mapOf(
                LocalDate.of(2026, 9, 22) to listOf(overnight, lunch),
                LocalDate.of(2026, 9, 21) to listOf(overnight),
            ),
        )

        assertEquals(
            listOf(
                "week 2026-09-21..2026-09-27 THIS_WEEK 14400",
                "day 2026-09-22 7200",
                "group night 7200",
                "group lunch 0",
                "day 2026-09-21 7200",
                "group night 7200",
            ),
            describe(items),
        )
    }

    @Test
    fun `running entries and empty days produce no rows`() {
        val running = TimeEntry(id = "run", userId = "u", start = "2026-09-23T11:00:00Z", organizationId = "o")

        assertEquals(
            emptyList<String>(),
            describe(build(DayOfWeek.MONDAY, mapOf(today to listOf(running), today.minusDays(1) to emptyList()))),
        )
    }

    @Test
    fun `lazy keys are unique and stable`() {
        val keys = build(DayOfWeek.MONDAY).map { it.key }

        assertEquals(keys.distinct(), keys)
        assertEquals("week_2026-09-21", keys.first())
        assertEquals("group_2026-09-22_a", keys[2])
    }

    @Test
    fun `header index points at the day header past the week headers`() {
        val items = build(DayOfWeek.MONDAY)

        assertEquals(4, historyHeaderIndex(LocalDate.of(2026, 9, 21), items))
        assertEquals(7, historyHeaderIndex(LocalDate.of(2026, 9, 19), items))
        assertEquals(10, historyHeaderIndex(LocalDate.of(2026, 8, 1), items))
        assertEquals(1, historyHeaderIndex(LocalDate.of(2026, 12, 1), items))
        assertEquals(-1, historyHeaderIndex(today, emptyList()))
    }

    @Test
    fun `clock durations always show hours minutes and seconds`() {
        assertEquals("00:00:00", formatClockDuration(-5))
        assertEquals("00:56:51", formatClockDuration(56 * 60 + 51L))
        assertEquals("06:59:28", formatClockDuration(6 * 3600 + 59 * 60 + 28L))
        assertEquals("124:00:00", formatClockDuration(124 * 3600L))
    }
}
