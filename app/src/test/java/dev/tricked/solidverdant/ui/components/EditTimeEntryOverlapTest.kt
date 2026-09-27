/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.components

import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import dev.tricked.solidverdant.ui.tracking.EntryTrustRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The entry editor parses existing entries once and narrows them to the edited days; its answer
 * must stay the one [EntryTrustRules.overlaps] gives for every entry.
 */
class EditTimeEntryOverlapTest {
    private val now = Instant.parse("2026-08-11T12:00:00Z")

    private fun entry(
        id: String,
        start: String,
        end: String? = null,
        duration: Int? = null,
        organizationId: String = "org",
        type: TimeEntryType = TimeEntryType.WORK,
    ) = TimeEntry(id = id, userId = "u", organizationId = organizationId, start = start, end = end, duration = duration, type = type)

    private val existing = listOf(
        entry("morning", "2026-08-11T08:00:00Z", end = "2026-08-11T09:00:00Z"),
        entry("cached", "2026-08-11T09:30:00Z", duration = 1_800),
        entry("running", "2026-08-11T11:30:00Z"),
        entry("break", "2026-08-11T10:00:00Z", end = "2026-08-11T10:30:00Z", type = TimeEntryType.BREAK),
        entry("other-org", "2026-08-11T10:00:00Z", end = "2026-08-11T11:00:00Z", organizationId = "other"),
        entry("overnight", "2026-08-09T20:00:00Z", end = "2026-08-11T07:30:00Z"),
        entry("last-week", "2026-08-04T10:00:00Z", end = "2026-08-04T11:00:00Z"),
        entry("broken", "not a time", end = "2026-08-11T10:00:00Z"),
    )

    private fun overlaps(start: String, end: String, id: String = ""): Boolean {
        val intervals = entryOverlapIntervals(existing)
        val from = Instant.parse(start)
        val until = Instant.parse(end)
        val nearby = overlapIntervalsNear(intervals, from, until, now)
        return entryOverlapsAny(nearby, id, "org", from, until, now)
    }

    private fun expected(start: String, end: String, id: String = ""): Boolean {
        val candidate = entry(id, start, end = end)
        return existing.any { it.id != id && EntryTrustRules.overlaps(candidate, it, now) }
    }

    @Test
    fun matches_the_trust_rules_for_every_kind_of_entry() {
        listOf(
            "2026-08-11T08:30:00Z" to "2026-08-11T08:45:00Z", // inside a completed entry
            "2026-08-11T09:00:00Z" to "2026-08-11T09:30:00Z", // exactly between two entries
            "2026-08-11T09:45:00Z" to "2026-08-11T09:50:00Z", // inside an entry known by duration
            "2026-08-11T11:45:00Z" to "2026-08-11T11:50:00Z", // inside the running entry
            "2026-08-11T12:30:00Z" to "2026-08-11T13:00:00Z", // after the running entry's now
            "2026-08-11T10:05:00Z" to "2026-08-11T10:25:00Z", // over a break and another org only
            "2026-08-11T07:00:00Z" to "2026-08-11T07:15:00Z", // inside an entry from two days ago
        ).forEach { (start, end) ->
            assertEquals("$start..$end", expected(start, end), overlaps(start, end))
        }
    }

    @Test
    fun an_entry_does_not_overlap_itself() {
        assertTrue(overlaps("2026-08-11T08:00:00Z", "2026-08-11T09:00:00Z"))
        assertFalse(overlaps("2026-08-11T08:00:00Z", "2026-08-11T09:00:00Z", id = "morning"))
    }

    @Test
    fun only_entries_near_the_edited_days_are_scanned() {
        val intervals = entryOverlapIntervals(existing)
        val nearby = overlapIntervalsNear(
            intervals,
            from = Instant.parse("2026-08-11T00:00:00Z"),
            until = Instant.parse("2026-08-12T00:00:00Z"),
            now = now,
        )

        assertFalse(nearby.any { it.id == "last-week" })
        assertTrue("An entry reaching into the window still counts", nearby.any { it.id == "overnight" })
        assertFalse("Breaks never overlap", intervals.any { it.id == "break" })
        assertFalse("Unparseable entries never overlap", intervals.any { it.id == "broken" })
    }

    @Test
    fun an_empty_or_reversed_span_overlaps_nothing() {
        assertFalse(overlaps("2026-08-11T08:30:00Z", "2026-08-11T08:30:00Z"))
        assertFalse(overlaps("2026-08-11T08:45:00Z", "2026-08-11T08:30:00Z"))
    }
}
