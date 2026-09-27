/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntOffset
import dev.tricked.solidverdant.data.model.TimeEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.roundToInt

/**
 * Hold-then-drag in the calendar grid. The block moves under the finger while the gesture is
 * read, so these pin that the dropped time follows the finger's full travel, and that a second
 * drag starts from where the first one landed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CalendarEntryDragTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val zone = ZoneOffset.UTC
    private val day = LocalDate.of(2026, 8, 6)
    private var settings = CalendarGridSettings(snapMinutes = 15, startHour = 0, endHour = 24)

    // 100 px per hour.
    private val gridHeightPx get() = (settings.endHour - settings.startHour) * 100f

    private var entry by mutableStateOf(
        TimeEntry(
            id = "e1",
            userId = "u1",
            organizationId = "org1",
            start = "2026-08-06T09:00:00Z",
            end = "2026-08-06T10:00:00Z",
        ),
    )
    private val moves = mutableListOf<Pair<String, String>>()

    /** The drawn block's top: like the grid layout, clipped to the visible hours. */
    private fun startFraction(e: TimeEntry): Float {
        val start = ZonedDateTime.parse(e.start).withZoneSameInstant(zone)
        val minutes = (start.hour * 60 + start.minute - settings.startMinute).coerceAtLeast(0)
        return minutes / settings.visibleMinutes.toFloat()
    }

    private fun setGrid(applyMoves: Boolean) {
        composeRule.setContent {
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize()) {
                val fraction = startFraction(entry)
                Box(
                    calendarEntryDragModifier(
                        modifier = Modifier
                            .offset { IntOffset(0, (fraction * gridHeightPx).roundToInt()) }
                            .width(with(density) { 300f.toDp() }),
                        entry = entry,
                        day = day,
                        zone = zone,
                        settings = settings,
                        dayIndex = 0,
                        dayCount = 1,
                        blockStartFraction = fraction,
                        blockHeightPx = 100f,
                        gridHeightPx = gridHeightPx,
                        columnWidthPx = 300f,
                        onMoveEntry = { moved, start, end ->
                            moves += start to end
                            if (applyMoves) entry = moved.copy(start = start, end = end)
                        },
                    ).testTag(BLOCK),
                )
            }
        }
    }

    /**
     * Hold, then drag down in [steps] equal moves totalling [totalPx]. Each move is its own frame,
     * so the block has been laid out at its dragged offset before the next move is read.
     */
    private fun holdAndDrag(totalPx: Float, steps: Int = 4) {
        val block = composeRule.onNodeWithTag(BLOCK)
        block.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + LONG_PRESS_MARGIN_MS)
            // The hold is only recognised once a later event arrives after the timeout.
            moveBy(Offset.Zero)
        }
        composeRule.waitForIdle()
        repeat(steps) {
            block.performTouchInput { moveBy(Offset(0f, totalPx / steps), delayMillis = 32) }
            composeRule.waitForIdle()
        }
        block.performTouchInput { up() }
        composeRule.waitForIdle()
    }

    @Test
    fun dropped_time_follows_the_whole_drag_even_as_the_block_moves_under_the_finger() {
        setGrid(applyMoves = false)

        holdAndDrag(totalPx = 200f)

        assertEquals(listOf(ZonedDateTime.parse("2026-08-06T11:00:00Z")), moves.map { ZonedDateTime.parse(it.first) })
        assertEquals(ZonedDateTime.parse("2026-08-06T12:00:00Z"), ZonedDateTime.parse(moves.single().second))
    }

    @Test
    fun a_second_drag_starts_from_where_the_first_landed() {
        setGrid(applyMoves = true)

        holdAndDrag(totalPx = 100f)
        holdAndDrag(totalPx = 50f, steps = 2)

        assertEquals(
            listOf("2026-08-06T10:00:00Z", "2026-08-06T10:30:00Z").map(ZonedDateTime::parse),
            moves.map { ZonedDateTime.parse(it.first) },
        )
    }

    @Test
    fun an_entry_starting_before_the_visible_hours_moves_by_the_drag_only() {
        // Grid from 08:00: the 07:00 entry is drawn from the grid top, an hour below its start.
        settings = settings.copy(startHour = 8)
        entry = entry.copy(start = "2026-08-06T07:00:00Z", end = "2026-08-06T09:00:00Z")
        setGrid(applyMoves = false)

        holdAndDrag(totalPx = 50f)

        assertEquals(listOf("2026-08-06T07:30:00Z" to "2026-08-06T09:30:00Z"), moves)
    }

    @Test
    fun moves_are_written_as_utc_timestamps() {
        entry = entry.copy(start = "2026-08-06T11:00:00+02:00", end = "2026-08-06T12:00:00+02:00")
        setGrid(applyMoves = false)

        holdAndDrag(totalPx = 100f)

        assertEquals(listOf("2026-08-06T10:00:00Z" to "2026-08-06T11:00:00Z"), moves)
    }

    @Test
    fun a_hold_released_on_the_same_slot_sends_no_update() {
        entry = entry.copy(start = "2026-08-06T11:00:00+02:00", end = "2026-08-06T12:00:00+02:00")
        setGrid(applyMoves = false)

        holdAndDrag(totalPx = 4f, steps = 1)

        assertEquals(emptyList<Pair<String, String>>(), moves)
    }

    @Test
    fun a_drag_without_the_hold_does_not_move_the_entry() {
        setGrid(applyMoves = false)

        composeRule.onNodeWithTag(BLOCK).performTouchInput {
            down(center)
            repeat(4) { moveBy(Offset(0f, 50f), delayMillis = 16) }
            up()
        }
        composeRule.waitForIdle()

        assertEquals(emptyList<Pair<String, String>>(), moves)
    }

    private companion object {
        const val BLOCK = "drag-block"
        const val LONG_PRESS_MARGIN_MS = 100L
    }
}
