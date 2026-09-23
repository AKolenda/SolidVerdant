/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import dev.tricked.solidverdant.ui.theme.Dimens
import kotlin.math.abs

/**
 * Calendar paging: swipe towards the start for the next day, week or month and towards
 * the end for the previous one (mirrored in right-to-left layouts). A drag only pages once it moved
 * horizontally first; vertical scrolling, the hold-to-drag entry move and the long-press range
 * selection keep their gestures because they claim the pointer before this parent sees it.
 */
internal fun Modifier.calendarSwipePaging(onPrevious: () -> Unit, onNext: () -> Unit): Modifier = composed {
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    pointerInput(rtl) {
        val threshold = Dimens.CalendarSwipeThreshold.toPx()
        var total = 0f
        detectHorizontalDragGestures(
            onDragStart = { total = 0f },
            onDragCancel = { total = 0f },
            onHorizontalDrag = { change, dragAmount ->
                change.consume()
                total += dragAmount
            },
            onDragEnd = {
                val swipe = calendarSwipeDirection(total, threshold, rtl)
                total = 0f
                when (swipe) {
                    CalendarSwipe.NEXT -> next()
                    CalendarSwipe.PREVIOUS -> previous()
                    null -> Unit
                }
            },
        )
    }
}

internal enum class CalendarSwipe { PREVIOUS, NEXT }

/** Which page a horizontal drag of [distancePx] turns to, or null when it stays below [thresholdPx]. */
internal fun calendarSwipeDirection(distancePx: Float, thresholdPx: Float, rtl: Boolean): CalendarSwipe? {
    if (abs(distancePx) < thresholdPx) return null
    // A finger moving left (negative x) reveals what comes next in left-to-right layouts.
    val towardsStart = distancePx < 0f
    return if (towardsStart != rtl) CalendarSwipe.NEXT else CalendarSwipe.PREVIOUS
}
