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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalendarEntryMetadataTest {
    @Test
    fun `metadata includes client project task and exact duration`() {
        val entry = TimeEntry(
            id = "entry",
            userId = "user",
            organizationId = "org",
            start = "2026-08-11T09:00:00Z",
            end = "2026-08-11T10:30:00Z",
            description = "  Review  ",
        )

        val metadata = calendarEntryMetadata(entry, "Project", "Task", "Client")

        assertEquals("Review", metadata.title)
        assertEquals(listOf("Client", "Project", "Task"), metadata.context)
        assertEquals("Client · Project · Task", metadata.subtitle)
        assertEquals(5_400L, metadata.durationSeconds)
    }

    @Test
    fun `metadata ignores blank and duplicate catalogue names`() {
        val entry = TimeEntry(
            id = "entry",
            userId = "user",
            organizationId = "org",
            start = "2026-08-11T09:00:00Z",
            end = "2026-08-11T10:00:00Z",
            duration = 3_600,
            description = " ",
        )

        val metadata = calendarEntryMetadata(entry, "Project", "Project", " ")

        assertNull(metadata.title)
        assertEquals(listOf("Project"), metadata.context)
        assertEquals(3_600L, metadata.durationSeconds)
    }

    @Test
    fun `metadata does not invent duration for malformed or running entries`() {
        val running = TimeEntry(
            id = "running",
            userId = "user",
            organizationId = "org",
            start = "not-a-time",
        )
        val malformed = running.copy(end = "also-not-a-time")

        assertNull(calendarEntryMetadata(running).durationSeconds)
        assertNull(calendarEntryMetadata(malformed).durationSeconds)
    }

    @Test
    fun `continue is offered only for a finished work entry while no timer is running or paused`() {
        val finished = TimeEntry(
            id = "done",
            userId = "user",
            organizationId = "org",
            start = "2026-08-11T09:00:00Z",
            end = "2026-08-11T10:00:00Z",
        )

        assertTrue(canContinueCalendarEntry(finished, timerActive = false))
        // A paused timer has no running entry but is still active: Continue cannot start then.
        assertFalse(canContinueCalendarEntry(finished, timerActive = true))
        assertFalse(canContinueCalendarEntry(finished.copy(end = null), timerActive = false))
        assertFalse(canContinueCalendarEntry(finished.copy(type = TimeEntryType.BREAK), timerActive = false))
    }
}
