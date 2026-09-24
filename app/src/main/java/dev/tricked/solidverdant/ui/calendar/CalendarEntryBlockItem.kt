/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import dev.tricked.solidverdant.R
import dev.tricked.solidverdant.data.model.Client
import dev.tricked.solidverdant.data.model.Project
import dev.tricked.solidverdant.data.model.Task
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.data.model.TimeEntryType
import dev.tricked.solidverdant.data.repository.TimeEntryRepository.EntrySyncStatus
import dev.tricked.solidverdant.domain.time.parseTimeEntryInstant
import dev.tricked.solidverdant.ui.components.EntryBlock
import dev.tricked.solidverdant.ui.statistics.hexToColor
import dev.tricked.solidverdant.ui.theme.Dimens
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One tracked entry in a day column of the week grid or the month's day timeline, with the shared
 * [EntryBlock] look, hold-to-drag and a tap for its actions.
 *
 * It is its own restart scope: a clock tick or a change to another entry does not recompose it.
 * Only an entry without an end (a running timer) reads [clock], so just that block grows and
 * counts up every second while the rest of the grid stays still. [modifier] places the block
 * (offset and width) inside its column.
 */
@Composable
internal fun CalendarEntryBlockItem(
    block: TrackedEntryBlock,
    day: LocalDate,
    zone: ZoneId,
    settings: CalendarGridSettings,
    clock: State<Instant>,
    totalHeight: Dp,
    gridHeightPx: Float,
    columnWidthPx: Float,
    dayIndex: Int,
    dayCount: Int,
    project: Project?,
    task: Task?,
    client: Client?,
    syncStatus: EntrySyncStatus?,
    testTag: String,
    showBreakSubtitle: Boolean,
    formatOpenDuration: (Long) -> String,
    onEntryClick: (TimeEntry) -> Unit,
    onMoveEntry: (TimeEntry, String, String) -> Unit,
    modifier: Modifier = Modifier,
    onDragActiveChange: (Boolean) -> Unit = {},
) {
    val entry = block.entry
    val isBreak = entry.type == TimeEntryType.BREAK
    val metadata = remember(entry, project?.name, task?.name, client?.name) {
        calendarEntryMetadata(entry = entry, projectName = project?.name, taskName = task?.name, clientName = client?.name)
    }
    val grid = remember(day, zone, settings) { calendarGridBounds(day, zone, settings) }
    val openStart = remember(entry.start, entry.end, entry.duration) {
        entry.takeIf(::hasOpenEnd)?.let { parseTimeEntryInstant(it.start) }
    }
    // The only per-second read in the grid, and only for an entry that is still running.
    val now = if (openStart != null) clock.value else null
    val heightFraction = if (now != null) liveEntryHeightFraction(block, grid, now) else block.heightFraction

    val color = if (isBreak) {
        MaterialTheme.colorScheme.tertiary
    } else {
        project?.color?.let { hexToColor(it) } ?: MaterialTheme.colorScheme.primary
    }
    val label = if (isBreak) {
        entry.description?.ifBlank { null }?.let { stringResource(R.string.calendar_break_with_description, it) }
            ?: stringResource(R.string.calendar_break_entry)
    } else {
        metadata.title ?: stringResource(R.string.calendar_entry_untitled)
    }
    val subtitle = metadata.subtitle.takeUnless { isBreak && !showBreakSubtitle }
    val duration = metadata.durationSeconds?.let(::formatDuration)
        ?: if (openStart != null && now != null) formatOpenDuration(openEntrySecondsOnDay(openStart, day, zone, now)) else null
    val details = listOfNotNull(subtitle, duration).joinToString(", ")
    val a11y = if (details.isBlank()) {
        stringResource(R.string.calendar_entry_a11y, label)
    } else {
        stringResource(R.string.calendar_entry_a11y_details, label, details)
    }
    val blockHeight = (totalHeight * heightFraction).coerceAtLeast(Dimens.EntryMinHeight)
    val entryModifier = calendarEntryDragModifier(
        modifier = modifier,
        entry = entry,
        day = day,
        zone = zone,
        settings = settings,
        dayIndex = dayIndex,
        dayCount = dayCount,
        blockStartFraction = block.startFraction,
        blockHeightPx = with(LocalDensity.current) { blockHeight.toPx() },
        gridHeightPx = gridHeightPx,
        columnWidthPx = columnWidthPx,
        onMoveEntry = onMoveEntry,
        onDragActiveChange = onDragActiveChange,
    )
    EntryBlock(
        color = color,
        title = label,
        subtitle = subtitle,
        time = duration,
        modifier = entryModifier
            .height(blockHeight)
            // Tap opens the entry's actions; a hold lifts it for dragging.
            .clickable(role = Role.Button) { onEntryClick(entry) }
            .testTag(testTag)
            .semantics { contentDescription = a11y },
        syncStatus = syncStatus,
    )
}

/** An entry with no end yet (a running timer): its drawn length follows the clock. */
internal fun hasOpenEnd(entry: TimeEntry): Boolean = entry.end == null && (entry.duration ?: 0) <= 0

/**
 * A running block's height at [now]: from its top down to now, clipped to the grid, and never
 * shorter than the minute-granular layout gave it (which keeps the minimum tappable height).
 */
internal fun liveEntryHeightFraction(block: TrackedEntryBlock, grid: CalendarGridBounds, now: Instant): Float {
    val nowFraction = ((now.epochSecond - grid.start.epochSecond).toFloat() / grid.seconds.coerceAtLeast(1L)).coerceIn(0f, 1f)
    return maxOf(block.heightFraction, nowFraction - block.startFraction)
}

/** Seconds of an open entry that started at [start] which fall on [day], up to [now]. */
internal fun openEntrySecondsOnDay(start: Instant, day: LocalDate, zone: ZoneId, now: Instant): Long {
    val dayStart = day.atStartOfDay(zone).toInstant()
    val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
    val from = maxOf(start, dayStart)
    val until = minOf(now, dayEnd)
    return (until.epochSecond - from.epochSecond).coerceAtLeast(0L)
}

/**
 * The instant a day's layout depends on: the minute clock for a day with a running entry, null
 * otherwise, so a day without one keeps its layout across clock ticks.
 */
internal fun calendarLayoutClockKey(entries: List<TimeEntry>, minuteNow: Instant): Instant? = minuteNow.takeIf { entries.any(::hasOpenEnd) }
