/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class StatRangeTest {

    // Wed 2026-07-08; ISO (Monday-start) week starts Monday 2026-07-06
    private val today = LocalDate.parse("2026-07-08")

    @Test
    fun `this week is monday to today`() {
        val r = StatRange.ThisWeek.resolve(today, DayOfWeek.MONDAY)
        assertEquals(LocalDate.parse("2026-07-06"), r.start)
        assertEquals(today, r.endInclusive)
    }

    @Test
    fun `this month is first to today`() {
        val r = StatRange.ThisMonth.resolve(today, DayOfWeek.MONDAY)
        assertEquals(LocalDate.parse("2026-07-01"), r.start)
        assertEquals(today, r.endInclusive)
    }

    @Test
    fun `custom returns its own bounds`() {
        val r = StatRange.Custom(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-01-31"))
            .resolve(today, DayOfWeek.MONDAY)
        assertEquals(LocalDate.parse("2026-01-01"), r.start)
        assertEquals(LocalDate.parse("2026-01-31"), r.endInclusive)
    }

    @Test fun `shortcut ranges resolve inclusively`() {
        assertEquals(LocalDate.parse("2026-06-29"), StatRange.LastWeek.resolve(today, DayOfWeek.MONDAY).start)
        assertEquals(LocalDate.parse("2026-07-05"), StatRange.LastWeek.resolve(today, DayOfWeek.MONDAY).endInclusive)
        assertEquals(LocalDate.parse("2026-06-01"), StatRange.PreviousMonth.resolve(today, DayOfWeek.MONDAY).start)
        assertEquals(LocalDate.parse("2026-06-30"), StatRange.PreviousMonth.resolve(today, DayOfWeek.MONDAY).endInclusive)
    }

    @Test
    fun `this week resolves with a sunday week start`() {
        // Wed 2026-07-08: the Sunday-start week began Sunday 2026-07-05.
        val r = StatRange.ThisWeek.resolve(today, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.parse("2026-07-05"), r.start)
        assertEquals(today, r.endInclusive)
    }

    @Test
    fun `last week resolves with a sunday week start`() {
        // This Sunday-start week began 2026-07-05, so last week is 2026-06-28..2026-07-04.
        val r = StatRange.LastWeek.resolve(today, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.parse("2026-06-28"), r.start)
        assertEquals(LocalDate.parse("2026-07-04"), r.endInclusive)
    }

    @Test
    fun `granularity is day for short ranges and week for long`() {
        assertEquals(
            TrendGranularity.DAY,
            granularityFor(LocalDate.parse("2026-07-01")..LocalDate.parse("2026-07-15")),
        )
        assertEquals(
            TrendGranularity.WEEK,
            granularityFor(LocalDate.parse("2026-01-01")..LocalDate.parse("2026-03-01")),
        )
    }

    private fun half(today: String, range: StatRange): ClosedRange<LocalDate> = range.resolve(LocalDate.parse(today), DayOfWeek.MONDAY)

    private fun assertRange(start: String, end: String, actual: ClosedRange<LocalDate>) {
        assertEquals(LocalDate.parse(start)..LocalDate.parse(end), actual)
    }

    @Test
    fun `current half month runs from its first day to today`() {
        assertRange("2026-07-01", "2026-07-01", half("2026-07-01", StatRange.ThisHalfMonth))
        assertRange("2026-07-01", "2026-07-15", half("2026-07-15", StatRange.ThisHalfMonth))
        assertRange("2026-07-16", "2026-07-16", half("2026-07-16", StatRange.ThisHalfMonth))
        assertRange("2026-07-16", "2026-07-31", half("2026-07-31", StatRange.ThisHalfMonth))
    }

    @Test
    fun `previous half from a second half is the first half of the same month`() {
        assertRange("2026-07-01", "2026-07-15", half("2026-07-16", StatRange.PreviousHalfMonth))
        assertRange("2026-07-01", "2026-07-15", half("2026-07-31", StatRange.PreviousHalfMonth))
    }

    @Test
    fun `previous half from a first half is the second half of the prior month for every month length`() {
        // 28, 29, 30 and 31 day prior months.
        assertRange("2026-02-16", "2026-02-28", half("2026-03-01", StatRange.PreviousHalfMonth))
        assertRange("2028-02-16", "2028-02-29", half("2028-03-15", StatRange.PreviousHalfMonth))
        assertRange("2026-06-16", "2026-06-30", half("2026-07-10", StatRange.PreviousHalfMonth))
        assertRange("2026-07-16", "2026-07-31", half("2026-08-15", StatRange.PreviousHalfMonth))
    }

    @Test
    fun `previous half crosses the year boundary`() {
        assertRange("2025-12-16", "2025-12-31", half("2026-01-05", StatRange.PreviousHalfMonth))
    }

    @Test
    fun `half month follows the account zone local day, not UTC`() {
        // 23:30 UTC on the 15th is already the 16th in Tokyo, so Tokyo is in the second half.
        val instant = Instant.parse("2026-07-15T23:30:00Z")
        val tokyoToday = LocalDate.ofInstant(instant, ZoneId.of("Asia/Tokyo"))
        val utcToday = LocalDate.ofInstant(instant, ZoneId.of("UTC"))
        assertRange("2026-07-16", "2026-07-16", StatRange.ThisHalfMonth.resolve(tokyoToday, DayOfWeek.MONDAY))
        assertRange("2026-07-01", "2026-07-15", StatRange.ThisHalfMonth.resolve(utcToday, DayOfWeek.MONDAY))
    }

    @Test
    fun `every preset period and offset maps to one range`() {
        val expected = mapOf(
            (StatPeriod.Day to StatOffset.Current) to StatRange.Today,
            (StatPeriod.Day to StatOffset.Previous) to StatRange.Yesterday,
            (StatPeriod.Week to StatOffset.Current) to StatRange.ThisWeek,
            (StatPeriod.Week to StatOffset.Previous) to StatRange.LastWeek,
            (StatPeriod.Month to StatOffset.Current) to StatRange.ThisMonth,
            (StatPeriod.Month to StatOffset.Previous) to StatRange.PreviousMonth,
            (StatPeriod.Half to StatOffset.Current) to StatRange.ThisHalfMonth,
            (StatPeriod.Half to StatOffset.Previous) to StatRange.PreviousHalfMonth,
        )
        expected.forEach { (key, range) ->
            val (period, offset) = key
            assertEquals(range, StatRange.preset(period, offset))
            assertEquals(period, range.period)
            assertEquals(offset, range.offset)
        }
    }

    @Test
    fun `custom has no preset`() {
        assertNull(StatRange.preset(StatPeriod.Custom, StatOffset.Current))
        assertNull(StatRange.preset(StatPeriod.Custom, StatOffset.Previous))
        assertEquals(StatPeriod.Custom, StatRange.Custom(today, today).period)
    }
}
