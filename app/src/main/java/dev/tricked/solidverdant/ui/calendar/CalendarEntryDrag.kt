/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import dev.tricked.solidverdant.data.model.TimeEntry
import dev.tricked.solidverdant.domain.time.formatTimeEntryInstant
import dev.tricked.solidverdant.domain.time.parseTimeEntryInstant
import dev.tricked.solidverdant.ui.tracking.EntryTrustRules
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * Adds the hold-then-drag move gesture to a rendered entry block. After a long press the
 * completed entry lifts and follows the pointer; the drop position is snapped to the calendar grid
 * and the complete interval is preserved through the caller's Room/outbox mutation path. Until the
 * long press lands, a drag belongs to the grid, so a swipe or scroll that starts on an entry still
 * pages or scrolls the calendar, and taps remain available to the caller's click modifier.
 *
 * The gesture is read outside the drag offset: reading it inside made every pointer position
 * relative to the block's own moving bounds, so the block chased itself and jittered. On drop the
 * block settles on the snapped slot and stays there until the moved entry arrives, rather than
 * snapping back to its old time for a frame. [onDragActiveChange] lets the day column draw above
 * its neighbours while an entry is dragged across them.
 */
@Composable
internal fun calendarEntryDragModifier(
    modifier: Modifier,
    entry: TimeEntry,
    day: LocalDate,
    zone: ZoneId,
    settings: CalendarGridSettings = CalendarGridSettings(),
    dayIndex: Int,
    dayCount: Int,
    blockStartFraction: Float,
    blockHeightPx: Float,
    gridHeightPx: Float,
    columnWidthPx: Float,
    onMoveEntry: (TimeEntry, String, String) -> Unit,
    onDragActiveChange: (Boolean) -> Unit = {},
): Modifier {
    val startDate = remember(entry.start, zone) { entryStartDate(entry, zone) }
    val canMove = entry.end != null && startDate == day
    val density = androidx.compose.ui.platform.LocalDensity.current
    val sizedModifier = modifier.height(with(density) { blockHeightPx.toDp() })
    // Running and cross-day entries are not movable, but they still need the measured timeline
    // height. Returning the caller's un-sized modifier made a running block collapse to its text
    // content instead of growing with elapsed time.
    if (!canMove || columnWidthPx <= 0f || gridHeightPx <= 0f || blockHeightPx <= 0f) return sizedModifier

    val onMoveEntryState by rememberUpdatedState(onMoveEntry)
    val onDragActiveChangeState by rememberUpdatedState(onDragActiveChange)
    // The gesture outlives recompositions, so it reads the latest entry and position, not the
    // values captured when it started: a second drag must start from where the first one landed.
    val currentEntry by rememberUpdatedState(entry)
    val baseTopPx by rememberUpdatedState(blockStartFraction * gridHeightPx)
    val haptic = LocalHapticFeedback.current
    var dragOffset by remember(entry.id, day) { mutableStateOf(Offset.Zero) }
    var isDragging by remember(entry.id, day) { mutableStateOf(false) }
    var awaitingMove by remember(entry.id, day) { mutableStateOf(false) }

    // The moved entry has arrived at its dropped slot, so the block no longer needs the offset.
    LaunchedEffect(entry.start, entry.end) {
        if (!isDragging) {
            dragOffset = Offset.Zero
            awaitingMove = false
        }
    }
    // A move the caller did not apply must not leave the block stranded at the drop slot.
    LaunchedEffect(awaitingMove) {
        if (awaitingMove) {
            delay(MOVE_SETTLE_TIMEOUT_MS)
            dragOffset = Offset.Zero
            awaitingMove = false
        }
    }
    // Paired start/stop, so a block that leaves the column mid-move still releases its column.
    val dragActive = isDragging || awaitingMove
    DisposableEffect(dragActive) {
        if (dragActive) onDragActiveChangeState(true)
        onDispose { if (dragActive) onDragActiveChangeState(false) }
    }

    return sizedModifier
        .zIndex(if (isDragging || awaitingMove) DRAGGED_ENTRY_Z_INDEX else 0f)
        .pointerInput(entry.id, day, dayIndex, dayCount, gridHeightPx, columnWidthPx, settings) {
            fun dropTarget(totalDrag: Offset): Pair<Offset, CalendarEntryRange>? {
                val targetDayIndex = (dayIndex + (totalDrag.x / columnWidthPx).roundToInt())
                    .coerceIn(0, (dayCount - 1).coerceAtLeast(0))
                val dayShift = targetDayIndex - dayIndex
                val entryStart = parseTimeEntryInstant(currentEntry.start) ?: return null
                val targetStart = calendarDragTargetStart(
                    entryStart = entryStart,
                    day = day,
                    dayShift = dayShift,
                    dragYPx = totalDrag.y,
                    gridHeightPx = gridHeightPx,
                    zone = zone,
                    settings = settings,
                )
                val range = calendarEntryRangeAt(currentEntry, targetStart) ?: return null
                val grid = calendarGridBounds(day.plusDays(dayShift.toLong()), zone, settings)
                // The block is drawn clipped to the grid, so it settles on the clipped top too.
                val targetTopPx = (
                    (targetStart.toInstant().epochSecond - grid.start.epochSecond).toFloat() /
                        grid.seconds.coerceAtLeast(1L)
                    ).coerceIn(0f, 1f) * gridHeightPx
                val settled = Offset(dayShift * columnWidthPx, targetTopPx - baseTopPx)
                return settled to range
            }
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                // Null when the pointer lifts or moves (a tap, scroll or swipe) before the hold.
                val lifted = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                isDragging = true
                awaitingMove = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                lifted.consume()
                // Positions are in this node's layout bounds, which the drag offset does not move.
                val startPosition = down.position
                var totalDrag = Offset.Zero
                val completed = drag(lifted.id) { change ->
                    totalDrag = change.position - startPosition
                    dragOffset = totalDrag
                    change.consume()
                }
                val target = if (completed && totalDrag != Offset.Zero) dropTarget(totalDrag) else null
                val moved = target != null && movesEntry(currentEntry, target.second)
                if (target != null && moved) {
                    dragOffset = target.first
                    awaitingMove = true
                    isDragging = false
                    dispatchMove(currentEntry, target.second, onMoveEntryState)
                } else {
                    dragOffset = Offset.Zero
                    isDragging = false
                }
            }
        }
        .offset { IntOffset(dragOffset.x.roundToInt(), dragOffset.y.roundToInt()) }
        .graphicsLayer { alpha = if (isDragging) DRAGGED_ENTRY_ALPHA else 1f }
}

/**
 * Local overlap warning for a moved entry. This is deliberately advisory: Solidtime remains the
 * authority for whether overlapping tracked time is allowed, but a drag should surface the same
 * useful warning as the editor before the optimistic Room/outbox mutation is sent.
 */
internal fun calendarMoveOverlapsExisting(
    entry: TimeEntry,
    start: String,
    end: String,
    existingEntries: Iterable<TimeEntry>,
    now: Instant = Instant.now(),
): Boolean {
    val moved = entry.copy(start = start, end = end)
    return existingEntries.any { candidate -> EntryTrustRules.overlaps(moved, candidate, now) }
}

/**
 * Whether dropping [entry] at [range] changes it. Instants are compared, not strings: the server
 * writes `Z` timestamps and a local copy may carry an offset, so equal times can differ as text,
 * and a hold released on the entry's own slot must not send an update.
 */
internal fun movesEntry(entry: TimeEntry, range: CalendarEntryRange): Boolean =
    parseTimeEntryInstant(entry.start) != range.start.toInstant() ||
        entry.end?.let(::parseTimeEntryInstant) != range.end.toInstant()

/** Moves are written in the app's UTC `Z` shape, like every other local time-entry writer. */
private fun dispatchMove(entry: TimeEntry, range: CalendarEntryRange, callback: (TimeEntry, String, String) -> Unit) {
    callback(entry, formatTimeEntryInstant(range.start), formatTimeEntryInstant(range.end))
}

private fun entryStartDate(entry: TimeEntry, zone: ZoneId): LocalDate? = parseTimeEntryInstant(entry.start)?.atZone(zone)?.toLocalDate()

private const val DRAGGED_ENTRY_ALPHA = 0.72f
private const val DRAGGED_ENTRY_Z_INDEX = 2f
private const val MOVE_SETTLE_TIMEOUT_MS = 1_500L
