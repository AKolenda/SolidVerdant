/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.domain.time.formatTimeEntryInstant
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong

/** A half-open time range selected in a calendar time grid. */
data class CalendarTimeRange(val start: ZonedDateTime, val end: ZonedDateTime)

/** The complete interval produced when an existing entry is moved to a new start. */
data class CalendarEntryRange(val start: ZonedDateTime, val end: ZonedDateTime)

/**
 * Convert a vertical drag in a configurable grid into a valid, snapped range. The grid uses the
 * actual elapsed length of the local day, so a DST transition does not create an invalid instant.
 */
fun calendarTimeRangeForDrag(
    day: LocalDate,
    startY: Float,
    endY: Float,
    gridHeightPx: Float,
    zone: ZoneId,
    settings: CalendarGridSettings = CalendarGridSettings(),
): CalendarTimeRange {
    val grid = calendarGridBounds(day, zone, settings)
    val secondsInGrid = grid.seconds
    val slotSeconds = settings.normalized().snapMinutes * SECONDS_PER_MINUTE
    val maxStartSecond = (secondsInGrid - slotSeconds).coerceAtLeast(0L)
    val lowSecond = calendarGridSecond(startY, gridHeightPx, secondsInGrid)
    val highSecond = calendarGridSecond(endY, gridHeightPx, secondsInGrid)
    val startSecond = snapToSlot(minOf(lowSecond, highSecond), slotSeconds).coerceIn(0L, maxStartSecond)
    val endSecond = snapToSlot(maxOf(lowSecond, highSecond), slotSeconds)
        .coerceIn(startSecond + slotSeconds, secondsInGrid)
    return CalendarTimeRange(
        start = grid.start.plusSeconds(startSecond).atZone(zone),
        end = grid.start.plusSeconds(endSecond).atZone(zone),
    )
}

/** Convert one vertical grid coordinate into the nearest valid calendar start time. */
fun calendarTimeAtGridPosition(
    day: LocalDate,
    y: Float,
    gridHeightPx: Float,
    zone: ZoneId,
    allowDayEnd: Boolean = false,
    settings: CalendarGridSettings = CalendarGridSettings(),
): ZonedDateTime {
    val normalized = settings.normalized()
    val grid = calendarGridBounds(day, zone, normalized)
    val slotSeconds = normalized.snapMinutes * SECONDS_PER_MINUTE
    val maxSecond = if (allowDayEnd) grid.seconds else (grid.seconds - slotSeconds).coerceAtLeast(0L)
    val second = snapToSlot(calendarGridSecond(y, gridHeightPx, grid.seconds), slotSeconds).coerceIn(0L, maxSecond)
    return grid.start.plusSeconds(second).atZone(zone)
}

/**
 * Where a held entry lands after being dragged [dragYPx] down (negative is up) and [dayShift]
 * columns across from [day]. The drop is the entry's real [entryStart] plus the dragged time,
 * snapped to the grid. Working from the real start rather than the drawn block matters for an entry
 * that starts before the visible hours: its block is clipped to the grid top, so reading the drop
 * from the block's top would shift it by the hidden part as well as by the drag.
 *
 * The start stays inside the target day's grid, except that an entry which already started before
 * the grid may stay up to its original time of day.
 */
fun calendarDragTargetStart(
    entryStart: Instant,
    day: LocalDate,
    dayShift: Int,
    dragYPx: Float,
    gridHeightPx: Float,
    zone: ZoneId,
    settings: CalendarGridSettings = CalendarGridSettings(),
): ZonedDateTime {
    val normalized = settings.normalized()
    val sourceGrid = calendarGridBounds(day, zone, normalized)
    val targetGrid = calendarGridBounds(day.plusDays(dayShift.toLong()), zone, normalized)
    val slotSeconds = normalized.snapMinutes * SECONDS_PER_MINUTE
    // The same local time-of-day on the target day: both grids open at the configured start hour.
    val startOffset = entryStart.epochSecond - sourceGrid.start.epochSecond
    val dragSeconds = if (gridHeightPx <= 0f) 0L else (dragYPx / gridHeightPx * sourceGrid.seconds).roundToLong()
    val minSecond = minOf(0L, startOffset)
    val maxSecond = (targetGrid.seconds - slotSeconds).coerceAtLeast(minSecond)
    val second = snapToSlot(startOffset + dragSeconds, slotSeconds).coerceIn(minSecond, maxSecond)
    return targetGrid.start.plusSeconds(second).atZone(zone)
}

/** Preserve an entry's complete duration while moving its start across local calendar days. */
fun calendarEntryRangeAt(entry: TimeEntry, targetStart: ZonedDateTime): CalendarEntryRange? {
    val originalStart = runCatching { ZonedDateTime.parse(entry.start) }.getOrNull() ?: return null
    val originalEnd = entry.end?.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() } ?: return null
    val duration = Duration.between(originalStart, originalEnd)
    if (duration.isZero || duration.isNegative) return null
    return CalendarEntryRange(start = targetStart, end = targetStart.plus(duration))
}

/**
 * The split point chosen as [date] at [hour]:[minute] in [zone], in the app's UTC `Z` timestamp
 * shape, or null unless it falls strictly inside [start]..[end].
 */
fun calendarSplitTimestamp(date: LocalDate, hour: Int, minute: Int, zone: ZoneId, start: ZonedDateTime, end: ZonedDateTime): String? {
    val candidate = date.atTime(hour, minute).atZone(zone)
    return if (candidate.isAfter(start) && candidate.isBefore(end)) formatTimeEntryInstant(candidate) else null
}

/** A useful one-hour fallback for the toolbar's Add action when no drag range was selected. */
fun defaultCalendarTimeRange(
    day: LocalDate,
    zone: ZoneId,
    now: ZonedDateTime = ZonedDateTime.now(zone),
    settings: CalendarGridSettings = CalendarGridSettings(),
): CalendarTimeRange {
    val grid = calendarGridBounds(day, zone, settings)
    val oneHour = 60L * SECONDS_PER_MINUTE
    val available = grid.seconds.coerceAtLeast(1L)
    val duration = minOf(oneHour, available)
    val preferredStart = if (day == now.toLocalDate()) {
        now.truncatedTo(ChronoUnit.HOURS).minusHours(1).toInstant()
    } else {
        grid.start
    }
    val start = preferredStart.coerceIn(grid.start, grid.end.minusSeconds(duration))
    return CalendarTimeRange(start = start.atZone(zone), end = start.plusSeconds(duration).atZone(zone))
}

private fun calendarGridSecond(y: Float, gridHeightPx: Float, secondsInGrid: Long): Long {
    if (gridHeightPx <= 0f) return 0L
    return ((y / gridHeightPx).coerceIn(0f, 1f) * secondsInGrid).toLong()
}

// Floor division so a time before the grid start (a negative offset) snaps like any other.
private fun snapToSlot(second: Long, slotSeconds: Long): Long = Math.floorDiv(second + slotSeconds / 2, slotSeconds) * slotSeconds

private const val SECONDS_PER_MINUTE = 60L
