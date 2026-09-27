/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.review

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit

/** The Inbox settings sheet's pure helpers: the selected horizon, the working-day summary and steppers. */
class InboxSettingsLogicTest {

    private val zone = ZoneId.of("Europe/Amsterdam")
    private val now = Instant.parse("2025-07-10T10:00:00Z").toEpochMilli() // a Thursday

    @Test
    fun `each stored choice reads back as selected on the day it was made`() {
        HorizonOption.entries.forEach { option ->
            val stored = horizonStartFor(option, now, zone, DayOfWeek.MONDAY)
            assertEquals(option, matchHorizonOption(true, stored, now, zone, DayOfWeek.MONDAY))
        }
    }

    @Test
    fun `this week starts on the account week start in the account zone`() {
        val sundayStart = horizonStartFor(HorizonOption.THIS_WEEK, now, zone, DayOfWeek.SUNDAY)
        assertEquals(Instant.parse("2025-07-05T22:00:00Z").toEpochMilli(), sundayStart)
    }

    @Test
    fun `a bound that no longer matches a choice selects nothing`() {
        val yesterdayStart = horizonStartFor(HorizonOption.TODAY, now - TimeUnit.DAYS.toMillis(1), zone, DayOfWeek.MONDAY)
        assertNull(matchHorizonOption(true, yesterdayStart, now, zone, DayOfWeek.MONDAY))
        assertNull("nothing is selected before the first choice", matchHorizonOption(false, null, now, zone, DayOfWeek.MONDAY))
    }

    @Test
    fun `working days summarise as a range, a list, every day or none`() {
        fun summary(days: Set<DayOfWeek>, first: DayOfWeek = DayOfWeek.MONDAY) =
            workDaysSummary(days, first, Locale.ENGLISH, none = "None", every = "Every day", rangeFormat = "%1\$s–%2\$s")

        val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        assertEquals("Mon–Fri", summary(weekdays))
        assertEquals("Mon, Wed", summary(setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY)))
        assertEquals("Every day", summary(DayOfWeek.entries.toSet()))
        assertEquals("None", summary(emptySet()))
        // A weekend-spanning run is contiguous in a Sunday-first week only.
        assertEquals("Sun–Tue", summary(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY), first = DayOfWeek.SUNDAY))
    }

    @Test
    fun `steppers snap to the step and stay in bounds`() {
        assertEquals(5, steppedValue(1, step = 5, min = 1, max = 1440, up = true))
        assertEquals(1, steppedValue(5, step = 5, min = 1, max = 1440, up = false))
        assertEquals(5, steppedValue(7, step = 5, min = 1, max = 1440, up = false))
        assertEquals(10, steppedValue(7, step = 5, min = 1, max = 1440, up = true))
        assertEquals(24, steppedValue(24, step = 1, min = 1, max = 24, up = true))
        assertEquals(1, steppedValue(1, step = 1, min = 1, max = 24, up = false))
    }
}
