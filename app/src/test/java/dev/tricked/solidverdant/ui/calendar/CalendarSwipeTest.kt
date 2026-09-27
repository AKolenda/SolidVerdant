/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */

package dev.tricked.solidverdant.ui.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CalendarSwipeTest {
    @Test
    fun swiping_left_shows_the_next_page_and_right_the_previous() {
        assertEquals(CalendarSwipe.NEXT, calendarSwipeDirection(distancePx = -200f, thresholdPx = 100f, rtl = false))
        assertEquals(CalendarSwipe.PREVIOUS, calendarSwipeDirection(distancePx = 200f, thresholdPx = 100f, rtl = false))
    }

    @Test
    fun right_to_left_layouts_mirror_the_direction() {
        assertEquals(CalendarSwipe.PREVIOUS, calendarSwipeDirection(distancePx = -200f, thresholdPx = 100f, rtl = true))
        assertEquals(CalendarSwipe.NEXT, calendarSwipeDirection(distancePx = 200f, thresholdPx = 100f, rtl = true))
    }

    @Test
    fun short_drags_do_not_page() {
        assertNull(calendarSwipeDirection(distancePx = -99f, thresholdPx = 100f, rtl = false))
        assertNull(calendarSwipeDirection(distancePx = 40f, thresholdPx = 100f, rtl = true))
    }
}
