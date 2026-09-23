/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields

private const val MONTHLY_GRANULARITY_DAYS = 31L

/** Last day of the first half of a month; the second half runs from the next day to month end. */
private const val FIRST_HALF_LAST_DAY = 15

// Minimal-days matching WeekFields.ISO so a Monday [firstDayOfWeek] reproduces ISO week starts
// exactly; only the first-day-of-week affects the week-START computation used here.
private const val WEEK_MIN_DAYS = 4

/** First local day (per [firstDayOfWeek]) of the week containing [date]. */
private fun weekStart(date: LocalDate, firstDayOfWeek: DayOfWeek): LocalDate =
    date.with(WeekFields.of(firstDayOfWeek, WEEK_MIN_DAYS).dayOfWeek(), 1)

/** The 1st–15th or 16th–end half of the month containing [date], as an inclusive window. */
private fun halfMonthOf(date: LocalDate): ClosedRange<LocalDate> = if (date.dayOfMonth <= FIRST_HALF_LAST_DAY) {
    date.withDayOfMonth(1)..date.withDayOfMonth(FIRST_HALF_LAST_DAY)
} else {
    date.withDayOfMonth(FIRST_HALF_LAST_DAY + 1)..date.withDayOfMonth(date.lengthOfMonth())
}

/** The period length the Dashboard's first segmented control selects. */
enum class StatPeriod { Day, Week, Month, Half, Custom }

/** Whether a preset period is the one containing today or the one before it. */
enum class StatOffset { Current, Previous }

sealed interface StatRange {
    val period: StatPeriod

    /**
     * Resolves this range to an inclusive [today]-relative window. Week-based variants start the
     * week on [firstDayOfWeek] (from the account [dev.tricked.solidverdant.domain.time.TemporalPolicy]);
     * non-week variants ignore it. Current periods end at [today]; previous periods are complete.
     */
    fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek): ClosedRange<LocalDate>

    /** A range the Dashboard selects as a ([period], [offset]) pair; see [preset]. */
    sealed interface Preset : StatRange {
        val offset: StatOffset
    }

    data object Today : Preset {
        override val period = StatPeriod.Day
        override val offset = StatOffset.Current
        override fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek) = today..today
    }

    data object Yesterday : Preset {
        override val period = StatPeriod.Day
        override val offset = StatOffset.Previous
        override fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek) = today.minusDays(1)..today.minusDays(1)
    }

    data object ThisWeek : Preset {
        override val period = StatPeriod.Week
        override val offset = StatOffset.Current
        override fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek): ClosedRange<LocalDate> = weekStart(today, firstDayOfWeek)..today
    }

    data object LastWeek : Preset {
        override val period = StatPeriod.Week
        override val offset = StatOffset.Previous
        override fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek): ClosedRange<LocalDate> {
            val thisWeekStart = weekStart(today, firstDayOfWeek)
            return thisWeekStart.minusWeeks(1)..thisWeekStart.minusDays(1)
        }
    }

    data object ThisMonth : Preset {
        override val period = StatPeriod.Month
        override val offset = StatOffset.Current
        override fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek): ClosedRange<LocalDate> = today.withDayOfMonth(1)..today
    }

    data object PreviousMonth : Preset {
        override val period = StatPeriod.Month
        override val offset = StatOffset.Previous
        override fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek): ClosedRange<LocalDate> {
            val month = today.withDayOfMonth(1).minusMonths(1)
            return month..month.withDayOfMonth(month.lengthOfMonth())
        }
    }

    /** The half month (1st–15th or 16th–end) containing today, up to today. */
    data object ThisHalfMonth : Preset {
        override val period = StatPeriod.Half
        override val offset = StatOffset.Current
        override fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek): ClosedRange<LocalDate> = halfMonthOf(today).start..today
    }

    /** The complete half month before the current one; from a 1st–15th half it is last month's second half. */
    data object PreviousHalfMonth : Preset {
        override val period = StatPeriod.Half
        override val offset = StatOffset.Previous
        override fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek): ClosedRange<LocalDate> =
            halfMonthOf(halfMonthOf(today).start.minusDays(1))
    }

    data class Custom(val start: LocalDate, val end: LocalDate) : StatRange {
        init {
            require(!end.isBefore(start)) { "Custom range end must not precede start" }
        }
        override val period = StatPeriod.Custom
        override fun resolve(today: LocalDate, firstDayOfWeek: DayOfWeek): ClosedRange<LocalDate> = start..end
    }

    companion object {
        private val presets: List<Preset> = listOf(
            Today,
            Yesterday,
            ThisWeek,
            LastWeek,
            ThisMonth,
            PreviousMonth,
            ThisHalfMonth,
            PreviousHalfMonth,
        )

        /** The preset for [period] and [offset], or null for [StatPeriod.Custom], which has no preset. */
        fun preset(period: StatPeriod, offset: StatOffset): Preset? = presets.firstOrNull { it.period == period && it.offset == offset }
    }
}

fun granularityFor(range: ClosedRange<LocalDate>): TrendGranularity {
    val days = ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1
    return if (days <= MONTHLY_GRANULARITY_DAYS) TrendGranularity.DAY else TrendGranularity.WEEK
}
