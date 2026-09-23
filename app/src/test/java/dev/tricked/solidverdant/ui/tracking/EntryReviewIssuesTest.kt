/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.tracking

import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** The Review checks shown on history cards. */
class EntryReviewIssuesTest {

    private val now = Instant.parse("2026-07-10T12:00:00Z")
    private val eightHours = Duration.ofHours(8)

    private fun entry(
        id: String,
        start: String,
        end: String?,
        projectId: String? = "p1",
        description: String? = "Work",
        organizationId: String = "org1",
        type: TimeEntryType = TimeEntryType.WORK,
    ) = TimeEntry(
        id = id,
        description = description,
        userId = "u1",
        start = start,
        end = end,
        projectId = projectId,
        organizationId = organizationId,
        type = type,
    )

    private fun issues(vararg entries: TimeEntry) = EntryTrustRules.reviewIssues(entries.toList(), eightHours, now)

    @Test
    fun a_complete_entry_has_no_issues() {
        assertTrue(issues(entry("a", "2026-07-06T09:00:00Z", "2026-07-06T10:00:00Z")).isEmpty())
    }

    @Test
    fun missing_project_and_description_are_flagged() {
        val result = issues(entry("a", "2026-07-06T09:00:00Z", "2026-07-06T10:00:00Z", projectId = null, description = " "))

        assertEquals(setOf(EntryReviewIssue.NO_PROJECT, EntryReviewIssue.NO_DESCRIPTION), result["a"])
    }

    @Test
    fun overlapping_entries_are_both_flagged_and_touching_ones_are_not() {
        val result = issues(
            entry("long", "2026-07-06T09:00:00Z", "2026-07-06T12:00:00Z"),
            entry("inside", "2026-07-06T10:00:00Z", "2026-07-06T10:30:00Z"),
            entry("later", "2026-07-06T11:30:00Z", "2026-07-06T13:00:00Z"),
            entry("touching", "2026-07-06T13:00:00Z", "2026-07-06T14:00:00Z"),
        )

        assertEquals(setOf(EntryReviewIssue.OVERLAP), result["long"])
        assertEquals(setOf(EntryReviewIssue.OVERLAP), result["inside"])
        assertEquals(setOf(EntryReviewIssue.OVERLAP), result["later"])
        assertEquals(null, result["touching"])
    }

    @Test
    fun entries_in_other_organizations_never_overlap() {
        val result = issues(
            entry("a", "2026-07-06T09:00:00Z", "2026-07-06T11:00:00Z", organizationId = "org1"),
            entry("b", "2026-07-06T10:00:00Z", "2026-07-06T12:00:00Z", organizationId = "org2"),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun entries_at_or_over_the_long_entry_threshold_are_flagged() {
        val result = issues(
            entry("shift", "2026-07-06T06:00:00Z", "2026-07-06T21:00:00Z"),
            entry("short", "2026-07-07T09:00:00Z", "2026-07-07T16:59:00Z"),
        )

        assertEquals(setOf(EntryReviewIssue.LONG_DURATION), result["shift"])
        assertEquals(null, result["short"])
    }

    @Test
    fun running_entries_and_breaks_are_not_reviewed() {
        val result = issues(
            entry("running", "2026-07-10T09:00:00Z", null, projectId = null),
            entry("break", "2026-07-06T12:00:00Z", "2026-07-06T12:30:00Z", projectId = null, type = TimeEntryType.BREAK),
            entry("work", "2026-07-06T12:00:00Z", "2026-07-06T13:00:00Z"),
        )

        assertTrue(result.isEmpty())
    }
}
