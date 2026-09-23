/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.statistics

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The immediately preceding equivalent-length window for [range].
 *
 * The previous period has the same number of inclusive days and ends the day before [range] starts,
 * so a 7-day range compares against the prior 7 days and a full month compares against the same
 * number of days directly before it. This is deterministic and independent of calendar shape, which
 * keeps tiny or partial current ranges from producing overlapping or zero-length comparison windows.
 */
fun previousPeriod(range: ClosedRange<LocalDate>): ClosedRange<LocalDate> {
    val days = ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1
    val prevEnd = range.start.minusDays(1)
    val prevStart = range.start.minusDays(days)
    return prevStart..prevEnd
}

/** A single metric measured across the current and previous periods. */
data class MetricDelta(val current: Long, val previous: Long) {
    val absoluteDelta: Long get() = current - previous

    /**
     * Percentage change from [previous] to [current], or null when there is no prior baseline to
     * grow from (an empty previous period). Callers render null as "new"/no-percentage rather than
     * dividing by zero or reporting a misleading infinite jump.
     */
    fun percentChange(): Double? = if (previous == 0L) null else (current - previous) * PERCENT_SCALE / previous
}

private const val PERCENT_SCALE = 100.0

/** Total tracked time across the current and previous periods, shown on the Dashboard total card. */
data class PeriodComparison(val total: MetricDelta, val previousStart: LocalDate, val previousEnd: LocalDate)

/** Builds the [PeriodComparison] from two already-computed summaries plus the previous window's dates. */
fun computeComparison(current: StatisticsSummary, previous: StatisticsSummary, previousRange: ClosedRange<LocalDate>): PeriodComparison =
    PeriodComparison(
        total = MetricDelta(current.totalSeconds, previous.totalSeconds),
        previousStart = previousRange.start,
        previousEnd = previousRange.endInclusive,
    )
