/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CalendarEntryMoveTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")

    private fun entry(id: String, start: String, end: String, organizationId: String = "org", type: TimeEntryType = TimeEntryType.WORK) =
        TimeEntry(
            id = id,
            userId = "user",
            start = start,
            end = end,
            organizationId = organizationId,
            type = type,
        )

    @Test
    fun movingAnEntryPreservesItsDurationAcrossDays() {
        val entry = TimeEntry(
            id = "entry-1",
            userId = "user-1",
            organizationId = "org-1",
            start = "2026-08-11T23:30:00+02:00",
            end = "2026-08-12T01:00:00+02:00",
        )
        val targetStart = ZonedDateTime.of(2026, 8, 13, 8, 15, 0, 0, ZoneOffset.ofHours(2))

        val moved = calendarEntryRangeAt(entry, targetStart)

        assertNotNull(moved)
        assertEquals(targetStart, moved?.start)
        assertEquals(targetStart.plusMinutes(90), moved?.end)
    }

    @Test
    fun invalidOrRunningEntriesCannotBeMoved() {
        val running = TimeEntry(
            id = "running",
            userId = "user-1",
            organizationId = "org-1",
            start = "2026-08-11T08:00:00Z",
        )
        val invalid = running.copy(end = "2026-08-11T07:00:00Z")
        val target = ZonedDateTime.parse("2026-08-11T09:00:00Z")

        assertEquals(null, calendarEntryRangeAt(running, target))
        assertEquals(null, calendarEntryRangeAt(invalid, target))
    }

    @Test
    fun durationUsesExactInstantsForOffsetChanges() {
        val entry = TimeEntry(
            id = "entry-dst",
            userId = "user-1",
            organizationId = "org-1",
            start = "2026-10-25T01:30:00+02:00",
            end = "2026-10-25T02:30:00+01:00",
        )
        val targetStart = ZonedDateTime.parse("2026-10-26T09:00:00+01:00")

        val moved = requireNotNull(calendarEntryRangeAt(entry, targetStart))

        assertEquals(Duration.ofHours(2), Duration.between(moved.start, moved.end))
    }

    @Test
    fun `move warns for a contained entry but ignores the moved entry itself`() {
        val moved = entry("moved", "2026-08-11T08:00:00Z", "2026-08-11T09:00:00Z")
        val existing = entry("existing", "2026-08-11T10:30:00Z", "2026-08-11T11:30:00Z")

        assertTrue(
            calendarMoveOverlapsExisting(
                moved,
                "2026-08-11T10:00:00Z",
                "2026-08-11T11:00:00Z",
                listOf(moved, existing),
                now,
            ),
        )
        assertFalse(
            calendarMoveOverlapsExisting(
                moved,
                moved.start,
                moved.end!!,
                listOf(moved),
                now,
            ),
        )
    }

    @Test
    fun `move does not warn for adjacent or cross-organization entries`() {
        val moved = entry("moved", "2026-08-11T08:00:00Z", "2026-08-11T09:00:00Z")
        val adjacent = entry("adjacent", "2026-08-11T09:00:00Z", "2026-08-11T10:00:00Z")
        val otherOrganization = entry(
            id = "other-org",
            start = "2026-08-11T08:30:00Z",
            end = "2026-08-11T09:30:00Z",
            organizationId = "other",
        )

        assertFalse(
            calendarMoveOverlapsExisting(
                moved,
                moved.start,
                moved.end!!,
                listOf(adjacent, otherOrganization),
                now,
            ),
        )
    }

    @Test
    fun `a drop on the same instants is not a move even when the stored text has an offset`() {
        val stored = entry("offset", "2026-08-11T10:00:00+02:00", "2026-08-11T11:00:00+02:00")
        val sameSlot = CalendarEntryRange(
            start = ZonedDateTime.parse("2026-08-11T08:00:00Z"),
            end = ZonedDateTime.parse("2026-08-11T09:00:00Z"),
        )
        val later = CalendarEntryRange(sameSlot.start.plusMinutes(15), sameSlot.end.plusMinutes(15))

        assertFalse(movesEntry(stored, sameSlot))
        assertTrue(movesEntry(stored, later))
    }

    @Test
    fun `dragging an entry that starts before the visible hours moves it by the drag only`() {
        // Grid 08:00-24:00 at 100 px per hour; the 07:00 entry is drawn clipped from 08:00.
        val settings = CalendarGridSettings(snapMinutes = 15, startHour = 8, endHour = 24)
        val day = java.time.LocalDate.of(2026, 8, 11)
        val start = Instant.parse("2026-08-11T07:00:00Z")

        fun drop(dragYPx: Float, dayShift: Int = 0) =
            calendarDragTargetStart(start, day, dayShift, dragYPx, gridHeightPx = 1_600f, zone = ZoneOffset.UTC, settings = settings)

        assertEquals(ZonedDateTime.parse("2026-08-11T07:30:00Z"), drop(50f))
        assertEquals("A hold without travel stays put", ZonedDateTime.parse("2026-08-11T07:00:00Z"), drop(2f))
        assertEquals("It cannot be lifted above where it was", ZonedDateTime.parse("2026-08-11T07:00:00Z"), drop(-200f))
        assertEquals(ZonedDateTime.parse("2026-08-12T07:30:00Z"), drop(50f, dayShift = 1))
    }

    @Test
    fun `a visible entry snaps to the grid and stays inside it`() {
        val settings = CalendarGridSettings(snapMinutes = 15, startHour = 0, endHour = 24)
        val day = java.time.LocalDate.of(2026, 8, 11)

        fun drop(start: String, dragYPx: Float) = calendarDragTargetStart(
            Instant.parse(start),
            day,
            0,
            dragYPx,
            gridHeightPx = 2_400f,
            zone = ZoneOffset.UTC,
            settings = settings,
        )

        assertEquals(ZonedDateTime.parse("2026-08-11T11:00:00Z"), drop("2026-08-11T09:00:00Z", 200f))
        assertEquals(ZonedDateTime.parse("2026-08-11T10:00:00Z"), drop("2026-08-11T09:07:00Z", 100f))
        assertEquals(ZonedDateTime.parse("2026-08-11T00:00:00Z"), drop("2026-08-11T01:00:00Z", -500f))
        assertEquals(ZonedDateTime.parse("2026-08-11T23:45:00Z"), drop("2026-08-11T22:00:00Z", 900f))
    }

    @Test
    fun `split points are written as UTC timestamps strictly inside the entry`() {
        val zone = java.time.ZoneId.of("Europe/Amsterdam")
        val start = ZonedDateTime.parse("2026-08-11T09:00:00+02:00")
        val end = ZonedDateTime.parse("2026-08-11T11:00:00+02:00")
        val day = java.time.LocalDate.of(2026, 8, 11)

        assertEquals("2026-08-11T08:00:00Z", calendarSplitTimestamp(day, 10, 0, zone, start, end))
        assertEquals(null, calendarSplitTimestamp(day, 9, 0, zone, start, end))
        assertEquals(null, calendarSplitTimestamp(day, 12, 0, zone, start, end))
    }

    @Test
    fun `move does not warn for break entries`() {
        val moved = entry("moved", "2026-08-11T08:00:00Z", "2026-08-11T09:00:00Z")
        val breakEntry = entry(
            id = "break",
            start = "2026-08-11T08:30:00Z",
            end = "2026-08-11T09:30:00Z",
            type = TimeEntryType.BREAK,
        )

        assertFalse(
            calendarMoveOverlapsExisting(
                moved,
                moved.start,
                moved.end!!,
                listOf(breakEntry),
                now,
            ),
        )
    }
}
