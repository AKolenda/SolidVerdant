/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import dev.tricked.solidverdant.data.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The grid lays days out once a minute at most and lets only a running block follow the second
 * clock; these pin the pieces that make that safe.
 */
class CalendarLiveLayoutTest {
    private val day = LocalDate.of(2026, 8, 6)
    private val zone = ZoneOffset.UTC
    private val grid = calendarGridBounds(day, zone, CalendarGridSettings())

    private fun entry(id: String, start: String, end: String? = null, duration: Int? = null) =
        TimeEntry(id = id, userId = "u", organizationId = "org", start = start, end = end, duration = duration)

    @Test
    fun only_entries_without_an_end_follow_the_clock() {
        assertTrue(hasOpenEnd(entry("running", "2026-08-06T09:00:00Z")))
        assertFalse(hasOpenEnd(entry("done", "2026-08-06T09:00:00Z", end = "2026-08-06T10:00:00Z")))
        assertFalse(hasOpenEnd(entry("cached", "2026-08-06T09:00:00Z", duration = 600)))
    }

    @Test
    fun a_day_without_a_running_entry_keeps_its_layout_across_clock_ticks() {
        val done = listOf(entry("done", "2026-08-06T09:00:00Z", end = "2026-08-06T10:00:00Z"))
        val running = done + entry("running", "2026-08-06T11:00:00Z")
        val minute = Instant.parse("2026-08-06T12:00:00Z")

        assertNull(calendarLayoutClockKey(done, minute))
        assertNull(calendarLayoutClockKey(done, minute.plusSeconds(60)))
        assertEquals(minute, calendarLayoutClockKey(running, minute))
    }

    @Test
    fun a_running_block_grows_to_now_between_minute_layouts() {
        val start = Instant.parse("2026-08-06T09:00:00Z")
        val block = layoutTrackedEntries(
            entries = listOf(entry("running", start.toString())),
            day = day,
            now = Instant.parse("2026-08-06T12:00:00Z"),
            zone = zone,
        ).single()

        val later = liveEntryHeightFraction(block, grid, Instant.parse("2026-08-06T12:00:30Z"))

        assertEquals((3 * 3_600 + 30) / 86_400f, later, 1e-6f)
        // Never shorter than the layout, and clipped at the end of the grid.
        assertEquals(block.heightFraction, liveEntryHeightFraction(block, grid, start), 1e-6f)
        assertEquals(1f - block.startFraction, liveEntryHeightFraction(block, grid, Instant.parse("2026-08-08T00:00:00Z")), 1e-6f)
    }

    @Test
    fun a_running_entry_counts_only_its_seconds_on_the_shown_day() {
        val start = Instant.parse("2026-08-05T23:00:00Z")

        assertEquals(3_600L + 30L, openEntrySecondsOnDay(start, day, zone, Instant.parse("2026-08-06T01:00:30Z")))
        assertEquals(3_600L, openEntrySecondsOnDay(start, day.minusDays(1), zone, Instant.parse("2026-08-06T01:00:30Z")))
        assertEquals(0L, openEntrySecondsOnDay(start, day.plusDays(1), zone, Instant.parse("2026-08-06T01:00:30Z")))
    }
}
